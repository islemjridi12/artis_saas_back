package com.artis.saas_platform.provisioning.scheduler;

import com.artis.saas_platform.keycloak.service.KeycloakProvisioner;
import com.artis.saas_platform.provisioning.entity.AccountType;
import com.artis.saas_platform.provisioning.entity.ProvisioningRequest;
import com.artis.saas_platform.provisioning.publisher.ProvisioningEventPublisher;
import com.artis.saas_platform.provisioning.repository.ProvisioningRequestRepository;
import com.artis.saas_platform.tenancy.entity.Tenant;
import com.artis.saas_platform.tenancy.repository.TenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Scheduler qui supprime definitvement les schemas DEMO
 * 30 jours apres la suspension du tenant.
 *
 * Execution toutes les heures.
 *
 * Critere de declenchement :
 *   - accountType = DEMO
 *   - suspended = true
 *   - suspendedAt < now() - 30 jours
 *
 * Action :
 *   Publie un evenement provisioning.expire sur RabbitMQ
 *   → Argo Workflow EXPIRE supprime le schema via DROP SCHEMA CASCADE
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DemoCleanupScheduler {

    private final TenantRepository tenantRepository;
    private final ProvisioningRequestRepository provisioningRequestRepository;
    private final ProvisioningEventPublisher publisher;
    private final KeycloakProvisioner keycloakProvisioner; // ← AJOUTE

    @Scheduled(fixedDelay = 3600000) // toutes les heures
    @Transactional
    public void processExpiredDemoCleanup() {

        LocalDateTime cutoff = LocalDateTime.now().minusDays(30);

        // Trouver les tenants DEMO suspendus depuis plus de 30 jours
        List<Tenant> tenantsToCleanup = tenantRepository
                .findByAccountTypeAndSuspendedTrueAndSuspendedAtBefore(
                        AccountType.DEMO, cutoff);

        if (tenantsToCleanup.isEmpty()) {
            log.debug("[CLEANUP] No DEMO tenants to cleanup");
            return;
        }

        log.info("[CLEANUP] Found {} DEMO tenant(s) to cleanup", tenantsToCleanup.size());

        for (Tenant tenant : tenantsToCleanup) {
            try {
                triggerExpireWorkflow(tenant);
            } catch (Exception e) {
                log.error("[CLEANUP] Failed domain={} error={}",
                        tenant.getTenantDomain(), e.getMessage(), e);
            }
        }
    }

    private void triggerExpireWorkflow(Tenant tenant) {

        String domain = tenant.getTenantDomain();

        log.info("[CLEANUP] Triggering EXPIRE workflow → domain={} suspendedAt={}",
                domain, tenant.getSuspendedAt());

        ProvisioningRequest pr = provisioningRequestRepository
                .findByTenantDomain(domain)
                .orElse(null);

        if (pr == null) {
            log.warn("[CLEANUP] No ProvisioningRequest found for domain={}", domain);
            return;
        }

        // 1. Publier l'evenement EXPIRE sur RabbitMQ
        publisher.publishExpire(pr);
        log.info("[CLEANUP] EXPIRE event published → domain={} schema={}", domain, pr.getSchemaName());

        // 2. Supprimer le realm Keycloak
        try {
            keycloakProvisioner.deleteRealm(tenant.getRealm());
            log.info("[CLEANUP] Keycloak realm deleted → realm={}", tenant.getRealm());
        } catch (Exception e) {
            log.error("[CLEANUP] Failed to delete Keycloak realm → realm={} error={}",
                    tenant.getRealm(), e.getMessage());
        }

        // 3. Marquer le tenant comme DELETED ← AJOUTE
        tenant.setStatus(com.artis.saas_platform.common.enums.TenantStatus.DELETED);
        tenant.setUpdatedAt(LocalDateTime.now());
        tenantRepository.save(tenant);
        log.info("[CLEANUP] Tenant marked as DELETED → domain={}", domain);
    }
}