package io.opaa.instance;

/**
 * The lock periods of the {@code @SchedulerLock} on every {@code @Scheduled} method (ADR-0053,
 * Entscheidung 7), as constants so that the reason for a value stands in one place.
 *
 * <p>{@code lockAtLeastFor} keeps a due time from running twice: every instance fires a cron at the
 * same wall-clock second, give or take its clock skew and a scheduler thread still busy with an
 * earlier run, and a run that ends before the next instance fires must still hold the lock then. It
 * stays below the interval of the run, so that the next due time is free again. A fixed-delay run
 * has a timer of its own on each instance, so its lock covers the interval minus a margin.
 *
 * <p>{@code lockAtMostFor} is only the net for an instance that died while holding the lock: well
 * above the longest expected run, because a run outliving it may start a second time. What must not
 * happen twice even then stands behind a marker committed in the database.
 */
public final class ScheduledRunLocks {

  /**
   * For a run whose cron the operator may set, or which fires every minute: half the shortest cron
   * interval, still far above the clock skew of hosts synchronised by NTP.
   */
  public static final String AT_LEAST_FOR_MINUTE = "PT30S";

  /** For an hourly cron: a scheduler thread late by minutes still finds the hour's lock held. */
  public static final String AT_LEAST_FOR_HOURLY = "PT5M";

  /** For a cron every ten minutes: half the interval, so a late scheduler thread finds it held. */
  public static final String AT_LEAST_FOR_TEN_MINUTES = "PT5M";

  /**
   * For a daily or monthly cron: covers a scheduler thread held up by the other night runs, whose
   * mail steps alone may take their budget of minutes each.
   */
  public static final String AT_LEAST_FOR_DAILY = "PT30M";

  /** For a short run - a few statements or one tick - that a dead instance may block at most. */
  public static final String AT_MOST_FOR_SHORT = "PT10M";

  /** For a night run that sends, deletes or reconciles in batches. */
  public static final String AT_MOST_FOR_NIGHTLY = "PT2H";

  /** The default of {@code @EnableSchedulerLock}; every method names its own value anyway. */
  static final String DEFAULT_AT_MOST_FOR = AT_MOST_FOR_SHORT;

  private ScheduledRunLocks() {}
}
