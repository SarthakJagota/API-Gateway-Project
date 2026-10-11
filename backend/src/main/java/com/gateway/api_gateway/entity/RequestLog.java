package com.gateway.api_gateway.entity;

import jakarta.persistence.*;
import java.time.Instant;

// One row per proxied request. This table is what the dashboard queries and
// what the "log completeness >= 99.9%" metric is measured against, so writes
// here must be reliable but must NEVER sit on the client's request path -
// see service/RequestLogService.java for the async write.
@Entity
@Table(
    name = "request_logs",
    indexes = {
        // Matches the proposal's stated index: (api_id, timestamp)
        @Index(name = "idx_request_logs_api_timestamp", columnList = "api_id, timestamp")
    }
)
public class RequestLog {

    public enum Outcome { ALLOWED, BLOCKED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "api_id", nullable = false)
    private Long apiId;

    // Whatever identifies the caller of the registered API (API key, IP, etc.)
    // Deliberately a plain string, not a FK - the client isn't a User in our system.
    @Column(name = "client_identity", nullable = false)
    private String clientIdentity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Outcome outcome;

    @Column(nullable = false)
    private Instant timestamp = Instant.now();

    // Optional: latency added by the gateway itself, useful for the p50/p95 metric.
    @Column(name = "latency_ms")
    private Long latencyMs;

    // HTTP status sent back to the client (429 when blocked, else the origin's status).
    @Column(name = "status")
    private Integer status;

    public RequestLog() {}

    public RequestLog(Long apiId, String clientIdentity, Outcome outcome, Long latencyMs) {
        this(apiId, clientIdentity, outcome, null, latencyMs);
    }

    public RequestLog(Long apiId, String clientIdentity, Outcome outcome, Integer status, Long latencyMs) {
        this.apiId = apiId;
        this.clientIdentity = clientIdentity;
        this.outcome = outcome;
        this.status = status;
        this.latencyMs = latencyMs;
    }

    public Long getId() { return id; }
    public Long getApiId() { return apiId; }
    public String getClientIdentity() { return clientIdentity; }
    public Outcome getOutcome() { return outcome; }
    public Instant getTimestamp() { return timestamp; }
    public Long getLatencyMs() { return latencyMs; }
    public Integer getStatus() { return status; }
}
