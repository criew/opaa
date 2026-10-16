package io.opaa.compliance;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.compliance.ManifestDelta.Change;
import io.opaa.compliance.ManifestDelta.Group;
import io.opaa.compliance.ManifestDelta.Kind;
import io.opaa.compliance.ManifestSnapshot.Origin;
import io.opaa.compliance.StageRules.Stage;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The cumulative manifest delta and the stage proposal of each change, on the fixture {@code
 * compliance/delta/base.yaml} and edited copies of it: added, removed and changed entries per
 * section, and three versions against their single deltas.
 */
class ManifestDeltaTest {

  static final String BASE = fixture();

  @Nested
  class Data {

    @Test
    void newPersonalDatumNeedsConsentZ1() {
      Change change =
          only(
              BASE,
              edit(
                  BASE,
                  "nonPersonalTables:",
                  """
                    - id: notes
                      title: Notizen
                      purpose: Gesprächsnotiz
                      personalReference: EMPLOYEE
                      tables:
                        - name: chat_notes
                          personalColumns: [text]
                  nonPersonalTables:"""));

      assertThat(change.kind()).isEqualTo(Kind.ADDED);
      assertThat(change.group()).isEqualTo(Group.DATA);
      assertProposal(change, Stage.ZUSTIMMUNG, "Z1");
      assertThat(change.assessment().vvt()).isTrue();
      assertThat(change.assessment().dsfa()).isTrue();
    }

    @Test
    void newDatumWithoutPersonalReferenceIsATrifle() {
      Change change =
          only(
              BASE,
              edit(
                  BASE,
                  "nonPersonalTables:",
                  """
                    - id: branding
                      title: Erscheinungsbild
                      personalReference: NONE
                      tables:
                        - name: branding
                          personalColumns: []
                          nonPersonalColumns: [logo]
                  nonPersonalTables:"""));

      assertProposal(change, Stage.BAGATELLE);
    }

    @Test
    void removedDatumIsANotice() {
      String without =
          BASE.substring(0, BASE.indexOf("  - id: accounts"))
              + BASE.substring(BASE.indexOf("nonPersonalTables:"));

      Change change = only(BASE, without);

      assertThat(change.kind()).isEqualTo(Kind.REMOVED);
      assertProposal(change, Stage.ANZEIGE);
      assertThat(change.assessment().vvt()).isTrue();
    }

    @Test
    void newPersonalColumnNeedsConsentZ1AndANewPlainColumnIsATrifle() {
      Change personal =
          only(
              BASE,
              edit(
                  BASE,
                  "personalColumns: [subject, email]",
                  "personalColumns: [subject, email, phone]"));
      Change plain =
          only(
              BASE, edit(BASE, "nonPersonalColumns: [id]", "nonPersonalColumns: [id, created_at]"));

      assertThat(personal.field()).isEqualTo("tables[users].personalColumns");
      assertProposal(personal, Stage.ZUSTIMMUNG, "Z1");
      assertProposal(plain, Stage.BAGATELLE);
    }

    @Test
    void aPlainColumnReplacedByAnotherIsANoticeForTheOneThatWent() {
      assertProposal(
          only(BASE, edit(BASE, "nonPersonalColumns: [id]", "nonPersonalColumns: [created_at]")),
          Stage.ANZEIGE);
    }

    @Test
    void widerVisibilityNeedsConsentZ2AndNarrowerIsANotice() {
      assertProposal(
          only(
              BASE,
              edit(BASE, "visibility: [SYSTEM_ADMIN]", "visibility: [SYSTEM_ADMIN, AUDITOR]")),
          Stage.ZUSTIMMUNG,
          "Z2");
      assertProposal(
          only(BASE, edit(BASE, "accessPaths: [UI, API, DATABASE]", "accessPaths: [UI, DATABASE]")),
          Stage.ANZEIGE);
    }

    @Test
    void newExportNeedsConsentZ3() {
      assertProposal(
          only(
              BASE,
              edit(
                  BASE,
                  "accessPaths: [UI, API, DATABASE]",
                  "accessPaths: [UI, API, EXPORT, DATABASE]")),
          Stage.ZUSTIMMUNG,
          "Z3");
    }

    @Test
    void rewordedEvaluationsNeedConsentInDoubt() {
      Change change =
          only(
              BASE,
              edit(
                  BASE,
                  "evaluations: Wer hat ein Konto",
                  "evaluations: Wer hat ein Konto und wann zuletzt angemeldet"));

      assertProposal(change, Stage.ZUSTIMMUNG, "Z3");
      assertThat(change.assessment().undecided()).isTrue();
      assertThat(change.assessment().reason()).contains("im Zweifel Zustimmung");
    }

