package com.example.bookserver;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.example.bookserver.payment.FakePaymentGateway;
import com.example.bookserver.payment.PaymentGateway;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer(DockerImageName.parse("postgres:latest"));
    }

    // No real payment provider in tests: the domain depends on the PaymentGateway port, so an
    // in-memory fake (succeeds by default) stands in for @SpringBootTest contexts.
    @Bean
    PaymentGateway paymentGateway() {
        return new FakePaymentGateway();
    }

}
