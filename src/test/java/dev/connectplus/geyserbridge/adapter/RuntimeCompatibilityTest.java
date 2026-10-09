package dev.connectplus.geyserbridge.adapter;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeCompatibilityTest {
    @Test void acceptsBaselineAndLaterGeyserVersions() {
        assertTrue(RuntimeCompatibility.problems("2.11.3-SNAPSHOT", "3.4.13", 21, "ViaProxy").isEmpty());
        assertTrue(RuntimeCompatibility.problems("2.11.4", "3.4.14", 25, "ViaProxy").isEmpty());
        assertTrue(RuntimeCompatibility.problems("2.11.3", "3.4.13-SNAPSHOT", 21, "ViaProxy").isEmpty());
        for (String version : new String[]{"2.11.10", "2.12.0", "2.12.1-SNAPSHOT", "2.100.0", "3.0.0", "10.0.0"}) {
            assertTrue(RuntimeCompatibility.problems(version, "3.4.13", 21, "ViaProxy").isEmpty(), version);
        }
    }
    @Test void rejectsMissingMalformedOrAmbiguousVersions() {
        for (String version : new String[]{null, "", "unknown", "63a4e2b", "2.11", "2.11.3garbage",
                "2.12.0garbage", "2147483648.0.0", "3.2147483648.0", "3.0.2147483648"}) {
            assertFalse(RuntimeCompatibility.problems(version, "3.4.13", 21, "ViaProxy").isEmpty());
        }
        assertFalse(RuntimeCompatibility.problems("2.11.3", "unknown", 21, "ViaProxy").isEmpty());
    }
    @Test void rejectsGeyserVersionsBelowTheMinimum() {
        for (String version : new String[]{"1.99.99", "2.0.99", "2.9.99", "2.10.99", "2.11.0", "2.11.2", "2.11.2-SNAPSHOT"}) {
            assertFalse(RuntimeCompatibility.problems(version, "3.4.13", 21, "ViaProxy").isEmpty(), version);
        }
    }
    @Test void rejectsOlderAndUnreviewedViaProxySeries() {
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
        assertTrue(RuntimeCompatibility.problems(
                "2.12.0-b1400 (git-master-abcdef0)", "3.4.13", 21, "ViaProxy").isEmpty());
        assertTrue(RuntimeCompatibility.problems(
                "3.0.0-SNAPSHOT (git-DEV-abcdef0)", "3.4.13", 21, "ViaProxy").isEmpty());
        assertFalse(RuntimeCompatibility.problems(
                "2.11.3-b1247 (git-master-63a4e2b) garbage", "3.4.13", 21, "ViaProxy").isEmpty());
    }
}
