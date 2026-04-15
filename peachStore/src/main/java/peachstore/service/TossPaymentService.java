package peachstore.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;

import javax.servlet.http.HttpSession;

import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import peachstore.domain.OrderReceipt;
import peachstore.domain.SnapShot;
import peachstore.domain.Tosspayment;
import peachstore.domain.User;
import peachstore.dto.ConfirmPaymentRequest;
import peachstore.dto.PaymentErrorResponse;
import peachstore.dto.PaymentSessionData;
import peachstore.dto.TossConfirmResponse;
import peachstore.exception.TosspaymentException;
import peachstore.exception.UserException;
import peachstore.repository.tosspayment.TosspaymentDAO;
import peachstore.service.cart.CartItemService;
import peachstore.service.orderdetail.OrderDetailService;
import peachstore.service.orderreceipt.OrderReceiptService;

/**
 * 토스 결제 서비스
 */
@Slf4j
@Service
public class TossPaymentService {

	private final String secretKey = "test_sk_zXLkKEypNArWmo50nX3lmeaxYG5R";
	private final String tossUrl = "https://api.tosspayments.com/v1/payments/confirm";

	private final ObjectMapper objectMapper;
	private final OrderReceiptService orderReceiptService;
	private final TosspaymentDAO tosspaymentDAO;
	private final OrderDetailService orderDetailService;
	private final CartItemService cartItemService;

	// 생성자 주입
	public TossPaymentService(RestTemplate restTemplate, OrderReceiptService orderReceiptService,
			TosspaymentDAO tosspaymentDAO, OrderDetailService orderDetailService, CartItemService cartItemService) {
		this.orderReceiptService = orderReceiptService;
		this.tosspaymentDAO = tosspaymentDAO;
		this.orderDetailService = orderDetailService;
		this.cartItemService = cartItemService;

		this.objectMapper = new ObjectMapper();
		this.objectMapper.registerModule(new JavaTimeModule());
		this.objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
	}

	/**
	 * 결제 승인 요청 후 완료 처리
	 * @param request
	 * @param session
	 * @param paymentSessionData
	 * @return
	 */
	@Transactional
	public TossConfirmResponse handlePaymentAndSession(ConfirmPaymentRequest request, HttpSession session, PaymentSessionData paymentSessionData) {
		// 결제 승인 요청 응답 수신
		TossConfirmResponse response = confirmPayment(request.getPaymentKey(), request.getOrderId(), request.getAmount());
		
		// 결제 전 세션 내 금액(sessionData.getAmount())과 결제한 금액(request.getAmount())이 같은지
		if (paymentSessionData.getAmount() != request.getAmount()) {
			log.debug("결제 금액 오류. 취소 요청");
			
			try {
				PaymentErrorResponse cancelResponse = cancelPayment(request.getPaymentKey(), "결제 금액 불일치로 인한 자동 취소");
		        log.info("결제 취소 완료: {}", cancelResponse);
			} catch (IOException e) {
				log.error("결제 취소 요청 실패", e);
			}
			throw new TosspaymentException("결제 금액 정보가 유효하지 않습니다.");
		}
		
		OrderReceipt orderReceipt = null;
		try {
			// 결제 완료 DB 처리
			orderReceipt = processPaymentComplete(request, response, session, paymentSessionData);
		} catch (Exception e) {
			// DB 처리 실패로 인한 결제 취소
			try {
				cancelPayment(request.getPaymentKey(), "DB 처리 오류로 인한 자동 취소");
			} catch (IOException e1) {
				log.error("결제 취소 실패", e1);
			}
			throw e;
		}

		// 세션 정리
		cleanupPaymentSession(session, paymentSessionData, orderReceipt.getOrder_receipt_id());

		return response;
	}
	

