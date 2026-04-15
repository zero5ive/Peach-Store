package peachstore.shop.controller;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpSession;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import peachstore.domain.SnapShot;
import peachstore.domain.User;
import peachstore.dto.ConfirmPaymentRequest;
import peachstore.dto.PaymentSessionData;
import peachstore.dto.TossConfirmResponse;
import peachstore.exception.TosspaymentException;
import peachstore.service.TossPaymentService;


@Controller
@Slf4j
@RequiredArgsConstructor
public class PaymentController {

	private final TossPaymentService tossPaymentService;

	/**
	 * 결제 품목 정보 보여주기
	 * @param model
	 * @param httpSession
	 * @return
	 */
	@GetMapping("/payment/payment-ready")
	public String getMethodName(Model model, HttpSession httpSession) {
		User user = (User) httpSession.getAttribute("user");
		List<SnapShot> snapshotList = (List<SnapShot>)httpSession.getAttribute("cartSnapshots");
		log.debug("스냅샷 리스트는======="+snapshotList);
		
		String successUrl = "http://localhost:8888/shop/payment/success-handler";
		String failUrl = "http://localhost:8888/shop/payment/fail";
		String orderId = String.valueOf(System.currentTimeMillis());

		log.debug("paymentPage orderId - {}", orderId);
		
		model.addAttribute("orderId", orderId);
		model.addAttribute("successUrl", successUrl);
		model.addAttribute("failUrl", failUrl);
		model.addAttribute("orderName", "주문번호 " + orderId + " 결제");
		model.addAttribute("snapshotList", snapshotList);
		
		model.addAttribute("user", user);
		return "/shop/payment/payment-ready";
	}
	
	/**
	 * 결제 전 입력한 정보를 세션에 저장
	 * @param paymentData
	 * @param session
	 * @return
	 */
	@PostMapping("/payment/save-address")
	@ResponseBody
	public ResponseEntity<Void> saveAddressToSession(
			@RequestBody PaymentSessionData paymentData, // JSON 요청 바디 받기
			HttpSession session) {
		
		// 세션 내에 결제 관련 정보를 저장
		session.setAttribute("paymentSessionData", paymentData);
		
		log.debug("Saved payment data in session: {}", paymentData);
		
		return ResponseEntity.ok().build();
	}

	/**
	 * 결제 완료 중간 단계로 이동
	 */
	@GetMapping("/payment/success-handler")
	public String successHandlerPage(@RequestParam String paymentKey, @RequestParam String orderId, @RequestParam long amount, Model model) {
		model.addAttribute("paymentKey", paymentKey);
		model.addAttribute("orderId", orderId);
		model.addAttribute("amount", amount);

		return "/shop/payment/success-handler";
	}

	
	/**
     * 결제 요청을 보내고 처리하는 메서드
     *
     * @param request
     * @return
     */
	@PostMapping("/payment/confirm")
	public ResponseEntity<?> successHandlerPageConfirm(
	        @RequestBody ConfirmPaymentRequest request,
	        HttpSession httpSession) {
	    
	    try {
	    	// 결제에 필요한 세션 데이터 객체
	        PaymentSessionData paymentData = (PaymentSessionData) httpSession.getAttribute("paymentSessionData");
	        
	        // 결제 수행
	        TossConfirmResponse response = tossPaymentService.handlePaymentAndSession(request, httpSession, paymentData);
	        
	        return ResponseEntity.ok(response);
	        
	    } catch (TosspaymentException e) {
	    	Map<String, String> errorResponse = new HashMap<>();
	    	errorResponse.put("error", e.getMessage());
	    	return ResponseEntity.badRequest().body(errorResponse);

	    } catch (Exception e) {
	    	  Map<String, String> errorResponse = new HashMap<>();
	          errorResponse.put("error", "서버 오류가 발생했습니다.");
	          return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
	    }
	}

	/**
	 * 결제 성공 페이지
	 * @return
	 */
	@GetMapping("/payment/success")
	public String successPage(HttpSession session, Model model) {
		Integer orderReceiptIdInt = (Integer) session.getAttribute("orderReceiptId");
		Long orderReceiptId = orderReceiptIdInt.longValue();

	    model.addAttribute("orderReceiptId", orderReceiptId);

	    return "/shop/payment/success";
	}


	/**
	 * 결제 실패 페이지
	 */
	@GetMapping("/payment/fail")
	public String failPage(@RequestParam Map<String, String> params, Model model) {
		model.addAllAttributes(params);
		return "/shop/payment/fail";
	}


}
