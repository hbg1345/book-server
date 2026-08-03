package com.example.bookserver.purchase;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Cancels orders that were placed but never paid within the payment window, releasing the
 * stock they had reserved. Stock is reserved at {@link PurchaseService#placeOrder}, and
 * without this an abandoned {@code PAYMENT_PENDING} order would hold that stock forever.
 *
 * <p>This is invoked externally rather than by an in-process timer: the app runs on Cloud
 * Run with scale-to-zero, so a {@code @Scheduled} loop would not fire while no instance is
 * up (and would run on every instance when several are). Instead Cloud Scheduler calls the
 * internal endpoint ({@link InternalOrderController}) on a cron, which spins an instance up,
 * runs {@link #sweep()}, and lets it scale back down. The cancel + stock restore reuses
 * {@link PurchaseService#expireUnpaidOrder}, so state history and inventory stay consistent
 * with a manual cancel.
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

    /** Cancel every unpaid order past the payment window; returns how many were cancelled. */
    public int sweep() {
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
        return cancelled;
    }
}
