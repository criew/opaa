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
  void theDataCatalogueSplitsColumnsByPersonalReference() {
    String catalogue = FactSheetRenderer.render(SHIPPED, PROVENANCE).get(FactSheet.DATENKATALOG);

    assertThat(catalogue)
        .contains("| Spalten mit Personenbezug | `external_access_tokens`: `user_id`, `name`")
        .contains("| Spalten ohne Personenbezug | `external_access_tokens`: `id`, `token_prefix`")
        .contains(
            "| `external_access_token_libraries` | `token_id`, `library_id`, `extinguished_at` |");
  }

  /** Data held in the operator's log or file store name that store, not the database. */
  @Test
  void theDataCatalogueNamesTheOperatorsStoreForDataOutsideTheDatabase() {
    String catalogue = FactSheetRenderer.render(SHIPPED, PROVENANCE).get(FactSheet.DATENKATALOG);

    assertThat(section(catalogue, "application-log"))
        .contains("| Zugriffsweg | Ablage des Betriebs (Dateisystem, Objektspeicher, Log) |")
        .doesNotContain("Datenbank |");
    assertThat(section(catalogue, "original-storage"))
        .contains(
            "| Zugriffsweg | Oberfläche, API, Ablage des Betriebs (Dateisystem, Objektspeicher,"
                + " Log) |")
        .doesNotContain("Datenbank |");
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
  void theDataFlowsAndTheDefaultsAreCapturedWithoutANotYetCapturedSection() {
    Map<FactSheet, String> sheets = FactSheetRenderer.render(SHIPPED, PROVENANCE);

    assertThat(sheets.get(FactSheet.DATENFLUESSE))
        .doesNotContain("Noch nicht erfasst")
        .contains("<a id=\"chat-model\"></a>", "<a id=\"mail\"></a>");
    // regression guard for #2854: the manifest holds settings and default models, none pending
    assertThat(sheets.get(FactSheet.VOREINSTELLUNGEN))
        .doesNotContain("Noch nicht erfasst")
        .doesNotContain("issues/2548")
        .contains("<a id=\"bereich-configuration\"></a>");
  }

  @Test
  void noHeadingRepeatsInTheDefaultsSheet() {
    for (ComplianceManifest manifest : List.of(SHIPPED, FIXTURE)) {
      assertThat(
              headings(
                  FactSheetRenderer.render(manifest, PROVENANCE).get(FactSheet.VOREINSTELLUNGEN)))
          .doesNotHaveDuplicates();
    }
  }

  @Test
  void promptsAndAiFunctionsAreGroupedByAreaOnlyWhenThereAreSeveral() {
    ComplianceManifest twoAreas =
        new ComplianceManifest(
            Map.of("first", "Erster Bereich", "second", "Zweiter Bereich"),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(prompt("first", "one"), prompt("second", "two")),
            List.of(),
            List.of());
    ComplianceManifest oneArea =
        new ComplianceManifest(
            Map.of("first", "Erster Bereich"),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(prompt("first", "one"), prompt("first", "two")),
            List.of(),
            List.of());

    assertThat(FactSheetRenderer.render(twoAreas, PROVENANCE).get(FactSheet.VOREINSTELLUNGEN))
        .contains("### Erster Bereich", "### Zweiter Bereich");
    assertThat(FactSheetRenderer.render(oneArea, PROVENANCE).get(FactSheet.VOREINSTELLUNGEN))
        .doesNotContain("### Erster Bereich")
        .contains("<a id=\"prompt-one\"></a>", "<a id=\"prompt-two\"></a>");
  }

  @Test
  void noHeadingRepeatsInTheDataFlowsSheet() {
    for (ComplianceManifest manifest : List.of(SHIPPED, FIXTURE)) {
      assertThat(
              headings(FactSheetRenderer.render(manifest, PROVENANCE).get(FactSheet.DATENFLUESSE)))
          .doesNotHaveDuplicates();
    }
  }

  @Test
  void dataFlowsAreGroupedByAreaOnlyWhenThereAreSeveralAndKeepTheAreaAnchor() {
    ComplianceManifest twoAreas =
        dataFlowsIn(
            Map.of("first", "Erster Bereich", "second", "Zweiter Bereich"),
            dataFlow("first", "one"),
            dataFlow("second", "two"));
    ComplianceManifest oneArea =
        dataFlowsIn(
            Map.of("first", "Erster Bereich"), dataFlow("first", "one"), dataFlow("first", "two"));

    assertThat(FactSheetRenderer.render(twoAreas, PROVENANCE).get(FactSheet.DATENFLUESSE))
        .contains(
            "### <a id=\"bereich-first\"></a>Erster Bereich",
            "### <a id=\"bereich-second\"></a>Zweiter Bereich",
            "#### <a id=\"one\"></a>`one`");
    String single = FactSheetRenderer.render(oneArea, PROVENANCE).get(FactSheet.DATENFLUESSE);
    assertThat(single)
        .doesNotContain("Erster Bereich")
        .contains(
            "## <a id=\"datenfluesse\"></a><a id=\"bereich-first\"></a>Ausgehende Datenflüsse",
            "### <a id=\"one\"></a>`one`",
            "### <a id=\"two\"></a>`two`");
    assertThat(FactSheetRenderer.placeholderTargets(SHIPPED))
        .contains("datenfluesse#bereich-outbound");
  }

  private static ComplianceManifest dataFlowsIn(
      Map<String, String> areas, ComplianceManifest.DataFlowEntry... flows) {
    return new ComplianceManifest(
        areas,
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(flows),
        List.of());
  }

  private static ComplianceManifest.DataFlowEntry dataFlow(String area, String id) {
    return new ComplianceManifest.DataFlowEntry(
        area,
        id,
        ComplianceManifest.DataFlowGroup.MAIL,
        false,
        "Ziel",
        "Daten",
        "Anlass",
        "Aus",
        List.of(),
        null);
  }

  private static ComplianceManifest.PromptEntry prompt(String area, String id) {
    return new ComplianceManifest.PromptEntry(
        area, id, "Zweck", List.of("io.opaa.Example"), null, "0".repeat(64), null);
  }

  private static List<String> headings(String sheet) {
    return sheet
        .lines()
        .filter(line -> line.startsWith("#"))
        .map(line -> ANCHOR.matcher(line.replaceFirst("^#+ ", "")).replaceAll(""))
        .toList();
  }

  @Test
  void theDataFlowSheetOpensWithTheGeneratedDiagram() {
    String sheet = FactSheetRenderer.render(SHIPPED, PROVENANCE).get(FactSheet.DATENFLUESSE);

    assertThat(sheet)
        .contains(
            "## <a id=\"uebersicht\"></a>Übersicht\n\n```mermaid\n"
                + DataFlowDiagram.render(SHIPPED.dataFlows())
                + "```\n");
    assertThat(sheet.indexOf("```mermaid"))
        .isLessThan(sheet.indexOf("<a id=\"datenfluesse\"></a>"));
    assertThat(FactSheetRenderer.render(EMPTY, PROVENANCE).get(FactSheet.DATENFLUESSE))
        .doesNotContain("```mermaid");
  }

  @Test
  void eachDataFlowNamesItsGroupAndWhetherItTakesTheOutboundProxy() {
    ComplianceManifest.DataFlowEntry proxied =
        new ComplianceManifest.DataFlowEntry(
            "outbound",
            "chat-model",
            ComplianceManifest.DataFlowGroup.MODEL,
            true,
            "Ziel",
            "Daten",
            "Anlass",
            "Aus",
            List.of(),
            null);
    ComplianceManifest.DataFlowEntry direct =
        new ComplianceManifest.DataFlowEntry(
            "outbound",
            "mail",
            ComplianceManifest.DataFlowGroup.MAIL,
            false,
            "Ziel",
            "Daten",
            "Anlass",
            "Aus",
            List.of(),
            null);
    ComplianceManifest manifest =
        new ComplianceManifest(
            Map.of("outbound", "Ausgehende Datenflüsse"),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(proxied, direct),
            List.of());

    String sheet = FactSheetRenderer.render(manifest, PROVENANCE).get(FactSheet.DATENFLUESSE);

    assertThat(section(sheet, "chat-model"))
        .contains("| Gruppe | Modelle |")
        .contains(
            "| Ausgangsproxy | ja, über den Ausgangsproxy, sofern einer eingetragen ist und das"
                + " Ziel weder lokal ist noch auf seiner Ausnahmeliste steht");
    assertThat(section(sheet, "mail"))
        .contains("| Gruppe | Mail |")
        .contains("| Ausgangsproxy | nein, nie |");
  }

  /** The part of a sheet from the heading anchored at {@code anchor} up to the next heading. */
  private static String section(String sheet, String anchor) {
    int start = sheet.indexOf("<a id=\"" + anchor + "\"></a>");
    assertThat(start).as("anchor %s", anchor).isNotNegative();
    int end = sheet.indexOf("\n#", start);
    return end < 0 ? sheet.substring(start) : sheet.substring(start, end);
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
    assertThat(json.get("notYetCaptured").has("datenfluesse")).isFalse();
    assertThat(json.get("notYetCaptured").has("voreinstellungen")).isFalse();
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

    // prompts, AI functions and data flows name their area only where a section spans several
    Set<String> grouped = new HashSet<>();
    manifest.data().forEach(entry -> grouped.add(entry.area()));
    manifest.auditEvents().forEach(entry -> grouped.add(entry.area()));
    manifest.settings().forEach(entry -> grouped.add(entry.area()));
    grouped.forEach(area -> assertThat(all).contains(manifest.areas().get(area)));
    manifest
        .dataFlows()
        .forEach(
            entry ->
                assertThat(anchors(sheets.get(FactSheet.DATENFLUESSE)))
                    .contains("bereich-" + FactSheetRenderer.anchor(entry.area())));
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
