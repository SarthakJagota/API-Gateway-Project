package com.gateway.api_gateway.repository;

import com.gateway.api_gateway.entity.RegisteredApi;
import com.gateway.api_gateway.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface RegisteredApiRepository extends JpaRepository<RegisteredApi, Long> {
    List<RegisteredApi> findByOwner(User owner);
    // Person 1's gateway calls this on every request to resolve target + owner.
    Optional<RegisteredApi> findByGatewayApiKey(String gatewayApiKey);
}
