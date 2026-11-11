package io.opaa.observability;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.spi.LoggingEvent;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.logging.logback.StructuredLogEncoder;
import org.springframework.core.env.Environment;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/**
 * The request id in both log forms: the plain-text pattern of {@code application.yml} shows it as
 * {@code [<id>] } and nothing outside a request, every structured format carries the MDC fields.
 */
class RequestIdLogFormatTest {

  private static final String ID = "4bf92f3577b34da6a3ce929d0e0e4736";

  private final LoggerContext context = new LoggerContext();

  @Test
  void thePlainTextPatternShowsTheIdAndNothingOutsideARequest() throws Exception {
    PatternLayout layout = new PatternLayout();
    layout.setContext(context);
    layout.setPattern(deliveredCorrelationPattern() + "%m");
    layout.start();

    assertThat(layout.doLayout(event(Map.of(RequestIds.MDC_KEY, ID))))
        .isEqualTo("[" + ID + "] Zeile");
    assertThat(layout.doLayout(event(Map.of()))).isEqualTo("Zeile");
  }

  @ParameterizedTest
  @ValueSource(strings = {"ecs", "logstash", "gelf"})
  void everyStructuredFormatCarriesRequestIdAndIndexingRun(String format) {
    StandardEnvironment environment = new StandardEnvironment();
    context.putObject(Environment.class.getName(), environment);
    StructuredLogEncoder encoder = new StructuredLogEncoder();
    encoder.setContext(context);
    encoder.setFormat(format);
    encoder.start();

    String line =
        new String(
            encoder.encode(
                event(Map.of(RequestIds.MDC_KEY, ID, RequestIds.INDEXING_RUN_MDC_KEY, "run-1"))),
            StandardCharsets.UTF_8);

    assertThat(line).contains(ID).contains("requestId").contains("indexingRunId").contains("run-1");
  }

  private LoggingEvent event(Map<String, String> mdc) {
    LoggingEvent event = new LoggingEvent();
    event.setLoggerContext(context);
    event.setLoggerName("io.opaa.Test");
    event.setLevel(Level.INFO);
    event.setMessage("Zeile");
    event.setTimeStamp(0);
    event.setThreadName("main");
    event.setMDCPropertyMap(mdc);
    return event;
  }

  private static String deliveredCorrelationPattern() throws Exception {
    return Objects.requireNonNull(
            new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"))
                .getFirst()
                .getProperty("logging.pattern.correlation"))
        .toString();
  }
}
