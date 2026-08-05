package com.example.bookserver.payment;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.bookserver.common.InternalTokenGuard;

/**
 * A deploy-time check that this revision can actually reach the payment provider.
 *
 * <p>Nothing else establishes that. The provider's key is not validated when the client is built,
 * so a revision holding a wrong, stale, or unreadable secret starts cleanly, serves every other
 * request, and reveals the problem only when a customer presses pay. Between deploying and that
 * first customer, the pipeline can ask here instead.
 *
 * <p>It answers for the credentials this revision is really running with, which is what makes it
 * worth having on top of the tests: those exercise the code against a key held somewhere else,
 * and a green suite says nothing about the value Secret Manager handed to this container, the IAM
 * binding that let it read it, or the environment variable that carried it.
 *
 * <p>Guarded by the same shared token as the rest of {@code /internal/**}: it makes an outbound
 * call per request, so it is not something to leave open.
 */
@RestController
@RequestMapping("/internal/payment")
public class InternalPaymentController {

    private final PaymentGateway paymentGateway;
    private final InternalTokenGuard tokenGuard;

    public InternalPaymentController(PaymentGateway paymentGateway, InternalTokenGuard tokenGuard) {
        this.paymentGateway = paymentGateway;
        this.tokenGuard = tokenGuard;
    }

    /**
     * 200 when the provider accepts our credentials, 503 when it does not — a status a deploy
     * pipeline can fail on directly. 503 rather than 500 because the deployment is intact and the
     * dependency is what is unusable.
     */
    @GetMapping("/health")
    public ResponseEntity<String> health(
            @RequestHeader(value = "X-Internal-Token", required = false) String token) {
        if (!tokenGuard.matches(token)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        if (!paymentGateway.credentialsValid()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(paymentGateway.provider() + " rejected this deployment's credentials");
        }
        return ResponseEntity.ok(paymentGateway.provider() + " ok");
    }
}
