package com.artis.saas_platform.common.config;


import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.orm.jpa.EntityManagerFactoryBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.util.Map;

@Configuration
@EnableJpaRepositories(
        basePackages = {
                "com.artis.saas_platform.tenancy.repository",
                "com.artis.saas_platform.provisioning.repository",
                "com.artis.saas_platform.subscription.repository"
        },
        entityManagerFactoryRef = "platformEntityManagerFactory",
        transactionManagerRef   = "platformTransactionManager"
)
public class PlatformDataSourceConfig {

    @Primary
    @Bean("platformDataSourceProperties")
    @ConfigurationProperties("spring.datasource")
    public DataSourceProperties platformDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Primary
    @Bean("platformDataSource")
    public DataSource platformDataSource(
            @Qualifier("platformDataSourceProperties")
            DataSourceProperties props) {
        return props.initializeDataSourceBuilder().build();
    }

    @Primary
    @Bean("platformEntityManagerFactory")
    public LocalContainerEntityManagerFactoryBean platformEntityManagerFactory(
            EntityManagerFactoryBuilder builder,
            @Qualifier("platformDataSource") DataSource ds) {

        return builder
                .dataSource(ds)
                .packages(
                        "com.artis.saas_platform.tenancy.entity",
                        "com.artis.saas_platform.provisioning.entity",
                        "com.artis.saas_platform.subscription.entity"
                )
                .persistenceUnit("platform")
                .properties(Map.of(
                        "hibernate.hbm2ddl.auto", "update",
                        "hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect"
                ))
                .build();
    }

    @Primary
    @Bean("platformTransactionManager")
    public PlatformTransactionManager platformTransactionManager(
            @Qualifier("platformEntityManagerFactory")
            LocalContainerEntityManagerFactoryBean emf) {

        return new JpaTransactionManager(emf.getObject());
    }
}
