package io.opaa.test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.support.AbstractTestExecutionListener;

/**
 * Names the class that left rows behind, instead of letting a later class fail on a RESTRICT
 * violation or a count it did not cause.
 *
 * <p>Each guarded table is counted when a class starts and again when it ends; the class fails if a
 * count grew. Because every class takes its own starting count, a leftover is reported exactly
 * once, at the class that caused it - the next class starts from the grown count. A class that
 * removes a foreign leftover while leaving one of its own balances out and goes unnoticed.
 *
 * <p>{@code @TestExecutionListeners} are execution machinery, not part of {@code
 * MergedContextConfiguration} - this guard costs no additional Spring context and no additional
 * container.
 */
final class LeftoverRowGuard extends AbstractTestExecutionListener {

  static final List<String> GUARDED_TABLES =
      List.of(
          "diagnostic_impersonation_grants",
          "audit_incident_scope_grants",
          "knowledge_libraries",
          "documents",
          "vector_store",
          // #1816: a provider row left behind is not inert - it decides whether the next class's
          // first provider becomes the default one, and fk_groups_provider holds its groups.
          "oidc_providers");

  private static final String STARTING_COUNTS = LeftoverRowGuard.class.getName() + ".counts";
  private static final String IDLE_GATE = LeftoverRowGuard.class.getName() + ".idleGate";
  private static final Duration EXECUTOR_IDLE_TIMEOUT = Duration.ofSeconds(60);

  /** Covers a {@code @BeforeAll} of every class whose context an earlier class already started. */
  @Override
  public void beforeTestClass(TestContext testContext) {
    if (testContext.hasApplicationContext()) {
      rememberStartingCounts(testContext);
    }
  }

  /** The first class of a context: its context exists only from here on. */
  @Override
  public void prepareTestInstance(TestContext testContext) {
    if (!testContext.hasAttribute(STARTING_COUNTS)) {
      rememberStartingCounts(testContext);
    }
  }

  @Override
  @SuppressWarnings("unchecked")
  public void afterTestClass(TestContext testContext) {
    // Never force a context that was never needed: without Docker the whole class is skipped.
    if (!testContext.hasApplicationContext() || !testContext.hasAttribute(STARTING_COUNTS)) {
      return;
    }
    Map<String, Long> before = (Map<String, Long>) testContext.getAttribute(STARTING_COUNTS);
    Map<String, Long> after = counts(testContext);
    List<String> leftovers = new ArrayList<>();
    for (String table : GUARDED_TABLES) {
      long grown = after.get(table) - before.get(table);
      if (grown > 0) {
        leftovers.add(grown + " row(s) in " + table);
      }
    }
    if (!leftovers.isEmpty()) {
      throw new IllegalStateException(
          testContext.getTestClass().getSimpleName()
              + " left "
              + String.join(", ", leftovers)
              + " behind. The suite shares one database: remove in @AfterEach what the class"
              + " created, scoped to its own ids (AGENTS.md, \"Spring-Testkontexte\").");
    }
  }

  private static void rememberStartingCounts(TestContext testContext) {
    testContext.setAttribute(STARTING_COUNTS, counts(testContext));
  }

  /**
   * Taken once every task executor of the context is idle, so rows a class's asynchronous work
   * writes late still count for that class. A table that does not exist counts as empty - the
   * pgvector signature starts without {@code vector_store} and drops and recreates it under test.
   */
  private static Map<String, Long> counts(TestContext testContext) {
    ApplicationContext context = testContext.getApplicationContext();
    awaitIdleExecutors(context);
    JdbcTemplate jdbcTemplate = context.getBean(JdbcTemplate.class);
    Map<String, Long> counts = new HashMap<>();
    for (String table : GUARDED_TABLES) {
      Boolean exists =
          jdbcTemplate.queryForObject("SELECT to_regclass(?) IS NOT NULL", Boolean.class, table);
      counts.put(
          table,
          Boolean.TRUE.equals(exists)
              ? jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Long.class)
              : 0L);
    }
    return counts;
  }

  /**
   * The gate is kept as a singleton of the context itself, so a timeout closes it for every later
   * class of that context and for no other context.
   */
  private static void awaitIdleExecutors(ApplicationContext context) {
    ConfigurableListableBeanFactory beanFactory =
        ((ConfigurableApplicationContext) context).getBeanFactory();
    ExecutorIdleGate gate;
    synchronized (beanFactory) {
      if (beanFactory.containsSingleton(IDLE_GATE)) {
        gate = (ExecutorIdleGate) beanFactory.getSingleton(IDLE_GATE);
      } else {
        gate =
            new ExecutorIdleGate(
                context.getBeansOfType(ThreadPoolTaskExecutor.class), EXECUTOR_IDLE_TIMEOUT);
        beanFactory.registerSingleton(IDLE_GATE, gate);
      }
    }
    gate.awaitIdle();
  }
}
