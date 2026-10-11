package com.gateway.api_gateway.service;

import com.gateway.api_gateway.entity.RequestLog;
import com.gateway.api_gateway.repository.RequestLogRepository;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

// Called by GatewayController AFTER a request has been allowed/blocked and
// (if allowed) relayed. @Async hands the DB write to a separate thread pool
// so a slow Postgres write can't add to the client-facing latency.
//
// Trade-off worth documenting for the report: @Async is fire-and-forget, so
// if the app crashes between "response sent" and "row written", that request
// is missing from the log. That's the direct risk to the >=99.9% log
// completeness target.
@Service
public class RequestLogService {

    private final RequestLogRepository requestLogRepository;

    public RequestLogService(RequestLogRepository requestLogRepository) {
        this.requestLogRepository = requestLogRepository;
    }

    @Async
    public void logAsync(Long apiId, String clientIdentity, RequestLog.Outcome outcome, Integer status, Long latencyMs) {
        requestLogRepository.save(new RequestLog(apiId, clientIdentity, outcome, status, latencyMs));
    }
}
