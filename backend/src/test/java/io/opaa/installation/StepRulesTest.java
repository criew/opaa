package io.opaa.installation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import io.opaa.account.PasswordPolicy;
import io.opaa.api.types.ColorScheme;
import io.opaa.api.types.MailEncryption;
import io.opaa.auth.OidcAddressPolicy;
import io.opaa.common.FieldValidationException;
import io.opaa.common.ValidationException;
import io.opaa.llm.ModelAddressPolicy;
import io.opaa.mail.MailSettingsRules;
import io.opaa.security.PasswordEncoderConfiguration;
import io.opaa.security.SettingsEncryptionProperties;
import io.opaa.security.SettingsEncryptor;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import tools.jackson.databind.json.JsonMapper;

/**
 * The rules of the steps: field errors name the field, a password leaves only as a hash, a model
 * key only encrypted, and a chat model step without a new key keeps the one saved before.
 */
class StepRulesTest {

  private static final String KEY = "c2V0dGluZ3NkZXZrZXkwMHNldHRpbmdzZGV2a2V5MDE=";
  private static final String PASSWORD = "Erstes-Konto-der-Installation-2026";
  private static final String MAIL_PASSWORD = "Passwort-des-Mailservers";

  private final PasswordEncoder encoder = new PasswordEncoderConfiguration().passwordEncoder();
  private final SettingsEncryptor encryptor =
      new SettingsEncryptor(new SettingsEncryptionProperties(KEY));
  private final OidcAddressPolicy addressPolicy = mock(OidcAddressPolicy.class);
  private final StepRules rules =
      new StepRules(
          new PasswordPolicy(() -> 12),
          encoder,
          encryptor,
          addressPolicy,
          JsonMapper.builder().build(),
          ModelAddressPolicy.allowing(List.of("ollama", "ollama.example.org")),
          InstallationTestMailTargets.LOOPBACK);

  @Test
  void theFirstAccountIsStoredWithItsPasswordHashOnly() {
    StepRules.Stored stored =
        rules.toStored(new StepInput.FirstAdmin(" it@stadt.example ", "IT", PASSWORD), null, null);

    assertThat(stored.payload()).contains("it@stadt.example").doesNotContain(PASSWORD);
    assertThat(encoder.matches(PASSWORD, stored.secret())).isTrue();
  }

  @Test
  void theShippedDefaultAddressAWeakPasswordAndAMissingNameAreFieldErrors() {
    assertThatThrownBy(
            () ->
                rules.toStored(
                    new StepInput.FirstAdmin("admin@opaa.local", "", "kurz"), null, null))
        .isInstanceOfSatisfying(
            FieldValidationException.class,
            e ->
                assertThat(e.fieldErrors())
                    .extracting(FieldValidationException.FieldError::field)
                    .contains("firstAdmin.email", "firstAdmin.displayName", "firstAdmin.password"));
  }

  @Test
  void theModelKeyIsEncryptedAndKeptRemovedOrReplacedOnTheNextSave() {
    StepRules.Stored first =
        rules.toStored(
            new StepInput.ChatModel("http://ollama:11434/v1", "qwen", "sk-geheim", null, null),
            null,
            null);
    assertThat(first.secret()).doesNotContain("sk-geheim");
    assertThat(encryptor.decrypt(first.secret())).isEqualTo("sk-geheim");
    assertThat(first.payload()).doesNotContain("sk-geheim");

    StepRules.Stored kept =
        rules.toStored(
            new StepInput.ChatModel("http://ollama:11434/v1/", "qwen", null, null, null),
            first.payload(),
            first.secret());
    assertThat(kept.secret()).isEqualTo(first.secret());

    StepRules.Stored removed =
        rules.toStored(
            new StepInput.ChatModel("http://ollama:11434/v1", "qwen", "", null, null),
            first.payload(),
            first.secret());
    assertThat(removed.secret()).isNull();
  }

