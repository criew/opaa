package io.opaa.installation;

import io.opaa.account.InitialAdministrator;
import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.audit.AuditEvent;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.OidcClaimMapping;
import io.opaa.auth.UserRepository;
import io.opaa.common.ConflictException;
import io.opaa.common.NotFoundException;
import io.opaa.common.TransactionCallbacks;
import io.opaa.directory.OidcProviderDraft;
import io.opaa.directory.OidcProviderService;
import io.opaa.installation.InstallationState.CompletionSource;
import io.opaa.installation.InstallationState.TokenSource;
import io.opaa.llm.EmbeddingModelService;
import io.opaa.llm.LlmModelService;
import io.opaa.llm.ModelEndpointUri;
import io.opaa.organization.Organization;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The installation of a fresh instance (ADR-0060). Every write takes the row lock of {@code
 * installation_state}, then the start-up lock {@code 211}, in that order everywhere, so steps,
 * checks, completion and the emergency reset of all instances run one after another. The completion
 * succeeds once; every later call answers {@link NotFoundException}.
 */
@Service
public class InstallationService {

  /** The system process every audit entry of the installation is recorded under. */
  public static final String SYSTEM_ACTOR = "installation";

  static final UUID OBJECT_ID =
      UUID.nameUUIDFromBytes("opaa-installation".getBytes(StandardCharsets.UTF_8));
  static final String OBJECT_LABEL = "Installation";
  static final String CHAT_MODEL_DISPLAY_NAME = "Chat-Modell aus der Installation";

  /**
   * The chat model the {@code dev} mode installs itself with (ADR-0060): the model address of the
   * profile, a fixed model, no key and no target address check. Only the {@code dev} mode reaches
   * it, and {@code io.opaa.auth.AuthProfileGuard} keeps that mode to development and tests.
   */
  static final String DEVELOPMENT_CHAT_MODEL_DISPLAY_NAME = "Chat-Modell der Entwicklung";

  static final String DEVELOPMENT_CHAT_MODEL = "phi3:mini";
  static final int DEVELOPMENT_CHAT_MAX_TOKENS = 8000;
  static final String DEVELOPMENT_CHAT_BASE_URL_PROPERTY = "spring.ai.openai.base-url";

  private static final Logger log = LoggerFactory.getLogger(InstallationService.class);

  private final InstallationStateRepository states;
  private final InstallationStepRepository steps;
  private final StepRules rules;
  private final StepChecks checks;
  private final InitialAdministrator initialAdministrator;
  private final OidcProviderService oidcProviders;
  private final LlmModelService llmModels;
  private final EmbeddingModelService embeddingModels;
  private final AuditEventRecorder audit;
  private final UserRepository users;
  private final DatabaseInstallationStatus status;
  private final Clock clock;
  private final Environment environment;
  private final TransactionTemplate transactions;

  InstallationService(
      InstallationStateRepository states,
      InstallationStepRepository steps,
      StepRules rules,
      StepChecks checks,
      InitialAdministrator initialAdministrator,
      OidcProviderService oidcProviders,
      LlmModelService llmModels,
      EmbeddingModelService embeddingModels,
      AuditEventRecorder audit,
      UserRepository users,
      DatabaseInstallationStatus status,
      Clock clock,
      Environment environment,
      PlatformTransactionManager transactionManager) {
    this.states = states;
    this.steps = steps;
    this.rules = rules;
    this.checks = checks;
    this.initialAdministrator = initialAdministrator;
    this.oidcProviders = oidcProviders;
    this.llmModels = llmModels;
    this.embeddingModels = embeddingModels;
    this.audit = audit;
    this.users = users;
    this.status = status;
    this.clock = clock;
    this.environment = environment;
    this.transactions = new TransactionTemplate(transactionManager);
  }

  /** Whether {@code presented} is the token of the pending installation, in constant time. */
  @Transactional(readOnly = true)
  public boolean tokenMatches(String presented) {
    return states
        .findById(InstallationState.SINGLETON_ID)
        .filter(state -> !state.isCompleted())
        .map(state -> InstallationTokens.matches(presented, state.getTokenHash()))
        .orElse(false);
  }

