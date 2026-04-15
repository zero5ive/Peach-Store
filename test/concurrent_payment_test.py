"""
동시 결제 중복 방지 테스트 (Peach-Store)

목적: 같은 세션으로 /payment/confirm 에 동시 요청 N건을 보내서, 
DB에 toss_payment 레코드가 1건만 생성되는지 검증한다.

방법:
1. 서버를 실행한다.
2. 브라우저에서 로그인, 장바구니, 결제 준비 → 결제창 결제 완료까지 진행한다.
3. success-handler 리디렉션 URL 에서 paymentKey, orderId, amount 를 복사한다.
    (이 시점에서 /payment/confirm 은 아직 호출 안 된 상태)
4. 브라우저 개발자도구에서 JSESSIONID 값을 복사한다.

의존 라이브러리 설치:
python -m pip install requests mysql-connector-python
"""

import threading
import time
import requests
import mysql.connector
from concurrent.futures import ThreadPoolExecutor, as_completed

# ========================================
# 설정
# ========================================
BASE_URL = "http://localhost:8888/shop"

# DB 접속 정보 (결과 검증용)
DB_CONFIG = {
    "host": "localhost",
    "port": 3306,
    "database": "electronicmall",
    "user": "electronicmall",
    "password": "1234",
}

# 동시 요청 수
CONCURRENT_REQUESTS = 15


# ========================================
# 브라우저 세션 쿠키로 Session 생성
# ========================================
def build_session(jsessionid: str) -> requests.Session:
    """
    브라우저의 JSESSIONID 를 사용해 Session 을 만든다.

    requests 의 http.cookiejar 는 localhost 도메인 쿠키를 RFC 정책상 거부하므로
    Cookie 헤더를 직접 세팅해 반드시 전송되도록 한다.
    """
    session = requests.Session()
    session.headers.update({"Cookie": f"JSESSIONID={jsessionid}"})
    print(f"[SESSION] JSESSIONID={jsessionid}")
    return session


# ========================================
# 동시 요청 실행
# ========================================
def send_confirm(
    session: requests.Session,
    payment_key: str,
    order_id: str,
    amount: int,
    thread_no: int,
) -> dict:
    """
    /payment/confirm 에 단일 요청을 보내고 결과를 반환한다.
    모든 스레드가 같은 requests.Session(=같은 JSESSIONID)을 공유한다.
    """
    payload = {
        "paymentKey": payment_key,
        "orderId": order_id,
        "amount": amount,
        "orderReceiptId": 0,
        "cartItemList": [],
    }
    start = time.perf_counter()
    try:
        resp = session.post(
            f"{BASE_URL}/payment/confirm",
            json=payload,
            timeout=30,
            allow_redirects=False,  # 302 리다이렉트를 추적하지 않고 그대로 반환 (세션 문제 감지용)
        )
        elapsed = time.perf_counter() - start
        try:
            body = resp.json() if resp.content else {}
        except Exception:
            # JSON 이 아닌 경우(HTML 리다이렉트 등) 앞 120자만 표시
            body = " ".join(resp.text.split())[:100] if resp.text else "(empty)"
        return {
            "thread": thread_no,
            "status": resp.status_code,
            "body": body,
            "elapsed_ms": round(elapsed * 1000),
        }
    except Exception as exc:
        elapsed = time.perf_counter() - start
        return {
            "thread": thread_no,
            "status": "ERROR",
            "body": str(exc),
            "elapsed_ms": round(elapsed * 1000),
        }


def run_concurrent(
    session: requests.Session,
    payment_key: str,
    order_id: str,
    amount: int,
    n: int = CONCURRENT_REQUESTS,
) -> list[dict]:
    """
    n 개의 스레드가 배리어로 동기화해 동시에 /payment/confirm 을 호출한다.
    """
    results = []
    barrier = threading.Barrier(n)

    def task(thread_no: int):
        barrier.wait()  # 모든 스레드가 준비될 때까지 대기 후 동시 출발
        return send_confirm(session, payment_key, order_id, amount, thread_no)

    with ThreadPoolExecutor(max_workers=n) as executor:
        futures = [executor.submit(task, i + 1) for i in range(n)]
        for future in as_completed(futures):
            results.append(future.result())

    results.sort(key=lambda r: r["thread"])
    return results


