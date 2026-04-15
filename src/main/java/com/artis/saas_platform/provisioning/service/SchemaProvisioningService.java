package com.artis.saas_platform.provisioning.service;

import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

@Service
public class SchemaProvisioningService {

    private static final Logger log =
            LoggerFactory.getLogger(SchemaProvisioningService.class);

    private final DataSource dataSource;
    private final DataSource demoDataSource; // 🔥 AJOUT

    public SchemaProvisioningService(
            @Qualifier("artisDataSource") DataSource dataSource,
            @Qualifier("demoDataSource") DataSource demoDataSource) { // 🔥 AJOUT
        this.dataSource     = dataSource;
        this.demoDataSource = demoDataSource;
    }

    // ================= PROD =================

    public void createSchema(String schemaName) {

        if (!schemaName.matches("^tenant_[a-z0-9_]+$")) {
            throw new IllegalArgumentException("Invalid schema name: " + schemaName);
        }

        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {

            stmt.execute("CREATE SCHEMA IF NOT EXISTS \"" + schemaName + "\"");
            log.info("[SCHEMA] Created prod: {}", schemaName);

        } catch (SQLException e) {
            throw new RuntimeException("Failed to create schema: " + schemaName, e);
        }
    }

    public void runMigrations(String schemaName) {

        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .schemas(schemaName)
                .locations("classpath:db/tenant-migration")
                .defaultSchema(schemaName)
                .createSchemas(false)
                .load();

        flyway.migrate();
        log.info("[FLYWAY] Migrations applied on prod: {}", schemaName);
    }

    // ================= DEMO 🔥 =================

    public void createDemoSchema(String schemaName) {

        if (!schemaName.matches("^demo_[a-z0-9_]+$")) {
            throw new IllegalArgumentException("Invalid demo schema name: " + schemaName);
        }

        try (Connection conn = demoDataSource.getConnection();
             Statement stmt = conn.createStatement()) {

            stmt.execute("CREATE SCHEMA IF NOT EXISTS \"" + schemaName + "\"");
            log.info("[SCHEMA] Created demo: {}", schemaName);

        } catch (SQLException e) {
            throw new RuntimeException("Failed to create demo schema: " + schemaName, e);
        }
    }

    public void runDemoMigrations(String schemaName) {

        Flyway flyway = Flyway.configure()
                .dataSource(demoDataSource) // 🔥 base démo
                .schemas(schemaName)
                .locations("classpath:db/tenant-migration") // mêmes migrations
                .defaultSchema(schemaName)
                .createSchemas(false)
                .load();

        flyway.migrate();
        log.info("[FLYWAY] Migrations applied on demo: {}", schemaName);
    }

    public void dropDemoSchema(String schemaName) {

        if (!schemaName.matches("^demo_[a-z0-9_]+$")) {
            throw new IllegalArgumentException("Invalid demo schema: " + schemaName);
        }

        try (Connection conn = demoDataSource.getConnection();
             Statement stmt = conn.createStatement()) {

            stmt.execute("DROP SCHEMA IF EXISTS \"" + schemaName + "\" CASCADE");
            log.info("[SCHEMA] Dropped demo schema: {}", schemaName);

        } catch (SQLException e) {
            throw new RuntimeException("Failed to drop demo schema: " + schemaName, e);
        }
    }
}