	/**
	 * 결제 승인 후 완료 처리를 위한 데이터 insert
	 */
	public OrderReceipt processPaymentComplete(ConfirmPaymentRequest request, TossConfirmResponse tossResponse, HttpSession session, PaymentSessionData paymentSessionData) {
		// 세션 내 데이터 확인
		User user = (User) session.getAttribute("user");
		if (user == null) {
			throw new UserException("로그인한 사용자 정보가 세션에 없습니다.");
		}
		
		List<SnapShot> snapshotList = (List<SnapShot>)session.getAttribute("cartSnapshots");
		if (snapshotList == null || snapshotList.isEmpty()) {
			throw new IllegalStateException("장바구니 스냅샷이 세션에 없습니다.");
		}

		// 중복 결제 방지: 동일 paymentKey 가 이미 DB 에 존재하면 즉시 거부
		if (tosspaymentDAO.selectByPaymentKey(request.getPaymentKey()) != null) {
			log.warn("중복 결제 시도 감지. paymentKey={}", request.getPaymentKey());
			throw new TosspaymentException("이미 처리된 결제입니다.");
		}

		// 결제 정보 DB 저장
		Tosspayment tosspayment = insert(request.getOrderId(), request.getPaymentKey(), tossResponse.getMethod(),
				tossResponse.getStatus(), request.getAmount(), tossResponse.getApprovedAt().toLocalDateTime(),
				tossResponse.getRequestedAt().toLocalDateTime());

		// OrderReceipt DB 저장
		OrderReceipt orderReceipt = orderReceiptService.insert(
				tossResponse.getApprovedAt().toLocalDateTime(), 
				"상품 준비 전",
				user,
				tosspayment, 
				paymentSessionData.getAddressData().getPostCode(), 
				paymentSessionData.getAddressData().getAddress(), 
				paymentSessionData.getAddressData().getDetailAddress()
				);

		// OrderDetail DB 저장
		for (SnapShot snapShot : snapshotList) {
			orderDetailService.insert(orderReceipt.getOrder_receipt_id(), snapShot.getSnapshot_id());
		}

		// 결제되었으므로 장바구니의 상품 삭제
		cartItemService.deleteAllByUserId(user.getUser_id());

		log.debug("결제 완료 처리 완료. OrderReceipt ID: {}", orderReceipt.getOrder_receipt_id());

		return orderReceipt;
	}

	/**
	 * 결제 완료 후 세션 정리
	 */
	public void cleanupPaymentSession(HttpSession session, PaymentSessionData paymentSessionData, int orderReceiptId) {
	    if (paymentSessionData != null) {
	        paymentSessionData.setAddressData(null);  // 주소 정보 삭제
	    }

	    session.setAttribute("orderReceiptId", orderReceiptId);  // 주문 영수증 ID 저장

	    log.debug("결제 관련 세션 정리 완료");
	}

