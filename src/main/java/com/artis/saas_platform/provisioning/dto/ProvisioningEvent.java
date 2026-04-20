package com.artis.saas_platform.provisioning.dto;

import com.artis.saas_platform.provisioning.entity.AccountType;
import lombok.Builder;
import lombok.Data;
import java.util.UUID;

@Data
@Builder
public class ProvisioningEvent {
    private UUID requestId;
    private String tenantDomain;
    private String organizationName;
    private String adminEmail;
    private String adminFirstName;
    private String adminPassword;
    private String plan;
    private String realm;
    private String tenantId;
    private AccountType accountType;
    private boolean migrationPending;
    private boolean withData;
    private String demoSchemaName;
    private String prodSchemaName;
}