  @Test
  void aModelAddressWithCredentialsAndATemperatureOutOfRangeAreFieldErrors() {
    assertThatThrownBy(
            () ->
                rules.toStored(
                    new StepInput.ChatModel(
                        "https://nutzer:pass@llm.example/v1", "qwen", null, new BigDecimal("3"), 0),
                    null,
                    null))
        .isInstanceOfSatisfying(
            FieldValidationException.class,
            e ->
                assertThat(e.fieldErrors())
                    .extracting(FieldValidationException.FieldError::field)
                    .contains("chatModel.baseUrl", "chatModel.temperature", "chatModel.maxTokens"));
  }

  @Test
  void anIdentityProviderNeedsIssuerAndClientIdAndPassesTheAddressCheck() {
    assertThatThrownBy(
            () ->
                rules.toStored(
                    new StepInput.SignIn(
                        StepInput.SignInMode.LOCAL_AND_OIDC,
                        new StepInput.Oidc("", "", null, null)),
                    null,
                    null))
        .isInstanceOf(FieldValidationException.class);
    doThrow(new ValidationException("Issuer-URI zeigt in ein gesperrtes Netz."))
        .when(addressPolicy)
        .requireAllowed(eq("http://169.254.169.254/realms/x"), eq("Issuer-URI"));
    assertThatThrownBy(
            () ->
                rules.toStored(
                    new StepInput.SignIn(
                        StepInput.SignInMode.LOCAL_AND_OIDC,
                        new StepInput.Oidc("http://169.254.169.254/realms/x", "opaa", null, null)),
                    null,
                    null))
        .isInstanceOf(ValidationException.class);

    StepRules.Stored stored =
        rules.toStored(
            new StepInput.SignIn(
                StepInput.SignInMode.LOCAL_AND_OIDC,
                new StepInput.Oidc("https://login.stadt.example/realms/opaa", "opaa", null, null)),
            null,
            null);
    assertThat(rules.signInPayload(stored.payload()).oidc().displayName())
        .isEqualTo(StepRules.DEFAULT_PROVIDER_NAME);
  }

  // regression guard: a saved key must never follow a changed model address
  @Test
  void aSavedKeyDoesNotFollowAChangedAddress() {
    StepRules.Stored first =
        rules.toStored(
            new StepInput.ChatModel("http://ollama:11434/v1", "qwen", "sk-geheim", null, null),
            null,
            null);

    for (String moved :
        List.of(
            "https://ollama:11434/v1",
            "http://ollama:11435/v1",
            "http://ollama:11434/v2",
            "http://ollama.example.org:11434/v1")) {
      assertThatThrownBy(
              () ->
                  rules.toStored(
                      new StepInput.ChatModel(moved, "qwen", null, null, null),
                      first.payload(),
                      first.secret()))
          .as(moved)
          .isInstanceOfSatisfying(
              FieldValidationException.class,
              e ->
                  assertThat(e.fieldErrors())
                      .extracting(FieldValidationException.FieldError::field)
                      .containsExactly("chatModel.apiKey"));
    }
    StepRules.Stored withNewKey =
        rules.toStored(
            new StepInput.ChatModel("https://ollama:11434/v1", "qwen", "sk-neu", null, null),
            first.payload(),
            first.secret());
    assertThat(encryptor.decrypt(withNewKey.secret())).isEqualTo("sk-neu");
  }

  // regression guard: the model address of the installation passes the address check of models
  @Test
  void aModelAddressInABlockedRangeIsAFieldError() {
    for (String blocked :
        List.of("http://169.254.169.254/v1", "http://127.0.0.1:11434/v1", "http://10.0.0.5/v1")) {
      assertThatThrownBy(
              () ->
                  rules.toStored(
                      new StepInput.ChatModel(blocked, "qwen", null, null, null), null, null))
          .as(blocked)
          .isInstanceOfSatisfying(
              FieldValidationException.class,
              e ->
                  assertThat(e.fieldErrors())
                      .extracting(FieldValidationException.FieldError::field)
                      .containsExactly("chatModel.baseUrl"));
    }
  }

