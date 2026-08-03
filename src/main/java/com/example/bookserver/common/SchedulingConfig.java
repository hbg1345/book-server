package com.example.bookserver.common;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on Spring's scheduled-task support so {@code @Scheduled} methods run — currently
 * the unpaid-order expiry sweep ({@link com.example.bookserver.purchase.UnpaidOrderSweeper}).
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
