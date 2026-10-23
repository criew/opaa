package io.opaa.diagnosticaccess;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.audit.AuditActorPseudonymService;
import io.opaa.compliance.SelfDisclosureLabels;
import io.opaa.compliance.SelfDisclosureSubject;
import io.opaa.test.CapturingSelfDisclosureSink;
import io.opaa.test.OpaaIntegrationTest;
import io.opaa.test.SelfDisclosureTestData;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The categories {@code diagnostic-impersonation} and {@code diagnostic-context-log} against
 * Postgres: the person's authorities and diagnoses, diagnoses in her rights context with "andere
 * Person" and without the third party's test question, and never a justification, pseudonym, hit or
 * permission snapshot. The organization stays behind with its protocol entries, which the
 * application account cannot delete (ADR-0015).
 */
@OpaaIntegrationTest
class DiagnosticSelfDisclosureExportersIntegrationTest {

  @Autowired private DiagnosticImpersonationSelfDisclosureExporter impersonation;
  @Autowired private DiagnosticContextLogSelfDisclosureExporter contextLog;
  @Autowired private AuditActorPseudonymService pseudonyms;
  @Autowired private JdbcTemplate jdbc;

  private SelfDisclosureTestData data;
  private UUID person;
  private UUID other;
  private UUID third;
  private UUID scope;

  @BeforeEach
  void setUp() {
    data = SelfDisclosureTestData.newOrganization(jdbc, "sd-diagnostic-org");
    person = data.user("petra-person");
    other = data.user("otto-fremd");
    third = data.user("theo-dritter");
    scope = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO groups (id, organization_id, kind, name) VALUES (?, ?, 'AD_HOC',"
            + " 'Amt für Bauwesen')",
        scope,
        data.organizationId());
  }

  @AfterEach
  void tearDown() {
    UUID organization = data.organizationId();
    jdbc.update(
        "DELETE FROM diagnostic_impersonation_grants WHERE organization_id = ?", organization);
    jdbc.update("DELETE FROM users WHERE organization_id = ?", organization);
    jdbc.update("DELETE FROM groups WHERE organization_id = ?", organization);
  }

  @Test
  void exportsTheAuthoritiesThePersonHoldsOrGrantedWithOthersOnlyAsAnotherPerson()
      throws Exception {
    grant(person, other, null);
    grant(other, person, person);
    grant(third, other, null);

    CapturingSelfDisclosureSink sink =
        new CapturingSelfDisclosureSink(DiagnosticImpersonationSelfDisclosureExporter.CATEGORY);
    impersonation.export(subject(), sink);

    assertThat(sink.records("grants")).hasSize(2);
    assertThat(sink.records("grants").get(0))
        .containsEntry("holder", SelfDisclosureLabels.YOU)
        .containsEntry("grantedBy", SelfDisclosureLabels.OTHER_PERSON)
        .containsEntry("scope", "Amt für Bauwesen")
        .containsEntry("revokedBy", null);
    assertThat(sink.records("grants").get(1))
        .containsEntry("holder", SelfDisclosureLabels.OTHER_PERSON)
        .containsEntry("grantedBy", SelfDisclosureLabels.YOU)
        .containsEntry("revokedBy", SelfDisclosureLabels.YOU);
    assertThat(sink.records("grants").get(1).get("revokedAt")).isNotNull();
    assertForeignFree(sink);
  }

  @Test
  void exportsOwnDiagnosesAndThoseInTheOwnContextWithoutJustificationOrThirdPartyText()
      throws Exception {
    String mine = pseudonym(person);
    String others = pseudonym(other);
    log(40, mine, "USER", pseudonym(third), "Eigene Testfrage zur Akte", 4, "SD-JUST-own");
    log(30, mine, "PERMISSION_PROFILE", scope.toString(), "Testfrage Rechteprofil", 2, null);
    log(20, others, "USER", mine, "SD-FOREIGN-QUESTION zu Petra", 7, "SD-JUST-other");
    log(10, others, "USER", pseudonym(third), "SD-FOREIGN-UNRELATED", 1, "SD-JUST-third");

    CapturingSelfDisclosureSink sink =
        new CapturingSelfDisclosureSink(DiagnosticContextLogSelfDisclosureExporter.CATEGORY);
    contextLog.export(subject(), sink);

    assertThat(sink.records("own"))
        .extracting(r -> r.get("testQuestion"))
        .containsExactly("Eigene Testfrage zur Akte", "Testfrage Rechteprofil");
    assertThat(sink.records("own").get(0))
        .containsEntry("targetKind", "Rechtekontext einer Person")
        .containsEntry("hitCount", 4);
    assertThat(sink.records("inYourContext"))
        .singleElement()
        .satisfies(
            record -> {
              assertThat(record).containsEntry("actor", SelfDisclosureLabels.OTHER_PERSON);
              assertThat(record).containsEntry("hitCount", 7);
              assertThat(record.get("recordedAt")).isNotNull();
            });
    assertThat(sink.text())
        .doesNotContain("SD-JUST")
        .doesNotContain("SD-FOREIGN")
        .doesNotContain("SD-HIT")
        .doesNotContain("SD-SNAPSHOT")
        .doesNotContain(mine)
        .doesNotContain(scope.toString());
    assertForeignFree(sink);
  }

  @Test
  void aPersonWithoutPseudonymHasNoDiagnosisEntries() throws Exception {
    CapturingSelfDisclosureSink sink =
        new CapturingSelfDisclosureSink(DiagnosticContextLogSelfDisclosureExporter.CATEGORY);
    contextLog.export(subject(), sink);

    assertThat(sink.text()).isEqualTo("{}");
  }

  private void assertForeignFree(CapturingSelfDisclosureSink sink) {
    assertThat(sink.text())
        .doesNotContain("otto")
        .doesNotContain("theo")
        .doesNotContain(other.toString())
        .doesNotContain(third.toString())
        .doesNotContain(pseudonym(other))
        .doesNotContain(pseudonym(third));
  }

  private SelfDisclosureSubject subject() {
    return new SelfDisclosureSubject(person, data.organizationId());
  }

  private String pseudonym(UUID account) {
    return pseudonyms.pseudonymFor(account, data.organizationId()).toString();
  }

  private void grant(UUID holder, UUID grantedBy, UUID revokedBy) {
    jdbc.update(
        "INSERT INTO diagnostic_impersonation_grants (id, organization_id, holder_user_id,"
            + " scope_group_id, valid_from, valid_until, granted_by_user_id, granted_at,"
            + " revoked_at, revoked_by_user_id) VALUES (?, ?, ?, ?, now(), now() + interval"
            + " '30 days', ?, clock_timestamp(), ?, ?)",
        UUID.randomUUID(),
        data.organizationId(),
        holder,
        scope,
        grantedBy,
        revokedBy == null ? null : java.sql.Timestamp.from(java.time.Instant.now()),
        revokedBy);
  }

  private void log(
      int minutesAgo,
      String actor,
      String targetKind,
      String target,
      String question,
      int hits,
      String justification) {
    jdbc.update(
        "INSERT INTO diagnostic_context_log (event_id, recorded_at, organization_id, actor_ref,"
            + " target_kind, target_ref, test_question, hit_count, hit_refs,"
            + " permission_snapshot, justification) VALUES (?, now() - make_interval(mins =>"
            + " ?), ?, ?, ?, ?, ?,"
            + " ?, 'SD-HIT-refs', 'SD-SNAPSHOT', ?)",
        UUID.randomUUID(),
        minutesAgo,
        data.organizationId(),
        actor,
        targetKind,
        target,
        question,
        hits,
        justification);
  }
}
