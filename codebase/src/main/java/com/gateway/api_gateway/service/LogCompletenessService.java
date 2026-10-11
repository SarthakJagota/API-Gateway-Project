package com.gateway.api_gateway.service;

import com.gateway.api_gateway.dto.DashboardDtos.CompletenessResponse;
import com.gateway.api_gateway.repository.RequestLogRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;

// Direct instrumentation for the proposal's stated secondary metric:
// "Log completeness: proportion of driver-issued requests appearing in the
// PostgreSQL log with the correct outcome. Target: >= 99.9%."
//
// This class does NOT know how many requests were actually issued - that
// number has to come from whoever is driving the load (a load-test script,
// or the gateway itself keeping a request counter). What it does is take
// that "expected" count, compare it against what's actually sitting in
// request_logs for the same window, and report the ratio - which is exactly
// what the pilot validation plan in the proposal calls for.
@Service
public class LogCompletenessService {

    public static final double TARGET_RATIO = 0.999;

    private final RequestLogRepository requestLogRepository;

    public LogCompletenessService(RequestLogRepository requestLogRepository) {
        this.requestLogRepository = requestLogRepository;
    }

    public CompletenessResponse measure(Long apiId, long expectedCount, Instant from, Instant to) {
        long actualCount = requestLogRepository.countByApiIdAndTimestampBetween(apiId, from, to);

        double ratio = expectedCount == 0 ? 1.0 : (double) actualCount / expectedCount;
        boolean meetsTarget = ratio >= TARGET_RATIO;

        return new CompletenessResponse(apiId, expectedCount, actualCount, ratio, meetsTarget);
    }
}
