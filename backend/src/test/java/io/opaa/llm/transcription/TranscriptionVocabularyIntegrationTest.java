package io.opaa.llm.transcription;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.common.FieldValidationException;
import io.opaa.compliance.SelfDisclosureSubject;
import io.opaa.test.CapturingSelfDisclosureSink;
import io.opaa.test.OpaaIntegrationTest;
import io.opaa.test.OwnOrganizationFixtures;
import io.opaa.test.SelfDisclosureTestData;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The person's own vocabulary list against Postgres: stored per account, removed by an empty list
 * and with the account, and exported in the person's self-disclosure only.
 */
@OpaaIntegrationTest
class TranscriptionVocabularyIntegrationTest {

  @Autowired private PersonalTranscriptionVocabularyService service;
  @Autowired private TranscriptionVocabularySelfDisclosureExporter exporter;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private OwnOrganizationFixtures organizations;

  private SelfDisclosureTestData data;
  private UUID person;
  private UUID other;

  @BeforeEach
  void setUp() {
    data = SelfDisclosureTestData.newOrganization(jdbc, "transcription-vocabulary-org");
    person = data.user("petra-person");
    other = data.user("otto-fremd");
  }

  @AfterEach
  void tearDown() {
    jdbc.update(
        "DELETE FROM transcription_vocabularies WHERE organization_id = ?", data.organizationId());
    data.removeAll(organizations);
  }

  @Test
  void eachPersonHasHerOwnListAndAnEmptyListRemovesIt() {
    assertThat(service.vocabularyOf(person, data.organizationId())).isEmpty();

    assertThat(service.replace(person, data.organizationId(), "  Referat Z 4, Az. 12-345 "))
        .isEqualTo("Referat Z 4, Az. 12-345");
    service.replace(other, data.organizationId(), "Otto-Begriff");
    service.replace(person, data.organizationId(), "Referat Z 4");

    assertThat(service.vocabularyOf(person, data.organizationId())).isEqualTo("Referat Z 4");
    assertThat(service.vocabularyOf(other, data.organizationId())).isEqualTo("Otto-Begriff");
    assertThat(service.vocabularyOf(person, UUID.randomUUID())).isEmpty();

    service.replace(person, data.organizationId(), " ");
    assertThat(rows(person)).isZero();
  }

  @Test
  void aListOverTheCapIsRefused() {
    assertThatThrownBy(
            () ->
                service.replace(
                    person,
                    data.organizationId(),
                    "x".repeat(TranscriptionVocabulary.MAX_LENGTH + 1)))
        .isInstanceOf(FieldValidationException.class);
    assertThat(rows(person)).isZero();
  }

  @Test
  void theListGoesWithTheAccount() {
    service.replace(person, data.organizationId(), "Referat Z 4");

    jdbc.update("DELETE FROM users WHERE id = ?", person);

    assertThat(rows(person)).isZero();
  }

  @Test
  void theSelfDisclosureCarriesTheOwnListOnly() throws Exception {
    service.replace(other, data.organizationId(), "Otto-Begriff");
    CapturingSelfDisclosureSink none = export();
    assertThat(none.text()).isEqualTo("{}");

    service.replace(person, data.organizationId(), "Referat Z 4");
    CapturingSelfDisclosureSink sink = export();

    assertThat(sink.records("vocabulary"))
        .singleElement()
        .satisfies(
            record -> {
              assertThat(record).containsEntry("vocabulary", "Referat Z 4");
              assertThat(record.get("updatedAt")).isNotNull();
            });
    assertThat(sink.text()).doesNotContain("Otto-Begriff").doesNotContain(person.toString());
  }

  private int rows(UUID account) {
    Integer count =
        jdbc.queryForObject(
            "SELECT count(*) FROM transcription_vocabularies WHERE user_id = ?",
            Integer.class,
            account);
    return count == null ? 0 : count;
  }

  private CapturingSelfDisclosureSink export() throws Exception {
    CapturingSelfDisclosureSink sink =
        new CapturingSelfDisclosureSink(TranscriptionVocabularySelfDisclosureExporter.CATEGORY);
    exporter.export(new SelfDisclosureSubject(person, data.organizationId()), sink);
    return sink;
  }
}
