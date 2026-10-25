package io.opaa.installation;

import io.opaa.account.LocalAdminSeeder;
import io.opaa.account.PasswordPolicy;
import io.opaa.auth.OidcAddressPolicy;
import io.opaa.auth.OidcIssuerUris;
import io.opaa.common.FieldValidationException;
import io.opaa.common.FieldValidationException.FieldError;
import io.opaa.common.ValidationException;
import io.opaa.llm.ModelEndpointUri;
import io.opaa.security.SettingsEncryptor;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * The rules of each step (ADR-0060, Entscheidung 6): what a saved step must satisfy, how it is
 * stored and how it is read back. A password leaves this class only as a hash and a model key only
 * encrypted; the payload JSON carries no secret.
 */
@Component
class StepRules {

  static final int DEFAULT_MAX_TOKENS = 8000;
  static final String DEFAULT_PROVIDER_NAME = "Verzeichnisdienst";
  static final BigDecimal MAX_TEMPERATURE = new BigDecimal("2");

  private static final String INVALID = "INVALID";
  private static final String REQUIRED = "REQUIRED";

  /** A step as stored: values without secret, and the hash or ciphertext of its secret. */
  record Stored(String payload, String secret) {}

  record FirstAdminPayload(String email, String displayName) {}

  record SignInPayload(StepInput.SignInMode mode, StepInput.Oidc oidc) {}

  record ChatModelPayload(
      String baseUrl, String model, BigDecimal temperature, Integer maxTokens) {}

  private final PasswordPolicy passwordPolicy;
  private final PasswordEncoder passwordEncoder;
  private final SettingsEncryptor settingsEncryptor;
  private final OidcAddressPolicy addressPolicy;
  private final JsonMapper json;

  StepRules(
      PasswordPolicy passwordPolicy,
      PasswordEncoder passwordEncoder,
      SettingsEncryptor settingsEncryptor,
      OidcAddressPolicy addressPolicy,
      JsonMapper json) {
    this.passwordPolicy = passwordPolicy;
    this.passwordEncoder = passwordEncoder;
    this.settingsEncryptor = settingsEncryptor;
    this.addressPolicy = addressPolicy;
    this.json = json;
  }

  /**
   * Validates {@code input} and returns what is stored; {@code previousSecret} is the secret saved
   * before, kept by a chat model step without a new key.
   *
   * @throws FieldValidationException naming every violated field
   * @throws ValidationException for an address the address check refuses
   */
  Stored toStored(StepInput input, String previousSecret) {
    return switch (input) {
      case StepInput.FirstAdmin admin -> firstAdmin(admin);
      case StepInput.SignIn signIn -> signIn(signIn);
      case StepInput.ChatModel model -> chatModel(model, previousSecret);
    };
  }

  InstallationOverview.SavedValues view(InstallationStep step, String payload, String secret) {
    return switch (step) {
      case FIRST_ADMIN -> {
        FirstAdminPayload admin = firstAdminPayload(payload);
        yield new InstallationOverview.FirstAdmin(
            admin.email(), admin.displayName(), secret != null);
      }
      case SIGN_IN -> {
        SignInPayload signIn = signInPayload(payload);
        yield new InstallationOverview.SignIn(signIn.mode(), signIn.oidc());
      }
      case CHAT_MODEL -> {
        ChatModelPayload model = chatModelPayload(payload);
        yield new InstallationOverview.ChatModel(
            model.baseUrl(), model.model(), secret != null, model.temperature(), maxTokens(model));
      }
    };
  }

  FirstAdminPayload firstAdminPayload(String payload) {
    return json.readValue(payload, FirstAdminPayload.class);
  }

  SignInPayload signInPayload(String payload) {
    return json.readValue(payload, SignInPayload.class);
  }

  ChatModelPayload chatModelPayload(String payload) {
    return json.readValue(payload, ChatModelPayload.class);
  }

  static int maxTokens(ChatModelPayload model) {
    return model.maxTokens() == null ? DEFAULT_MAX_TOKENS : model.maxTokens();
  }

  private Stored firstAdmin(StepInput.FirstAdmin input) {
    List<FieldError> errors = new ArrayList<>();
    String email = trimmed(input.email());
    String displayName = trimmed(input.displayName());
    if (email.isEmpty()) {
      errors.add(error("firstAdmin.email", REQUIRED, "Die E-Mail-Adresse fehlt."));
    } else if (!looksLikeAnAddress(email) || email.length() > 320) {
      errors.add(error("firstAdmin.email", INVALID, "Das ist keine gültige E-Mail-Adresse."));
    } else if (LocalAdminSeeder.REJECTED_DEFAULT_EMAIL.equalsIgnoreCase(email)) {
      errors.add(
          error(
              "firstAdmin.email",
              INVALID,
              "Bitte ein zustellbares Postfach angeben, etwa ein Funktionspostfach der IT."));
    }
    if (displayName.isEmpty()) {
      errors.add(error("firstAdmin.displayName", REQUIRED, "Der Anzeigename fehlt."));
    } else if (displayName.length() > 200) {
      errors.add(error("firstAdmin.displayName", INVALID, "Der Anzeigename ist zu lang."));
    }
    for (PasswordPolicy.Violation violation : passwordPolicy.check(input.password(), email)) {
      errors.add(error("firstAdmin.password", violation.code(), violation.message()));
    }
    requireNoErrors(errors);
    return new Stored(
        json.writeValueAsString(new FirstAdminPayload(email, displayName)),
        passwordEncoder.encode(input.password()));
  }

