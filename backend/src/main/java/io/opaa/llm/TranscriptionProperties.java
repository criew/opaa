package io.opaa.llm;

import static io.opaa.common.PropertyDefaults.positiveOr;

import io.opaa.compliance.NoDataImpact;
import io.opaa.compliance.PerInstance;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The load limits of the transcription model role. Switch, address, model, key and language are the
 * administration's ({@link TranscriptionEndpoint}, ADR-0060, Entscheidung 10).
 *
 * @param maxAudioBytes the largest recording accepted, independent of the document upload limit.
 * @param timeout budget of one transcription call, the probe included.
 * @param maxConcurrent how many recordings the whole installation passes on at the same time; each
 *     one is held in memory until its call ends.
 */
@PerInstance("limits and timeouts of this process")
@NoDataImpact("bounds and timeouts of the transcription role")
@ConfigurationProperties(prefix = "opaa.transcription")
public record TranscriptionProperties(
    @DefaultValue("" + DEFAULT_MAX_AUDIO_BYTES) long maxAudioBytes,
    @DefaultValue(DEFAULT_TIMEOUT_SECONDS + "s") @NoDataImpact("a timeout of one request")
        Duration timeout,
    @DefaultValue("" + DEFAULT_MAX_CONCURRENT) int maxConcurrent) {

  static final long DEFAULT_MAX_AUDIO_BYTES = 10L * 1024 * 1024;
  static final int DEFAULT_TIMEOUT_SECONDS = 120;
  static final int DEFAULT_MAX_CONCURRENT = 4;

  public TranscriptionProperties {
    maxAudioBytes = positiveOr(maxAudioBytes, DEFAULT_MAX_AUDIO_BYTES);
    timeout = positiveOr(timeout, Duration.ofSeconds(DEFAULT_TIMEOUT_SECONDS));
    maxConcurrent = positiveOr(maxConcurrent, DEFAULT_MAX_CONCURRENT);
  }
}
