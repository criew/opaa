package io.opaa.llm;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * The single row of {@code transcription_model} (ADR-0060, Entscheidung 10), built like {@link
 * RerankModelSettings}; {@link #language} {@code null} lets the model detect the language, {@link
 * #vocabulary} {@code null} sends no installation-wide prompt.
 */
@Entity
@Table(name = "transcription_model")
public class TranscriptionModelSettings extends OptionalRoleSettings {

  @Column(name = "language", length = 8)
  private String language;

  @Column(name = "vocabulary", length = TranscriptionVocabulary.MAX_LENGTH)
  private String vocabulary;

  protected TranscriptionModelSettings() {}

  TranscriptionModelSettings(Instant now) {
    super(now);
  }

  /** Replaces the columns of the transcription role; the endpoint goes through the superclass. */
  void replaceOwn(String language, String vocabulary) {
    this.language = language;
    this.vocabulary = vocabulary;
  }

  public String getLanguage() {
    return language;
  }

  /** The installation's vocabulary list; {@code null} when none is stored. */
  public String getVocabulary() {
    return vocabulary;
  }
}
