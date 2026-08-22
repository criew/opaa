package io.opaa.llm;

import io.opaa.security.SettingsEncryptor;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
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
 * <p><b>Two takeover paths, not one</b> - {@code spring.ai.model.chat} alone no longer decides
 * which, because #762 removed the native Ollama starter and fixed that property to {@code openai}
 * unconditionally. Instead:
 *
 * <ul>
 *   <li><b>Legacy {@code OPAA_AI_CHAT_PROVIDER=ollama}</b> (read directly as a raw environment
 *       variable, not through any {@code application.yml} property - the {@code ollama} blocks that
 *       variable used to select are gone): the takeover of a Bestandsinstallation upgrading past
 *       #762 in one step, before the operator has removed the now-obsolete variable. Reads {@code
 *       OPAA_OLLAMA_BASE_URL}/{@code OPAA_OLLAMA_CHAT_MODEL} the same way it always has, with a
 *       {@code /v1} suffix appended to the base URL (unless already present) and no access key -
 *       Ollama's own OpenAI-compatible endpoint requires none. Falls back to the same
 *       profile-dependent default address the removed {@code ollama} configuration block used to
 *       carry (docker: the {@code ollama} service name; otherwise: {@code localhost}) if the
 *       variable itself was never set - see {@link #legacyOllamaBaseUrlDefault()}.
 *   <li><b>Everything else</b> (including a fresh installation that never knew {@code
 *       OPAA_AI_CHAT_PROVIDER} at all): takes over {@code spring.ai.openai.chat.*} unchanged - base
 *       URL, model and access key - except the bundled {@code sk-placeholder} default (no real key
 *       configured at all), which is treated as no key rather than encrypted and stored as if it
 *       were a real secret. Since #762 this path's own defaults already point at a locally operated
 *       Ollama server, so a fresh installation seeds the same values the legacy path above would
 *       have produced for an equivalent Bestandsinstallation.
 * </ul>
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

  /**
   * {@code application.yml}'s own default chat model since #762 - kept here too as the fallback for
   * the legacy takeover path, so a Bestandsinstallation that never overrode {@code
   * OPAA_OLLAMA_CHAT_MODEL} still seeds the same value it always effectively ran with.
   */
  static final String LEGACY_OLLAMA_CHAT_MODEL_DEFAULT = "phi3:mini";

  /**
   * Raw environment variable name (not a {@code spring.ai.*} property - #762 removed the {@code
   * application.yml} blocks that used to expose it as one) that selected the now-removed native
   * Ollama takeover path. Read directly via {@link Environment#getProperty(String)}, which resolves
   * both real OS environment variables and {@code -D} system properties by their exact name.
   */
  private static final String LEGACY_CHAT_PROVIDER_ENV = "OPAA_AI_CHAT_PROVIDER";

  private static final String LEGACY_OLLAMA_PROVIDER_VALUE = "ollama";
  private static final String LEGACY_OLLAMA_BASE_URL_ENV = "OPAA_OLLAMA_BASE_URL";
  private static final String LEGACY_OLLAMA_CHAT_MODEL_ENV = "OPAA_OLLAMA_CHAT_MODEL";
  private static final String DOCKER_PROFILE = "docker";

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
    String legacyChatProvider = environment.getProperty(LEGACY_CHAT_PROVIDER_ENV, "");
    LlmModel seeded =
        LEGACY_OLLAMA_PROVIDER_VALUE.equalsIgnoreCase(legacyChatProvider)
            ? seedFromLegacyOllamaEnv()
            : seedFromOpenAi();
    if (seeded != null) {
      seeded.activate();
      repository.save(seeded);
      log.info(
          "Initiales Chat-Modell aus der Umgebungskonfiguration übernommen: {}",
          seeded.getDisplayName());
    }
    markerRepository.save(new LlmModelSeedMarker(Instant.now()));
  }

  /**
   * Legacy takeover path for a Bestandsinstallation still carrying {@code
   * OPAA_AI_CHAT_PROVIDER=ollama} (#762 removed the {@code application.yml} blocks that variable
   * used to select, but not the variable's meaning for a deployment upgrading straight past it).
   * Reads the same two raw environment variables the removed {@code ollama} configuration block
   * used to expose as {@code spring.ai.ollama.*} properties.
   */
  private LlmModel seedFromLegacyOllamaEnv() {
    String baseUrl = environment.getProperty(LEGACY_OLLAMA_BASE_URL_ENV);
    if (!StringUtils.hasText(baseUrl)) {
      baseUrl = legacyOllamaBaseUrlDefault();
    }
    String model =
        environment.getProperty(LEGACY_OLLAMA_CHAT_MODEL_ENV, LEGACY_OLLAMA_CHAT_MODEL_DEFAULT);
    return new LlmModel(
        DEFAULT_DISPLAY_NAME,
        ensureV1Suffix(baseUrl),
        model,
        DEFAULT_TEMPERATURE,
        DEFAULT_MAX_TOKENS,
        null);
  }

  /**
   * The profile-dependent default the removed {@code ollama.base-url} configuration block used to
   * carry, reproduced here for the legacy takeover path so a Bestandsinstallation that never
   * overrode {@code OPAA_OLLAMA_BASE_URL} still seeds the same address it always effectively ran
   * with.
   */
  private String legacyOllamaBaseUrlDefault() {
    return environment.acceptsProfiles(Profiles.of(DOCKER_PROFILE))
        ? "http://ollama:11434"
        : "http://localhost:11434";
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
