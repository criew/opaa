package io.opaa.installation;

import io.opaa.auth.OidcProviderConnectionTester;
import io.opaa.llm.EmbeddingModelService;
import io.opaa.llm.LlmModelConnectionTester;
import io.opaa.security.SettingsEncryptor;
import io.opaa.security.TargetAddressValidator;
import org.springframework.stereotype.Component;

/**
 * The check of each saved step (ADR-0060, Entscheidung 6): the same connection tests the
 * administration runs for an identity provider and a chat model, and a probe embedding for the
 * embedding model, which also yields its vector size (Entscheidung 10). A model address is checked
 * against the target address check again right before the probe. The outcome is German and
 * user-facing; a failed check is a result, never an exception.
 */
@Component
class StepChecks {

  /**
   * The outcome of one check; {@code dimensions} is the vector size of a successful probe
   * embedding, else {@code null}.
   */
  record Outcome(boolean succeeded, String message, Integer dimensions) {

    Outcome(boolean succeeded, String message) {
      this(succeeded, message, null);
    }
  }

  private static final String UNREADABLE_KEY =
      "Der gespeicherte Zugangsschlüssel ist nicht lesbar; bitte den Schlüssel erneut eingeben.";

  private final StepRules rules;
  private final OidcProviderConnectionTester oidcTester;
  private final LlmModelConnectionTester chatTester;
  private final SettingsEncryptor settingsEncryptor;
  private final TargetAddressValidator modelAddresses;
  private final EmbeddingModelService embeddingModels;

  StepChecks(
      StepRules rules,
      OidcProviderConnectionTester oidcTester,
      LlmModelConnectionTester chatTester,
      SettingsEncryptor settingsEncryptor,
      TargetAddressValidator modelAddresses,
      EmbeddingModelService embeddingModels) {
    this.rules = rules;
    this.oidcTester = oidcTester;
    this.chatTester = chatTester;
    this.settingsEncryptor = settingsEncryptor;
    this.modelAddresses = modelAddresses;
    this.embeddingModels = embeddingModels;
  }

  Outcome check(InstallationStep step, String payload, String secret) {
    return switch (step) {
      case FIRST_ADMIN ->
          secret == null
              ? new Outcome(false, "Das Passwort des ersten Kontos fehlt.")
              : new Outcome(true, "Das erste Konto erfüllt die Regeln.");
      case SIGN_IN -> signIn(rules.signInPayload(payload));
      case CHAT_MODEL -> chatModel(rules.chatModelPayload(payload), secret);
      case EMBEDDING_MODEL -> embeddingModel(rules.embeddingModelPayload(payload), secret);
    };
  }

  private Outcome signIn(StepRules.SignInPayload signIn) {
    if (signIn.mode() == StepInput.SignInMode.LOCAL_ONLY) {
      return new Outcome(true, "Anmeldung mit lokalen Konten.");
    }
    OidcProviderConnectionTester.TestOutcome outcome =
        oidcTester.test(signIn.oidc().issuerUri(), signIn.oidc().jwkSetUri());
    return new Outcome(outcome.success(), outcome.message());
  }

  private Outcome chatModel(StepRules.ChatModelPayload model, String secret) {
    String blocked = StepRules.blockedModelAddress(modelAddresses, model.baseUrl());
    if (blocked != null) {
      return new Outcome(false, blocked);
    }
    String apiKey;
    try {
      apiKey = settingsEncryptor.decrypt(secret);
    } catch (IllegalStateException e) {
      return new Outcome(false, UNREADABLE_KEY);
    }
    LlmModelConnectionTester.TestOutcome outcome =
        chatTester.test(model.baseUrl(), model.model(), apiKey, null, model.temperature());
    return new Outcome(outcome.success(), outcome.message());
  }

  private Outcome embeddingModel(StepRules.EmbeddingModelPayload model, String secret) {
    String blocked = StepRules.blockedModelAddress(modelAddresses, model.baseUrl());
    if (blocked != null) {
      return new Outcome(false, blocked);
    }
    EmbeddingModelService.ProbeOutcome outcome;
    try {
      outcome = embeddingModels.probe(rules.embeddingEndpoint(model, secret));
    } catch (IllegalStateException e) {
      return new Outcome(false, UNREADABLE_KEY);
    }
    return new Outcome(outcome.succeeded(), outcome.message(), outcome.dimensions());
  }
}
