package io.opaa.installation;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.opaa.common.ValidationException;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * The answer file read at start-up from {@code OPAA_INSTALLATION_ANSWER_FILE} (ADR-0060,
 * Entscheidung 7). Its secrets are never plain text: the password and the model key are files,
 * typically mounted from a secret, read when the file is applied. Parts of later steps are ignored.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record AnswerFile(
    FirstAdmin firstAdmin, SignIn signIn, ChatModel chatModel, EmbeddingModel embeddingModel) {

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
    if (firstAdmin == null || signIn == null || chatModel == null || embeddingModel == null) {
      throw new ValidationException(
          "Die Antwortdatei braucht die Angaben firstAdmin, signIn, chatModel und"
              + " embeddingModel.");
    }
    if (firstAdmin.password() != null
        || chatModel.apiKey() != null
        || embeddingModel.apiKey() != null) {
      throw new ValidationException(
          "Geheimnisse stehen nicht im Klartext in der Antwortdatei: passwordFile und apiKeyFile"
              + " verweisen auf eine Datei, etwa aus einem Secret.");
    }
    if (firstAdmin.passwordFile() == null || firstAdmin.passwordFile().isBlank()) {
      throw new ValidationException("firstAdmin.passwordFile fehlt in der Antwortdatei.");
    }
    String password = secret(firstAdmin.passwordFile(), "firstAdmin.passwordFile");
    String apiKey =
        chatModel.apiKeyFile() == null || chatModel.apiKeyFile().isBlank()
            ? ""
            : secret(chatModel.apiKeyFile(), "chatModel.apiKeyFile");
    String embeddingApiKey =
        embeddingModel.apiKeyFile() == null || embeddingModel.apiKeyFile().isBlank()
            ? ""
            : secret(embeddingModel.apiKeyFile(), "embeddingModel.apiKeyFile");
    StepInput.Oidc oidc =
        signIn.oidc() == null
            ? null
            : new StepInput.Oidc(
                signIn.oidc().issuerUri(),
                signIn.oidc().clientId(),
                signIn.oidc().jwkSetUri(),
                signIn.oidc().displayName());
    return List.of(
        new StepInput.FirstAdmin(
            firstAdmin.email(),
            firstAdmin.displayName(),
            password,
            !Boolean.FALSE.equals(firstAdmin.requirePasswordChange())),
        new StepInput.SignIn(signIn.mode(), oidc),
        new StepInput.ChatModel(
            chatModel.baseUrl(),
            chatModel.model(),
            apiKey,
            chatModel.temperature(),
            chatModel.maxTokens()),
        new StepInput.EmbeddingModel(
            embeddingModel.baseUrl(),
            embeddingModel.model(),
            embeddingApiKey,
            embeddingModel.maxInputTokens()));
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
