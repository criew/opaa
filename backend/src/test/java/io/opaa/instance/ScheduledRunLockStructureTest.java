package io.opaa.instance;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import io.opaa.architecture.MainClasses;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;

/**
 * Every {@code @Scheduled} method of the backend takes the scheduled-run lock (ADR-0053,
 * Entscheidung 7), or stands in {@link #RUNS_ON_EVERY_INSTANCE} with the reason why it must run on
 * every instance. A locked method is public (the lock sits on the method proxy, which leaves other
 * methods alone), names a lock no other method uses, and holds it at least long enough to keep one
 * due time on one instance but not as long as the interval of its run.
 */
class ScheduledRunLockStructureTest {

  /**
   * The runs that deliberately take no lock, keyed {@code class#method}, each with its reason. A
   * run listed here claims each unit of its work under a row lock or only reads what its own
   * instance reports, so every instance may run it at once.
   */
  static final Map<String, String> RUNS_ON_EVERY_INSTANCE =
      Map.of(
          "io.opaa.mail.MailOutboxWorker#poll",
          "every instance delivers; FOR UPDATE SKIP LOCKED claims each mail (ADR-0053, E11)",
          "io.opaa.connection.token.GrantRevocations#poll",
          "every instance revokes; FOR UPDATE SKIP LOCKED claims each revocation (ADR-0053, E10)",
          "io.opaa.indexing.run.SourceEventIntake#drainDue",
          "each instance drains the batches it has a target for, one claim at a time (ADR-0053, E10)",
          "io.opaa.audit.AuditStorageMetrics#refresh",
          "each instance reads the gauges it reports itself; it only reads (ADR-0053, E12)");

  /** Above the clock skew of hosts synchronised by NTP; the database clock compares the locks. */
  static final Duration MINIMUM_AT_LEAST_FOR = Duration.ofSeconds(2);

  @Test
  void everyScheduledMethodTakesTheLockOrIsAnExplicitException() {
    List<Class<?>> classes =
        MainClasses.get().stream()
            .map(JavaClass::reflect)
            .sorted(Comparator.comparing(Class::getName))
            .toList();

    assertThat(scheduledMethods(classes)).as("the backend has scheduled runs at all").isNotEmpty();
    assertThat(violations(classes)).isEmpty();
  }

  @Test
  void aNewScheduledMethodWithoutTheLockFails() {
    assertThat(violations(List.of(UnlockedRun.class)))
        .singleElement()
        .asString()
        .contains(UnlockedRun.class.getName() + "#tick")
        .contains("@SchedulerLock");
  }

  @Test
  void aLockHeldForTheWholeIntervalFails() {
    assertThat(violations(List.of(LockAsLongAsTheInterval.class)))
        .singleElement()
        .asString()
        .contains("below the interval");
  }

  @Test
  void twoRunsSharingOneLockNameFail() {
    assertThat(violations(List.of(SharedLockName.class)))
        .singleElement()
        .asString()
        .contains("shared-name");
  }

  @Test
  void aPackagePrivateLockedMethodFails() {
    assertThat(violations(List.of(PackagePrivateRun.class)))
        .singleElement()
        .asString()
        .contains("public");
  }

  static List<Method> scheduledMethods(Collection<Class<?>> classes) {
    List<Method> methods = new ArrayList<>();
    for (Class<?> type : classes) {
      Arrays.stream(type.getDeclaredMethods())
          .filter(
              method ->
                  !AnnotatedElementUtils.findAllMergedAnnotations(method, Scheduled.class)
                      .isEmpty())
          .sorted(Comparator.comparing(Method::getName))
          .forEach(methods::add);
    }
    return methods;
  }

  static List<String> violations(Collection<Class<?>> classes) {
    List<String> violations = new ArrayList<>();
    Map<String, String> lockOwners = new HashMap<>();
    for (Method method : scheduledMethods(classes)) {
      String id = method.getDeclaringClass().getName() + "#" + method.getName();
      SchedulerLock lock = method.getAnnotation(SchedulerLock.class);
      if (lock == null) {
        if (!RUNS_ON_EVERY_INSTANCE.containsKey(id)) {
          violations.add(
              id
                  + ": a @Scheduled method needs @SchedulerLock (ScheduledRunLocks) or an entry with"
                  + " its reason in RUNS_ON_EVERY_INSTANCE");
        }
        continue;
      }
      if (RUNS_ON_EVERY_INSTANCE.containsKey(id)) {
        violations.add(id + ": locked, so it must not stand in RUNS_ON_EVERY_INSTANCE");
      }
      if (!Modifier.isPublic(method.getModifiers())) {
        violations.add(id + ": the lock sits on the proxy and needs a public method");
      }
      String previous = lockOwners.putIfAbsent(lock.name(), id);
      if (previous != null) {
        violations.add(id + ": the lock name '" + lock.name() + "' is taken by " + previous);
      }
      violations.addAll(periodViolations(id, method, lock));
    }
    return violations;
  }

