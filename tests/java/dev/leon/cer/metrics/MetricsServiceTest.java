package dev.leon.cer.metrics;

import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class MetricsServiceTest {
    @Test
    void regionCollectorPreservesTheOfficialPluginVersionField() {
        var data = new org.bstats.json.JsonObjectBuilder();
        RegionMetrics.serviceData(data, "1.0.10");
        assertEquals("{\"pluginVersion\":\"1.0.10\"}", data.build().toString());
    }
    @Test
    void builtInServicesKeepBothTheForkAndOriginalProjects() {
        assertEquals(Set.of(34772, 34637), MetricsService.builtInServiceIds());
    }
    @Test
    void acceptsTwoIndependentServiceIds() {
        assertEquals(Set.of(12345, 67890), MetricsService.serviceIds(12345, 67890));
    }
    @Test
    void sameIdIsReportedOnlyOnce() {
        assertEquals(Set.of(12345), MetricsService.serviceIds(12345, 12345));
    }
    @Test
    void zeroAndNegativeIdsDisableIndividualServices() {
        assertTrue(MetricsService.serviceIds(0, -1).isEmpty());
        assertEquals(Set.of(12345), MetricsService.serviceIds(0, 12345));
        assertEquals(Set.of(12345), MetricsService.serviceIds(12345, -1));
    }
}
