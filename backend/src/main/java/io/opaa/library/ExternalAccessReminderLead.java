package io.opaa.library;

/**
 * How many days before a release for external AI tools expires the responsible person is reminded,
 * as the system administration set it in the settings of the external AI tools. Read on every run,
 * so a change holds on every instance from the next run on (ADR-0053); {@code 0} switches the
 * reminder off.
 */
@FunctionalInterface
public interface ExternalAccessReminderLead {

  /** The lead in days, from {@code 0} to one day less than the longest Befristung. */
  int days();
}
