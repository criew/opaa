package io.opaa.installation;

import io.opaa.auth.OidcProviderConnectionTester;
import io.opaa.llm.LlmModelConnectionTester;
import io.opaa.security.SettingsEncryptor;
import io.opaa.security.TargetAddressValidator;
import org.springframework.stereotype.Component;

/**
 * The check of each saved step (ADR-0060, Entscheidung 6): the same connection tests the
 * administration runs for an identity provider and a chat model. A model address is checked against
 * the target address check again right before the probe. The outcome is German and user-facing; a
 * failed check is a result, never an exception.
 */
@Component
class StepChecks {

  /** The outcome of one check. */
  record Outcome(boolean succeeded, String message) {}

  private final StepRules rules;
  private final OidcProviderConnectionTester oidcTester;
  private final LlmModelConnectionTester chatTester;
  private final SettingsEncryptor settingsEncryptor;
  private final TargetAddressValidator modelAddresses;

  StepChecks(
      StepRules rules,
      OidcProviderConnectionTester oidcTester,
      LlmModelConnectionTester chatTester,
      SettingsEncryptor settingsEncryptor,
      TargetAddressValidator modelAddresses) {
    this.rules = rules;
    this.oidcTester = oidcTester;
    this.chatTester = chatTester;
    this.settingsEncryptor = settingsEncryptor;
    this.modelAddresses = modelAddresses;
  }

  Outcome check(InstallationStep step, String payload, String secret) {
    return switch (step) {
      case FIRST_ADMIN ->
          secret == null
              ? new Outcome(false, "Das Passwort des ersten Kontos fehlt.")
              : new Outcome(true, "Das erste Konto erfüllt die Regeln.");
      case SIGN_IN -> signIn(rules.signInPayload(payload));
      case CHAT_MODEL -> chatModel(rules.chatModelPayload(payload), secret);
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
      return new Outcome(
          false,
          "Der gespeicherte Zugangsschlüssel ist nicht lesbar; bitte den Schlüssel erneut"
              + " eingeben.");
    }
    LlmModelConnectionTester.TestOutcome outcome =
        chatTester.test(model.baseUrl(), model.model(), apiKey, null, model.temperature());
    return new Outcome(outcome.success(), outcome.message());
  }
}
