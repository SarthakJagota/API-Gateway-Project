package com.gateway.api_gateway.repository;

import com.gateway.api_gateway.entity.Policy;
import com.gateway.api_gateway.entity.RegisteredApi;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface PolicyRepository extends JpaRepository<Policy, Long> {
    Optional<Policy> findByRegisteredApi(RegisteredApi registeredApi);
    Optional<Policy> findByRegisteredApiId(Long apiId);
}
