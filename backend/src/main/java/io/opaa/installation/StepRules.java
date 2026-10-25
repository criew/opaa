package io.opaa.installation;

import io.opaa.account.InitialAdministrator;
import io.opaa.account.PasswordPolicy;
import io.opaa.auth.OidcAddressPolicy;
import io.opaa.auth.OidcIssuerUris;
import io.opaa.common.FieldValidationException;
import io.opaa.common.FieldValidationException.FieldError;
import io.opaa.common.ValidationException;
import io.opaa.llm.EmbeddingEndpoint;
import io.opaa.llm.ModelEndpointUri;
import io.opaa.security.SettingsEncryptor;
import io.opaa.security.TargetAddressValidator;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
  static final int DEFAULT_MAX_INPUT_TOKENS = EmbeddingEndpoint.DEFAULT_MAX_INPUT_TOKENS;

  /** Upper bound of an embedding input limit; no served embedding model takes more. */
  static final int MAX_INPUT_TOKENS = 1_000_000;

  static final String DEFAULT_PROVIDER_NAME = "Verzeichnisdienst";
  static final BigDecimal MAX_TEMPERATURE = new BigDecimal("2");

  private static final String INVALID = "INVALID";
  private static final String REQUIRED = "REQUIRED";
  private static final String TOO_LONG = "TOO_LONG";
  static final int MAX_ADDRESS = 500;
  static final int MAX_NAME = 200;
  static final int MAX_PROVIDER_NAME = 120;
  static final int MAX_API_KEY = 2000;

  /** A step as stored: values without secret, and the hash or ciphertext of its secret. */
  record Stored(String payload, String secret) {}

  /** {@code requirePasswordChange} is absent from steps saved before it existed: no change. */
  record FirstAdminPayload(String email, String displayName, Boolean requirePasswordChange) {
    FirstAdminPayload {
      requirePasswordChange = Boolean.TRUE.equals(requirePasswordChange);
    }
  }

  record SignInPayload(StepInput.SignInMode mode, StepInput.Oidc oidc) {}

  record ChatModelPayload(
      String baseUrl, String model, BigDecimal temperature, Integer maxTokens) {}

  /** {@code dimensions} is set by a successful probe embedding and cleared by every save. */
  record EmbeddingModelPayload(
      String baseUrl, String model, Integer maxInputTokens, Integer dimensions) {

    EmbeddingModelPayload withDimensions(int probed) {
      return new EmbeddingModelPayload(baseUrl, model, maxInputTokens, probed);
    }
  }

  private final PasswordPolicy passwordPolicy;
  private final PasswordEncoder passwordEncoder;
  private final SettingsEncryptor settingsEncryptor;
  private final OidcAddressPolicy addressPolicy;
  private final JsonMapper json;
  private final TargetAddressValidator modelAddresses;

  StepRules(
      PasswordPolicy passwordPolicy,
      PasswordEncoder passwordEncoder,
      SettingsEncryptor settingsEncryptor,
      OidcAddressPolicy addressPolicy,
      JsonMapper json,
      TargetAddressValidator modelAddresses) {
    this.passwordPolicy = passwordPolicy;
    this.passwordEncoder = passwordEncoder;
    this.settingsEncryptor = settingsEncryptor;
    this.addressPolicy = addressPolicy;
    this.json = json;
    this.modelAddresses = modelAddresses;
  }

  /**
   * Validates {@code input} and returns what is stored; {@code previousPayload} and {@code
   * previousSecret} are what was saved before. A chat model step without a new key keeps the saved
   * one only for an unchanged endpoint.
   *
   * @throws FieldValidationException naming every violated field
   * @throws ValidationException for an address the address check refuses
   */
  Stored toStored(StepInput input, String previousPayload, String previousSecret) {
    return switch (input) {
      case StepInput.FirstAdmin admin -> firstAdmin(admin);
      case StepInput.SignIn signIn -> signIn(signIn);
      case StepInput.ChatModel model -> chatModel(model, previousPayload, previousSecret);
      case StepInput.EmbeddingModel model -> embeddingModel(model, previousPayload, previousSecret);
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
      case EMBEDDING_MODEL -> {
        EmbeddingModelPayload model = embeddingModelPayload(payload);
        yield new InstallationOverview.EmbeddingModel(
            model.baseUrl(),
            model.model(),
            secret != null,
            maxInputTokens(model),
            model.dimensions());
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

  EmbeddingModelPayload embeddingModelPayload(String payload) {
    return json.readValue(payload, EmbeddingModelPayload.class);
  }

  /** {@code payload} with the vector size of a successful probe embedding. */
  String withProbedDimensions(String payload, int dimensions) {
    return json.writeValueAsString(embeddingModelPayload(payload).withDimensions(dimensions));
  }

  static int maxInputTokens(EmbeddingModelPayload model) {
    return model.maxInputTokens() == null ? DEFAULT_MAX_INPUT_TOKENS : model.maxInputTokens();
  }

  /** The endpoint of a saved embedding model step, its key decrypted. */
  EmbeddingEndpoint embeddingEndpoint(EmbeddingModelPayload model, String secret) {
    return new EmbeddingEndpoint(
        model.baseUrl(), model.model(), settingsEncryptor.decrypt(secret), maxInputTokens(model));
  }

  private Stored firstAdmin(StepInput.FirstAdmin input) {
    List<FieldError> errors = new ArrayList<>();
    String email = trimmed(input.email());
    String displayName = trimmed(input.displayName());
    if (email.isEmpty()) {
      errors.add(error("firstAdmin.email", REQUIRED, "Die E-Mail-Adresse fehlt."));
    } else {
      InitialAdministrator.addressProblem(email)
          .ifPresent(problem -> errors.add(error("firstAdmin.email", INVALID, problem)));
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
        json.writeValueAsString(
            new FirstAdminPayload(email, displayName, input.requirePasswordChange())),
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
    requireAtMost(errors, "signIn.oidc.issuerUri", issuer, MAX_ADDRESS);
    requireAtMost(errors, "signIn.oidc.clientId", clientId, MAX_NAME);
    requireAtMost(errors, "signIn.oidc.jwkSetUri", jwkSetUri, MAX_ADDRESS);
    requireAtMost(errors, "signIn.oidc.displayName", displayName, MAX_PROVIDER_NAME);
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

  private Stored chatModel(
      StepInput.ChatModel input, String previousPayload, String previousSecret) {
    List<FieldError> errors = new ArrayList<>();
    String baseUrl = trimmed(input.baseUrl());
    String model = trimmed(input.model());
    endpointErrors("chatModel", baseUrl, model, input.apiKey(), errors);
    if (input.maxTokens() != null && input.maxTokens() < 1) {
      errors.add(error("chatModel.maxTokens", INVALID, "Die Antwortlänge muss mindestens 1 sein."));
    }
    BigDecimal temperature = input.temperature();
    if (temperature != null
        && (temperature.signum() < 0 || temperature.compareTo(MAX_TEMPERATURE) > 0)) {
      errors.add(error("chatModel.temperature", INVALID, "Die Temperatur liegt zwischen 0 und 2."));
    }
    blockedAddressError("chatModel", baseUrl, errors);
    requireNoErrors(errors);
    String secret =
        secretOf(
            "chatModel",
            input.apiKey(),
            previousSecret == null ? null : chatModelPayload(previousPayload).baseUrl(),
            baseUrl,
            previousSecret);
    return new Stored(
        json.writeValueAsString(
            new ChatModelPayload(baseUrl, model, temperature, input.maxTokens())),
        secret);
  }

  private Stored embeddingModel(
      StepInput.EmbeddingModel input, String previousPayload, String previousSecret) {
    List<FieldError> errors = new ArrayList<>();
    String baseUrl = trimmed(input.baseUrl());
    String model = trimmed(input.model());
    endpointErrors("embeddingModel", baseUrl, model, input.apiKey(), errors);
    if (input.maxInputTokens() != null
        && (input.maxInputTokens() < 1 || input.maxInputTokens() > MAX_INPUT_TOKENS)) {
      errors.add(
          error(
              "embeddingModel.maxInputTokens",
              INVALID,
              "Die Eingabegrenze liegt zwischen 1 und " + MAX_INPUT_TOKENS + " Tokens."));
    }
    blockedAddressError("embeddingModel", baseUrl, errors);
    requireNoErrors(errors);
    String secret =
        secretOf(
            "embeddingModel",
            input.apiKey(),
            previousSecret == null ? null : embeddingModelPayload(previousPayload).baseUrl(),
            baseUrl,
            previousSecret);
    return new Stored(
        json.writeValueAsString(
            new EmbeddingModelPayload(baseUrl, model, input.maxInputTokens(), null)),
        secret);
  }

  /**
   * The rules of a model's address, identifier and key, lengths included, as field errors under
   * {@code prefix}.
   */
  private static void endpointErrors(
      String prefix, String baseUrl, String model, String apiKey, List<FieldError> errors) {
    if (baseUrl.isEmpty()) {
      errors.add(error(prefix + ".baseUrl", REQUIRED, "Die Basis-Adresse fehlt."));
    } else if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
      errors.add(
          error(
              prefix + ".baseUrl",
              INVALID,
              "Die Basis-Adresse muss mit http:// oder https:// beginnen."));
    } else if (ModelEndpointUri.containsCredentials(baseUrl)) {
      errors.add(
          error(
              prefix + ".baseUrl",
              INVALID,
              "Die Basis-Adresse darf keine Anmeldedaten enthalten; der Schlüssel gehört in das"
                  + " Feld Zugangsschlüssel."));
    }
    if (model.isEmpty()) {
      errors.add(error(prefix + ".model", REQUIRED, "Die Modell-Kennung fehlt."));
    }
    requireAtMost(errors, prefix + ".baseUrl", baseUrl, MAX_ADDRESS);
    requireAtMost(errors, prefix + ".model", model, MAX_NAME);
    requireAtMost(errors, prefix + ".apiKey", apiKey, MAX_API_KEY);
    if (apiKey != null && apiKey.strip().chars().anyMatch(c -> c < 0x20 || c == 0x7f)) {
      errors.add(
          error(
              prefix + ".apiKey",
              INVALID,
              "Der Zugangsschlüssel enthält Steuerzeichen wie einen Zeilenumbruch."));
    }
  }

  /** The target address check of a valid address, as a field error under {@code prefix}. */
  private void blockedAddressError(String prefix, String baseUrl, List<FieldError> errors) {
    if (errors.isEmpty()) {
      String blocked = blockedModelAddress(modelAddresses, baseUrl);
      if (blocked != null) {
        errors.add(error(prefix + ".baseUrl", "BLOCKED", blocked));
      }
    }
  }

  /**
   * The key to store: a new one encrypted, an empty one removed, none kept from {@code
   * previousSecret} only for an unchanged endpoint.
   */
  private String secretOf(
      String prefix, String apiKey, String previousBaseUrl, String baseUrl, String previousSecret) {
    if (apiKey == null) {
      if (previousSecret != null && !sameEndpoint(previousBaseUrl, baseUrl)) {
        throw FieldValidationException.of(
            prefix + ".apiKey",
            REQUIRED,
            "Die Basis-Adresse hat sich geändert: Bitte den Zugangsschlüssel erneut eingeben oder"
                + " das Feld leeren, um ihn zu entfernen.");
      }
      return previousSecret;
    }
    if (apiKey.isBlank()) {
      return null;
    }
    return encrypt(prefix, apiKey.strip());
  }

  /**
   * Why the target address check refuses {@code baseUrl}, or {@code null}: the installation runs
   * before any account exists, so its probes must not reach internal ranges unless the operator
   * released the host ({@link TargetAddressValidator#ALLOWLIST_HINT}).
   */
  static String blockedModelAddress(TargetAddressValidator validator, String baseUrl) {
    try {
      validator.validate(new URI(baseUrl.strip()));
      return null;
    } catch (URISyntaxException e) {
      return "Die Basis-Adresse ist keine gültige URL.";
    } catch (IOException e) {
      return e.getMessage() + " " + TargetAddressValidator.ALLOWLIST_HINT;
    }
  }

  /**
   * Whether a saved key may stay with {@code next}: scheme, host, port and path unchanged, so a key
   * never follows the address to another endpoint.
   */
  static boolean sameEndpoint(String previous, String next) {
    try {
      return endpointKey(previous).equals(endpointKey(next));
    } catch (URISyntaxException | NullPointerException e) {
      return false;
    }
  }

  private static String endpointKey(String baseUrl) throws URISyntaxException {
    URI uri = new URI(baseUrl.strip());
    String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
    int port = uri.getPort() != -1 ? uri.getPort() : "https".equals(scheme) ? 443 : 80;
    String path = uri.getPath() == null ? "" : uri.getPath();
    while (path.endsWith("/")) {
      path = path.substring(0, path.length() - 1);
    }
    return scheme + "://" + uri.getHost().toLowerCase(Locale.ROOT) + ":" + port + path;
  }

  private String encrypt(String prefix, String apiKey) {
    try {
      return settingsEncryptor.encrypt(apiKey);
    } catch (IllegalStateException e) {
      throw FieldValidationException.of(
          prefix + ".apiKey",
          INVALID,
          "Der Zugangsschlüssel kann nicht gespeichert werden: OPAA_SETTINGS_ENCRYPTION_KEY ist"
              + " nicht gesetzt oder ungültig.");
    }
  }

  /** The lengths of the specification ({@code maxLength}), enforced here for every way in. */
  private static void requireAtMost(List<FieldError> errors, String field, String value, int max) {
    if (value != null && value.length() > max) {
      errors.add(error(field, TOO_LONG, "Die Angabe ist zu lang (höchstens " + max + " Zeichen)."));
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
}
