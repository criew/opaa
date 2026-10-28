package io.opaa.installation;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.opaa.api.types.ColorScheme;
import io.opaa.api.types.MailEncryption;
import io.opaa.common.ValidationException;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * The answer file read at start-up from {@code OPAA_INSTALLATION_ANSWER_FILE} (ADR-0060,
 * Entscheidung 7). Its secrets are never plain text: the passwords and the model keys are files,
 * typically mounted from a secret, read when the file is applied. {@code chatModel}, {@code
 * embeddingModel}, {@code mail} and {@code branding} are optional: a file without one skips its
 * step, without {@code mail} sending stays off, without {@code branding} the OPAA default stays.
 * Parts of later steps are ignored.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record AnswerFile(
    FirstAdmin firstAdmin,
    SignIn signIn,
    ChatModel chatModel,
    EmbeddingModel embeddingModel,
    Mail mail,
    Branding branding) {

  /** Upper bound of a secret file; a password or a key is far shorter. */
  static final long MAX_SECRET_BYTES = 8 * 1024;

  /** Upper bound of the answer file itself. */
  static final long MAX_FILE_BYTES = 64 * 1024;

  /** {@code requirePasswordChange} absent means {@code true}: the file's password is one-off. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record FirstAdmin(
      String email,
      String displayName,
      String passwordFile,
      String password,
      Boolean requirePasswordChange) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record SignIn(StepInput.SignInMode mode, Oidc oidc) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record Oidc(String issuerUri, String clientId, String jwkSetUri, String displayName) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record ChatModel(
      String baseUrl,
      String model,
      String apiKeyFile,
      String apiKey,
      BigDecimal temperature,
      Integer maxTokens) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record EmbeddingModel(
      String baseUrl, String model, String apiKeyFile, String apiKey, Integer maxInputTokens) {}

  /** The mail server; without {@code passwordFile} it connects without a password. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record Mail(
      String host,
      Integer port,
      MailEncryption encryption,
      String username,
      String passwordFile,
      String password,
      String fromAddress,
      String fromName) {}

  /** The branding without its images; no secret, so it stands in the file as it is. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record Branding(
      String productName,
      String claim,
      String aiNotice,
      String primaryColor,
      ColorScheme defaultColorScheme) {}

  /** Reads and parses the file; the message of a failure names the file, never its content. */
  static AnswerFile read(Path file, JsonMapper json) {
    String text = readBounded(file, MAX_FILE_BYTES, "Die Antwortdatei");
    try {
      AnswerFile answers = json.readValue(text, AnswerFile.class);
      if (answers == null) {
        throw new ValidationException("Die Antwortdatei " + file + " ist leer.");
      }
      return answers;
    } catch (JacksonException e) {
      throw new ValidationException(
          "Die Antwortdatei " + file + " ist kein gültiges JSON der Installation.");
    }
  }

  /** The steps of the file, with the secrets read from their files. */
  List<StepInput> steps() {
    if (firstAdmin == null || signIn == null) {
      throw new ValidationException("Die Antwortdatei braucht die Angaben firstAdmin und signIn.");
    }
    if (firstAdmin.password() != null
        || (chatModel != null && chatModel.apiKey() != null)
        || (embeddingModel != null && embeddingModel.apiKey() != null)
        || (mail != null && mail.password() != null)) {
      throw new ValidationException(
          "Geheimnisse stehen nicht im Klartext in der Antwortdatei: passwordFile und apiKeyFile"
              + " verweisen auf eine Datei, etwa aus einem Secret.");
    }
    if (firstAdmin.passwordFile() == null || firstAdmin.passwordFile().isBlank()) {
      throw new ValidationException("firstAdmin.passwordFile fehlt in der Antwortdatei.");
    }
    String password = secret(firstAdmin.passwordFile(), "firstAdmin.passwordFile");
    StepInput.Oidc oidc =
        signIn.oidc() == null
            ? null
            : new StepInput.Oidc(
                signIn.oidc().issuerUri(),
                signIn.oidc().clientId(),
                signIn.oidc().jwkSetUri(),
                signIn.oidc().displayName());
    List<StepInput> steps = new ArrayList<>();
    steps.add(
        new StepInput.FirstAdmin(
            firstAdmin.email(),
            firstAdmin.displayName(),
            password,
            !Boolean.FALSE.equals(firstAdmin.requirePasswordChange())));
    steps.add(new StepInput.SignIn(signIn.mode(), oidc));
    if (chatModel != null) {
      steps.add(
          new StepInput.ChatModel(
              chatModel.baseUrl(),
              chatModel.model(),
              keyOf(chatModel.apiKeyFile(), "chatModel.apiKeyFile"),
              chatModel.temperature(),
              chatModel.maxTokens()));
    }
    if (embeddingModel != null) {
      steps.add(
          new StepInput.EmbeddingModel(
              embeddingModel.baseUrl(),
              embeddingModel.model(),
              keyOf(embeddingModel.apiKeyFile(), "embeddingModel.apiKeyFile"),
              embeddingModel.maxInputTokens()));
    }
    if (mail != null) {
      steps.add(
          new StepInput.Mail(
              mail.host(),
              mail.port(),
              mail.encryption(),
              mail.username(),
              keyOf(mail.passwordFile(), "mail.passwordFile"),
              mail.fromAddress(),
              mail.fromName()));
    }
    if (branding != null) {
      steps.add(
          new StepInput.Branding(
              branding.productName(),
              branding.claim(),
              branding.aiNotice(),
              branding.primaryColor(),
              branding.defaultColorScheme()));
    }
    return List.copyOf(steps);
  }

  /** The secret from the file at {@code path}; an absent path sends none. */
  private static String keyOf(String path, String field) {
    return path == null || path.isBlank() ? "" : secret(path, field);
  }

  /** The file's content without the trailing line break a mounted secret usually carries. */
  private static String secret(String path, String field) {
    String value = readBounded(Path.of(path.trim()), MAX_SECRET_BYTES, field).strip();
    if (value.isEmpty()) {
      throw new ValidationException("Die Datei aus " + field + " ist leer.");
    }
    return value;
  }

  private static String readBounded(Path file, long maxBytes, String what) {
    try {
      if (!Files.isRegularFile(file) || !Files.isReadable(file)) {
        throw new ValidationException(what + " (" + file + ") ist keine lesbare Datei.");
      }
      if (Files.size(file) > maxBytes) {
        throw new ValidationException(what + " (" + file + ") ist zu groß.");
      }
      return Files.readString(file, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new ValidationException(what + " (" + file + ") ist nicht lesbar.");
    }
  }
}
