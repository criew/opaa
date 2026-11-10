package io.opaa.instance;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;

/**
 * The run locks the current thread acquired since {@link #clear()}: a locked {@code @Scheduled} run
 * whose lock another instance holds returns without running, and only this tells {@link
 * ScheduledJobMetrics} that it did not run here. Filled by the {@link LockProvider} that {@link
 * #recording(LockProvider)} wraps; the scheduler thread runs and acquires on the same thread.
 */
final class ScheduledRunLockAcquisitions {

  private static final ThreadLocal<Set<String>> ACQUIRED = ThreadLocal.withInitial(HashSet::new);

  private ScheduledRunLockAcquisitions() {}

  /** Forgets every lock this thread acquired. */
  static void clear() {
    ACQUIRED.remove();
  }

  /** Whether this thread acquired the lock {@code name} since the last {@link #clear()}. */
  static boolean acquired(String name) {
    return ACQUIRED.get().contains(name);
  }

  /** {@code delegate}, noting each lock it grants on the acquiring thread. */
  static LockProvider recording(LockProvider delegate) {
    return new LockProvider() {
      @Override
      public Optional<SimpleLock> lock(LockConfiguration configuration) {
        Optional<SimpleLock> lock = delegate.lock(configuration);
        lock.ifPresent(granted -> ACQUIRED.get().add(configuration.getName()));
        return lock;
      }
    };
  }
}
