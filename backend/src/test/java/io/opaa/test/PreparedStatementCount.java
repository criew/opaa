package io.opaa.test;

import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;

/**
 * Counts the statements Hibernate prepares for one call, for tests that prove a query count stays
 * flat. Hibernate's statistics count every thread of the context, and a cache reload can follow an
 * invalidation late; both only add statements. The count is therefore the fewest of {@value
 * #ATTEMPTS} attempts: it differs from the call's own count only when every attempt was disturbed.
 */
public final class PreparedStatementCount {

  private static final int ATTEMPTS = 3;

  private PreparedStatementCount() {}

  /** The fewest statements {@code call} prepared, with {@code settle} run before each attempt. */
  public static long of(EntityManagerFactory entityManagerFactory, Runnable settle, Runnable call) {
    Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    boolean previouslyEnabled = statistics.isStatisticsEnabled();
    statistics.setStatisticsEnabled(true);
    try {
      long fewest = Long.MAX_VALUE;
      for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
        settle.run();
        statistics.clear();
        call.run();
        fewest = Math.min(fewest, statistics.getPrepareStatementCount());
      }
      return fewest;
    } finally {
      statistics.setStatisticsEnabled(previouslyEnabled);
    }
  }
}
