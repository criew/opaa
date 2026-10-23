package io.opaa.compliance;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.compliance.SelfDisclosureCategory.Field;
import io.opaa.compliance.SelfDisclosureCategory.Section;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The self-disclosure exporters against the fact manifest ({@link SelfDisclosureCoverage}), without
 * a database: the backend as built has no gap, and each kind of gap is reported on a synthetic
 * exporter against the delivered manifest.
 */
class SelfDisclosureCoverageTest {

  private static final ComplianceManifest MANIFEST = ComplianceManifestLoader.load();

  private static final SelfDisclosureCategory NOTIFICATIONS =
      category(
          "notifications",
          Field.of("type", "Art", "notifications.type"),
          Field.of("title", "Titel", "notifications.title"),
          Field.of("body", "Text", "notifications.body"),
          Field.of("readAt", "Gelesen", "notifications.read_at"));

  @Test
  void everyCategoryHasExactlyOneExporterOrIsPendingAndEveryColumnIsCovered() {
    List<String> gaps =
        SelfDisclosureCoverage.gaps(
            MANIFEST,
            SelfDisclosureCoverage.productionCategories(),
            SelfDisclosureCoverage.productionPending());

    assertThat(gaps).as(String.join("\n\n", gaps)).isEmpty();
  }

  @Test
  void theProductionExportersAreFound() {
    assertThat(SelfDisclosureCoverage.productionCategories())
        .containsKeys(
            "io.opaa.notification.NotificationSelfDisclosureExporter",
            "io.opaa.selfdisclosure.SelfDisclosureStateExporter");
  }

  @Test
  void aCompleteExporterHasNoGapOfItsOwn() {
    assertThat(gapsAbout("notifications", Map.of("A", NOTIFICATIONS))).isEmpty();
  }

  @Test
  void aCategoryWithoutExporterOutsideThePendingListsIsAGap() {
    assertThat(gapsAbout("notifications", Map.of()))
        .singleElement()
        .asString()
        .startsWith("Category 'notifications'")
        .contains("has no self-disclosure exporter");
  }

  @Test
  void twoExportersOfOneCategoryAreAGap() {
    Map<String, SelfDisclosureCategory> exporters = new LinkedHashMap<>();
    exporters.put("A", NOTIFICATIONS);
    exporters.put("B", NOTIFICATIONS);

    assertThat(gapsAbout("notifications", exporters))
        .containsExactly("Category 'notifications' has two exporters, A and B; keep one.");
  }

  @Test
  void anUnknownOrExcludedCategoryIsAGap() {
    assertThat(
            gapsAbout(
                "nowhere", Map.of("A", category("nowhere", Field.of("x", "X", "users.email")))))
        .singleElement()
        .asString()
        .contains("delivers 'nowhere'");
    assertThat(
            gapsAbout(
                "mail-outbox",
                Map.of("A", category("mail-outbox", Field.of("x", "X", "mail_outbox.id")))))
        .singleElement()
        .asString()
        .contains("delivers 'mail-outbox'");
  }

  @Test
  void aFieldOnAnUnknownOrExcludedColumnIsAGapUnlessDerived() {
    SelfDisclosureCategory unknown =
        withField(NOTIFICATIONS, Field.of("ghost", "Gespenst", "notifications.ghost"));
    SelfDisclosureCategory excluded =
        withField(NOTIFICATIONS, Field.of("reason", "Begründung", "audit_log.reason"));
    SelfDisclosureCategory derived =
        withField(NOTIFICATIONS, Field.derived("reasonGiven", "Begründet", "audit_log.reason"));

    assertThat(gapsAbout("notifications", Map.of("A", unknown)))
        .singleElement()
        .asString()
        .contains("notifications.ghost, which the manifest does not know");
    assertThat(gapsAbout("notifications", Map.of("A", excluded)))
        .singleElement()
        .asString()
        .contains("audit_log.reason, which the manifest excludes");
    assertThat(gapsAbout("notifications", Map.of("A", derived))).isEmpty();
  }