	/**
	 * 토스 결제 확정 API 호출 서비스 메서드
	 * 
	 * @param paymentKey 토스 결제 키
	 * @param orderId    주문 ID
	 * @param amount     결제 금액
	 * @return TossConfirmResponse 결제 확인 응답 DTO
	 * @throws RuntimeException
	 */
	public TossConfirmResponse confirmPayment(String paymentKey, String orderId, long amount) {
		log.debug("confirmPayment 진입");
		log.debug("paymentKey={}, orderId={}, amount={}", paymentKey, orderId, amount);

		// 최대 3번까지 재시도
		for (int attempt = 1; attempt <= 3; attempt++) {
			try {
				// ObjectMapper로 요청 JSON 객체 생성
				ObjectNode requestObj = objectMapper.createObjectNode();
				requestObj.put("paymentKey", paymentKey);
				requestObj.put("orderId", orderId);
				requestObj.put("amount", amount);

				String bodyJson = objectMapper.writeValueAsString(requestObj);

				// Basic Auth 헤더 생성
				String auth = secretKey + ":";
				String encodedAuth = Base64.getEncoder().encodeToString(auth.getBytes(StandardCharsets.US_ASCII));
				String authHeader = "Basic " + encodedAuth;

				log.debug("Toss Confirm 요청 URL: {}, 헤더 Authorization: {}, 요청 바디: {}", tossUrl, authHeader, bodyJson);

				OkHttpClient client = new OkHttpClient();
				okhttp3.RequestBody body = okhttp3.RequestBody.create(bodyJson,
						okhttp3.MediaType.get("application/json"));

				Request request = new Request.Builder().url(tossUrl).addHeader("Authorization", authHeader)
						.addHeader("Content-Type", "application/json").post(body).build();

				try (Response response = client.newCall(request).execute()) {
					int statusCode = response.code();
					String responseBody = response.body() != null ? response.body().string() : "";

					log.debug("OkHttp confirm status: {}, body: {}", statusCode, responseBody);

					if (statusCode == 200) {
						return objectMapper.readValue(responseBody, TossConfirmResponse.class);
					} else if (statusCode >= 500 && attempt < 3) {
						log.warn("Toss 서버 오류 ({}), {}번째 재시도 중...", statusCode, attempt);
						Thread.sleep(1000); // 1초 쉬고 재시도
						continue;
					} else {
						throw new RuntimeException("Toss Confirm API 호출 실패 " + statusCode);
					}
				}
			} catch (IOException e) {
				log.error("OkHttp 요청 중 IOException 발생", e);
				if (attempt >= 3) {
					throw new RuntimeException("Toss Confirm API 호출 중 오류 발생", e);
				}
				log.warn("IOException 발생으로 재시도 시도 중... ({}번째)", attempt);
				try {
					Thread.sleep(1000);
				} catch (InterruptedException ignored) {
				}
			} catch (Exception e) {
				log.error("Toss Confirm 처리 중 예외 발생", e);
				throw new RuntimeException("Toss Confirm 처리 실패", e);
			}
		}

		throw new RuntimeException("Toss Confirm API 호출 재시도 실패");
	}

	/**
	 * 토스에 결제 승인받기
	 * 
	 * @param confirmPaymentRequest
	 * @return
	 * @throws Exception
	 */
	private String getAuthorizations() {
		log.debug("getAuthorizations");
		String auth = secretKey + ":";
		return "Basic " + Base64.getEncoder().encodeToString(auth.getBytes());
	}

	/**
	 * 결제 정보 데이터베이스에 추가
	 * 
	 * @param tossOrderId
	 * @param tossPaymentKey
	 * @param tossPaymentMethod
	 * @param tossPaymentStatus
	 * @param totalAmount
	 * @param ApprovedAt
	 * @param requestedAt
	 * @return
	 */
	public Tosspayment insert(String tossOrderId, String tossPaymentKey, String tossPaymentMethod,
			String tossPaymentStatus, long totalAmount, LocalDateTime ApprovedAt, LocalDateTime requestedAt) {
		Tosspayment tosspayment = new Tosspayment();
		tosspayment.setTossOrderId(tossOrderId);
		tosspayment.setTossPaymentKey(tossPaymentKey);
		tosspayment.setTossPaymentMethod(tossPaymentMethod);
		tosspayment.setTossPaymentStatus(tossPaymentStatus);
		tosspayment.setTotalAmount(totalAmount);
		tosspayment.setApprovedAt(ApprovedAt);
		tosspayment.setRequestedAt(requestedAt);

		return tosspaymentDAO.insert(tosspayment);
	}

	/**
	 * 결제 취소 요청
	 */
	public PaymentErrorResponse cancelPayment(String paymentKey, String cancelReason) throws IOException {
		log.debug("cancelPayment");
		String cancelUrl = "https://api.tosspayments.com/v1/payments/" + paymentKey + "/cancel";

		// JSON 본문 구성
		String requestBody = "{\"cancelReason\": \"" + cancelReason + "\"}";

		HttpPost post = new HttpPost(cancelUrl);
		post.setHeader("Authorization", getAuthorizations());
		post.setHeader("Content-Type", "application/json");
		post.setEntity(new StringEntity(requestBody, "UTF-8"));

		try (CloseableHttpClient client = HttpClients.createDefault();
				CloseableHttpResponse response = client.execute(post)) {

			String responseBody = EntityUtils.toString(response.getEntity(), "UTF-8");
			
			ObjectMapper mapper = new ObjectMapper();
	        return mapper.readValue(responseBody, PaymentErrorResponse.class);
		}
	}
}
