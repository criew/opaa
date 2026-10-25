package io.opaa.directory.sync;

import io.opaa.compliance.DataImpact;
import io.opaa.compliance.NoDataImpact;
import io.opaa.compliance.PerInstance;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration of the directory synchronisation.
 *
 * <p>The plausibility threshold is a setting of the system administration ({@link
 * DirectorySyncChangeThreshold}).
 *
 * @param scheduleEnabled whether {@link DirectorySyncScheduler}'s tick exists at all. On in
 *     production; the backend test suite switches it off so a run never starts behind a test's
 *     back, and exercises the tick by calling it.
 */
@PerInstance("whether this process ticks the directory runs")
@NoDataImpact("the schedule switch of the directory sync")
@ConfigurationProperties(prefix = "opaa.directory-sync")
public record DirectorySyncProperties(@DataImpact boolean scheduleEnabled) {}