    @Test
    void strongerPersonalReferenceNeedsConsentZ1() {
      assertProposal(
          only(BASE, edit(BASE, "personalReference: IDENTIFIABLE", "personalReference: EMPLOYEE")),
          Stage.ZUSTIMMUNG,
          "Z1");
    }

    @Test
    void titleAndWhitespaceAreTrifles() {
      assertProposal(
          only(BASE, edit(BASE, "title: Benutzerkonten", "title: Konten")), Stage.BAGATELLE);
      assertProposal(
          only(
              BASE,
              edit(BASE, "evaluations: Wer hat ein Konto", "evaluations: Wer  hat ein Konto")),
          Stage.BAGATELLE);
    }

    @Test
    void tableWithoutPersonalReferenceAddedIsATrifleAndAColumnRemovedANotice() {
      assertProposal(
          only(
              BASE,
              edit(
                  BASE,
                  "  audit_retention_settings: [id, retention_months]",
                  "  audit_retention_settings: [id, retention_months]\n  feature_flags: [id, name]")),
          Stage.BAGATELLE);
      assertProposal(only(BASE, edit(BASE, "[id, retention_months]", "[id]")), Stage.ANZEIGE);
    }
  }

  @Nested
  class AuditEvents {

    @Test
    void newEventNeedsConsentZ1AndUpdatesTheSecurityConcept() {
      Change change =
          only(
              BASE,
              edit(
                  BASE,
                  "    trigger: Eine Bibliothek wird angelegt",
                  "    trigger: Eine Bibliothek wird angelegt\n  - id: LIBRARY_DELETED\n    trigger: Eine Bibliothek wird gelöscht"));

      assertThat(change.kind()).isEqualTo(Kind.ADDED);
      assertThat(change.group()).isEqualTo(Group.AUDIT_EVENTS);
      assertThat(change.id()).isEqualTo("LIBRARY_DELETED");
      assertProposal(change, Stage.ZUSTIMMUNG, "Z1");
      assertThat(change.assessment().securityConcept()).isTrue();
      assertThat(change.assessment().vvt()).isTrue();
    }

    @Test
    void removedEventIsANotice() {
      Change change =
          only(
              BASE,
              edit(
                  BASE,
                  "  - id: LIBRARY_CREATED\n    trigger: Eine Bibliothek wird angelegt\n",
                  ""));

      assertThat(change.kind()).isEqualTo(Kind.REMOVED);
      assertProposal(change, Stage.ANZEIGE);
    }

    @Test
    void aChangedDefaultReachesEveryEventOfTheFile() {
      Change change =
          only(
              BASE,
              edit(
                  BASE,
                  "recordFields: [Zeitpunkt, handelnde Person]",
                  "recordFields: [Zeitpunkt, handelnde Person, IP-Adresse]"));

      assertThat(change.id()).isEqualTo("LIBRARY_CREATED");
      assertThat(change.field()).isEqualTo("recordFields");
      assertProposal(change, Stage.ZUSTIMMUNG, "Z1");
    }
  }

  @Nested
  class Roles {

    @Test
    void newRoleNeedsConsentZ2() {
      Change change =
          only(
              BASE,
              edit(
                  BASE,
                  "capabilities:",
                  "  - id: SPACE_ADMIN\n    seesAboutOthers: Mitglieder der Spaces\ncapabilities:"));

      assertThat(change.group()).isEqualTo(Group.ROLES);
      assertProposal(change, Stage.ZUSTIMMUNG, "Z2");
      assertThat(change.assessment().securityConcept()).isTrue();
    }

    @Test
    void removedCapabilityIsANoticeAndRewordedSightIsUndecided() {
      assertProposal(
          only(
              BASE,
              edit(
                  BASE,
                  "  - id: GROUP_MANAGER\n    seesAboutOthers: Mitglieder der verwalteten Gruppen\n",
                  "")),
          Stage.ANZEIGE);
      Change reworded =
          only(
              BASE,
              edit(
                  BASE,
                  "seesAboutOthers: Mitglieder der verwalteten Gruppen",
                  "seesAboutOthers: Mitglieder und ihre Anmeldezeiten"));
      assertProposal(reworded, Stage.ZUSTIMMUNG, "Z2");
      assertThat(reworded.assessment().undecided()).isTrue();
    }
  }

