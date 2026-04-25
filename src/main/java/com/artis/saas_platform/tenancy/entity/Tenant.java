package com.artis.saas_platform.tenancy.entity;
import com.artis.saas_platform.common.enums.TenantStatus;
import com.artis.saas_platform.provisioning.entity.AccountType;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "tenants")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Tenant {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false, unique = true)
    private String tenantId;

    @Column(nullable = false, unique = true)
    private String tenantDomain;

    @Column(nullable = false, unique = true)
    private String schemaName;

    @Column(nullable = false, unique = true)
    private String realm;

    @Column(nullable = false)
    private String organizationName;

    @Column(nullable = false)
    private String plan;

    @Column(nullable = false)
    private String adminEmail;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TenantStatus status;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    @Column
    @Enumerated(EnumType.STRING)
    private AccountType accountType;

    @Column
    private LocalDateTime demoExpiresAt;

    @Column
    private boolean suspended = false;

    private LocalDateTime suspendedAt;
}
