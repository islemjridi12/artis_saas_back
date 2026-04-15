package com.artis.saas_platform.provisioning.repository;

import com.artis.saas_platform.common.enums.ProvisioningStatus;
import com.artis.saas_platform.provisioning.entity.AccountType;
import com.artis.saas_platform.provisioning.entity.ProvisioningRequest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProvisioningRequestRepository
        extends JpaRepository<ProvisioningRequest, UUID> {

        Optional<ProvisioningRequest> findByPaymentToken(String token);
        Optional<ProvisioningRequest> findByOtpToken(String token);
        Optional<ProvisioningRequest> findByTenantDomain(String domain);
        boolean existsByTenantDomain(String domain);

        Optional<ProvisioningRequest> findTopByAdminEmailOrderByCreatedAtDesc(String email);

        Optional<ProvisioningRequest> findByTenantDomainAndStatusIn(
                String domain,
                List<ProvisioningStatus> statuses
        );

        List<ProvisioningRequest> findTop10ByStatusInOrderByCreatedAtAsc(
                List<ProvisioningStatus> statuses
        );
}
