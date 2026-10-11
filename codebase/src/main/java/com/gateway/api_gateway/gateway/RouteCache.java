package com.gateway.api_gateway.gateway;

import com.gateway.api_gateway.entity.Policy;
import com.gateway.api_gateway.entity.RegisteredApi;
import com.gateway.api_gateway.repository.PolicyRepository;
import com.gateway.api_gateway.repository.RegisteredApiRepository;
import com.gateway.api_gateway.service.UrlValidationService;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// Looks up "which target + which policy" for a registered API.
// Cached for 30 s so the gateway doesn't hit Postgres on every request
// (that would blow the p95 latency budget). Edits from the dashboard call
// evict() so they apply immediately on this instance, and within 30 s on others.
@Service
public class RouteCache {

    public record Route(long apiId, String targetUrl, String apiKey, boolean active,
                        Policy.Algorithm algorithm, int limit, int windowSeconds, Integer burstCapacity) {}

    private record Entry(Route route, long loadedAt) {}

    private static final long TTL_MS = 30_000;

    private final Map<Long, Entry> cache = new ConcurrentHashMap<>();
    // Short names never change, so slug -> id can be cached forever.
    private final Map<String, Long> slugToId = new ConcurrentHashMap<>();
    private final PolicyRepository policyRepository;
    private final RegisteredApiRepository registeredApiRepository;
    private final UrlValidationService urlValidationService;

    public RouteCache(PolicyRepository policyRepository, RegisteredApiRepository registeredApiRepository,
                      UrlValidationService urlValidationService) {
        this.policyRepository = policyRepository;
        this.registeredApiRepository = registeredApiRepository;
        this.urlValidationService = urlValidationService;
    }

    /** Resolves the part after /g/ - either a short name ("weatherhello") or a number ("7"). */
    public Route resolve(String key) {
        if (key.matches("\\d{1,18}")) {
            return get(Long.parseLong(key));
        }
        Long id = slugToId.get(key);
        if (id == null) {
            id = registeredApiRepository.findBySlug(key).map(RegisteredApi::getId).orElse(null);
            if (id == null) return null;
            slugToId.put(key, id);
        }
        return get(id);
    }

    /** @return the route, or null if no such API (or its target is no longer allowed). */
    public Route get(long apiId) {
        Entry e = cache.get(apiId);
        if (e != null && System.currentTimeMillis() - e.loadedAt() < TTL_MS) {
            return e.route();
        }
        Route route = load(apiId);
        if (route == null) {
            cache.remove(apiId);
        } else {
            cache.put(apiId, new Entry(route, System.currentTimeMillis()));
        }
        return route;
    }

    public void evict(long apiId) {
        cache.remove(apiId);
    }

    private Route load(long apiId) {
        Policy p = policyRepository.findByRegisteredApiId(apiId).orElse(null);
        if (p == null) return null;
        RegisteredApi api = p.getRegisteredApi();
        try {
            // Re-check the target on every reload, not just at registration,
            // so a DNS name later pointed at 127.0.0.1 gets refused.
            urlValidationService.validateOrThrow(api.getTargetUrl());
        } catch (IllegalArgumentException ex) {
            return null;
        }
        return new Route(api.getId(), api.getTargetUrl(), api.getGatewayApiKey(), api.isActive(),
                p.getAlgorithm(), p.getRequestsPerWindow(), p.getWindowSeconds(), p.getBurstCapacity());
    }
}