  /**
   * @throws NotFoundException once the installation is completed
   */
  @Transactional(readOnly = true)
  public InstallationOverview overview() {
    if (states.isCompleted()) {
      throw notFound();
    }
    return overviewOfSavedSteps();
  }

  /** Saves the values of one step and discards an earlier check of it. */
  @Transactional
  public InstallationOverview.StepState save(StepInput input) {
    lockPending();
    return state(saveLocked(input));
  }

  /**
   * Runs the check of a saved step and stores its outcome. The check talks to the network, so it
   * runs outside every transaction and lock; only its outcome is stored under them, and only for
   * the values it checked.
   *
   * @throws ConflictException when the step is not saved, or was saved anew during the check
   */
  public InstallationOverview.StepState check(InstallationStep step) {
    Saved saved =
        transactions.execute(
            status -> {
              lockPending();
              return steps
                  .findById(step)
                  .map(record -> new Saved(record.getPayload(), record.getSecret()))
                  .orElseThrow(() -> notSaved(step));
            });
    StepChecks.Outcome outcome = checks.check(step, saved.payload(), saved.secret());
    return transactions.execute(
        status -> {
          lockPending();
          InstallationStepRecord record =
              steps.findForUpdate(step).orElseThrow(() -> notSaved(step));
          if (!saved.matches(record)) {
            throw new ConflictException(
                "Der Schritt "
                    + step.name()
                    + " wurde während der Prüfung neu gespeichert; bitte erneut prüfen.");
          }
          return state(recordOutcome(record, outcome));
        });
  }

  /**
   * Completes the installation from its checked steps.
   *
   * @throws ConflictException when a mandatory step is not saved or not successfully checked
   */
  @Transactional
  public InstallationOverview complete(CompletionSource source) {
    InstallationState state = lockPending();
    return completeLocked(state, source);
  }

  /**
   * Saves, checks and completes every step of an answer file, all or nothing. Validation and checks
   * run before any lock is taken; saving and completing run in one transaction.
   *
   * @throws ConflictException naming the step whose check failed; nothing is saved then
   */
  public InstallationOverview applyAnswers(List<StepInput> inputs, CompletionSource source) {
    List<Checked> checked = checkAnswers(inputs);
    return transactions.execute(
        status -> {
          InstallationState state = lockPending();
          storeAnswers(checked);
          return completeLocked(state, source);
        });
  }

  /** What a start found: whether the installation is completed, and by which source if just now. */
  public record StartupDecision(boolean completed, CompletionSource source) {}

  /**
   * Completes a pending installation that needs no step: a database in use (an account or a chat
   * model exists), or the {@code dev} mode, which installs its chat model itself.
   */
  @Transactional
  public StartupDecision completeAtStartupIfDue(boolean developmentMode) {
    InstallationState state = lockState();
    if (state.isCompleted()) {
      return new StartupDecision(true, null);
    }
    CompletionSource source = null;
    if (databaseInUse()) {
      source = CompletionSource.EXISTING;
    } else if (developmentMode) {
      source = CompletionSource.DEVELOPMENT;
      installDevelopmentChatModel();
    }
    if (source == null) {
      return new StartupDecision(false, null);
    }
    markCompleted(state, source, Map.of("source", source.name()));
    return new StartupDecision(true, source);
  }

  /**
   * {@link #applyAnswers} for the start: an installation another instance completed meanwhile is
   * success, not an error. Returns whether this call completed it.
   */
  public boolean applyAnswersAtStartup(List<StepInput> inputs) {
    if (Boolean.TRUE.equals(transactions.execute(status -> states.isCompleted()))) {
      return false;
    }
    List<Checked> checked = checkAnswers(inputs);
    return Boolean.TRUE.equals(
        transactions.execute(
            status -> {
              InstallationState state = lockState();
              if (state.isCompleted()) {
                return false;
              }
              storeAnswers(checked);
              completeLocked(state, CompletionSource.ANSWER_FILE_STARTUP);
              return true;
            }));
  }

  /** The values of a saved step as a check read them. */
  private record Saved(String payload, String secret) {

    boolean matches(InstallationStepRecord record) {
      return Objects.equals(payload, record.getPayload())
          && Objects.equals(secret, record.getSecret());
    }
  }

