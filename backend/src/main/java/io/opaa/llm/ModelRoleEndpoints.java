package io.opaa.llm;

import com.github.benmanes.caffeine.cache.Caffeine;
import io.opaa.instance.BusBoundCache;
import io.opaa.instance.InvalidationBus;
import io.opaa.instance.InvalidationSubscriber;
import io.opaa.security.CipherBinding;
import io.opaa.security.SettingsEncryptor;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The stored rerank, transcription and text recognition endpoints (ADR-0060, Entscheidung 10), each
 * the factory default while its row is missing. Read on every query and availability check, so all
 * are cached; the triggers on {@code rerank_model}, {@code transcription_model} and {@code
 * text_recognition_model} drop the cache on every instance through the {@link InvalidationBus}
 * (ADR-0053, Entscheidung 5).
 *
 * <p>A stored key that cannot be decrypted is passed on as no key and logged: the endpoint then
 * refuses the call, which the role reports as its state.
 */
@Component
public class ModelRoleEndpoints implements InvalidationSubscriber {

  /** The topic the trigger on {@code rerank_model} announces. */
  public static final String RERANK_TOPIC = "rerank-model";

  /** The topic the trigger on {@code transcription_model} announces. */
  public static final String TRANSCRIPTION_TOPIC = "transcription-model";

  /** The topic the trigger on {@code text_recognition_model} announces. */
  public static final String TEXT_RECOGNITION_TOPIC = "text-recognition-model";

  private static final Logger log = LoggerFactory.getLogger(ModelRoleEndpoints.class);

  private final RerankModelSettingsRepository rerankRows;
  private final TranscriptionModelSettingsRepository transcriptionRows;
  private final TextRecognitionModelSettingsRepository textRecognitionRows;
  private final SettingsEncryptor settingsEncryptor;
  private final BusBoundCache<Boolean, RerankEndpoint> rerank;
  private final BusBoundCache<Boolean, TranscriptionEndpoint> transcription;
  private final BusBoundCache<Boolean, TextRecognitionEndpoint> textRecognition;

  public ModelRoleEndpoints(
      RerankModelSettingsRepository rerankRows,
      TranscriptionModelSettingsRepository transcriptionRows,
      TextRecognitionModelSettingsRepository textRecognitionRows,
      SettingsEncryptor settingsEncryptor,
      InvalidationBus bus) {
    this.rerankRows = rerankRows;
    this.transcriptionRows = transcriptionRows;
    this.textRecognitionRows = textRecognitionRows;
    this.settingsEncryptor = settingsEncryptor;
    this.rerank = new BusBoundCache<>(Caffeine.newBuilder().maximumSize(1).build(), bus);
    this.transcription = new BusBoundCache<>(Caffeine.newBuilder().maximumSize(1).build(), bus);
    this.textRecognition = new BusBoundCache<>(Caffeine.newBuilder().maximumSize(1).build(), bus);
  }

  /** The rerank endpoint in force; never blocks on the network. */
  public RerankEndpoint rerank() {
    return rerank.get(Boolean.TRUE, key -> loadRerank());
  }

  /** The transcription endpoint in force; never blocks on the network. */
  public TranscriptionEndpoint transcription() {
    return transcription.get(Boolean.TRUE, key -> loadTranscription());
  }

  /** The text recognition endpoint in force; never blocks on the network. */
  public TextRecognitionEndpoint textRecognition() {
    return textRecognition.get(Boolean.TRUE, key -> loadTextRecognition());
  }

  @Override
  public Set<String> invalidationTopics() {
    return Set.of(RERANK_TOPIC, TRANSCRIPTION_TOPIC, TEXT_RECOGNITION_TOPIC);
  }

  @Override
  public void invalidateAll() {
    rerank.invalidateAll();
    transcription.invalidateAll();
    textRecognition.invalidateAll();
  }

  private RerankEndpoint loadRerank() {
    return rerankRows
        .findStored()
        .map(
            row ->
                new RerankEndpoint(
                    row.isEnabled(),
                    row.getBaseUrl(),
                    row.getModelIdentifier(),
                    decrypt(
                        row.getApiKeyCiphertext(), LlmEncryptedColumns.rerankModelKey(), "rerank")))
        .orElse(RerankEndpoint.FACTORY_DEFAULT);
  }

  private TranscriptionEndpoint loadTranscription() {
    return transcriptionRows
        .findStored()
        .map(
            row -> {
              String key =
                  decrypt(
                      row.getApiKeyCiphertext(),
                      LlmEncryptedColumns.TRANSCRIPTION_MODEL_KEY.row(
                          TranscriptionModelSettings.SINGLETON_ID),
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

  private TextRecognitionEndpoint loadTextRecognition() {
    return textRecognitionRows
        .findStored()
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

  private String decrypt(String ciphertext, CipherBinding binding, String role) {
    if (ciphertext == null) {
      return "";
    }
    try {
      return settingsEncryptor.decrypt(ciphertext, binding);
    } catch (IllegalStateException e) {
      log.error(
          "The stored key of the {} model cannot be decrypted ({}); calls go without a key until"
              + " OPAA_SETTINGS_ENCRYPTION_KEY matches or the key is entered again",
          role,
          e.getClass().getSimpleName());
      return "";
    }
  }
}
