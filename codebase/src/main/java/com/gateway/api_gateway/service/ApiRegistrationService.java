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
import com.gateway.api_gateway.repository.RequestLogRepository;
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
    private final RequestLogRepository requestLogRepository;

    public ApiRegistrationService(
        RegisteredApiRepository registeredApiRepository,
        PolicyRepository policyRepository,
        UserRepository userRepository,
        UrlValidationService urlValidationService,
        RouteCache routeCache,
        RequestLogRepository requestLogRepository
    ) {
        this.registeredApiRepository = registeredApiRepository;
        this.policyRepository = policyRepository;
        this.userRepository = userRepository;
        this.urlValidationService = urlValidationService;
        this.routeCache = routeCache;
        this.requestLogRepository = requestLogRepository;
    }

    @Transactional
    public RegisteredApiResponse register(String ownerEmail, RegisterApiRequest request) {
        // Proxy-abuse mitigation happens before anything is persisted.
        urlValidationService.validateOrThrow(request.targetUrl());

        if (registeredApiRepository.existsBySlug(request.slug())) {
            throw new IllegalArgumentException("The short name '" + request.slug() + "' is already taken");
        }

        User owner = userRepository.findByEmail(ownerEmail)
            .orElseThrow(() -> new UsernameNotFoundException("User not found: " + ownerEmail));

        RegisteredApi api = new RegisteredApi();
        api.setOwner(owner);
        api.setName(request.name());
        api.setSlug(request.slug());
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

    // Deletes an API the caller owns, with its policy and logs. Its gateway URL
    // stops working immediately on this instance (cache evicted) and its short
    // name becomes free to register again. Redis counters expire on their own.
    @Transactional
    public void delete(String ownerEmail, Long apiId) {
        RegisteredApi api = getOwned(ownerEmail, apiId);
        requestLogRepository.deleteByApiId(apiId);
        policyRepository.findByRegisteredApi(api).ifPresent(policyRepository::delete);
        registeredApiRepository.delete(api);
        routeCache.forget(apiId, api.getSlug());
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
        // Uses the short name if the API has one, otherwise its number.
        Object pathKey = api.getSlug() != null ? api.getSlug() : api.getId();
        String gatewayUrl = ServletUriComponentsBuilder.fromCurrentContextPath()
            .path("/g/{key}").buildAndExpand(pathKey).toUriString();
        return new RegisteredApiResponse(
            api.getId(),
            api.getName(),
            api.getSlug(),
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
