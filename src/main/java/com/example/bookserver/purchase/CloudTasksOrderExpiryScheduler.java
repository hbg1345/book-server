package com.example.bookserver.purchase;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.google.cloud.tasks.v2.CloudTasksClient;
import com.google.cloud.tasks.v2.HttpMethod;
import com.google.cloud.tasks.v2.HttpRequest;
import com.google.cloud.tasks.v2.QueueName;
import com.google.cloud.tasks.v2.Task;
import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;

import jakarta.annotation.PreDestroy;

/**
 * Precise per-order expiry via Google Cloud Tasks: when an order is placed, enqueue a task
 * scheduled at {@code now + order.payment-timeout} that POSTs the single-order expiry
 * endpoint. The task carries the same {@code X-Internal-Token} shared secret as the sweep,
 * and the endpoint no-ops if the order was paid in the meantime — so a task firing for an
 * already-paid order is harmless.
 *
 * <p>Active only when {@code order.expiry.cloud-tasks.enabled=true} (prod), since it needs
 * GCP credentials and a provisioned queue. Scheduling is best-effort: any failure is logged
 * and swallowed, and the periodic {@link UnpaidOrderSweeper} still catches the order.
 */
@Component
@ConditionalOnProperty(name = "order.expiry.cloud-tasks.enabled", havingValue = "true")
public class CloudTasksOrderExpiryScheduler implements OrderExpiryScheduler {

    private static final Logger log = LoggerFactory.getLogger(CloudTasksOrderExpiryScheduler.class);

    private final CloudTasksClient client;
    private final String queuePath;
    private final String targetBaseUrl;
    private final String token;
    private final Duration paymentTimeout;

    public CloudTasksOrderExpiryScheduler(
            @Value("${gcp.project-id}") String projectId,
            @Value("${order.expiry.cloud-tasks.location}") String location,
            @Value("${order.expiry.cloud-tasks.queue}") String queue,
            @Value("${order.expiry.cloud-tasks.target-base-url}") String targetBaseUrl,
            @Value("${internal.sweep-token}") String token,
            @Value("${order.payment-timeout}") Duration paymentTimeout) throws Exception {
        this.client = CloudTasksClient.create();
        this.queuePath = QueueName.of(projectId, location, queue).toString();
        this.targetBaseUrl = targetBaseUrl;
        this.token = token;
        this.paymentTimeout = paymentTimeout;
    }

    @Override
    public void scheduleExpiry(UUID purchaseUuid) {
        try {
            Instant when = Instant.now().plus(paymentTimeout);
            HttpRequest request = HttpRequest.newBuilder()
                    .setUrl(targetBaseUrl + "/internal/orders/" + purchaseUuid + "/expire")
                    .setHttpMethod(HttpMethod.POST)
                    .putHeaders("X-Internal-Token", token)
                    .setBody(ByteString.EMPTY)
                    .build();
            Task task = Task.newBuilder()
                    .setHttpRequest(request)
                    .setScheduleTime(Timestamp.newBuilder()
                            .setSeconds(when.getEpochSecond())
                            .setNanos(when.getNano())
                            .build())
                    .build();
            client.createTask(queuePath, task);
        } catch (Exception e) {
            // best-effort: the periodic sweep is the safety net for a failed enqueue
            log.warn("Failed to schedule Cloud Tasks expiry for order {}; the sweep will still catch it",
                    purchaseUuid, e);
        }
    }

    @PreDestroy
    void close() {
        client.close();
    }
}
