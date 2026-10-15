package io.opaa.llm;

import static io.opaa.common.PropertyDefaults.positiveOr;

import io.opaa.compliance.DataImpact;
import io.opaa.compliance.NoDataImpact;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The transcription model role's configuration, built like {@link RerankProperties}: the switch
 * states the intent, the endpoint fields the how.
 *
 * @param enabled the explicit switch, {@code OPAA_TRANSCRIPTION_ENABLED}; default off.
 * @param baseUrl base address of an OpenAI-compatible endpoint, without {@code
 *     /audio/transcriptions}. An address carrying userinfo leaves the role unusable.
 * @param model the model identifier sent with every request.
 * @param apiKey optional bearer token; never logged and never part of a status.
 * @param language optional ISO-639-1 code passed on as {@code language}; blank lets the model
 *     guess.
 * @param maxAudioBytes the largest recording accepted, independent of the document upload limit.
 * @param timeout budget of one transcription call, the probe included.
 * @param maxConcurrent how many recordings the whole installation passes on at the same time; each
 *     one is held in memory until its call ends.
 */
@NoDataImpact("credentials, bounds and timeouts of the transcription role")
@ConfigurationProperties(prefix = "opaa.transcription")
public record TranscriptionProperties(
    @DefaultValue("false") @DataImpact boolean enabled,
    @DataImpact String baseUrl,
    @DataImpact String model,
    String apiKey,
    String language,
    @DefaultValue("" + DEFAULT_MAX_AUDIO_BYTES) long maxAudioBytes,
    @DefaultValue(DEFAULT_TIMEOUT_SECONDS + "s") @NoDataImpact("a timeout of one request")
        Duration timeout,
    @DefaultValue("" + DEFAULT_MAX_CONCURRENT) int maxConcurrent) {

  static final long DEFAULT_MAX_AUDIO_BYTES = 10L * 1024 * 1024;
  static final int DEFAULT_TIMEOUT_SECONDS = 120;
  static final int DEFAULT_MAX_CONCURRENT = 4;

  public TranscriptionProperties {
    baseUrl = baseUrl == null ? "" : baseUrl.strip();
    model = model == null ? "" : model.strip();
    apiKey = apiKey == null ? "" : apiKey.strip();
    language = language == null ? "" : language.strip();
    maxAudioBytes = positiveOr(maxAudioBytes, DEFAULT_MAX_AUDIO_BYTES);
    timeout = positiveOr(timeout, Duration.ofSeconds(DEFAULT_TIMEOUT_SECONDS));
    maxConcurrent = positiveOr(maxConcurrent, DEFAULT_MAX_CONCURRENT);
  }

  /** Whether both endpoint fields are present. */
  public boolean bound() {
    return !baseUrl.isEmpty() && !model.isEmpty();
  }

  /** Endpoint and model as far as they may be logged: never the key, never userinfo. */
  public String describeWithoutSecrets() {
    if (!bound()) {
      return "(no endpoint configured)";
    }
    return model + " @ " + safeBaseUrl();
  }

  String safeBaseUrl() {
    return ModelEndpointUri.containsCredentials(baseUrl)
        ? "(base URL with credentials - rejected)"
        : baseUrl;
  }

  /** Overridden so an accidental log of this record never prints the key or userinfo. */
  @Override
  public String toString() {
    return "TranscriptionProperties[enabled="
        + enabled
        + ", baseUrl="
        + safeBaseUrl()
        + ", model="
        + model
        + ", apiKey=***"
        + ", language="
        + language
        + ", maxAudioBytes="
        + maxAudioBytes
        + ", timeout="
        + timeout
        + ", maxConcurrent="
        + maxConcurrent
        + "]";
  }
}
