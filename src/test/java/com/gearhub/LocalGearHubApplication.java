package com.gearhub;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.File;
import java.io.IOException;
import org.springframework.boot.SpringApplication;

/**
 * Sobe a API com um Postgres real embutido, sem precisar de Docker.
 * Os dados ficam na pasta .local-db, fora do Git. Para recomeçar do zero, apague a pasta. Ver README.
 */
public class LocalGearHubApplication {

    private static final int POSTGRES_PORT = 54329;
    private static final File DATA_DIRECTORY = new File(".local-db");

    public static void main(String[] args) throws IOException {
        EmbeddedPostgres postgres = EmbeddedPostgres.builder()
                .setPort(POSTGRES_PORT)
                .setDataDirectory(DATA_DIRECTORY)
                .setCleanDataDirectory(false)
                .start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                postgres.close();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }));

        System.setProperty("spring.datasource.url", postgres.getJdbcUrl("postgres", "postgres"));
        System.setProperty("spring.datasource.username", "postgres");
        System.setProperty("spring.datasource.password", "postgres");
        System.setProperty("spring.config.import", "optional:file:.env[.properties]");
        SpringApplication.run(GearhubApiApplication.class, args);
    }
}
