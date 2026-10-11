package com.gateway.api_gateway.entity;

import jakarta.persistence.*;

// The budget + algorithm choice for a registered API.
// Person 1's Bucket4j/Redis layer reads this to configure the bucket/window;
// this table is just the durable source of truth for what the policy IS.
@Entity
@Table(name = "policies")
public class Policy {

    public enum Algorithm { TOKEN_BUCKET, SLIDING_WINDOW }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(optional = false)
    @JoinColumn(name = "api_id", nullable = false, unique = true)
    private RegisteredApi registeredApi;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Algorithm algorithm = Algorithm.TOKEN_BUCKET;

    // requests allowed per windowSeconds
    @Column(name = "requests_per_window", nullable = false)
    private int requestsPerWindow;

    @Column(name = "window_seconds", nullable = false)
    private int windowSeconds;

    // Only meaningful for TOKEN_BUCKET; burst capacity above the steady rate.
    @Column(name = "burst_capacity")
    private Integer burstCapacity;

    public Policy() {}

    public Long getId() { return id; }
    public RegisteredApi getRegisteredApi() { return registeredApi; }
    public void setRegisteredApi(RegisteredApi registeredApi) { this.registeredApi = registeredApi; }
    public Algorithm getAlgorithm() { return algorithm; }
    public void setAlgorithm(Algorithm algorithm) { this.algorithm = algorithm; }
    public int getRequestsPerWindow() { return requestsPerWindow; }
    public void setRequestsPerWindow(int requestsPerWindow) { this.requestsPerWindow = requestsPerWindow; }
    public int getWindowSeconds() { return windowSeconds; }
    public void setWindowSeconds(int windowSeconds) { this.windowSeconds = windowSeconds; }
    public Integer getBurstCapacity() { return burstCapacity; }
    public void setBurstCapacity(Integer burstCapacity) { this.burstCapacity = burstCapacity; }
}
