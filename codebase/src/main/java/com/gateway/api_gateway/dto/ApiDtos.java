package com.gateway.api_gateway.dto;

import com.gateway.api_gateway.entity.Policy;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public class ApiDtos {

    public record RegisterApiRequest(
        @NotBlank String name,
        @NotBlank String targetUrl,
        @NotNull Policy.Algorithm algorithm,
        @Min(1) int limit,          // requests allowed ...
        @Min(1) int windowSeconds,  // ... per this many seconds
        @Min(1) Integer burstCapacity // optional, only used for TOKEN_BUCKET
    ) {}

    public record UpdatePolicyRequest(
        @NotNull Policy.Algorithm algorithm,
        @Min(1) int limit,
        @Min(1) int windowSeconds,
        @Min(1) Integer burstCapacity
    ) {}

    public record RegisteredApiResponse(
        Long id,
        String name,
        String targetUrl,
        String apiKey,      // clients send this as the X-API-Key header
        String gatewayUrl,  // clients call this instead of targetUrl
        boolean active,
        Policy.Algorithm algorithm,
        int limit,
        int windowSeconds,
        Integer burstCapacity
    ) {}
}
