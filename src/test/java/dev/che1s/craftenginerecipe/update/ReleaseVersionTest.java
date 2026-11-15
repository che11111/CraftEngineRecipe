package dev.che1s.craftenginerecipe.update;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReleaseVersionTest {
    @Test
    void comparesNumericComponentsRatherThanAlphabeticalOrder() {
        assertTrue(ReleaseVersion.isNewer("1.0.16", "1.0.9"));
        assertTrue(ReleaseVersion.isNewer("1.10.0", "1.9.99"));
        assertTrue(ReleaseVersion.isNewer("2.0.0", "1.99.99"));
        assertFalse(ReleaseVersion.isNewer("1.0.9", "1.0.16"));
    }

    @Test
    void toleratesTagPrefixesAndIgnoresBuildMetadataAndTrailingZeroes() {
        assertTrue(ReleaseVersion.isNewer("v1.0.17", "V1.0.16"));
        assertFalse(ReleaseVersion.isNewer("v1.0.16", "1.0.16"));
        assertFalse(ReleaseVersion.isNewer("1.0.16+release.123", "1.0.16+local.456"));
        assertFalse(ReleaseVersion.isNewer("1.0.16.0", "1.0.16"));
    }

    @Test
    void stableReleaseReplacesSameVersionSnapshotButCannotDowngradeNewerDevelopmentBuild() {
        assertTrue(ReleaseVersion.isNewer("1.0.16", "1.0.16-SNAPSHOT"));
        assertTrue(ReleaseVersion.isNewer("1.0.16", "1.0.16-rc.1"));
        assertFalse(ReleaseVersion.isNewer("1.0.16", "1.0.17-SNAPSHOT"));
        assertFalse(ReleaseVersion.isNewer("1.0.16-rc.1", "1.0.16"));
        assertTrue(ReleaseVersion.isNewer("1.0.16-rc.10", "1.0.16-rc.2"));
    }

    @Test
    void malformedOrUnknownLocalVersionsNeverTriggerAnUpdate() {
        for (String value : new String[]{null, "", "latest", "${project.version}", " 1.0.16", "1..16", "1.0.16/other"}) {
            assertFalse(ReleaseVersion.isNewer("1.0.17", value));
            assertFalse(ReleaseVersion.isNewer(value, "1.0.16"));
        }
        assertFalse(ReleaseVersion.isNewer("9".repeat(129), "1.0.16"));
    }

    @Test
    void largeNumericComponentsDoNotOverflow() {
        assertTrue(ReleaseVersion.isNewer("1.0.999999999999999999999999999", "1.0.2147483647"));
        assertFalse(ReleaseVersion.isNewer("1.0.2147483647", "1.0.999999999999999999999999999"));
    }
}
