package io.opaa.compliance;

import static io.opaa.compliance.ManifestDeltaTest.BASE;
import static io.opaa.compliance.ManifestDeltaTest.delta;
import static io.opaa.compliance.ManifestDeltaTest.edit;
import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.compliance.ManifestDeltaRenderer.LabelledChanges;
import io.opaa.compliance.ManifestDeltaRenderer.PullRequest;
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
            "  - Angaben: Auslöser: Gelöscht; Felder des Protokollsatzes: Zeitpunkt; handelnde Person\n")
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
    assertThat(root.path("summary").path("stages").path("BAGATELLE").asInt()).isEqualTo(1);
    JsonNode change = root.path("changes").get(0);
    assertThat(change.path("group").asString()).isEqualTo("DATA_FLOWS");
    assertThat(change.path("section").asString()).isEqualTo("dataFlows");
    assertThat(change.path("id").asString()).isEqualTo("chat-model");
    assertThat(change.path("field").asString()).isEqualTo("group");
    assertThat(change.path("before").asString()).isEqualTo("MODEL");
    assertThat(change.path("after").asString()).isEqualTo("CONNECTOR");
    assertThat(change.path("assessment").path("stage").asString()).isEqualTo("BAGATELLE");
    assertThat(root.path("labelledPullRequests").path("queried").asBoolean()).isFalse();
  }
}
