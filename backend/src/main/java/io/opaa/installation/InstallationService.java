package io.opaa.installation;

import io.opaa.account.InitialAdministrator;
import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.audit.AuditEvent;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.OidcClaimMapping;
import io.opaa.auth.UserRepository;
import io.opaa.branding.BrandingSettingsService;
import io.opaa.common.ConflictException;
import io.opaa.common.FieldValidationException;
import io.opaa.common.NotFoundException;
import io.opaa.common.TooManyRequestsException;
import io.opaa.common.TransactionCallbacks;
import io.opaa.common.ValidationException;
import io.opaa.directory.OidcProviderDraft;
import io.opaa.directory.OidcProviderService;
import io.opaa.installation.InstallationState.CompletionSource;
import io.opaa.installation.InstallationState.TokenSource;
import io.opaa.llm.EmbeddingModelService;
import io.opaa.llm.LlmModelService;
import io.opaa.llm.ModelEndpointUri;
import io.opaa.mail.MailSettingsRules;
import io.opaa.mail.MailSettingsService;
import io.opaa.organization.Organization;
import io.opaa.ratelimit.RateLimitService;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
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
 * succeeds once; every later call answers {@link NotFoundException}. Checks of the mail step and
 * its test messages have budgets per instance (ADR-0053), for all clients together; the answer file
 * read at start-up is not counted.
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
   * profile, a fixed model, no key and no target address check. Only the profile {@code dev}
   * reaches it, which a start chooses explicitly ({@code io.opaa.auth.AuthProfileGuard} refuses a
   * start without a mode) and the Helm chart refuses (ADR-0005).
   */
  static final String DEVELOPMENT_CHAT_MODEL_DISPLAY_NAME = "Chat-Modell der Entwicklung";

  static final String DEVELOPMENT_CHAT_MODEL = "phi3:mini";
  static final int DEVELOPMENT_CHAT_MAX_TOKENS = 8000;
  static final String DEVELOPMENT_CHAT_BASE_URL_PROPERTY = "spring.ai.openai.base-url";

  static final int MAX_TEST_MAILS = 10;
  static final int TEST_MAIL_WINDOW_SECONDS = 600;

  /** The code of a refusal by the budget of test messages, apart from the API's own budget. */
  static final String TEST_MAIL_LIMIT_CODE = "MAIL_TEST_LIMIT";

  private static final String TEST_MAIL_LIMIT_MESSAGE =
      "Zu viele Testnachrichten. Bitte später erneut versuchen; die Prüfung ohne Testnachricht"
          + " ist weiter möglich.";
  private static final String TEST_MAIL_BUDGET_KEY = "installation-test-mail";

  static final int MAX_MAIL_CHECKS = 30;
  static final int MAIL_CHECK_WINDOW_SECONDS = 600;

  /** The code of a refusal by the budget of mail checks, with or without test message. */
  static final String MAIL_CHECK_LIMIT_CODE = "MAIL_CHECK_LIMIT";

  private static final String MAIL_CHECK_LIMIT_MESSAGE =
      "Zu viele Prüfungen des Mailservers. Bitte später erneut versuchen.";
  private static final String MAIL_CHECK_BUDGET_KEY = "installation-mail-check";
  private static final String TEST_RECIPIENT = "testRecipient";

  private static final Logger log = LoggerFactory.getLogger(InstallationService.class);

  private final InstallationStateRepository states;
  private final InstallationStepRepository steps;
  private final StepRules rules;
  private final StepChecks checks;
  private final InitialAdministrator initialAdministrator;
  private final OidcProviderService oidcProviders;
  private final LlmModelService llmModels;
  private final EmbeddingModelService embeddingModels;
  private final MailSettingsService mailSettings;
  private final BrandingSettingsService brandingSettings;
  private final AuditEventRecorder audit;
  private final UserRepository users;
  private final DatabaseInstallationStatus status;
  private final Clock clock;
  private final Environment environment;
  private final TransactionTemplate transactions;
  private final RateLimitService testMailBudget =
      new RateLimitService(MAX_TEST_MAILS, TEST_MAIL_WINDOW_SECONDS);
  private final RateLimitService mailCheckBudget =
      new RateLimitService(MAX_MAIL_CHECKS, MAIL_CHECK_WINDOW_SECONDS);

  InstallationService(
      InstallationStateRepository states,
      InstallationStepRepository steps,
      StepRules rules,
      StepChecks checks,
      InitialAdministrator initialAdministrator,
      OidcProviderService oidcProviders,
      LlmModelService llmModels,
      EmbeddingModelService embeddingModels,
      MailSettingsService mailSettings,
      BrandingSettingsService brandingSettings,
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
    this.mailSettings = mailSettings;
    this.brandingSettings = brandingSettings;
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

  /** {@link #check(InstallationStep, String)} without a test message. */
  public InstallationOverview.StepState check(InstallationStep step) {
    return check(step, null);
  }

  /**
   * Runs the check of a saved step and stores its outcome. The check talks to the network, so it
   * runs outside every transaction and lock; only its outcome is stored under them, and only for
   * the values it checked. A non-blank {@code testRecipient}, for {@link InstallationStep#MAIL}
   * only, also receives the test message; it is neither stored nor audited.
   *
   * @throws ConflictException when the step is not saved, or was saved anew during the check
   * @throws ValidationException for a test recipient of another step or one that is no address
   * @throws TooManyRequestsException past the budget of test messages
   */
  public InstallationOverview.StepState check(InstallationStep step, String testRecipient) {
    String recipient = testRecipient(step, testRecipient);
    Saved saved =
        transactions.execute(
            status -> {
              lockPending();
              return steps
                  .findById(step)
                  .map(record -> new Saved(record.getPayload(), record.getSecret()))
                  .orElseThrow(() -> notSaved(step));
            });
    if (step == InstallationStep.MAIL) {
      acquireMailCheck();
    }
    if (recipient != null) {
      RateLimitService.Decision decision = testMailBudget.tryAcquire(TEST_MAIL_BUDGET_KEY);
      if (!decision.allowed()) {
        throw new TooManyRequestsException(
            TEST_MAIL_LIMIT_MESSAGE, decision.retryAfterSeconds(), TEST_MAIL_LIMIT_CODE);
      }
    }
    StepChecks.Outcome outcome = checks.check(step, saved.payload(), saved.secret(), recipient);
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
          return state(recordOutcome(record, outcome, recipient != null));
        });
  }

  /**
   * Skips an optional step: discards what is saved for it, so the completion leaves what the step
   * would set as it is. Audited also when nothing was saved.
   *
   * @throws ValidationException for a mandatory step
   */
  @Transactional
  public InstallationOverview.StepState skip(InstallationStep step) {
    if (step.isMandatory()) {
      throw new ValidationException("Ein Pflichtschritt lässt sich nicht überspringen.");
    }
    lockPending();
    discardLocked(step);
    return unsavedState(step);
  }

  /**
   * Completes the installation from its checked steps; a skipped optional step leaves its model
   * out.
   *
   * @throws ConflictException when a mandatory step is not saved or not successfully checked, or an
   *     optional step is saved but not successfully checked
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
    if (inputs.stream().anyMatch(input -> input.step() == InstallationStep.MAIL)) {
      acquireMailCheck();
    }
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

  /**
   * The trimmed test recipient, or {@code null} for none.
   *
   * @throws ValidationException for a recipient of a step other than {@link InstallationStep#MAIL}
   * @throws FieldValidationException for a recipient that is no mail address
   */
  private static String testRecipient(InstallationStep step, String testRecipient) {
    String recipient = MailSettingsRules.trimmedOrNull(testRecipient);
    if (recipient == null) {
      return null;
    }
    if (step != InstallationStep.MAIL) {
      throw new ValidationException("Eine Testnachricht gibt es nur im Schritt MAIL.");
    }
    if (recipient.length() > MailSettingsRules.MAX_FROM_ADDRESS_LENGTH) {
      throw FieldValidationException.of(
          TEST_RECIPIENT,
          MailSettingsRules.TOO_LONG,
          "Die Adresse der Testnachricht darf höchstens "
              + MailSettingsRules.MAX_FROM_ADDRESS_LENGTH
              + " Zeichen lang sein.");
    }
    if (!MailSettingsRules.isMailAddress(recipient)) {
      throw FieldValidationException.of(
          TEST_RECIPIENT,
          "INVALID",
          "Die Adresse der Testnachricht muss eine E-Mail-Adresse sein, zum Beispiel"
              + " it-betrieb@stadt.example.");
    }
    return recipient;
  }

  /**
   * Takes one connection to a mail server from the budget of this instance, for all clients.
   *
   * @throws TooManyRequestsException past the budget
   */
  private void acquireMailCheck() {
    RateLimitService.Decision decision = mailCheckBudget.tryAcquire(MAIL_CHECK_BUDGET_KEY);
    if (!decision.allowed()) {
      throw new TooManyRequestsException(
          MAIL_CHECK_LIMIT_MESSAGE, decision.retryAfterSeconds(), MAIL_CHECK_LIMIT_CODE);
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
          checks.check(step, stored.get(i).payload(), stored.get(i).secret(), null);
      if (!outcome.succeeded()) {
        throw new ConflictException(
            "Schritt " + step.name() + " der Antwortdatei: " + outcome.message());
      }
      checked.add(new Checked(step, stored.get(i), outcome));
    }
    return checked;
  }

  /**
   * Saves the checked answers with their outcomes and skips every optional step the answers leave
   * out, so the completion applies exactly the answers; the caller holds the locks.
   */
  private void storeAnswers(List<Checked> checked) {
    Set<InstallationStep> answered = EnumSet.noneOf(InstallationStep.class);
    for (Checked answer : checked) {
      answered.add(answer.step());
      recordOutcome(storeLocked(answer.step(), answer.stored()), answer.outcome(), false);
    }
    Arrays.stream(InstallationStep.values())
        .filter(step -> !step.isMandatory() && !answered.contains(step))
        .forEach(this::discardLocked);
  }

  /** Deletes what is saved for {@code step} and records the skip; the caller holds the locks. */
  private void discardLocked(InstallationStep step) {
    Optional<InstallationStepRecord> saved = steps.findForUpdate(step);
    saved.ifPresent(steps::delete);
    record(
        AuditEventType.INSTALLATION_STEP_SKIPPED,
        null,
        Map.of("step", step.name(), "discarded", saved.isPresent()));
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
      InstallationStepRecord record, StepChecks.Outcome outcome, boolean testMail) {
    InstallationStep step = record.getStep();
    if (outcome.succeeded() && outcome.dimensions() != null) {
      record.recordCheckedValues(
          rules.withProbedDimensions(record.getPayload(), outcome.dimensions()));
    }
    record.recordCheck(outcome.succeeded(), outcome.message(), clock.instant());
    steps.save(record);
    Map<String, Object> after = new LinkedHashMap<>();
    after.put("step", step.name());
    after.put("succeeded", outcome.succeeded());
    if (step == InstallationStep.MAIL) {
      after.put("testMail", testMail);
    }
    record(AuditEventType.INSTALLATION_STEP_CHECKED, null, after);
    return record;
  }

  private InstallationOverview completeLocked(InstallationState state, CompletionSource source) {
    Map<InstallationStep, InstallationStepRecord> saved = savedSteps();
    List<InstallationStep> open =
        Arrays.stream(InstallationStep.values())
            .filter(step -> isOpen(step, saved.get(step)))
            .toList();
    if (!open.isEmpty()) {
      throw new ConflictException(
          "Die Installation lässt sich erst abschließen, wenn jeder Pflichtschritt geprüft ist"
              + " und jeder gespeicherte optionale Schritt geprüft oder übersprungen."
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
    // both sign-in methods include the local accounts; a provider comes on top of them
    oidcProviders.enableLocalAccountsForInstallation(Organization.DEFAULT_ID, SYSTEM_ACTOR);
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
    if (modelStep != null) {
      installChatModel(modelStep);
    }
    InstallationStepRecord embeddingStep = saved.get(InstallationStep.EMBEDDING_MODEL);
    if (embeddingStep != null) {
      installEmbeddingModel(embeddingStep);
    }
    InstallationStepRecord mailStep = saved.get(InstallationStep.MAIL);
    if (mailStep != null) {
      mailSettings.installSettings(
          Organization.DEFAULT_ID,
          SYSTEM_ACTOR,
          rules.mailSettingsUpdate(rules.mailPayload(mailStep.getPayload()), mailStep.getSecret()));
    }
    InstallationStepRecord brandingStep = saved.get(InstallationStep.BRANDING);
    if (brandingStep != null) {
      installBranding(rules.brandingPayload(brandingStep.getPayload()));
    }
    steps.deleteAllInBatch();
    List<String> skipped =
        Arrays.stream(InstallationStep.values())
            .filter(step -> !step.isMandatory() && !saved.containsKey(step))
            .map(Enum::name)
            .toList();
    markCompleted(
        state,
        source,
        Map.of(
            "source",
            source.name(),
            "signInMode",
            signIn.mode().name(),
            "skippedSteps",
            skipped,
            "mailEnabled",
            mailStep != null));
    return new InstallationOverview(false, true, List.of());
  }

  /**
   * Whether {@code step} holds up the completion: a mandatory step until it is checked, an optional
   * one while it is saved but not checked.
   */
  private static boolean isOpen(InstallationStep step, InstallationStepRecord saved) {
    if (step.isMandatory()) {
      return saved == null || !saved.isCheckSucceeded();
    }
    return saved != null && !saved.isCheckSucceeded();
  }

  /** The chat model of the step, created active in the completion's transaction. */
  private void installChatModel(InstallationStepRecord step) {
    StepRules.ChatModelPayload model = rules.chatModelPayload(step.getPayload());
    llmModels.installActiveModel(
        Organization.DEFAULT_ID,
        SYSTEM_ACTOR,
        CHAT_MODEL_DISPLAY_NAME,
        model.baseUrl(),
        model.model(),
        model.temperature(),
        StepRules.maxTokens(model),
        step.getSecret());
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

  /**
   * The branding of the step through the write path of the administration, audited under the
   * installation; a step left at every default writes and records nothing.
   */
  private void installBranding(StepRules.BrandingPayload branding) {
    brandingSettings.installBranding(
        Organization.DEFAULT_ID,
        SYSTEM_ACTOR,
        branding.productName(),
        branding.claim(),
        branding.aiNotice(),
        branding.primaryColor(),
        branding.defaultColorScheme());
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
            .map(step -> saved.containsKey(step) ? state(saved.get(step)) : unsavedState(step))
            .toList();
    boolean completable =
        Arrays.stream(InstallationStep.values()).noneMatch(step -> isOpen(step, saved.get(step)));
    return new InstallationOverview(true, completable, states);
  }

  private static InstallationOverview.StepState unsavedState(InstallationStep step) {
    return new InstallationOverview.StepState(step, step.isMandatory(), false, false, null, null);
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
      case InstallationOverview.Mail mail -> {
        summary.put("host", mail.host());
        summary.put("port", mail.port());
        summary.put("encryption", mail.encryption().name());
        summary.put("username", mail.username() == null ? "-" : mail.username());
        summary.put("passwordSet", mail.passwordSet());
        summary.put("fromAddress", mail.fromAddress());
      }
      case InstallationOverview.Branding branding -> {
        summary.put("productName", orDash(branding.productName()));
        summary.put("claim", orDash(branding.claim()));
        summary.put("aiNoticeSet", branding.aiNotice() != null);
        summary.put("primaryColor", orDash(branding.primaryColor()));
        summary.put(
            "defaultColorScheme",
            branding.defaultColorScheme() == null ? "-" : branding.defaultColorScheme().name());
      }
    }
    return summary;
  }

  /** A value of a step summary; {@code "-"} for none, which for the branding means the default. */
  private static String orDash(String value) {
    return value == null ? "-" : value;
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
