<%@ page contentType="text/html; charset=UTF-8" %>
<%
String paymentKey = request.getParameter("paymentKey");
String orderId = request.getParameter("orderId");
String amountStr = request.getParameter("amount");
String orderReceiptId = request.getParameter("orderReceiptId");

long amount = Long.parseLong(amountStr);
%>
<!DOCTYPE html>
<html lang="ko">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>결제 승인 중 - Peach Store</title>
<style>
* {
    margin: 0;
    padding: 0;
    box-sizing: border-box;
}

body {
    font-family: 'SF Pro Display', -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif;
    background: linear-gradient(135deg, #f093fb 0%, #f5576c 100%);
    color: #1d1d1f;
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    min-height: 100vh;
    text-align: center;
    padding: 20px;
    overflow: hidden;
}

.processing-container {
    background: rgba(255, 255, 255, 0.95);
    backdrop-filter: blur(20px);
    border-radius: 24px;
    padding: 60px 40px;
    max-width: 420px;
    width: 100%;
    box-shadow: 0 20px 40px rgba(0, 0, 0, 0.1);
    border: 1px solid rgba(255, 255, 255, 0.2);
    position: relative;
    overflow: hidden;
}

.processing-container::before {
    content: '';
    position: absolute;
    top: 0;
    left: 0;
    right: 0;
    height: 4px;
    background: linear-gradient(90deg, #f093fb, #f5576c);
}

.loading-spinner {
    width: 80px;
    height: 80px;
    margin: 0 auto 32px;
    position: relative;
}

.spinner {
    width: 100%;
    height: 100%;
    border: 4px solid #f5f5f7;
    border-top: 4px solid #0071e3;
    border-radius: 50%;
    animation: spin 1.2s cubic-bezier(0.25, 0.46, 0.45, 0.94) infinite;
}

@keyframes spin {
    0% { 
        transform: rotate(0deg);
        border-top-color: #0071e3;
    }
    25% { 
        border-top-color: #00d4aa;
    }
    50% { 
        transform: rotate(180deg);
        border-top-color: #ff9500;
    }
    75% { 
        border-top-color: #ff3b30;
    }
    100% { 
        transform: rotate(360deg);
        border-top-color: #0071e3;
    }
}

.processing-title {
    font-size: 28px;
    font-weight: 700;
    margin-bottom: 16px;
    color: #1d1d1f;
    letter-spacing: -0.3px;
    line-height: 1.2;
}

.processing-subtitle {
    font-size: 17px;
    font-weight: 400;
    color: #86868b;
    margin-bottom: 32px;
    line-height: 1.4;
}

.progress-info {
    background: #f5f5f7;
    border-radius: 16px;
    padding: 24px;
    margin-bottom: 32px;
}

.progress-step {
    display: flex;
    align-items: center;
    margin-bottom: 16px;
    font-size: 15px;
}

.progress-step:last-child {
    margin-bottom: 0;
}

.step-icon {
    width: 20px;
    height: 20px;
    border-radius: 50%;
    margin-right: 12px;
    display: flex;
    align-items: center;
    justify-content: center;
    font-size: 12px;
    font-weight: bold;
}

.step-completed {
    background: #34d399;
    color: white;
}

.step-processing {
    background: #0071e3;
    color: white;
    animation: pulse 1.5s ease-in-out infinite;
}

.step-pending {
    background: #e5e5e7;
    color: #86868b;
}

@keyframes pulse {
    0%, 100% { 
        opacity: 1;
        transform: scale(1);
    }
    50% { 
        opacity: 0.7;
        transform: scale(1.1);
    }
}

.step-text {
    color: #1d1d1f;
    font-weight: 500;
}

.step-text.completed {
    color: #86868b;
}

.step-text.processing {
    color: #0071e3;
    font-weight: 600;
}

.security-info {
    font-size: 13px;
    color: #86868b;
    line-height: 1.5;
    padding: 20px;
    background: rgba(0, 113, 227, 0.05);
    border-radius: 12px;
    border-left: 3px solid #0071e3;
}

.security-icon {
    display: inline-block;
    margin-right: 6px;
    font-size: 14px;
}

.dots-animation {
    display: inline-block;
    animation: dots 1.5s steps(5, end) infinite;
}

@keyframes dots {
    0%, 20% { 
        color: transparent;
        text-shadow: .25em 0 0 transparent, .5em 0 0 transparent;
    }
    40% { 
        color: #86868b;
        text-shadow: .25em 0 0 transparent, .5em 0 0 transparent;
    }
    60% { 
        text-shadow: .25em 0 0 #86868b, .5em 0 0 transparent;
    }
    80%, 100% { 
        text-shadow: .25em 0 0 #86868b, .5em 0 0 #86868b;
    }
}

/* Floating particles animation */
.particles {
    position: absolute;
    top: 0;
    left: 0;
    width: 100%;
    height: 100%;
    pointer-events: none;
    overflow: hidden;
    z-index: -1;
}

.particle {
    position: absolute;
    width: 4px;
    height: 4px;
    background: rgba(0, 113, 227, 0.2);
    border-radius: 50%;
    animation: float 6s ease-in-out infinite;
}

.particle:nth-child(1) { left: 10%; animation-delay: 0s; }
.particle:nth-child(2) { left: 20%; animation-delay: 1s; }
.particle:nth-child(3) { left: 30%; animation-delay: 2s; }
.particle:nth-child(4) { left: 40%; animation-delay: 3s; }
.particle:nth-child(5) { left: 50%; animation-delay: 4s; }
.particle:nth-child(6) { left: 60%; animation-delay: 5s; }

@keyframes float {
    0%, 100% { 
        transform: translateY(100vh) scale(0);
        opacity: 0;
    }
    10% { 
        opacity: 1;
        transform: scale(1);
    }
    90% { 
        opacity: 1;
        transform: scale(1);
    }
    100% { 
        transform: translateY(-100px) scale(0);
        opacity: 0;
    }
}

/* Responsive */
@media (max-width: 768px) {
    .processing-container {
        padding: 40px 24px;
        margin: 20px;
        border-radius: 20px;
    }
    
    .processing-title {
        font-size: 24px;
    }
    
    .processing-subtitle {
        font-size: 15px;
    }
}

@media (max-width: 480px) {
    body {
        padding: 16px;
    }
    
    .processing-container {
        padding: 32px 20px;
    }
    
    .progress-info {
        padding: 20px;
    }
}
</style>
</head>
<body>
    <div class="particles">
        <div class="particle"></div>
        <div class="particle"></div>
        <div class="particle"></div>
        <div class="particle"></div>
        <div class="particle"></div>
        <div class="particle"></div>
    </div>

    <div class="processing-container">
        <div class="loading-spinner">
            <div class="spinner"></div>
        </div>
        
        <h1 class="processing-title">결제 승인 중입니다<span class="dots-animation">...</span></h1>
        <p class="processing-subtitle">잠시만 기다려 주세요. 결제를 안전하게 처리하고 있습니다.</p>
        
        <div class="progress-info">
            <div class="progress-step">
                <div class="step-icon step-completed">✓</div>
                <span class="step-text completed">결제 정보 확인</span>
            </div>
            <div class="progress-step">
                <div class="step-icon step-processing">2</div>
                <span class="step-text processing">결제 승인 중</span>
            </div>
            <div class="progress-step">
                <div class="step-icon step-pending">3</div>
                <span class="step-text">주문 완료</span>
            </div>
        </div>
        
        <div class="security-info">
            <span class="security-icon">🔒</span>
            <strong>안전한 결제</strong><br>
            모든 결제 정보는 암호화로 보호되며, 개인정보는 안전하게 처리됩니다.
        </div>
    </div>

    <script>
    const paymentKey = '<%=paymentKey%>';
    const orderId = '<%=orderId%>';
    const amount = <%=amount%>;

        fetch("/shop/payment/confirm", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ paymentKey, orderId, amount })
        })
        .then(res => {
            if (!res.ok) throw new Error(res.status);
            window.location.href = "/shop/payment/success?orderReceiptId=" + orderId + "&amount=" + amount;
        })
        .catch(() => {
            window.location.href = "/shop/payment/fail?orderId=" + orderId + "&amount=" + amount;
        });
    
    </script>
</body>
</html>