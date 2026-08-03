package com.example.bookserver.purchase;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Default scheduler used when Cloud Tasks is off (local {@code bootRun}/docker, tests, CI):
 * it schedules nothing, so per-order expiry is handled entirely by the periodic
 * {@link UnpaidOrderSweeper}. Active unless {@code order.expiry.cloud-tasks.enabled=true}.
 */
@Component
@ConditionalOnProperty(name = "order.expiry.cloud-tasks.enabled", havingValue = "false", matchIfMissing = true)
public class NoopOrderExpiryScheduler implements OrderExpiryScheduler {

    private static final Logger log = LoggerFactory.getLogger(NoopOrderExpiryScheduler.class);

    @Override
    public void scheduleExpiry(UUID purchaseUuid) {
        log.debug("Cloud Tasks disabled; order {} expiry left to the periodic sweep", purchaseUuid);
    }
}
