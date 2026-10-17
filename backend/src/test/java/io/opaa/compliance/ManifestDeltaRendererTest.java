package io.opaa.compliance;

import static io.opaa.compliance.ManifestDeltaTest.BASE;
import static io.opaa.compliance.ManifestDeltaTest.delta;
import static io.opaa.compliance.ManifestDeltaTest.edit;
import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.compliance.ManifestDeltaRenderer.LabelledChanges;
import io.opaa.compliance.ManifestDeltaRenderer.PullRequest;
import io.opaa.compliance.ManifestSnapshot.Origin;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The Markdown for the authority and the JSON for tools. */
class ManifestDeltaRendererTest {

  private static final String THREE_EVENTS =
      edit(
          BASE,
          "    trigger: Eine Bibliothek wird angelegt",
          "    trigger: Eine Bibliothek wird angelegt\n  - id: LIBRARY_DELETED\n    trigger: Gelöscht\n"
              + "  - id: LIBRARY_RENAMED\n    trigger: Umbenannt");

  @Test
  void headNamesBothVersionsAndThatStagesAreProposals() {
    String markdown =
        ManifestDeltaRenderer.markdown(
            delta(BASE, edit(BASE, "defaultValue: \"36\"", "defaultValue: \"48\"")),
            LabelledChanges.notQueried());

    assertThat(markdown)
        .startsWith(
            "---\nerzeugung: generiert\nvon: v1\nvon-commit: c-v1\nbis: v2\nbis-commit: c-v2\n---\n")
        .contains("> " + FactSheetRenderer.NOTICE + "\n> " + ManifestDeltaRenderer.PROPOSAL_NOTICE)
        .contains("| Zustimmung | 1 |")
        .contains("- Erfüllte Zustimmungsklassen: Z4")
        .contains("## Fristen\n\n### Frist `audit_retention_settings.retention_months`")
        .contains("  - Vorher: `36`\n  - Nachher: `48`\n")
        .contains("VVT anpassen: ja · DSFA überprüfen: ja · Sicherheitskonzept fortschreiben: nein")
        .contains("## Daten\n\nKeine Änderungen.")
        .contains("## Weitere gekennzeichnete Änderungen\n\nNicht abgefragt");
  }

  @Test
  void addedEntryShowsItsFactsAndAListChangeWhatCameAndWent() {
    String markdown =
        ManifestDeltaRenderer.markdown(
            delta(BASE, edit(THREE_EVENTS, "visibility: [SYSTEM_ADMIN]", "visibility: [AUDITOR]")),
            LabelledChanges.notQueried());

    assertThat(markdown)
        .contains(
            "### Protokollereignis `LIBRARY_DELETED`\n\n- **Neu**: Vorschlag **Zustimmung** (Z1)\n")
        .contains(
            "  - Angaben:\n    - Bereich: `test`\n    - Zweck: Nachweis des Verwaltungshandelns\n"
                + "    - Personenbezug: Beschäftigtendaten\n")
        .contains(
            "    - Speicherort: Anwendungsdatenbank\n"
                + "    - Frist: Voreinstellung 36 Monate\n"
                + "    - Einstellung der Frist: `audit_retention_settings.retention_months`\n"
                + "    - Sichtbarkeit: AUDITOR\n"
                + "    - Zugriffsweg:\n      - Oberfläche\n      - Datenbank\n"
                + "    - Löschweg: Monatlicher Lauf\n"
                + "    - Mögliche Auswertungen: Wer hat wann was getan\n")
        .contains(
            "    - Auslöser: Gelöscht\n"
                + "    - Felder des Protokollsatzes:\n      - Zeitpunkt\n      - handelnde Person\n")
        .contains(
            "- **Sichtbarkeit geändert**: Vorschlag **Zustimmung** (Z2, Zuordnung offen)\n  - Hinzu: AUDITOR\n  - Entfällt: SYSTEM_ADMIN\n");
  }

