package io.opaa.observability;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.spi.LoggingEvent;
import java.util.List;
import org.junit.jupiter.api.Test;

class SingleLineMessagesTest {

  /** A forged line in a log argument stays on the line of its own entry, whatever the pattern. */
  @Test
  void aMessageWithLineBreaksAndBidiCharactersStaysOnOneLine() {
    SingleLineMessages.install();
    LoggerContext context = new LoggerContext();
    for (String pattern : List.of("%5p %logger : %m%n", "%msg%n", "%message%n")) {
      PatternLayout layout = new PatternLayout();
      layout.setContext(context);
      layout.setPattern(pattern);
      layout.start();
      LoggingEvent event =
          new LoggingEvent(
              getClass().getName(),
              context.getLogger("io.opaa.Test"),
              Level.INFO,
              "Processing: {}",
              null,
              new Object[] {"a.pdf\r\n2026-10-29 INFO forged\u2028b\u202Ec"});

      String line = layout.doLayout(event);

      assertThat(line).as(pattern).endsWith("Processing: a.pdf??2026-10-29 INFO forged?b?c\n");
      assertThat(line.indexOf('\n')).as(pattern).isEqualTo(line.length() - 1);
    }
  }
}
