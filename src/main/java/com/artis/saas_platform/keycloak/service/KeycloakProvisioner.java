package com.artis.saas_platform.keycloak.service;


import com.artis.saas_platform.provisioning.entity.ProvisioningRequest;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

import lombok.extern.slf4j.Slf4j;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.representations.idm.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class KeycloakProvisioner {


    private final Keycloak keycloak;
    public KeycloakProvisioner(Keycloak keycloak) {
        this.keycloak = keycloak;
    }

    @Value("${app.frontend.url:http://localhost:57987}")
    private String frontendUrl;

    @Value("${app.frontend.client-id:artis-frontend}")
    private String frontendClientId;

    // ============================================================
    // 🔥 ENTRY POINT
    // ============================================================

    public void bootstrapTenant(ProvisioningRequest pr, String tenantId) {

        String realm = tenantId.trim().toLowerCase();

        log.info("[KEYCLOAK] ▶ START bootstrap → realm:{} domain:{}",
                realm, pr.getTenantDomain());

        try {

            // 1️⃣ Realm
            createRealmIfNotExists(realm, pr.getOrganizationName());

            // 2️⃣ Client
            createFrontendClientIfNotExists(realm);

            // 3️⃣ Roles
            createClientRoleIfNotExists(realm, frontendClientId, "role_admin");
            createClientRoleIfNotExists(realm, frontendClientId, "role_user");
            createClientRoleIfNotExists(realm, frontendClientId, "role_vendeur");

            // 4️⃣ Admin user
            String userId = createAdminUser(realm, pr);

            // 5️⃣ Assign role
            assignClientRole(realm, frontendClientId, userId, "role_admin");

            log.info("[KEYCLOAK] ✔ SUCCESS bootstrap → realm:{} admin:{}",
                    realm, pr.getAdminEmail());

        } catch (Exception e) {

            log.error("[KEYCLOAK] ❌ FAILED bootstrap → realm:{} error:{}",
                    realm, e.getMessage(), e);

            throw new RuntimeException("Keycloak provisioning failed", e);
        }
    }

    // ============================================================
    // 🔹 REALM
    // ============================================================

    public boolean realmExists(String realm) {
        try {
            keycloak.realm(realm).toRepresentation();
            return true;
        } catch (WebApplicationException e) {
            return false;
        }
    }

    private void createRealmIfNotExists(String realm, String displayName) {

        if (realmExists(realm)) {
            log.warn("[KEYCLOAK] Realm already exists: {}", realm);
            return;
        }

        RealmRepresentation rep = new RealmRepresentation();
        rep.setRealm(realm);
        rep.setDisplayName(displayName);
        rep.setEnabled(true);
        rep.setLoginWithEmailAllowed(true);
        rep.setDuplicateEmailsAllowed(false);
        rep.setRegistrationAllowed(false);
        rep.setSslRequired("external");

        keycloak.realms().create(rep);

        log.info("[KEYCLOAK] Realm created → id:{} name:{}",
                realm, displayName);
    }

    // ============================================================
    // 🔹 CLIENT
    // ============================================================

    private void createFrontendClientIfNotExists(String realm) {

        var existing = keycloak.realm(realm)
                .clients()
                .findByClientId(frontendClientId);

        if (existing != null && !existing.isEmpty()) {
            log.debug("[KEYCLOAK] Client already exists: {}", frontendClientId);
            return;
        }

        ClientRepresentation client = new ClientRepresentation();
        client.setClientId(frontendClientId);
        client.setName("ARTIS Frontend");
        client.setEnabled(true);
        client.setPublicClient(true);
        client.setStandardFlowEnabled(true);
        client.setDirectAccessGrantsEnabled(true);

        client.setRedirectUris(List.of(
                frontendUrl + "/*",
                "http://localhost:4201/*",
                "http://localhost:4200/*"
        ));
        client.setWebOrigins(List.of(
                frontendUrl,
                "http://localhost:4201",
                "http://localhost:4200"
        ));

        client.setAttributes(Map.of(
                "post.logout.redirect.uris", frontendUrl
        ));

        keycloak.realm(realm).clients().create(client);

        log.info("[KEYCLOAK] Client created → {} (realm:{})",
                frontendClientId, realm);
    }

    // ============================================================
    // 🔹 ROLES
    // ============================================================

    private void createClientRoleIfNotExists(String realm,
                                             String clientId,
                                             String roleName) {

        String clientUuid = getClientUuid(realm, clientId);

        try {
            keycloak.realm(realm)
                    .clients().get(clientUuid)
                    .roles().get(roleName)
                    .toRepresentation();

            log.debug("[KEYCLOAK] Role already exists: {}", roleName);

        } catch (WebApplicationException e) {

            RoleRepresentation role = new RoleRepresentation();
            role.setName(roleName);
            role.setClientRole(true);

            keycloak.realm(realm)
                    .clients().get(clientUuid)
                    .roles().create(role);

            log.info("[KEYCLOAK] Role created → {} (realm:{})",
                    roleName, realm);
        }
    }

    private void assignClientRole(String realm,
                                  String clientId,
                                  String userId,
                                  String roleName) {

        String clientUuid = getClientUuid(realm, clientId);

        RoleRepresentation role = keycloak.realm(realm)
                .clients().get(clientUuid)
                .roles().get(roleName)
                .toRepresentation();

        keycloak.realm(realm)
                .users().get(userId)
                .roles().clientLevel(clientUuid)
                .add(List.of(role));

        log.info("[KEYCLOAK] Role assigned → {} to user:{} realm:{}",
                roleName, userId, realm);
    }

    // ============================================================
    // 🔹 USER
    // ============================================================

    private String createAdminUser(String realm, ProvisioningRequest pr) {

        String username = pr.getAdminEmail().trim().toLowerCase();

        var existing = keycloak.realm(realm)
                .users()
                .search(username, true);

        if (existing != null && !existing.isEmpty()) {
            log.warn("[KEYCLOAK] User already exists: {}", username);
            return existing.get(0).getId();
        }

        UserRepresentation user = new UserRepresentation();
        user.setUsername(username);
        user.setEmail(pr.getAdminEmail());
        user.setFirstName(pr.getAdminFirstName());
        user.setLastName(pr.getAdminLastName());
        user.setEnabled(true);
        user.setEmailVerified(false);

        user.setRequiredActions(List.of("UPDATE_PASSWORD"));

        user.setAttributes(Map.of(
                "tenantDomain", List.of(pr.getTenantDomain()),
                "phone", List.of(pr.getAdminPhone())
        ));

        Response res = keycloak.realm(realm).users().create(user);

        if (res.getStatus() >= 300) {
            throw new RuntimeException("[KEYCLOAK] Create user failed: " + res.getStatus());
        }

        String location = res.getLocation().toString();
        String userId = location.substring(location.lastIndexOf('/') + 1);

        setTemporaryPassword(realm, userId, pr.getAdminPassword());

        log.info("[KEYCLOAK] Admin created → {} (realm:{})",
                username, realm);

        return userId;
    }

    private void setTemporaryPassword(String realm,
                                      String userId,
                                      String password) {

        CredentialRepresentation cred = new CredentialRepresentation();
        cred.setType(CredentialRepresentation.PASSWORD);
        cred.setValue(password);
        cred.setTemporary(true);

        keycloak.realm(realm)
                .users().get(userId)
                .resetPassword(cred);
    }

    // ============================================================
    // 🔹 UTIL
    // ============================================================

    private String getClientUuid(String realm, String clientId) {

        var clients = keycloak.realm(realm)
                .clients()
                .findByClientId(clientId);

        if (clients == null || clients.isEmpty()) {
            throw new RuntimeException(
                    "[KEYCLOAK] Client not found → " + clientId + " realm:" + realm
            );
        }

        return clients.get(0).getId();
    }
}