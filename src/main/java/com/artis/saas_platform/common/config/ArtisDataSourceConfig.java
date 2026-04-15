package com.artis.saas_platform.common.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

@Configuration
public class ArtisDataSourceConfig {

    // ================= PROD =================

    @Bean("artisDataSourceProperties")
    @ConfigurationProperties("artis.datasource")
    public DataSourceProperties artisDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean("artisDataSource")
    public DataSource artisDataSource(
            @Qualifier("artisDataSourceProperties")
            DataSourceProperties props) {
        return props.initializeDataSourceBuilder().build();
    }

    // ================= DEMO 🔥 =================

    @Bean("demoDataSourceProperties")
    @ConfigurationProperties("demo.datasource")
    public DataSourceProperties demoDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean("demoDataSource")
    public DataSource demoDataSource(
            @Qualifier("demoDataSourceProperties")
            DataSourceProperties props) {
        return props.initializeDataSourceBuilder().build();
    }
}