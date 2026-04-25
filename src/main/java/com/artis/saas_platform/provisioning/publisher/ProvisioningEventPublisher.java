package com.artis.saas_platform.provisioning.publisher;

import com.artis.saas_platform.provisioning.dto.ProvisioningEvent;
import com.artis.saas_platform.provisioning.entity.AccountType;
import com.artis.saas_platform.provisioning.entity.ProvisioningRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class ProvisioningEventPublisher {

    private static final String EXCHANGE = "provisioning.exchange";

    private final RabbitTemplate rabbitTemplate;

    // ───────────────────────────────────────────────────────────────────────
    // CREATE : nouveau tenant DEMO ou PROD
    // ───────────────────────────────────────────────────────────────────────
    public void publishCreate(ProvisioningRequest pr) {
        rabbitTemplate.convertAndSend(EXCHANGE, "provisioning.create", buildCreateEvent(pr));
        log.info("[RABBIT] Published provisioning.create → {}", pr.getTenantDomain());
    }

    // ───────────────────────────────────────────────────────────────────────
    // MIGRATE : DEMO → PROD (accountType du request reste DEMO, on force PROD)
    // ───────────────────────────────────────────────────────────────────────
    public void publishMigrate(ProvisioningRequest pr) {
        rabbitTemplate.convertAndSend(EXCHANGE, "provisioning.migrate", buildMigrateEvent(pr));
        log.info("[RABBIT] Published provisioning.migrate → {}", pr.getTenantDomain());
    }

    // ───────────────────────────────────────────────────────────────────────
    // EXPIRE : suppression tenant DEMO expire (schema deja connu en base)
    // ───────────────────────────────────────────────────────────────────────
    public void publishExpire(ProvisioningRequest pr) {
        rabbitTemplate.convertAndSend(EXCHANGE, "provisioning.expire", buildExpireEvent(pr));
        log.info("[RABBIT] Published provisioning.expire → {}", pr.getTenantDomain());
    }

    // ───────────────────────────────────────────────────────────────────────
    // Builders
    // ───────────────────────────────────────────────────────────────────────

    private ProvisioningEvent buildCreateEvent(ProvisioningRequest pr) {
        boolean isDemo = pr.getAccountType() == AccountType.DEMO;
        String domain = pr.getTenantDomain();

        return baseBuilder(pr)
                .accountType(pr.getAccountType())
                .demoSchemaName(isDemo ? "demo_" + domain : null)
                .prodSchemaName("tenant_" + domain)
                // ─── Champs pre-resolus pour Argo Events ───
                .schemaName(isDemo ? "demo_" + domain : "tenant_" + domain)
                .databaseName(isDemo ? "artisdb_demo" : "artisdb")
                .devSchema("atlas_dev_" + domain)
                .build();
    }

    private ProvisioningEvent buildMigrateEvent(ProvisioningRequest pr) {
        String domain = pr.getTenantDomain();
        return baseBuilder(pr)
                .accountType(AccountType.PROD)
                .migrationPending(true)
                .demoSchemaName("demo_" + domain)
                .prodSchemaName("tenant_" + domain)
                .schemaName("tenant_" + domain)
                .databaseName("artisdb")
                .demoDatabaseName("artisdb_demo")    // ← AJOUT
                .devSchema("atlas_dev_" + domain)
                .build();
    }

    private ProvisioningEvent buildExpireEvent(ProvisioningRequest pr) {
        // Pour expire : schemaName est DEJA en base (persiste lors du provisioning)
        // Pas besoin de recalculer, on prend depuis l'entite.
        String schemaFromDb = pr.getSchemaName();   // ex: demo_testv7
        String databaseName = schemaFromDb != null && schemaFromDb.startsWith("demo_")
                ? "artisdb_demo"
                : "artisdb";

        return baseBuilder(pr)
                .accountType(pr.getAccountType())
                .schemaName(schemaFromDb)
                .databaseName(databaseName)
                // devSchema pas utile pour expire (juste un DROP SCHEMA)
                .build();
    }

    /**
     * Remplit les champs communs a tous les types d'evenements.
     * Les champs specifiques (accountType, schemaName, etc.) sont ajoutes
     * par les builders dedies.
     */
    private ProvisioningEvent.ProvisioningEventBuilder baseBuilder(ProvisioningRequest pr) {
        return ProvisioningEvent.builder()
                .requestId(pr.getId())
                .tenantDomain(pr.getTenantDomain())
                .organizationName(pr.getOrganizationName())
                .adminEmail(pr.getAdminEmail())
                .adminFirstName(pr.getAdminFirstName())
                .adminPassword(pr.getAdminPassword())
                .plan(pr.getPlan())
                .realm(pr.getRealm())
                .tenantId(pr.getTenantId())
                .withData(pr.isWithData());
    }
}