  @Nested
  class Retention {

    @Test
    void longerRetentionNeedsConsentZ4() {
      Change change = only(BASE, edit(BASE, "defaultValue: \"36\"", "defaultValue: \"48\""));

      assertThat(change.group()).isEqualTo(Group.RETENTION);
      assertThat(change.before()).isEqualTo("36");
      assertThat(change.after()).isEqualTo("48");
      assertProposal(change, Stage.ZUSTIMMUNG, "Z4");
      assertThat(change.assessment().vvt()).isTrue();
    }

    @Test
    void shorterRetentionIsANotice() {
      assertProposal(
          only(BASE, edit(BASE, "defaultValue: \"36\"", "defaultValue: \"24\"")), Stage.ANZEIGE);
    }

    @Test
    void incomparableRetentionNeedsConsentInDoubt() {
      Change change = only(BASE, edit(BASE, "defaultValue: \"36\"", "defaultValue: \"0\""));

      assertProposal(change, Stage.ZUSTIMMUNG, "Z4");
      assertThat(change.assessment().undecided()).isTrue();
    }

    @Test
    void rewordedRetentionRuleOfADatumNeedsConsentInDoubt() {
      Change change =
          only(
              BASE,
              edit(
                  BASE,
                  "rule: Bis zur Löschung des Kontos",
                  "rule: Bis zur Löschung des Kontos, gesperrt unbefristet"));

      assertThat(change.field()).isEqualTo("retention.rule");
      assertProposal(change, Stage.ZUSTIMMUNG, "Z4");
      assertThat(change.assessment().undecided()).isTrue();
    }
  }

  @Nested
  class Defaults {

    @Test
    void changedDefaultValueNeedsConsentZ7() {
      Change change =
          only(
              BASE,
              edit(
                  BASE,
                  "    defaultValue: \"false\"\n    effect: Ob Suchtreffer",
                  "    defaultValue: \"true\"\n    effect: Ob Suchtreffer"));

      assertThat(change.group()).isEqualTo(Group.DEFAULTS);
      assertThat(change.field()).isEqualTo("defaultValue");
      assertProposal(change, Stage.ZUSTIMMUNG, "Z7");
    }

    @Test
    void changedHelmDefaultNeedsConsentZ7() {
      Change change =
          only(
              BASE,
              edit(
                  BASE,
                  "      values: rerank.enabled\n      defaultValue: \"false\"",
                  "      values: rerank.enabled\n      defaultValue: \"true\""));

      assertThat(change.field()).isEqualTo("helm.defaultValue");
      assertProposal(change, Stage.ZUSTIMMUNG, "Z7");
    }

    @Test
    void newSettingNeedsConsentZ7AndARemovedOneIsUndecided() {
      assertProposal(
          only(
              BASE,
              edit(
                  BASE,
                  "prompts:\n  - id",
                  "  - key: opaa.chat.note.max-items\n    source: PROPERTY\n    defaultValue: \"10\"\n"
                      + "    effect: Punkte der Notiz\n    bearings: [DATA]\nprompts:\n  - id")),
          Stage.ZUSTIMMUNG,
          "Z7");
      Change removed =
          only(
              BASE,
              edit(
                  BASE,
                  "  - key: spring.ai.openai.chat.model\n    source: PROPERTY\n    defaultValue: phi3:mini\n"
                      + "    effect: Chat-Modell\n    bearings: [DATA_FLOW]\n",
                  ""));
      assertThat(removed.kind()).isEqualTo(Kind.REMOVED);
      assertProposal(removed, Stage.ZUSTIMMUNG, "Z7");
      assertThat(removed.assessment().undecided()).isTrue();
    }
  }

  @Nested
  class ModelsAndPrompts {

    @Test
    void changedPromptIsANotice() {
      Change change =
          only(
              BASE,
              edit(
                  BASE,
                  "\"1111111111111111111111111111111111111111111111111111111111111111\"",
                  "\"2222222222222222222222222222222222222222222222222222222222222222\""));

      assertThat(change.group()).isEqualTo(Group.MODELS_AND_PROMPTS);
      assertThat(change.field()).isEqualTo("sha256");
      assertProposal(change, Stage.ANZEIGE);
    }

