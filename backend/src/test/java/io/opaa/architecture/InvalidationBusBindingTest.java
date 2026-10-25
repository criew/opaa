package io.opaa.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaModifier;
import io.opaa.instance.BusBoundCache;
import io.opaa.instance.InvalidationSubscriber;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.context.event.EventListener;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * The rule for caches (ADR-0053, Entscheidung 5; backend/AGENTS.md): a cache that only an event
 * empties, or whose time-based expiry would leave a gap in rights or security, is bound to the
 * invalidation bus. Two shapes are recognized: a class holding a Caffeine cache, and a class that
 * reacts to an event and keeps state in an {@link AtomicReference} or a {@code volatile} field.
 * Either implements {@link InvalidationSubscriber}, or it stands in {@link #NOT_BOUND} with the
 * reason it needs no binding.
 */
class InvalidationBusBindingTest {

  /** Classes of either shape that need no binding, each with its reason. */
  static final Map<String, String> NOT_BOUND =
      Map.ofEntries(
          Map.entry(
              "io.opaa.account.LocalTokenRevocationService",
              "caches only revoked tokens; a revocation is read from the table on every miss"),
          Map.entry(
              "io.opaa.ratelimit.RateLimitService",
              "a counter per instance, not a copy of the database (ADR-0053, Entscheidung 12)"),
          Map.entry(
              "io.opaa.externalaccess.ExternalAccessQuota",
              "a quota per instance, deliberately not stored (ADR-0053, Entscheidung 12)"),
          Map.entry(
              "io.opaa.indexing.maintenance.EmbeddingReindexService",
              "the state of this instance's own run, not a copy (ADR-0053, Entscheidung 8)"),
          Map.entry(
              "io.opaa.indexing.chunk.EmbeddingIndexReconciler",
              "reconciles after an installation on this instance; another instance takes the"
                  + " recorded index over through EmbeddingIndexStateRefresh"),
          Map.entry(
              "io.opaa.mail.MailOutboxWorker",
              "a stop flag of this instance's worker; the outbox itself is the table"),
          Map.entry(
              "io.opaa.space.SpaceService",
              "remembers only that a personal space exists; a miss costs one lookup"));

  @Test
  void everyEventDrivenCacheIsBoundToTheBusOrNamesItsReason() {
    Set<String> unbound = new TreeSet<>();
    for (JavaClass type : MainClasses.get()) {
      if (type.isAssignableTo(InvalidationSubscriber.class)
          || type.isEquivalentTo(BusBoundCache.class)
          || type.getName().contains("$")) {
        continue;
      }
      if ((holdsCache(type) || keepsEventDrivenState(type))
          && !NOT_BOUND.containsKey(type.getName())) {
        unbound.add(type.getName());
      }
    }

    assertThat(unbound)
        .as(
            "caches neither bound to the invalidation bus nor listed in NOT_BOUND: implement"
                + " InvalidationSubscriber, or name the reason they need no binding")
        .isEmpty();
  }

  @Test
  void everyExceptionStillHasTheShapeItIsExemptedFor() {
    Set<String> stale = new TreeSet<>();
    for (String name : NOT_BOUND.keySet()) {
      JavaClass type = MainClasses.get().get(name);
      if (!holdsCache(type) && !keepsEventDrivenState(type)) {
        stale.add(name);
      }
    }

    assertThat(stale).as("NOT_BOUND entries that no longer hold a cache").isEmpty();
  }

  private static boolean holdsCache(JavaClass type) {
    return instanceFields(type)
        .anyMatch(
            field ->
                field.getRawType().getPackageName().equals("com.github.benmanes.caffeine.cache")
                    || field.getRawType().isEquivalentTo(BusBoundCache.class));
  }

  private static boolean keepsEventDrivenState(JavaClass type) {
    boolean listens =
        type.getMethods().stream()
            .anyMatch(
                method ->
                    method.isAnnotatedWith(TransactionalEventListener.class)
                        || method.isAnnotatedWith(EventListener.class));
    return listens
        && instanceFields(type)
            .anyMatch(
                field ->
                    field.getModifiers().contains(JavaModifier.VOLATILE)
                        || field.getRawType().isEquivalentTo(AtomicReference.class));
  }

  private static java.util.stream.Stream<JavaField> instanceFields(JavaClass type) {
    return type.getFields().stream()
        .filter(field -> !field.getModifiers().contains(JavaModifier.STATIC));
  }
}
