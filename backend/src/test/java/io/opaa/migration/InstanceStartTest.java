package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.opaa.security.PasswordEncoderConfiguration;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The application as separate processes against one database, the way a rolling update starts it:
 * the migration mode migrates and ends with exit code 0, the await mode ends only after it, an
 * instance without migration starts on the migrated database, and two instances that cold-start
 * together against an empty database both start - with the emergency account reset exactly once and
 * only a working password in their logs. Real processes rather than contexts in this JVM: each
 * brings its own heap, its own exit code and its own connections, as two pods do.
 */
class InstanceStartTest extends AbstractMigrationTest {

  private static final long START_SECONDS = 240;
  private static final String STARTED = "Started OpaaApplication";
  private static final Pattern PASSWORD_LINE = Pattern.compile("Passwort:\\s+(\\S+)");
  private static final String ADMIN_EMAIL = "systemverwaltung@instances.test.example";

  private final List<Instance> instances = Collections.synchronizedList(new ArrayList<>());

  private Path uploads;

  @Override
  protected List<String> baseFixtureMigrations() {
    return List.of();
  }

  @AfterEach
  void stopInstances() throws InterruptedException {
    for (Instance instance : instances) {
      instance.process().destroy();
      if (!instance.process().waitFor(30, TimeUnit.SECONDS)) {
        instance.process().destroyForcibly();
      }
    }
  }

  @Test
  void theMigrationModeMigratesAndEndsAndAnInstanceWithoutMigrationStartsOnTheResult()
      throws Exception {
    Instance migration = start("migration", Map.of("OPAA_MODE", "migrate"));

    assertThat(migration.process().waitFor(START_SECONDS, TimeUnit.SECONDS))
        .as("the migration ends")
        .isTrue();
    assertThat(migration.process().exitValue()).as(migration.log()).isZero();
    assertThat(migration.log()).contains("Started DatabaseMigrationMode").doesNotContain(STARTED);
    long applied = count("SELECT count(*) FROM flyway_schema_history");
    assertThat(applied).isPositive();
    assertThat(count("SELECT count(*) FROM pg_class WHERE relname = 'vector_store'")).isOne();

    Instance instance = start("instance", Map.of("SPRING_FLYWAY_ENABLED", "false"));

    assertThat(instance.started().get(START_SECONDS, TimeUnit.SECONDS)).as(instance.log()).isTrue();
    assertThat(count("SELECT count(*) FROM flyway_schema_history")).isEqualTo(applied);
  }

  @Test
  void theAwaitModeWaitsWithoutWritingAndEndsOnceTheMigrationModeHasMigrated() throws Exception {
    Instance waiting = start("await", Map.of("OPAA_MODE", "await-migration"));

    assertThat(logReaches(waiting, "Waiting for the migration step")).as(waiting.log()).isTrue();
    assertThat(waiting.process().isAlive()).as(waiting.log()).isTrue();
    assertThat(count("SELECT count(*) FROM pg_class WHERE relname = 'flyway_schema_history'"))
        .as("waiting writes nothing")
        .isZero();

    Instance migration = start("migration", Map.of("OPAA_MODE", "migrate"));

    assertThat(migration.process().waitFor(START_SECONDS, TimeUnit.SECONDS)).isTrue();
    assertThat(migration.process().exitValue()).as(migration.log()).isZero();
    assertThat(waiting.process().waitFor(START_SECONDS, TimeUnit.SECONDS))
        .as(waiting.log())
        .isTrue();
    assertThat(waiting.process().exitValue()).as(waiting.log()).isZero();
    assertThat(waiting.log()).contains("The database holds all").doesNotContain(STARTED);
  }

  // regression guard for #2944: concurrent cold starts raced in Spring AI's vector table DDL, and
  // two forced resets failed on @Version and left a dead password in a log
  @Test
  void twoInstancesColdStartingTogetherOnAnEmptyDatabaseBothStartAndResetTheAccountOnce()
      throws Exception {
    HttpServer chat = chatEndpoint();
    try {
      Map<String, String> forced = new HashMap<>(answerFileEnvironment(chat));
      forced.put("OPAA_LOCAL_ADMIN_RESET", "force");
      Instance first = start("first", forced);
      Instance second = start("second", forced);
      assertTheAccountIsResetOnce(first, second);
    } finally {
      chat.stop(0);
    }
  }

