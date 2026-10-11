package com.gateway.api_gateway.service;

import com.gateway.api_gateway.dto.DashboardDtos.CompletenessResponse;
import com.gateway.api_gateway.entity.RequestLog;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

// This is the concrete instrumentation for the proposal's "Log completeness
// >= 99.9%" metric, run against a REAL Postgres container (not H2), because
// the whole point of the metric is to catch what happens under real
// concurrent load with a real @Async thread pool and a real database
// connection pool - both of which behave very differently from an
// in-memory stand-in.
//
// What this test does:
//   1. Spins up Postgres in Docker via Testcontainers.
//   2. Fires N concurrent calls to RequestLogService.logAsync(), simulating
//      N proxied requests all being logged off the request path at once.
//   3. Waits for the async writes to settle.
//   4. Asks LogCompletenessService what fraction of those N actually made
//      it into request_logs, and asserts it clears the 99.9% target.
//
// If this test starts failing, it's telling you something real: either the
// async thread pool is too small under this load, or the fire-and-forget
// write pattern is genuinely dropping rows - both are exactly the kind of
// thing this metric exists to catch before the pilot, not during it.
@SpringBootTest
@Testcontainers
class LogCompletenessIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("apigateway_test")
        .withUsername("test")
        .withPassword("test");

    @org.springframework.test.context.DynamicPropertySource
    static void configureProperties(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        // Let Hibernate create the schema for this throwaway test database
        // instead of requiring schema.sql to be applied manually first.
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        registry.add("app.jwt.secret", () -> "test_secret_at_least_32_bytes_long_______");
    }

    @Autowired
    private RequestLogService requestLogService;

    @Autowired
    private LogCompletenessService logCompletenessService;

    @Test
    void concurrentAsyncLogging_meetsCompletenessTarget() throws InterruptedException {
        long apiId = 1L;
        int requestCount = 2000; // "M >> N" per the proposal's own EA metric shape
        Instant windowStart = Instant.now().minusSeconds(5);

        ExecutorService driver = Executors.newFixedThreadPool(50);
        CountDownLatch latch = new CountDownLatch(requestCount);

        for (int i = 0; i < requestCount; i++) {
            String clientId = "load-test-client-" + (i % 10); // spread across a few clients
            RequestLog.Outcome outcome = (i % 5 == 0) ? RequestLog.Outcome.BLOCKED : RequestLog.Outcome.ALLOWED;
            driver.submit(() -> {
                try {
                    requestLogService.logAsync(apiId, clientId, outcome, outcome == RequestLog.Outcome.BLOCKED ? 429 : 200, 12L);
                } finally {
                    latch.countDown();
                }
            });
        }

        // Give the driver threads time to submit, then give the @Async pool
        // time to actually flush writes to Postgres before measuring.
        assertTrue(latch.await(30, TimeUnit.SECONDS), "Driver did not finish submitting in time");
        driver.shutdown();
        // Wait for the @Async pool to flush: poll until every row is in, or
        // give up after 20 s (a fixed sleep is either too slow or flaky).
        CompletenessResponse result;
        long deadline = System.currentTimeMillis() + 20_000;
        do {
            Thread.sleep(200);
            result = logCompletenessService.measure(apiId, requestCount, windowStart, Instant.now());
        } while (result.actualCount() < requestCount && System.currentTimeMillis() < deadline);
        final CompletenessResponse r = result;

        System.out.printf(
            "Log completeness: expected=%d actual=%d ratio=%.4f meetsTarget=%b%n",
            r.expectedCount(), r.actualCount(), r.completenessRatio(), r.meetsTarget()
        );

        assertTrue(
            r.completenessRatio() >= LogCompletenessService.TARGET_RATIO,
            () -> String.format(
                "Completeness %.4f fell below the %.3f target (%d/%d rows written)",
                r.completenessRatio(), LogCompletenessService.TARGET_RATIO,
                r.actualCount(), r.expectedCount()
            )
        );
    }
}
