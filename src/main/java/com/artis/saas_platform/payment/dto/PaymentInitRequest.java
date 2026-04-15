package com.artis.saas_platform.payment.dto;


import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class PaymentInitRequest {

    @NotBlank
    private String tenantDomain;

    @NotBlank
    private String organizationName;

    @NotBlank
    private String plan;

    @Valid
    private AdminDto admin;
}