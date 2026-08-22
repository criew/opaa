package io.opaa.llm;

import io.opaa.security.SettingsEncryptor;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Performs the one-time takeover of the existing environment configuration into {@code llm_models}
 * as the initial active model (#756,
 * docs/features/llm-integration.md#übergang-aus-der-heutigen-konfiguration): "Beim ersten Start
 * nach der Umstellung wird die vorhandene Konfiguration als initiales, aktives Modell übernommen,
 * sofern noch kein Modell hinterlegt ist."
 *
 * <p><b>Guarded by {@link LlmModelSeedMarker}, not by "is {@code llm_models} empty?"</b> (PR #763
 * review): the takeover must happen at most once, ever - not once per empty table. Inferring it
 * from emptiness would re-seed a stale environment configuration the moment a Systemverwaltung
 * deletes every managed model, which contradicts the same section's "Danach ist die Datenbank für
 * das Chat-Modell führend. Die Umgebungsvariablen werden nicht mehr ausgewertet." The marker is
 * written in the same transaction as the seeded model (or alone, if there was nothing to seed), so
 * "attempted" and "succeeded in creating a model" are recorded atomically together.
 *
 * <p>{@code spring.ai.model.chat} decides which of the two existing configuration blocks the
 * takeover reads from - it no longer decides a stored provider type (there is none, see {@link
 * LlmModel}'s own Javadoc), only where the one-time seed's values come from:
 *
 * <ul>
 *   <li>{@code ollama}: the Ollama base URL with a {@code /v1} suffix appended (unless already
 *       present) and no access key - Ollama's own OpenAI-compatible endpoint requires none.
 *   <li>{@code openai}: base URL, model and access key are taken over unchanged - except the
 *       bundled {@code sk-placeholder} default (no real key configured at all), which is treated as
 *       no key rather than encrypted and stored as if it were a real secret.
 * </ul>
 *
 * <p>Anything else (including a blank/unset value, which should not occur since {@code
 * application.yml} defaults {@code spring.ai.model.chat} to {@code ollama}) seeds no model but
 * still writes the marker - a takeover that found nothing to seed has still been attempted, and
 * must not be retried on the next start either.
 *
 * <p>{@link #seedIfNeeded()} is called from {@link LlmModelSeedRunner}, a separate bean, rather
 * than being an {@code ApplicationRunner} itself: {@code @Transactional} only takes effect on a
 * call that goes through this bean's Spring proxy, which requires the call to come from another
 * bean rather than from a method on {@code this}.
 */
@Component
class LlmModelSeeder {

  private static final Logger log = LoggerFactory.getLogger(LlmModelSeeder.class);

  static final String DEFAULT_DISPLAY_NAME = "Übernommen aus der Umgebungskonfiguration";
  static final BigDecimal DEFAULT_TEMPERATURE = new BigDecimal("0.70");
  static final int DEFAULT_MAX_TOKENS = 2000;

  /**
   * {@code application.yml}'s {@code spring.ai.openai.api-key} default - never a real key. A
   * deployment that left every OpenAI-related environment variable unset but happens to have {@code
   * spring.ai.model.chat=openai} (a self-contradictory but not-impossible configuration) must not
   * have this placeholder encrypted and stored as if it were an operator-supplied secret.
   */
  static final String OPENAI_API_KEY_PLACEHOLDER = "sk-placeholder";

  private static final String OLLAMA_PROVIDER = "ollama";
  private static final String OPENAI_PROVIDER = "openai";

  private final LlmModelRepository repository;
  private final LlmModelSeedMarkerRepository markerRepository;
  private final SettingsEncryptor settingsEncryptor;
  private final Environment environment;

  LlmModelSeeder(
      LlmModelRepository repository,
      LlmModelSeedMarkerRepository markerRepository,
      SettingsEncryptor settingsEncryptor,
      Environment environment) {
    this.repository = repository;
    this.markerRepository = markerRepository;
    this.settingsEncryptor = settingsEncryptor;
    this.environment = environment;
  }

  @Transactional
  void seedIfNeeded() {
    if (markerRepository.seedAlreadyAttempted()) {
      return;
    }
    String provider = environment.getProperty("spring.ai.model.chat", "");
    LlmModel seeded;
    if (OLLAMA_PROVIDER.equalsIgnoreCase(provider)) {
      seeded = seedFromOllama();
    } else if (OPENAI_PROVIDER.equalsIgnoreCase(provider)) {
      seeded = seedFromOpenAi();
    } else {
      log.warn(
          "Kein Chat-Modell hinterlegt: weder ein bestehendes Modell in llm_models noch eine"
              + " bekannte spring.ai.model.chat-Konfiguration (\"{}\") gefunden",
          provider);
      seeded = null;
    }
    if (seeded != null) {
      seeded.activate();
      repository.save(seeded);
      log.info(
          "Initiales Chat-Modell aus der Umgebungskonfiguration übernommen: {}",
          seeded.getDisplayName());
    }
    markerRepository.save(new LlmModelSeedMarker(Instant.now()));
  }

  private LlmModel seedFromOllama() {
    String baseUrl = environment.getProperty("spring.ai.ollama.base-url");
    if (!StringUtils.hasText(baseUrl)) {
      log.warn("Kein Chat-Modell hinterlegt: spring.ai.ollama.base-url ist nicht gesetzt");
      return null;
    }
    String model = environment.getProperty("spring.ai.ollama.chat.model", "");
    return new LlmModel(
        DEFAULT_DISPLAY_NAME,
        ensureV1Suffix(baseUrl),
        model,
        DEFAULT_TEMPERATURE,
        DEFAULT_MAX_TOKENS,
        null);
  }

  private LlmModel seedFromOpenAi() {
    String baseUrl = environment.getProperty("spring.ai.openai.chat.base-url");
    if (!StringUtils.hasText(baseUrl)) {
      log.warn("Kein Chat-Modell hinterlegt: spring.ai.openai.chat.base-url ist nicht gesetzt");
      return null;
    }
    String model = environment.getProperty("spring.ai.openai.chat.model", "");
    BigDecimal temperature =
        parseTemperature(environment.getProperty("spring.ai.openai.chat.temperature"));
    int maxTokens = parseMaxTokens(environment.getProperty("spring.ai.openai.chat.max-tokens"));
    String apiKey = environment.getProperty("spring.ai.openai.chat.api-key");
    boolean noRealKeyConfigured =
        !StringUtils.hasText(apiKey) || OPENAI_API_KEY_PLACEHOLDER.equals(apiKey);
    String apiKeyCiphertext = noRealKeyConfigured ? null : settingsEncryptor.encrypt(apiKey);
    return new LlmModel(
        DEFAULT_DISPLAY_NAME, baseUrl, model, temperature, maxTokens, apiKeyCiphertext);
  }

  private BigDecimal parseTemperature(String value) {
    if (!StringUtils.hasText(value)) {
      return DEFAULT_TEMPERATURE;
    }
    try {
      return new BigDecimal(value);
    } catch (NumberFormatException e) {
      return DEFAULT_TEMPERATURE;
    }
  }

  private int parseMaxTokens(String value) {
    if (!StringUtils.hasText(value)) {
      return DEFAULT_MAX_TOKENS;
    }
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException e) {
      return DEFAULT_MAX_TOKENS;
    }
  }

  /**
   * Appends {@code /v1} to an Ollama base URL unless it is already present
   * (docs/features/llm-integration.md#übergang-aus-der-heutigen-konfiguration: "ein bereits
   * vorhandenes /v1 wird nicht verdoppelt"). A trailing slash is normalised away first so {@code
   * http://ollama:11434/v1/} and {@code http://ollama:11434/v1} are recognised as the same case.
   */
  static String ensureV1Suffix(String baseUrl) {
    String trimmed = baseUrl.strip();
    String withoutTrailingSlash =
        trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    if (withoutTrailingSlash.toLowerCase(Locale.ROOT).endsWith("/v1")) {
      return withoutTrailingSlash;
    }
    return withoutTrailingSlash + "/v1";
  }
}
