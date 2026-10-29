package io.opaa.observability;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.CoreConstants;
import ch.qos.logback.core.pattern.DynamicConverter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class SafeLogMessagesTest {

  private static final String FORGED = "2026-10-29T09:00:00.000Z ERROR 1 --- [main] forged : x";

  private final Map<String, Supplier<DynamicConverter>> suppliersBefore =
      new HashMap<>(PatternLayout.DEFAULT_CONVERTER_SUPPLIER_MAP);
  private final Map<String, String> classesBefore =
      new HashMap<>(PatternLayout.DEFAULT_CONVERTER_MAP);

  @AfterEach
  void restoreTheDefaultConverters() {
    PatternLayout.DEFAULT_CONVERTER_SUPPLIER_MAP.clear();
    PatternLayout.DEFAULT_CONVERTER_SUPPLIER_MAP.putAll(suppliersBefore);
    PatternLayout.DEFAULT_CONVERTER_MAP.clear();
    PatternLayout.DEFAULT_CONVERTER_MAP.putAll(classesBefore);
  }

  /**
   * Every line break continues the message indented, so a forged entry never starts a line; a
   * multi-line report keeps its lines, bidi characters show as {@code ?}, a tab stays.
   */
  @Test
  void aMultiLineMessageKeepsItsLinesButNoneStartsLikeAnEntry() {
    LoggerContext context = contextWithTheRules();
    for (String pattern : List.of("%5p %logger : %m%n", "%msg%n", "%message%n")) {
      String text =
          layout(context, pattern)
              .doLayout(
                  event(
                      context,
                      "APPLICATION FAILED TO START\r\n\nDescription:\n\t{}",
                      "a.pdf\r\n" + FORGED + "\u2028b\u202Ec",
                      null));

      assertThat(text)
          .as(pattern)
          .endsWith(
              "APPLICATION FAILED TO START\n    \n    Description:\n    \ta.pdf\n    "
                  + FORGED
                  + "\n    b?c\n");
      assertThat(text.lines().skip(1)).as(pattern).allMatch(line -> line.startsWith("    "));
    }
  }

  /** The messages of an exception, its cause and its suppressed ones are continued alike. */
  @Test
  void theExceptionMessagesInAStackTraceCannotForgeAnEntry() {
    LoggerContext context = contextWithTheRules();
    RuntimeException cause = new RuntimeException("cause\u2028" + FORGED);
    IllegalStateException thrown = new IllegalStateException("top\n" + FORGED, cause);
    thrown.addSuppressed(new IllegalArgumentException("suppressed\r" + FORGED));

    String text =
        layout(context, "%m%n%" + SafeLogMessages.EXCEPTION_WORD)
            .doLayout(event(context, "failed", null, thrown));

    assertThat(text)
        .contains("IllegalStateException: top\n    " + FORGED)
        .contains("RuntimeException: cause\n    " + FORGED)
        .contains("IllegalArgumentException: suppressed\n    " + FORGED)
        .contains("\tat io.opaa.observability.SafeLogMessagesTest.");
    assertThat(text.lines()).noneMatch(line -> line.startsWith("2026"));
  }

  @Test
  void installRegistersTheRulesAsDefaults() {
    SafeLogMessages.install();

    assertThat(PatternLayout.DEFAULT_CONVERTER_SUPPLIER_MAP.get("m").get())
        .isInstanceOf(SafeLogMessages.MessageText.class);
    assertThat(PatternLayout.DEFAULT_CONVERTER_MAP)
        .containsEntry(
            SafeLogMessages.EXCEPTION_WORD, SafeLogMessages.ExceptionText.class.getName());
  }

  /** A context carrying the rules in its own registry, leaving the global defaults alone. */
  private static LoggerContext contextWithTheRules() {
    LoggerContext context = new LoggerContext();
    context.putObject(
        CoreConstants.PATTERN_RULE_REGISTRY_FOR_SUPPLIERS, new HashMap<>(SafeLogMessages.rules()));
    return context;
  }

  private static PatternLayout layout(LoggerContext context, String pattern) {
    PatternLayout layout = new PatternLayout();
    layout.setContext(context);
    layout.setPattern(pattern);
    layout.start();
    return layout;
  }

  private static LoggingEvent event(
      LoggerContext context, String message, String argument, Throwable thrown) {
    return new LoggingEvent(
        SafeLogMessagesTest.class.getName(),
        context.getLogger("io.opaa.Test"),
        Level.ERROR,
        message,
        thrown,
        argument == null ? null : new Object[] {argument});
  }
}