# ========================================
# DB 검증
# ========================================
def verify_db(payment_key: str) -> dict:
    """
    toss_payment / order_receipt 테이블에서 해당 paymentKey 의 레코드 수를 확인
    """
    conn = mysql.connector.connect(**DB_CONFIG)
    cursor = conn.cursor(dictionary=True)

    cursor.execute(
        "SELECT COUNT(*) AS cnt FROM toss_payment WHERE toss_payment_key = %s",
        (payment_key,),
    )
    payment_count = cursor.fetchone()["cnt"]

    cursor.execute(
        """
        SELECT COUNT(*) AS cnt
        FROM order_receipt r
        JOIN toss_payment p ON r.payment_id = p.payment_id
        WHERE p.toss_payment_key = %s
        """,
        (payment_key,),
    )
    receipt_count = cursor.fetchone()["cnt"]

    cursor.close()
    conn.close()
    return {"toss_payment_records": payment_count, "order_receipt_records": receipt_count}


# ========================================
# 메인
# ========================================
def main():
    print("=" * 60)
    print("Peach-Store 동시 결제 중복 방지 테스트")
    print("=" * 60)
    print()
    print("준비단계")
    print("  1. 브라우저에서 로그인 → 장바구니 → 결제 준비 → Toss 결제창 결제 완료")
    print("  2. 리디렉션된 success-handler URL 에서 paymentKey / orderId / amount 복사")
    print("     예) /payment/success-handler?paymentKey=XXX&orderId=YYY&amount=ZZZ")
    print("  3. F12 → Application → Cookies → JSESSIONID 값 복사")
    print()

    PAYMENT_KEY  = input("paymentKey 입력  : ").strip()
    ORDER_ID     = input("orderId 입력     : ").strip()
    AMOUNT       = int(input("amount 입력 (숫자만): ").strip())
    JSESSIONID   = input("JSESSIONID 입력  : ").strip()

    print(f"\n{'=' * 60}")
    print(f"  테스트 대상  : {BASE_URL}/payment/confirm")
    print(f"  동시 요청 수 : {CONCURRENT_REQUESTS}")
    print(f"  paymentKey   : {PAYMENT_KEY}")
    print(f"  JSESSIONID   : {JSESSIONID[:12]}...")
    print(f"{'=' * 60}\n")

    # 브라우저 세션 그대로 사용
    session = build_session(JSESSIONID)

    # 동시 요청
    print(f"[TEST] {CONCURRENT_REQUESTS}개 스레드 동시 요청 시작...\n")
    results = run_concurrent(session, PAYMENT_KEY, ORDER_ID, AMOUNT)

    # 결과 출력
    print(f"{'스레드':>6}  {'HTTP':>6}  {'응답(ms)':>8}  응답 바디")
    print("-" * 75)
    success_count = 0
    for r in results:
        is_json_success = isinstance(r["body"], dict) and "status" in r["body"]
        flag = " ◀ 결제 성공" if is_json_success else ""
        if is_json_success:
            success_count += 1
        body_preview = str(r["body"])[:60]
        print(f"  {r['thread']:>4}  {str(r['status']):>6}  {r['elapsed_ms']:>7}ms  {body_preview}{flag}")

    # DB 검증
    print(f"\n{'=' * 60}")
    print("[DB 검증]")
    try:
        db_result = verify_db(PAYMENT_KEY)
        tp = db_result["toss_payment_records"]
        or_ = db_result["order_receipt_records"]
        print(f"  toss_payment  레코드 수 : {tp}건")
        print(f"  order_receipt 레코드 수 : {or_}건")
        passed = (tp == 1 and or_ == 1)
        if passed:
            print(f"\n  결과 : PASS ✓  중복 없음 — 레코드 정확히 1건 확인됨")
        else:
            print(f"\n  결과 : FAIL ✗  예상(1건) 과 다름 — 중복 또는 미생성 감지!")
    except Exception as e:
        print(f"  DB 검증 실패 (연결 오류): {e}")
        print(f"  결제 성공 JSON 응답 수 : {success_count} / {CONCURRENT_REQUESTS}  (기대값: 1)")
    print(f"{'=' * 60}\n")


if __name__ == "__main__":
    main()
