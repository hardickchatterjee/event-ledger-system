package com.example.eventsService.client;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

@Component
@RequiredArgsConstructor
@Slf4j
public class AccountServiceClient {

    private final RestClient restClient;

    @Value("${account.service.base-url}")
    private String baseUrl;

    public boolean applyTransaction(String accountId, TransactionRequest request) {
        try {
            restClient.post()
                .uri(baseUrl + "/accounts/{accountId}/transactions", accountId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .toBodilessEntity();
            return true;
        } catch (ResourceAccessException ex) {
            log.warn("Account Service unavailable for accountId={}: {}", accountId, ex.getMessage());
            return false;
        }
    }
}
