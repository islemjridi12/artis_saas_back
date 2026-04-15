package com.artis.saas_platform.tenancy.controller;

import com.artis.saas_platform.tenancy.entity.Tenant;
import com.artis.saas_platform.tenancy.repository.TenantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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
            return ResponseEntity.status(404).body("Domain introuvable");
        }

        return ResponseEntity.ok(Map.of(
                "realm",  tenant.getRealm(),
                "domain", tenant.getTenantDomain()
        ));
    }
}