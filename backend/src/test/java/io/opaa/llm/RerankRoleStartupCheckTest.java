package io.opaa.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.TaskScheduler;

/**
 * The startup line of the rerank role. It is the one place the role's endpoint is written to a log
 * file, which makes it the place where a base address carrying userinfo would end up in the
 * operator's log - so it is asserted against the recorded log event, not against a return value.
 */
class RerankRoleStartupCheckTest {

  private static final String SECRET_IN_BASE_URL = "benutzer:geheim";

  @Test
  void aBaseUrlWithCredentialsIsReportedWithoutEverPrintingTheCredentials() {
    RerankClient client = mock(RerankClient.class);
    RerankProperties properties =
        new RerankProperties(
            true,
            "https://" + SECRET_IN_BASE_URL + "@reranker.example.internal/v1",
            "bge-reranker",
            "",
            Duration.ofSeconds(5));
    // Would the address ever reach the probe, the endpoint answers as unreachable - the branch
    // that writes the endpoint into the log line at ERROR level.
    when(client.probe(any())).thenReturn(new RerankClient.ProbeFailure("connection refused", false));
    RerankRoleStartupCheck check =
        new RerankRoleStartupCheck(
            new RerankModelRole(properties, client), mock(TaskScheduler.class));

    List<ILoggingEvent> events = captureWhile(check::probeAndReport);

    assertThat(events).isNotEmpty();
    assertThat(events)
        .allSatisfy(event -> assertThat(event.getFormattedMessage()).doesNotContain("geheim"));
    verify(client, never()).probe(any());
  }

  private static List<ILoggingEvent> captureWhile(Runnable action) {
    ch.qos.logback.classic.Logger logger =
        (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(RerankRoleStartupCheck.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      action.run();
      return List.copyOf(appender.list);
    } finally {
      logger.detachAppender(appender);
      appender.stop();
    }
  }
}
