package io.opaa.msgraph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import io.opaa.security.TargetAddressValidator;
import io.opaa.sourceaccess.RateLimitHandling;
import io.opaa.sourceaccess.RateLimitListener;
import io.opaa.sourceaccess.RateLimitPolicy;
import io.opaa.sourceaccess.SourceRequestMeter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Neither the access token nor the pre-signed download address appears in a log line of the client,
 * the source-access primitives or the address validation at TRACE, nor in the message of a failure
 * - across the paths that log or fail at all: a download, a throttle, a refused token, a redirect
 * to plain http, a blocked target, a refusal by the pre-signed host.
 */
class GraphClientLogLeakTest {

  private static final String DRIVE = "b!drive-1";
  private static final List<String> WATCHED =
      List.of("io.opaa.msgraph", "io.opaa.sourceaccess", "io.opaa.security");

  private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
  private final List<String> messages = new ArrayList<>();
  private FakeGraphServer server;

  @BeforeEach
  void attach() {
    appender.list = new CopyOnWriteArrayList<>();
    appender.start();
    for (String name : WATCHED) {
      Logger logger = (Logger) LoggerFactory.getLogger(name);
      logger.setLevel(Level.TRACE);
      logger.addAppender(appender);
    }
    server = new FakeGraphServer();
    server.site("site-1", "contoso.sharepoint.com", "/sites/team", "Team");
    server.drive("site-1", DRIVE, "Dokumente", "documentLibrary");
    server.file(
        DRIVE,
        "a",
        "a.txt",
        FakeGraphServer.rootId(DRIVE),
        "Inhalt".getBytes(StandardCharsets.UTF_8));
  }

  @AfterEach
  void detach() {
    for (String name : WATCHED) {
      Logger logger = (Logger) LoggerFactory.getLogger(name);
      logger.detachAppender(appender);
      logger.setLevel(null);
    }
    server.close();
  }

  @Test
  void noLogLineAndNoFailureCarriesTheTokenOrTheDownloadAddress() throws Exception {
    String content = "drives/" + DRIVE + "/items/a/content";
    Files.delete(client(TargetAddressValidator.disabled()).download(content, 1024));
    server.failNext("/root/delta", 429, "activityLimitReached", "1", 1);
    client(TargetAddressValidator.disabled())
        .page("drives/" + DRIVE + "/root/delta", Map.of(), null);
    server.failNext("/blob/", 403, "denied", null, 1);
    attempt(() -> client(TargetAddressValidator.disabled()).download(content, 1024));
    server.downloadHost(FakeGraphServer.DownloadHost.PLAIN_HTTP);
    attempt(() -> client(TargetAddressValidator.disabled()).download(content, 1024));
    server.downloadHost(FakeGraphServer.DownloadHost.HTTPS_BY_NAME);
    attempt(
        () ->
            client(new TargetAddressValidator(true, List.of("127.0.0.1"))).download(content, 1024));
    server.acceptOnly("other");
    attempt(() -> client(TargetAddressValidator.disabled()).get("drives/" + DRIVE, Map.of()));

    String logs =
        appender.list.stream()
            .map(
                event ->
                    event.getFormattedMessage()
                        + (event.getThrowableProxy() == null
                            ? ""
                            : ThrowableProxyUtil.asString(event.getThrowableProxy())))
            .collect(Collectors.joining("\n"));
    assertThat(appender.list).isNotEmpty();
    assertThat(messages).hasSize(4);
    for (String text : List.of(logs, String.join("\n", messages))) {
      assertThat(text)
          .doesNotContain(FakeGraphServer.TOKEN)
          .doesNotContain(server.presignedSecret())
          .doesNotContain("/blob/");
    }
  }

  private interface Call {
    void run() throws Exception;
  }

  private void attempt(Call call) {
    Throwable thrown = catchThrowable(call::run);
    assertThat(thrown).isInstanceOf(GraphException.class);
    messages.add(thrown.getMessage());
  }

  private GraphClient client(TargetAddressValidator validator) {
    return new GraphClient(
        server.origin(),
        () -> FakeGraphServer.TOKEN,
        null,
        server.httpClient(),
        validator,
        new RateLimitHandling(
            RateLimitPolicy.of(2, Duration.ofSeconds(1)), wait -> {}, RateLimitListener.NONE),
        new SourceRequestMeter(),
        Duration.ofSeconds(5),
        GraphClient.DEFAULT_DOWNLOAD_TIMEOUT,
        GraphClient.DEFAULT_MAX_JSON_BYTES);
  }
}
