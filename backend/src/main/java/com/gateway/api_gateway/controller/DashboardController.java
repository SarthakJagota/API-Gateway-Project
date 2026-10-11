package com.gateway.api_gateway.controller;

import com.gateway.api_gateway.dto.DashboardDtos.*;
import com.gateway.api_gateway.entity.RegisteredApi;
import com.gateway.api_gateway.entity.RequestLog;
import com.gateway.api_gateway.repository.RequestLogRepository;
import com.gateway.api_gateway.service.ApiRegistrationService;
import com.gateway.api_gateway.service.LogCompletenessService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

// Everything the dashboard reads. Every endpoint only ever looks at the
// logged-in user's own APIs. Returns pre-aggregated numbers and paginated
// slices, never the whole log table.
@RestController
@RequestMapping("/api")
public class DashboardController {

    private final RequestLogRepository logs;
    private final ApiRegistrationService apis;
    private final LogCompletenessService logCompletenessService;

    public DashboardController(RequestLogRepository logs, ApiRegistrationService apis,
                               LogCompletenessService logCompletenessService) {
        this.logs = logs;
        this.apis = apis;
        this.logCompletenessService = logCompletenessService;
    }

    // Allowed vs blocked per API, last 24 hours.
    @GetMapping("/stats/summary")
    public SummaryResponse summary(Authentication auth) {
        Instant to = Instant.now();
        Instant from = to.minus(24, ChronoUnit.HOURS);
        List<ApiSummary> perApi = new ArrayList<>();
        for (RegisteredApi api : apis.ownedApis(auth.getName())) {
            long allowed = 0, blocked = 0;
            for (var row : logs.countByOutcome(api.getId(), from, to)) {
                if (row.getOutcome() == RequestLog.Outcome.ALLOWED) allowed = row.getCount();
                else blocked = row.getCount();
            }
            perApi.add(new ApiSummary(api.getId(), api.getName(), allowed, blocked));
        }
        return new SummaryResponse(perApi);
    }

    // 24 hourly points, oldest first. Optional apiId narrows it to one API.
    @GetMapping("/stats/timeseries")
    public List<HourPoint> timeseries(Authentication auth, @RequestParam(required = false) Long apiId) {
        List<Long> ids = apiIds(auth, apiId);
        long nowHour = Instant.now().getEpochSecond() / 3600;
        long firstHour = nowHour - 23;
        long[] allowed = new long[24], blocked = new long[24];

        if (!ids.isEmpty()) {
            for (Object[] row : logs.hourlyCounts(ids, Instant.ofEpochSecond(firstHour * 3600))) {
                int i = (int) (((Number) row[0]).longValue() - firstHour);
                if (i < 0 || i > 23) continue;
                long count = ((Number) row[2]).longValue();
                if ("ALLOWED".equals(String.valueOf(row[1]))) allowed[i] += count;
                else blocked[i] += count;
            }
        }
        List<HourPoint> out = new ArrayList<>();
        for (int i = 0; i < 24; i++) out.add(new HourPoint((firstHour + i) * 3600_000, allowed[i], blocked[i]));
        return out;
    }

    // Request log table. status=200 -> allowed only, status=429 -> blocked only.
    @GetMapping("/logs")
    public LogPage logs(Authentication auth,
                        @RequestParam(required = false) String status,
                        @RequestParam(required = false) Long apiId,
                        @RequestParam(defaultValue = "0") int page,
                        @RequestParam(defaultValue = "20") int size) {
        List<RegisteredApi> mine = apis.ownedApis(auth.getName());
        Map<Long, String> names = new HashMap<>();
        mine.forEach(a -> names.put(a.getId(), a.getName()));
        List<Long> ids = apiIds(auth, apiId);
        if (ids.isEmpty()) return new LogPage(List.of(), 0);

        PageRequest pr = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100));
        Page<RequestLog> result;
        if ("200".equals(status)) result = logs.findByApiIdInAndOutcomeOrderByTimestampDesc(ids, RequestLog.Outcome.ALLOWED, pr);
        else if ("429".equals(status)) result = logs.findByApiIdInAndOutcomeOrderByTimestampDesc(ids, RequestLog.Outcome.BLOCKED, pr);
        else result = logs.findByApiIdInOrderByTimestampDesc(ids, pr);

        List<LogEntry> items = result.getContent().stream().map(l -> new LogEntry(
            l.getId(), l.getTimestamp().toString(), l.getApiId(), names.get(l.getApiId()),
            l.getClientIdentity(), l.getOutcome().name(), l.getStatus(), l.getLatencyMs()
        )).toList();
        return new LogPage(items, result.getTotalElements());
    }

    // Log-completeness metric for one API. "expected" comes from the load
    // driver's own tally; this reports what fraction reached Postgres.
    @GetMapping("/stats/{apiId}/completeness")
    public CompletenessResponse completeness(
        Authentication auth,
        @PathVariable Long apiId,
        @RequestParam long expected,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to
    ) {
        apis.getOwned(auth.getName(), apiId); // 404 unless it's yours
        return logCompletenessService.measure(apiId, expected, from, to);
    }

    // The user's API ids, or just one of them (after checking it's theirs).
    private List<Long> apiIds(Authentication auth, Long apiId) {
        if (apiId != null) return List.of(apis.getOwned(auth.getName(), apiId).getId());
        return apis.ownedApis(auth.getName()).stream().map(RegisteredApi::getId).toList();
    }
}
