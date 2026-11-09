package io.opaa.query.spike;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** What {@link OpenSandboxClient} accepts from the sandbox server, all of it untrusted. */
class OpenSandboxClientTest {

  private final ObjectMapper objectMapper = JsonMapper.builder().build();

  private static InputStream stream(String text) {
    return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void aCompletedRunHasExitCodeZeroAndItsOutput() throws IOException {
    CodeSandbox.Run run =
        OpenSandboxClient.readEvents(
            stream(
                """
                {"type":"init","text":"abc"}

                {"type":"stdout","text":"pdf "}

                data: {"type":"stdout","text":"written\\n"}

                {"type":"execution_complete","execution_time":136}
                """),
            objectMapper);

    assertThat(run.exitCode()).isZero();
    assertThat(run.stdout()).isEqualTo("pdf written\n");
  }

  @Test
  void anErrorEventCarriesTheExitCodeAndAnUnreadableOneCountsAsOne() throws IOException {
    CodeSandbox.Run failed =
        OpenSandboxClient.readEvents(
            stream(
                """
                {"type":"stderr","text":"boom"}
                {"type":"error","error":{"ename":"CommandExecError","evalue":"3"}}
                """),
            objectMapper);
    CodeSandbox.Run unreadable =
        OpenSandboxClient.readEvents(
            stream("{\"type\":\"error\",\"error\":{\"evalue\":\"kaputt\"}}\n"), objectMapper);

    assertThat(failed.exitCode()).isEqualTo(3);
    assertThat(failed.stderr()).isEqualTo("boom");
    assertThat(unreadable.exitCode()).isEqualTo(1);
  }

  @Test
  void aStreamWithoutResultOrWithAnOverlongLineFails() {
    assertThatThrownBy(
            () -> OpenSandboxClient.readEvents(stream("{\"type\":\"ping\"}\n"), objectMapper))
        .isInstanceOf(OpenSandboxClient.SandboxException.class);
    String overlong = "x".repeat(OpenSandboxClient.MAX_EVENT_CHARS + 1);
    assertThatThrownBy(() -> OpenSandboxClient.readEvents(stream(overlong), objectMapper))
        .isInstanceOf(OpenSandboxClient.SandboxException.class);
  }

  @Test
  void outputBeyondItsLimitIsDropped() throws IOException {
    String chunk = "{\"type\":\"stdout\",\"text\":\"" + "a".repeat(40_000) + "\"}\n";
    CodeSandbox.Run run =
        OpenSandboxClient.readEvents(
            stream(chunk + chunk + "{\"type\":\"execution_complete\"}\n"), objectMapper);

    assertThat(run.stdout()).hasSize(OpenSandboxClient.MAX_STREAM_CHARS);
  }

  @Test
  void onlyFilesDirectlyInTheOutputDirectoryAreFetched() {
    List<String> fetched = new CopyOnWriteArrayList<>();
    String listing =
        """
        [
          {"path":"/workspace/out/bericht.pdf","type":"file","size":10},
          {"path":"/workspace/out/sub","type":"directory","size":0},
          {"path":"/workspace/out/link.pdf","type":"symlink","size":10},
          {"path":"/etc/passwd","type":"file","size":10},
          {"path":"/workspace/out/../main.py","type":"file","size":10},
          {"path":"/workspace/out/riesig.pdf","type":"file","size":999999999},
          {"path":"/workspace/out/geschummelt.pdf","type":"file","size":10}
        ]
        """;

    List<CodeSandbox.OutputFile> files =
        OpenSandboxClient.outputFiles(
            objectMapper.readTree(listing),
            path -> {
              fetched.add(path);
              return path.endsWith("geschummelt.pdf") ? null : new byte[] {1};
            });

    assertThat(files).extracting(CodeSandbox.OutputFile::name).containsExactly("bericht.pdf");
    assertThat(fetched)
        .containsExactly("/workspace/out/bericht.pdf", "/workspace/out/geschummelt.pdf");
  }

  @Test
  void atMostMaxFilesAreFetched() {
    StringBuilder listing = new StringBuilder("[");
    for (int i = 0; i < OpenSandboxClient.MAX_FILES + 3; i++) {
      listing
          .append(i == 0 ? "" : ",")
          .append("{\"path\":\"/workspace/out/f")
          .append(i)
          .append(".csv\",\"type\":\"file\",\"size\":1}");
    }
    listing.append(']');

    List<CodeSandbox.OutputFile> files =
        OpenSandboxClient.outputFiles(
            objectMapper.readTree(listing.toString()), path -> new byte[] {1});

    assertThat(files).hasSize(OpenSandboxClient.MAX_FILES);
  }

  @Test
  void theSandboxIsDeletedWhenTheRunFails() throws IOException {
    List<String> calls = new CopyOnWriteArrayList<>();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          String call = exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath();
          calls.add(call);
          byte[] body = new byte[0];
          int status = 200;
          if (call.equals("POST /v1/sandboxes")) {
            body = "{\"id\":\"sbx-1\"}".getBytes(StandardCharsets.UTF_8);
            status = 202;
          } else if (call.endsWith("/command")) {
            status = 502;
          } else if (call.startsWith("DELETE")) {
            status = 204;
          }
          exchange.sendResponseHeaders(status, status == 204 ? -1 : body.length);
          if (status != 204) {
            exchange.getResponseBody().write(body);
          }
          exchange.close();
        });
    server.start();
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    try {
      OpenSandboxClient client =
          new OpenSandboxClient(
              new SandboxProperties(
                  true,
                  "http://127.0.0.1:" + server.getAddress().getPort(),
                  "key",
                  "opaa/sandbox-python:1",
                  Duration.ofSeconds(10),
                  "1",
                  "1Gi"),
              objectMapper,
              meterRegistry);

      assertThatThrownBy(() -> client.run("print(1)"))
          .isInstanceOf(OpenSandboxClient.SandboxException.class)
          .hasMessageContaining("502");
      assertThat(calls).last().isEqualTo("DELETE /v1/sandboxes/sbx-1");
      assertThat(
              meterRegistry
                  .get("opaa.outbound.requests")
                  .tag("target", "sandbox")
                  .tag("outcome", "server_error")
                  .tag("path_class", "interactive")
                  .timer()
                  .count())
          .isEqualTo(1);
      client.close();
    } finally {
      server.stop(0);
    }
  }
}
