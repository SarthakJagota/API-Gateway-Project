package com.gateway.api_gateway.service;

import com.gateway.api_gateway.dto.ApiDtos.RegisterApiRequest;
import com.gateway.api_gateway.dto.ApiDtos.RegisteredApiResponse;
import com.gateway.api_gateway.dto.ApiDtos.UpdatePolicyRequest;
import com.gateway.api_gateway.entity.Policy;
import com.gateway.api_gateway.entity.RegisteredApi;
import com.gateway.api_gateway.entity.User;
import com.gateway.api_gateway.gateway.RouteCache;
import com.gateway.api_gateway.repository.PolicyRepository;
import com.gateway.api_gateway.repository.RegisteredApiRepository;
import com.gateway.api_gateway.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.util.List;

@Service
public class ApiRegistrationService {

    private final RegisteredApiRepository registeredApiRepository;
    private final PolicyRepository policyRepository;
    private final UserRepository userRepository;
    private final UrlValidationService urlValidationService;
    private final RouteCache routeCache;

    public ApiRegistrationService(
        RegisteredApiRepository registeredApiRepository,
        PolicyRepository policyRepository,
        UserRepository userRepository,
        UrlValidationService urlValidationService,
        RouteCache routeCache
    ) {
        this.registeredApiRepository = registeredApiRepository;
        this.policyRepository = policyRepository;
        this.userRepository = userRepository;
        this.urlValidationService = urlValidationService;
        this.routeCache = routeCache;
    }

    @Transactional
    public RegisteredApiResponse register(String ownerEmail, RegisterApiRequest request) {
        // Proxy-abuse mitigation happens before anything is persisted.
        urlValidationService.validateOrThrow(request.targetUrl());

        User owner = userRepository.findByEmail(ownerEmail)
            .orElseThrow(() -> new UsernameNotFoundException("User not found: " + ownerEmail));

        RegisteredApi api = new RegisteredApi();
        api.setOwner(owner);
        api.setName(request.name());
        api.setTargetUrl(request.targetUrl());
        registeredApiRepository.save(api);

        Policy policy = new Policy();
        policy.setRegisteredApi(api);
        policy.setAlgorithm(request.algorithm());
        policy.setRequestsPerWindow(request.limit());
        policy.setWindowSeconds(request.windowSeconds());
        policy.setBurstCapacity(request.burstCapacity());
        policyRepository.save(policy);

        return toResponse(api, policy);
    }

    public List<RegisteredApiResponse> listForOwner(String ownerEmail) {
        return ownedApis(ownerEmail).stream()
            .map(api -> toResponse(api, policyOf(api)))
            .toList();
    }

    public RegisteredApiResponse get(String ownerEmail, Long apiId) {
        RegisteredApi api = getOwned(ownerEmail, apiId);
        return toResponse(api, policyOf(api));
    }

    @Transactional
    public RegisteredApiResponse updatePolicy(String ownerEmail, Long apiId, UpdatePolicyRequest request) {
        RegisteredApi api = getOwned(ownerEmail, apiId);
        Policy policy = policyOf(api);
        policy.setAlgorithm(request.algorithm());
        policy.setRequestsPerWindow(request.limit());
        policy.setWindowSeconds(request.windowSeconds());
        policy.setBurstCapacity(request.burstCapacity());
        policyRepository.save(policy);
        routeCache.evict(apiId); // apply the new limit right away
        return toResponse(api, policy);
    }

    @Transactional
    public RegisteredApiResponse rotateKey(String ownerEmail, Long apiId) {
        RegisteredApi api = getOwned(ownerEmail, apiId);
        api.rotateKey();
        registeredApiRepository.save(api);
        routeCache.evict(apiId); // old key stops working right away
        return toResponse(api, policyOf(api));
    }

    public List<RegisteredApi> ownedApis(String ownerEmail) {
        User owner = userRepository.findByEmail(ownerEmail)
            .orElseThrow(() -> new UsernameNotFoundException("User not found: " + ownerEmail));
        return registeredApiRepository.findByOwner(owner);
    }

    // Ownership check: you can only see or change APIs you registered.
    // Answers 404 (not 403) so other people's API ids aren't revealed.
    public RegisteredApi getOwned(String ownerEmail, Long apiId) {
        return registeredApiRepository.findById(apiId)
            .filter(api -> api.getOwner().getEmail().equals(ownerEmail))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "API not found"));
    }

    private Policy policyOf(RegisteredApi api) {
        return policyRepository.findByRegisteredApi(api)
            .orElseThrow(() -> new IllegalStateException("Policy missing for API " + api.getId()));
    }

    private RegisteredApiResponse toResponse(RegisteredApi api, Policy policy) {
        // Built from the incoming request, so it is right on localhost and on Render.
        String gatewayUrl = ServletUriComponentsBuilder.fromCurrentContextPath()
            .path("/g/{id}").buildAndExpand(api.getId()).toUriString();
        return new RegisteredApiResponse(
            api.getId(),
            api.getName(),
            api.getTargetUrl(),
            api.getGatewayApiKey(),
            gatewayUrl,
            api.isActive(),
            policy.getAlgorithm(),
            policy.getRequestsPerWindow(),
            policy.getWindowSeconds(),
            policy.getBurstCapacity()
        );
    }
}
