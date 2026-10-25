package io.opaa.audit;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * A correlation reference for every entry {@link AuditEventRecorder#recordUserAction} writes on the
 * calling thread while {@link #within} runs, unless the event names its own: one operation that
 * goes through many writers (the import of a settings document) is then one "Vorgang" of the
 * protocol. The reference is bound to the thread for the duration of the call only.
 */
public final class AuditCorrelation {

  private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

  private AuditCorrelation() {}

  /** Runs {@code work} with {@code correlationRef} bound; a nested call is refused. */
  public static <T> T within(String correlationRef, Supplier<T> work) {
    Objects.requireNonNull(correlationRef, "correlationRef");
    if (CURRENT.get() != null) {
      throw new IllegalStateException("an audit correlation is already bound to this thread");
    }
    CURRENT.set(correlationRef);
    try {
      return work.get();
    } finally {
      CURRENT.remove();
    }
  }

  /** The reference bound to the calling thread, or {@code null}. */
  static String current() {
    return CURRENT.get();
  }
}
