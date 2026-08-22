package io.opaa.llm;

import io.opaa.security.SettingsEncryptor;
import java.math.BigDecimal;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Seeds the first managed chat model from the existing environment configuration (#756,
 * docs/features/llm-integration.md#übergang-aus-der-heutigen-konfiguration): "Beim ersten Start
 * nach der Umstellung wird die vorhandene Konfiguration als initiales, aktives Modell übernommen,
 * sofern noch kein Modell hinterlegt ist."
 *
 * <p>Runs once, at application startup, before any request can be served - the same shape {@link
 * io.opaa.library.UploadPendingRecoveryRunner} already uses for its own one-shot startup migration.
 * Only ever acts while {@code llm_models} is empty: a second start after the first seed sees a
 * non-empty table and does nothing, which is what keeps a restart from creating a second row or
 * overwriting whatever the Systemverwaltung has since configured through the future admin API
 * (#757).
 *
 * <p>{@code spring.ai.model.chat} decides which of the two existing configuration blocks the seed
 * reads from - it no longer decides a stored provider type (there is none, see {@link LlmModel}'s
 * own Javadoc), only where the one-time seed's values come from:
 *
 * <ul>
 *   <li>{@code ollama}: the Ollama base URL with a {@code /v1} suffix appended (unless already
 *       present) and no access key - Ollama's own OpenAI-compatible endpoint requires none.
 *   <li>{@code openai}: base URL, model and access key are taken over unchanged.
 * </ul>
 *
 * <p>Anything else (including a blank/unset value, which should not occur since {@code
 * application.yml} defaults {@code spring.ai.model.chat} to {@code ollama}) seeds nothing and logs
 * that no chat model is configured - the application still starts, per #756's own acceptance
 * criteria.
 */
@Component
public class LlmModelSeedRunner implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(LlmModelSeedRunner.class);

  static final String DEFAULT_DISPLAY_NAME = "Übernommen aus der Umgebungskonfiguration";
  static final BigDecimal DEFAULT_TEMPERATURE = new BigDecimal("0.70");
  static final int DEFAULT_MAX_TOKENS = 2000;

  private static final String OLLAMA_PROVIDER = "ollama";
  private static final String OPENAI_PROVIDER = "openai";

  private final LlmModelRepository repository;
  private final SettingsEncryptor settingsEncryptor;
  private final Environment environment;

  public LlmModelSeedRunner(
      LlmModelRepository repository, SettingsEncryptor settingsEncryptor, Environment environment) {
    this.repository = repository;
    this.settingsEncryptor = settingsEncryptor;
    this.environment = environment;
  }

  @Override
  public void run(ApplicationArguments args) {
    if (repository.count() > 0) {
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
      return;
    }
    if (seeded == null) {
      return;
    }
    seeded.activate();
    try {
      repository.save(seeded);
      log.info(
          "Initiales Chat-Modell aus der Umgebungskonfiguration übernommen: {}",
          seeded.getDisplayName());
    } catch (RuntimeException e) {
      // Multiple replicas can start at once and both see an empty table; the partial unique index
      // ux_llm_models_single_active (migration 058) lets only one of them win. The losing replica
      // logs and moves on rather than failing its own startup over a seed another instance already
      // performed.
      log.warn(
          "Initiales Chat-Modell konnte nicht gespeichert werden - vermutlich hat eine andere"
              + " Instanz die Übernahme bereits durchgeführt",
          e);
    }
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
    return new LlmModel(
        DEFAULT_DISPLAY_NAME,
        baseUrl,
        model,
        temperature,
        maxTokens,
        settingsEncryptor.encrypt(apiKey));
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
