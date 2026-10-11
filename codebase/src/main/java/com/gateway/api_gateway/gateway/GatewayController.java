package com.gateway.api_gateway.gateway;

import com.gateway.api_gateway.entity.RequestLog;
import com.gateway.api_gateway.service.RequestLogService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.Set;

// The request path from the proposal (section 4.2):
//   client -> /g/{apiId}/anything  (with X-API-Key)
//   -> look up the registered API and its policy
//   -> atomic consume-or-reject in Redis
//   -> allowed: forward to targetUrl/anything and relay the response as-is
//      blocked: 429 + Retry-After
//   -> always: write a log row (async, off the request path)
@RestController
public class GatewayController {

    // Headers that describe one connection, not the request itself; they must
    // not be copied across a proxy. x-api-key is our credential, not the origin's.
    private static final Set<String> SKIP_HEADERS = Set.of(
            "connection", "keep-alive", "transfer-encoding", "te", "trailer", "upgrade",
            "proxy-authorization", "proxy-authenticate", "host", "content-length", "expect",
            "x-api-key");

    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER) // relay redirects, don't follow them
            .build();

    private final RouteCache routes;
    private final RateLimiter rateLimiter;
    private final RequestLogService requestLogService;

    public GatewayController(RouteCache routes, RateLimiter rateLimiter, RequestLogService requestLogService) {
        this.routes = routes;
        this.rateLimiter = rateLimiter;
        this.requestLogService = requestLogService;
    }

    // {key} is the API's short name (/g/weatherhello/...) or, for older APIs, its number (/g/7/...).
    @RequestMapping("/g/{key}/**")
    public void proxy(@PathVariable("key") String key, HttpServletRequest req, HttpServletResponse res) throws IOException {
        long start = System.nanoTime();

        RouteCache.Route route = routes.resolve(key);
        if (route == null || !route.active()) {
            sendText(res, 404, "Unknown API");
            return;
        }
        long apiId = route.apiId();
        if (!route.apiKey().equals(req.getHeader("X-API-Key"))) {
            sendText(res, 401, "Missing or wrong X-API-Key header");
            return;
        }

        // Real client IP. Behind Render/Railway's load balancer this is taken
        // from X-Forwarded-For (see server.forward-headers-strategy).
        String clientId = req.getRemoteAddr();

        RateLimiter.Decision decision = rateLimiter.check(route, clientId);
        if (!decision.allowed()) {
            res.setHeader("Retry-After", String.valueOf(decision.retryAfterSeconds()));
            sendText(res, 429, "Rate limit exceeded. Try again later.");
            requestLogService.logAsync(apiId, clientId, RequestLog.Outcome.BLOCKED, 429, elapsedMs(start, 0));
            return;
        }

        long upstreamNanos = 0;
        int status;
        try {
            HttpRequest outgoing = buildOutgoing(route, req);
            long t0 = System.nanoTime();
            HttpResponse<byte[]> incoming = http.send(outgoing, HttpResponse.BodyHandlers.ofByteArray());
            upstreamNanos = System.nanoTime() - t0;

            status = incoming.statusCode();
            res.setStatus(status);
            incoming.headers().map().forEach((name, values) -> {
                if (!SKIP_HEADERS.contains(name.toLowerCase())) values.forEach(v -> res.addHeader(name, v));
            });
            res.getOutputStream().write(incoming.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            status = 502;
            if (!res.isCommitted()) sendText(res, 502, "Target API did not respond");
        } catch (IOException | IllegalArgumentException e) {
            status = 502;
            if (!res.isCommitted()) sendText(res, 502, "Could not reach the target API");
        }

        // latencyMs = time the GATEWAY added (total minus time spent waiting on
        // the origin) - that's the "added latency p50/p95" metric.
        requestLogService.logAsync(apiId, clientId, RequestLog.Outcome.ALLOWED, status, elapsedMs(start, upstreamNanos));
    }

    private HttpRequest buildOutgoing(RouteCache.Route route, HttpServletRequest req) throws IOException {
        // "/g/7/orders/42" -> "/orders/42"
        String uri = req.getRequestURI();
        int slash = uri.indexOf('/', "/g/".length());
        String rest = slash < 0 ? "" : uri.substring(slash);
        String query = req.getQueryString() == null ? "" : "?" + req.getQueryString();
        String base = route.targetUrl().replaceAll("/+$", "");

        byte[] body = req.getInputStream().readAllBytes();
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + rest + query))
                .timeout(Duration.ofSeconds(30))
                .method(req.getMethod(), body.length == 0
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofByteArray(body));

        for (String name : Collections.list(req.getHeaderNames())) {
            if (SKIP_HEADERS.contains(name.toLowerCase())) continue;
            for (String value : Collections.list(req.getHeaders(name))) b.header(name, value);
        }
        return b.build();
    }

    private static long elapsedMs(long start, long excludeNanos) {
        return (System.nanoTime() - start - excludeNanos) / 1_000_000;
    }

    private static void sendText(HttpServletResponse res, int status, String msg) throws IOException {
        res.setStatus(status);
        res.setContentType("text/plain;charset=UTF-8");
        res.getOutputStream().write(msg.getBytes(StandardCharsets.UTF_8));
    }
}
