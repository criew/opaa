package io.opaa.llm;

import io.opaa.api.types.RealtimeTranscriptionProtocol;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * The single row of {@code transcription_model} (ADR-0060, Entscheidung 10), built like {@link
 * RerankModelSettings}; {@link #language} {@code null} lets the model detect the language, {@link
 * #vocabulary} {@code null} sends no installation-wide prompt. The {@code realtime_*} columns hold
 * the optional live upstream (ADR-0072): all {@code null} for none; its key is encrypted like the
 * batch key, {@code null} sends the batch key.
 */
@Entity
@Table(name = "transcription_model")
public class TranscriptionModelSettings extends OptionalRoleSettings {

  @Column(name = "language", length = 8)
  private String language;

  @Column(name = "vocabulary", length = TranscriptionVocabulary.MAX_LENGTH)
  private String vocabulary;

  @Enumerated(EnumType.STRING)
  @Column(name = "realtime_protocol", length = 16)
  private RealtimeTranscriptionProtocol realtimeProtocol;

  @Column(name = "realtime_url", length = ModelEndpointRules.MAX_ADDRESS)
  private String realtimeUrl;

  @Column(name = "realtime_model", length = ModelEndpointRules.MAX_NAME)
  private String realtimeModel;

  @Column(name = "realtime_api_key_ciphertext")
  private String realtimeApiKeyCiphertext;

  @Column(name = "realtime_max_sessions")
  private Integer realtimeMaxSessions;

  protected TranscriptionModelSettings() {}

  TranscriptionModelSettings(Instant now) {
    super(now);
  }

  /** Replaces the columns of the transcription role; the endpoint goes through the superclass. */
  void replaceOwn(String language, String vocabulary) {
    this.language = language;
    this.vocabulary = vocabulary;
  }

  /** Replaces the live upstream; {@code protocol} {@code null} with all others {@code null}. */
  void replaceRealtime(
      RealtimeTranscriptionProtocol protocol,
      String url,
      String model,
      String apiKeyCiphertext,
      Integer maxSessions) {
    this.realtimeProtocol = protocol;
    this.realtimeUrl = url;
    this.realtimeModel = model;
    this.realtimeApiKeyCiphertext = apiKeyCiphertext;
    this.realtimeMaxSessions = maxSessions;
  }

  public String getLanguage() {
    return language;
  }

  /** The installation's vocabulary list; {@code null} when none is stored. */
  public String getVocabulary() {
    return vocabulary;
  }

  /** The dialect of the live upstream; {@code null} while none is stored. */
  public RealtimeTranscriptionProtocol getRealtimeProtocol() {
    return realtimeProtocol;
  }

  public String getRealtimeUrl() {
    return realtimeUrl;
  }

  public String getRealtimeModel() {
    return realtimeModel;
  }

  String getRealtimeApiKeyCiphertext() {
    return realtimeApiKeyCiphertext;
  }

  /** The stored number of live sessions; {@code null} for the factory default. */
  public Integer getRealtimeMaxSessions() {
    return realtimeMaxSessions;
  }
}
