package io.datavortex.adinsight.ingestion.infrastructure.config;

import io.datavortex.adinsight.advertising.application.port.AccountCatalog;
import io.datavortex.adinsight.ingestion.application.port.IngestionJobRepository;
import io.datavortex.adinsight.ingestion.application.service.IngestionJobService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
public class IngestionConfiguration {
    @Bean
    IngestionJobService ingestionJobs(AccountCatalog accounts, IngestionJobRepository repository) {
        return new IngestionJobService(accounts, repository);
    }

    @Bean
    TransactionTemplate transactionTemplate(PlatformTransactionManager manager) {
        return new TransactionTemplate(manager);
    }
}
