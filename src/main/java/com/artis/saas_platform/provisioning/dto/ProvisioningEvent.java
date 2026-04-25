package com.artis.saas_platform.provisioning.dto;

import com.artis.saas_platform.provisioning.entity.AccountType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProvisioningEvent {

    // ─── Identite du tenant ───
    private UUID requestId;
    private String tenantDomain;
    private String organizationName;
    private String realm;
    private String tenantId;          // String pour aligner sur ProvisioningRequest.tenantId

    // ─── Admin user ───
    private String adminEmail;
    private String adminFirstName;
    private String adminPassword;

    // ─── Souscription ───
    private String plan;
    private AccountType accountType;
    private boolean migrationPending;
    private boolean withData;

    // ─── Schemas nommes (info pour consumers avances) ───
    private String demoSchemaName;    // demo_xxx (null si PROD direct)
    private String prodSchemaName;    // tenant_xxx

    // ─── Champs pre-resolus pour Argo Events ───
    // Argo Events ne peut pas faire de if/else en YAML,
    // donc on lui fournit directement les valeurs a utiliser.
    private String schemaName;        // demo_xxx ou tenant_xxx selon le contexte
    private String databaseName;      // artisdb_demo ou artisdb
    private String devSchema;         // atlas_dev_xxx (schema jetable pour Atlas)
    private String demoDatabaseName;
}