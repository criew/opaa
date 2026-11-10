package io.opaa.observability;

import java.util.Map;
import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

/**
 * Runs a task with the MDC of the thread that submitted it - request id, indexing run, trace and
 * span id - and leaves the pool thread with the MDC it had before, so nothing of one task's context
 * reaches the next task on the same thread. Spring's executors get it as their {@link
 * TaskDecorator}; a plain JDK pool wraps each task with {@link #withCallersMdc(Runnable)}.
 */
public final class MdcTaskDecorator implements TaskDecorator {

  private static final MdcTaskDecorator INSTANCE = new MdcTaskDecorator();

  /** The task with the submitting thread's MDC, for a JDK pool without a decorator. */
  public static Runnable withCallersMdc(Runnable task) {
    return INSTANCE.decorate(task);
  }

  @Override
  public Runnable decorate(Runnable task) {
    Map<String, String> submitted = MDC.getCopyOfContextMap();
    return () -> {
      Map<String, String> before = MDC.getCopyOfContextMap();
      replaceMdc(submitted);
      try {
        task.run();
      } finally {
        replaceMdc(before);
      }
    };
  }

  private static void replaceMdc(Map<String, String> context) {
    if (context == null) {
      MDC.clear();
    } else {
      MDC.setContextMap(context);
    }
  }
}
