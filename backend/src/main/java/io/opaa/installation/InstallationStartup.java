package io.opaa.installation;

import io.opaa.account.LocalAdminSeeder;
import io.opaa.account.OidcProviderSeedRunner;
import io.opaa.auth.AuthProperties;
import io.opaa.auth.OidcProviderRegistry;
import io.opaa.common.ConflictException;
import io.opaa.common.FieldValidationException;
import io.opaa.common.ValidationException;
import io.opaa.installation.InstallationState.CompletionSource;
import io.opaa.instance.CurrentInstance;
import io.opaa.llm.EmbeddingModelService;
import io.opaa.llm.LlmModelSeedRunner;
import io.opaa.ratelimit.RateLimitProperties;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * The start-up of the installation (ADR-0060; ADR-0053: "Sperre"): after the instance is admitted
 * to the register and before the web server starts, it completes a due installation, applies the
 * answer file or stores the token, printing a generated one once. Only a completed installation
 * runs the start-up seeds and takes over the embedding model of an older installation, before the
 * index reconciliation of the next phase.
 */
@Component
class InstallationStartup implements SmartLifecycle {

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
  private final EmbeddingModelService embeddingModels;
  private final OidcProviderRegistry providerRegistry;
  private final JsonMapper json;
  private final List<String> trustedProxyCidrs;
  private volatile boolean running;

  InstallationStartup(
      InstallationService installation,
      InstallationProperties properties,
      AuthProperties authProperties,
      DatabaseInstallationStatus status,
      OidcProviderSeedRunner identitySeeds,
      LlmModelSeedRunner chatModelSeed,
      EmbeddingModelService embeddingModels,
      OidcProviderRegistry providerRegistry,
      JsonMapper json,
      RateLimitProperties rateLimit) {
    this.installation = installation;
    this.properties = properties;
    this.authProperties = authProperties;
    this.status = status;
    this.identitySeeds = identitySeeds;
    this.chatModelSeed = chatModelSeed;
    this.embeddingModels = embeddingModels;
    this.providerRegistry = providerRegistry;
    this.json = json;
    this.trustedProxyCidrs = rateLimit.trustedProxyCidrs();
  }

  @Override
  public void start() {
    run();
    running = true;
  }

  @Override
  public void stop() {
    running = false;
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  @Override
  public int getPhase() {
    return CurrentInstance.REGISTERED_PHASE;
  }

  void run() {
    boolean decided = decide();
    status.reload();
    boolean completed = decided || !status.isPending();
    if (completed) {
      identitySeeds.runStartupSeeds();
      chatModelSeed.runStartupSeed();
      embeddingModels.adoptIfMissing();
    } else {
      providerRegistry.refresh();
      log.warn(
          "OPAA is in installation mode: every API outside the installation answers 503 until"
              + " the installation is completed");
      if (!properties.allowedCidrs().isEmpty() && trustedProxyCidrs.isEmpty()) {
        log.warn(
            "{} is set but {} is empty: behind a reverse proxy every request carries the proxy's"
                + " address, which then decides the network check and shares one budget",
            InstallationProperties.ALLOWED_CIDRS_VARIABLE,
            RateLimitProperties.TRUSTED_PROXY_CIDRS_VARIABLE);
      }
    }
  }

  /** Whether the installation is completed once this start has decided. */
  boolean decide() {
    boolean answerFile = properties.hasAnswerFile();
    String mode = authProperties.mode();
    InstallationService.StartupDecision decision =
        installation.completeAtStartupIfDue(
            !answerFile && DEV_MODE.equals(mode), !answerFile && legacyStartValues(mode));
    if (decision.source() == CompletionSource.LEGACY_START_VALUES) {
      log.warn(
          "The installation was completed from the start values OPAA_INITIAL_ADMIN_*, a"
              + " deprecated way; install with an answer file ({}) instead",
          InstallationProperties.ANSWER_FILE_VARIABLE);
    }
    if (decision.completed()) {
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
   * The start values of the transition: a deliverable {@code OPAA_INITIAL_ADMIN_EMAIL} in the
   * {@code oidc} mode completes a pending installation from the environment; a refused address
   * leaves it pending, so nobody ends up with an installation no one can sign in to.
   */
  private boolean legacyStartValues(String mode) {
    String email = authProperties.initialAdminEmail();
    if (!OIDC_MODE.equals(mode) || email == null || email.isBlank()) {
      return false;
    }
    if (!LocalAdminSeeder.isDeliverableAddress(email)) {
      log.error(
          "OPAA_INITIAL_ADMIN_EMAIL is no deliverable address (admin@opaa.local and values"
              + " without @ are refused): the installation stays pending");
      return false;
    }
    return true;
  }

  /** Applies the answer file; a refusal is logged with step and reason and leaves it pending. */
  boolean applyAnswerFile() {
    Path file = Path.of(properties.answerFile());
    try {
      AnswerFile answers = AnswerFile.read(file, json);
      if (installation.applyAnswersAtStartup(answers.steps())) {
        log.info("Installation completed from the answer file {}", file);
      } else {
        log.info("The installation was completed by another instance; {} is ignored", file);
      }
      return true;
    } catch (FieldValidationException e) {
      log.error(
          "The answer file {} was not applied{}: {}",
          file,
          pendingNote(),
          e.fieldErrors().stream()
              .map(error -> error.field() + ": " + error.message())
              .collect(Collectors.joining("; ")));
    } catch (ValidationException | ConflictException e) {
      log.error("The answer file {} was not applied{}: {}", file, pendingNote(), e.getMessage());
    }
    return false;
  }

  /**
   * Says the installation stays pending only when it does; another instance may have completed it.
   */
  private String pendingNote() {
    status.reload();
    return status.isPending() ? ", the installation stays pending" : "";
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
