package io.opaa.indexing.source.smb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import io.opaa.indexing.filesync.FileSyncHarness;
import io.opaa.indexing.job.IndexingRunEvent;
import io.opaa.indexing.source.SourceBrowser;
import io.opaa.indexing.source.SourceSettings;
import io.opaa.indexing.source.SourceSyncStateRepository;
import io.opaa.knowledge.Document;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.security.TargetAddressValidator;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The service account's password - the right one and a wrong one - appears in no protocol entry,
 * run failure, connection-test or browse message and no log line (DEBUG included, smbj's own
 * loggers too) of a sync, an unreadable file, a refused sign-in, a missing share and an original.
 */
@Testcontainers(disabledWithoutDocker = true)
class SmbLogLeakTest {

  private static final String WRONG_PASSWORD = "Falsches-Passwort-4711";

  private SambaFixture samba;
  private ListAppender<ILoggingEvent> appender;
  private Logger root;
  private Level previousLevel;
  private final String folder = "Leck " + UUID.randomUUID();

  @BeforeEach
  void setUp() {
    samba = SambaFixture.get();
    samba.put(folder + "/a.txt", "A.");
    samba.put(folder + "/b.txt", "B.");
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
  }

  @Test
  void thePasswordLeaksNowhere() throws Exception {
    String wrong = SambaFixture.DOMAIN + "\\" + SambaFixture.USER + ":" + WRONG_PASSWORD;
    SmbSourceConnector connector =
        new SmbSourceConnector(
            SmbProperties.defaults(),
            TargetAddressValidator.disabled(),
            mock(SourceSyncStateRepository.class));
    List<String> texts = new ArrayList<>();
    FileSyncHarness harness = new FileSyncHarness();
    texts.addAll(texts(harness.fullSync(store(samba.credentials()))));

    samba.denyReading(folder);
    samba.put(folder + "/c.txt", "C.");
    texts.addAll(texts(harness.fullSync(store(samba.credentials()))));
    texts.addAll(texts(harness.fullSync(store(wrong))));

    texts.add(connector.testConnection(settings(samba.credentials()), null).message());
    texts.add(connector.testConnection(settings(wrong), null).message());
    texts.add(
        connector
            .testConnection(
                new SourceSettings(
                    null, samba.url("fehlt"), null, samba.credentials(), false, null),
                null)
            .message());
    try {
      connector.browse(new SourceBrowser.Query(settings(wrong), null));
    } catch (RuntimeException e) {
      texts.add(e.getMessage());
    }
    Document document = mock(Document.class);
    when(document.getFilePath())
        .thenReturn(samba.url(SambaFixture.SHARE) + "/" + folder + "/a.txt");
    when(document.getFileName()).thenReturn("a.txt");
    when(document.getId()).thenReturn(UUID.randomUUID());
    KnowledgeLibrary library = mock(KnowledgeLibrary.class);
    when(library.getId()).thenReturn(UUID.randomUUID());
    connector.openOriginal(document, library, settings(samba.credentials()));
    try {
      connector.openOriginal(document, library, settings(wrong));
    } catch (RuntimeException e) {
      texts.add(e.getMessage());
      texts.add(String.valueOf(e.getCause()));
    }

    // smbj's reader threads still log while their connections close; appending holds the lock
    List<ILoggingEvent> logged;
    synchronized (appender) {
      logged = new ArrayList<>(appender.list);
    }
    for (ILoggingEvent event : logged) {
      texts.add(event.getFormattedMessage());
      if (event.getThrowableProxy() != null) {
        texts.add(ThrowableProxyUtil.asString(event.getThrowableProxy()));
      }
    }
    assertThat(appender.list).as("DEBUG reached the appender").isNotEmpty();
    assertThat(String.join("\n", texts))
        .doesNotContain(SambaFixture.PASSWORD)
        .doesNotContain(WRONG_PASSWORD);
  }

  @Test
  void theCheckpointOfARoundCarriesNeitherThePasswordNorAnAddress() throws Exception {
    for (int i = 0; i < 6; i++) {
      samba.put(folder + "/ordner-" + i + "/datei.txt", "Datei " + i);
    }
    samba.denyListing(folder + "/ordner-0");
    FileSyncHarness harness = new FileSyncHarness();

    harness.fullSync(
        SmbTestStores.open(
            settings(samba.credentials()),
            2,
            new io.opaa.indexing.source.RequestBudget(
                new io.opaa.sourceaccess.SourceRequestMeter(), 40, null)));

    String checkpoint = harness.state().scanProgress().containers().get("/" + folder).checkpoint();
    assertThat(checkpoint)
        .as("the unreadable folder is named in it")
        .contains("ordner-0")
        .doesNotContain(SambaFixture.PASSWORD)
        .doesNotContain(SambaFixture.USER)
        .doesNotContain(samba.host())
        .doesNotContain("smb://")
        .doesNotContain(SambaFixture.SHARE);
  }

  private SourceSettings settings(String credentials) {
    return samba.settings(credentials, List.of("/" + folder));
  }

  private SmbFileStore store(String credentials) {
    return SmbTestStores.open(settings(credentials), 10);
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