    @Test
    void movedPromptWithTheSameTextIsATrifle() {
      assertProposal(
          only(
              BASE,
              edit(
                  BASE,
                  "classes: [io.opaa.query.answer.AnswerGenerationService]",
                  "classes: [io.opaa.query.answer.AnswerService]")),
          Stage.BAGATELLE);
    }

    @Test
    void newPromptNeedsConsentInDoubt() {
      Change change =
          only(
              BASE,
              edit(
                  BASE,
                  "dataFlows:",
                  "  - id: chat-title\n    purpose: Titel des Chats\n    classes: [io.opaa.chat.ChatTitle]\n"
                      + "    sha256: \"3333333333333333333333333333333333333333333333333333333333333333\"\ndataFlows:"));

      assertProposal(change, Stage.ZUSTIMMUNG, "Z3");
      assertThat(change.assessment().undecided()).isTrue();
    }

    @Test
    void changedStandardModelIsANoticeAndAChangedAddressNeedsConsentZ5() {
      Change model = only(BASE, edit(BASE, "defaultValue: phi3:mini", "defaultValue: llama3"));
      Change address =
          only(
              BASE,
              edit(
                  BASE,
                  "    defaultValue: \"\"\n    effect: Adresse",
                  "    defaultValue: https://rerank.example\n    effect: Adresse"));

      assertThat(model.group()).isEqualTo(Group.MODELS_AND_PROMPTS);
      assertProposal(model, Stage.ANZEIGE);
      assertThat(address.group()).isEqualTo(Group.MODELS_AND_PROMPTS);
      assertProposal(address, Stage.ZUSTIMMUNG, "Z5", "Z7");
      assertThat(address.assessment().securityConcept()).isTrue();
    }
  }

  @Nested
  class DataFlows {

    @Test
    void newDataFlowNeedsConsentZ5() {
      Change change =
          only(
              BASE,
              edit(
                  BASE,
                  "aiFunctions:",
                  """
                    - id: rerank-model
                      group: MODEL
                      viaOutboundProxy: true
                      target: Das Rerank-Modell
                      data: Frage und Suchtreffer
                      trigger: Jede Suche
                      switchOff: Mit opaa.rerank.enabled
                      settings: [opaa.rerank.enabled]
                  aiFunctions:"""));

      assertThat(change.group()).isEqualTo(Group.DATA_FLOWS);
      assertProposal(change, Stage.ZUSTIMMUNG, "Z5");
      assertThat(change.assessment().vvt()).isTrue();
      assertThat(change.assessment().securityConcept()).isTrue();
    }

    @Test
    void newTargetNeedsConsentZ5AndANewGroupIsATrifle() {
      assertProposal(
          only(
              BASE,
              edit(
                  BASE,
                  "target: Das aktive Chat-Modell der Systemverwaltung",
                  "target: Ein Cloud-Dienst")),
          Stage.ZUSTIMMUNG,
          "Z5");
      assertProposal(only(BASE, edit(BASE, "group: MODEL", "group: CONNECTOR")), Stage.BAGATELLE);
    }

    @Test
    void removedDataFlowIsANotice() {
      String without =
          BASE.substring(0, BASE.indexOf("  - id: chat-model"))
              + BASE.substring(BASE.indexOf("aiFunctions:"));

      assertProposal(only(BASE, without), Stage.ANZEIGE);
    }

    @Test
    void aFieldTheOlderSchemaLacksIsCompared() {
      Change change = only(edit(BASE, "    viaOutboundProxy: true\n", ""), BASE);

      assertThat(change.field()).isEqualTo("viaOutboundProxy");
      assertThat(change.before()).isNull();
      assertThat(change.after()).isEqualTo("true");
      assertProposal(change, Stage.ZUSTIMMUNG, "Z5");
      assertThat(change.assessment().undecided()).isTrue();
    }
  }

  @Nested
  class AiFunctions {

    @Test
    void newAiFunctionNeedsConsentZ6() {
      Change change =
          only(
              BASE,
              BASE
                  + """
                    - id: summary
                      purpose: Fasst Dokumente zusammen
                      outputKind: TEXT
                      interactsWithPersons: true
                      assessesPersons: false
                      labelling: Hinweis
                      prompts: []
                  """);

      assertThat(change.group()).isEqualTo(Group.AI_FUNCTIONS);
      assertProposal(change, Stage.ZUSTIMMUNG, "Z6");
    }

