package com.example.bookserver;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards that Flyway actually runs on application startup. This is deliberately
 * independent of the {@code @Sql("/reset.sql")} mechanism the other tests use:
 * it asserts on {@code flyway_schema_history}, a table only Flyway creates. If the
 * Flyway autoconfiguration is missing (e.g. only {@code flyway-core} on the
 * classpath without {@code spring-boot-starter-flyway}), this table won't exist
 * and the test fails — which is exactly the production bug it protects against.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class FlywayMigrationIntegrationTest {

    @Autowired
    private DataSource dataSource;

    @Test
    void flyway_appliesV1_onStartup() throws Exception {
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement();
             var rs = statement.executeQuery(
                     "SELECT success FROM flyway_schema_history WHERE version = '1'")) {
            assertThat(rs.next()).as("flyway_schema_history has a V1 row").isTrue();
            assertThat(rs.getBoolean("success")).as("V1 migration succeeded").isTrue();
        }
    }
}
