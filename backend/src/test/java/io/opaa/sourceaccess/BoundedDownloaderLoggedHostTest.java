package io.opaa.sourceaccess;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.opaa.security.TargetAddressValidator;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * A download for a library whose names are withheld - a private library's attachment or original -
 * keeps a refused host out of every log line, the target validator's included.
 */
class BoundedDownloaderLoggedHostTest {

  private static final LoggedName PRIVATE = LoggedName.withheld("<private library 42>");
  private static final String TARGET = "http://10.20.30.40/hr/salaries.pdf";

  private final BoundedDownloader downloader =
      new BoundedDownloader(new TargetAddressValidator(true, List.of()));
  private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
  private final List<Logger> loggers =
      List.of(
          (Logger) LoggerFactory.getLogger(TargetAddressValidator.class),
          (Logger) LoggerFactory.getLogger(BoundedDownloader.class));

  @BeforeEach
  void attach() {
    appender.start();
    loggers.forEach(
        logger -> {
          logger.setLevel(Level.DEBUG);
          logger.addAppender(appender);
        });
  }

  @AfterEach
  void detach() {
    loggers.forEach(
        logger -> {
          logger.detachAppender(appender);
          logger.setLevel(null);
        });
  }

  @Test
  void aRefusedAttachmentDownloadNamesNoHost() {
    try (HttpClient client = HttpClient.newHttpClient()) {
      assertThatThrownBy(
              () ->
                  downloader.downloadBounded(
                      client,
                      TARGET,
                      "salaries.pdf",
                      1024,
                      null,
                      RedirectFollowingFetcher.RedirectPolicy.REJECT_OFF_ORIGIN,
                      RateLimitListener.NONE,
                      PRIVATE))
          .isInstanceOf(TargetAddressValidator.TargetAddressBlockedException.class);
    }

    assertRefusalLoggedWithoutHost();
  }

  @Test
  void aRefusedOriginalNamesNoHost() {
    try (HttpClient client = HttpClient.newHttpClient()) {
      assertThatThrownBy(
              () ->
                  downloader.downloadStreaming(
                      client,
                      TARGET,
                      1024,
                      null,
                      Duration.ofSeconds(5),
                      Duration.ofSeconds(5),
                      RedirectFollowingFetcher.ANY_TARGET,
                      PRIVATE))
          .isInstanceOf(TargetAddressValidator.TargetAddressBlockedException.class);
    }

    assertRefusalLoggedWithoutHost();
  }

  private void assertRefusalLoggedWithoutHost() {
    List<String> lines = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    assertThat(lines).anyMatch(line -> line.startsWith("Rejecting fetch to <private library 42>"));
    assertThat(lines).noneMatch(line -> line.contains("10.20.30.40") || line.contains("salaries"));
  }
}
