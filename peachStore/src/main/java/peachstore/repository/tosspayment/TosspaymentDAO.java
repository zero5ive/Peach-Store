package peachstore.repository.tosspayment;

import peachstore.domain.Tosspayment;

/**
 * 토스 페이먼트 DAO 인터페이스입니다
 * @author 김예진
 * @since 2025-08-03
 */
public interface TosspaymentDAO {
	
	/**
	 * 결제정보 insert
	 * @param tosspayment
	 * @return
	 */
	public Tosspayment insert(Tosspayment tosspayment);

	/**
	 * paymentKey 로 결제 정보 조회 (중복 결제 방지용)
	 * @param tossPaymentKey
	 * @return 존재하면 Tosspayment, 없으면 null
	 */
	public Tosspayment selectByPaymentKey(String tossPaymentKey);

}
