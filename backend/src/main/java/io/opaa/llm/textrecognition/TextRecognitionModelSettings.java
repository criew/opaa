package io.opaa.llm.textrecognition;

import io.opaa.api.types.TextRecognitionConnectionType;
import io.opaa.llm.OptionalRoleSettings;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * The single row of {@code text_recognition_model}, built like {@code TranscriptionModelSettings};
 * {@link #modelIdentifier} is {@code null} for {@link TextRecognitionConnectionType#DOCLING_SERVE}.
 */
@Entity
@Table(name = "text_recognition_model")
public class TextRecognitionModelSettings extends OptionalRoleSettings {

  @Enumerated(EnumType.STRING)
  @Column(name = "connection_type", nullable = false, length = 32)
  private TextRecognitionConnectionType connectionType;

  protected TextRecognitionModelSettings() {}

  public TextRecognitionModelSettings(Instant now) {
    super(now);
    this.connectionType = TextRecognitionEndpoint.DEFAULT_CONNECTION_TYPE;
  }

  /** Replaces the column of the text recognition role; the endpoint goes through the superclass. */
  public void replaceOwn(TextRecognitionConnectionType connectionType) {
    this.connectionType = connectionType;
  }

  public TextRecognitionConnectionType getConnectionType() {
    return connectionType;
  }
}
