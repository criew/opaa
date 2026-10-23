package io.opaa.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import io.opaa.architecture.MainClasses;
import io.opaa.compliance.SelfDisclosureExporter;
import io.opaa.selfdisclosure.SelfDisclosureService;
import io.opaa.test.JavaSources;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The readers of {@code audit_log} beside the funnel of {@link AuditFunnelStructureTest}, which
 * cannot see raw SQL: only {@link AuditLogSelfDisclosureExporter} names the table in a query, no
 * production class refers to that exporter, and only {@link SelfDisclosureService} - which passes
 * the signed-in person, never a parameter - calls an exporter. A second reader or caller would be
 * the person filter the specification rules out for third parties.
 */
class AuditLogReaderStructureTest {

  private static final JavaClasses MAIN = MainClasses.get();

  /** A query naming the table itself, not one of its monthly partitions. */
  private static final Pattern QUERY_ON_AUDIT_LOG =
      Pattern.compile("\\b(FROM|JOIN|INTO|UPDATE)\\s+(ONLY\\s+)?audit_log\\b");

  @Test
  void onlyTheSelfDisclosureExporterQueriesTheAuditLogInSql() throws IOException {
    Set<String> readers = new TreeSet<>();
    try (Stream<Path> files = Files.walk(JavaSources.MAIN_SOURCES)) {
      for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
        if (QUERY_ON_AUDIT_LOG.matcher(Files.readString(file, StandardCharsets.UTF_8)).find()) {
          readers.add(file.getFileName().toString());
        }
      }
    }

    assertThat(readers).containsExactly("AuditLogSelfDisclosureExporter.java");
  }

  @Test
  void noProductionClassRefersToTheAuditLogExporter() {
    String exporter = AuditLogSelfDisclosureExporter.class.getName();

    assertThat(
            MAIN.get(AuditLogSelfDisclosureExporter.class).getDirectDependenciesToSelf().stream()
                .map(dependency -> dependency.getOriginClass().getName())
                .filter(origin -> !origin.equals(exporter) && !origin.startsWith(exporter + "$"))
                .collect(Collectors.toSet()))
        .isEmpty();
  }

  @Test
  void onlyTheSelfDisclosureServiceCallsAnExporter() {
    Set<String> callers =
        MAIN.stream()
            .filter(
                type ->
                    type.getMethodCallsFromSelf().stream()
                        .anyMatch(
                            call ->
                                call.getName().equals("export")
                                    && call.getTargetOwner()
                                        .isAssignableTo(SelfDisclosureExporter.class)))
            .map(JavaClass::getName)
            .collect(Collectors.toSet());

    assertThat(callers).containsExactly(SelfDisclosureService.class.getName());
  }
}
