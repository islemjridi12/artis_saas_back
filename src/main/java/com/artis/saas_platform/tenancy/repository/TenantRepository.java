package com.artis.saas_platform.tenancy.repository;

import com.artis.saas_platform.provisioning.entity.AccountType;
import com.artis.saas_platform.tenancy.entity.Tenant;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TenantRepository extends JpaRepository<Tenant, UUID> {

    boolean existsByTenantDomain(String domain);

    Optional<Tenant> findByTenantDomain(String domain);

    Optional<Tenant> findByTenantDomainAndAccountTypeAndSuspendedTrue(
            String tenantDomain,
            AccountType accountType
    );

    List<Tenant> findByAccountTypeAndDemoExpiresAtBeforeAndSuspendedFalse(
            AccountType accountType,
            LocalDateTime date
    );

    List<Tenant> findByAccountTypeAndSuspendedFalseAndDemoExpiresAtBefore(
            AccountType accountType,
            LocalDateTime date
    );
}