  @Test
  void aModelHostIsReleasedOnlyByItsEntryInTheAllowlistOfModels() {
    StepRules withPort =
        new StepRules(
            new PasswordPolicy(() -> 12),
            encoder,
            encryptor,
            addressPolicy,
            JsonMapper.builder().build(),
            ModelAddressPolicy.allowing(List.of("127.0.0.1:11434")),
            InstallationTestMailTargets.LOOPBACK);

    assertThat(
            withPort
                .toStored(
                    new StepInput.ChatModel("http://127.0.0.1:11434/v1", "qwen", null, null, null),
                    null,
                    null)
                .payload())
        .contains("127.0.0.1:11434");
    assertThatThrownBy(
            () ->
                withPort.toStored(
                    new StepInput.EmbeddingModel("http://127.0.0.1:8080/v1", "bge-m3", null, null),
                    null,
                    null))
        .isInstanceOfSatisfying(
            FieldValidationException.class,
            e ->
                assertThat(e.fieldErrors().getFirst().message())
                    .contains("OPAA_MODEL_TARGET_VALIDATION_ALLOWLIST"));
  }

  @Test
  void anEmbeddingModelNeedsAddressModelAndAPositiveInputLimit() {
    assertThatThrownBy(
            () ->
                rules.toStored(
                    new StepInput.EmbeddingModel("ollama:11434/v1", " ", null, 0), null, null))
        .isInstanceOfSatisfying(
            FieldValidationException.class,
            e ->
                assertThat(e.fieldErrors())
                    .extracting(FieldValidationException.FieldError::field)
                    .containsExactlyInAnyOrder(
                        "embeddingModel.baseUrl",
                        "embeddingModel.model",
                        "embeddingModel.maxInputTokens"));
  }

  @Test
  void theProbedVectorSizeIsKeptUntilTheNextSave() {
    StepRules.Stored saved =
        rules.toStored(
            new StepInput.EmbeddingModel("http://ollama:11434/v1", "bge-m3", "sk-geheim", null),
            null,
            null);
    String probed = rules.withProbedDimensions(saved.payload(), 1024);

    assertThat(rules.embeddingModelPayload(saved.payload()).dimensions()).isNull();
    assertThat(rules.embeddingModelPayload(probed).dimensions()).isEqualTo(1024);
    assertThat(rules.view(InstallationStep.EMBEDDING_MODEL, probed, saved.secret()))
        .isEqualTo(
            new InstallationOverview.EmbeddingModel(
                "http://ollama:11434/v1", "bge-m3", true, 2048, 1024));
    StepRules.Stored again =
        rules.toStored(
            new StepInput.EmbeddingModel("http://ollama:11434/v1", "bge-m3", null, 8192),
            probed,
            saved.secret());
    assertThat(rules.embeddingModelPayload(again.payload()).dimensions()).isNull();
    assertThat(again.secret()).isEqualTo(saved.secret());
    assertThat(encryptor.decrypt(again.secret())).isEqualTo("sk-geheim");
  }

  // regression guard: a saved key must never follow a changed embedding address
  @Test
  void aSavedEmbeddingKeyDoesNotFollowAChangedAddress() {
    StepRules.Stored first =
        rules.toStored(
            new StepInput.EmbeddingModel("http://ollama:11434/v1", "bge-m3", "sk-geheim", null),
            null,
            null);

    assertThatThrownBy(
            () ->
                rules.toStored(
                    new StepInput.EmbeddingModel(
                        "http://ollama.example.org:11434/v1", "bge-m3", null, null),
                    first.payload(),
                    first.secret()))
        .isInstanceOfSatisfying(
            FieldValidationException.class,
            e ->
                assertThat(e.fieldErrors())
                    .extracting(FieldValidationException.FieldError::field)
                    .containsExactly("embeddingModel.apiKey"));
  }

  @Test
  void theEmbeddingStepKeepsTheLengthLimitsOfTheChatStep() {
    assertThatThrownBy(
            () ->
                rules.toStored(
                    new StepInput.EmbeddingModel(
                        "http://ollama:11434/" + "v".repeat(StepRules.MAX_ADDRESS),
                        "m".repeat(StepRules.MAX_NAME + 1),
                        "k".repeat(StepRules.MAX_API_KEY + 1),
                        StepRules.MAX_INPUT_TOKENS + 1),
                    null,
                    null))
        .isInstanceOfSatisfying(
            FieldValidationException.class,
            e ->
                assertThat(e.fieldErrors())
                    .extracting(FieldValidationException.FieldError::field)
                    .contains(
                        "embeddingModel.baseUrl",
                        "embeddingModel.model",
                        "embeddingModel.apiKey",
                        "embeddingModel.maxInputTokens"));
  }

