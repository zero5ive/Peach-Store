# 동시 결제 중복 방지 테스트

동일 세션으로 `/payment/confirm` 에 동시 요청 15건을 보냈을 때 DB 에 결제 레코드가 1건만 생성되는지 확인한다.

---

## Python 으로 작성한 이유

JUnit 으로 작성할 경우 Toss API 를 Mock 으로 대체해야 한다. 그렇게 하면 "Toss 서버가 중복 paymentKey 를 실제로 거부하는지"를 검증할 수 없다.

Python + `requests` 는 브라우저에서 얻은 JSESSIONID 를 그대로 Cookie 헤더에 붙여 실서버에 요청을 날릴 수 있어서, 실제 결제 흐름을 재현할 수 있다.

---

## 중복 방지 구조

```
클라이언트 (15개 스레드, 동일 JSESSIONID)
        │  POST /shop/payment/confirm
        ▼
[LoginCheckFilter] — 세션에 user 없으면 차단
        │
        ▼
[PaymentController]
        │
        ▼
[TossPaymentService.handlePaymentAndSession()]  @Transactional
   1. 세션 금액 일치 검증
   2. Toss Payments API 결제 확정 요청   ← Toss 서버가 중복 paymentKey 거부
   3. selectByPaymentKey() 로 DB 중복 확인
   4. toss_payment / order_receipt / order_detail INSERT
   5. 장바구니 삭제
```

`@Transactional` 은 `TossPaymentService.handlePaymentAndSession()` 에 선언되어 있으므로 DB 처리 중 예외가 나면 전체 롤백된다.

---

## 테스트 실행 방법

```bash
pip install requests mysql-connector-python
python test/concurrent_payment_test.py
```

1. 브라우저에서 로그인 → 장바구니 → Toss 결제창 결제 완료
2. 리다이렉션된 `success-handler` URL 에서 `paymentKey` / `orderId` / `amount` 복사
3. DevTools → Application → Cookies → `JSESSIONID` 복사
4. 스크립트 실행 후 값 입력

---

## 테스트 결과 (2026-04-15)

```
   스레드    HTTP    응답(ms)  응답 바디
---------------------------------------------------------------------------
     1     500     3205ms  {'error': '서버 오류가 발생했습니다.'}
     ...   (2~11, 13~15 동일)
    12     200     3400ms  {'method': '간편결제', 'status': 'DONE', ...} ◀ 결제 성공
    13     500     3201ms  {'error': '서버 오류가 발생했습니다.'}
    ...

[DB 검증]
  toss_payment  레코드 수 : 1건
  order_receipt 레코드 수 : 1건
  결과 : PASS ✓
```

스레드 12만 성공, 나머지 14건은 Toss API 가 동일 paymentKey 를 거부해 500 반환. 
DB 에는 정확히 1건만 생성되었다.

---

## 트러블슈팅

**테스트 초기에 15건 전부 HTTP 200 인데 DB 0건**

Python `requests` 가 RFC 쿠키 정책 때문에 `localhost` 도메인에 쿠키를 붙이지 않는다. 
JSESSIONID 가 빠진 요청이 `LoginCheckFilter` 에서 로그인 페이지로 302 리다이렉트되고, `requests` 가 이를 자동으로 따라서 로그인 HTML + 200 을 반환하게 되었다.

```python
# 수정 전
session.cookies.set("JSESSIONID", jsessionid, domain="localhost", path="/")

# 수정 후
session.headers.update({"Cookie": f"JSESSIONID={jsessionid}"})
```

---

## 남은 한계
- `@Transactional` 범위가 Toss API 외부 호출까지 포함되어 있기 때문에 트래픽이 늘면 DB 커넥션 점유 시간이 길어질 수 있다.
