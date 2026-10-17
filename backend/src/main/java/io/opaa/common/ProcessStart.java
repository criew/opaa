package io.opaa.common;

import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.util.Objects;

/**
 * The moment this backend process started. Everything the process writes is stamped after it, so a
 * row stamped before it was left behind by an earlier process - the cutoff of the startup
 * recoveries, which run while the web server and the schedulers are already working (ADR-0021).
 * This assumes one wall clock that does not jump back across a restart, i.e. NTP-synchronised
 * nodes.
 *
 * @param instant the start of the JVM, as {@link java.lang.management.RuntimeMXBean#getStartTime}
 */
public record ProcessStart(Instant instant) {

  public ProcessStart {
    Objects.requireNonNull(instant, "instant");
  }

  /** The start of the running JVM. */
  public static ProcessStart ofThisJvm() {
    return new ProcessStart(
        Instant.ofEpochMilli(ManagementFactory.getRuntimeMXBean().getStartTime()));
  }
}
