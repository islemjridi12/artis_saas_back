package com.artis.saas_platform.provisioning.entity;

import com.artis.saas_platform.common.enums.ProvisioningStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "provisioning_requests")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProvisioningRequest {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false, unique = true)
    private String tenantDomain;

    @Column(nullable = false)
    private String organizationName;

    @Column(nullable = false)
    private String plan;

    @Column(nullable = false)
    private String adminEmail;

    @Column(nullable = false)
    private String adminFirstName;

    @Column(nullable = false)
    private String adminLastName;

    @Column(nullable = false)
    private String adminPhone;

    @Column(nullable = false, length = 500)
    private String adminPassword;

    @Column(unique = true)
    private String paymentToken;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ProvisioningStatus status;

    @Column(nullable = false)
    private Integer attempts;

    @Column(length = 1000)
    private String errorMessage;

    private String tenantId;
    private String schemaName;
    private String realm;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime expiresAt;

    private String emailVerificationCode;
    private LocalDateTime emailVerificationExpiresAt;
    private boolean emailVerified;

    @Column(unique = true)
    private String otpToken;

    @Column
    @Enumerated(EnumType.STRING)
    private AccountType accountType;

    @Column
    private boolean migrationPending = false;

    @Column
    private boolean withData = false;
}