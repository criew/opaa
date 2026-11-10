package io.opaa.llm.transcription;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A person's own vocabulary list for speech input, one row per account. Only the person reads or
 * writes it; the row goes with the account ({@code ON DELETE CASCADE}).
 */
@Entity
@Table(name = "transcription_vocabularies")
public class PersonalTranscriptionVocabulary {

  @Id
  @Column(name = "user_id")
  private UUID userId;

  @Column(name = "organization_id", nullable = false)
  private UUID organizationId;

  @Column(name = "vocabulary", nullable = false, length = TranscriptionVocabulary.MAX_LENGTH)
  private String vocabulary;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected PersonalTranscriptionVocabulary() {}

  PersonalTranscriptionVocabulary(UUID userId, UUID organizationId) {
    this.userId = userId;
    this.organizationId = organizationId;
  }

  void replace(String vocabulary, Instant now) {
    this.vocabulary = vocabulary;
    this.updatedAt = now;
  }

  public UUID getUserId() {
    return userId;
  }

  public UUID getOrganizationId() {
    return organizationId;
  }

  public String getVocabulary() {
    return vocabulary;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
