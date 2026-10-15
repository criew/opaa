package io.opaa.compliance;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.compliance.ComplianceManifest.DataFacts;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The fact sheets rendered from the manifest: one per {@link FactSheet}, each with its provenance
 * in the head, every manifest entry under a stable anchor, and an empty section said to be not yet
 * captured instead of left out.
 */
class FactSheetRendererTest {

  private static final FactSheetProvenance PROVENANCE =
      new FactSheetProvenance(
          "1.2.3", "0123456789abcdef0123456789abcdef01234567", LocalDate.of(2026, 10, 15));

  private static final ComplianceManifest SHIPPED = ComplianceManifestLoader.load();

  private static final ComplianceManifest FIXTURE =
      ComplianceManifestLoader.load("classpath*:compliance-fixtures/valid/*.yaml");

  private static final ComplianceManifest EMPTY =
      new ComplianceManifest(
          Map.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
          List.of(), List.of());

  private static final Pattern ANCHOR = Pattern.compile("<a id=\"([^\"]*)\"></a>");

  @Test
  void rendersEverySheet() {
    assertThat(FactSheetRenderer.render(SHIPPED, PROVENANCE)).containsOnlyKeys(FactSheet.values());
  }

  @Test
  void everySheetCarriesVersionCommitAndGenerationInItsHead() {
    for (Map.Entry<FactSheet, String> sheet :
        FactSheetRenderer.render(SHIPPED, PROVENANCE).entrySet()) {
      assertThat(sheet.getValue())
          .as(sheet.getKey().sheetName())
          .startsWith(
              """
              ---
              gilt-fuer: 1.2.3
              commit: 0123456789abcdef0123456789abcdef01234567
              erzeugt-am: 2026-10-15
              erzeugung: generiert
              ---

              # Faktenblatt: %s

              > Fakten aus dem Code; rechtliche Bewertung bleibt bei der Behörde.
              """
                  .formatted(sheet.getKey().title()));
    }
  }

  @Test
  void everyEntryOfTheShippedManifestAppearsInAFactSheet() {
    assertEveryEntryAppears(SHIPPED);
  }

  @Test
  void everyEntryKindAppearsInAFactSheet() {
    assertThat(FIXTURE.dataFlows()).isNotEmpty();
    assertEveryEntryAppears(FIXTURE);
  }

  @Test
  void everyRetentionSettingHasARowInTheDeadlineSheet() {
    String deadlines = FactSheetRenderer.render(SHIPPED, PROVENANCE).get(FactSheet.FRISTEN);
    List<DataFacts> facts = new ArrayList<>(SHIPPED.data());
    facts.addAll(SHIPPED.auditEvents());
    for (DataFacts entry : facts) {
      for (String key : entry.retention().settings()) {
        assertThat(anchors(deadlines)).as(key).contains(FactSheetRenderer.anchor(key));
      }
    }
  }

  @Test
  void anchorsAreWellFormedAndUniqueWithinASheet() {
    for (Map.Entry<FactSheet, String> sheet :
        FactSheetRenderer.render(SHIPPED, PROVENANCE).entrySet()) {
      List<String> anchors = anchors(sheet.getValue());
      assertThat(anchors).as(sheet.getKey().sheetName()).doesNotHaveDuplicates();
      assertThat(anchors).allMatch(anchor -> anchor.matches("[a-z0-9]+(-[a-z0-9]+)*"));
    }
  }

  @Test
  void textMasksBackslashBeforePipeAndHtml() {
    assertThat(FactSheetRenderer.text("a\\|b <c> & d")).isEqualTo("a\\\\\\|b &lt;c> &amp; d");
  }

  @Test
  void codeSpanFenceOutlastsBackticksInTheValue() {
    assertThat(FactSheetRenderer.code("plain")).isEqualTo("`plain`");
    assertThat(FactSheetRenderer.code("a`b")).isEqualTo("``a`b``");
    assertThat(FactSheetRenderer.code("``x")).isEqualTo("``` ``x ```");
    assertThat(FactSheetRenderer.code("a|b")).isEqualTo("`a\\|b`");
  }