  /** One answer, validated as it will be stored, with the outcome of its check. */
  private record Checked(
      InstallationStep step, StepRules.Stored stored, StepChecks.Outcome outcome) {}

  /**
   * Validates every answer against what is saved and checks it, without any lock.
   *
   * @throws ConflictException naming the first step whose check failed
   */
  private List<Checked> checkAnswers(List<StepInput> inputs) {
    Map<InstallationStep, Saved> previous =
        transactions.execute(
            status -> {
              Map<InstallationStep, Saved> saved = new EnumMap<>(InstallationStep.class);
              steps
                  .findAll()
                  .forEach(
                      record ->
                          saved.put(
                              record.getStep(),
                              new Saved(record.getPayload(), record.getSecret())));
              return saved;
            });
    List<StepRules.Stored> stored =
        inputs.stream()
            .map(
                input -> {
                  Saved before =
                      Optional.ofNullable(previous.get(input.step())).orElse(new Saved(null, null));
                  return rules.toStored(input, before.payload(), before.secret());
                })
            .toList();
    List<Checked> checked = new java.util.ArrayList<>();
    for (int i = 0; i < inputs.size(); i++) {
      InstallationStep step = inputs.get(i).step();
      StepChecks.Outcome outcome =
          checks.check(step, stored.get(i).payload(), stored.get(i).secret());
      if (!outcome.succeeded()) {
        throw new ConflictException(
            "Schritt " + step.name() + " der Antwortdatei: " + outcome.message());
      }
      checked.add(new Checked(step, stored.get(i), outcome));
    }
    return checked;
  }

  /** Saves the checked answers with their outcomes; the caller holds the locks. */
  private void storeAnswers(List<Checked> checked) {
    for (Checked answer : checked) {
      recordOutcome(storeLocked(answer.step(), answer.stored()), answer.outcome());
    }
  }

  /**
   * Keeps the stored token in line with the configuration and returns a freshly generated one to
   * print after the commit, else {@code null}. A configured token no longer configured is replaced.
   */
  @Transactional
  public String ensureToken(String configuredToken) {
    InstallationState state = lockState();
    if (state.isCompleted()) {
      return null;
    }
    Instant now = clock.instant();
    if (configuredToken != null) {
      String hash = InstallationTokens.hash(configuredToken);
      if (!hash.equals(state.getTokenHash())) {
        state.storeToken(hash, TokenSource.CONFIGURED, now);
        states.save(state);
      }
      return null;
    }
    if (state.getTokenHash() != null && state.getTokenSource() == TokenSource.GENERATED) {
      return null;
    }
    if (state.getTokenSource() == TokenSource.CONFIGURED) {
      log.warn(
          "The configured installation token is replaced: this instance starts without"
              + " OPAA_INSTALLATION_TOKEN. Set it on all instances with the same value, or on none");
    }
    String token = InstallationTokens.generate();
    state.storeToken(InstallationTokens.hash(token), TokenSource.GENERATED, now);
    states.save(state);
    return token;
  }

  private InstallationStepRecord saveLocked(StepInput input) {
    InstallationStepRecord current = steps.findForUpdate(input.step()).orElse(null);
    StepRules.Stored stored =
        rules.toStored(
            input,
            current == null ? null : current.getPayload(),
            current == null ? null : current.getSecret());
    return storeLocked(input.step(), stored);
  }

  private InstallationStepRecord storeLocked(InstallationStep step, StepRules.Stored stored) {
    InstallationStepRecord record =
        steps.findForUpdate(step).orElseGet(() -> new InstallationStepRecord(step));
    Map<String, Object> before =
        record.getPayload() == null ? null : auditSummary(record.getStep(), record);
    record.replaceValues(stored.payload(), stored.secret(), clock.instant());
    steps.save(record);
    record(AuditEventType.INSTALLATION_STEP_SAVED, before, auditSummary(step, record));
    return record;
  }

  private static ConflictException notSaved(InstallationStep step) {
    return new ConflictException("Der Schritt " + step.name() + " ist nicht gespeichert.");
  }