  private void assertTheAccountIsResetOnce(Instance first, Instance second) throws Exception {
    assertThat(first.started().get(START_SECONDS, TimeUnit.SECONDS)).as(first.log()).isTrue();
    assertThat(second.started().get(START_SECONDS, TimeUnit.SECONDS)).as(second.log()).isTrue();

    assertThat(count("SELECT count(*) FROM audit_log WHERE event_type = 'LOCAL_ADMIN_RESET'"))
        .isOne();
    List<String> printed = new ArrayList<>();
    for (Instance instance : List.of(first, second)) {
      Matcher password = PASSWORD_LINE.matcher(instance.log());
      while (password.find()) {
        printed.add(password.group(1));
      }
    }
    assertThat(printed).hasSize(1);
    assertThat(
            new PasswordEncoderConfiguration()
                .passwordEncoder()
                .matches(printed.getFirst(), bootstrapPasswordHash()))
        .as("the password in the log signs in")
        .isTrue();
  }

  // ADR-0060: the completion and the token of the installation happen once under the start-up lock
  @Test
  void twoInstancesColdStartingWithAnAnswerFileCompleteTheInstallationOnce() throws Exception {
    HttpServer chat = chatEndpoint();
    try {
      Map<String, String> environment = answerFileEnvironment(chat);
      Instance first = start("first", environment);
      Instance second = start("second", environment);

      assertThat(first.started().get(START_SECONDS, TimeUnit.SECONDS)).as(first.log()).isTrue();
      assertThat(second.started().get(START_SECONDS, TimeUnit.SECONDS)).as(second.log()).isTrue();

      assertThat(
              count("SELECT count(*) FROM audit_log WHERE event_type = 'INSTALLATION_COMPLETED'"))
          .isOne();
      assertThat(count("SELECT count(*) FROM local_credentials WHERE is_bootstrap")).isOne();
      assertThat(
              count(
                  "SELECT count(*) FROM installation_state WHERE status = 'COMPLETED'"
                      + " AND completion_source = 'ANSWER_FILE_STARTUP' AND token_hash IS NULL"))
          .isOne();
      assertThat(first.log() + second.log())
          .doesNotContain("Kennwort-der-Installation-2026")
          .doesNotContain("INSTALLATION TOKEN");
    } finally {
      chat.stop(0);
    }
  }

  @Test
  void twoInstancesColdStartingWithoutStartValuesWaitForTheInstallationWithOneToken()
      throws Exception {
    Instance first = start("first", Map.of());
    Instance second = start("second", Map.of());

    assertThat(first.started().get(START_SECONDS, TimeUnit.SECONDS)).as(first.log()).isTrue();
    assertThat(second.started().get(START_SECONDS, TimeUnit.SECONDS)).as(second.log()).isTrue();

    Pattern tokenLine = Pattern.compile("Token:\\s+(\\S+)");
    List<String> printed = new ArrayList<>();
    for (Instance instance : List.of(first, second)) {
      Matcher token = tokenLine.matcher(instance.log());
      while (token.find()) {
        printed.add(token.group(1));
      }
    }
    assertThat(printed).hasSize(1);
    assertThat(
            count(
                "SELECT count(*) FROM installation_state WHERE status = 'PENDING'"
                    + " AND token_source = 'GENERATED' AND token_hash IS NOT NULL"))
        .isOne();
    assertThat(count("SELECT count(*) FROM local_credentials")).isZero();
    assertThat(count("SELECT count(*) FROM llm_models")).isZero();
  }