  @Test
  void durationsAreReadInWords() {
    assertThat(DurationReading.of("P7D", "x")).isEqualTo("7 Tage");
    assertThat(DurationReading.of("PT15M", "x")).isEqualTo("15 Minuten");
    assertThat(DurationReading.of("P1Y2M", "x")).isEqualTo("1 Jahr 2 Monate");
    assertThat(DurationReading.of("15m", "x")).isEqualTo("15 Minuten");
    assertThat(DurationReading.of("7d", "x")).isEqualTo("7 Tage");
    assertThat(DurationReading.of("1h", "x")).isEqualTo("1 Stunde");
    assertThat(DurationReading.of("72", "local_auth_settings.invitation_token_ttl_hours"))
        .isEqualTo("72 Stunden");
    assertThat(DurationReading.of("36", "audit_retention_settings.retention_months"))
        .isEqualTo("36 Monate");
    assertThat(DurationReading.of("90", "opaa.connection.inactivity-threshold-days"))
        .isEqualTo("90 Tage");
    assertThat(DurationReading.of("90", "opaa.limits.max-items")).isNull();
    assertThat(DurationReading.of("true", "x")).isNull();
  }

  @Test
  void deadlinesAndDefaultsShowTheReadingAndConstantsTheirValue() {
    Map<FactSheet, String> sheets = FactSheetRenderer.render(SHIPPED, PROVENANCE);

    assertThat(sheets.get(FactSheet.FRISTEN)).contains("`P7D` = 7 Tage", "`36` = 36 Monate");
    assertThat(sheets.get(FactSheet.VOREINSTELLUNGEN)).contains("`15m` = 15 Minuten");
    assertThat(sheets.get(FactSheet.DATENKATALOG))
        .contains(
            "[`io.opaa.account.LocalTokenCleanupService#RETENTION`]"
                + "(fristen.md#io-opaa-account-localtokencleanupservice-retention)"
                + " (Standard: `P7D` = 7 Tage)");
  }

  @Test
  void theMatrixHasNoColumnForARoleNoVisibilityNames() {
    String roles = FactSheetRenderer.render(SHIPPED, PROVENANCE).get(FactSheet.ROLLENSICHT);

    assertThat(roles)
        .contains("| Datenkategorie | `SYSTEM_ADMIN` | `AUDITOR` | Weitere Sichtbarkeit |")
        .contains("Keine Angabe zur Sichtbarkeit nennt [`USER`](#user) beim Namen")
        .contains("| nicht genannt |");
  }

  @Test
  void anchorIsLowerCaseWithHyphens() {
    assertThat(FactSheetRenderer.anchor("ASSET_GRANT_GRANTED")).isEqualTo("asset-grant-granted");
    assertThat(FactSheetRenderer.anchor("opaa.auth.local.access-token-ttl"))
        .isEqualTo("opaa-auth-local-access-token-ttl");
    assertThat(FactSheetRenderer.anchor("io.opaa.audit.AuditIncidentScopeGrant#USABLE_WINDOW"))
        .isEqualTo("io-opaa-audit-auditincidentscopegrant-usable-window");
  }

  @Test
  void anEmptyManifestRendersEverySheetAndSaysWhatIsNotYetCaptured() {
    Map<FactSheet, String> sheets = FactSheetRenderer.render(EMPTY, PROVENANCE);

    assertThat(sheets).containsOnlyKeys(FactSheet.values());
    for (Map.Entry<FactSheet, String> sheet : sheets.entrySet()) {
      assertThat(sheet.getValue()).as(sheet.getKey().sheetName()).contains("Noch nicht erfasst");
    }
  }

  @Test
  void dataFlowsAndDefaultModelsAreMarkedAsNotYetCaptured() {
    Map<FactSheet, String> sheets = FactSheetRenderer.render(SHIPPED, PROVENANCE);

    assertThat(sheets.get(FactSheet.DATENFLUESSE)).contains("## Noch nicht erfasst");
    assertThat(sheets.get(FactSheet.VOREINSTELLUNGEN))
        .contains("## Noch nicht erfasst")
        .contains("Standardmodelle");
  }

