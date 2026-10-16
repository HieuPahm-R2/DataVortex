package io.datavortex.adinsight.analytics.application.port;

import io.datavortex.adinsight.analytics.domain.model.*;
import java.util.List;
import java.util.UUID;

public interface IAnalyticsWarehouse {
    AnalyticsResult query(UUID tenant, AnalyticsQuery query, List<AnalyticsAccount> accounts);
}
