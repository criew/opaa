package io.opaa.installation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import io.opaa.account.PasswordPolicy;
import io.opaa.auth.OidcAddressPolicy;
import io.opaa.common.FieldValidationException;
import io.opaa.common.ValidationException;
import io.opaa.security.PasswordEncoderConfiguration;
import io.opaa.security.SettingsEncryptionProperties;
import io.opaa.security.SettingsEncryptor;
import io.opaa.security.TargetAddressValidator;
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
          new TargetAddressValidator(true, List.of("ollama", "ollama.example.org")));

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

  // regression guard: the model address of the installation passes the target address check
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
}
