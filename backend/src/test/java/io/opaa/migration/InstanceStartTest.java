package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.security.PasswordEncoderConfiguration;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
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
 * the migration mode migrates and ends with exit code 0, an instance without migration starts on
 * the migrated database, and two instances that cold-start together against an empty database both
 * start - with the emergency account reset exactly once and only a working password in their logs.
 * Real processes rather than contexts in this JVM: each brings its own heap, its own exit code and
 * its own connections, as two pods do.
 */
class InstanceStartTest extends AbstractMigrationTest {

  private static final long START_SECONDS = 240;
  private static final String STARTED = "Started OpaaApplication";
  private static final Pattern PASSWORD_LINE = Pattern.compile("Passwort:\\s+(\\S+)");
  private static final String ADMIN_EMAIL = "systemverwaltung@instances.test.example";

  private final List<Instance> instances = Collections.synchronizedList(new ArrayList<>());

  @Override
  protected List<String> baseFixtureChangelogs() {
    return List.of("db/changelog/test-empty.yaml");
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
    long applied = count("SELECT count(*) FROM databasechangelog");
    assertThat(applied).isPositive();
    assertThat(count("SELECT count(*) FROM pg_class WHERE relname = 'vector_store'")).isOne();

    Instance instance = start("instance", Map.of("SPRING_LIQUIBASE_ENABLED", "false"));

    assertThat(instance.started().get(START_SECONDS, TimeUnit.SECONDS)).as(instance.log()).isTrue();
    assertThat(count("SELECT count(*) FROM databasechangelog")).isEqualTo(applied);
  }

  // regression guard for #2944: concurrent cold starts raced in Spring AI's vector table DDL, and
  // two forced resets failed on @Version and left a dead password in a log
  @Test
  void twoInstancesColdStartingTogetherOnAnEmptyDatabaseBothStartAndResetTheAccountOnce()
      throws Exception {
    Map<String, String> forced = Map.of("OPAA_LOCAL_ADMIN_RESET", "force");
    Instance first = start("first", forced);
    Instance second = start("second", forced);

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

  private Instance start(String name, Map<String, String> environment) throws IOException {
    Path uploads = Files.createTempDirectory("opaa-instance-" + name);
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
                "--opaa.auth.initial-admin-email=" + ADMIN_EMAIL,
                "--opaa.directory-sync.schedule-enabled=false",
                "--opaa.upload.storage-path=" + uploads,
                "--spring.ai.openai.chat.base-url=http://chat-model.invalid/v1",
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
