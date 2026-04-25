package com.artis.saas_platform.payment.controller;


import com.artis.saas_platform.common.Email.EmailService;
import com.artis.saas_platform.common.enums.ProvisioningStatus;
import com.artis.saas_platform.payment.dto.AdminDto;
import com.artis.saas_platform.payment.dto.PaymentInitRequest;
import com.artis.saas_platform.payment.service.PaymeeService;
import com.artis.saas_platform.payment.verifier.PaymeeVerifier;
import com.artis.saas_platform.provisioning.entity.AccountType;
import com.artis.saas_platform.provisioning.entity.ProvisioningRequest;
import com.artis.saas_platform.provisioning.service.interfaces.ProvisioningService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/payment")
@RequiredArgsConstructor
public class PaymentController {

    private static final Logger log = LoggerFactory.getLogger(PaymentController.class);
    @Autowired
    PaymeeService paymeeService;
    @Autowired
    PaymeeVerifier verifier;
    @Autowired
    ProvisioningService provisioningService;

    @Autowired
    private EmailService emailService;

    @Value("${app.frontend.url}")
    private String frontendUrl;

@PostMapping("/init")
public ResponseEntity<?> init(@Valid @RequestBody PaymentInitRequest req) {

    try {

        ProvisioningRequest existing =
                provisioningService.findPendingPaymentByDomain(req.getTenantDomain());

        if (existing != null) {

            if (existing.getExpiresAt() != null &&
                    existing.getExpiresAt().isBefore(LocalDateTime.now())) {

                return ResponseEntity.status(410).body(Map.of(
                        "message", "Session expirée"
                ));
            }

            if (!existing.isEmailVerified()) {
                return ResponseEntity.ok(Map.of(
                        "step", "OTP",
                        "token", existing.getOtpToken()
                ));
            }

            Map<String, Object> res = paymeeService.createPayment(req);
            validatePaymeeResponse(res);

            Map<String, Object> data = (Map<String, Object>) res.get("data");

            String paymentUrl = data.get("payment_url").toString();
            String token = data.get("token").toString();

            existing.setPaymentToken(token);
            existing.setExpiresAt(LocalDateTime.now().plusHours(1));
            provisioningService.save(existing);

            return ResponseEntity.ok(Map.of(
                    "paymentUrl", paymentUrl,
                    "token", token
            ));
        }

        // 🔥 ICI peut lancer l’exception
        ProvisioningRequest pr = provisioningService.createInitialRequest(req);

        return ResponseEntity.ok(Map.of(
                "step", "OTP",
                "token", pr.getOtpToken()
        ));

    } catch (IllegalStateException ex) {

        if (ex.getMessage().contains("Tenant domain already used")) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(
                    Map.of("message", "Tenant déjà utilisé")
            );
        }

        throw ex;
    }
}

    @PostMapping(
            value = "/webhook",
            consumes = {
                    MediaType.APPLICATION_JSON_VALUE,
                    MediaType.APPLICATION_FORM_URLENCODED_VALUE
            }
    )
    public ResponseEntity<String> webhook(@RequestParam Map<String, String> payload) {

        log.info("[WEBHOOK] Received {}", payload);

        String token = payload.get("token");
        boolean paid = Boolean.parseBoolean(payload.get("payment_status"));
        String checkSum = payload.get("check_sum");

        if (!verifier.isValid(token, paid, checkSum)) {
            return ResponseEntity.status(403).body("Invalid checksum");
        }

        if (!paid) {
            provisioningService.markPaymentFailure(token);
            return ResponseEntity.ok("Payment failed");
        }

        // ✅ récupérer le provisioningRequest
        ProvisioningRequest pr = provisioningService.markPaymentSuccess(token);

        return ResponseEntity.ok("OK");
    }

