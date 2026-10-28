package io.opaa.installation;

import io.opaa.common.FeatureFlags;
import io.opaa.common.NotFoundException;
import io.opaa.common.TooManyRequestsException;
import io.opaa.common.ValidationException;
import io.opaa.llm.LlmModelDiscoverer;
import io.opaa.llm.ModelDiscovery;
import io.opaa.ratelimit.RateLimitService;
import io.opaa.security.SettingsEncryptor;
import org.springframework.stereotype.Service;

/**
 * The model list of a model step's endpoint through {@link LlmModelDiscoverer} (ADR-0060); nothing
 * is saved. The key saved with the step serves only its own endpoint ({@link
 * StepRules#sameEndpoint}). The budget is per instance (ADR-0053), on top of the API's own.
 */
@Service
public class InstallationModelDiscovery {

  static final int MAX_DISCOVERIES = 30;
  static final int WINDOW_SECONDS = 600;

  /** The code of a refusal by this budget, apart from the per-address budget of the API. */
  static final String LIMIT_CODE = "MODEL_DISCOVERY_LIMIT";

  private static final String LIMIT_MESSAGE =
      "Zu viele Modellabrufe. Bitte später erneut versuchen; die Kennung lässt sich von Hand"
          + " eintragen.";

  private static final String BUDGET_KEY = "installation";

  private final InstallationStateRepository states;
  private final InstallationStepRepository steps;
  private final StepRules rules;
  private final SettingsEncryptor settingsEncryptor;
  private final LlmModelDiscoverer discoverer;
  private final FeatureFlags features;
  private final RateLimitService budget = new RateLimitService(MAX_DISCOVERIES, WINDOW_SECONDS);

  InstallationModelDiscovery(
      InstallationStateRepository states,
      InstallationStepRepository steps,
      StepRules rules,
      SettingsEncryptor settingsEncryptor,
      LlmModelDiscoverer discoverer,
      FeatureFlags features) {
    this.states = states;
    this.steps = steps;
    this.rules = rules;
    this.settingsEncryptor = settingsEncryptor;
    this.discoverer = discoverer;
    this.features = features;
  }

  /**
   * Lists the models at {@code baseUrl}. {@code apiKey} {@code null} uses the key saved with the
   * step for the same endpoint, an empty one sends none.
   *
   * @throws ValidationException 400 for a step without model or not offered, an address with
   *     credentials, or a saved key that would have to leave its endpoint
   * @throws NotFoundException once the installation is completed
   * @throws TooManyRequestsException past the budget
   */
  public ModelDiscovery discover(InstallationStep step, String baseUrl, String apiKey) {
    if (step != InstallationStep.CHAT_MODEL
        && step != InstallationStep.EMBEDDING_MODEL
        && step != InstallationStep.TRANSCRIPTION_MODEL) {
      throw new ValidationException(
          "Modelle lassen sich nur in den Schritten Chat-Modell, Embedding-Modell und"
              + " Transkriptionsmodell abrufen.");
    }
    if (!step.isOfferedWith(features)) {
      throw new ValidationException(InstallationService.notOffered(step));
    }
    if (states.isCompleted()) {
      throw new NotFoundException("Die angeforderte Ressource wurde nicht gefunden");
    }
    String address = baseUrl == null ? "" : baseUrl.strip();
    String key = effectiveKey(step, address, apiKey);
    RateLimitService.Decision decision = budget.tryAcquire(BUDGET_KEY);
    if (!decision.allowed()) {
      throw new TooManyRequestsException(LIMIT_MESSAGE, decision.retryAfterSeconds(), LIMIT_CODE);
    }
    return discoverer.discover(address, key, null);
  }

  private String effectiveKey(InstallationStep step, String baseUrl, String apiKey) {
    if (apiKey != null) {
      return apiKey.isBlank() ? null : apiKey.strip();
    }
    InstallationStepRecord saved = steps.findById(step).orElse(null);
    if (saved == null || saved.getSecret() == null) {
      return null;
    }
    if (!StepRules.sameEndpoint(savedBaseUrl(step, saved.getPayload()), baseUrl)) {
      throw new ValidationException(
          "Die Basis-Adresse weicht von der gespeicherten ab. Der gespeicherte Zugangsschlüssel"
              + " gilt nur für sie: Bitte den Schlüssel erneut eingeben oder die gespeicherte"
              + " Adresse verwenden.");
    }
    try {
      return settingsEncryptor.decrypt(saved.getSecret());
    } catch (IllegalStateException e) {
      throw new ValidationException(
          "Der gespeicherte Zugangsschlüssel ist nicht lesbar; bitte den Schlüssel erneut"
              + " eingeben.");
    }
  }

  private String savedBaseUrl(InstallationStep step, String payload) {
    return switch (step) {
      case CHAT_MODEL -> rules.chatModelPayload(payload).baseUrl();
      case TRANSCRIPTION_MODEL -> rules.transcriptionModelPayload(payload).baseUrl();
      default -> rules.embeddingModelPayload(payload).baseUrl();
    };
  }
}
