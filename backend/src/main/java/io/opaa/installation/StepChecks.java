package io.opaa.installation;

import io.opaa.auth.OidcProviderConnectionTester;
import io.opaa.common.FieldValidationException;
import io.opaa.llm.EmbeddingModelService;
import io.opaa.llm.LlmModelConnectionTester;
import io.opaa.llm.ModelAddressPolicy;
import io.opaa.llm.ModelRoleAdministration;
import io.opaa.mail.MailConnectionTester;
import io.opaa.mail.MailSettingsSnapshot;
import io.opaa.security.SettingsEncryptor;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * The check of each saved step (ADR-0060, Entscheidung 6): the same connection tests the
 * administration runs for an identity provider and a chat model, a probe embedding for the
 * embedding model, which also yields its vector size (Entscheidung 10), the probe of the model
 * administration for the transcription model (Entscheidung 16), and a connection to the mail server
 * that sends nothing unless a test recipient is named, and the rules of the branding administration
 * once more for the branding, without any connection. A model address and a mail server are checked
 * against the address check of the installation again right before the connection. The outcome is
 * German and user-facing; a failed check is a result, never an exception.
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
  private static final String UNREADABLE_PASSWORD =
      "Das gespeicherte Passwort ist nicht lesbar; bitte das Passwort erneut eingeben.";

  private final StepRules rules;
  private final OidcProviderConnectionTester oidcTester;
  private final LlmModelConnectionTester chatTester;
  private final SettingsEncryptor settingsEncryptor;
  private final ModelAddressPolicy modelAddresses;
  private final EmbeddingModelService embeddingModels;
  private final MailConnectionTester mailTester;
  private final ModelRoleAdministration modelRoles;

  StepChecks(
      StepRules rules,
      OidcProviderConnectionTester oidcTester,
      LlmModelConnectionTester chatTester,
      SettingsEncryptor settingsEncryptor,
      ModelAddressPolicy modelAddresses,
      EmbeddingModelService embeddingModels,
      MailConnectionTester mailTester,
      ModelRoleAdministration modelRoles) {
    this.rules = rules;
    this.oidcTester = oidcTester;
    this.chatTester = chatTester;
    this.settingsEncryptor = settingsEncryptor;
    this.modelAddresses = modelAddresses;
    this.embeddingModels = embeddingModels;
    this.mailTester = mailTester;
    this.modelRoles = modelRoles;
  }

  /**
   * Checks the saved values of {@code step}; {@code testRecipient} is read by {@link
   * InstallationStep#MAIL} only, where a non-blank one also receives the test message.
   */
  Outcome check(InstallationStep step, String payload, String secret, String testRecipient) {
    return switch (step) {
      case FIRST_ADMIN ->
          secret == null
              ? new Outcome(false, "Das Passwort des ersten Kontos fehlt.")
              : new Outcome(true, "Das erste Konto erfüllt die Regeln.");
      case SIGN_IN -> signIn(rules.signInPayload(payload));
      case CHAT_MODEL -> chatModel(rules.chatModelPayload(payload), secret);
      case EMBEDDING_MODEL -> embeddingModel(rules.embeddingModelPayload(payload), secret);
      case TRANSCRIPTION_MODEL ->
          transcriptionModel(rules.transcriptionModelPayload(payload), secret);
      case MAIL -> mail(rules.mailPayload(payload), secret, testRecipient);
      case BRANDING -> branding(rules.brandingPayload(payload));
    };
  }

  private Outcome branding(StepRules.BrandingPayload branding) {
    List<FieldValidationException.FieldError> errors = rules.brandingErrors(branding);
    return errors.isEmpty()
        ? new Outcome(true, "Das Branding erfüllt die Regeln der Verwaltung.")
        : new Outcome(false, errors.getFirst().message());
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

  private Outcome transcriptionModel(StepRules.TranscriptionModelPayload model, String secret) {
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
    ModelRoleAdministration.TestOutcome outcome =
        modelRoles.probeTranscription(model.baseUrl(), model.model(), apiKey);
    return new Outcome(outcome.success(), outcome.message());
  }

  private Outcome mail(StepRules.MailPayload mail, String secret, String testRecipient) {
    String blocked = rules.blockedMailServer(mail);
    if (blocked != null) {
      return new Outcome(false, blocked);
    }
    MailSettingsSnapshot settings;
    try {
      settings = rules.mailSettings(mail, secret);
    } catch (IllegalStateException e) {
      return new Outcome(false, UNREADABLE_PASSWORD);
    }
    MailConnectionTester.Outcome outcome = mailTester.test(settings, testRecipient);
    return new Outcome(outcome.succeeded(), outcome.message());
  }
}
