package io.datavortex.adinsight.ingestion.infrastructure.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.bigquery.BigQueryOptions;
import com.google.cloud.storage.StorageOptions;
import io.datavortex.adinsight.ingestion.application.port.WarehouseQueue;
import io.datavortex.adinsight.ingestion.application.service.WarehouseWorker;
import io.datavortex.adinsight.ingestion.infrastructure.cloud.*;
import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.*;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "ingestion.cloud.enabled", havingValue = "true")
public class CloudWorkerConfiguration {
    @Bean(destroyMethod = "close")
    com.google.cloud.storage.Storage warehouseStorage(Environment env) throws IOException {
        return StorageOptions.newBuilder()
                .setProjectId(required(env, "project"))
                .setCredentials(credentials())
                .build()
                .getService();
    }

    @Bean
    com.google.cloud.bigquery.BigQuery warehouseBigQuery(Environment env) throws IOException {
        return BigQueryOptions.newBuilder()
                .setProjectId(required(env, "project"))
                .setLocation(required(env, "location"))
                .setCredentials(credentials())
                .build()
                .getService();
    }

    @Bean
    WarehouseWorker warehouseWorker(
            WarehouseQueue queue,
            com.google.cloud.storage.Storage storage,
            com.google.cloud.bigquery.BigQuery bigquery,
            JdbcTemplate jdbc,
            ObjectMapper json,
            Environment env)
            throws IOException {
        String project = required(env, "project"),
                dataset = required(env, "dataset"),
                location = required(env, "location");
        var artifacts =
                new GcsSnapshotStore(
                        storage,
                        bigquery,
                        jdbc,
                        json,
                        project,
                        required(env, "bucket"),
                        dataset,
                        location,
                        Path.of(required(env, "artifacts-root")));
        var compiler =
                new DataformCompiler(
                        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
                        credentials(),
                        json,
                        required(env, "dataform-repository"),
                        required(env, "dataform-revision"),
                        project,
                        dataset);
        var gateway =
                new BigQueryWarehouseGateway(
                        bigquery,
                        artifacts,
                        compiler,
                        json,
                        project,
                        dataset,
                        location,
                        Long.parseLong(required(env, "maximum-bytes-billed")));
        return new WarehouseWorker(
                queue,
                gateway,
                "warehouse-" + UUID.randomUUID(),
                env.getProperty(
                        "ingestion.cloud.lease-duration", Duration.class, Duration.ofSeconds(60)));
    }

    @Bean
    CloudWorkerPoller cloudWorkerPoller(WarehouseWorker worker) {
        return new CloudWorkerPoller(worker);
    }

    private static GoogleCredentials credentials() throws IOException {
        return GoogleCredentials.getApplicationDefault()
                .createScoped("https://www.googleapis.com/auth/cloud-platform");
    }

    private static String required(Environment env, String key) {
        String value = env.getProperty("ingestion.cloud." + key);
        if (value == null || value.isBlank())
            throw new IllegalArgumentException(
                    "Configure ingestion.cloud." + key + " before enabling cloud worker");
        return value;
    }

    static final class CloudWorkerPoller {
        private final WarehouseWorker worker;
        private final org.slf4j.Logger log =
                org.slf4j.LoggerFactory.getLogger(CloudWorkerPoller.class);

        CloudWorkerPoller(WarehouseWorker worker) {
            this.worker = worker;
        }

        @Scheduled(fixedDelayString = "${ingestion.cloud.poll-delay:1000}")
        public void poll() {
            try {
                worker.pollOnce();
            } catch (RuntimeException error) {
                log.warn(
                        "Warehouse interrupted; reconcile occupied slot ({})",
                        error.getClass().getSimpleName());
            }
        }
    }
}
