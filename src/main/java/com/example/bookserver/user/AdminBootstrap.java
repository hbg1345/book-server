package com.example.bookserver.user;

import java.time.LocalDate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Optionally seeds an ADMIN account on startup so there is a way to actually obtain the
 * ADMIN role (self-registration only ever yields USER). Disabled by default; enable it
 * for local/docker runs with {@code app.admin.bootstrap.enabled=true} and supply
 * {@code user-id}/{@code password}. Idempotent: on restart it re-asserts the role on the
 * existing account rather than creating a duplicate. It is intentionally NOT enabled on
 * the public deployment, so the live catalog has no admin and stays read-only to callers.
 */
@Component
@ConditionalOnProperty(prefix = "app.admin.bootstrap", name = "enabled", havingValue = "true")
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final UserService userService;
    private final String userId;
    private final String password;
    private final String name;
    private final String phone;
    private final LocalDate birthDate;

    public AdminBootstrap(UserService userService,
                          @Value("${app.admin.bootstrap.user-id:}") String userId,
                          @Value("${app.admin.bootstrap.password:}") String password,
                          @Value("${app.admin.bootstrap.name:Administrator}") String name,
                          @Value("${app.admin.bootstrap.phone:000-0000-0000}") String phone,
                          @Value("${app.admin.bootstrap.birth-date:2000-01-01}") LocalDate birthDate) {
        this.userService = userService;
        this.userId = userId;
        this.password = password;
        this.name = name;
        this.phone = phone;
        this.birthDate = birthDate;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!StringUtils.hasText(userId) || !StringUtils.hasText(password)) {
            log.warn("Admin bootstrap is enabled but user-id/password are not set; skipping.");
            return;
        }
        userService.ensureAdminAccount(userId, password, name, phone, birthDate);
        log.info("Admin bootstrap ensured ADMIN account for user-id '{}'.", userId);
    }
}
