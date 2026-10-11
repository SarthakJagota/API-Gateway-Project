package com.gateway.api_gateway.repository;

import com.gateway.api_gateway.entity.RequestLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface RequestLogRepository extends JpaRepository<RequestLog, Long> {

    // Paginated raw log view, filterable by API.
    Page<RequestLog> findByApiIdOrderByTimestampDesc(Long apiId, Pageable pageable);

    // Full filtered view for one API: outcome, client identity (partial match)
    // and a date range are all optional - pass null for any filter you don't want.
    @Query("""
        SELECT r FROM RequestLog r
        WHERE r.apiId = :apiId
          AND (:outcome IS NULL OR r.outcome = :outcome)
          AND (:clientIdentity IS NULL OR r.clientIdentity LIKE %:clientIdentity%)
          AND (:from IS NULL OR r.timestamp >= :from)
          AND (:to IS NULL OR r.timestamp <= :to)
        ORDER BY r.timestamp DESC
        """)
    Page<RequestLog> findFiltered(@Param("apiId") Long apiId,
                                   @Param("outcome") RequestLog.Outcome outcome,
                                   @Param("clientIdentity") String clientIdentity,
                                   @Param("from") Instant from,
                                   @Param("to") Instant to,
                                   Pageable pageable);

    // Dashboard log table across several of the user's APIs.
    Page<RequestLog> findByApiIdInOrderByTimestampDesc(Collection<Long> apiIds, Pageable pageable);

    Page<RequestLog> findByApiIdInAndOutcomeOrderByTimestampDesc(Collection<Long> apiIds,
                                                                RequestLog.Outcome outcome,
                                                                Pageable pageable);

    // Total row count for one API in a window - the "actually logged" side
    // of the log-completeness ratio.
    long countByApiIdAndTimestampBetween(Long apiId, Instant from, Instant to);

    // Requests in the last N seconds - used for throughput.
    long countByApiIdInAndTimestampAfter(Collection<Long> apiIds, Instant since);

    // Allowed-vs-blocked counts for one API, aggregated in the database so
    // raw logs never ship to the browser.
    @Query("""
        SELECT r.outcome as outcome, COUNT(r) as count
        FROM RequestLog r
        WHERE r.apiId = :apiId AND r.timestamp BETWEEN :from AND :to
        GROUP BY r.outcome
        """)
    List<OutcomeCount> countByOutcome(@Param("apiId") Long apiId,
                                       @Param("from") Instant from,
                                       @Param("to") Instant to);

    interface OutcomeCount {
        RequestLog.Outcome getOutcome();
        long getCount();
    }

    // Hourly traffic for the chart. Each row: [hour number since 1970, outcome, count].
    @Query(value = """
        SELECT CAST(FLOOR(EXTRACT(EPOCH FROM "timestamp") / 3600) AS BIGINT) AS hr,
               outcome, COUNT(*) AS cnt
        FROM request_logs
        WHERE api_id IN (:apiIds) AND "timestamp" >= :since
        GROUP BY hr, outcome
        """, nativeQuery = true)
    List<Object[]> hourlyCounts(@Param("apiIds") Collection<Long> apiIds, @Param("since") Instant since);

    // p50 / p95 of the latency the gateway itself added. Row: [p50, p95].
    @Query(value = """
        SELECT percentile_cont(0.5)  WITHIN GROUP (ORDER BY latency_ms),
               percentile_cont(0.95) WITHIN GROUP (ORDER BY latency_ms)
        FROM request_logs
        WHERE api_id IN (:apiIds) AND "timestamp" >= :since AND latency_ms IS NOT NULL
        """, nativeQuery = true)
    List<Object[]> latencyPercentiles(@Param("apiIds") Collection<Long> apiIds, @Param("since") Instant since);
}
