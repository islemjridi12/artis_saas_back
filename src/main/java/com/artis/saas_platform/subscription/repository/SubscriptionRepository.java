package com.artis.saas_platform.subscription.repository;



import com.artis.saas_platform.common.enums.SubscriptionStatus;
import com.artis.saas_platform.subscription.entity.Subscription;
import com.artis.saas_platform.tenancy.entity.Tenant;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface SubscriptionRepository extends JpaRepository<Subscription, UUID> {


        // 🔥 AJOUTER
        Optional<Subscription> findByTenantAndStatus(
                Tenant tenant,
                SubscriptionStatus status
        );

}
