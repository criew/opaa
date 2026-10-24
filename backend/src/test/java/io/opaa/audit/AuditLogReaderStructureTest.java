package io.opaa.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import io.opaa.architecture.MainClasses;
import io.opaa.compliance.SelfDisclosureExporter;
import io.opaa.compliance.SelfDisclosureSink;
import io.opaa.compliance.SelfDisclosureSubject;
import io.opaa.selfdisclosure.SelfDisclosureService;
import io.opaa.test.JavaSources;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
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

  private static final String QUERY_KEYWORD = "\\b(?:FROM|JOIN|INTO|UPDATE)\\s+(?:ONLY\\s+)?";

  /** A query naming the table itself, not one of its monthly partitions, in any letter case. */
  private static final Pattern QUERY_ON_AUDIT_LOG =
      Pattern.compile(QUERY_KEYWORD + "audit_log\\b", Pattern.CASE_INSENSITIVE);

  /** A string literal that is the bare table name, so it can stand in a query as a constant. */
  private static final Pattern TABLE_NAME_LITERAL =
      Pattern.compile("\"audit_log\"", Pattern.CASE_INSENSITIVE);

  /** The constant a {@link #TABLE_NAME_LITERAL} is assigned to; group 1 is its name. */
  private static final Pattern TABLE_NAME_CONSTANT =
      Pattern.compile("(\\w+)\\s*=\\s*\"audit_log\"", Pattern.CASE_INSENSITIVE);

  /**
   * The lines that hold the bare table name without querying it: the JPA mapping, read only through
   * the funnel of {@link AuditFunnelStructureTest}, and the private object id of the self-log
   * entry. Any other such literal counts as a reader.
   */
  private static final Set<String> NON_QUERY_TABLE_NAME_LINES =
      Set.of(
          "@Table(name = \"audit_log\")",
          "private static final String AUDIT_LOG_SELF_OBJECT_ID = \"audit_log\";");

  @Test
  void onlyTheSelfDisclosureExporterQueriesTheAuditLogInSql() {
    assertThat(sqlReaders(mainSources())).containsExactly("AuditLogSelfDisclosureExporter.java");
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
    assertThat(exporterCallers(MAIN)).containsExactly(SelfDisclosureService.class.getName());
  }

  @Test
  void aLowerCaseQueryCountsAsReader() {
    assertThat(
            sqlReaders(
                Map.of("Fixture.java", "String sql = \"select event_id from audit_log where 1\";")))
        .containsExactly("Fixture.java");
  }

  @Test
  void aQueryOnTheTableNameFromAConstantCountsAsReader() {
    assertThat(
            sqlReaders(
                Map.of(
                    "Fixture.java",
                    """
                    private static final String TABLE = "audit_log";
                    String sql = "SELECT event_id FROM " + TABLE;
                    """)))
        .containsExactly("Fixture.java");
  }

  @Test
  void aMethodReferenceToExportCountsAsCall() {
    JavaClasses fixtures =
        new ClassFileImporter().importClasses(MethodReferenceCaller.class, LambdaCaller.class);

    assertThat(exporterCallers(fixtures))
        .containsExactlyInAnyOrder(
            MethodReferenceCaller.class.getName(), LambdaCaller.class.getName());
  }

  /**
   * The sources that query {@code audit_log}: by name in any letter case, by a constant holding the
   * name, or by holding the name in a literal outside {@link #NON_QUERY_TABLE_NAME_LINES}.
   */
  private static Set<String> sqlReaders(Map<String, String> sources) {
    return sources.entrySet().stream()
        .filter(source -> queriesTheAuditLog(source.getValue()))
        .map(Map.Entry::getKey)
        .collect(Collectors.toCollection(TreeSet::new));
  }

  private static boolean queriesTheAuditLog(String source) {
    if (QUERY_ON_AUDIT_LOG.matcher(source).find()) {
      return true;
    }
    boolean literalOutsideKnownLines =
        source
            .lines()
            .map(String::strip)
            .anyMatch(
                line ->
                    TABLE_NAME_LITERAL.matcher(line).find()
                        && !NON_QUERY_TABLE_NAME_LINES.contains(line));
    if (literalOutsideKnownLines) {
      return true;
    }
    Matcher constants = TABLE_NAME_CONSTANT.matcher(source);
    while (constants.find()) {
      Pattern queryOnConstant =
          Pattern.compile(
              QUERY_KEYWORD + "\"\\s*\\+\\s*(?:\\w+\\.)?" + constants.group(1) + "\\b",
              Pattern.CASE_INSENSITIVE);
      if (queryOnConstant.matcher(source).find()) {
        return true;
      }
    }
    return false;
  }

  /** The classes that call {@code export} on an exporter, directly or as a method reference. */
  private static Set<String> exporterCallers(JavaClasses classes) {
    return classes.stream()
        .filter(
            type ->
                Stream.concat(
                        type.getMethodCallsFromSelf().stream(),
                        type.getMethodReferencesFromSelf().stream())
                    .anyMatch(
                        access ->
                            access.getName().equals("export")
                                && access
                                    .getTargetOwner()
                                    .isAssignableTo(SelfDisclosureExporter.class)))
        .map(JavaClass::getName)
        .collect(Collectors.toSet());
  }

  /** Every main source file by its file name. */
  private static Map<String, String> mainSources() {
    Map<String, String> sources = new TreeMap<>();
    try (Stream<Path> files = Files.walk(JavaSources.MAIN_SOURCES)) {
      for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
        sources.put(file.getFileName().toString(), Files.readString(file, StandardCharsets.UTF_8));
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return sources;
  }

  @FunctionalInterface
  private interface Export {
    void run(SelfDisclosureSubject subject, SelfDisclosureSink sink) throws IOException;
  }

  /** Fixture: hands the export on as a method reference instead of calling it. */
  private static final class MethodReferenceCaller {
    Export export(SelfDisclosureExporter exporter) {
      return exporter::export;
    }
  }

  /** Fixture: calls the export inside a lambda. */
  private static final class LambdaCaller {
    Export export(SelfDisclosureExporter exporter) {
      return (subject, sink) -> exporter.export(subject, sink);
    }
  }
}
