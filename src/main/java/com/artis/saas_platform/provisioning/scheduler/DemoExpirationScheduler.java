package com.artis.saas_platform.provisioning.scheduler;

import com.artis.saas_platform.common.Email.EmailService;
import com.artis.saas_platform.provisioning.entity.AccountType;
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
 * Scheduler qui detecte les tenants DEMO expires et les suspend automatiquement.
 *
 * Execution toutes les 60 secondes.
 *
 * Critere de suspension :
 *   - accountType = DEMO
 *   - suspended = false
 *   - demoExpiresAt < now()
 *
 * Actions :
 *   1. Suspend le tenant
 *   2. Stocke la date de suspension (IMPORTANT pour les 30 jours)
 *   3. Envoie un email avec lien de migration
 *
 * NOTE :
 *   - Aucune suppression ici
 *   - Le cleanup sera fait par un autre scheduler + Argo
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DemoExpirationScheduler {

    private final TenantRepository tenantRepository;
    private final EmailService emailService;

    @Scheduled(fixedDelay = 60000)
    @Transactional
    public void processExpiredDemos() {

        LocalDateTime now = LocalDateTime.now();

        List<Tenant> expiredTenants = tenantRepository
                .findByAccountTypeAndSuspendedFalseAndDemoExpiresAtBefore(
                        AccountType.DEMO, now);

        if (expiredTenants.isEmpty()) {
            log.debug("[EXPIRATION] No expired DEMO tenants found");
            return;
        }

        log.info("[EXPIRATION] Found {} expired DEMO tenant(s)", expiredTenants.size());

        for (Tenant tenant : expiredTenants) {
            try {
                suspendAndNotify(tenant);
            } catch (Exception e) {
                log.error("[EXPIRATION] Failed to process tenant domain={} error={}",
                        tenant.getTenantDomain(), e.getMessage(), e);
            }
        }
    }

    private void suspendAndNotify(Tenant tenant) {

        String domain = tenant.getTenantDomain();

        // sécurité supplémentaire (éviter double traitement)
        if (Boolean.TRUE.equals(tenant.isSuspended())) {
            log.warn("[EXPIRATION] Tenant already suspended → domain={}", domain);
            return;
        }

        log.info("[EXPIRATION] Suspending tenant → domain={} expiredAt={}",
                domain, tenant.getDemoExpiresAt());

        // 1. Suspendre + enregistrer date de suspension
        tenant.setSuspended(true);
        tenant.setSuspendedAt(LocalDateTime.now()); // ✅ CRITIQUE
        tenant.setUpdatedAt(LocalDateTime.now());

        tenantRepository.save(tenant);

        // 2. Envoyer email d'expiration (avec lien upgrade)
        try {
            emailService.sendDemoExpiredEmail(
                    tenant.getAdminEmail(),
                    tenant.getOrganizationName(),
                    tenant.getTenantDomain()
            );
        } catch (Exception e) {
            log.error("[EXPIRATION] Email failed → domain={} error={}",
                    domain, e.getMessage(), e);
        }

        log.info("[EXPIRATION] ✔ Tenant suspended → domain={} email={}",
                domain, tenant.getAdminEmail());
    }
}