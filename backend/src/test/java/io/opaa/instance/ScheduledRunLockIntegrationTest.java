package io.opaa.instance;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import io.opaa.architecture.MainClasses;
import io.opaa.externalaccess.ExternalAccessAlertRetention;
import io.opaa.test.OpaaIntegrationTest;
import java.lang.reflect.Method;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Two instances against one database (ADR-0053, Entscheidung 7): for every locked
 * {@code @Scheduled} method of the backend, two lock providers with the configuration of {@link
 * ScheduledRunLockConfiguration} - one per instance - run its lock as the method declares it. A due
 * time runs on one instance only, also when the run is over before the second instance fires, and
 * the next due time is free again. The lock rows of this test carry a prefix of their own, so a
 * tick of the running context never meets them.
 */
@OpaaIntegrationTest
class ScheduledRunLockIntegrationTest {

  private static final String PREFIX = "two-instances:";

  @Autowired private DataSource dataSource;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private ApplicationContext context;
  @Autowired private ExternalAccessAlertRetention alertRetention;

  @BeforeEach
  @AfterEach
  void removeOwnLockRows() {
    jdbcTemplate.update("DELETE FROM shedlock WHERE name LIKE ?", PREFIX + "%");
  }

  @Test
  void everyScheduledRunExecutesOncePerDueTimeAcrossTwoInstances() {
    LockingTaskExecutor first = instance("instance-a");
    LockingTaskExecutor second = instance("instance-b");
    List<Method> locked = lockedScheduledMethods();
    assertThat(locked).hasSizeGreaterThan(15);

    for (Method method : locked) {
      LockConfiguration lock = configurationOf(method);
      AtomicInteger runs = new AtomicInteger();

      // A short run: over before the second instance fires for the same due time.
      first.executeWithLock((Runnable) runs::incrementAndGet, lock);
      second.executeWithLock((Runnable) runs::incrementAndGet, lock);
      first.executeWithLock((Runnable) runs::incrementAndGet, lock);
      assertThat(runs).as("%s within one due time", lock.getName()).hasValue(1);

      // The next due time: lockAtLeastFor has passed by the database clock.
      jdbcTemplate.update(
          "UPDATE shedlock SET lock_until = timezone('utc', now()) - interval '1 second'"
              + " WHERE name = ?",
          lock.getName());
      second.executeWithLock((Runnable) runs::incrementAndGet, lock);
      assertThat(runs).as("%s at its next due time", lock.getName()).hasValue(2);
    }
  }

  @Test
  void aRunInFlightOnOneInstanceIsSkippedOnTheOther() throws InterruptedException {
    LockingTaskExecutor first = instance("instance-a");
    LockingTaskExecutor second = instance("instance-b");
    LockConfiguration lock = configurationOf(lockedScheduledMethods().getFirst());
    CountDownLatch running = new CountDownLatch(1);
    CountDownLatch mayFinish = new CountDownLatch(1);
    AtomicInteger runs = new AtomicInteger();
    Thread inFlight =
        Thread.ofVirtual()
            .start(
                () ->
                    first.executeWithLock(
                        (Runnable)
                            () -> {
                              runs.incrementAndGet();
                              running.countDown();
                              await(mayFinish);
                            },
                        lock));
    assertThat(running.await(10, TimeUnit.SECONDS)).isTrue();

    second.executeWithLock((Runnable) runs::incrementAndGet, lock);

    mayFinish.countDown();
    inFlight.join(Duration.ofSeconds(10));
    assertThat(runs).hasValue(1);
  }

  /** The lock is on the beans of this context, not only on the annotation. */
  @Test
  void everyBeanWithALockedRunIsProxiedAndADirectCallTakesTheLock() {
    List<Class<?>> lockingTypes =
        lockedScheduledMethods().stream()
            .<Class<?>>map(Method::getDeclaringClass)
            .distinct()
            .toList();
    int beans = 0;
    for (Class<?> type : lockingTypes) {
      for (Map.Entry<String, ?> bean : context.getBeansOfType(type).entrySet()) {
        assertThat(AopUtils.isAopProxy(bean.getValue())).as("bean %s", bean.getKey()).isTrue();
        beans++;
      }
    }
    assertThat(beans).as("beans with a locked run in this context").isGreaterThan(10);

    String name = "external-access-alert-retention";
    release(name);
    try {
      Instant before = databaseUtcNow();
      alertRetention.deleteExpiredAlerts();
      Map<String, Object> taken = lockRow(name);
      assertThat(((Timestamp) taken.get("locked_at")).toInstant()).isAfterOrEqualTo(before);
      assertThat(((Timestamp) taken.get("lock_until")).toInstant())
          .isAfterOrEqualTo(
              ((Timestamp) taken.get("locked_at"))
                  .toInstant()
                  .plus(Duration.parse(ScheduledRunLocks.AT_LEAST_FOR_HOURLY)));

      alertRetention.deleteExpiredAlerts();

      assertThat(lockRow(name)).as("the second call found the lock held").isEqualTo(taken);
    } finally {
      release(name);
    }
  }

  private LockingTaskExecutor instance(String lockedBy) {
    return new DefaultLockingTaskExecutor(
        new JdbcTemplateLockProvider(
            JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(new JdbcTemplate(dataSource))
                .withTransactionManager(transactionManager)
                .withTableName(ScheduledRunLockConfiguration.TABLE)
                .withLockedByValue(lockedBy)
                .usingDbTime()
                .build()));
  }

  private static List<Method> lockedScheduledMethods() {
    List<Class<?>> classes = MainClasses.get().stream().map(JavaClass::reflect).toList();
    List<Method> locked = new ArrayList<>();
    for (Method method : ScheduledRunLockStructureTest.scheduledMethods(classes)) {
      if (method.isAnnotationPresent(SchedulerLock.class)) {
        locked.add(method);
      }
    }
    return locked;
  }

  private static LockConfiguration configurationOf(Method method) {
    SchedulerLock lock = method.getAnnotation(SchedulerLock.class);
    return new LockConfiguration(
        Instant.now(),
        PREFIX + lock.name(),
        Duration.parse(lock.lockAtMostFor()),
        Duration.parse(lock.lockAtLeastFor()));
  }

  private Map<String, Object> lockRow(String name) {
    return jdbcTemplate.queryForMap(
        "SELECT lock_until, locked_at, locked_by FROM shedlock WHERE name = ?", name);
  }

  /** Frees the lock of the context's own run for the next due time; ShedLock keeps its row. */
  private void release(String name) {
    jdbcTemplate.update(
        "UPDATE shedlock SET lock_until = timezone('utc', now()) - interval '1 second'"
            + " WHERE name = ?",
        name);
  }

  private Instant databaseUtcNow() {
    return jdbcTemplate
        .queryForObject("SELECT timezone('utc', now())::timestamp", Timestamp.class)
        .toInstant();
  }

  private static void await(CountDownLatch latch) {
    try {
      latch.await(10, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
