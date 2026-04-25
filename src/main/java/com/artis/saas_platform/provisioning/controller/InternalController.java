package com.artis.saas_platform.provisioning.controller;

import com.artis.saas_platform.provisioning.dto.ProvisioningCompleteRequest;
import com.artis.saas_platform.provisioning.service.ProvisioningCompleteService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Controller pour les endpoints internes appeles par le pipeline Argo.
 *
 * Tous les endpoints sous /api/internal/** sont proteges par
 * InternalAuthFilter qui verifie le header X-Internal-Secret.
 *
 * Ces endpoints ne doivent JAMAIS etre exposes publiquement.
 */
@RestController
@RequestMapping("/api/internal/provisioning")
@RequiredArgsConstructor
@Slf4j
public class InternalController {

    private final ProvisioningCompleteService completeService;

    /**
     * Appele par le step "notify" du workflow Argo apres provisioning reussi.
     *
     * Actions effectuees :
     *   1. Retrouve le ProvisioningRequest par requestId
     *   2. Cree l'entite Tenant en base
     *   3. Si PROD : cree une Subscription active
     *   4. Met a jour le status -> COMPLETED
     *   5. Envoie l'email de bienvenue (demo-ready ou tenant-ready)
     */
    @PostMapping("/complete")
    public ResponseEntity<Map<String, String>> complete(
            @RequestBody ProvisioningCompleteRequest request) {

        log.info("[INTERNAL] /complete received → domain={} status={}",
                request.getDomain(), request.getStatus());

        try {
            completeService.handleComplete(request);
            return ResponseEntity.ok(Map.of(
                    "status", "OK",
                    "domain", request.getDomain()
            ));
        } catch (IllegalStateException e) {
            // ProvisioningRequest deja COMPLETED ou introuvable
            log.warn("[INTERNAL] /complete business error → {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "ERROR",
                    "message", e.getMessage()
            ));
        } catch (Exception e) {
            log.error("[INTERNAL] /complete failed → domain={} error={}",
                    request.getDomain(), e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "status", "ERROR",
                    "message", e.getMessage()
            ));
        }
    }
}