  private InstallationStepRecord recordOutcome(
      InstallationStepRecord record, StepChecks.Outcome outcome) {
    InstallationStep step = record.getStep();
    if (outcome.succeeded() && outcome.dimensions() != null) {
      record.recordCheckedValues(
          rules.withProbedDimensions(record.getPayload(), outcome.dimensions()));
    }
    record.recordCheck(outcome.succeeded(), outcome.message(), clock.instant());
    steps.save(record);
    record(
        AuditEventType.INSTALLATION_STEP_CHECKED,
        null,
        Map.of("step", step.name(), "succeeded", outcome.succeeded()));
    return record;
  }

  private InstallationOverview completeLocked(InstallationState state, CompletionSource source) {
    Map<InstallationStep, InstallationStepRecord> saved = savedSteps();
    List<InstallationStep> open =
        Arrays.stream(InstallationStep.values())
            .filter(InstallationStep::isMandatory)
            .filter(step -> saved.get(step) == null || !saved.get(step).isCheckSucceeded())
            .toList();
    if (!open.isEmpty()) {
      throw new ConflictException(
          "Die Installation lässt sich erst abschließen, wenn jeder Pflichtschritt geprüft ist."
              + " Offen: "
              + open.stream().map(Enum::name).collect(Collectors.joining(", "))
              + ".");
    }
    InstallationStepRecord adminStep = saved.get(InstallationStep.FIRST_ADMIN);
    StepRules.FirstAdminPayload admin = rules.firstAdminPayload(adminStep.getPayload());
    initialAdministrator.create(
        admin.email(),
        admin.displayName(),
        adminStep.getSecret(),
        SYSTEM_ACTOR,
        admin.requirePasswordChange());
    StepRules.SignInPayload signIn =
        rules.signInPayload(saved.get(InstallationStep.SIGN_IN).getPayload());
    if (signIn.mode() == StepInput.SignInMode.LOCAL_AND_OIDC) {
      StepInput.Oidc oidc = signIn.oidc();
      oidcProviders.createInitialProvider(
          Organization.DEFAULT_ID,
          SYSTEM_ACTOR,
          new OidcProviderDraft(
              oidc.displayName(),
              oidc.issuerUri(),
              oidc.clientId(),
              oidc.jwkSetUri(),
              OidcClaimMapping.keycloakDefaults()));
    }
    InstallationStepRecord modelStep = saved.get(InstallationStep.CHAT_MODEL);
    StepRules.ChatModelPayload model = rules.chatModelPayload(modelStep.getPayload());
    llmModels.installActiveModel(
        Organization.DEFAULT_ID,
        SYSTEM_ACTOR,
        CHAT_MODEL_DISPLAY_NAME,
        model.baseUrl(),
        model.model(),
        model.temperature(),
        StepRules.maxTokens(model),
        modelStep.getSecret());
    installEmbeddingModel(saved.get(InstallationStep.EMBEDDING_MODEL));
    steps.deleteAllInBatch();
    markCompleted(
        state, source, Map.of("source", source.name(), "signInMode", signIn.mode().name()));
    return new InstallationOverview(false, true, List.of());
  }

  /**
   * The embedding model and {@code vector_store} with the probed vector size, in the completion's
   * transaction and under its locks, so the table exists exactly once (ADR-0060, Entscheidung 10).
   */
  private void installEmbeddingModel(InstallationStepRecord step) {
    StepRules.EmbeddingModelPayload embedding = rules.embeddingModelPayload(step.getPayload());
    if (embedding.dimensions() == null) {
      throw new ConflictException(
          "Die Probeeinbettung des Embedding-Modells fehlt; bitte den Schritt erneut prüfen.");
    }
    embeddingModels.install(
        rules.embeddingEndpoint(embedding, step.getSecret()), embedding.dimensions());
  }

  private void markCompleted(
      InstallationState state, CompletionSource source, Map<String, Object> after) {
    state.complete(source, clock.instant());
    states.save(state);
    record(AuditEventType.INSTALLATION_COMPLETED, null, after);
    TransactionCallbacks.afterCommitOrNow(status::reload);
    log.info("Installation completed ({})", source);
  }

