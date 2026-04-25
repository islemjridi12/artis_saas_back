package com.artis.saas_platform.provisioning.service.interfaces;


import com.artis.saas_platform.payment.dto.PaymentInitRequest;
import com.artis.saas_platform.provisioning.entity.ProvisioningRequest;

import java.util.UUID;

public interface ProvisioningService {

    ProvisioningRequest createInitialRequest(PaymentInitRequest req);
    ProvisioningRequest createDemoRequest(PaymentInitRequest req);
    void process(UUID requestId);
    ProvisioningRequest markPaymentSuccess(String paymentToken);
    void markPaymentFailure(String paymentToken);
    void attachPaymentToken(UUID id, String token);
    void save(ProvisioningRequest pr);

    ProvisioningRequest findPendingPaymentByDomain(String domain);
    ProvisioningRequest findByPaymentToken(String token);
    ProvisioningRequest findByOtpToken(String token);
    ProvisioningRequest findByAdminEmail(String email);
    ProvisioningRequest findByDomainAndDemoSuspended(String domain);
    void triggerMigration(ProvisioningRequest pr);
}