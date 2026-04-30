package com.artis.saas_platform.tenancy.controller;

import com.artis.saas_platform.provisioning.entity.AccountType;
import com.artis.saas_platform.tenancy.entity.Tenant;
import com.artis.saas_platform.tenancy.repository.TenantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/tenant")
@RequiredArgsConstructor
public class TenantController {

    private final TenantRepository tenantRepository;

    @GetMapping("/resolve")
    public ResponseEntity<?> resolve(@RequestParam String domain) {

        Tenant tenant = tenantRepository
                .findByTenantDomain(domain.trim().toLowerCase())
                .orElse(null);

        if (tenant == null) {
            return ResponseEntity.status(404).body(Map.of("error", "Domain introuvable"));
        }

        AccountType accountType = tenant.getAccountType();
        boolean isDemo = accountType == AccountType.DEMO;
        String accountTypeName = accountType != null ? accountType.name() : "PROD";
        String database = isDemo ? "artisdb_demo" : "artisdb";

        String schema = tenant.getSchemaName();
        if (schema == null || schema.isBlank()) {
            schema = (isDemo ? "demo_" : "tenant_") + tenant.getTenantDomain();
        }

        String realm = tenant.getRealm();
        if (realm == null || realm.isBlank()) {
            realm = tenant.getTenantDomain();
        }

        Map<String, Object> response = new HashMap<>();
        response.put("realm", realm);
        response.put("domain", tenant.getTenantDomain());
        response.put("schema", schema);
        response.put("accountType", accountTypeName);
        response.put("database", database);

        return ResponseEntity.ok(response);
    }
}