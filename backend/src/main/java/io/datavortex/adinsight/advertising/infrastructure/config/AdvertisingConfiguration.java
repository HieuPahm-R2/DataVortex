package io.datavortex.adinsight.advertising.infrastructure.config;

import io.datavortex.adinsight.advertising.application.port.AccountCatalog;
import io.datavortex.adinsight.advertising.application.service.AccountQueryService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AdvertisingConfiguration {
    @Bean
    AccountQueryService accountQueries(AccountCatalog catalog) {
        return new AccountQueryService(catalog);
    }
}
