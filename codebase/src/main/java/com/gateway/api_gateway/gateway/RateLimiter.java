package com.gateway.api_gateway.gateway;

import com.gateway.api_gateway.entity.Policy;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

// Decides allow / block for one request. All counting state lives in Redis,
// so any number of gateway instances share the same budget.
@Service
public class RateLimiter {

    public record Decision(boolean allowed, long retryAfterSeconds) {}

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);
    private static final Decision ALLOW = new Decision(true, 0);

    // True sliding window (a "sliding log"): keep one timestamp per admitted
    // request in a sorted set, drop the ones older than the window, and admit
    // only if fewer than `limit` remain. Runs as ONE Lua script, so Redis
    // executes it atomically - two gateway instances can't both see the
    // same free slot. Uses Redis's own clock so instances' clocks can't disagree.
    // Returns -1 if admitted, otherwise milliseconds until a slot frees up.
    static final String SLIDING_WINDOW_LUA = """
            local key    = KEYS[1]
            local window = tonumber(ARGV[1])
            local limit  = tonumber(ARGV[2])
            local t      = redis.call('TIME')
            local now    = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
            redis.call('ZREMRANGEBYSCORE', key, 0, now - window)
            if redis.call('ZCARD', key) < limit then
              redis.call('ZADD', key, now, ARGV[3])
              redis.call('PEXPIRE', key, window)
              return -1
            end
            local oldest = redis.call('ZRANGE', key, 0, 0, 'WITHSCORES')
            return tonumber(oldest[2]) + window - now
            """;

    private final JedisPool jedisPool;
    private final ProxyManager<byte[]> buckets;

    // "open" = let traffic through unlimited if Redis is down (keeps the API up)
    // "closed" = reject traffic if Redis is down (keeps the protection)
    // Changed from the System page; kept per instance to stay simple.
    private volatile String failPolicy;
    private volatile long redisDownUntil = 0;

    public RateLimiter(JedisPool jedisPool, ProxyManager<byte[]> buckets,
                       @Value("${app.ratelimit.fail-policy:open}") String failPolicy) {
        this.jedisPool = jedisPool;
        this.buckets = buckets;
        this.failPolicy = failPolicy;
    }

    public Decision check(RouteCache.Route route, String clientId) {
        // After a Redis failure, skip Redis for 5 s instead of timing out on
        // every single request (a tiny circuit breaker).
        if (System.currentTimeMillis() < redisDownUntil) {
            return whenRedisDown();
        }
        try {
            return route.algorithm() == Policy.Algorithm.SLIDING_WINDOW
                    ? slidingWindow(route, clientId)
                    : tokenBucket(route, clientId);
        } catch (Exception e) {
            redisDownUntil = System.currentTimeMillis() + 5_000;
            log.warn("Redis unreachable, failing {}: {}", failPolicy, e.getMessage());
            return whenRedisDown();
        }
    }

    private Decision whenRedisDown() {
        return "closed".equals(failPolicy) ? new Decision(false, 5) : ALLOW;
    }

    private Decision tokenBucket(RouteCache.Route r, String clientId) {
        // Bucket holds up to `capacity` tokens (the burst) and refills at
        // limit / windowSeconds. Burst defaults to the limit if not set.
        int capacity = r.burstCapacity() != null ? r.burstCapacity() : r.limit();
        BucketConfiguration config = BucketConfiguration.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(capacity)
                        .refillGreedy(r.limit(), Duration.ofSeconds(r.windowSeconds()))
                        .build())
                .build();
        // The policy numbers are part of the key, so editing a policy starts
        // fresh buckets instead of reusing ones built with the old numbers.
        String key = "tb:" + r.apiId() + ":" + r.limit() + "/" + r.windowSeconds() + "/" + capacity + ":" + clientId;

        ConsumptionProbe probe = buckets.builder()
                .build(key.getBytes(StandardCharsets.UTF_8), () -> config)
                .tryConsumeAndReturnRemaining(1);

        if (probe.isConsumed()) return ALLOW;
        // Real wait until the next token, not the whole window.
        return new Decision(false, toSeconds(probe.getNanosToWaitForRefill() / 1_000_000));
    }

    private Decision slidingWindow(RouteCache.Route r, String clientId) {
        String key = "sw:" + r.apiId() + ":" + clientId;
        try (Jedis jedis = jedisPool.getResource()) {
            Object result = jedis.eval(SLIDING_WINDOW_LUA, List.of(key), List.of(
                    String.valueOf(r.windowSeconds() * 1000L),
                    String.valueOf(r.limit()),
                    UUID.randomUUID().toString()));
            long waitMs = ((Number) result).longValue();
            return waitMs < 0 ? ALLOW : new Decision(false, toSeconds(waitMs));
        }
    }

    private static long toSeconds(long ms) {
        return Math.max(1, (ms + 999) / 1000); // round up, at least 1 s
    }

    public boolean isRedisUp() {
        try (Jedis jedis = jedisPool.getResource()) {
            return "PONG".equals(jedis.ping());
        } catch (Exception e) {
            return false;
        }
    }

    public String getFailPolicy() { return failPolicy; }

    public void setFailPolicy(String policy) {
        if (!"open".equals(policy) && !"closed".equals(policy)) {
            throw new IllegalArgumentException("failPolicy must be 'open' or 'closed'");
        }
        this.failPolicy = policy;
    }
}