  @Test
  void theMailServerIsStoredWithItsPasswordEncryptedOnly() {
    StepRules.Stored stored =
        rules.toStored(mail("127.0.0.1", "kennung", MAIL_PASSWORD), null, null);

    assertThat(stored.payload()).contains("127.0.0.1").doesNotContain(MAIL_PASSWORD);
    assertThat(stored.secret()).startsWith("enc:").doesNotContain(MAIL_PASSWORD);
    assertThat(encryptor.decrypt(stored.secret())).isEqualTo(MAIL_PASSWORD);
    InstallationOverview.Mail view =
        (InstallationOverview.Mail)
            rules.view(InstallationStep.MAIL, stored.payload(), stored.secret());
    assertThat(view.passwordSet()).isTrue();
    assertThat(view.encryption()).isEqualTo(MailEncryption.NONE);
  }

  @Test
  void theMailServerNeedsServerPortAndSenderWithTheSentencesOfTheAdministration() {
    assertThatThrownBy(
            () ->
                rules.toStored(
                    new StepInput.Mail(" ", null, null, null, null, "opaa", null), null, null))
        .isInstanceOfSatisfying(
            FieldValidationException.class,
            e ->
                assertThat(e.fieldErrors())
                    .extracting(
                        FieldValidationException.FieldError::field,
                        FieldValidationException.FieldError::message)
                    .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                            "mail.host", MailSettingsRules.HOST_MISSING),
                        org.assertj.core.groups.Tuple.tuple(
                            "mail.port", MailSettingsRules.PORT_MISSING),
                        org.assertj.core.groups.Tuple.tuple(
                            "mail.fromAddress",
                            MailSettingsRules.fromAddressProblem("opaa").orElseThrow())));
  }

  // regression guard: the installation's mail check reaches no internal address unless released
  @Test
  void anInternalMailServerIsRefusedUntilTheOperatorReleasesIt() {
    assertThatThrownBy(() -> rules.toStored(mail("10.20.30.40", null, null), null, null))
        .isInstanceOfSatisfying(
            FieldValidationException.class,
            e ->
                assertThat(e.fieldErrors())
                    .singleElement()
                    .satisfies(
                        error -> {
                          assertThat(error.field()).isEqualTo("mail.host");
                          assertThat(error.code()).isEqualTo("BLOCKED");
                          assertThat(error.message())
                              .contains("OPAA_INSTALLATION_MAIL_TARGET_ALLOWLIST");
                        }));
  }

  @Test
  void aSavedMailPasswordStaysOnlyWithTheSameTargetAndAnEmptyOneRemovesIt() {
    StepRules.Stored first =
        rules.toStored(mail("127.0.0.1", "kennung", MAIL_PASSWORD), null, null);

    StepRules.Stored kept =
        rules.toStored(
            new StepInput.Mail(
                "127.0.0.1",
                2525,
                MailEncryption.NONE,
                "kennung",
                null,
                "opaa@stadt.example",
                "Neuer Name"),
            first.payload(),
            first.secret());
    assertThat(kept.secret()).isEqualTo(first.secret());

    assertThatThrownBy(
            () ->
                rules.toStored(
                    new StepInput.Mail(
                        "127.0.0.1",
                        2525,
                        MailEncryption.STARTTLS,
                        "kennung",
                        null,
                        "opaa@stadt.example",
                        null),
                    first.payload(),
                    first.secret()))
        .isInstanceOfSatisfying(
            FieldValidationException.class,
            e ->
                assertThat(e.fieldErrors())
                    .extracting(
                        FieldValidationException.FieldError::field,
                        FieldValidationException.FieldError::code,
                        FieldValidationException.FieldError::message)
                    .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                            "mail.password",
                            MailSettingsRules.PASSWORD_REQUIRED,
                            MailSettingsRules.PASSWORD_FOLLOWS_TARGET)));

    assertThat(
            rules
                .toStored(mail("127.0.0.1", "kennung", ""), first.payload(), first.secret())
                .secret())
        .isNull();
  }

  // regression guard: the assistant reaches only SMTP ports unless an entry names host and port
  @Test
  void aMailServerOnAnotherPortNeedsAnEntryNamingExactlyThatPort() {
    StepInput.Mail unusualPort =
        new StepInput.Mail(
            "127.0.0.1", 6379, MailEncryption.NONE, null, null, "opaa@stadt.example", null);

    assertThatThrownBy(() -> rules.toStored(unusualPort, null, null))
        .isInstanceOfSatisfying(
            FieldValidationException.class,
            e ->
                assertThat(e.fieldErrors())
                    .extracting(
                        FieldValidationException.FieldError::field,
                        FieldValidationException.FieldError::message)
                    .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                            "mail.port", InstallationMailTargets.PORT_REFUSED)));

    StepRules released =
        new StepRules(
            new PasswordPolicy(() -> 12),
            encoder,
            encryptor,
            addressPolicy,
            JsonMapper.builder().build(),
            ModelAddressPolicy.allowing(List.of()),
            new InstallationMailTargets(
                new InstallationProperties(null, List.of(), null, List.of("127.0.0.1:6379"))));
    assertThat(released.toStored(unusualPort, null, null).payload()).contains("6379");
  }

  // regression guard: the mask of the administration must never be stored as a password
  @Test
  void theMaskOfTheAdministrationIsNoPassword() {
    assertThatThrownBy(() -> rules.toStored(mail("127.0.0.1", "kennung", "***"), null, null))
        .isInstanceOfSatisfying(
            FieldValidationException.class,
            e ->
                assertThat(e.fieldErrors())
                    .extracting(FieldValidationException.FieldError::field)
                    .containsExactly("mail.password"));
  }

  @Test
  void aServerWithControlCharactersAndASenderListAreRefused() {
    assertThatThrownBy(
            () ->
                rules.toStored(
                    new StepInput.Mail(
                        "smtp\r\nX-Evil", 25, MailEncryption.NONE, null, null, "a@b.c,d@e.f", null),
                    null,
                    null))
        .isInstanceOfSatisfying(
            FieldValidationException.class,
            e ->
                assertThat(e.fieldErrors())
                    .extracting(FieldValidationException.FieldError::field)
                    .containsExactly("mail.host", "mail.fromAddress"));
  }

  private static StepInput.Mail mail(String host, String username, String password) {
    return new StepInput.Mail(
        host, 2525, MailEncryption.NONE, username, password, " opaa@stadt.example ", "OPAA");
  }

  @Test
  void aKeyWithControlCharactersIsAFieldError() {
    assertThatThrownBy(
            () ->
                rules.toStored(
                    new StepInput.EmbeddingModel(
                        "http://ollama:11434/v1", "bge-m3", "sk-eins\r\nX-Evil: 1", null),
                    null,
                    null))
        .isInstanceOfSatisfying(
            FieldValidationException.class,
            e ->
                assertThat(e.fieldErrors())
                    .extracting(FieldValidationException.FieldError::field)
                    .contains("embeddingModel.apiKey"));
  }

  @Test
  void theBrandingIsStoredWithTheRulesOfTheAdministrationAndBlankMeansTheDefault() {
    StepRules.Stored stored =
        rules.toStored(
            new StepInput.Branding(" Landesamt-Assistent ", "", " ", " #7A3E9D ", ColorScheme.DARK),
            null,
            null);

    assertThat(stored.secret()).isNull();
    assertThat(rules.view(InstallationStep.BRANDING, stored.payload(), null))
        .isEqualTo(
            new InstallationOverview.Branding(
                "Landesamt-Assistent", null, null, "#7A3E9D", ColorScheme.DARK));
    assertThat(rules.brandingErrors(rules.brandingPayload(stored.payload()))).isEmpty();
  }

  @Test
  void aBrandingProblemIsAFieldErrorUnderTheStep() {
    assertThatThrownBy(
            () ->
                rules.toStored(
                    new StepInput.Branding("x".repeat(61), null, null, "#12345", null), null, null))
        .isInstanceOfSatisfying(
            FieldValidationException.class,
            e ->
                assertThat(e.fieldErrors())
                    .extracting(FieldValidationException.FieldError::field)
                    .containsExactly("branding.productName", "branding.primaryColor"));
  }
}
