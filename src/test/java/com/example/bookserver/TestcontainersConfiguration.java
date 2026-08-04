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

    // NOTE: no PaymentGateway bean here on purpose. @SpringBootTest contexts wire the real main
    // bean (StubPaymentGateway) so those tests double as a guard that the app can start in prod;
    // a test needing a successful charge overrides the gateway locally (see
    // PurchaseControllerIntegrationTest).

}
