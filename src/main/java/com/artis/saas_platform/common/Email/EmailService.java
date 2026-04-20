package com.artis.saas_platform.common.Email;

import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;

@Service
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    @Autowired
    private JavaMailSender mailSender;

    //  AJOUTER ces deux champs
    @Value("${app.platform.url}")
    private String platformUrl;

    @Value("${app.metier.url}")
    private String metierUrl;

    public void sendVerificationEmail(String to, String code) {
        String html = loadTemplate("otp-verification.html")
                .replace("{{code}}", code);
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setTo(to);
            helper.setSubject("Votre code de verification ARTIS");
            helper.setText(html, true);
            mailSender.send(message);
            log.info("[EMAIL] OTP sent → {}", to);
        } catch (Exception e) {
            log.error("[EMAIL] Failed OTP → {} error={}", to, e.getMessage());
        }
    }

    public void sendTenantReadyEmail(
            String to, String firstName, String organizationName,
            String tenantDomain, String tempPassword) {

        // 🔥 Utiliser metierUrl au lieu de localhost
        String loginUrl = metierUrl + "/signin";

        String passwordText = (tempPassword == null
                || tempPassword.equals("**CLEARED**"))
                ? "Votre mot de passe reste inchange"
                : tempPassword;

        String html = loadTemplate("tenant-ready.html")
                .replace("{{firstName}}",        firstName != null ? firstName : "")
                .replace("{{organizationName}}", organizationName)
                .replace("{{loginUrl}}",         loginUrl)
                .replace("{{tenantDomain}}",     tenantDomain)
                .replace("{{adminEmail}}",       to)
                .replace("{{tempPassword}}",     passwordText);

        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setTo(to);
            helper.setSubject("Votre espace " + organizationName + " est pret !");
            helper.setText(html, true);
            mailSender.send(message);
            log.info("[EMAIL] Tenant ready sent → {}", to);
        } catch (Exception e) {
            log.error("[EMAIL] Failed → {} error={}", to, e.getMessage());
        }
    }

    public void sendDemoExpiredEmail(String to, String orgName, String domain) {

        // 🔥 Utiliser platformUrl au lieu de localhost
        String migrateUrl = platformUrl + "/api/payment/migrate-to-prod"
                + "?domain=" + domain
                + "&email=" + to;

        String html = loadTemplate("demo-expired.html")
                .replace("{{organizationName}}", orgName)
                .replace("{{domain}}",           domain)
                .replace("{{registerUrl}}",      migrateUrl);

        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setTo(to);
            helper.setSubject("Votre periode d essai ARTIS a expire");
            helper.setText(html, true);
            mailSender.send(message);
            log.info("[EMAIL] Demo expired sent → {}", to);
        } catch (Exception e) {
            log.error("[EMAIL] Demo expired failed → {}", e.getMessage());
        }
    }

    public void sendDemoReadyEmail(
            String to, String firstName, String organizationName,
            String tenantDomain, String tempPassword) {

        // 🔥 Utiliser metierUrl au lieu de localhost
        String loginUrl = metierUrl + "/signin";

        String html = loadTemplate("demo-ready.html")
                .replace("{{firstName}}",        firstName)
                .replace("{{organizationName}}", organizationName)
                .replace("{{loginUrl}}",         loginUrl)
                .replace("{{tenantDomain}}",     tenantDomain)
                .replace("{{adminEmail}}",       to)
                .replace("{{tempPassword}}",     tempPassword)
                .replace("{{expiresIn}}",        "2 semaines");

        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setTo(to);
            helper.setSubject("Votre essai gratuit ARTIS est pret !");
            helper.setText(html, true);
            mailSender.send(message);
            log.info("[EMAIL] Demo ready sent → {}", to);
        } catch (Exception e) {
            log.error("[EMAIL] Failed demo ready → {} error={}", to, e.getMessage());
        }
    }

    public String loadTemplate(String filename) {
        try {
            ClassPathResource resource =
                    new ClassPathResource("templates/" + filename);
            return new String(
                    resource.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8
            );
        } catch (Exception e) {
            throw new RuntimeException("Template not found: " + filename, e);
        }
    }
}