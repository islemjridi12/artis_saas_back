package com.artis.saas_platform.provisioning.scheduler;

import com.artis.saas_platform.common.Email.EmailService;
import com.artis.saas_platform.keycloak.service.KeycloakProvisioner;
import com.artis.saas_platform.provisioning.entity.AccountType;
import com.artis.saas_platform.provisioning.publisher.ProvisioningEventPublisher;
import com.artis.saas_platform.provisioning.repository.ProvisioningRequestRepository;
import com.artis.saas_platform.provisioning.service.SchemaProvisioningService;
import com.artis.saas_platform.tenancy.entity.Tenant;
import com.artis.saas_platform.tenancy.repository.TenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.keycloak.admin.client.Keycloak;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class DemoExpirationScheduler {

    private final TenantRepository tenantRepository;
    private final EmailService emailService;
    private final SchemaProvisioningService schemaProvisioningService;
    private final KeycloakProvisioner keycloakProvisioner;
    private final Keycloak keycloak;
    private final ProvisioningEventPublisher publisher;
    private final ProvisioningRequestRepository provisioningRepository;

    @Scheduled(fixedDelay = 10000)
    public void checkDemoExpiration() {

        // ===== PARTIE 1 : suspendre les demos expirees =====
        List<Tenant> expiredDemos = tenantRepository
                .findByAccountTypeAndDemoExpiresAtBeforeAndSuspendedFalse(
                        AccountType.DEMO,
                        LocalDateTime.now()
                );

        for (Tenant tenant : expiredDemos) {
            tenant.setSuspended(true);
            tenant.setUpdatedAt(LocalDateTime.now());
            tenantRepository.save(tenant);

            // 🔥 Publier event expire sur RabbitMQ
            provisioningRepository.findByTenantDomain(tenant.getTenantDomain())
                    .ifPresent(pr -> publisher.publishExpire(pr));

            // Email demo-expired
            emailService.sendDemoExpiredEmail(
                    tenant.getAdminEmail(),
                    tenant.getOrganizationName(),
                    tenant.getTenantDomain()
            );

            log.info("[DEMO] Expired → domain={}", tenant.getTenantDomain());
        }

        // ===== PARTIE 2 : supprimer apres 1 mois =====
        List<Tenant> toDelete = tenantRepository
                .findByAccountTypeAndSuspendedTrueAndUpdatedAtBefore(
                        AccountType.DEMO,
                        LocalDateTime.now().minusMonths(1)
                );

        for (Tenant tenant : toDelete) {
            try {
                schemaProvisioningService.dropDemoSchema(tenant.getSchemaName());

                if (keycloakProvisioner.realmExists(tenant.getRealm())) {
                    keycloak.realm(tenant.getRealm()).remove();
                }

                tenantRepository.delete(tenant);

                log.info("[DEMO] Deleted after 1 month → domain={}", tenant.getTenantDomain());

            } catch (Exception e) {
                log.error("[DEMO] Failed to delete → domain={} error={}",
                        tenant.getTenantDomain(), e.getMessage());
            }
        }
    }
}