package com.artis.saas_platform.provisioning.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Payload recue du workflow Argo apres provisioning reussi.
 *
 * Le pipeline Argo envoie un JSON de ce format vers
 * POST /api/internal/provisioning/complete :
 *
 * {
 *   "requestId": "uuid-de-la-provisioning-request",
 *   "domain":    "testv9",
 *   "status":    "SUCCESS"
 * }
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProvisioningCompleteRequest {

    /** UUID de la ProvisioningRequest (set par le Sensor depuis body.requestId). */
    private UUID requestId;

    /** tenantDomain, ex: "testv9". */
    private String domain;

    /** "SUCCESS" ou "FAILED" (pour remonter l'erreur eventuelle du pipeline). */
    private String status;

    /** Optionnel : message d'erreur si status=FAILED. */
    private String errorMessage;
}