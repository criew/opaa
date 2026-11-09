package io.opaa.observability;

import java.util.Map;
import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

/**
 * Runs a task with the MDC of the thread that submitted it - request id, indexing run, trace and
 * span id - and leaves the pool thread with the MDC it had before, so nothing of one task's context
 * reaches the next task on the same thread.
 */
public final class MdcTaskDecorator implements TaskDecorator {

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
