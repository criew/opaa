package io.opaa.installation;

import io.opaa.account.OidcProviderSeedRunner;
import io.opaa.auth.AuthProperties;
import io.opaa.auth.OidcProviderRegistry;
import io.opaa.common.ConflictException;
import io.opaa.common.FieldValidationException;
import io.opaa.common.ValidationException;
import io.opaa.installation.InstallationState.CompletionSource;
import io.opaa.llm.LlmModelSeedRunner;
import java.nio.file.Path;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * The start-up of the installation (ADR-0060, Entscheidungen 4, 5 and 7; ADR-0053: "Sperre"),
 * before the web server accepts a request. Every instance decides under the start-up lock: an
 * existing database, the {@code dev} mode or the start values {@code OPAA_INITIAL_ADMIN_*} of the
 * transition complete the installation at once; otherwise the answer file of {@code
 * OPAA_INSTALLATION_ANSWER_FILE} is applied, and if the installation is still pending the token is
 * stored and, when generated, printed exactly once. Only a completed installation runs the start-up
 * seeds, so a pending one takes nothing from the environment.
 */
@Component
class InstallationStartup implements SmartInitializingSingleton {

  private static final Logger log = LoggerFactory.getLogger(InstallationStartup.class);
  private static final String DEV_MODE = "dev";
  private static final String OIDC_MODE = "oidc";
  private static final String RULE =
      "=====================================================================";

  private final InstallationService installation;
  private final InstallationProperties properties;
  private final AuthProperties authProperties;
  private final DatabaseInstallationStatus status;
  private final OidcProviderSeedRunner identitySeeds;
  private final LlmModelSeedRunner chatModelSeed;
  private final OidcProviderRegistry providerRegistry;
  private final JsonMapper json;

  InstallationStartup(
      InstallationService installation,
      InstallationProperties properties,
      AuthProperties authProperties,
      DatabaseInstallationStatus status,
      OidcProviderSeedRunner identitySeeds,
      LlmModelSeedRunner chatModelSeed,
      OidcProviderRegistry providerRegistry,
      JsonMapper json) {
    this.installation = installation;
    this.properties = properties;
    this.authProperties = authProperties;
    this.status = status;
    this.identitySeeds = identitySeeds;
    this.chatModelSeed = chatModelSeed;
    this.providerRegistry = providerRegistry;
    this.json = json;
  }

  @Override
  public void afterSingletonsInstantiated() {
    boolean completed = decide();
    status.reload();
    if (completed) {
      identitySeeds.runStartupSeeds();
      chatModelSeed.runStartupSeed();
    } else {
      providerRegistry.refresh();
      log.warn(
          "OPAA is in installation mode: every API outside the installation answers 503 until"
              + " the installation is completed");
    }
  }

  /** Whether the installation is completed once this start has decided. */
  boolean decide() {
    boolean answerFile = properties.hasAnswerFile();
    String mode = authProperties.mode();
    boolean completed =
        installation.completeAtStartupIfDue(
            !answerFile && DEV_MODE.equals(mode), !answerFile && legacyStartValues(mode));
    if (completed) {
      if (answerFile) {
        log.info(
            "{} is ignored: the installation is already completed",
            InstallationProperties.ANSWER_FILE_VARIABLE);
      }
      return true;
    }
    if (answerFile && applyAnswerFile()) {
      return true;
    }
    String generated = installation.ensureToken(properties.token());
    if (generated != null) {
      printTokenBlock(generated);
    }
    return false;
  }

  /**
   * The start values of the transition: {@code OPAA_INITIAL_ADMIN_EMAIL} set in the {@code oidc}
   * mode takes the installation over from the environment, as before the installation mode.
   */
  private boolean legacyStartValues(String mode) {
    String email = authProperties.initialAdminEmail();
    if (!OIDC_MODE.equals(mode) || email == null || email.isBlank()) {
      return false;
    }
    log.warn(
        "OPAA_INITIAL_ADMIN_EMAIL is set: a pending installation is completed from the start"
            + " values OPAA_INITIAL_ADMIN_*, a deprecated way; install with an answer file ({})"
            + " instead",
        InstallationProperties.ANSWER_FILE_VARIABLE);
    return true;
  }

  /** Applies the answer file; a refusal is logged with step and reason and leaves it pending. */
  private boolean applyAnswerFile() {
    Path file = Path.of(properties.answerFile());
    try {
      AnswerFile answers = AnswerFile.read(file, json);
      installation.applyAnswers(answers.steps(), CompletionSource.ANSWER_FILE_STARTUP);
      log.info("Installation completed from the answer file {}", file);
      return true;
    } catch (FieldValidationException e) {
      log.error(
          "The answer file {} was not applied, the installation stays pending: {}",
          file,
          e.fieldErrors().stream()
              .map(error -> error.field() + ": " + error.message())
              .collect(Collectors.joining("; ")));
    } catch (ValidationException | ConflictException e) {
      log.error(
          "The answer file {} was not applied, the installation stays pending: {}",
          file,
          e.getMessage());
    }
    return false;
  }

  /**
   * The one place the installation token reaches the log (ADR-0060, Entscheidung 5): one WARN event
   * after the commit that stored its hash, on the one instance that generated it.
   */
  private static void printTokenBlock(String token) {
    String block =
        String.join(
            System.lineSeparator(),
            "",
            RULE,
            "  OPAA INSTALLATION TOKEN - PRINTED ONCE",
            "  Token:  " + token,
            "  Use it as \"Authorization: Bearer <token>\" for /api/v1/installation.",
            "  It is not printed again; set " + InstallationProperties.TOKEN_VARIABLE,
            "  and restart to replace a lost one.",
            RULE);
    log.warn("{}", block);
  }
}
