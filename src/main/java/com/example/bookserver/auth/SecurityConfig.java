package com.example.bookserver.auth;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Stateless JWT security. No sessions, no CSRF (the API carries auth in the
 * Authorization header, not an auto-attached cookie). Auth endpoints and the public
 * catalog <em>reads</em> are open; {@code /api/users/me/**}, {@code /api/cart/**},
 * {@code /api/orders/**} and {@code /api/addresses/**} require a valid access token;
 * catalog <em>writes</em>
 * (POST/PUT/DELETE on books and authors) require the {@code ADMIN} role. Unauthenticated
 * requests to a protected route get 401; authenticated-but-not-admin gets 403.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public JwtAuthenticationFilter jwtAuthenticationFilter(JwtProvider jwtProvider) {
        return new JwtAuthenticationFilter(jwtProvider);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   JwtAuthenticationFilter jwtAuthenticationFilter) throws Exception {
        http
                .cors(Customizer.withDefaults())   // uses the corsConfigurationSource bean below
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/**").permitAll()          // login / refresh / logout
                        .requestMatchers("/internal/**").permitAll()          // Cloud Scheduler jobs, guarded by a shared secret in the controller
                        .requestMatchers(HttpMethod.POST, "/api/users").permitAll()  // register
                        .requestMatchers("/api/users/**").authenticated()     // /users/me and below
                        .requestMatchers("/api/cart/**").authenticated()      // the caller's own cart
                        // seller/admin fulfillment transitions (must precede the generic /api/orders rule)
                        .requestMatchers(HttpMethod.POST, "/api/orders/*/prepare", "/api/orders/*/ship",
                                "/api/orders/*/deliver").hasRole("ADMIN")
                        .requestMatchers("/api/orders/**").authenticated()    // the caller's own orders (pay/cancel/confirm)
                        .requestMatchers("/api/addresses/**").authenticated() // the caller's own address book
                        // catalog writes are admin-only; reads (GET) stay public via anyRequest below
                        .requestMatchers(HttpMethod.POST, "/api/books/**", "/api/authors/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/api/books/**", "/api/authors/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/books/**", "/api/authors/**").hasRole("ADMIN")
                        .anyRequest().permitAll())                            // catalog reads + everything else
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /**
     * Allow the Swagger UI docs (served from GitHub Pages) to call the API from the browser.
     * Auth is header-based (Authorization: Bearer ...), not cookie-based, so credentials
     * are not allowed and only the headers/methods the API actually uses are permitted.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of("https://hbg1345.github.io"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type"));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }
}
