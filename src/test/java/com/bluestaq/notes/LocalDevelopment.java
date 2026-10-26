package com.bluestaq.notes;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Runs the local demo with a persistent PostgreSQL process and the {@code local} data profile.
 * HTTP and database listeners default to loopback, and database files survive graceful restarts.
 * This launcher and its native database dependency remain in test scope and are not packaged in the application JAR.
 */
public class LocalDevelopment {
    /**
     * Starts the application with local demo accounts and an embedded PostgreSQL DataSource.
     *
     * @param args Spring Boot options that may override local defaults, such as the HTTP port
     */
    public static void main(String[] args) {
        new SpringApplicationBuilder(TeamNotesApplication.class, LocalDatabase.class)
                .profiles("local")
                .properties("server.address=127.0.0.1")
                .run(args);
    }

    /**
     * Supplies database beans only when explicitly included by the local launcher or restart test.
     * Spring closes the managed PostgreSQL process when the application context shuts down.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class LocalDatabase {
        /**
         * Starts a loopback-only PostgreSQL process on an available port while retaining its data files.
         *
         * @param directory database data directory, defaulting to the ignored {@code .local-db} folder
         * @return the database process whose lifecycle is managed by Spring
         * @throws IOException if native database preparation or startup cannot complete
         */
        @Bean(destroyMethod = "close")
        EmbeddedPostgres localPostgres(@Value("${notes.local-data-directory:.local-db}") String directory) throws IOException {
            return EmbeddedPostgres.builder()
                    .setDataDirectory(Path.of(directory).toFile())
                    .setCleanDataDirectory(false)
                    .setServerConfig("listen_addresses", "localhost")
                    .start();
        }

        /**
         * Makes the embedded database available to Flyway and application JDBC repositories.
         *
         * @param postgres managed local PostgreSQL process
         * @return the DataSource for the process's {@code postgres} database
         */
        @Bean
        DataSource dataSource(EmbeddedPostgres postgres) {
            return postgres.getPostgresDatabase();
        }
    }
}
