package com.artis.saas_platform.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Filter qui protege les endpoints /api/internal/**.
 *
 * Verifie que chaque requete entrante contient le header :
 *   X-Internal-Secret: <valeur-du-secret>
 *
 * La valeur attendue est lue depuis la config (app.internal.secret),
 * qui est elle-meme injectee depuis un Secret Kubernetes.
 *
 * Si le header manque ou ne matche pas, retourne 403 Forbidden.
 */
@Component
@Slf4j
public class InternalAuthFilter extends OncePerRequestFilter {

    private static final String HEADER_NAME = "X-Internal-Secret";
    private static final String INTERNAL_PATH_PREFIX = "/api/internal/";

    @Value("${app.internal.secret:}")
    private String expectedSecret;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        String path = request.getRequestURI();

        // Filtrer UNIQUEMENT les endpoints internes
        if (!path.startsWith(INTERNAL_PATH_PREFIX)) {
            filterChain.doFilter(request, response);
            return;
        }

        // Verifier que le secret est configure cote serveur
        if (expectedSecret == null || expectedSecret.isBlank()) {
            log.error("[SECURITY] app.internal.secret not configured, blocking request");
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "Internal endpoint not properly configured");
            return;
        }

        // Verifier le header
        String providedSecret = request.getHeader(HEADER_NAME);
        if (providedSecret == null || !expectedSecret.equals(providedSecret)) {
            log.warn("[SECURITY] Rejected call to {} from {} (missing or invalid secret)",
                    path, request.getRemoteAddr());
            response.sendError(HttpServletResponse.SC_FORBIDDEN,
                    "Forbidden: invalid or missing internal secret");
            return;
        }

        // Secret OK, on laisse passer
        filterChain.doFilter(request, response);
    }
}