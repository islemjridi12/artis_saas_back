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

    private final RabbitTemplate rabbitTemplate;

    public void publishCreate(ProvisioningRequest pr) {
        rabbitTemplate.convertAndSend(
                "provisioning.exchange", "provisioning.create", buildEvent(pr));
        log.info("[RABBIT] Published provisioning.create → {}", pr.getTenantDomain());
    }

    public void publishMigrate(ProvisioningRequest pr) {
        rabbitTemplate.convertAndSend(
                "provisioning.exchange", "provisioning.migrate", buildEvent(pr));
        log.info("[RABBIT] Published provisioning.migrate → {}", pr.getTenantDomain());
    }

    public void publishExpire(ProvisioningRequest pr) {
        rabbitTemplate.convertAndSend(
                "provisioning.exchange", "provisioning.expire", buildEvent(pr));
        log.info("[RABBIT] Published provisioning.expire → {}", pr.getTenantDomain());
    }

    private ProvisioningEvent buildEvent(ProvisioningRequest pr) {
        boolean isDemo = pr.getAccountType() == AccountType.DEMO;
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
                .accountType(pr.getAccountType())
                .migrationPending(pr.isMigrationPending())
                .withData(pr.isWithData())
                .demoSchemaName(isDemo ? "demo_" + pr.getTenantDomain() : null)
                .prodSchemaName("tenant_" + pr.getTenantDomain())
                .build();
    }
}
