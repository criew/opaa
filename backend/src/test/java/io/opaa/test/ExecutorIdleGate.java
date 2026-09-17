package io.opaa.test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Waits until every task executor of one application context is idle.
 *
 * <p>Once a wait has timed out, the gate stays closed: every later call fails at once instead of
 * waiting again, because a task that hung for the whole timeout will not finish for the next class
 * either. The failure names the busy executors and the stacks of their threads.
 */
final class ExecutorIdleGate {

  private static final long POLL_INTERVAL_MILLIS = 50;
  private static final int MAX_FRAMES_PER_THREAD = 25;

  private final Map<String, ThreadPoolTaskExecutor> executors;
  private final Duration timeout;
  private final AtomicBoolean timedOut = new AtomicBoolean();

  ExecutorIdleGate(Map<String, ThreadPoolTaskExecutor> executors, Duration timeout) {
    this.executors = Map.copyOf(executors);
    this.timeout = timeout;
  }

  void awaitIdle() {
    if (timedOut.get()) {
      throw new IllegalStateException(
          "Task executors of this context already failed to become idle within "
              + timeout
              + "; not waiting again. "
              + describeBusyExecutors());
    }
    long deadline = System.nanoTime() + timeout.toNanos();
    while (!allIdle()) {
      if (System.nanoTime() - deadline >= 0) {
        timedOut.set(true);
        throw new IllegalStateException(
            "Task executors did not become idle within "
                + timeout
                + ". "
                + describeBusyExecutors());
      }
      try {
        Thread.sleep(POLL_INTERVAL_MILLIS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("Interrupted while waiting for idle task executors", e);
      }
    }
  }

  private boolean allIdle() {
    return executors.values().stream().allMatch(ExecutorIdleGate::isIdle);
  }

  private static boolean isIdle(ThreadPoolTaskExecutor executor) {
    return executor.getActiveCount() == 0 && executor.getThreadPoolExecutor().getQueue().isEmpty();
  }

  private String describeBusyExecutors() {
    Map<Thread, StackTraceElement[]> stacks = Thread.getAllStackTraces();
    List<String> busy = new ArrayList<>();
    executors.entrySet().stream()
        .filter(entry -> !isIdle(entry.getValue()))
        .sorted(Map.Entry.comparingByKey())
        .forEach(
            entry -> {
              ThreadPoolTaskExecutor executor = entry.getValue();
              StringBuilder text =
                  new StringBuilder(entry.getKey())
                      .append(" (active=")
                      .append(executor.getActiveCount())
                      .append(", queued=")
                      .append(executor.getThreadPoolExecutor().getQueue().size())
                      .append(')');
              stacks.forEach(
                  (thread, frames) -> {
                    if (thread.getName().startsWith(executor.getThreadNamePrefix())) {
                      text.append("\n  thread ")
                          .append(thread.getName())
                          .append(" [")
                          .append(thread.getState())
                          .append(']');
                      for (int i = 0; i < Math.min(frames.length, MAX_FRAMES_PER_THREAD); i++) {
                        text.append("\n    at ").append(frames[i]);
                      }
                    }
                  });
              busy.add(text.toString());
            });
    return busy.isEmpty()
        ? "No executor is busy any more."
        : "Busy executors:\n" + String.join("\n", busy);
  }
}
