package io.opaa.llm.transcription;

import io.opaa.instance.InvalidationBus;
import io.opaa.llm.LlmEncryptedColumns;
import io.opaa.llm.OptionalRoleEndpoints;
import io.opaa.security.SettingsEncryptor;
import org.springframework.stereotype.Component;

/**
 * The stored transcription endpoint with its live upstream, cached until the trigger on {@code
 * transcription_model} announces a change.
 */
@Component
public class TranscriptionEndpoints extends OptionalRoleEndpoints<TranscriptionEndpoint> {

  /** The topic the trigger on {@code transcription_model} announces. */
  private static final String TOPIC = "transcription-model";

  private final TranscriptionModelSettingsRepository rows;

  public TranscriptionEndpoints(
      TranscriptionModelSettingsRepository rows,
      SettingsEncryptor settingsEncryptor,
      InvalidationBus bus) {
    super(TOPIC, "transcription_endpoint", settingsEncryptor, bus);
    this.rows = rows;
  }

  @Override
  protected TranscriptionEndpoint load() {
    return rows.findStored()
        .map(
            row -> {
              String key =
                  decrypt(
                      row.getApiKeyCiphertext(),
                      LlmEncryptedColumns.transcriptionModelKey(),
                      "transcription");
              return new TranscriptionEndpoint(
                  row.isEnabled(),
                  row.getBaseUrl(),
                  row.getModelIdentifier(),
                  key,
                  row.getLanguage(),
                  row.getVocabulary(),
                  realtimeOf(row, key));
            })
        .orElse(TranscriptionEndpoint.FACTORY_DEFAULT);
  }

  /**
   * The live upstream of {@code row}; without a key of its own it sends {@code batchKey}, but only
   * to the origin of the batch address ({@link RealtimeTranscriptionEndpoint#sharesOrigin}).
   */
  private RealtimeTranscriptionEndpoint realtimeOf(
      TranscriptionModelSettings row, String batchKey) {
    if (row.getRealtimeProtocol() == null) {
      return RealtimeTranscriptionEndpoint.NONE;
    }
    String key =
        row.getRealtimeApiKeyCiphertext() == null
            ? RealtimeTranscriptionEndpoint.sharesOrigin(row.getRealtimeUrl(), row.getBaseUrl())
                ? batchKey
                : ""
            : decrypt(
                row.getRealtimeApiKeyCiphertext(),
                LlmEncryptedColumns.transcriptionRealtimeKey(),
                "live transcription");
    return new RealtimeTranscriptionEndpoint(
        row.getRealtimeProtocol(),
        row.getRealtimeUrl(),
        row.getRealtimeModel(),
        key,
        row.getRealtimeMaxSessions() == null
            ? RealtimeTranscriptionEndpoint.DEFAULT_MAX_SESSIONS
            : row.getRealtimeMaxSessions());
  }
}
