package com.gateway.api_gateway.controller;

import com.gateway.api_gateway.dto.ApiDtos.RegisterApiRequest;
import com.gateway.api_gateway.dto.ApiDtos.RegisteredApiResponse;
import com.gateway.api_gateway.dto.ApiDtos.UpdatePolicyRequest;
import com.gateway.api_gateway.service.ApiRegistrationService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

// Authentication.getName() is the email we put as the JWT subject in JwtAuthFilter.
@RestController
@RequestMapping("/api/apis")
public class ApiRegistrationController {

    private final ApiRegistrationService service;

    public ApiRegistrationController(ApiRegistrationService service) {
        this.service = service;
    }

    @PostMapping
    public RegisteredApiResponse register(Authentication auth, @Valid @RequestBody RegisterApiRequest request) {
        return service.register(auth.getName(), request);
    }

    @GetMapping
    public List<RegisteredApiResponse> listMine(Authentication auth) {
        return service.listForOwner(auth.getName());
    }

    @GetMapping("/{id}")
    public RegisteredApiResponse get(Authentication auth, @PathVariable Long id) {
        return service.get(auth.getName(), id);
    }

    @PutMapping("/{id}")
    public RegisteredApiResponse updatePolicy(Authentication auth, @PathVariable Long id,
                                              @Valid @RequestBody UpdatePolicyRequest request) {
        return service.updatePolicy(auth.getName(), id, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(Authentication auth, @PathVariable Long id) {
        service.delete(auth.getName(), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/rotate-key")
    public RegisteredApiResponse rotateKey(Authentication auth, @PathVariable Long id) {
        return service.rotateKey(auth.getName(), id);
    }
}
