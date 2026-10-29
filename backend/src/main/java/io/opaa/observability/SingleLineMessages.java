package io.opaa.observability;

import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.pattern.MessageConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import io.opaa.common.LogText;
import java.util.List;

/**
 * The plain-text log writes every message through {@link LogText}: a name, path or host that
 * reaches a log argument unfiltered still cannot forge a line or reorder what the reader sees. It
 * replaces the converter behind {@code %m}, {@code %msg} and {@code %message} for every pattern
 * layout built afterwards, whatever pattern the operation configures; the structured formats escape
 * on their own. The stack trace of an exception is left as it is.
 */
public final class SingleLineMessages {

  private static final List<String> MESSAGE_WORDS = List.of("m", "msg", "message");

  private SingleLineMessages() {}

  /** Installs the converter; called before Spring Boot configures the logging system. */
  public static void install() {
    for (String word : MESSAGE_WORDS) {
      PatternLayout.DEFAULT_CONVERTER_SUPPLIER_MAP.put(word, Converter::new);
      PatternLayout.DEFAULT_CONVERTER_MAP.put(word, Converter.class.getName());
    }
  }

  /** {@code %m} with {@link LogText} applied. */
  public static final class Converter extends MessageConverter {
    @Override
    public String convert(ILoggingEvent event) {
      return LogText.of(super.convert(event));
    }
  }
}
