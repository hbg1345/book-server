package com.example.bookserver.payment;

import org.springframework.stereotype.Component;

/**
 * Default {@link PaymentGateway} for environments where no real provider is wired yet (the Toss
 * adapter lands in #25 PR-2). It declines every charge, so the application still starts and no
 * order is ever "paid" by a fake success in a real deployment. A real adapter — or a test mock —
 * replaces it when present.
 */
@Component
public class StubPaymentGateway implements PaymentGateway {

    @Override
    public ChargeResult confirm(ChargeRequest request) {
        return ChargeResult.failed("payment gateway not configured");
    }
}