  @Test
  void theJsonFormHoldsProvenanceAndTheWholeManifest() {
    JsonNode json =
        JsonMapper.builder().build().readTree(FactSheetRenderer.renderJson(SHIPPED, PROVENANCE));

    assertThat(json.get("erzeugung").asString()).isEqualTo("generiert");
    assertThat(json.get("opaaVersion").asString()).isEqualTo("1.2.3");
    assertThat(json.get("commit").asString()).isEqualTo(PROVENANCE.commit());
    assertThat(json.get("generatedOn").asString()).isEqualTo("2026-10-15");
    assertThat(json.get("factSheets").values())
        .extracting(JsonNode::asString)
        .containsExactly(
            "datenkatalog",
            "protokollereignisse",
            "rollensicht",
            "datenfluesse",
            "voreinstellungen",
            "fristen");
    assertThat(json.get("notYetCaptured").has("datenfluesse")).isTrue();
    JsonNode manifest = json.get("manifest");
    assertThat(manifest.get("data").size()).isEqualTo(SHIPPED.data().size());
    assertThat(manifest.get("auditEvents").size()).isEqualTo(SHIPPED.auditEvents().size());
    assertThat(manifest.get("settings").size()).isEqualTo(SHIPPED.settings().size());
    assertThat(manifest.get("prompts").get(0).get("sha256").asString())
        .isEqualTo(SHIPPED.prompts().getFirst().sha256());
    assertThat(manifest.get("prompts").get(0).has("locatedOnce")).isFalse();
  }

  private static void assertEveryEntryAppears(ComplianceManifest manifest) {
    Map<FactSheet, String> sheets = FactSheetRenderer.render(manifest, PROVENANCE);
    String all = String.join("\n", sheets.values());

    manifest.areas().values().forEach(title -> assertThat(all).contains(title));
    manifest
        .data()
        .forEach(entry -> assertListed(sheets, FactSheet.DATENKATALOG, entry.id(), entry.id()));
    manifest
        .nonPersonalTables()
        .forEach(
            table ->
                assertThat(sheets.get(FactSheet.DATENKATALOG)).contains("`" + table.table() + "`"));
    manifest
        .auditEvents()
        .forEach(
            entry ->
                assertListed(
                    sheets,
                    FactSheet.PROTOKOLLEREIGNISSE,
                    FactSheetRenderer.anchor(entry.id()),
                    entry.id()));
    List<ComplianceManifest.RoleEntry> roles = new ArrayList<>(manifest.roles());
    roles.addAll(manifest.capabilities());
    roles.forEach(
        entry ->
            assertListed(
                sheets, FactSheet.ROLLENSICHT, FactSheetRenderer.anchor(entry.id()), entry.id()));
    manifest
        .settings()
        .forEach(
            entry ->
                assertListed(
                    sheets,
                    FactSheet.VOREINSTELLUNGEN,
                    FactSheetRenderer.anchor(entry.key()),
                    entry.key()));
    manifest
        .prompts()
        .forEach(
            entry -> {
              assertListed(sheets, FactSheet.VOREINSTELLUNGEN, "prompt-" + entry.id(), entry.id());
              assertThat(sheets.get(FactSheet.VOREINSTELLUNGEN)).contains(entry.sha256());
            });
    manifest
        .aiFunctions()
        .forEach(
            entry ->
                assertListed(
                    sheets, FactSheet.VOREINSTELLUNGEN, "ki-funktion-" + entry.id(), entry.id()));
    manifest
        .dataFlows()
        .forEach(
            entry ->
                assertListed(
                    sheets,
                    FactSheet.DATENFLUESSE,
                    FactSheetRenderer.anchor(entry.id()),
                    entry.id()));
  }

  private static void assertListed(
      Map<FactSheet, String> sheets, FactSheet sheet, String anchor, String identifier) {
    String content = sheets.get(sheet);
    assertThat(anchors(content)).as("anchor of %s in %s", identifier, sheet).contains(anchor);
    assertThat(content).as("%s in %s", identifier, sheet).contains("`" + identifier + "`");
  }

  static List<String> anchors(String content) {
    List<String> anchors = new ArrayList<>();
    Matcher matcher = ANCHOR.matcher(content);
    while (matcher.find()) {
      anchors.add(matcher.group(1));
    }
    return anchors;
  }

  static Set<String> anchorSet(String content) {
    return new HashSet<>(anchors(content));
  }
}