    @Test
    void changedClassificationFactNeedsConsentZ6AndARemovedFunctionIsANotice() {
      assertProposal(
          only(BASE, edit(BASE, "assessesPersons: false", "assessesPersons: true")),
          Stage.ZUSTIMMUNG,
          "Z6");
      String without = BASE.substring(0, BASE.indexOf("aiFunctions:")) + "aiFunctions: []\n";
      assertProposal(only(BASE, without), Stage.ANZEIGE);
    }
  }

  @Test
  void renamedAreaIsATrifle() {
    Change change = only(BASE, edit(BASE, "title: Testbereich", "title: Prüfbereich"));

    assertThat(change.group()).isEqualTo(Group.STRUCTURE);
    assertProposal(change, Stage.BAGATELLE);
  }

  @Test
  void unknownSectionNeedsConsentInDoubt() {
    Change change = only(BASE, BASE + "exports:\n  - id: audit-csv\n    format: CSV\n");

    assertThat(change.group()).isEqualTo(Group.OTHER);
    assertProposal(change, Stage.ZUSTIMMUNG);
    assertThat(change.assessment().undecided()).isTrue();
  }

  @Test
  void identicalVersionsHaveNoChanges() {
    assertThat(delta(BASE, BASE).changes()).isEmpty();
  }

  @Test
  void theDeltaOverThreeVersionsIsTheSummaryOfTheSingleDeltas() {
    String a = BASE;
    String b =
        edit(
            edit(a, "defaultValue: \"36\"", "defaultValue: \"48\""),
            "    trigger: Eine Bibliothek wird angelegt",
            "    trigger: Eine Bibliothek wird angelegt\n  - id: LIBRARY_DELETED\n    trigger: Eine Bibliothek wird gelöscht");
    String c =
        edit(
            edit(b, "defaultValue: \"48\"", "defaultValue: \"60\""),
            "\n  - id: LIBRARY_DELETED\n    trigger: Eine Bibliothek wird gelöscht",
            "");

    assertThat(delta(a, b).changes()).hasSize(2);
    assertThat(delta(b, c).changes()).hasSize(2);
    List<Change> cumulative = delta(a, c).changes();

    assertThat(cumulative).hasSize(1);
    assertThat(cumulative.getFirst().id()).isEqualTo("audit_retention_settings.retention_months");
    assertThat(cumulative.getFirst().before()).isEqualTo("36");
    assertThat(cumulative.getFirst().after()).isEqualTo("60");
    assertProposal(cumulative.getFirst(), Stage.ZUSTIMMUNG, "Z4");
  }

  @Test
  void changesComeInTheOrderOfTheirGroupsWhateverTheInput() {
    String newer =
        edit(
            edit(BASE, "group: MODEL", "group: CONNECTOR"),
            "personalReference: IDENTIFIABLE",
            "personalReference: EMPLOYEE");

    ManifestDelta delta = delta(BASE, newer);

    assertThat(delta.changes())
        .extracting(Change::group)
        .containsExactly(Group.DATA, Group.DATA_FLOWS);
    assertThat(delta(BASE, newer)).isEqualTo(delta);
  }

  static ManifestDelta delta(String older, String newer) {
    return ManifestDelta.between(version(older, "v1"), version(newer, "v2"));
  }

  static ManifestSnapshot version(String yaml, String name) {
    return ManifestSnapshot.fromYaml(
        new Origin("directory", name, name, "c-" + name, true),
        new TreeMap<>(Map.of("test.yaml", yaml)));
  }

  private static Change only(String older, String newer) {
    List<Change> changes = delta(older, newer).changes();
    assertThat(changes).hasSize(1);
    return changes.getFirst();
  }

  private static void assertProposal(Change change, Stage stage, String... classes) {
    assertThat(change.assessment().stage()).isEqualTo(stage);
    assertThat(change.assessment().classes()).containsExactly(classes);
    assertThat(change.assessment().dsfa()).isEqualTo(stage == Stage.ZUSTIMMUNG);
  }

  /** {@code yaml} with each {@code old} of the pairs replaced once; fails if one is missing. */
  static String edit(String yaml, String... pairs) {
    String result = yaml;
    for (int i = 0; i < pairs.length; i += 2) {
      assertThat(result).contains(pairs[i]);
      int at = result.indexOf(pairs[i]);
      result = result.substring(0, at) + pairs[i + 1] + result.substring(at + pairs[i].length());
    }
    return result;
  }

  private static String fixture() {
    try (InputStream in =
        ManifestDeltaTest.class.getResourceAsStream("/compliance/delta/base.yaml")) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
