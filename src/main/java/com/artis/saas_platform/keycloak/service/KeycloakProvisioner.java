package com.artis.saas_platform.keycloak.service;

import com.artis.saas_platform.provisioning.entity.ProvisioningRequest;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import lombok.extern.slf4j.Slf4j;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.representations.idm.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class KeycloakProvisioner {

    private final Keycloak keycloak;

    public KeycloakProvisioner(Keycloak keycloak) {
        this.keycloak = keycloak;
    }

    @Value("${app.frontend.url:http://localhost:4201}")
    private String frontendUrl;

    @Value("${app.frontend.client-id:artis-frontend}")
    private String frontendClientId;

    // ============================================================
    // 🔥 ENTRY POINT
    // ============================================================

    public void bootstrapTenant(ProvisioningRequest pr, String tenantId) {

        // 🔥 VALIDATION PRÉALABLE — fail fast
        validateProvisioningRequest(pr);

        String realm = tenantId.trim().toLowerCase();

        log.info("[KEYCLOAK] ▶ START bootstrap → realm:{} domain:{} email:{}",
                realm, pr.getTenantDomain(), pr.getAdminEmail());

        try {
            // 1. Realm
            createRealmIfNotExists(realm, pr.getOrganizationName());

            // 2. Client
            createFrontendClientIfNotExists(realm);

            // 3. Mapper tenantDomain
            createTenantDomainMapper(realm);

            // 4. Roles
            createClientRoleIfNotExists(realm, frontendClientId, "role_admin");
            createClientRoleIfNotExists(realm, frontendClientId, "role_user");
            createClientRoleIfNotExists(realm, frontendClientId, "role_vendeur");

            // 5. Admin user
            String userId = createOrUpdateAdminUser(realm, pr);

            // 6. Assign role
            assignClientRole(realm, frontendClientId, userId, "role_admin");

            log.info("[KEYCLOAK] ✔ SUCCESS bootstrap → realm:{} userId:{}",
                    realm, userId);

        } catch (Exception e) {
            log.error("[KEYCLOAK] ❌ FAILED bootstrap → realm:{} error:{}",
                    realm, e.getMessage(), e);
            throw new RuntimeException("Keycloak provisioning failed: " + e.getMessage(), e);
        }
    }

    // 🔥 NOUVEAU : Méthode publique exposée pour le patch des realms existants
    public void ensureTenantDomainMapper(String realm) {
        createTenantDomainMapper(realm);
    }

    // ============================================================
    // 🔹 VALIDATION
    // ============================================================

    private void validateProvisioningRequest(ProvisioningRequest pr) {
        if (pr == null) {
            throw new IllegalArgumentException("ProvisioningRequest is null");
        }
        if (pr.getTenantDomain() == null || pr.getTenantDomain().isBlank()) {
            throw new IllegalArgumentException("tenantDomain is null/blank");
        }
        if (pr.getAdminEmail() == null || pr.getAdminEmail().isBlank()) {
            throw new IllegalArgumentException("adminEmail is null/blank");
        }
        if (pr.getAdminPassword() == null || pr.getAdminPassword().isBlank()) {
            throw new IllegalArgumentException(
                    "adminPassword is null/blank for tenant " + pr.getTenantDomain());
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

        // 🔥 NOUVEAU : Activer unmanaged attributes pour permettre tenantDomain
        enableUnmanagedAttributes(realm);

        log.info("[KEYCLOAK] Realm created → id:{}", realm);
    }

    /**
     * 🔥 NOUVEAU : Active la User Profile policy pour permettre les attributs custom
     * Sur Keycloak 26, par défaut, les attributs non déclarés sont rejetés silencieusement
     */
    private void enableUnmanagedAttributes(String realm) {
        try {
            var userProfile = keycloak.realm(realm).users().userProfile().getConfiguration();
            userProfile.setUnmanagedAttributePolicy(
                    org.keycloak.representations.userprofile.config.UPConfig.UnmanagedAttributePolicy.ENABLED);
            keycloak.realm(realm).users().userProfile().update(userProfile);
            log.info("[KEYCLOAK] Unmanaged attributes enabled for realm {}", realm);
        } catch (Exception e) {
            log.warn("[KEYCLOAK] Could not enable unmanaged attributes: {}", e.getMessage());
        }
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
        log.info("[KEYCLOAK] Client created → {} (realm:{})", frontendClientId, realm);
    }

    // ============================================================
    // 🔹 PROTOCOL MAPPER (tenantDomain → JWT claim)
    // ============================================================

    private void createTenantDomainMapper(String realm) {
        String clientUuid = getClientUuid(realm, frontendClientId);

        var mappers = keycloak.realm(realm)
                .clients().get(clientUuid)
                .getProtocolMappers()
                .getMappers();

        boolean exists = mappers.stream()
                .anyMatch(m -> "tenantDomain".equals(m.getName()));

        if (exists) {
            log.debug("[KEYCLOAK] Mapper tenantDomain already exists in realm {}", realm);
            return;
        }

        ProtocolMapperRepresentation mapper = new ProtocolMapperRepresentation();
        mapper.setName("tenantDomain");
        mapper.setProtocol("openid-connect");
        mapper.setProtocolMapper("oidc-usermodel-attribute-mapper");

        Map<String, String> config = new HashMap<>();
        config.put("user.attribute", "tenantDomain");
        config.put("claim.name", "tenantDomain");
        config.put("jsonType.label", "String");
        config.put("id.token.claim", "true");
        config.put("access.token.claim", "true");
        config.put("userinfo.token.claim", "true");
        config.put("multivalued", "false");
        mapper.setConfig(config);

        keycloak.realm(realm)
                .clients().get(clientUuid)
                .getProtocolMappers()
                .createMapper(mapper);

        log.info("[KEYCLOAK] Mapper tenantDomain created → realm:{}", realm);
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
            log.info("[KEYCLOAK] Role created → {} (realm:{})", roleName, realm);
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

        log.debug("[KEYCLOAK] Role assigned → {} to user:{}", roleName, userId);
    }

    // ============================================================
    // 🔹 USER (création OU mise à jour)
    // ============================================================

    /**
     * 🔥 NOUVEAU : Crée le user OU le met à jour si déjà existant
     * Garantit que le password est toujours bien set
     */
    private String createOrUpdateAdminUser(String realm, ProvisioningRequest pr) {
        String username = pr.getAdminEmail().trim().toLowerCase();
        String userId;

        var existing = keycloak.realm(realm)
                .users()
                .search(username, true);

        if (existing != null && !existing.isEmpty()) {
            // User existe déjà → on met à jour
            userId = existing.get(0).getId();
            log.warn("[KEYCLOAK] User already exists, updating: {} (id:{})", username, userId);

            // Mettre à jour les attributs au cas où ils manquent
            UserRepresentation user = keycloak.realm(realm).users().get(userId).toRepresentation();
            ensureAttributes(user, pr);
            user.setEnabled(true);
            keycloak.realm(realm).users().get(userId).update(user);

        } else {
            // Création
            userId = createNewUser(realm, pr, username);
        }

        // 🔥 TOUJOURS set le password (même si user existait)
        ensurePasswordSet(realm, userId, pr.getAdminPassword());

        return userId;
    }

    private String createNewUser(String realm, ProvisioningRequest pr, String username) {
        UserRepresentation user = new UserRepresentation();
        user.setUsername(username);
        user.setEmail(pr.getAdminEmail());
        user.setFirstName(pr.getAdminFirstName());
        user.setLastName(pr.getAdminLastName());
        user.setEnabled(true);
        user.setEmailVerified(true);   // 🔥 true pour éviter les blocages

        user.setRequiredActions(List.of("UPDATE_PASSWORD"));

        ensureAttributes(user, pr);

        Response res = keycloak.realm(realm).users().create(user);

        if (res.getStatus() >= 300) {
            String body = "";
            try {
                body = res.readEntity(String.class);
            } catch (Exception ignore) {}
            throw new RuntimeException(
                    "[KEYCLOAK] Create user failed: status=" + res.getStatus() + " body=" + body);
        }

        String location = res.getLocation().toString();
        String userId = location.substring(location.lastIndexOf('/') + 1);

        log.info("[KEYCLOAK] User created → {} (id:{} realm:{})", username, userId, realm);
        return userId;
    }

    private void ensureAttributes(UserRepresentation user, ProvisioningRequest pr) {
        Map<String, List<String>> attrs = user.getAttributes() != null
                ? new HashMap<>(user.getAttributes())
                : new HashMap<>();

        attrs.put("tenantDomain", List.of(pr.getTenantDomain()));
        if (pr.getAdminPhone() != null && !pr.getAdminPhone().isBlank()) {
            attrs.put("phone", List.of(pr.getAdminPhone()));
        }

        user.setAttributes(attrs);
    }

    /**
     * 🔥 NOUVEAU : Set le password de manière fiable + vérification
     */
    private void ensurePasswordSet(String realm, String userId, String password) {
        if (password == null || password.isBlank()) {
            throw new RuntimeException("Cannot set null/empty password");
        }

        CredentialRepresentation cred = new CredentialRepresentation();
        cred.setType(CredentialRepresentation.PASSWORD);
        cred.setValue(password);
        cred.setTemporary(true);

        try {
            keycloak.realm(realm)
                    .users().get(userId)
                    .resetPassword(cred);

            log.info("[KEYCLOAK] ✓ Password set for userId:{} realm:{}", userId, realm);

        } catch (Exception e) {
            log.error("[KEYCLOAK] ❌ Failed to set password for userId:{} → {}",
                    userId, e.getMessage());
            throw new RuntimeException("Failed to set password: " + e.getMessage(), e);
        }
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
                    "[KEYCLOAK] Client not found → " + clientId + " realm:" + realm);
        }
        return clients.get(0).getId();
    }
}