package io.opaa.installation;

import io.opaa.auth.OidcProviderConnectionTester;
import io.opaa.common.FieldValidationException;
import io.opaa.knowledge.MalwareScanSettingsService;
import io.opaa.llm.EmbeddingModelService;
import io.opaa.llm.LlmModelConnectionTester;
import io.opaa.llm.ModelAddressPolicy;
import io.opaa.llm.ModelRoleAdministration;
import io.opaa.llm.TextRecognitionClient;
import io.opaa.mail.MailConnectionTester;
import io.opaa.mail.MailSettingsSnapshot;
import java.util.List;
import java.util.function.Function;
import org.springframework.stereotype.Component;

/**
 * The check of each saved step (ADR-0060, Entscheidung 6): the same connection tests the
 * administration runs for an identity provider and a chat model, a probe embedding for the
 * embedding model, which also yields its vector size (Entscheidung 10), the probes of the model
 * administration for the rerank and the transcription model (Entscheidung 16 for transcription),
 * the staged test of the model administration for text recognition (Entscheidung 18), the version
 * request of the scanner administration for the malware scan (Entscheidung 19), a connection to the
 * mail server that sends nothing unless a test recipient is named, and the rules of the branding
 * administration once more for the branding, without any connection. A model address and a mail
 * server are checked against the address check of the installation again right before the
 * connection. The outcome is German and user-facing; a failed check is a result, never an
 * exception.
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
  private final ModelAddressPolicy modelAddresses;
  private final EmbeddingModelService embeddingModels;
  private final MailConnectionTester mailTester;
  private final ModelRoleAdministration modelRoles;
  private final MalwareScanSettingsService malwareScans;

  StepChecks(
      StepRules rules,
      OidcProviderConnectionTester oidcTester,
      LlmModelConnectionTester chatTester,
      ModelAddressPolicy modelAddresses,
      EmbeddingModelService embeddingModels,
      MailConnectionTester mailTester,
      ModelRoleAdministration modelRoles,
      MalwareScanSettingsService malwareScans) {
    this.rules = rules;
    this.oidcTester = oidcTester;
    this.chatTester = chatTester;
    this.modelAddresses = modelAddresses;
    this.embeddingModels = embeddingModels;
    this.mailTester = mailTester;
    this.modelRoles = modelRoles;
    this.malwareScans = malwareScans;
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
      case RERANK_MODEL -> rerankModel(rules.rerankModelPayload(payload), secret);
      case TRANSCRIPTION_MODEL ->
          transcriptionModel(rules.transcriptionModelPayload(payload), secret);
      case TEXT_RECOGNITION_MODEL ->
          textRecognitionModel(rules.textRecognitionModelPayload(payload), secret);
      case MALWARE_SCAN -> malwareScan(rules.malwareScanPayload(payload));
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
    return withKey(
        InstallationStep.CHAT_MODEL,
        secret,
        apiKey -> {
          LlmModelConnectionTester.TestOutcome outcome =
              chatTester.test(model.baseUrl(), model.model(), apiKey, null, model.temperature());
          return new Outcome(outcome.success(), outcome.message());
        });
  }

  /**
   * {@code probe} with the saved key of a model step in clear; an unreadable key fails the check.
   */
  private Outcome withKey(InstallationStep step, String secret, Function<String, Outcome> probe) {
    String apiKey;
    try {
      apiKey = rules.apiKey(step, secret);
    } catch (IllegalStateException e) {
      return new Outcome(false, UNREADABLE_KEY);
    }
    return probe.apply(apiKey);
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

  private Outcome rerankModel(StepRules.RerankModelPayload model, String secret) {
    String blocked = StepRules.blockedModelAddress(modelAddresses, model.baseUrl());
    if (blocked != null) {
      return new Outcome(false, blocked);
    }
    return withKey(
        InstallationStep.RERANK_MODEL,
        secret,
        apiKey -> outcome(modelRoles.probeRerank(model.baseUrl(), model.model(), apiKey)));
  }

  private Outcome transcriptionModel(StepRules.TranscriptionModelPayload model, String secret) {
    String blocked = StepRules.blockedModelAddress(modelAddresses, model.baseUrl());
    if (blocked != null) {
      return new Outcome(false, blocked);
    }
    return withKey(
        InstallationStep.TRANSCRIPTION_MODEL,
        secret,
        apiKey -> outcome(modelRoles.probeTranscription(model.baseUrl(), model.model(), apiKey)));
  }

  private static Outcome outcome(ModelRoleAdministration.TestOutcome outcome) {
    return new Outcome(outcome.success(), outcome.message());
  }

  /**
   * The staged test of the text recognition administration with its test page; the message names
   * the stage a failure stopped at (address, sign-in, recognition).
   */
  private Outcome textRecognitionModel(StepRules.TextRecognitionModelPayload model, String secret) {
    String blocked = StepRules.blockedModelAddress(modelAddresses, model.baseUrl());
    if (blocked != null) {
      return new Outcome(false, stageMessage(TextRecognitionClient.FailedStage.ADDRESS, blocked));
    }
    return withKey(
        InstallationStep.TEXT_RECOGNITION_MODEL,
        secret,
        apiKey -> {
          ModelRoleAdministration.TextRecognitionTestOutcome outcome =
              modelRoles.probeTextRecognition(
                  model.connectionType(), model.baseUrl(), model.model(), apiKey);
          return outcome.success()
              ? new Outcome(
                  true, "Adresse, Anmeldung und Erkennung in Ordnung. " + outcome.message())
              : new Outcome(false, stageMessage(outcome.failedStage(), outcome.message()));
        });
  }

  /** A failed staged test, the stage named first; without a stage the message alone. */
  static String stageMessage(TextRecognitionClient.FailedStage stage, String message) {
    if (stage == null) {
      return message;
    }
    String name =
        switch (stage) {
          case ADDRESS -> "Adresse";
          case AUTHENTICATION -> "Anmeldung";
          case RECOGNITION -> "Erkennung";
        };
    return "Gescheitert an der Stufe „" + name + "“: " + message;
  }

  /** The version request of the scanner administration: connect, ask, no file. */
  private Outcome malwareScan(StepRules.MalwareScanPayload scan) {
    MalwareScanSettingsService.TestOutcome outcome =
        malwareScans.test("malwareScan.", scan.host(), scan.port(), scan.timeoutSeconds());
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
