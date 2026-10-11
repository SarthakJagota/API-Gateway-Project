package com.gateway.api_gateway.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

// One row per API a developer has registered behind the gateway.
// The gatewayApiKey is what clients present when calling through the proxy;
// Person 1's gateway layer looks this up to resolve which target + policy applies.
@Entity
@Table(name = "registered_apis")
public class RegisteredApi {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    private User owner;

    @Column(nullable = false)
    private String name;

    // Short, unique, URL-friendly name chosen by the owner, e.g. "weatherhello".
    // Shows up in the gateway endpoint: /g/weatherhello/...
    // Nullable so APIs registered before this feature still work (by number).
    @Column(unique = true)
    private String slug;

    // Validated on registration - see service/UrlValidationService.java
    // to block SSRF / internal-network targets (proxy-abuse mitigation).
    @Column(name = "target_url", nullable = false)
    private String targetUrl;

    @Column(name = "gateway_api_key", nullable = false, unique = true)
    private String gatewayApiKey = UUID.randomUUID().toString();

    @Column(nullable = false)
    private boolean active = true;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    public RegisteredApi() {}

    public Long getId() { return id; }
    public User getOwner() { return owner; }
    public void setOwner(User owner) { this.owner = owner; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getSlug() { return slug; }
    public void setSlug(String slug) { this.slug = slug; }
    public String getTargetUrl() { return targetUrl; }
    public void setTargetUrl(String targetUrl) { this.targetUrl = targetUrl; }
    public String getGatewayApiKey() { return gatewayApiKey; }
    public void rotateKey() { this.gatewayApiKey = UUID.randomUUID().toString(); }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public Instant getCreatedAt() { return createdAt; }
}
