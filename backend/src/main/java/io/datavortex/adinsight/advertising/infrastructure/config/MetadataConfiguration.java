package io.datavortex.adinsight.advertising.infrastructure.config;

import io.datavortex.adinsight.advertising.application.port.IMetadataCatalog;
import io.datavortex.adinsight.advertising.application.service.MetadataService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MetadataConfiguration {
    @Bean
    MetadataService metadata(IMetadataCatalog catalog) {
        return new MetadataService(catalog);
    }
}
