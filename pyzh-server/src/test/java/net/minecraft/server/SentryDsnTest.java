package net.minecraft.server;

import gg.pufferfish.pufferfish.sentry.SentryManager;
import org.bukkit.support.environment.LeafTest;
import org.dreeam.leaf.config.LeafConfig;
import org.dreeam.leaf.config.LeafGlobalConfig;
import org.dreeam.leaf.config.modules.misc.SentryDSN;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@LeafTest
class SentryDsnTest {
    @Test
    void environmentRequiresOptInAndNeverBecomesTheConfiguredDefault() {
        final boolean previousAllowed = SentryDSN.allowEnvironmentDsn;
        final String previousDsn = SentryDSN.sentryDsn;
        final String previousPath = SentryDSN.sentryDsnConfigPath;
        final String previousLevel = SentryDSN.logLevel;
        final boolean previousOnlyThrown = SentryDSN.onlyLogThrown;
        final LeafGlobalConfig config = mock(LeafGlobalConfig.class);
        when(config.pickStringRegionBased(anyString(), anyString())).thenAnswer(call -> call.getArgument(0));
        when(config.getString(anyString(), anyString(), anyString())).thenAnswer(call -> call.getArgument(1));
        when(config.getBoolean(anyString(), anyBoolean(), anyString())).thenAnswer(call -> call.getArgument(1));
        try (final var leaf = mockStatic(LeafConfig.class, CALLS_REAL_METHODS);
             final var sentry = mockStatic(SentryManager.class)) {
            leaf.when(LeafConfig::globalConfig).thenReturn(config);
            SentryDSN.allowEnvironmentDsn = false;
            SentryDSN.sentryDsn = "previously-resolved-dsn";
            final SentryDSN module = new SentryDSN();
            module.onLoaded();
            assertEquals("", SentryDSN.sentryDsn);
            assertFalse(SentryDSN.allowEnvironmentDsn);
            sentry.verifyNoInteractions();

            final String dsnPath = module.basePath() + ".dsn";
            when(config.getString(eq(dsnPath), eq(""), anyString())).thenReturn("configured-dsn");
            module.onLoaded();
            assertEquals("configured-dsn", SentryDSN.sentryDsn);

            when(config.getBoolean(eq(module.basePath() + ".allow-environment-dsn"), anyBoolean(), anyString())).thenReturn(true);
            module.onLoaded();
            final String environmentDsn = System.getenv("SENTRY_DSN");
            assertEquals(environmentDsn == null ? "configured-dsn" : environmentDsn, SentryDSN.sentryDsn);

            when(config.getBoolean(eq(module.basePath() + ".allow-environment-dsn"), anyBoolean(), anyString())).thenAnswer(call -> call.getArgument(1));
            when(config.getString(eq(dsnPath), eq(""), anyString())).thenReturn("");
            module.onLoaded();
            assertEquals("", SentryDSN.sentryDsn);
            assertFalse(SentryDSN.allowEnvironmentDsn);
            verify(config, times(4)).getString(eq(dsnPath), eq(""), anyString());
        } finally {
            SentryDSN.allowEnvironmentDsn = previousAllowed;
            SentryDSN.sentryDsn = previousDsn;
            SentryDSN.sentryDsnConfigPath = previousPath;
            SentryDSN.logLevel = previousLevel;
            SentryDSN.onlyLogThrown = previousOnlyThrown;
        }
    }
}
