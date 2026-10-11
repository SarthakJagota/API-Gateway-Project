package com.gateway.api_gateway.dto;

import java.util.List;

public class DashboardDtos {

    public record ApiSummary(Long apiId, String name, long allowed, long blocked) {}

    public record SummaryResponse(List<ApiSummary> perApi) {}

    // One point on the 24-hour chart. t = start of that hour (epoch ms).
    public record HourPoint(long t, long allowed, long blocked) {}

    public record LogEntry(
        Long id,
        String ts,
        Long apiId,
        String apiName,
        String client,
        String outcome,   // ALLOWED / BLOCKED
        Integer status,   // HTTP status returned to the client
        Long latencyMs
    ) {}

    public record LogPage(List<LogEntry> items, long total) {}

    public record CompletenessResponse(
        Long apiId,
        long expectedCount,
        long actualCount,
        double completenessRatio,
        boolean meetsTarget // true once completenessRatio >= 0.999
    ) {}

    public record HealthResponse(
        String redis,       // UP / DOWN
        String failPolicy,  // open / closed
        long p50,           // ms added by the gateway, last 24 h
        long p95,
        double throughput   // requests per second over the last minute
    ) {}

    public record SettingsRequest(String failPolicy) {}
}
