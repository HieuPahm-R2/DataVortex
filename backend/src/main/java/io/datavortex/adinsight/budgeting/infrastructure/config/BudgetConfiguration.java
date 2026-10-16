package io.datavortex.adinsight.budgeting.infrastructure.config;

import io.datavortex.adinsight.budgeting.application.port.IBudgetPlans;
import io.datavortex.adinsight.budgeting.application.service.BudgetService;
import org.springframework.context.annotation.*;

@Configuration
public class BudgetConfiguration {
    @Bean
    BudgetService budgets(IBudgetPlans plans) {
        return new BudgetService(plans);
    }
}