  /** Answers every chat completion, as a model server does for the connection test. */
  private static HttpServer chatEndpoint() throws IOException {
    HttpServer server =
        HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          byte[] body =
              "{\"choices\":[{\"message\":{\"content\":\"Pong\"}}]}"
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
          }
        });
    server.start();
    return server;
  }

  /** The answer file of the installation, its password file and the loopback chat endpoint. */
  private static Map<String, String> answerFileEnvironment(HttpServer chat) throws IOException {
    Path directory = Files.createTempDirectory("opaa-installation");
    Path password =
        Files.writeString(directory.resolve("password"), "Kennwort-der-Installation-2026\n");
    Path answers =
        Files.writeString(
            directory.resolve("installation.json"),
            "{\"firstAdmin\":{\"email\":\""
                + ADMIN_EMAIL
                + "\",\"displayName\":\"IT\",\"passwordFile\":\""
                + password
                + "\"},\"signIn\":{\"mode\":\"LOCAL_ONLY\"},\"chatModel\":{\"baseUrl\":"
                + "\"http://127.0.0.1:"
                + chat.getAddress().getPort()
                + "/v1\",\"model\":\"qwen2.5:7b\"}}");
    return Map.of(
        "OPAA_INSTALLATION_ANSWER_FILE",
        answers.toString(),
        "OPAA_INDEXING_TARGET_VALIDATION_ALLOWLIST",
        "127.0.0.1");
  }

  private Instance start(String name, Map<String, String> environment) throws IOException {
    // One original store for every instance: the store path is installation-wide, so an instance
    // with another path refuses to start (instance register, ADR-0053).
    if (uploads == null) {
      uploads = Files.createTempDirectory("opaa-instances-uploads");
    }
    List<String> command =
        new ArrayList<>(
            List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx384m",
                "-XX:TieredStopAtLevel=1",
                "-cp",
                System.getProperty("java.class.path"),
                "io.opaa.OpaaApplication",
                "--spring.datasource.url=" + currentDatabaseUrl(),
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword(),
                "--spring.profiles.active=oidc",
                "--server.port=0",
                "--opaa.auth.local.jwt-secret=instance-start-test-secret-0123456789-abcdefghij",
                "--opaa.directory-sync.schedule-enabled=false",
                "--opaa.upload.storage-path=" + uploads,
                "--spring.ai.openai.base-url=http://chat-model.invalid/v1",
                "--spring.ai.openai.embedding.base-url=http://embedding-model.invalid/v1",
                "--spring.main.banner-mode=off"));
    ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
    builder
        .environment()
        .keySet()
        .removeIf(key -> key.startsWith("OPAA_") || key.startsWith("SPRING_"));
    builder.environment().putAll(environment);
    Process process = builder.start();
    StringBuffer log = new StringBuffer();
    CompletableFuture<Boolean> started = new CompletableFuture<>();
    Thread reader =
        new Thread(
            () -> {
              try (BufferedReader lines =
                  new BufferedReader(
                      new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = lines.readLine()) != null) {
                  log.append(line).append('\n');
                  if (line.contains(STARTED)) {
                    started.complete(true);
                  }
                }
              } catch (IOException ignored) {
                // the process ended; the outcome is decided below
              }
              started.complete(false);
            },
            "instance-" + name);
    reader.setDaemon(true);
    reader.start();
    Instance instance = new Instance(process, log, started);
    instances.add(instance);
    return instance;
  }

  private static boolean logReaches(Instance instance, String text) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(START_SECONDS);
    while (System.nanoTime() < deadline && instance.process().isAlive()) {
      if (instance.log().contains(text)) {
        return true;
      }
      Thread.sleep(200);
    }
    return instance.log().contains(text);
  }

  private long count(String sql) throws SQLException {
    try (Connection connection = connect();
        Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery(sql)) {
      rows.next();
      return rows.getLong(1);
    }
  }

  private String bootstrapPasswordHash() throws SQLException {
    try (Connection connection = connect();
        Statement statement = connection.createStatement();
        ResultSet rows =
            statement.executeQuery(
                "SELECT password_hash FROM local_credentials WHERE is_bootstrap")) {
      assertThat(rows.next()).isTrue();
      return rows.getString(1);
    }
  }

  private record Instance(
      Process process, StringBuffer logBuffer, CompletableFuture<Boolean> started) {
    String log() {
      return logBuffer.toString();
    }
  }
}
