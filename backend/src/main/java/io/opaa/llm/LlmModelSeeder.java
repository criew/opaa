package io.opaa.llm;

import io.opaa.security.SettingsEncryptor;
import java.math.BigDecimal;
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
 *   <li><b>Legacy {@code OPAA_AI_CHAT_PROVIDER=ollama}, together with at least one of {@code
 *       OPAA_OLLAMA_BASE_URL}/{@code OPAA_OLLAMA_CHAT_MODEL}</b> (all read directly as raw
 *       environment variables, not through any {@code application.yml} property - the {@code
 *       ollama} blocks that variable used to select are gone): the takeover of an existing
 *       installation upgrading past #762 in one step, before the operator has removed the
 *       now-obsolete variables. The second condition matters (PR #766 review, Befund 5): {@code
 *       OPAA_AI_CHAT_PROVIDER=ollama} left over on its own, with neither Ollama variable still set,
 *       must not divert a genuinely fresh/openai-configured installation onto this path and seed it
 *       with the wrong (Ollama-shaped) defaults - see {@link #seedIfNeeded()}. Reads {@code
 *       OPAA_OLLAMA_BASE_URL}/{@code OPAA_OLLAMA_CHAT_MODEL} the same way it always has, with a
 *       {@code /v1} suffix appended to the base URL (unless already present) and no access key -
 *       Ollama's own OpenAI-compatible endpoint requires none. Falls back to the same
 *       profile-dependent default address the removed {@code ollama} configuration block used to
 *       carry (docker: the {@code ollama} service name; otherwise: {@code localhost}) if the base
 *       URL variable itself was never set - see {@link #legacyOllamaBaseUrlDefault()}.
 *   <li><b>Everything else</b> (including a fresh installation that never knew {@code
 *       OPAA_AI_CHAT_PROVIDER} at all): takes over {@code spring.ai.openai.chat.*} unchanged - base
 *       URL, model and access key - except the bundled {@code sk-placeholder} default (no real key
 *       configured at all), which is treated as no key rather than encrypted and stored as if it
 *       were a real secret. Since #762 this path's own defaults already point at a locally operated
 *       Ollama server, so a fresh installation seeds the same values the legacy path above would
 *       have produced for an equivalent existing installation.
 * </ul>
 *
 * <p><b>This class only ever seeds the chat model.</b> There is no analogous takeover for the
 * embedding configuration - embedding models are not managed objects yet (unlike the chat model
 * since Stufe 1 of the model management), so an existing installation upgrading past #762 with a
 * non-default embedding address (e.g. {@code OPAA_OLLAMA_BASE_URL} pointing at a host-run Ollama
 * server rather than the new default's {@code ollama} Compose service name) must translate that
 * value into {@code OPAA_OPENAI_EMBEDDING_BASE_URL} itself, in the environment, before the update -
 * see docs/handbuch/deployment.md's own migration note for #762.
 *
 * <p>{@link #seedIfNeeded()} is called from {@link LlmModelSeedRunner}, a separate bean, rather
 * than being an {@code ApplicationRunner} itself: {@code @Transactional} only takes effect on a
 * call that goes through this bean's Spring proxy, which requires the call to come from another
 * bean rather than from a method on {@code this}.
 *
 * <p><b>Ablaufdatum:</b> einmalige Übernahme für Bestandsinstallationen, Kandidat zur Entfernung ab
 * v1.0.
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
   * the legacy takeover path, so an existing installation that never overrode {@code
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

  private static final String LEGACY_EMBEDDING_PROVIDER_ENV = "OPAA_AI_EMBEDDING_PROVIDER";

  private static final String LEGACY_OLLAMA_PROVIDER_VALUE = "ollama";
  private static final String LEGACY_OLLAMA_BASE_URL_ENV = "OPAA_OLLAMA_BASE_URL";
  private static final String LEGACY_OLLAMA_CHAT_MODEL_ENV = "OPAA_OLLAMA_CHAT_MODEL";
  private static final String LEGACY_OLLAMA_EMBEDDING_MODEL_ENV = "OPAA_OLLAMA_EMBEDDING_MODEL";
  private static final String DOCKER_PROFILE = "docker";

  /**
   * Every environment variable #762 removed from {@code application.yml}. Not all of them still
   * have a meaning to this class (only {@link #LEGACY_CHAT_PROVIDER_ENV}/{@link
   * #LEGACY_OLLAMA_BASE_URL_ENV}/{@link #LEGACY_OLLAMA_CHAT_MODEL_ENV} do, for the chat takeover
   * path), but a value left over in any of them is equally silent otherwise - {@link
   * #warnAboutLeftoverLegacyVariables()} surfaces all five so an operator notices before assuming
   * they still do something (PR #766 review, Befund 6).
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
    warnAboutLeftoverLegacyVariables();
    if (markerRepository.seedAlreadyAttempted()) {
      return;
    }
    if (repository.count() > 0) {
      // #771 review, Befund 1: a marker-less skip (below, on a missing/invalid encryption key)
      // is retried on every subsequent start until it either succeeds or the operator adds a
      // model by hand in the meantime - the very fallback this class's own ERROR log recommends.
      // Without this check, that hand-added model would collide with the retried takeover the
      // next time it runs: ux_llm_models_single_active (migration 058) if the seeded model were
      // activated too, or a silent second row - either way, a taken-over environment
      // configuration the operator never asked for once the key finally is set. The marker
      // still is the primary guard (PR #763 review) for the ordinary case; this is only reached
      // when it was never written in the first place.
      log.info(
          "Übernahme aus der Umgebungskonfiguration entfällt: Es sind bereits Chat-Modelle"
              + " hinterlegt (vermutlich manuell angelegt, nachdem eine frühere Übernahme mangels"
              + " OPAA_SETTINGS_ENCRYPTION_KEY übersprungen wurde). Seed-Marker wird nachträglich"
              + " gesetzt.");
      markerRepository.save(new LlmModelSeedMarker(Instant.now()));
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
              + " anlegen. Siehe docs/handbuch/deployment.md. Es wurde kein Seed-Marker geschrieben.",
          e.getCause().getMessage());
      return;
    }
    if (seeded != null && ModelEndpointUri.containsCredentials(seeded.getBaseUrl())) {
      // #1147: this class writes to the repository directly, so LlmModelService's own rejection
      // does not cover it. Skipped without a marker, like the missing-encryption-key case above -
      // a corrected environment variable is taken over on the next start. The address itself is
      // not logged; naming it would put the credentials into the very log this rule protects.
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
    markerRepository.save(new LlmModelSeedMarker(Instant.now()));
  }

  /**
   * {@code OPAA_AI_CHAT_PROVIDER=ollama} alone is not enough (PR #766 review, Befund 5): left over
   * on its own - both {@code OPAA_OLLAMA_*} variables already removed - it must not divert a
   * takeover that should read {@code spring.ai.openai.chat.*} instead onto the Ollama-shaped
   * defaults of {@link #seedFromLegacyOllamaEnv()}. At least one of the two Ollama variables still
   * being set is what actually distinguishes "this is an existing installation upgrading past #762
   * in one step" from "this leftover variable has no bearing on the current configuration anymore".
   */
  private boolean legacyOllamaTakeoverApplies() {
    String legacyChatProvider = environment.getProperty(LEGACY_CHAT_PROVIDER_ENV, "");
    if (!LEGACY_OLLAMA_PROVIDER_VALUE.equalsIgnoreCase(legacyChatProvider)) {
      return false;
    }
    return StringUtils.hasText(environment.getProperty(LEGACY_OLLAMA_BASE_URL_ENV))
        || StringUtils.hasText(environment.getProperty(LEGACY_OLLAMA_CHAT_MODEL_ENV));
  }

  /**
   * Surfaces every environment variable #762 removed, so an operator who left one set (harmlessly
   * ignored otherwise, see this class's own Javadoc) notices instead of assuming it still has an
   * effect (PR #766 review, Befund 6). Runs on every start, independent of {@link
   * LlmModelSeedMarker} - the leftover variable itself does not stop existing once the one-time
   * takeover has happened.
   */
  private void warnAboutLeftoverLegacyVariables() {
    List<String> stillSet =
        LEGACY_ENVIRONMENT_VARIABLES.stream()
            .filter(name -> StringUtils.hasText(environment.getProperty(name)))
            .toList();
    if (!stillSet.isEmpty()) {
      log.warn(
          "Folgende Umgebungsvariablen sind seit #762 ohne Wirkung, aber noch gesetzt: {}. Siehe"
              + " docs/handbuch/deployment.md, Abschnitt \"LLM-Anbieter\".",
          stillSet);
    }
  }

  /**
   * Legacy takeover path for an existing installation still carrying {@code
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
   * carry, reproduced here for the legacy takeover path so an existing installation that never
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
        // #771 review, Befund "Sollte" 1: not just a missing key (SettingsEncryptor#requireKey()
        // throws the same IllegalStateException for a set-but-malformed one - wrong Base64,
        // wrong length) - both are the same "Übernahme scheitert kontrolliert" category the
        // deployment docs promise, not a reason to abort startup.
        throw new MissingEncryptionKeyException(e);
      }
    }
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

  /**
   * Thrown by {@link #seedFromOpenAi()} when a configured API key would need to be encrypted but
   * {@code OPAA_SETTINGS_ENCRYPTION_KEY} is missing, invalid Base64 or the wrong length (#771,
   * every case {@link io.opaa.security.SettingsEncryptor#encrypt} itself reports as an {@link
   * IllegalStateException}). A configuration problem, not a fatal one: caught within {@link
   * #seedIfNeeded()} itself, before it ever reaches {@link LlmModelSeedRunner}, so this one-time
   * takeover is simply skipped for this start - no {@link LlmModelSeedMarker} is written, so it is
   * retried automatically on every subsequent start until the operator fixes the key (or the model
   * is added by hand through the Verwaltungsoberfläche in the meantime, at which point {@link
   * #seedIfNeeded()}'s own {@code repository.count() > 0} check takes over instead).
   */
  private static final class MissingEncryptionKeyException extends RuntimeException {
    MissingEncryptionKeyException(IllegalStateException cause) {
      super(cause);
    }
  }
}