  private Stored signIn(StepInput.SignIn input) {
    if (input.mode() == null) {
      throw FieldValidationException.of(
          "signIn.mode", REQUIRED, "Bitte ein Anmeldeverfahren wählen.");
    }
    if (input.mode() == StepInput.SignInMode.LOCAL_ONLY) {
      return new Stored(json.writeValueAsString(new SignInPayload(input.mode(), null)), null);
    }
    StepInput.Oidc oidc = input.oidc();
    if (oidc == null) {
      throw FieldValidationException.of(
          "signIn.oidc", REQUIRED, "Die Angaben zum Identitätsanbieter fehlen.");
    }
    List<FieldError> errors = new ArrayList<>();
    String issuer = trimmed(oidc.issuerUri());
    String clientId = trimmed(oidc.clientId());
    String jwkSetUri = trimmed(oidc.jwkSetUri());
    String displayName = trimmed(oidc.displayName());
    if (issuer.isEmpty()) {
      errors.add(error("signIn.oidc.issuerUri", REQUIRED, "Die Issuer-URI fehlt."));
    }
    if (clientId.isEmpty()) {
      errors.add(error("signIn.oidc.clientId", REQUIRED, "Die Client-ID fehlt."));
    }
    requireNoErrors(errors);
    addressPolicy.requireAllowed(issuer, "Issuer-URI");
    if (!jwkSetUri.isEmpty()) {
      addressPolicy.requireAllowed(jwkSetUri, "JWK-Set-URI");
    }
    StepInput.Oidc stored =
        new StepInput.Oidc(
            OidcIssuerUris.requireHttpUri(issuer, "Issuer-URI").toString(),
            clientId,
            jwkSetUri.isEmpty() ? null : jwkSetUri,
            displayName.isEmpty() ? DEFAULT_PROVIDER_NAME : displayName);
    return new Stored(json.writeValueAsString(new SignInPayload(input.mode(), stored)), null);
  }

  private Stored chatModel(StepInput.ChatModel input, String previousSecret) {
    List<FieldError> errors = new ArrayList<>();
    String baseUrl = trimmed(input.baseUrl());
    String model = trimmed(input.model());
    if (baseUrl.isEmpty()) {
      errors.add(error("chatModel.baseUrl", REQUIRED, "Die Basis-Adresse fehlt."));
    } else if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
      errors.add(
          error(
              "chatModel.baseUrl",
              INVALID,
              "Die Basis-Adresse muss mit http:// oder https:// beginnen."));
    } else if (ModelEndpointUri.containsCredentials(baseUrl)) {
      errors.add(
          error(
              "chatModel.baseUrl",
              INVALID,
              "Die Basis-Adresse darf keine Anmeldedaten enthalten; der Schlüssel gehört in das"
                  + " Feld Zugangsschlüssel."));
    }
    if (model.isEmpty()) {
      errors.add(error("chatModel.model", REQUIRED, "Die Modell-Kennung fehlt."));
    }
    if (input.maxTokens() != null && input.maxTokens() < 1) {
      errors.add(error("chatModel.maxTokens", INVALID, "Die Antwortlänge muss mindestens 1 sein."));
    }
    BigDecimal temperature = input.temperature();
    if (temperature != null
        && (temperature.signum() < 0 || temperature.compareTo(MAX_TEMPERATURE) > 0)) {
      errors.add(error("chatModel.temperature", INVALID, "Die Temperatur liegt zwischen 0 und 2."));
    }
    requireNoErrors(errors);
    String secret;
    if (input.apiKey() == null) {
      secret = previousSecret;
    } else if (input.apiKey().isBlank()) {
      secret = null;
    } else {
      secret = encrypt(input.apiKey().strip());
    }
    return new Stored(
        json.writeValueAsString(
            new ChatModelPayload(baseUrl, model, temperature, input.maxTokens())),
        secret);
  }

  private String encrypt(String apiKey) {
    try {
      return settingsEncryptor.encrypt(apiKey);
    } catch (IllegalStateException e) {
      throw FieldValidationException.of(
          "chatModel.apiKey",
          INVALID,
          "Der Zugangsschlüssel kann nicht gespeichert werden: OPAA_SETTINGS_ENCRYPTION_KEY ist"
              + " nicht gesetzt oder ungültig.");
    }
  }

  private static void requireNoErrors(List<FieldError> errors) {
    if (!errors.isEmpty()) {
      throw new FieldValidationException("Die Angaben des Schritts sind unvollständig.", errors);
    }
  }

  private static FieldError error(String field, String code, String message) {
    return new FieldError(field, code, message);
  }

  private static String trimmed(String value) {
    return value == null ? "" : value.trim();
  }

  private static boolean looksLikeAnAddress(String email) {
    int at = email.indexOf('@');
    return at > 0
        && at < email.length() - 1
        && email.indexOf('@', at + 1) < 0
        && email.chars().noneMatch(Character::isWhitespace);
  }
}
