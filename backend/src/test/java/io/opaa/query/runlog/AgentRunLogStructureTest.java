package io.opaa.query.runlog;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.audit.AuditRetentionSettingsService;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * What keeps the step log and the sandbox log from becoming a profile of persons (ADR-0070,
 * Entscheidung 4): one reader, which knows no query but "this run of this organization"; no other
 * production class names either table; and the retention bound stays below the shortest period of
 * the audit log, so the logs never outlive the evidence trail.
 */
class AgentRunLogStructureTest {

  private static final Path MAIN = Path.of("src/main/java");

  private static final Pattern NAMES_A_LOG =
      Pattern.compile("agent_(step|sandbox)_log(?!_retention|\\w)", Pattern.CASE_INSENSITIVE);

  @Test
  void onlyTheReaderAndTheWriterNameTheLogTables() throws IOException {
    try (Stream<Path> files = Files.walk(MAIN)) {
      List<String> naming =
          files
              .filter(file -> file.toString().endsWith(".java"))
              .filter(file -> NAMES_A_LOG.matcher(read(file)).find())
              .map(file -> MAIN.relativize(file).toString().replace('\\', '/'))
              .toList();
      assertThat(naming)
          .containsExactlyInAnyOrder(
              "io/opaa/query/runlog/AgentRunLogReader.java",
              "io/opaa/query/runlog/AgentRunLogWriter.java");
    }
  }

  @Test
  void theReaderAsksForOneRunOfOneOrganizationAndNothingElse() {
    List<Method> queries =
        Arrays.stream(AgentRunLogReader.class.getDeclaredMethods())
            .filter(method -> !Modifier.isPrivate(method.getModifiers()))
            .filter(method -> !method.isSynthetic())
            .toList();

    assertThat(queries).extracting(Method::getName).containsExactlyInAnyOrder("steps", "sandboxes");
    assertThat(queries)
        .allSatisfy(
            method ->
                assertThat(method.getParameterTypes()).containsExactly(UUID.class, UUID.class));
  }

  @Test
  void theQueryServiceOffersOnlyTheReadOfOneRun() {
    assertThat(
            Arrays.stream(AgentRunLogQueryService.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .map(Method::getName))
        .containsExactly("findRun");
  }

  @Test
  void theLogsAreAlwaysKeptShorterThanTheAuditLog() {
    // The shortest audit retention in days, counting each month as 28 days.
    int shortestAuditDays = AuditRetentionSettingsService.MIN_RETENTION_MONTHS * 28;
    assertThat(AgentRunLogRetentionSettings.MAX_RETENTION_DAYS).isLessThan(shortestAuditDays);
    assertThat(AgentRunLogRetentionSettings.MIN_RETENTION_DAYS).isPositive();
  }

  @Test
  void theSchemaBoundsMatchTheJavaBounds() throws IOException {
    String migration =
        Files.readString(
            Path.of(
                "src/main/resources/db/migration/assistant/V2026.11.10.61__agent_run_logs.sql"));
    assertThat(migration)
        .contains(
            "step_log_retention_days >= "
                + AgentRunLogRetentionSettings.MIN_RETENTION_DAYS
                + " AND step_log_retention_days <= "
                + AgentRunLogRetentionSettings.MAX_RETENTION_DAYS)
        .contains("sandbox_log_retention_days <= step_log_retention_days");
  }

  private static String read(Path file) {
    try {
      return Files.readString(file);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }
}
