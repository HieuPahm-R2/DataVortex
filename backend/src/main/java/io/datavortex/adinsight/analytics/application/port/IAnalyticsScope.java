package io.datavortex.adinsight.analytics.application.port;

import io.datavortex.adinsight.analytics.domain.model.*;
import java.util.List;
import java.util.UUID;

public interface IAnalyticsScope {
    List<AnalyticsAccount> resolve(UUID tenant, AnalyticsQuery query);
}
