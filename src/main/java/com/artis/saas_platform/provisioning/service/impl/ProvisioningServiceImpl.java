package com.artis.saas_platform.provisioning.service.impl;



import com.artis.saas_platform.common.Email.EmailService;
import com.artis.saas_platform.common.enums.ProvisioningStatus;
import com.artis.saas_platform.common.enums.SubscriptionStatus;
import com.artis.saas_platform.common.enums.TenantStatus;
import com.artis.saas_platform.keycloak.service.KeycloakProvisioner;
import com.artis.saas_platform.payment.dto.PaymentInitRequest;
import com.artis.saas_platform.provisioning.entity.AccountType;
import com.artis.saas_platform.provisioning.entity.ProvisioningRequest;
import com.artis.saas_platform.provisioning.repository.ProvisioningRequestRepository;
import com.artis.saas_platform.provisioning.service.MigrationService;
import com.artis.saas_platform.provisioning.service.SchemaProvisioningService;
import com.artis.saas_platform.provisioning.service.interfaces.ProvisioningService;
import com.artis.saas_platform.subscription.entity.Subscription;
import com.artis.saas_platform.subscription.repository.SubscriptionRepository;
import com.artis.saas_platform.tenancy.entity.Tenant;
import com.artis.saas_platform.tenancy.repository.TenantRepository;
import lombok.RequiredArgsConstructor;

