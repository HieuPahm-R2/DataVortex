package io.datavortex.adinsight.analytics.infrastructure.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.datavortex.adinsight.analytics.application.port.*;
import io.datavortex.adinsight.analytics.application.service.AnalyticsService;
import io.datavortex.adinsight.analytics.infrastructure.local.PythonAnalyticsWarehouse;
import io.datavortex.adinsight.shared.domain.BusinessException;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;

@Configuration
public class AnalyticsConfiguration {
    @Bean
    AnalyticsService analyticsService(IAnalyticsScope scope, IAnalyticsWarehouse warehouse) {
        return new AnalyticsService(scope, warehouse);
    }

    @Bean
    IAnalyticsWarehouse analyticsWarehouse(ObjectMapper json, Environment env) {
        String mode = env.getProperty("analytics.mode", "cloud");
        if (mode.equals("local")) {
            if (!env.matchesProfiles("dev-warehouse"))
                throw new IllegalStateException(
                        "Local analytics requires the dev-warehouse profile");
            return new PythonAnalyticsWarehouse(
                    json,
                    Path.of(env.getRequiredProperty("analytics.local.root")),
                    Path.of(env.getRequiredProperty("analytics.local.database")),
                    env.getRequiredProperty("analytics.local.python"),
                    env.getProperty(
                            "analytics.local.timeout", Duration.class, Duration.ofSeconds(30)));
        }
        if (!mode.equals("cloud")) throw new IllegalStateException("Unsupported analytics.mode");
        return (tenant, query, accounts) -> {
            throw new BusinessException(
                    BusinessException.Code.WAREHOUSE_UNAVAILABLE,
                    "Cloud analytics adapter is not configured");
        };
    }
}
