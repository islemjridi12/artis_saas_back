package com.artis.saas_platform.provisioning.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

@Service
@RequiredArgsConstructor
public class MigrationService {

    private final DataSource dataSource;

    public void migrateDemoToProd(String sourceSchema, String targetSchema) {
        try (Connection conn = dataSource.getConnection()) {

            // Lister toutes les tables du schema demo
            String listTables = """
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema = ?
                AND table_type = 'BASE TABLE'
            """;

            PreparedStatement ps = conn.prepareStatement(listTables);
            ps.setString(1, sourceSchema);
            ResultSet rs = ps.executeQuery();

            while (rs.next()) {
                String table = rs.getString("table_name");

                // Copier les données table par table
                String copy = String.format(
                        "INSERT INTO %s.%s SELECT * FROM %s.%s ON CONFLICT DO NOTHING",
                        targetSchema, table,
                        sourceSchema, table
                );

                conn.createStatement().execute(copy);
            }

        } catch (Exception e) {
            throw new RuntimeException("Migration failed: " + e.getMessage(), e);
        }
    }
}
