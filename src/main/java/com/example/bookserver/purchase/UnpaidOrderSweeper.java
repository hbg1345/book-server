package com.example.bookserver.purchase;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically cancels orders that were placed but never paid within the payment window,
 * releasing the stock they had reserved. Stock is reserved at {@link PurchaseService#placeOrder},
 * and without this sweep an abandoned {@code PAYMENT_PENDING} order would hold that stock
 * forever. The actual cancel + restore reuses {@link PurchaseService#expireUnpaidOrder},
 * so the state history and inventory stay consistent with a manual cancel.
 */
@Component
public class UnpaidOrderSweeper {

    private static final Logger log = LoggerFactory.getLogger(UnpaidOrderSweeper.class);

    private final PurchaseService purchaseService;
    private final Duration paymentTimeout;

    public UnpaidOrderSweeper(PurchaseService purchaseService,
                              @Value("${order.payment-timeout}") Duration paymentTimeout) {
        this.purchaseService = purchaseService;
        this.paymentTimeout = paymentTimeout;
    }

    @Scheduled(fixedDelayString = "${order.expiry-sweep-interval}")
    public void sweep() {
        LocalDateTime cutoff = LocalDateTime.now().minus(paymentTimeout);
        List<UUID> expired = purchaseService.findUnpaidOrdersBefore(cutoff);
        int cancelled = 0;
        for (UUID purchaseUuid : expired) {
            try {
                purchaseService.expireUnpaidOrder(purchaseUuid);   // per-order tx: one failure doesn't stop the rest
                cancelled++;
            } catch (Exception e) {
                log.warn("Failed to expire unpaid order {}", purchaseUuid, e);
            }
        }
        if (cancelled > 0) {
            log.info("Expired {} unpaid order(s) past the {} payment window", cancelled, paymentTimeout);
        }
    }
}
