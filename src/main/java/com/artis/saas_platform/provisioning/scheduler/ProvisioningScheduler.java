package com.artis.saas_platform.provisioning.scheduler;

import com.artis.saas_platform.common.enums.ProvisioningStatus;
import com.artis.saas_platform.provisioning.entity.ProvisioningRequest;
import com.artis.saas_platform.provisioning.repository.ProvisioningRequestRepository;
import com.artis.saas_platform.provisioning.service.interfaces.ProvisioningService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class ProvisioningScheduler {

    private static final Logger log =
            LoggerFactory.getLogger(ProvisioningScheduler.class);

    private final ProvisioningRequestRepository repository;
    private final ProvisioningService provisioningService;

    @Scheduled(fixedDelay = 10000)
    public void processQueue() {

        log.info("🔥 SCHEDULER RUNNING...");

        List<ProvisioningRequest> requests =
                repository.findTop10ByStatusInOrderByCreatedAtAsc(
                        List.of(ProvisioningStatus.PENDING, ProvisioningStatus.RETRYING)
                );

        log.info("➡️ Found {} requests", requests.size());

        for (ProvisioningRequest pr : requests) {

            try {
                log.info("🚀 Processing domain={} status={}",
                        pr.getTenantDomain(), pr.getStatus());

                provisioningService.process(pr.getId());

            } catch (Exception e) {
                log.error("❌ Scheduler error domain={} error={}",
                        pr.getTenantDomain(), e.getMessage(), e);
            }
        }
    }
}