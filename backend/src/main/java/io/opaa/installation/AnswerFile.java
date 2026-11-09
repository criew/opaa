package io.opaa.installation;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.opaa.api.types.MailEncryption;
import io.opaa.api.types.MalwareScanUnavailablePolicy;
import io.opaa.api.types.TextRecognitionConnectionType;
import io.opaa.common.ValidationException;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * The answer file read at start-up from {@code OPAA_INSTALLATION_ANSWER_FILE} (ADR-0060,
 * Entscheidung 7). Its secrets are never plain text: the passwords and the model keys are files,
 * typically mounted from a secret, read when the file is applied. {@code chatModel}, {@code
 * embeddingModel}, {@code rerankModel}, {@code transcriptionModel}, {@code textRecognitionModel},
 * {@code malwareScan}, {@code mail} and {@code branding} are optional: a file without one skips its
 * step, without {@code malwareScan} the malware scan stays off, without {@code rerankModel}
 * reranking stays off, without {@code transcriptionModel} speech input stays off, without {@code
 * textRecognitionModel} text recognition stays off, without {@code mail} sending stays off, without
 * {@code branding} the OPAA default stays. Parts of later steps are ignored, and so are {@code
 * transcriptionModel} while speech input is switched off and {@code textRecognitionModel} while the
 * operation has not released text recognition ({@link OfferedSteps}).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record AnswerFile(
    FirstAdmin firstAdmin,
    SignIn signIn,
    ChatModel chatModel,
    EmbeddingModel embeddingModel,
    RerankModel rerankModel,
    TranscriptionModel transcriptionModel,
    TextRecognitionModel textRecognitionModel,
    MalwareScan malwareScan,
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

  /** The rerank model; without {@code apiKeyFile} it is called without a key. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record RerankModel(String baseUrl, String model, String apiKeyFile, String apiKey) {}

  /** {@code language} absent means {@code de}, empty lets the model detect it. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record TranscriptionModel(
      String baseUrl, String model, String apiKeyFile, String apiKey, String language) {}

  /**
   * The text recognition model; {@code connectionType} absent means docling-serve, {@code model} is
   * read for a vision model only, and without {@code apiKeyFile} it is called without a key.
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record TextRecognitionModel(
      TextRecognitionConnectionType connectionType,
      String baseUrl,
      String model,
      String apiKeyFile,
      String apiKey) {}

  /** The clamd daemon of the malware scan; no secret, so it stands in the file as it is. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record MalwareScan(
      String host,
      Integer port,
      Integer timeoutSeconds,
      MalwareScanUnavailablePolicy onUnavailable) {}

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
  record Branding(String productName, String claim, String aiNotice, String primaryColor) {}

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

  /**
   * The steps of the file, with the secrets read from their files. A part of a step this
   * installation does not offer ({@code offered} false) is left out before its secret file is read,
   * so a missing file there does not refuse the answers.
   */
  List<StepInput> steps(Predicate<InstallationStep> offered) {
    if (firstAdmin == null || signIn == null) {
      throw new ValidationException("Die Antwortdatei braucht die Angaben firstAdmin und signIn.");
    }
    if (firstAdmin.password() != null
        || (chatModel != null && chatModel.apiKey() != null)
        || (embeddingModel != null && embeddingModel.apiKey() != null)
        || (rerankModel != null && rerankModel.apiKey() != null)
        || (transcriptionModel != null && transcriptionModel.apiKey() != null)
        || (textRecognitionModel != null && textRecognitionModel.apiKey() != null)
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
    if (rerankModel != null) {
      steps.add(
          new StepInput.RerankModel(
              rerankModel.baseUrl(),
              rerankModel.model(),
              keyOf(rerankModel.apiKeyFile(), "rerankModel.apiKeyFile")));
    }
    if (transcriptionModel != null && offered.test(InstallationStep.TRANSCRIPTION_MODEL)) {
      steps.add(
          new StepInput.TranscriptionModel(
              transcriptionModel.baseUrl(),
              transcriptionModel.model(),
              keyOf(transcriptionModel.apiKeyFile(), "transcriptionModel.apiKeyFile"),
              transcriptionModel.language()));
    }
    if (textRecognitionModel != null && offered.test(InstallationStep.TEXT_RECOGNITION_MODEL)) {
      steps.add(
          new StepInput.TextRecognitionModel(
              textRecognitionModel.connectionType(),
              textRecognitionModel.baseUrl(),
              textRecognitionModel.model(),
              keyOf(textRecognitionModel.apiKeyFile(), "textRecognitionModel.apiKeyFile")));
    }
    if (malwareScan != null) {
      steps.add(
          new StepInput.MalwareScan(
              malwareScan.host(),
              malwareScan.port(),
              malwareScan.timeoutSeconds(),
              malwareScan.onUnavailable()));
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
              branding.primaryColor()));
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