  @Test
  void theSameChangeOfManyEntriesIsShownOnce() {
    String older = THREE_EVENTS;
    String newer =
        edit(
            older,
            "readers: [AUDITOR zur Prüfung]",
            "readers: [AUDITOR zur Prüfung, Personalvertretung]");

    String markdown =
        ManifestDeltaRenderer.markdown(delta(older, newer), LabelledChanges.notQueried());

    assertThat(markdown)
        .contains("| Zustimmung | 3 |")
        .contains(
            "### Gleiche Änderung an 3 Einträgen\n\nBetrifft: `LIBRARY_CREATED`, `LIBRARY_DELETED`,"
                + " `LIBRARY_RENAMED`\n\n- **Leser und Zweck geändert**")
        .doesNotContain("### Protokollereignis");
  }

  @Test
  void anAddedDataFlowShowsEveryField() {
    String markdown =
        ManifestDeltaRenderer.markdown(
            delta(edit(BASE, "  - id: chat-model", "  - id: other-model"), BASE),
            LabelledChanges.notQueried());

    assertThat(markdown)
        .contains("### Datenfluss `chat-model`\n\n- **Neu**")
        .contains("    - Gruppe im Übersichtsbild: Modelle\n")
        .contains("    - Über den Ausgangsproxy: ja\n")
        .contains("    - Ziel: Das aktive Chat-Modell der Systemverwaltung\n")
        .contains("    - Auslöser: Jede Chat-Anfrage\n")
        .contains("    - Abschaltbarkeit: Nicht abschaltbar\n")
        .contains("    - Einstellungen: `spring.ai.openai.chat.model`\n");
  }

  @Test
  void labelledPullRequestsAreListed() {
    String markdown =
        ManifestDeltaRenderer.markdown(
            delta(BASE, BASE),
            new LabelledChanges(true, false, List.of(new PullRequest(7, "Neue | Auswertung", ""))));

    assertThat(markdown)
        .contains("enthält alle übergebenen Pull Requests")
        .contains("- #7 Neue \\| Auswertung\n");
  }

  @Test
  void jsonCarriesEveryChangeWithItsProposalAndASummary() {
    String json =
        ManifestDeltaRenderer.json(
            delta(BASE, edit(BASE, "group: MODEL", "group: CONNECTOR")),
            LabelledChanges.notQueried());

    JsonNode root = JsonMapper.builder().build().readTree(json);
    assertThat(root.path("proposalNotice").asString())
        .isEqualTo(ManifestDeltaRenderer.PROPOSAL_NOTICE);
    assertThat(root.path("summary").path("stages").path("ZUSTIMMUNG").asInt()).isEqualTo(1);
    JsonNode change = root.path("changes").get(0);
    assertThat(change.path("group").asString()).isEqualTo("DATA_FLOWS");
    assertThat(change.path("section").asString()).isEqualTo("dataFlows");
    assertThat(change.path("id").asString()).isEqualTo("chat-model");
    assertThat(change.path("field").asString()).isEqualTo("group");
    assertThat(change.path("before").asString()).isEqualTo("MODEL");
    assertThat(change.path("after").asString()).isEqualTo("CONNECTOR");
    assertThat(change.path("assessment").path("stage").asString()).isEqualTo("ZUSTIMMUNG");
    assertThat(root.path("labelledPullRequests").path("queried").asBoolean()).isFalse();
  }

  /**
   * The cases in {@code compliance/delta/bare-commit-names.json} are shared with {@code
   * .github/scripts/test_update_package.py}, so delta and update package name a version without
   * manifest by the same rule.
   */
  @Test
  void namesAVersionLikeTheUpdatePackage() throws IOException {
    JsonNode cases;
    try (InputStream in =
        ManifestDeltaRendererTest.class.getResourceAsStream(
            "/compliance/delta/bare-commit-names.json")) {
      cases = JsonMapper.builder().build().readTree(in);
    }
    assertThat(cases).hasSizeGreaterThan(5);
    for (JsonNode c : cases) {
      Origin origin =
          new Origin(
              "ref",
              c.path("label").asString(),
              c.path("label").asString(),
              c.path("commit").asString(),
              c.path("hasManifest").asBoolean());
      assertThat(ManifestDeltaRenderer.name(origin))
          .as(c.toString())
          .isEqualTo(c.path("name").asString());
    }
  }
}
