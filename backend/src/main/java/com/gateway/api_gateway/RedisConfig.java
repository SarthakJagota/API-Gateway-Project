package com.gateway.api_gateway;

import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.jedis.Bucket4jJedis;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.net.URI;
import java.time.Duration;

@Configuration
public class RedisConfig {

    // REDIS_URL works for local Docker (redis://localhost:6379) and for
    // managed Redis on Render/Railway (rediss://user:pass@host:port).
    @Bean
    public JedisPool jedisPool(@Value("${app.redis.url}") String redisUrl) {
        GenericObjectPoolConfig<Jedis> pool = new GenericObjectPoolConfig<>();
        pool.setMaxTotal(64);                       // enough for concurrent load tests
        pool.setMaxWait(Duration.ofMillis(500));    // never block a request forever
        // 500 ms socket timeout: if Redis is down we fail fast instead of
        // adding seconds of latency to every request.
        return new JedisPool(pool, URI.create(redisUrl), 500);
    }

    @Bean
    public ProxyManager<byte[]> proxyManager(JedisPool jedisPool) {
        return Bucket4jJedis.casBasedBuilder(jedisPool)
                // Delete a client's bucket from Redis once it would be full again,
                // so idle clients don't pile up in Redis forever.
                .expirationAfterWrite(ExpirationAfterWriteStrategy
                        .basedOnTimeForRefillingBucketUpToMax(Duration.ofSeconds(10)))
                .build();
    }
}
