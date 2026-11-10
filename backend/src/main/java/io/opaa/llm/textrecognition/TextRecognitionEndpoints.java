package io.opaa.llm.textrecognition;

import io.opaa.instance.InvalidationBus;
import io.opaa.llm.LlmEncryptedColumns;
import io.opaa.llm.OptionalRoleEndpoints;
import io.opaa.security.SettingsEncryptor;
import org.springframework.stereotype.Component;

/**
 * The stored text recognition endpoint, cached until the trigger on {@code text_recognition_model}
 * announces a change.
 */
@Component
public class TextRecognitionEndpoints extends OptionalRoleEndpoints<TextRecognitionEndpoint> {

  /** The topic the trigger on {@code text_recognition_model} announces. */
  private static final String TOPIC = "text-recognition-model";

  private final TextRecognitionModelSettingsRepository rows;

  public TextRecognitionEndpoints(
      TextRecognitionModelSettingsRepository rows,
      SettingsEncryptor settingsEncryptor,
      InvalidationBus bus) {
    super(TOPIC, settingsEncryptor, bus);
    this.rows = rows;
  }

  @Override
  protected TextRecognitionEndpoint load() {
    return rows.findStored()
        .map(
            row ->
                new TextRecognitionEndpoint(
                    row.isEnabled(),
                    row.getConnectionType(),
                    row.getBaseUrl(),
                    row.getModelIdentifier(),
                    decrypt(
                        row.getApiKeyCiphertext(),
                        LlmEncryptedColumns.textRecognitionModelKey(),
                        "text recognition")))
        .orElse(TextRecognitionEndpoint.FACTORY_DEFAULT);
  }
}
