package com.example.bookserver;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer(DockerImageName.parse("postgres:latest"));
    }

    // NOTE: no PaymentGateway bean here on purpose. There is no fallback gateway in main any
    // more, so @SpringBootTest contexts wire the real Stripe adapter off the dummy key set by the
    // test task in build.gradle — which keeps those tests a guard that the production path is
    // constructible. Nothing reaches Stripe; a test needing payment behaviour overrides the
    // gateway locally (see PurchaseControllerIntegrationTest).

}
