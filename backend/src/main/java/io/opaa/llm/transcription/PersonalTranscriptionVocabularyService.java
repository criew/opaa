package io.opaa.llm.transcription;

import io.opaa.common.FieldValidationException;
import io.opaa.llm.ModelEndpointRules;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads and replaces a person's own vocabulary list for speech input. Every call names the person
 * and the organization of the caller; nobody reads or writes another person's list. Changes are
 * neither audited nor counted: the list is the person's own working aid.
 */
@Service
public class PersonalTranscriptionVocabularyService {

  private final PersonalTranscriptionVocabularyRepository rows;
  private final Clock clock;

  PersonalTranscriptionVocabularyService(
      PersonalTranscriptionVocabularyRepository rows, Clock clock) {
    this.rows = rows;
    this.clock = clock;
  }

  /** The person's list; empty when none is stored. */
  @Transactional(readOnly = true)
  public String vocabularyOf(UUID userId, UUID organizationId) {
    return rows.findByUserIdAndOrganizationId(userId, organizationId)
        .map(PersonalTranscriptionVocabulary::getVocabulary)
        .orElse("");
  }

  /**
   * Replaces the person's list; an empty list removes the row.
   *
   * @return the list as stored
   * @throws FieldValidationException when the list is longer than {@link
   *     TranscriptionVocabulary#MAX_LENGTH}
   */
  @Transactional
  public String replace(UUID userId, UUID organizationId, String vocabulary) {
    String normalised = TranscriptionVocabulary.normalised(vocabulary);
    if (!TranscriptionVocabulary.isValid(normalised)) {
      throw FieldValidationException.of(
          "vocabulary", ModelEndpointRules.INVALID, TranscriptionVocabulary.RULE);
    }
    PersonalTranscriptionVocabulary existing =
        rows.findByUserIdAndOrganizationId(userId, organizationId).orElse(null);
    if (normalised == null || normalised.isEmpty()) {
      if (existing != null) {
        rows.delete(existing);
      }
      return "";
    }
    PersonalTranscriptionVocabulary row =
        existing != null ? existing : new PersonalTranscriptionVocabulary(userId, organizationId);
    row.replace(normalised, clock.instant());
    rows.save(row);
    return normalised;
  }
}
