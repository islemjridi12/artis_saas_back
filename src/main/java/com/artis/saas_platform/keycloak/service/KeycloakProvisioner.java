package com.artis.saas_platform.keycloak.service;

import com.artis.saas_platform.provisioning.entity.AccountType;
import com.artis.saas_platform.provisioning.entity.ProvisioningRequest;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import lombok.extern.slf4j.Slf4j;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.representations.idm.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.net.http.HttpHeaders;
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
    // ENTRY POINT
    // ============================================================

    public void bootstrapTenant(ProvisioningRequest pr, String tenantId) {

        validateProvisioningRequest(pr);

        String realm = tenantId.trim().toLowerCase();

        log.info("[KEYCLOAK] START bootstrap -> realm:{} domain:{} email:{}",
                realm, pr.getTenantDomain(), pr.getAdminEmail());

        try {
            // 1. Realm
            createRealmIfNotExists(realm, pr.getOrganizationName());

            // 2. Client
            createFrontendClientIfNotExists(realm);

            // 3. Mapper tenantDomain -> JWT claim
            createTenantDomainMapper(realm);

            // 4. Mapper accountType -> JWT claim (DEMO ou PROD)
            createAccountTypeMapper(realm, String.valueOf(pr.getAccountType()));

            // 5. Roles
            createClientRoleIfNotExists(realm, frontendClientId, "role_admin");
            createClientRoleIfNotExists(realm, frontendClientId, "role_user");
            createClientRoleIfNotExists(realm, frontendClientId, "role_vendeur");

            // 6. Admin user
            String userId = createOrUpdateAdminUser(realm, pr);

            // 7. Assign role
            assignClientRole(realm, frontendClientId, userId, "role_admin");

            log.info("[KEYCLOAK] SUCCESS bootstrap -> realm:{} userId:{}", realm, userId);

        } catch (Exception e) {
            log.error("[KEYCLOAK] FAILED bootstrap -> realm:{} error:{}", realm, e.getMessage(), e);
            throw new RuntimeException("Keycloak provisioning failed: " + e.getMessage(), e);
        }
    }

    public void ensureTenantDomainMapper(String realm) {
        createTenantDomainMapper(realm);
    }

    // ============================================================
    // VALIDATION
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
    // REALM
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

        enableUnmanagedAttributes(realm);

        log.info("[KEYCLOAK] Realm created -> id:{}", realm);
    }

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
    // CLIENT
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
        log.info("[KEYCLOAK] Client created -> {} (realm:{})", frontendClientId, realm);
    }

    // ============================================================
    // PROTOCOL MAPPERS
    // ============================================================

    /**
     * Mapper tenantDomain : injecte le domaine du tenant dans le JWT
     * Exemple : tenantDomain=client1
     */
    private void createTenantDomainMapper(String realm) {
        createUserAttributeMapper(realm, "tenantDomain", "tenantDomain");
    }

    /**
     * Mapper accountType : injecte DEMO ou PROD dans le JWT
     * Utilise par TenantFilter pour choisir la datasource et le schema
     * Exemple : accountType=DEMO  -> artisdb_demo + schema demo_xxx
     *           accountType=PROD  -> artisdb      + schema tenant_xxx
     *
     * @param accountType valeur fixe a inserer dans l'attribut de l'admin user ("DEMO" ou "PROD")
     */
    private void createAccountTypeMapper(String realm, String accountType) {
        // On stocke accountType comme attribut user, puis on le mappe dans le JWT
        createUserAttributeMapper(realm, "accountType", "accountType");
        log.info("[KEYCLOAK] Mapper accountType created -> realm:{} value:{}", realm, accountType);
    }

    /**
     * Methode generique : cree un oidc-usermodel-attribute-mapper
     * si il n'existe pas deja sur le client
     */
    private void createUserAttributeMapper(String realm,
                                           String mapperName,
                                           String claimName) {
        String clientUuid = getClientUuid(realm, frontendClientId);

        var mappers = keycloak.realm(realm)
                .clients().get(clientUuid)
                .getProtocolMappers()
                .getMappers();

        boolean exists = mappers.stream()
                .anyMatch(m -> mapperName.equals(m.getName()));

        if (exists) {
            log.debug("[KEYCLOAK] Mapper {} already exists in realm {}", mapperName, realm);
            return;
        }

        ProtocolMapperRepresentation mapper = new ProtocolMapperRepresentation();
        mapper.setName(mapperName);
        mapper.setProtocol("openid-connect");
        mapper.setProtocolMapper("oidc-usermodel-attribute-mapper");

        Map<String, String> config = new HashMap<>();
        config.put("user.attribute", mapperName);
        config.put("claim.name", claimName);
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

        log.info("[KEYCLOAK] Mapper {} created -> realm:{}", mapperName, realm);
    }

    // ============================================================
    // ROLES
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
            log.info("[KEYCLOAK] Role created -> {} (realm:{})", roleName, realm);
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

        log.debug("[KEYCLOAK] Role assigned -> {} to user:{}", roleName, userId);
    }

    // ============================================================
    // USER
    // ============================================================

    private String createOrUpdateAdminUser(String realm, ProvisioningRequest pr) {
        String username = pr.getAdminEmail().trim().toLowerCase();
        String userId;

        var existing = keycloak.realm(realm)
                .users()
                .search(username, true);

        if (existing != null && !existing.isEmpty()) {
            userId = existing.get(0).getId();
            log.warn("[KEYCLOAK] User already exists, updating: {} (id:{})", username, userId);

            UserRepresentation user = keycloak.realm(realm).users().get(userId).toRepresentation();
            ensureAttributes(user, pr);
            user.setEnabled(true);
            keycloak.realm(realm).users().get(userId).update(user);

        } else {
            userId = createNewUser(realm, pr, username);
        }

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
        user.setEmailVerified(true);

        user.setRequiredActions(List.of("UPDATE_PASSWORD"));

        ensureAttributes(user, pr);

        Response res = keycloak.realm(realm).users().create(user);

        if (res.getStatus() >= 300) {
            String body = "";
            try { body = res.readEntity(String.class); } catch (Exception ignore) {}
            throw new RuntimeException(
                    "[KEYCLOAK] Create user failed: status=" + res.getStatus() + " body=" + body);
        }

        String location = res.getLocation().toString();
        String userId = location.substring(location.lastIndexOf('/') + 1);

        log.info("[KEYCLOAK] User created -> {} (id:{} realm:{})", username, userId, realm);
        return userId;
    }

    /**
     * Stocke tenantDomain ET accountType comme attributs Keycloak sur le user
     * Ces attributs sont ensuite injectes dans le JWT via les Protocol Mappers
     */
    private void ensureAttributes(UserRepresentation user, ProvisioningRequest pr) {
        Map<String, List<String>> attrs = user.getAttributes() != null
                ? new HashMap<>(user.getAttributes())
                : new HashMap<>();

        attrs.put("tenantDomain", List.of(pr.getTenantDomain()));

        // accountType : DEMO ou PROD — utilise par TenantFilter pour choisir la datasource
        AccountType accountType = pr.getAccountType() != null ? pr.getAccountType(): AccountType.PROD;
        attrs.put("accountType", List.of(String.valueOf(accountType)));

        if (pr.getAdminPhone() != null && !pr.getAdminPhone().isBlank()) {
            attrs.put("phone", List.of(pr.getAdminPhone()));
        }

        user.setAttributes(attrs);
    }

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
            log.info("[KEYCLOAK] Password set for userId:{} realm:{}", userId, realm);
        } catch (Exception e) {
            log.error("[KEYCLOAK] Failed to set password for userId:{} -> {}", userId, e.getMessage());
            throw new RuntimeException("Failed to set password: " + e.getMessage(), e);
        }
    }

    // ============================================================
    // UTIL
    // ============================================================

    private String getClientUuid(String realm, String clientId) {
        var clients = keycloak.realm(realm)
                .clients()
                .findByClientId(clientId);

        if (clients == null || clients.isEmpty()) {
            throw new RuntimeException(
                    "[KEYCLOAK] Client not found -> " + clientId + " realm:" + realm);
        }
        return clients.get(0).getId();
    }

    public void updateAccountTypeClaim(String realm, String accountType) {
        try {
            log.info("[KEYCLOAK] Updating accountType -> {} realm={}", accountType, realm);

            // Trouver tous les users du realm
            var users = keycloak.realm(realm).users().list();

            if (users == null || users.isEmpty()) {
                log.warn("[KEYCLOAK] No users found in realm={}", realm);
                return;
            }

            // Mettre à jour l'attribut accountType pour chaque user
            for (var userRep : users) {
                Map<String, List<String>> attrs = userRep.getAttributes() != null
                        ? new HashMap<>(userRep.getAttributes())
                        : new HashMap<>();

                attrs.put("accountType", List.of(accountType));
                userRep.setAttributes(attrs);

                keycloak.realm(realm)
                        .users()
                        .get(userRep.getId())
                        .update(userRep);

                log.info("[KEYCLOAK] accountType updated -> {} for user={} realm={}",
                        accountType, userRep.getEmail(), realm);
            }

        } catch (Exception e) {
            log.error("[KEYCLOAK] updateAccountTypeClaim failed realm={} error={}",
                    realm, e.getMessage());
        }
    }
}