  private static List<String> periodViolations(String id, Method method, SchedulerLock lock) {
    List<String> violations = new ArrayList<>();
    Duration atLeast = Duration.parse(lock.lockAtLeastFor());
    Duration atMost = Duration.parse(lock.lockAtMostFor());
    if (atLeast.compareTo(MINIMUM_AT_LEAST_FOR) < 0) {
      violations.add(id + ": lockAtLeastFor " + atLeast + " is below " + MINIMUM_AT_LEAST_FOR);
    }
    if (atMost.compareTo(atLeast) < 0) {
      violations.add(id + ": lockAtMostFor " + atMost + " is below lockAtLeastFor " + atLeast);
    }
    for (Scheduled scheduled :
        AnnotatedElementUtils.findAllMergedAnnotations(method, Scheduled.class)) {
      Duration interval = shortestInterval(scheduled);
      if (atLeast.compareTo(interval) >= 0) {
        violations.add(
            id
                + ": lockAtLeastFor "
                + atLeast
                + " must stay below the interval "
                + interval
                + ", or the next due time finds the lock still held");
      }
    }
    return violations;
  }

  /**
   * The shortest gap between two due times of the schedule; a placeholder counts by its default.
   */
  static Duration shortestInterval(Scheduled scheduled) {
    if (!scheduled.cron().isEmpty()) {
      CronExpression cron = CronExpression.parse(defaultOf(scheduled.cron()));
      ZonedDateTime previous = cron.next(ZonedDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC));
      Duration shortest = null;
      for (int i = 0; i < 400 && previous != null; i++) {
        ZonedDateTime next = cron.next(previous);
        if (next == null) {
          break;
        }
        Duration gap = Duration.between(previous, next);
        shortest = shortest == null || gap.compareTo(shortest) < 0 ? gap : shortest;
        previous = next;
      }
      return shortest;
    }
    TimeUnit unit = scheduled.timeUnit();
    if (scheduled.fixedDelay() >= 0) {
      return Duration.ofMillis(unit.toMillis(scheduled.fixedDelay()));
    }
    if (scheduled.fixedRate() >= 0) {
      return Duration.ofMillis(unit.toMillis(scheduled.fixedRate()));
    }
    String text =
        defaultOf(
            scheduled.fixedDelayString().isEmpty()
                ? scheduled.fixedRateString()
                : scheduled.fixedDelayString());
    return text.startsWith("P")
        ? Duration.parse(text)
        : Duration.ofMillis(unit.toMillis(Long.parseLong(text)));
  }

  /** {@code ${key:default}} read as its default, anything else as it stands. */
  private static String defaultOf(String value) {
    if (value.startsWith("${") && value.endsWith("}") && value.contains(":")) {
      return value.substring(value.indexOf(':') + 1, value.length() - 1);
    }
    return value;
  }

  static class UnlockedRun {
    @Scheduled(cron = "0 * * * * *")
    public void tick() {}
  }

  static class LockAsLongAsTheInterval {
    @Scheduled(fixedDelayString = "PT5M")
    @SchedulerLock(name = "whole-interval", lockAtLeastFor = "PT5M", lockAtMostFor = "PT10M")
    public void tick() {}
  }

  static class SharedLockName {
    @Scheduled(cron = "0 0 3 * * *")
    @SchedulerLock(name = "shared-name", lockAtLeastFor = "PT30M", lockAtMostFor = "PT2H")
    public void first() {}

    @Scheduled(cron = "0 0 4 * * *")
    @SchedulerLock(name = "shared-name", lockAtLeastFor = "PT30M", lockAtMostFor = "PT2H")
    public void second() {}
  }

  static class PackagePrivateRun {
    @Scheduled(cron = "0 0 3 * * *")
    @SchedulerLock(name = "package-private", lockAtLeastFor = "PT30M", lockAtMostFor = "PT2H")
    void tick() {}
  }
}
