package com.artis.saas_platform.provisioning.service;

import com.artis.saas_platform.common.Email.EmailService;
import com.artis.saas_platform.common.enums.ProvisioningStatus;
import com.artis.saas_platform.common.enums.SubscriptionStatus;
import com.artis.saas_platform.common.enums.TenantStatus;
import com.artis.saas_platform.keycloak.service.KeycloakProvisioner;
import com.artis.saas_platform.provisioning.dto.ProvisioningCompleteRequest;
import com.artis.saas_platform.provisioning.entity.AccountType;
import com.artis.saas_platform.provisioning.entity.ProvisioningRequest;
import com.artis.saas_platform.provisioning.repository.ProvisioningRequestRepository;
import com.artis.saas_platform.subscription.entity.Subscription;
import com.artis.saas_platform.subscription.repository.SubscriptionRepository;
import com.artis.saas_platform.tenancy.entity.Tenant;
import com.artis.saas_platform.tenancy.repository.TenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Logique metier de finalisation d'un provisioning.
 *
 * Gere 2 cas principaux :
 *   - SUCCESS  : nouveau tenant (DEMO ou PROD)
 *   - MIGRATED : tenant qui passe de DEMO -> PROD
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProvisioningCompleteService {

    private static final int DEMO_DURATION_WEEKS = 2;

    private final ProvisioningRequestRepository provisioningRepo;
    private final TenantRepository tenantRepo;
    private final SubscriptionRepository subscriptionRepo;
    private final EmailService emailService;
    private final KeycloakProvisioner keycloakProvisioner;

    @Transactional
    public void handleComplete(ProvisioningCompleteRequest request) {

        ProvisioningRequest pr = provisioningRepo.findById(request.getRequestId())
                .orElseThrow(() -> new IllegalStateException(
                        "ProvisioningRequest introuvable : " + request.getRequestId()));

        // Idempotence
        if (pr.getStatus() == ProvisioningStatus.COMPLETED) {
            log.info("[COMPLETE] Already completed → domain={} (idempotent, skip)",
                    pr.getTenantDomain());
            return;
        }

        // Echec du pipeline
        if (!"SUCCESS".equalsIgnoreCase(request.getStatus())
                && !"MIGRATED".equalsIgnoreCase(request.getStatus())) {
            log.warn("[COMPLETE] Pipeline reported failure → domain={} error={}",
                    pr.getTenantDomain(), request.getErrorMessage());
            pr.setStatus(ProvisioningStatus.FAILED);
            pr.setErrorMessage(request.getErrorMessage());
            pr.setUpdatedAt(LocalDateTime.now());
            provisioningRepo.save(pr);
            return;
        }

        // Dispatcher selon le type
        boolean isMigration = "MIGRATED".equalsIgnoreCase(request.getStatus());

        if (isMigration) {
            handleMigration(pr);
        } else {
            handleNewProvisioning(pr);
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // Cas 1 : nouveau provisioning (DEMO ou PROD)
    // ══════════════════════════════════════════════════════════════════

    private void handleNewProvisioning(ProvisioningRequest pr) {
        boolean isDemo = pr.getAccountType() == AccountType.DEMO;
        String domain = pr.getTenantDomain();
        LocalDateTime now = LocalDateTime.now();

        // 1. Creer le Tenant
        Tenant tenant = createTenant(pr, isDemo, now);
        tenant = tenantRepo.save(tenant);
        log.info("[COMPLETE] Tenant created → id={} domain={} schema={}",
                tenant.getTenantId(), domain, tenant.getSchemaName());

        // 2. Si PROD : Subscription
        if (!isDemo) {
            Subscription subscription = createSubscription(tenant, pr, now);
            subscriptionRepo.save(subscription);
            log.info("[COMPLETE] Subscription created → tenant={} plan={}",
                    domain, pr.getPlan());
        }

        // 3. Keycloak
        provisionKeycloak(pr, tenant);

        // 4. Update ProvisioningRequest
        pr.setStatus(ProvisioningStatus.COMPLETED);
        pr.setTenantId(tenant.getTenantId());
        pr.setSchemaName(tenant.getSchemaName());
        pr.setRealm(tenant.getRealm());
        pr.setUpdatedAt(now);
        provisioningRepo.save(pr);

        // 5. Email
        if (isDemo) {
            emailService.sendDemoReadyEmail(
                    pr.getAdminEmail(),
                    pr.getAdminFirstName(),
                    pr.getOrganizationName(),
                    pr.getTenantDomain(),
                    pr.getAdminPassword()
            );
        } else {
            emailService.sendTenantReadyEmail(
                    pr.getAdminEmail(),
                    pr.getAdminFirstName(),
                    pr.getOrganizationName(),
                    pr.getTenantDomain(),
                    pr.getAdminPassword()
            );
        }

        log.info("[COMPLETE] Provisioning finalized → domain={} accountType={}",
                domain, pr.getAccountType());
    }

    // ══════════════════════════════════════════════════════════════════
    // Cas 2 : migration DEMO -> PROD
    // ══════════════════════════════════════════════════════════════════

    private void handleMigration(ProvisioningRequest pr) {
        String domain = pr.getTenantDomain();
        LocalDateTime now = LocalDateTime.now();

        // 1. Retrouver le Tenant existant (cree lors de l'inscription DEMO)
        Tenant tenant = tenantRepo.findByTenantDomain(domain)
                .orElseThrow(() -> new IllegalStateException(
                        "Tenant introuvable lors de la migration : " + domain));

        log.info("[MIGRATE] Promoting tenant → domain={} oldSchema={} newSchema=tenant_{}",
                domain, tenant.getSchemaName(), domain);

        // 2. Promouvoir le Tenant : DEMO -> PROD
        tenant.setAccountType(AccountType.PROD);
        tenant.setSchemaName("tenant_" + domain);
        tenant.setStatus(TenantStatus.ACTIVE);
        tenant.setSuspended(false);
        tenant.setDemoExpiresAt(null);       // plus de date d'expiration
        tenant.setPlan(pr.getPlan());
        tenant.setUpdatedAt(now);
        tenant = tenantRepo.save(tenant);

        // 3. Creer la Subscription PROD
        Subscription subscription = createSubscription(tenant, pr, now);
        subscriptionRepo.save(subscription);
        log.info("[MIGRATE] Subscription created → tenant={} plan={}",
                domain, pr.getPlan());

        // 4. Pas de modification Keycloak : le realm existe deja et on garde
        //    le meme user/password

        // 5. Update ProvisioningRequest
        pr.setStatus(ProvisioningStatus.COMPLETED);
        pr.setTenantId(tenant.getTenantId());
        pr.setSchemaName(tenant.getSchemaName());
        pr.setMigrationPending(false);
        pr.setUpdatedAt(now);
        provisioningRepo.save(pr);

        // 6. Email : "tenant-ready" (compte PROD)
        //    On passe "**CLEARED**" pour ne pas re-exposer le password en clair
        //    (l'utilisateur connait deja son password depuis la DEMO)
        emailService.sendTenantReadyEmail(
                pr.getAdminEmail(),
                pr.getAdminFirstName(),
                pr.getOrganizationName(),
                pr.getTenantDomain(),
                "**CLEARED**"
        );

        log.info("[MIGRATE] Migration finalized → domain={} withData={}",
                domain, pr.isWithData());
    }

    // ══════════════════════════════════════════════════════════════════
    // Helpers
    // ══════════════════════════════════════════════════════════════════

    private Tenant createTenant(ProvisioningRequest pr, boolean isDemo, LocalDateTime now) {
        String domain = pr.getTenantDomain();
        String schemaName = isDemo ? "demo_" + domain : "tenant_" + domain;

        return Tenant.builder()
                .tenantId(UUID.randomUUID().toString())
                .tenantDomain(domain)
                .schemaName(schemaName)
                .realm(domain)
                .organizationName(pr.getOrganizationName())
                .plan(pr.getPlan())
                .adminEmail(pr.getAdminEmail())
                .status(TenantStatus.ACTIVE)
                .accountType(pr.getAccountType())
                .demoExpiresAt(isDemo ? now.plusWeeks(DEMO_DURATION_WEEKS) : null)
                .suspended(false)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    private Subscription createSubscription(Tenant tenant, ProvisioningRequest pr,
                                            LocalDateTime now) {
        return Subscription.builder()
                .tenant(tenant)
                .plan(pr.getPlan())
                .status(SubscriptionStatus.ACTIVE)
                .startDate(now)
                .endDate(now.plusMonths(1))
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    private void provisionKeycloak(ProvisioningRequest pr, Tenant tenant) {
        try {
            keycloakProvisioner.bootstrapTenant(pr, tenant.getRealm());
            log.info("[COMPLETE] Keycloak realm provisioned → realm={}",
                    tenant.getRealm());
        } catch (Exception e) {
            log.error("[COMPLETE] Keycloak provisioning failed → domain={} error={}",
                    pr.getTenantDomain(), e.getMessage(), e);
            throw new RuntimeException(
                    "Keycloak provisioning failed for " + pr.getTenantDomain(), e);
        }
    }
}