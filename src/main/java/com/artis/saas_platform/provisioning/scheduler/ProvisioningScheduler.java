package com.artis.saas_platform.provisioning.scheduler;

import com.artis.saas_platform.common.enums.ProvisioningStatus;
import com.artis.saas_platform.provisioning.entity.ProvisioningRequest;
import com.artis.saas_platform.provisioning.publisher.ProvisioningEventPublisher;
import com.artis.saas_platform.provisioning.repository.ProvisioningRequestRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class ProvisioningScheduler {

    private final ProvisioningRequestRepository repository;
    private final ProvisioningEventPublisher publisher;

    @Scheduled(fixedDelay = 10000)
    public void processQueue() {

        log.info("SCHEDULER RUNNING...");

        List<ProvisioningRequest> requests =
                repository.findTop10ByStatusInOrderByCreatedAtAsc(
                        List.of(ProvisioningStatus.PENDING, ProvisioningStatus.RETRYING)
                );

        log.info("Found {} requests", requests.size());

        for (ProvisioningRequest pr : requests) {
            try {
                log.info("Publishing domain={} type={} migration={}",
                        pr.getTenantDomain(), pr.getAccountType(), pr.isMigrationPending());

                if (pr.isMigrationPending()) {
                    publisher.publishMigrate(pr);
                } else {
                    publisher.publishCreate(pr);
                }

                // Marquer comme IN_PROGRESS pour eviter double traitement
                pr.setStatus(ProvisioningStatus.IN_PROGRESS);
                pr.setUpdatedAt(LocalDateTime.now());
                repository.save(pr);

            } catch (Exception e) {
                log.error("Scheduler error domain={} error={}",
                        pr.getTenantDomain(), e.getMessage());
            }
        }
    }
}