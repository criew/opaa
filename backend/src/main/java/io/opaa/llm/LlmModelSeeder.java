package io.opaa.llm;

import io.opaa.security.SettingsEncryptor;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Takes over the environment configuration once as the initial active chat model, guarded by {@link
 * LlmModelSeedMarker} (same transaction), not by an empty {@code llm_models}: deleting every model
 * never re-seeds. Source is the legacy Ollama variables (see {@link
 * #legacyOllamaTakeoverApplies()}), otherwise {@code spring.ai.openai.chat.*}.
 *
 * <p><b>Ablaufdatum:</b> einmalige Übernahme für Bestandsinstallationen, Kandidat zur Entfernung ab
 * v1.0.
 */
@Component
class LlmModelSeeder {

  private static final Logger log = LoggerFactory.getLogger(LlmModelSeeder.class);

  static final String DEFAULT_DISPLAY_NAME = "Übernommen aus der Umgebungskonfiguration";

  /** The temperature the legacy Ollama configuration always ran with. */
  static final BigDecimal LEGACY_OLLAMA_TEMPERATURE = new BigDecimal("0.70");

  static final int DEFAULT_MAX_TOKENS = 8000;

  /**
   * {@code application.yml}'s {@code spring.ai.openai.api-key} default - never a real key. A
   * deployment that left every OpenAI-related environment variable unset but happens to have {@code
   * spring.ai.model.chat=openai} (a self-contradictory but not-impossible configuration) must not
   * have this placeholder encrypted and stored as if it were an operator-supplied secret.
   */
  static final String OPENAI_API_KEY_PLACEHOLDER = "sk-placeholder";

  /**
   * {@code application.yml}'s own default chat model - kept here too as the fallback for the legacy
   * takeover path, so an existing installation that never overrode {@code OPAA_OLLAMA_CHAT_MODEL}
   * still seeds the same value it always effectively ran with.
   */
  static final String LEGACY_OLLAMA_CHAT_MODEL_DEFAULT = "phi3:mini";

  /**
   * Raw environment variable name, no {@code spring.ai.*} property; {@link
   * Environment#getProperty(String)} resolves OS variables and {@code -D} properties by exact name.
   */
  private static final String LEGACY_CHAT_PROVIDER_ENV = "OPAA_AI_CHAT_PROVIDER";

  private static final String LEGACY_EMBEDDING_PROVIDER_ENV = "OPAA_AI_EMBEDDING_PROVIDER";

  private static final String LEGACY_OLLAMA_PROVIDER_VALUE = "ollama";
  private static final String LEGACY_OLLAMA_BASE_URL_ENV = "OPAA_OLLAMA_BASE_URL";
  private static final String LEGACY_OLLAMA_CHAT_MODEL_ENV = "OPAA_OLLAMA_CHAT_MODEL";
  private static final String LEGACY_OLLAMA_EMBEDDING_MODEL_ENV = "OPAA_OLLAMA_EMBEDDING_MODEL";
  private static final String DOCKER_PROFILE = "docker";

  /**
   * Every environment variable that no longer configures anything; {@link
   * #warnAboutLeftoverLegacyVariables()} reports each one still set.
   */
  private static final List<String> LEGACY_ENVIRONMENT_VARIABLES =
      List.of(
          LEGACY_CHAT_PROVIDER_ENV,
          LEGACY_EMBEDDING_PROVIDER_ENV,
          LEGACY_OLLAMA_BASE_URL_ENV,
          LEGACY_OLLAMA_CHAT_MODEL_ENV,
          LEGACY_OLLAMA_EMBEDDING_MODEL_ENV);

  private final LlmModelRepository repository;
  private final LlmModelSeedMarkerRepository markerRepository;
  private final SettingsEncryptor settingsEncryptor;
  private final Environment environment;
  private final Clock clock;

  LlmModelSeeder(
      LlmModelRepository repository,
      LlmModelSeedMarkerRepository markerRepository,
      SettingsEncryptor settingsEncryptor,
      Environment environment,
      Clock clock) {
    this.repository = repository;
    this.markerRepository = markerRepository;
    this.settingsEncryptor = settingsEncryptor;
    this.environment = environment;
    this.clock = clock;
  }

  @Transactional
  void seedIfNeeded() {
    warnAboutLeftoverLegacyVariables();
    markerRepository.lockStartupSeeds();
    if (markerRepository.seedAlreadyAttempted()) {
      return;
    }
    if (repository.count() > 0) {
      // Reached only after a marker-less skip (missing encryption key): a model added by hand in
      // the meantime ends the takeover instead of colliding with a retried one.
      log.info(
          "Übernahme aus der Umgebungskonfiguration entfällt: Es sind bereits Chat-Modelle"
              + " hinterlegt (vermutlich manuell angelegt, nachdem eine frühere Übernahme mangels"
              + " OPAA_SETTINGS_ENCRYPTION_KEY übersprungen wurde). Seed-Marker wird nachträglich"
              + " gesetzt.");
      markerRepository.save(new LlmModelSeedMarker(Instant.now(clock)));
      return;
    }
    LlmModel seeded;
    try {
      seeded = legacyOllamaTakeoverApplies() ? seedFromLegacyOllamaEnv() : seedFromOpenAi();
    } catch (MissingEncryptionKeyException e) {
      log.error(
          "Initiales Chat-Modell konnte nicht aus der Umgebungskonfiguration übernommen werden:"
              + " {} Variable setzen und neu starten, damit die Übernahme nachgeholt wird -"
              + " alternativ das Modell ohne Zugangsschlüssel über die Verwaltungsoberfläche"
              + " anlegen. Siehe docs/handbuch/modelle.md. Es wurde kein Seed-Marker geschrieben.",
          e.getCause().getMessage());
      return;
    }
    if (seeded != null && ModelEndpointUri.containsCredentials(seeded.getBaseUrl())) {
      // This class writes to the repository directly, so LlmModelService's own rejection does not
      // cover it. Skipped without a marker, like the missing-encryption-key case above - a
      // corrected environment variable is taken over on the next start. The address itself is not
      // logged; naming it would put the credentials into the very log this rule protects.
      log.error(
          "Initiales Chat-Modell konnte nicht aus der Umgebungskonfiguration übernommen werden:"
              + " Die konfigurierte Basis-Adresse enthält Anmeldedaten (Form"
              + " \"https://benutzer:passwort@host\"). Adresse ohne Anmeldedaten setzen und den"
              + " Zugangsschlüssel über OPAA_OPENAI_CHAT_API_KEY hinterlegen, dann neu starten."
              + " Es wurde kein Seed-Marker geschrieben.");
      return;
    }
    if (seeded != null) {
      seeded.activate();
      repository.save(seeded);
      log.info(
          "Initiales Chat-Modell aus der Umgebungskonfiguration übernommen: {}",
          seeded.getDisplayName());
    }
    markerRepository.save(new LlmModelSeedMarker(Instant.now(clock)));
  }

  /**
   * True for {@code OPAA_AI_CHAT_PROVIDER=ollama} together with at least one {@code OPAA_OLLAMA_*}
   * variable; the provider variable left over alone does not divert the takeover onto the Ollama
   * defaults.
   */
  private boolean legacyOllamaTakeoverApplies() {
    String legacyChatProvider = environment.getProperty(LEGACY_CHAT_PROVIDER_ENV, "");
    if (!LEGACY_OLLAMA_PROVIDER_VALUE.equalsIgnoreCase(legacyChatProvider)) {
      return false;
    }
    return StringUtils.hasText(environment.getProperty(LEGACY_OLLAMA_BASE_URL_ENV))
        || StringUtils.hasText(environment.getProperty(LEGACY_OLLAMA_CHAT_MODEL_ENV));
  }

  /** Warns on every start, independent of {@link LlmModelSeedMarker}, about leftover variables. */
  private void warnAboutLeftoverLegacyVariables() {
    List<String> stillSet =
        LEGACY_ENVIRONMENT_VARIABLES.stream()
            .filter(name -> StringUtils.hasText(environment.getProperty(name)))
            .toList();
    if (!stillSet.isEmpty()) {
      log.warn(
          "Folgende Umgebungsvariablen sind seit #762 ohne Wirkung, aber noch gesetzt: {}. Siehe"
              + " docs/handbuch/konfiguration.md, Abschnitt \"Entfallene und veraltete Variablen\".",
          stillSet);
    }
  }

  /**
   * Reads {@code OPAA_OLLAMA_BASE_URL} and {@code OPAA_OLLAMA_CHAT_MODEL} raw, appends {@code /v1}
   * and stores no access key: Ollama's OpenAI-compatible endpoint requires none.
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
        LEGACY_OLLAMA_TEMPERATURE,
        DEFAULT_MAX_TOKENS,
        null);
  }

  /** Profile-dependent Ollama address for an unset {@code OPAA_OLLAMA_BASE_URL}. */
  private String legacyOllamaBaseUrlDefault() {
    return environment.acceptsProfiles(Profiles.of(DOCKER_PROFILE))
        ? "http://ollama:11434"
        : "http://localhost:11434";
  }

  private LlmModel seedFromOpenAi() {
    String baseUrl = environment.getProperty("spring.ai.openai.chat.base-url");
    if (!StringUtils.hasText(baseUrl)) {
      log.warn("No chat model configured: spring.ai.openai.chat.base-url is not set");
      return null;
    }
    String model = environment.getProperty("spring.ai.openai.chat.model", "");
    BigDecimal temperature =
        parseTemperature(environment.getProperty("spring.ai.openai.chat.temperature"));
    int maxTokens = parseMaxTokens(environment.getProperty("spring.ai.openai.chat.max-tokens"));
    String apiKey = environment.getProperty("spring.ai.openai.chat.api-key");
    boolean noRealKeyConfigured =
        !StringUtils.hasText(apiKey) || OPENAI_API_KEY_PLACEHOLDER.equals(apiKey);
    String apiKeyCiphertext = null;
    if (!noRealKeyConfigured) {
      try {
        apiKeyCiphertext = settingsEncryptor.encrypt(apiKey);
      } catch (IllegalStateException e) {
        // A missing and a malformed key both throw IllegalStateException; either skips the
        // takeover instead of aborting startup.
        throw new MissingEncryptionKeyException(e);
      }
    }
    return new LlmModel(
        DEFAULT_DISPLAY_NAME, baseUrl, model, temperature, maxTokens, apiKeyCiphertext);
  }

  /** Unset or unreadable means no temperature: the model's own default applies. */
  private BigDecimal parseTemperature(String value) {
    if (!StringUtils.hasText(value)) {
      return null;
    }
    try {
      return new BigDecimal(value);
    } catch (NumberFormatException e) {
      log.warn(
          "OPAA_OPENAI_CHAT_TEMPERATURE='{}' is not a number (decimal point, not comma) - the"
              + " seeded chat model gets no temperature",
          value);
      return null;
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

  /**
   * A configured API key cannot be encrypted because {@code OPAA_SETTINGS_ENCRYPTION_KEY} is
   * missing or malformed. Caught in {@link #seedIfNeeded()}: no marker is written, so the takeover
   * is retried on every start until the key is fixed or a model is added by hand.
   */
  private static final class MissingEncryptionKeyException extends RuntimeException {
    MissingEncryptionKeyException(IllegalStateException cause) {
      super(cause);
    }
  }
}
