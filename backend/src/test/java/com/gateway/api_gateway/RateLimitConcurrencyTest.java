package com.gateway.api_gateway;

import com.gateway.api_gateway.entity.Policy;
import com.gateway.api_gateway.gateway.RateLimiter;
import com.gateway.api_gateway.gateway.RouteCache;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Primary metric from proposal section 6.1 (enforcement accuracy):
// fire M >> N requests from ONE client through TWO independent "gateway
// instances" (two RateLimiters, each with its own Redis connection pool,
// exactly like two app servers) and check the admitted count is N within 2%.
@Testcontainers
public class RateLimitConcurrencyTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7"))
            .withExposedPorts(6379);

    private static RateLimiter newInstance() {
        RedisConfig cfg = new RedisConfig();
        var pool = cfg.jedisPool("redis://" + redis.getHost() + ":" + redis.getMappedPort(6379));
        return new RateLimiter(pool, cfg.proxyManager(pool), "open");
    }

    private static int admittedAcrossTwoInstances(Policy.Algorithm algorithm, long apiId) throws InterruptedException {
        int limit = 20;          // N
        int totalRequests = 400; // M, much greater than N
        // Long window, so no refill happens during the test.
        RouteCache.Route route = new RouteCache.Route(apiId, "https://example.com", "key", true,
                algorithm, limit, 3600, null);

        RateLimiter instanceA = newInstance();
        RateLimiter instanceB = newInstance();

        AtomicInteger admitted = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(32);
        CountDownLatch latch = new CountDownLatch(totalRequests);
        for (int i = 0; i < totalRequests; i++) {
            RateLimiter instance = (i % 2 == 0) ? instanceA : instanceB; // alternate instances
            pool.submit(() -> {
                try {
                    if (instance.check(route, "203.0.113.7").allowed()) admitted.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await();
        pool.shutdown();
        System.out.println(algorithm + ": admitted " + admitted.get() + " of " + totalRequests + " (limit " + limit + ")");
        return admitted.get();
    }

    @Test
    void tokenBucketIsExactAcrossTwoInstances() throws InterruptedException {
        int admitted = admittedAcrossTwoInstances(Policy.Algorithm.TOKEN_BUCKET, 1);
        assertTrue(Math.abs(admitted - 20) <= 1, "token bucket admitted " + admitted);
    }

    @Test
    void slidingWindowIsExactAcrossTwoInstances() throws InterruptedException {
        int admitted = admittedAcrossTwoInstances(Policy.Algorithm.SLIDING_WINDOW, 2);
        assertTrue(Math.abs(admitted - 20) <= 1, "sliding window admitted " + admitted);
    }

    // Risk section: the Redis-down path must behave as configured, and fast.
    @Test
    void failOpenAndFailClosedWhenRedisIsDown() {
        RedisConfig cfg = new RedisConfig();
        var deadPool = cfg.jedisPool("redis://127.0.0.1:1"); // nothing listens on port 1
        RouteCache.Route route = new RouteCache.Route(9, "https://example.com", "key", true,
                Policy.Algorithm.TOKEN_BUCKET, 5, 60, null);

        RateLimiter open = new RateLimiter(deadPool, cfg.proxyManager(deadPool), "open");
        assertTrue(open.check(route, "c").allowed(), "fail-open should allow");

        RateLimiter closed = new RateLimiter(deadPool, cfg.proxyManager(deadPool), "closed");
        assertEquals(false, closed.check(route, "c").allowed(), "fail-closed should block");

        // second call is answered by the circuit breaker without touching Redis
        long t0 = System.nanoTime();
        closed.check(route, "c");
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 50, "circuit breaker should answer instantly");
    }
}
