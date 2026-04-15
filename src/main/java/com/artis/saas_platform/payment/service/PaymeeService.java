package com.artis.saas_platform.payment.service;


import com.artis.saas_platform.payment.dto.PaymentInitRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

@Service
public class PaymeeService {

    @Value("${paymee.base-url}")
    private String baseUrl;

    @Value("${paymee.api-token}")
    private String apiToken;

    @Value("${paymee.public-base-url}")
    private String publicBaseUrl;

    private final RestTemplate restTemplate = new RestTemplate();

    public Map<String, Object> createPayment(PaymentInitRequest req) {
        String url = baseUrl + "/payments/create";

        Map<String, Object> payload = new HashMap<>();
        payload.put("amount", 1000.000);
        payload.put("note", "ARTIS SaaS Subscription");
        payload.put("first_name", req.getAdmin().getFirstName());
        payload.put("last_name", req.getAdmin().getLastName());
        payload.put("email", req.getAdmin().getEmail());
        payload.put("phone", req.getAdmin().getPhone());
        payload.put("return_url", publicBaseUrl + "/api/payment/redirect-success");
        payload.put("cancel_url", publicBaseUrl + "/api/payment/redirect-cancel");
        payload.put("webhook_url", publicBaseUrl + "/api/payment/webhook");
        payload.put("order_id", req.getTenantDomain());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Token " + apiToken);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(payload, headers);
        ResponseEntity<Map> response =
                restTemplate.postForEntity(
                        url,
                        entity,
                        Map.class
                );

        return response.getBody();
    }
}