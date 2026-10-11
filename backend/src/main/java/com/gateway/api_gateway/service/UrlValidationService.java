package com.gateway.api_gateway.service;

import org.springframework.stereotype.Service;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;

// Proxy-abuse mitigation from the proposal: only forward to public targets.
// Blocks the SSRF trick of registering a "target API" that's really
// localhost, a private-network address, or a cloud metadata endpoint.
// Called at registration AND whenever the gateway reloads a route.
@Service
public class UrlValidationService {

    public void validateOrThrow(String rawUrl) {
        URI uri;
        try {
            uri = new URI(rawUrl);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Malformed target URL: " + rawUrl);
        }

        if (uri.getScheme() == null || !(uri.getScheme().equals("http") || uri.getScheme().equals("https"))) {
            throw new IllegalArgumentException("Target URL must use http or https");
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("Target URL must include a host");
        }

        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host); // check EVERY address, not just the first
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("Could not resolve target host: " + host);
        }

        for (InetAddress a : addresses) {
            if (isPrivate(a)) {
                throw new IllegalArgumentException(
                    "Target URL resolves to a non-public address and cannot be registered");
            }
        }
    }

    private static boolean isPrivate(InetAddress a) {
        return a.isLoopbackAddress()      // 127.0.0.0/8, ::1
            || a.isLinkLocalAddress()     // 169.254.0.0/16 incl. cloud metadata 169.254.169.254
            || a.isSiteLocalAddress()     // 10/8, 172.16/12, 192.168/16
            || a.isAnyLocalAddress()      // 0.0.0.0
            || a.isMulticastAddress()
            // IPv6 private range fc00::/7 (Java's checks miss it; AWS IPv6 metadata lives here)
            || (a instanceof Inet6Address && (a.getAddress()[0] & 0xFE) == 0xFC);
    }
}
