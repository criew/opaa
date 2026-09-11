package io.opaa.auth.local;

import java.time.Instant;

/**
 * One step of the daily local-account run {@link LocalTokenCleanupScheduler} drives after the token
 * cleanup (ADR-0033, Entscheidung 7): the lock after the inactivity period (#1537) and the expiry
 * reminders (#1538) plug in as beans of this type. Steps run in registration order with the same
 * instant; one failing step is logged and never stops the others.
 */
public interface LocalAccountMaintenanceStep {

  /** A short name for the log line. */
  String name();

  void run(Instant now);
}
