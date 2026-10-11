package com.gateway.api_gateway.controller;

import com.gateway.api_gateway.dto.DashboardDtos.HealthResponse;
import com.gateway.api_gateway.dto.DashboardDtos.SettingsRequest;
import com.gateway.api_gateway.entity.RegisteredApi;
import com.gateway.api_gateway.gateway.RateLimiter;
import com.gateway.api_gateway.repository.RequestLogRepository;
import com.gateway.api_gateway.service.ApiRegistrationService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

// System page: Redis health, fail policy, added latency and throughput.
@RestController
@RequestMapping("/api/system")
public class SystemController {

    private final RateLimiter rateLimiter;
    private final RequestLogRepository logs;
    private final ApiRegistrationService apis;

    public SystemController(RateLimiter rateLimiter, RequestLogRepository logs, ApiRegistrationService apis) {
        this.rateLimiter = rateLimiter;
        this.logs = logs;
        this.apis = apis;
    }

    @GetMapping("/health")
    public HealthResponse health(Authentication auth) {
        List<Long> ids = apis.ownedApis(auth.getName()).stream().map(RegisteredApi::getId).toList();
        long p50 = 0, p95 = 0;
        double throughput = 0;
        if (!ids.isEmpty()) {
            List<Object[]> rows = logs.latencyPercentiles(ids, Instant.now().minus(24, ChronoUnit.HOURS));
            if (!rows.isEmpty() && rows.get(0)[0] != null) {
                p50 = Math.round(((Number) rows.get(0)[0]).doubleValue());
                p95 = Math.round(((Number) rows.get(0)[1]).doubleValue());
            }
            throughput = logs.countByApiIdInAndTimestampAfter(ids, Instant.now().minusSeconds(60)) / 60.0;
        }
        return new HealthResponse(rateLimiter.isRedisUp() ? "UP" : "DOWN",
                rateLimiter.getFailPolicy(), p50, p95, Math.round(throughput * 10) / 10.0);
    }

    @PutMapping("/settings")
    public Map<String, String> settings(@RequestBody SettingsRequest request) {
        rateLimiter.setFailPolicy(request.failPolicy());
        return Map.of("failPolicy", rateLimiter.getFailPolicy());
    }
}