  @Test
  void aDisclosedColumnWithoutFieldIsAGap() {
    SelfDisclosureCategory withoutBody =
        category(
            "notifications",
            Field.of("title", "Titel", "notifications.title"),
            Field.of("readAt", "Gelesen", "notifications.read_at"));

    assertThat(gapsAbout("notifications", Map.of("A", withoutBody)))
        .singleElement()
        .asString()
        .startsWith("Column notifications.body is part of the self-disclosure of 'notifications'");
  }

  @Test
  void aCategoryWithExporterButWithoutExportAccessPathIsAGap() {
    SelfDisclosureCategory accounts =
        category(
            "accounts",
            Field.of("email", "Adresse", "users.email"),
            Field.of("name", "Name", "users.display_name"),
            Field.of("lastLogin", "Letzte Anmeldung", "users.last_login_at"),
            Field.of("role", "Rolle", "users.system_role"),
            Field.of("locked", "Gesperrt", "users.directory_locked_at"));

    ComplianceManifest withoutExport = withoutExportPath(MANIFEST, "accounts");

    assertThat(
            SelfDisclosureCoverage.gaps(withoutExport, Map.of("A", accounts), Map.of()).stream()
                .filter(gap -> gap.contains("'accounts'"))
                .toList())
        .singleElement()
        .asString()
        .contains("add EXPORT to its accessPaths");
  }

  /** {@code manifest} with the data entry {@code id} no longer leading {@code EXPORT}. */
  private static ComplianceManifest withoutExportPath(ComplianceManifest manifest, String id) {
    List<ComplianceManifest.DataEntry> data =
        manifest.data().stream()
            .map(
                d ->
                    !d.id().equals(id)
                        ? d
                        : new ComplianceManifest.DataEntry(
                            d.area(),
                            d.id(),
                            d.title(),
                            d.purpose(),
                            d.personalReference(),
                            d.tables(),
                            d.storage(),
                            d.retention(),
                            d.visibility(),
                            d.accessPaths().stream()
                                .filter(p -> p != ComplianceManifest.AccessPath.EXPORT)
                                .toList(),
                            d.deletion(),
                            d.evaluations(),
                            d.notes(),
                            d.personExcluded()))
            .toList();
    return new ComplianceManifest(
        manifest.areas(),
        data,
        manifest.nonPersonalTables(),
        manifest.auditEvents(),
        manifest.roles(),
        manifest.capabilities(),
        manifest.settings(),
        manifest.prompts(),
        manifest.dataFlows(),
        manifest.aiFunctions());
  }

  @Test
  void aPendingCategoryWithExporterOrAnUnknownPendingEntryIsAGap() {
    Map<String, List<String>> pending = Map.of("identity.txt", List.of("notifications", "nowhere"));

    List<String> gaps =
        SelfDisclosureCoverage.gaps(MANIFEST, Map.of("A", NOTIFICATIONS), pending).stream()
            .filter(gap -> gap.contains("identity.txt"))
            .toList();

    assertThat(gaps)
        .anySatisfy(gap -> assertThat(gap).contains("'notifications' has the exporter A"))
        .anySatisfy(gap -> assertThat(gap).contains("names 'nowhere'"));
  }

  /** The gaps about {@code id}, every other category of the manifest counted as pending. */
  private static List<String> gapsAbout(String id, Map<String, SelfDisclosureCategory> exporters) {
    List<String> others =
        MANIFEST.data().stream()
            .filter(ComplianceManifest.DataEntry::selfDisclosed)
            .map(ComplianceManifest.DataEntry::id)
            .filter(other -> !other.equals(id))
            .filter(other -> exporters.values().stream().noneMatch(c -> c.id().equals(other)))
            .toList();
    return SelfDisclosureCoverage.gaps(MANIFEST, exporters, Map.of("all.txt", others));
  }

  private static SelfDisclosureCategory category(String id, Field... fields) {
    return new SelfDisclosureCategory(
        id, id, List.of(new Section("main", "Haupt", List.of(fields))));
  }

  private static SelfDisclosureCategory withField(SelfDisclosureCategory category, Field field) {
    List<Field> fields = new ArrayList<>(category.sections().getFirst().fields());
    fields.add(field);
    return category(category.id(), fields.toArray(Field[]::new));
  }
}
