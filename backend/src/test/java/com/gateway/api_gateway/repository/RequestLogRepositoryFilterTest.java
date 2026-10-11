package com.gateway.api_gateway.repository;

import com.gateway.api_gateway.entity.RequestLog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Covers the filtering gap called out for the dashboard logs endpoint:
// outcome filter, client-identity filter, and date-range filter, each
// checked independently against a real Postgres instance.
// @SpringBootTest instead of @DataJpaTest: in Spring Boot 4 the JPA test
// slice lives in a separate starter, and this keeps the dependencies simple.
@SpringBootTest
@Testcontainers
class RequestLogRepositoryFilterTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("apigateway_test")
        .withUsername("test")
        .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
    }

    @Autowired
    private RequestLogRepository requestLogRepository;

    private static final long API_ID = 42L;

    @BeforeEach
    void seed() {
        requestLogRepository.deleteAll();
        requestLogRepository.save(new RequestLog(API_ID, "client-a", RequestLog.Outcome.ALLOWED, 10L));
        requestLogRepository.save(new RequestLog(API_ID, "client-b", RequestLog.Outcome.BLOCKED, 5L));
        requestLogRepository.save(new RequestLog(API_ID, "client-a", RequestLog.Outcome.BLOCKED, 8L));
        // A different API's row should never show up in API_ID's filtered results.
        requestLogRepository.save(new RequestLog(999L, "client-a", RequestLog.Outcome.ALLOWED, 3L));
    }

    @Test
    void filtersByOutcome() {
        var page = requestLogRepository.findFiltered(
            API_ID, RequestLog.Outcome.BLOCKED, null, null, null, PageRequest.of(0, 10));

        assertEquals(2, page.getTotalElements());
        page.forEach(log -> assertEquals(RequestLog.Outcome.BLOCKED, log.getOutcome()));
    }

    @Test
    void filtersByClientIdentity() {
        var page = requestLogRepository.findFiltered(
            API_ID, null, "client-a", null, null, PageRequest.of(0, 10));

        assertEquals(2, page.getTotalElements());
        page.forEach(log -> assertTrue(log.getClientIdentity().contains("client-a")));
    }

    @Test
    void filtersByDateRange() {
        Instant future = Instant.now().plus(1, ChronoUnit.DAYS);
        var page = requestLogRepository.findFiltered(
            API_ID, null, null, future, null, PageRequest.of(0, 10));

        // No rows have a timestamp after "future", so this should come back empty.
        assertEquals(0, page.getTotalElements());
    }

    @Test
    void neverLeaksAcrossApiId() {
        var page = requestLogRepository.findFiltered(
            API_ID, null, null, null, null, PageRequest.of(0, 10));

        assertEquals(3, page.getTotalElements());
    }
}
