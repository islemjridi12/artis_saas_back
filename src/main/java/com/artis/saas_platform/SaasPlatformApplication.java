package com.artis.saas_platform;

import com.artis.saas_platform.common.util.SSLUtil;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling

public class SaasPlatformApplication {

    public static void main(String[] args) {
        SSLUtil.disableSSL();
        SpringApplication.run(SaasPlatformApplication.class, args);
    }

}
