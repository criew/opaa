package io.opaa.installation;

import io.opaa.account.InitialAdministrator;
import io.opaa.account.LocalAdminSeedMarkerRepository;
import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.audit.AuditEvent;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.OidcClaimMapping;
import io.opaa.auth.OidcProviderSeedMarker;
import io.opaa.auth.OidcProviderSeedMarkerRepository;
import io.opaa.common.ConflictException;
import io.opaa.common.NotFoundException;
import io.opaa.common.TransactionCallbacks;
import io.opaa.directory.OidcProviderDraft;
import io.opaa.directory.OidcProviderService;
import io.opaa.installation.InstallationState.CompletionSource;
import io.opaa.installation.InstallationState.TokenSource;
import io.opaa.llm.LlmModelSeedMarkerRepository;
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
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The installation of a fresh instance (ADR-0060, Entscheidungen 4 to 9). Every write takes the row
 * lock of {@code installation_state} and then the start-up lock (namespace {@code 211}), in that
 * order everywhere, so steps, checks, the completion and the start-up seeds of every instance run
 * one after another. The completion succeeds exactly once: a later call finds the row completed and
 * answers {@link NotFoundException}, like every call after it.
 */
@Service
public class InstallationService {

  /** The system process every audit entry of the installation is recorded under. */
  public static final String SYSTEM_ACTOR = "installation";

  static final UUID OBJECT_ID =
      UUID.nameUUIDFromBytes("opaa-installation".getBytes(StandardCharsets.UTF_8));
  static final String OBJECT_LABEL = "Installation";
  static final String CHAT_MODEL_DISPLAY_NAME = "Chat-Modell aus der Installation";

  private static final Logger log = LoggerFactory.getLogger(InstallationService.class);

  private final InstallationStateRepository states;
  private final InstallationStepRepository steps;
  private final StepRules rules;
  private final StepChecks checks;
  private final InitialAdministrator initialAdministrator;
  private final OidcProviderService oidcProviders;
  private final LlmModelService llmModels;
  private final LocalAdminSeedMarkerRepository localAdminMarker;
  private final OidcProviderSeedMarkerRepository oidcMarker;
  private final LlmModelSeedMarkerRepository llmMarker;
  private final AuditEventRecorder audit;
  private final DatabaseInstallationStatus status;
  private final Clock clock;

  InstallationService(
      InstallationStateRepository states,
      InstallationStepRepository steps,
      StepRules rules,
      StepChecks checks,
      InitialAdministrator initialAdministrator,
      OidcProviderService oidcProviders,
      LlmModelService llmModels,
      LocalAdminSeedMarkerRepository localAdminMarker,
      OidcProviderSeedMarkerRepository oidcMarker,
      LlmModelSeedMarkerRepository llmMarker,
      AuditEventRecorder audit,
      DatabaseInstallationStatus status,
      Clock clock) {
    this.states = states;
    this.steps = steps;
    this.rules = rules;
    this.checks = checks;
    this.initialAdministrator = initialAdministrator;
    this.oidcProviders = oidcProviders;
    this.llmModels = llmModels;
    this.localAdminMarker = localAdminMarker;
    this.oidcMarker = oidcMarker;
    this.llmMarker = llmMarker;
    this.audit = audit;
    this.status = status;
    this.clock = clock;
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
   * Runs the check of a saved step and stores its outcome.
   *
   * @throws ConflictException when the step is not saved
   */
  @Transactional
  public InstallationOverview.StepState check(InstallationStep step) {
    lockPending();
    return state(checkLocked(step));
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
   * Saves, checks and completes every step of an answer file, all or nothing.
   *
   * @throws ConflictException naming the step whose check failed; nothing is saved then
   */
  @Transactional
  public InstallationOverview applyAnswers(List<StepInput> inputs, CompletionSource source) {
    InstallationState state = lockPending();
    for (StepInput input : inputs) {
      saveLocked(input);
    }
    for (StepInput input : inputs) {
      InstallationStepRecord checked = checkLocked(input.step());
      if (!checked.isCheckSucceeded()) {
        throw new ConflictException(
            "Schritt " + input.step().name() + " der Antwortdatei: " + checked.getCheckMessage());
      }
    }
    return completeLocked(state, source);
  }

  /**
   * The start-up decision for an installation that needs no step: an existing database, the {@code
   * dev} mode or the start values of the transition complete it at once. Returns whether the
   * installation is completed afterwards.
   */
  @Transactional
  public boolean completeAtStartupIfDue(boolean developmentMode, boolean legacyStartValues) {
    InstallationState state = lockState();
    if (state.isCompleted()) {
      return true;
    }
    CompletionSource source = null;
    if (anyStartupSeedRan()) {
      source = CompletionSource.EXISTING;
    } else if (developmentMode) {
      source = CompletionSource.DEVELOPMENT;
    } else if (legacyStartValues) {
      source = CompletionSource.LEGACY_START_VALUES;
    }
    if (source == null) {
      return false;
    }
    markCompleted(state, source, Map.of("source", source.name()));
    return true;
  }

  /**
   * Stores the hash of the configured token, or generates one if none is stored. Returns the token
   * that must be printed once - a freshly generated one - or {@code null}; the caller prints it
   * after this transaction committed.
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
    if (state.getTokenHash() != null) {
      return null;
    }
    String token = InstallationTokens.generate();
    state.storeToken(InstallationTokens.hash(token), TokenSource.GENERATED, now);
    states.save(state);
    return token;
  }

  private InstallationStepRecord saveLocked(StepInput input) {
    InstallationStepRecord record =
        steps.findForUpdate(input.step()).orElseGet(() -> new InstallationStepRecord(input.step()));
    Map<String, Object> before =
        record.getPayload() == null ? null : auditSummary(record.getStep(), record);
    StepRules.Stored stored = rules.toStored(input, record.getPayload(), record.getSecret());
    record.replaceValues(stored.payload(), stored.secret(), clock.instant());
    steps.save(record);
    record(AuditEventType.INSTALLATION_STEP_SAVED, before, auditSummary(input.step(), record));
    return record;
  }

  private InstallationStepRecord checkLocked(InstallationStep step) {
    InstallationStepRecord record =
        steps
            .findForUpdate(step)
            .orElseThrow(
                () ->
                    new ConflictException(
                        "Der Schritt " + step.name() + " ist nicht gespeichert."));
    StepChecks.Outcome outcome = checks.check(step, record.getPayload(), record.getSecret());
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
        admin.email(), admin.displayName(), adminStep.getSecret(), SYSTEM_ACTOR);
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
    if (!oidcMarker.seedAlreadyAttempted()) {
      oidcMarker.save(new OidcProviderSeedMarker(clock.instant()));
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
    steps.deleteAllInBatch();
    markCompleted(
        state, source, Map.of("source", source.name(), "signInMode", signIn.mode().name()));
    return new InstallationOverview(false, true, List.of());
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

  /** A database an earlier version of OPAA started on already ran one of the start-up seeds. */
  private boolean anyStartupSeedRan() {
    return localAdminMarker.seedAlreadyAttempted()
        || oidcMarker.seedAlreadyAttempted()
        || llmMarker.seedAlreadyAttempted();
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
