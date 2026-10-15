package io.datavortex.adinsight.ingestion.infrastructure.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.datavortex.adinsight.ingestion.application.port.FetchJobQueue;
import io.datavortex.adinsight.ingestion.application.service.LocalIngestionWorker;
import io.datavortex.adinsight.ingestion.infrastructure.pipeline.PythonIngestionPipeline;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "ingestion.local.enabled", havingValue = "true")
public class LocalWorkerConfiguration {
    @Bean
    LocalIngestionWorker localIngestionWorker(
            FetchJobQueue queue, JdbcTemplate jdbc, ObjectMapper json, Environment env) {
        Path root = Path.of(env.getRequiredProperty("ingestion.local.root"));
        var pipeline =
                new PythonIngestionPipeline(
                        jdbc,
                        json,
                        root,
                        Path.of(env.getRequiredProperty("ingestion.local.output")),
                        env.getRequiredProperty("ingestion.local.python"),
                        env.getRequiredProperty("ingestion.local.endpoint"),
                        env.getProperty(
                                "ingestion.local.request-timeout",
                                Duration.class,
                                Duration.ofSeconds(30)),
                        env.getProperty(
                                "ingestion.local.process-timeout",
                                Duration.class,
                                Duration.ofMinutes(30)));
        return new LocalIngestionWorker(
                queue,
                pipeline,
                "local-" + UUID.randomUUID(),
                env.getProperty(
                        "ingestion.local.lease-duration", Duration.class, Duration.ofSeconds(60)));
    }

    @Bean
    LocalWorkerPoller localWorkerPoller(LocalIngestionWorker worker) {
        return new LocalWorkerPoller(worker);
    }

    static final class LocalWorkerPoller {
        private final LocalIngestionWorker worker;
        private final org.slf4j.Logger log =
                org.slf4j.LoggerFactory.getLogger(LocalWorkerPoller.class);

        LocalWorkerPoller(LocalIngestionWorker worker) {
            this.worker = worker;
        }

        @Scheduled(fixedDelayString = "${ingestion.local.poll-delay:1000}")
        public void poll() {
            try {
                worker.pollOnce();
            } catch (RuntimeException error) {
                // No dependency messages, source payloads or credentials in routine logs.
                log.warn(
                        "Local ingestion interrupted; expired leases are recoverable ({})",
                        error.getClass().getSimpleName());
            }
        }
    }
}
