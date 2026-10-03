package io.opaa.indexing.source.nextcloud;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import io.opaa.indexing.filesync.FileSyncHarness;
import io.opaa.indexing.job.IndexingRunEvent;
import io.opaa.indexing.source.SourceConnectionTestResult;
import io.opaa.indexing.source.SourceSyncStateRepository;
import io.opaa.security.TargetAddressValidator;
import io.opaa.sourceaccess.SourceRequestPolicy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;

/**
 * The app password appears in no protocol entry, run failure, connection-test message or log line
 * (DEBUG included) of a refused sign-in, a file the instance cannot open and a foreign address -
 * neither in clear nor as the Basic header.
 */
class NextcloudLogLeakTest {

  private FakeNextcloudServer server;
  private ListAppender<ILoggingEvent> appender;
  private Logger root;
  private Level previousLevel;

  @BeforeEach
  void setUp() throws Exception {
    server = new FakeNextcloudServer("");
    server.put("Akten/a.txt", "A.").put("Akten/b.txt", "B.");
    root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    previousLevel = root.getLevel();
    root.setLevel(Level.DEBUG);
    appender = new ListAppender<>();
    appender.start();
    root.addAppender(appender);
  }

  @AfterEach
  void tearDown() {
    root.detachAppender(appender);
    root.setLevel(previousLevel);
    server.close();
  }

  @Test
  void theAppPasswordLeaksNowhere() throws Exception {
    List<String> texts = new ArrayList<>();
    FileSyncHarness harness = new FileSyncHarness();
    texts.addAll(texts(harness.fullSync(store())));

    server.unopenable("Akten/a.txt").put("Akten/a.txt", "A, neu.");
    texts.addAll(texts(harness.fullSync(store())));
    server.answerFileHrefsWith("@andere.example/x");
    server.put("Akten/b.txt", "B, neu.");
    texts.addAll(texts(harness.fullSync(store())));

    server.rejectCredentials();
    texts.addAll(texts(harness.fullSync(store())));
    SourceConnectionTestResult test =
        new NextcloudSourceConnector(
                NextcloudProperties.defaults(),
                TargetAddressValidator.disabled(),
                SourceRequestPolicy.defaults(),
                Mockito.mock(SourceSyncStateRepository.class))
            .testConnection(settings(), null);
    texts.add(test.message());

    for (ILoggingEvent event : appender.list) {
      texts.add(event.getFormattedMessage());
      if (event.getThrowableProxy() != null) {
        texts.add(ThrowableProxyUtil.asString(event.getThrowableProxy()));
      }
    }
    String basic =
        Base64.getEncoder().encodeToString(server.credentials().getBytes(StandardCharsets.UTF_8));
    assertThat(texts).isNotEmpty();
    assertThat(String.join("\n", texts))
        .doesNotContain(FakeNextcloudServer.APP_PASSWORD)
        .doesNotContain(basic);
  }

  private io.opaa.indexing.source.SourceSettings settings() {
    return NextcloudTestStores.settings(server.baseUrl(), server.credentials(), List.of("/Akten"));
  }

  private io.opaa.indexing.filesync.FileStore store() {
    return NextcloudTestStores.open(settings());
  }

  private static List<String> texts(FileSyncHarness.Run run) {
    List<String> texts = new ArrayList<>();
    for (IndexingRunEvent event : run.events()) {
      texts.add(event.getMessage());
      texts.add(String.valueOf(event.getReference()));
    }
    texts.add(String.valueOf(run.failure()));
    return texts;
  }
}
