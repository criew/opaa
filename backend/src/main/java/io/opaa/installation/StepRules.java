package io.opaa.installation;

import io.opaa.account.InitialAdministrator;
import io.opaa.account.PasswordPolicy;
import io.opaa.api.types.ColorScheme;
import io.opaa.api.types.MailEncryption;
import io.opaa.auth.OidcAddressPolicy;
import io.opaa.auth.OidcIssuerUris;
import io.opaa.branding.BrandingDefaults;
import io.opaa.branding.BrandingSettingsRules;
import io.opaa.common.FieldValidationException;
import io.opaa.common.FieldValidationException.FieldError;
import io.opaa.common.ValidationException;
import io.opaa.llm.EmbeddingEndpoint;
import io.opaa.llm.ModelAddressPolicy;
import io.opaa.llm.ModelEndpointRules;
import io.opaa.llm.TranscriptionEndpoint;
import io.opaa.mail.MailSettingsRules;
import io.opaa.mail.MailSettingsService;
import io.opaa.mail.MailSettingsSnapshot;
import io.opaa.mail.MailSettingsUpdate;
import io.opaa.security.SettingsEncryptor;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * The rules of each step (ADR-0060, Entscheidung 6): what a saved step must satisfy, how it is
 * stored and how it is read back. The password of the first account leaves this class only as a
 * hash, a model key and the mail password only encrypted; the payload JSON carries no secret.
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
  private static final String BRANDING_PREFIX = "branding.";
  private static final String RERANK_PREFIX = "rerankModel";
  private static final String TRANSCRIPTION_PREFIX = "transcriptionModel";

  /**
   * A host name or address: no whitespace and no control character in the Unicode sense (U+0085,
   * U+2028, U+2029 included), so no forged log line.
   */
  private static final Pattern MAIL_HOST = Pattern.compile("(?U)[^\\s\\p{Cc}\\p{Zl}\\p{Zp}]+");

  private static final String REQUIRED = "REQUIRED";
  private static final String TOO_LONG = "TOO_LONG";
  static final int MAX_ADDRESS = ModelEndpointRules.MAX_ADDRESS;
  static final int MAX_NAME = ModelEndpointRules.MAX_NAME;
  static final int MAX_PROVIDER_NAME = 120;
  static final int MAX_API_KEY = ModelEndpointRules.MAX_API_KEY;

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

  /** The rerank model without its key. */
  record RerankModelPayload(String baseUrl, String model) {}

  /** The transcription model; {@code language} is never {@code null}, empty for detection. */
  record TranscriptionModelPayload(String baseUrl, String model, String language) {}

  /** The mail server without its password; {@code encryption} is never {@code null}. */
  record MailPayload(
      String host,
      Integer port,
      MailEncryption encryption,
      String username,
      String fromAddress,
      String fromName) {}

  /** The branding as the administration stores it; {@code null} in a field means the default. */
  record BrandingPayload(
      String productName,
      String claim,
      String aiNotice,
      String primaryColor,
      ColorScheme defaultColorScheme) {}

  private final PasswordPolicy passwordPolicy;
  private final PasswordEncoder passwordEncoder;
  private final SettingsEncryptor settingsEncryptor;
  private final OidcAddressPolicy addressPolicy;
  private final JsonMapper json;
  private final ModelAddressPolicy modelAddresses;
  private final InstallationMailTargets mailTargets;

  StepRules(
      PasswordPolicy passwordPolicy,
      PasswordEncoder passwordEncoder,
      SettingsEncryptor settingsEncryptor,
      OidcAddressPolicy addressPolicy,
      JsonMapper json,
      ModelAddressPolicy modelAddresses,
      InstallationMailTargets mailTargets) {
    this.passwordPolicy = passwordPolicy;
    this.passwordEncoder = passwordEncoder;
    this.settingsEncryptor = settingsEncryptor;
    this.addressPolicy = addressPolicy;
    this.json = json;
    this.modelAddresses = modelAddresses;
    this.mailTargets = mailTargets;
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
      case StepInput.RerankModel model -> rerankModel(model, previousPayload, previousSecret);
      case StepInput.TranscriptionModel model ->
          transcriptionModel(model, previousPayload, previousSecret);
      case StepInput.Mail mail -> mail(mail, previousPayload, previousSecret);
      case StepInput.Branding branding -> branding(branding);
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
      case RERANK_MODEL -> {
        RerankModelPayload model = rerankModelPayload(payload);
        yield new InstallationOverview.RerankModel(model.baseUrl(), model.model(), secret != null);
      }
      case TRANSCRIPTION_MODEL -> {
        TranscriptionModelPayload model = transcriptionModelPayload(payload);
        yield new InstallationOverview.TranscriptionModel(
            model.baseUrl(), model.model(), secret != null, model.language());
      }
      case MAIL -> {
        MailPayload mail = mailPayload(payload);
        yield new InstallationOverview.Mail(
            mail.host(),
            mail.port(),
            mail.encryption(),
            mail.username(),
            secret != null,
            mail.fromAddress(),
            mail.fromName());
      }
      case BRANDING -> {
        BrandingPayload branding = brandingPayload(payload);
        yield new InstallationOverview.Branding(
            branding.productName(),
            branding.claim(),
            branding.aiNotice(),
            branding.primaryColor(),
            branding.defaultColorScheme());
      }
    };
  }

  BrandingPayload brandingPayload(String payload) {
    return json.readValue(payload, BrandingPayload.class);
  }

  /**
   * The problems the rules of the branding administration find in a saved branding step now, empty
   * when it passes; the payload is stored normalised, so a passing step stores unchanged.
   */
  List<FieldError> brandingErrors(BrandingPayload branding) {
    return BrandingSettingsRules.check(
            BRANDING_PREFIX,
            branding.productName(),
            branding.claim(),
            branding.aiNotice(),
            branding.primaryColor(),
            branding.defaultColorScheme())
        .errors();
  }

  MailPayload mailPayload(String payload) {
    return json.readValue(payload, MailPayload.class);
  }

  /** The saved mail server as the check connects with it, its password decrypted. */
  MailSettingsSnapshot mailSettings(MailPayload mail, String secret) {
    return new MailSettingsSnapshot(
        true,
        mail.host(),
        mail.port(),
        mail.username(),
        settingsEncryptor.decrypt(secret, InstallationEncryptedColumns.of(InstallationStep.MAIL)),
        mail.encryption(),
        mail.fromAddress(),
        mail.fromName());
  }

  /**
   * The saved mail server as the completion stores it, sending switched on; without a saved
   * password none is stored.
   */
  MailSettingsUpdate mailSettingsUpdate(MailPayload mail, String secret) {
    String password =
        settingsEncryptor.decrypt(secret, InstallationEncryptedColumns.of(InstallationStep.MAIL));
    return new MailSettingsUpdate(
        true,
        mail.host(),
        mail.port(),
        mail.username(),
        password == null ? "" : password,
        mail.encryption(),
        mail.fromAddress(),
        mail.fromName());
  }

  /** Why the mail server of a saved step is refused now, or {@code null}. */
  String blockedMailServer(MailPayload mail) {
    return mailTargets.blockedReason(mail.host(), mail.port());
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

  RerankModelPayload rerankModelPayload(String payload) {
    return json.readValue(payload, RerankModelPayload.class);
  }

  /**
   * The base address saved with a model step, the only one its saved key may be sent to.
   *
   * @throws IllegalArgumentException for a step without a model endpoint
   */
  String savedModelBaseUrl(InstallationStep step, String payload) {
    return switch (step) {
      case CHAT_MODEL -> chatModelPayload(payload).baseUrl();
      case EMBEDDING_MODEL -> embeddingModelPayload(payload).baseUrl();
      case RERANK_MODEL -> rerankModelPayload(payload).baseUrl();
      case TRANSCRIPTION_MODEL -> transcriptionModelPayload(payload).baseUrl();
      default -> throw new IllegalArgumentException("No model step: " + step);
    };
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
        model.baseUrl(),
        model.model(),
        apiKey(InstallationStep.EMBEDDING_MODEL, secret),
        maxInputTokens(model));
  }

  /**
   * The saved key of the model step {@code step} in clear, {@code null} without one.
   *
   * @throws IllegalStateException when it cannot be decrypted
   */
  String apiKey(InstallationStep step, String secret) {
    return settingsEncryptor.decrypt(secret, InstallationEncryptedColumns.of(step));
  }

  TranscriptionModelPayload transcriptionModelPayload(String payload) {
    return json.readValue(payload, TranscriptionModelPayload.class);
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
            InstallationStep.CHAT_MODEL,
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
            InstallationStep.EMBEDDING_MODEL,
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
   * The rules of the rerank model administration for a switched-on role ({@link
   * ModelEndpointRules}), with the address check of the installation for every address.
   */
  private Stored rerankModel(
      StepInput.RerankModel input, String previousPayload, String previousSecret) {
    List<FieldError> errors = new ArrayList<>();
    String baseUrl = trimmed(input.baseUrl());
    String model = trimmed(input.model());
    endpointErrors(RERANK_PREFIX, baseUrl, model, input.apiKey(), errors);
    blockedAddressError(RERANK_PREFIX, baseUrl, errors);
    requireNoErrors(errors);
    String secret =
        secretOf(
            InstallationStep.RERANK_MODEL,
            RERANK_PREFIX,
            input.apiKey(),
            previousSecret == null ? null : rerankModelPayload(previousPayload).baseUrl(),
            baseUrl,
            previousSecret);
    return new Stored(json.writeValueAsString(new RerankModelPayload(baseUrl, model)), secret);
  }

  /**
   * The rules of the transcription model administration (endpoint and {@link
   * TranscriptionEndpoint#isValidLanguage language}), with the address check of the installation
   * for every address. The language is stored as the role passes it on, {@code de} when absent.
   */
  private Stored transcriptionModel(
      StepInput.TranscriptionModel input, String previousPayload, String previousSecret) {
    List<FieldError> errors = new ArrayList<>();
    String baseUrl = trimmed(input.baseUrl());
    String model = trimmed(input.model());
    String language = TranscriptionEndpoint.normalisedLanguage(input.language());
    endpointErrors(TRANSCRIPTION_PREFIX, baseUrl, model, input.apiKey(), errors);
    if (!TranscriptionEndpoint.isValidLanguage(language)) {
      errors.add(
          error(TRANSCRIPTION_PREFIX + ".language", INVALID, TranscriptionEndpoint.LANGUAGE_RULE));
    }
    blockedAddressError(TRANSCRIPTION_PREFIX, baseUrl, errors);
    requireNoErrors(errors);
    String secret =
        secretOf(
            InstallationStep.TRANSCRIPTION_MODEL,
            TRANSCRIPTION_PREFIX,
            input.apiKey(),
            previousSecret == null ? null : transcriptionModelPayload(previousPayload).baseUrl(),
            baseUrl,
            previousSecret);
    return new Stored(
        json.writeValueAsString(
            new TranscriptionModelPayload(
                baseUrl,
                model,
                language == null ? TranscriptionEndpoint.DEFAULT_LANGUAGE : language)),
        secret);
  }

  /**
   * The rules of the mail settings of the administration ({@link MailSettingsRules}), with every
   * field the completion needs to switch sending on required, and the target address check of the
   * installation on top.
   */
  private Stored mail(StepInput.Mail input, String previousPayload, String previousSecret) {
    List<FieldError> errors =
        new ArrayList<>(
            MailSettingsRules.lengthErrors(
                "mail.",
                input.host(),
                input.username(),
                input.password(),
                input.fromAddress(),
                input.fromName()));
    String host = MailSettingsRules.trimmedOrNull(input.host());
    Integer port = input.port();
    String username = MailSettingsRules.trimmedOrNull(input.username());
    String fromAddress = MailSettingsRules.trimmedOrNull(input.fromAddress());
    String fromName = MailSettingsRules.trimmedOrNull(input.fromName());
    MailEncryption encryption =
        input.encryption() == null ? MailSettingsRules.DEFAULT_ENCRYPTION : input.encryption();
    if (host == null) {
      errors.add(error("mail.host", REQUIRED, MailSettingsRules.HOST_MISSING));
    } else if (!MAIL_HOST.matcher(host).matches()) {
      errors.add(
          error(
              "mail.host",
              INVALID,
              "Der SMTP-Server ist ein Rechnername oder eine Adresse, ohne Leer- und"
                  + " Steuerzeichen."));
    }
    if (MailSettingsService.PASSWORD_MASK.equals(input.password())) {
      errors.add(
          error(
              "mail.password",
              INVALID,
              "„"
                  + MailSettingsService.PASSWORD_MASK
                  + "“ steht in der Verwaltung für ein unverändertes Passwort und lässt sich nicht"
                  + " als Passwort setzen."));
    }
    if (port == null) {
      errors.add(error("mail.port", REQUIRED, MailSettingsRules.PORT_MISSING));
    } else {
      MailSettingsRules.portProblem(port)
          .ifPresent(problem -> errors.add(error("mail.port", INVALID, problem)));
    }
    if (fromAddress == null) {
      errors.add(error("mail.fromAddress", REQUIRED, MailSettingsRules.FROM_ADDRESS_MISSING));
    } else if (fromAddress.length() <= MailSettingsRules.MAX_FROM_ADDRESS_LENGTH) {
      // an overlong address already carries TOO_LONG from lengthErrors
      MailSettingsRules.fromAddressProblem(fromAddress)
          .ifPresent(problem -> errors.add(error("mail.fromAddress", INVALID, problem)));
    }
    if (errors.isEmpty()) {
      String blocked = mailTargets.blockedReason(host, port);
      if (blocked != null) {
        String field =
            InstallationMailTargets.PORT_REFUSED.equals(blocked) ? "mail.port" : "mail.host";
        errors.add(error(field, "BLOCKED", blocked));
      }
    }
    requireNoErrors(errors);
    MailPayload payload = new MailPayload(host, port, encryption, username, fromAddress, fromName);
    return new Stored(
        json.writeValueAsString(payload),
        mailSecret(input.password(), previousPayload, previousSecret, payload));
  }

  /**
   * The rules of the branding administration ({@link BrandingSettingsRules}), each problem named
   * under {@code branding.}; stored normalised, a field {@code null} for the OPAA default. The
   * default colour scheme is stored as "not set" too, so a step that only names it writes nothing.
   */
  private Stored branding(StepInput.Branding input) {
    BrandingSettingsRules.Checked checked =
        BrandingSettingsRules.check(
            BRANDING_PREFIX,
            input.productName(),
            input.claim(),
            input.aiNotice(),
            input.primaryColor(),
            input.defaultColorScheme());
    requireNoErrors(checked.errors());
    BrandingSettingsRules.Values values = checked.values();
    return new Stored(
        json.writeValueAsString(
            new BrandingPayload(
                values.productName(),
                values.claim(),
                values.aiNotice(),
                values.primaryColor(),
                values.defaultColorScheme() == BrandingDefaults.COLOR_SCHEME
                    ? null
                    : values.defaultColorScheme())),
        null);
  }

  /**
   * The mail password to store: a new one encrypted, an empty one removed, none kept from {@code
   * previousSecret} only for the same host, port, user name and encryption.
   */
  private String mailSecret(
      String password, String previousPayload, String previousSecret, MailPayload next) {
    if (password == null) {
      if (previousSecret == null) {
        return null;
      }
      MailPayload previous = mailPayload(previousPayload);
      if (!MailSettingsRules.sameTarget(
          previous.host(),
          previous.port(),
          previous.username(),
          previous.encryption(),
          next.host(),
          next.port(),
          next.username(),
          next.encryption())) {
        throw FieldValidationException.of(
            "mail.password",
            MailSettingsRules.PASSWORD_REQUIRED,
            MailSettingsRules.PASSWORD_FOLLOWS_TARGET);
      }
      return previousSecret;
    }
    if (password.isEmpty()) {
      return null;
    }
    try {
      return settingsEncryptor.encrypt(
          password, InstallationEncryptedColumns.of(InstallationStep.MAIL));
    } catch (IllegalStateException e) {
      throw FieldValidationException.of(
          "mail.password",
          INVALID,
          "Das Passwort kann nicht gespeichert werden: OPAA_SETTINGS_ENCRYPTION_KEY ist nicht"
              + " gesetzt oder ungültig.");
    }
  }

  /** The rules of a model's address, identifier and key ({@link ModelEndpointRules}). */
  private static void endpointErrors(
      String prefix, String baseUrl, String model, String apiKey, List<FieldError> errors) {
    ModelEndpointRules.endpointErrors(prefix, baseUrl, model, apiKey, true, errors);
  }

  /** The address check of a valid address, as a field error under {@code prefix}. */
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
      InstallationStep step,
      String prefix,
      String apiKey,
      String previousBaseUrl,
      String baseUrl,
      String previousSecret) {
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
    return encrypt(step, prefix, apiKey.strip());
  }

  /**
   * Why the address check of models refuses {@code baseUrl}, or {@code null}: the installation runs
   * before any account exists, so its probes must not reach internal ranges unless the operator
   * released the host, also for a step saved before.
   */
  static String blockedModelAddress(ModelAddressPolicy policy, String baseUrl) {
    return policy.blockedReason(baseUrl);
  }

  /** Whether a saved key may stay with {@code next} ({@link ModelEndpointRules#sameEndpoint}). */
  static boolean sameEndpoint(String previous, String next) {
    return ModelEndpointRules.sameEndpoint(previous, next);
  }

  private String encrypt(InstallationStep step, String prefix, String apiKey) {
    try {
      return settingsEncryptor.encrypt(apiKey, InstallationEncryptedColumns.of(step));
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
    ModelEndpointRules.requireAtMost(errors, field, value, max);
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