//    @GetMapping("/redirect-success")
//    public void redirectSuccess(HttpServletResponse response) throws IOException {
//        response.setHeader("ngrok-skip-browser-warning", "true");
//        response.sendRedirect("http://localhost:4200/payment/success");
//    }
//
//    @GetMapping("/redirect-cancel")
//    public void redirectCancel(HttpServletResponse response) throws IOException {
//        response.sendRedirect("http://localhost:4200/payment/cancel");
//    }

    @GetMapping("/redirect-success")
    public void redirectSuccess(HttpServletResponse response) throws IOException {
        response.setHeader("ngrok-skip-browser-warning", "true");
        response.sendRedirect(frontendUrl + "/payment/success");
    }

    // 🔥 MODIFIER redirectCancel
    @GetMapping("/redirect-cancel")
    public void redirectCancel(HttpServletResponse response) throws IOException {
        response.sendRedirect(frontendUrl + "/payment/cancel");
    }

    @SuppressWarnings("unchecked")
    private void validatePaymeeResponse(Map<String, Object> res) {
        if (res == null || !Boolean.TRUE.equals(res.get("status"))) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Paymee error: " + res);
        }

        Map<String, Object> data = (Map<String, Object>) res.get("data");
        if (data == null || data.get("payment_url") == null || data.get("token") == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Paymee missing payment_url/token");
        }
    }

    @GetMapping("/resume/{token}")
    public ResponseEntity<?> resume(@PathVariable String token) {

        ProvisioningRequest pr =
                provisioningService.findByPaymentToken(token);

        if (pr == null) {
            return ResponseEntity.notFound().build();
        }

        // vérifier expiration
        if (pr.getExpiresAt() == null ||
                pr.getExpiresAt().isBefore(java.time.LocalDateTime.now())) {

            return ResponseEntity.status(410).body("Session expired");
        }

        // déjà traité
        if (pr.getStatus() != ProvisioningStatus.PENDING_PAYMENT) {
            return ResponseEntity.badRequest().body("Already processed");
        }

        return ResponseEntity.ok(pr);
    }

    @PostMapping("/verify-email")
    public ResponseEntity<?> verifyEmail(@RequestBody Map<String, String> body) {

        String token = body.get("token");
        String code  = body.get("code");

        ProvisioningRequest pr =
                provisioningService.findByOtpToken(token);

        if (pr == null) {
            return ResponseEntity.notFound().build();
        }

        if (pr.getEmailVerificationExpiresAt().isBefore(LocalDateTime.now())) {
            return ResponseEntity.status(410).body("Code expiré");
        }

        if (!code.equals(pr.getEmailVerificationCode())) {
            return ResponseEntity.status(400).body("Code invalide");
        }

        pr.setEmailVerified(true);
        pr.setStatus(ProvisioningStatus.PENDING_PAYMENT);
        provisioningService.save(pr);

        return ResponseEntity.ok(Map.of(
                "status", "OK"
        ));
    }
    @PostMapping("/init-after-verification")
    public ResponseEntity<?> initAfterVerification(@RequestBody Map<String, String> body) {

        String otpToken = body.get("otpToken");

        ProvisioningRequest pr = provisioningService.findByOtpToken(otpToken);

        if (pr == null) return ResponseEntity.notFound().build();
        if (!pr.isEmailVerified()) return ResponseEntity.status(403).body("Email non vérifié");

        PaymentInitRequest req = buildRequestFromPr(pr);

        Map<String, Object> res = paymeeService.createPayment(req);
        validatePaymeeResponse(res);

        Map<String, Object> data = (Map<String, Object>) res.get("data");
        String paymentUrl = data.get("payment_url").toString();
        String token = data.get("token").toString();

        pr.setPaymentToken(token);
        pr.setExpiresAt(LocalDateTime.now().plusHours(1));
        provisioningService.save(pr);

        return ResponseEntity.ok(Map.of("paymentUrl", paymentUrl, "token", token));
    }

    private PaymentInitRequest buildRequestFromPr(ProvisioningRequest pr) {
        PaymentInitRequest req = new PaymentInitRequest();
        req.setOrganizationName(pr.getOrganizationName());
        req.setTenantDomain(pr.getTenantDomain());
        req.setPlan(pr.getPlan());

        AdminDto admin = new AdminDto();
        admin.setFirstName(pr.getAdminFirstName());
        admin.setLastName(pr.getAdminLastName());
        admin.setEmail(pr.getAdminEmail());
        admin.setPhone(pr.getAdminPhone());
        admin.setPassword(pr.getAdminPassword());
        req.setAdmin(admin);

        return req;
    }
    @PostMapping("/demo")
    public ResponseEntity<?> requestDemo(@Valid @RequestBody PaymentInitRequest req) {

        // Vérifier domain disponible
        ProvisioningRequest existing =
                provisioningService.findPendingPaymentByDomain(req.getTenantDomain());

        if (existing != null) {
            return ResponseEntity.status(409).body(Map.of(
                    "message", "Domain déjà utilisé"
            ));
        }

        // Créer demande demo — pas de paiement
        ProvisioningRequest pr = provisioningService.createDemoRequest(req);

        return ResponseEntity.ok(Map.of(
                "step",  "OTP",
                "token", pr.getOtpToken()
        ));
    }

    @PostMapping("/init-demo-after-verification")
    public ResponseEntity<?> initDemoAfterVerification(@RequestBody Map<String, String> body) {

        String otpToken = body.get("otpToken");
        ProvisioningRequest pr = provisioningService.findByOtpToken(otpToken);

        if (pr == null) return ResponseEntity.notFound().build();
        if (!pr.isEmailVerified()) return ResponseEntity.status(403).body("Email non vérifié");

        //  Pas de paiement — lancer directement le provisioning
        pr.setStatus(ProvisioningStatus.PENDING); // → scheduler va le prendre
        pr.setUpdatedAt(LocalDateTime.now());
        provisioningService.save(pr);

        return ResponseEntity.ok(Map.of("status", "OK"));
    }



    @GetMapping(value = "/migrate-to-prod", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> showMigrationChoice(
            @RequestParam String domain,
            @RequestParam(required = false) String email) {

        ProvisioningRequest demo = provisioningService.findByDomainAndDemoSuspended(domain);

        if (demo == null) {
            return ResponseEntity.status(404).body("Demo non trouvée ou non expirée");
        }

        String finalEmail = demo.getAdminEmail();

        if (email != null && !email.equals(finalEmail)) {
            return ResponseEntity.status(403).body("Accès refusé");
        }

        String html = emailService.loadTemplate("migration-choice.html")
                .replace("{{organizationName}}", demo.getOrganizationName())
                .replace("{{domain}}", domain)
                .replace("{{email}}", finalEmail);

        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .body(html);
    }

    @PostMapping("/migrate-to-prod")
    public ResponseEntity<?> processMigrationChoice(
            @RequestParam String domain,
            @RequestParam boolean withData) {

        ProvisioningRequest demo = provisioningService.findByDomainAndDemoSuspended(domain);

        if (demo == null) {
            return ResponseEntity.status(404).body("Demo non trouvée");
        }

        demo.setMigrationPending(true);
        demo.setWithData(withData);
        provisioningService.save(demo);

        PaymentInitRequest req = buildRequestFromPr(demo);
        Map<String, Object> res = paymeeService.createPayment(req);
        validatePaymeeResponse(res);

        Map<String, Object> data = (Map<String, Object>) res.get("data");

        String paymeeUrl = data.get("payment_url").toString();
        String token = data.get("token").toString();

        demo.setPaymentToken(token);
        demo.setExpiresAt(LocalDateTime.now().plusHours(1));
        provisioningService.save(demo);

        return ResponseEntity.ok(Map.of("redirectUrl", paymeeUrl));
    }
}
