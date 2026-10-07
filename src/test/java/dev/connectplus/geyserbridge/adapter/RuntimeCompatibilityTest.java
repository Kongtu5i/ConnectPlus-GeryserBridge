package dev.connectplus.geyserbridge.adapter;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeCompatibilityTest {
    @Test void acceptsBaselineAndNewBuildsOfTheSameSeries() {
        assertTrue(RuntimeCompatibility.problems("2.11.3-SNAPSHOT", "3.4.13", 21, "ViaProxy").isEmpty());
        assertTrue(RuntimeCompatibility.problems("2.11.4", "3.4.14", 25, "ViaProxy").isEmpty());
        assertTrue(RuntimeCompatibility.problems("2.11.3", "3.4.13-SNAPSHOT", 21, "ViaProxy").isEmpty());
    }
    @Test void rejectsMissingMalformedOrAmbiguousVersions() {
        for (String version : new String[]{null, "", "unknown", "63a4e2b", "2.11", "2.11.3garbage"}) {
            assertFalse(RuntimeCompatibility.problems(version, "3.4.13", 21, "ViaProxy").isEmpty());
        }
        assertFalse(RuntimeCompatibility.problems("2.11.3", "unknown", 21, "ViaProxy").isEmpty());
    }
    @Test void rejectsOlderAndUnreviewedHostSeries() {
        for (String version : new String[]{"2.10.9", "2.11.2", "2.12.0", "3.0.0"}) {
            assertFalse(RuntimeCompatibility.problems(version, "3.4.13", 21, "ViaProxy").isEmpty());
        }
        for (String version : new String[]{"3.4.12", "3.5.0", "4.0.0"}) {
            assertFalse(RuntimeCompatibility.problems("2.11.3", version, 21, "ViaProxy").isEmpty());
        }
    }
    @Test void requiresJava21OrLater() {
        assertFalse(RuntimeCompatibility.problems("2.11.3", "3.4.13", 17, "ViaProxy").isEmpty());
        assertTrue(RuntimeCompatibility.problems("2.11.3", "3.4.13", 21, "ViaProxy").isEmpty());
    }
    @Test void rejectsOtherGeyserPlatforms() {
        assertFalse(RuntimeCompatibility.problems("2.11.3", "3.4.13", 21, "Standalone").isEmpty());
        assertFalse(RuntimeCompatibility.problems("2.11.3", "3.4.13", 21, null).isEmpty());
    }

    @Test void acceptsOfficialReleaseAndDevelopmentVersionMetadata() {
        assertTrue(RuntimeCompatibility.problems(
                "2.11.3-b1247 (git-master-63a4e2b)", "3.4.13", 21, "ViaProxy").isEmpty());
        assertTrue(RuntimeCompatibility.problems(
                "2.11.4-b1300 (git-master-abcdef0)", "3.4.14", 21, "ViaProxy").isEmpty());
        assertTrue(RuntimeCompatibility.problems(
                "2.11.3-SNAPSHOT (git-DEV-63a4e2b)", "3.4.13", 21, "ViaProxy").isEmpty());
        assertFalse(RuntimeCompatibility.problems(
                "2.11.3-b1247 (git-master-63a4e2b) garbage", "3.4.13", 21, "ViaProxy").isEmpty());
    }
}