  /** The row under its lock, then the start-up lock; written as pending if it is missing. */
  private InstallationState lockState() {
    states.insertPendingIfAbsent();
    InstallationState state = states.findForUpdate().orElseThrow();
    states.lockStartupSeeds();
    return state;
  }

  private InstallationState lockPending() {
    InstallationState state = lockState();
    if (state.isCompleted()) {
      throw notFound();
    }
    return state;
  }

  /** A database OPAA already ran on: an account or a chat model exists. */
  private boolean databaseInUse() {
    return users.count() > 0 || llmModels.anyModelExists();
  }

  private void installDevelopmentChatModel() {
    llmModels.installActiveModel(
        Organization.DEFAULT_ID,
        SYSTEM_ACTOR,
        DEVELOPMENT_CHAT_MODEL_DISPLAY_NAME,
        environment.getRequiredProperty(DEVELOPMENT_CHAT_BASE_URL_PROPERTY).trim(),
        DEVELOPMENT_CHAT_MODEL,
        null,
        DEVELOPMENT_CHAT_MAX_TOKENS,
        null);
  }

  private InstallationOverview overviewOfSavedSteps() {
    Map<InstallationStep, InstallationStepRecord> saved = savedSteps();
    List<InstallationOverview.StepState> states =
        Arrays.stream(InstallationStep.values())
            .map(
                step ->
                    saved.containsKey(step)
                        ? state(saved.get(step))
                        : new InstallationOverview.StepState(
                            step, step.isMandatory(), false, false, null, null))
            .toList();
    boolean completable =
        states.stream()
            .filter(InstallationOverview.StepState::mandatory)
            .allMatch(s -> s.checked());
    return new InstallationOverview(true, completable, states);
  }

  private Map<InstallationStep, InstallationStepRecord> savedSteps() {
    Map<InstallationStep, InstallationStepRecord> saved = new EnumMap<>(InstallationStep.class);
    steps.findAll().forEach(record -> saved.put(record.getStep(), record));
    return saved;
  }

  private InstallationOverview.StepState state(InstallationStepRecord record) {
    InstallationStep step = record.getStep();
    return new InstallationOverview.StepState(
        step,
        step.isMandatory(),
        true,
        record.isCheckSucceeded(),
        record.getCheckMessage(),
        rules.view(step, record.getPayload(), record.getSecret()));
  }

  /** What the audit entry of a step names: never the address of the first account, no secret. */
  private Map<String, Object> auditSummary(InstallationStep step, InstallationStepRecord record) {
    Map<String, Object> summary = new LinkedHashMap<>();
    summary.put("step", step.name());
    switch (rules.view(step, record.getPayload(), record.getSecret())) {
      case InstallationOverview.FirstAdmin admin -> summary.put("passwordSet", admin.passwordSet());
      case InstallationOverview.SignIn signIn -> {
        summary.put("signInMode", signIn.mode().name());
        if (signIn.oidc() != null) {
          summary.put("issuerUri", signIn.oidc().issuerUri());
        }
      }
      case InstallationOverview.ChatModel model -> {
        summary.put("baseUrl", ModelEndpointUri.withoutSecrets(model.baseUrl()));
        summary.put("model", model.model());
        summary.put("apiKeySet", model.apiKeySet());
      }
      case InstallationOverview.EmbeddingModel model -> {
        summary.put("baseUrl", ModelEndpointUri.withoutSecrets(model.baseUrl()));
        summary.put("model", model.model());
        summary.put("apiKeySet", model.apiKeySet());
      }
    }
    return summary;
  }

  private void record(AuditEventType type, Map<String, Object> before, Map<String, Object> after) {
    audit.recordSystemProcessAction(
        AuditEvent.builder()
            .organizationId(Organization.DEFAULT_ID)
            .actorRef(SYSTEM_ACTOR)
            .type(type)
            .object(AuditObjectType.SYSTEM_SETTING, OBJECT_ID, OBJECT_LABEL)
            .before(before)
            .after(after)
            .outcome(AuditOutcome.SUCCESS)
            .build());
  }

  private static NotFoundException notFound() {
    return new NotFoundException("Die angeforderte Ressource wurde nicht gefunden");
  }
}