import org.apache.commons.lang3.RandomStringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ProvisioningServiceImpl implements ProvisioningService {

    private static final Logger log = LoggerFactory.getLogger(ProvisioningServiceImpl.class);

    private final ProvisioningRequestRepository repository;
    private final TenantRepository tenantRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final KeycloakProvisioner keycloakProvisioner;
    private final SchemaProvisioningService schemaProvisioningService;
    private final EmailService emailService;
    private final MigrationService migrationService;

    @Override
    public ProvisioningRequest createInitialRequest(PaymentInitRequest req) {

        String tenantDomain = req.getTenantDomain().trim().toLowerCase();
        String email = req.getAdmin().getEmail();

        ProvisioningRequest existing =
                repository.findTopByAdminEmailOrderByCreatedAtDesc(email).orElse(null);

        // ================= CAS EXISTANT NON VERIFIÉ =================
        if (existing != null && !existing.isEmailVerified()) {

            //  régénérer OTP
            String code = String.valueOf((int)(Math.random() * 900000) + 100000);

            existing.setEmailVerificationCode(code);
            existing.setEmailVerificationExpiresAt(LocalDateTime.now().plusMinutes(10));
            existing.setUpdatedAt(LocalDateTime.now());

            repository.save(existing);

            // 🔥 renvoyer email
            emailService.sendVerificationEmail(existing.getAdminEmail(), code);

            return existing;
        }

        // ================= VERIFICATION DOMAIN =================
        if (tenantRepository.existsByTenantDomain(tenantDomain) ||
                repository.existsByTenantDomain(tenantDomain)) {
            throw new IllegalStateException("Tenant domain already used");
        }

        // ================= NOUVELLE DEMANDE =================
        String code = String.valueOf((int)(Math.random() * 900000) + 100000);
        String otpToken = UUID.randomUUID().toString();

        ProvisioningRequest pr = ProvisioningRequest.builder()
                .tenantDomain(tenantDomain)
                .organizationName(req.getOrganizationName())
                .plan(req.getPlan())
                .adminEmail(email)
                .adminFirstName(req.getAdmin().getFirstName())
                .adminLastName(req.getAdmin().getLastName())
                .adminPhone(req.getAdmin().getPhone())
                .adminPassword(req.getAdmin().getPassword())

                .emailVerificationCode(code)
                .emailVerificationExpiresAt(LocalDateTime.now().plusMinutes(10))
                .emailVerified(false)

                .otpToken(otpToken)

                .status(ProvisioningStatus.PENDING_EMAIL)
                .attempts(0)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        pr = repository.save(pr);

        // 🔥 envoi email
        emailService.sendVerificationEmail(email, code);

        return pr;
    }

    @Override
    public void attachPaymentToken(UUID provisioningRequestId, String paymentToken) {
        ProvisioningRequest pr = repository.findById(provisioningRequestId).orElseThrow();
        pr.setPaymentToken(paymentToken);
        pr.setUpdatedAt(LocalDateTime.now());
        repository.save(pr);
    }

    @Override
    public void markPaymentSuccess(String paymentToken) {
        ProvisioningRequest pr = repository.findByPaymentToken(paymentToken).orElseThrow();
        if (pr.isMigrationPending()) {
            pr.setStatus(ProvisioningStatus.PENDING);
            pr.setUpdatedAt(LocalDateTime.now());
            repository.save(pr);
            return;
        }
        if (pr.getStatus() == ProvisioningStatus.COMPLETED || pr.getStatus() == ProvisioningStatus.IN_PROGRESS) {
            return;
        }
        pr.setStatus(ProvisioningStatus.PENDING);
        pr.setUpdatedAt(LocalDateTime.now());
        repository.save(pr);
    }

    @Override
    public void markPaymentFailure(String paymentToken) {
        ProvisioningRequest pr = repository.findByPaymentToken(paymentToken).orElseThrow();
        pr.setStatus(ProvisioningStatus.ERROR);
        pr.setErrorMessage("Payment failed");
        pr.setUpdatedAt(LocalDateTime.now());
        repository.save(pr);
    }

    @Override
    public void process(UUID requestId) {
        ProvisioningRequest pr = repository.findById(requestId).orElseThrow();

        if (tenantRepository.existsByTenantDomain(pr.getTenantDomain())
                && !pr.isMigrationPending()) {
            pr.setStatus(ProvisioningStatus.COMPLETED);
            pr.setUpdatedAt(LocalDateTime.now());
            repository.save(pr);
            return;
        }

        try {
            pr.setStatus(ProvisioningStatus.IN_PROGRESS);
            pr.setErrorMessage(null);
            pr.setUpdatedAt(LocalDateTime.now());
            repository.save(pr);

            boolean isDemo = pr.getAccountType() == AccountType.DEMO;
            boolean isMigration = pr.isMigrationPending();

            String tenantId = pr.getTenantId();
            if (tenantId == null || tenantId.isBlank()) {
                tenantId = generateTenantId();
                pr.setTenantId(tenantId);
            }

            String realm = pr.getRealm();
            if (realm == null || realm.isBlank()) {
                realm = tenantId;
                pr.setRealm(realm);
            }

            repository.save(pr);

            // ═══════════════════════════════
            // CAS 1 : MIGRATION DEMO → PROD
            // ═══════════════════════════════
            if (isMigration) {

                log.info("[MIGRATION] Start domain={} withData={}",
                        pr.getTenantDomain(), pr.isWithData());

//                // 1. Verifier realm Keycloak
//                if (!keycloakProvisioner.realmExists(pr.getRealm())) {
//                    throw new RuntimeException("Realm introuvable : " + pr.getRealm());
//                }

                String demoSchema = "demo_" + pr.getTenantDomain();
                String prodSchema = "tenant_" + pr.getTenantDomain();

                // 2. Creer schema PROD
                schemaProvisioningService.createSchema(prodSchema);
                schemaProvisioningService.runMigrations(prodSchema);

                // 3. Migrer ou tables vides
                if (pr.isWithData()) {
                    log.info("[MIGRATION] Copying {} → {}", demoSchema, prodSchema);
                    migrationService.migrateDemoToProd(demoSchema, prodSchema);
                } else {
                    log.info("[MIGRATION] Fresh start");
                }

                // 4. Supprimer schema demo
                schemaProvisioningService.dropDemoSchema(demoSchema);

                // 5. Mettre a jour Tenant
                Tenant tenant = tenantRepository
                        .findByTenantDomain(pr.getTenantDomain())
                        .orElseThrow();

                tenant.setAccountType(AccountType.PROD);
                tenant.setSuspended(false);
                tenant.setSchemaName(prodSchema);
                tenant.setDemoExpiresAt(null);
                tenant.setUpdatedAt(LocalDateTime.now());
                tenantRepository.save(tenant);

                // 6. Desactiver subscription DEMO
                subscriptionRepository
                        .findByTenantAndStatus(tenant, SubscriptionStatus.ACTIVE)
                        .ifPresent(sub -> {
                            sub.setStatus(SubscriptionStatus.CANCELLED);
                            subscriptionRepository.save(sub);
                        });

                // 7. Creer Subscription PROD
                Subscription subscription = Subscription.builder()
                        .tenant(tenant)
                        .plan(pr.getPlan())
                        .status(SubscriptionStatus.ACTIVE)
                        .startDate(LocalDateTime.now())
                        .endDate(LocalDateTime.now().plusYears(1))
                        .createdAt(LocalDateTime.now())
                        .build();
                subscriptionRepository.save(subscription);

                // 8. Email tenant-ready
                emailService.sendTenantReadyEmail(
                        pr.getAdminEmail(),
                        pr.getAdminFirstName(),
                        pr.getOrganizationName(),
                        pr.getTenantDomain(),
                        null
                );

                // 9. Finaliser ProvisioningRequest
                pr.setAccountType(AccountType.PROD);
                pr.setSchemaName(prodSchema);
                pr.setMigrationPending(false);
                pr.setWithData(false);
                pr.setStatus(ProvisioningStatus.COMPLETED);
                pr.setUpdatedAt(LocalDateTime.now());
                repository.save(pr);

                log.info("[MIGRATION] SUCCESS domain={}", pr.getTenantDomain());
                return;
            }

            // ═══════════════════════════════
            // CAS 2 : PROVISIONING NORMAL
            // ═══════════════════════════════

            String schemaName = pr.getSchemaName();
            if (schemaName == null || schemaName.isBlank()) {
                schemaName = isDemo
                        ? "demo_" + pr.getTenantDomain()
                        : "tenant_" + pr.getTenantDomain();
                pr.setSchemaName(schemaName);
            }

            repository.save(pr);

            // 1. Keycloak
            keycloakProvisioner.bootstrapTenant(pr, tenantId);

            // 2. Schema
            if (isDemo) {
                schemaProvisioningService.createDemoSchema(schemaName);
                schemaProvisioningService.runDemoMigrations(schemaName);
            } else {
                schemaProvisioningService.createSchema(schemaName);
                schemaProvisioningService.runMigrations(schemaName);
            }

            // 3. Tenant
            Tenant tenant = Tenant.builder()
                    .tenantId(tenantId)
                    .tenantDomain(pr.getTenantDomain())
                    .schemaName(schemaName)
                    .realm(realm)
                    .organizationName(pr.getOrganizationName())
                    .plan(pr.getPlan())
                    .adminEmail(pr.getAdminEmail())
                    .status(TenantStatus.ACTIVE)
                    .accountType(pr.getAccountType())
                    .demoExpiresAt(isDemo
                            ? LocalDateTime.now().plusWeeks(2)
                            : null)
                    .suspended(false)
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build();

            tenant = tenantRepository.save(tenant);

            // 4. Subscription
            Subscription subscription = Subscription.builder()
                    .tenant(tenant)
                    .plan(pr.getPlan())
                    .status(SubscriptionStatus.ACTIVE)
                    .startDate(LocalDateTime.now())
                    .createdAt(LocalDateTime.now())
                    .endDate(isDemo
                            ? LocalDateTime.now().plusWeeks(2)
                            : LocalDateTime.now().plusYears(1))
                    .build();

            subscriptionRepository.save(subscription);

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

            // 6. Finaliser
            pr.setAdminPassword("**CLEARED**");
            pr.setStatus(ProvisioningStatus.COMPLETED);
            pr.setUpdatedAt(LocalDateTime.now());
            repository.save(pr);

            log.info("[PROVISIONING] SUCCESS domain={} type={}",
                    pr.getTenantDomain(), pr.getAccountType());

        } catch (Exception e) {
            pr.setAttempts(pr.getAttempts() + 1);
            pr.setErrorMessage(truncate(e.getMessage(), 500));
            pr.setUpdatedAt(LocalDateTime.now());

            if (pr.getAttempts() < 3) {
                pr.setStatus(ProvisioningStatus.RETRYING);
            } else {
                pr.setStatus(ProvisioningStatus.ERROR);
            }

            repository.save(pr);
            log.error("[PROVISIONING] FAILED domain={} error={}",
                    pr.getTenantDomain(), e.getMessage());
        }
    }

    @Override
    public ProvisioningRequest findPendingPaymentByDomain(String domain) {
        return repository
                .findByTenantDomainAndStatusIn(
                        domain,
                        List.of(ProvisioningStatus.PENDING_EMAIL, ProvisioningStatus.PENDING_PAYMENT)
                )
                .orElse(null);
    }

    @Override
    public ProvisioningRequest findByPaymentToken(String token) {
        return repository.findByPaymentToken(token).orElse(null);
    }

    @Override
    public void save(ProvisioningRequest pr) {
        repository.save(pr);
    }

    private String generateTenantId() {
        return "t_" + RandomStringUtils.randomAlphanumeric(6).toLowerCase();
    }

    private String truncate(String msg, int max) {
        if (msg == null) return "Unknown error";
        return msg.length() > max ? msg.substring(0, max) + "..." : msg;
    }

    @Override
    public ProvisioningRequest findByAdminEmail(String email) {
        return repository
                .findTopByAdminEmailOrderByCreatedAtDesc(email)
                .orElse(null);
    }

    @Override
    public ProvisioningRequest findByOtpToken(String token) {
        return repository.findByOtpToken(token).orElse(null);
    }

    @Override
    public ProvisioningRequest createDemoRequest(PaymentInitRequest req) {

        String tenantDomain = req.getTenantDomain().trim().toLowerCase();
        String email        = req.getAdmin().getEmail();

        if (tenantRepository.existsByTenantDomain(tenantDomain) ||
                repository.existsByTenantDomain(tenantDomain)) {
            throw new IllegalStateException("Tenant domain already used");
        }

        String code     = String.valueOf((int)(Math.random() * 900000) + 100000);
        String otpToken = UUID.randomUUID().toString();

        ProvisioningRequest pr = ProvisioningRequest.builder()
                .tenantDomain(tenantDomain)
                .organizationName(req.getOrganizationName())
                .plan(req.getPlan())
                .adminEmail(email)
                .adminFirstName(req.getAdmin().getFirstName())
                .adminLastName(req.getAdmin().getLastName())
                .adminPhone(req.getAdmin().getPhone())
                .adminPassword(req.getAdmin().getPassword())
                .emailVerificationCode(code)
                .emailVerificationExpiresAt(LocalDateTime.now().plusMinutes(10))
                .emailVerified(false)
                .otpToken(otpToken)
                .accountType(AccountType.DEMO)           //  DEMO
                .status(ProvisioningStatus.PENDING_EMAIL)
                .attempts(0)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        pr = repository.save(pr);
        emailService.sendVerificationEmail(email, code);

        return pr;
    }

    @Override
    public ProvisioningRequest findByDomainAndDemoSuspended(String domain) {

        // 1. Verifier que le Tenant est suspendu via TenantRepository
        Tenant tenant = tenantRepository
                .findByTenantDomainAndAccountTypeAndSuspendedTrue(domain, AccountType.DEMO)
                .orElse(null);

        if (tenant == null) return null;

        // 2. Retrouver la ProvisioningRequest correspondante
        return repository.findByTenantDomain(domain).orElse(null);
    }
}