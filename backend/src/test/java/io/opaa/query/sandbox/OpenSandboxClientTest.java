package io.opaa.query.sandbox;

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
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
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
    SandboxSession.ScriptRun run =
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
    SandboxSession.ScriptRun failed =
        OpenSandboxClient.readEvents(
            stream(
                """
                {"type":"stderr","text":"boom"}
                {"type":"error","error":{"ename":"CommandExecError","evalue":"3"}}
                """),
            objectMapper);
    SandboxSession.ScriptRun unreadable =
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
        .isInstanceOf(SandboxException.class);
    String overlong = "x".repeat(OpenSandboxClient.MAX_EVENT_CHARS + 1);
    assertThatThrownBy(() -> OpenSandboxClient.readEvents(stream(overlong), objectMapper))
        .isInstanceOf(SandboxException.class);
  }

  @Test
  void outputBeyondItsLimitIsDropped() throws IOException {
    String chunk = "{\"type\":\"stdout\",\"text\":\"" + "a".repeat(40_000) + "\"}\n";
    SandboxSession.ScriptRun run =
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

    List<SandboxSession.OutputFile> files =
        OpenSandboxClient.outputFiles(
            OpenSandboxClient.entries(objectMapper.readTree(listing)),
            name -> true,
            () -> false,
            path -> {
              fetched.add(path);
              return path.endsWith("geschummelt.pdf") ? null : new byte[] {1};
            });

    assertThat(files).extracting(SandboxSession.OutputFile::name).containsExactly("bericht.pdf");
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

    List<SandboxSession.OutputFile> files =
        OpenSandboxClient.outputFiles(
            OpenSandboxClient.entries(objectMapper.readTree(listing.toString())),
            name -> true,
            () -> false,
            path -> new byte[] {1});

    assertThat(files).hasSize(OpenSandboxClient.MAX_FILES);
  }

  @Test
  void aRefusedFormatTakesNoPlaceAndNothingIsReadOnceTheTimeIsUp() {
    List<String> fetched = new CopyOnWriteArrayList<>();
    List<SandboxSession.OutputEntry> entries =
        List.of(
            new SandboxSession.OutputEntry("bild.png", 1),
            new SandboxSession.OutputEntry("a.csv", 1),
            new SandboxSession.OutputEntry("b.csv", 1));
    AtomicInteger checks = new AtomicInteger();

    List<SandboxSession.OutputFile> files =
        OpenSandboxClient.outputFiles(
            entries,
            name -> !name.endsWith(".png"),
            () -> checks.incrementAndGet() > 1,
            path -> {
              fetched.add(path);
              return new byte[] {1};
            });

    assertThat(files).extracting(SandboxSession.OutputFile::name).containsExactly("a.csv");
    assertThat(fetched).containsExactly("/workspace/out/a.csv");
  }

  @Test
  void aFailedDownloadLeavesOnlyThatFileOut() {
    List<SandboxSession.OutputEntry> entries =
        List.of(
            new SandboxSession.OutputEntry("a.csv", 1),
            new SandboxSession.OutputEntry("b.csv", 1),
            new SandboxSession.OutputEntry("c.csv", 1));

    List<SandboxSession.OutputFile> files =
        OpenSandboxClient.outputFiles(
            entries,
            name -> true,
            () -> false,
            path -> {
              if (path.endsWith("b.csv")) {
                throw new SandboxException("download answered 500");
              }
              return new byte[] {1};
            });

    assertThat(files).extracting(SandboxSession.OutputFile::name).containsExactly("a.csv", "c.csv");
  }

  @Test
  void aSandboxThatIsNotReadyInTimeIsDeleted() throws IOException {
    List<String> calls = new CopyOnWriteArrayList<>();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          String call = exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath();
          calls.add(call);
          byte[] body = new byte[0];
          int status = 503;
          if (call.equals("POST /v1/sandboxes")) {
            body = "{\"id\":\"sbx-1\"}".getBytes(StandardCharsets.UTF_8);
            status = 202;
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
              properties("http://127.0.0.1:" + server.getAddress().getPort()),
              objectMapper,
              meterRegistry);

      assertThatThrownBy(() -> client.open(Duration.ofMinutes(5), Duration.ofMillis(600)))
          .isInstanceOf(SandboxException.class)
          .hasMessageContaining("in time");
      assertThat(calls).last().isEqualTo("DELETE /v1/sandboxes/sbx-1");
      client.close();
    } finally {
      server.stop(0);
    }
  }

  @Test
  void theSandboxIsDeletedWhenItsSessionIsClosedAfterAFailedScript() throws IOException {
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
              properties("http://127.0.0.1:" + server.getAddress().getPort()),
              objectMapper,
              meterRegistry);

      try (SandboxSession session = client.open(Duration.ofMinutes(5), Duration.ofSeconds(10))) {
        assertThatThrownBy(() -> session.run("print(1)", Duration.ofSeconds(10)))
            .isInstanceOf(SandboxException.class)
            .hasMessageContaining("502");
      }
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

  @Test
  void theScriptStartsWithoutTheTokenOfItsExecutionAgent() throws IOException {
    List<String> createBodies = new CopyOnWriteArrayList<>();
    List<String> commandBodies = new CopyOnWriteArrayList<>();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          String call = exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath();
          String request =
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          String body = "";
          int status = 200;
          if (call.equals("POST /v1/sandboxes")) {
            createBodies.add(request);
            body = "{\"id\":\"sbx-3\"}";
            status = 202;
          } else if (call.endsWith("/command")) {
            commandBodies.add(request);
            body = "{\"type\":\"execution_complete\"}\n";
          } else if (call.startsWith("DELETE")) {
            status = 204;
          }
          byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(status, status == 204 ? -1 : bytes.length);
          if (status != 204) {
            exchange.getResponseBody().write(bytes);
          }
          exchange.close();
        });
    server.start();
    try {
      OpenSandboxClient client =
          new OpenSandboxClient(
              properties("http://127.0.0.1:" + server.getAddress().getPort()),
              objectMapper,
              new SimpleMeterRegistry());
      try (SandboxSession session = client.open(Duration.ofMinutes(5), Duration.ofSeconds(10))) {
        session.run("print(1)", Duration.ofSeconds(5));
      }

      // execd hands its environment to the script; with the token the script could call execd
      // on loopback and run as root. The start command therefore unsets exactly that variable.
      List<String> tokenVariables =
          List.copyOf(objectMapper.readTree(createBodies.getFirst()).path("env").propertyNames());
      assertThat(tokenVariables).hasSize(1);
      List<String> argv =
          objectMapper
              .readTree(commandBodies.getFirst())
              .path("argv")
              .valueStream()
              .map(JsonNode::asString)
              .toList();
      assertThat(argv)
          .containsExactly("env", "-u", tokenVariables.getFirst(), "python3", "/workspace/main.py");
      client.close();
    } finally {
      server.stop(0);
    }
  }

  @Test
  void aSessionKeepsItsSandboxAcrossScriptsAndCapsTheLifetime() throws IOException {
    List<String> calls = new CopyOnWriteArrayList<>();
    List<String> createBodies = new CopyOnWriteArrayList<>();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          String call = exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath();
          calls.add(call);
          String body = "";
          int status = 200;
          if (call.equals("POST /v1/sandboxes")) {
            createBodies.add(
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            body = "{\"id\":\"sbx-2\"}";
            status = 202;
          } else if (call.endsWith("/command")) {
            body = "{\"type\":\"execution_complete\"}\n";
          } else if (call.endsWith("/directories/list")) {
            body = "[{\"path\":\"/workspace/out/a.csv\",\"type\":\"file\",\"size\":3}]";
          } else if (call.startsWith("DELETE")) {
            status = 204;
          }
          byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(status, status == 204 ? -1 : bytes.length);
          if (status != 204) {
            exchange.getResponseBody().write(bytes);
          }
          exchange.close();
        });
    server.start();
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    try {
      OpenSandboxClient client =
          new OpenSandboxClient(
              properties("http://127.0.0.1:" + server.getAddress().getPort()),
              objectMapper,
              meterRegistry);
      SandboxSession session = client.open(Duration.ofHours(3), Duration.ofSeconds(10));

      assertThat(session.run("print(1)", Duration.ofSeconds(5)).exitCode()).isZero();
      assertThat(session.run("print(2)", Duration.ofSeconds(5)).exitCode()).isZero();
      assertThat(session.outputEntries())
          .containsExactly(new SandboxSession.OutputEntry("a.csv", 3));
      session.close();
      session.close();

      assertThat(calls).filteredOn(call -> call.equals("POST /v1/sandboxes")).hasSize(1);
      assertThat(calls).filteredOn(call -> call.endsWith("/command")).hasSize(2);
      assertThat(calls).filteredOn(call -> call.startsWith("DELETE")).hasSize(1);
      // The lifetime is capped by opaa.sandbox.max-lifetime (15 minutes).
      assertThat(objectMapper.readTree(createBodies.getFirst()).path("timeout").asLong())
          .isEqualTo(900);
      assertThatThrownBy(() -> session.run("print(3)", Duration.ofSeconds(5)))
          .isInstanceOf(IllegalStateException.class);
      client.close();
    } finally {
      server.stop(0);
    }
  }

  @Test
  void anInstalledScriptRunsIsolatedWithoutTheTokenAndOnlyFromTheImagesDirectory() {
    assertThat(
            OpenSandboxClient.installedCommand(
                "/opt/opaa/fill_template.py", List.of("/workspace/fill_template.json")))
        .containsExactly(
            "env",
            "-u",
            OpenSandboxClient.EXECD_TOKEN_VARIABLE,
            "python3",
            "-I",
            "/opt/opaa/fill_template.py",
            "/workspace/fill_template.json");
    for (String path :
        List.of("/workspace/main.py", "/opt/opaa/../../workspace/main.py", "/opt/opaa/x.sh")) {
      assertThatThrownBy(() -> OpenSandboxClient.installedCommand(path, List.of()))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void anUploadedFileKeepsItsBytesAndStaysDirectlyInTheWorkingDirectory() throws IOException {
    List<byte[]> uploads = new CopyOnWriteArrayList<>();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          String call = exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath();
          byte[] request = exchange.getRequestBody().readAllBytes();
          String body = "";
          int status = 200;
          if (call.equals("POST /v1/sandboxes")) {
            body = "{\"id\":\"sbx-4\"}";
            status = 202;
          } else if (call.endsWith("/files/upload")) {
            uploads.add(request);
          } else if (call.startsWith("DELETE")) {
            status = 204;
          }
          byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(status, status == 204 ? -1 : bytes.length);
          if (status != 204) {
            exchange.getResponseBody().write(bytes);
          }
          exchange.close();
        });
    server.start();
    try {
      OpenSandboxClient client =
          new OpenSandboxClient(
              properties("http://127.0.0.1:" + server.getAddress().getPort()),
              objectMapper,
              new SimpleMeterRegistry());
      byte[] docx = {'P', 'K', 3, 4, (byte) 0xff, 0, (byte) 0x80};
      try (SandboxSession session = client.open(Duration.ofMinutes(5), Duration.ofSeconds(10))) {
        session.upload("/workspace/fill_template.docx", docx);
        for (String path : List.of("/workspace/out/x.docx", "/etc/hosts", "/workspace/../x")) {
          assertThatThrownBy(() -> session.upload(path, docx))
              .isInstanceOf(IllegalArgumentException.class);
        }
      }

      assertThat(uploads).hasSize(1);
      String multipart = new String(uploads.getFirst(), StandardCharsets.ISO_8859_1);
      assertThat(multipart)
          .contains("\"path\":\"/workspace/fill_template.docx\"")
          .contains(new String(docx, StandardCharsets.ISO_8859_1));
      client.close();
    } finally {
      server.stop(0);
    }
  }

  @Test
  void listedEntriesAreOnlyPlainFilesDirectlyInTheOutputDirectory() {
    List<SandboxSession.OutputEntry> entries =
        OpenSandboxClient.entries(
            objectMapper.readTree(
                """
                [
                  {"path":"/workspace/out/bericht.pdf","type":"file","size":10},
                  {"path":"/workspace/out/sub","type":"directory","size":0},
                  {"path":"/workspace/out/../main.py","type":"file","size":10},
                  {"path":"/workspace/out/x.csv","type":"file","size":-4}
                ]
                """));

    assertThat(entries)
        .containsExactly(
            new SandboxSession.OutputEntry("bericht.pdf", 10),
            new SandboxSession.OutputEntry("x.csv", 0));
  }

  @Test
  void switchedOnWithoutAddressOrKeyTheStartFails() {
    assertThatThrownBy(() -> properties(" "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("base-url");
    assertThatThrownBy(
            () ->
                new SandboxProperties(
                    true,
                    "http://sandbox:8090",
                    "",
                    "img",
                    Duration.ofSeconds(10),
                    Duration.ofMinutes(15),
                    "1",
                    "1Gi"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("api-key");
  }

  private static SandboxProperties properties(String baseUrl) {
    return new SandboxProperties(
        true,
        baseUrl,
        "key",
        "opaa/sandbox-python:1",
        Duration.ofSeconds(10),
        Duration.ofMinutes(15),
        "1",
        "1Gi");
  }
}
