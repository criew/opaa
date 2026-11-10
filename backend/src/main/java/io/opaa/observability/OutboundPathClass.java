package io.opaa.observability;

import java.util.Locale;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * Whether a person waits for an outbound call ({@link #INTERACTIVE}) or a run, a schedule or a
 * probe made it ({@link #BACKGROUND}). A {@code 429} on a background path is ordinary under
 * adaptive throttling, on an interactive one a person sees it.
 */
public enum OutboundPathClass {
  INTERACTIVE,
  BACKGROUND;

  /** The value of the {@code path_class} tag. */
  public String tagValue() {
    return name().toLowerCase(Locale.ROOT);
  }

  /**
   * {@link #INTERACTIVE} on a thread that serves an HTTP request right now, otherwise {@link
   * #BACKGROUND}: indexing runs, schedules and probes run on threads of their own.
   */
  public static OutboundPathClass ofCurrentThread() {
    return RequestContextHolder.getRequestAttributes() != null ? INTERACTIVE : BACKGROUND;
  }
}
