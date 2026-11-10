package io.opaa.llm;

import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.api.types.RealtimeTranscriptionProtocol;
import io.opaa.api.types.TextRecognitionConnectionType;
import io.opaa.audit.AuditEvent;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.audit.AuditSecretChange;
import io.opaa.common.Feature;
import io.opaa.common.FeatureFlags;
import io.opaa.common.FieldValidationException;
import io.opaa.common.FieldValidationException.FieldError;
import io.opaa.common.TransactionCallbacks;
import io.opaa.security.CipherBinding;
import io.opaa.security.SettingsEncryptor;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * What the system administration does with the rerank, transcription and text recognition model
 * roles (ADR-0060, Entscheidung 10; ADR-0067, Entscheidung 1): read, change and test them; the
 * installation stores a checked rerank, transcription or text recognition step through the same
 * write path (Entscheidungen 16 and 18). A change is validated like every model endpoint ({@link
 * ModelEndpointRules}, {@link ModelAddressPolicy} for an address other than the stored one), stored
 * with the key encrypted, and recorded with the values before and after (Entscheidung 11); a change
 * to the same values writes nothing. The speech-input feature is the operation's limit: the
 * transcription role cannot be switched on while it is off (Entscheidung 2); {@code
 * opaa.ocr.enabled} is the limit of the text recognition role, which then can neither be switched
 * on nor tested.
 *
 * <p>The change reaches every instance through the triggers on the three tables; this instance
 * drops its cache and probes the new endpoint right after the commit, so the state on the page
 * follows without waiting for the next scheduled probe.
 */
@Service
public class ModelRoleAdministration {

  /** The budget of a test request from the administration, below the runtime timeouts. */
  static final Duration TEST_TIMEOUT = Duration.ofSeconds(30);

  static final String RERANK = "rerank";
  static final String TRANSCRIPTION = "transcription";
  static final String TEXT_RECOGNITION = "textRecognition";
  static final String TRANSCRIPTION_REALTIME = "transcription.realtime";

  /** The budget of a text recognition test: the first conversion may load the OCR models. */
  static final Duration TEXT_RECOGNITION_TEST_TIMEOUT = Duration.ofMinutes(3);

  private static final String TEXT_RECOGNITION_OFF =
      "Die Texterkennung ist im Betrieb abgeschaltet (OPAA_OCR_ENABLED); die Rolle lässt sich"
          + " deshalb weder einschalten noch testen.";

  private static final String SPEECH_INPUT_OFF =
      "Die Spracheingabe ist im Betrieb abgeschaltet (OPAA_FEATURES_SPEECH_INPUT_ENABLED); das"
          + " Transkriptionsmodell lässt sich deshalb nicht einschalten.";

  /**
   * One role as the page shows it; {@code status} is the role's last known state for what is
   * stored.
   */
  public record RerankModelView(
      boolean enabled,
      String baseUrl,
      String model,
      boolean apiKeySet,
      OptionalRoleStatus status,
      Instant updatedAt,
      List<String> retiredVariables) {}

  /** The transcription role as the page shows it, with the operation's limit. */
  public record TranscriptionModelView(
      boolean enabled,
      String baseUrl,
      String model,
      boolean apiKeySet,
      String language,
      String vocabulary,
      RealtimeView realtime,
      OptionalRoleStatus status,
      boolean speechInputAvailable,
      Instant updatedAt,
      List<String> retiredVariables) {}

  /**
   * The live upstream of the transcription role as the page shows it (ADR-0072); {@code apiKeySet}
   * names a key of its own only.
   */
  public record RealtimeView(
      boolean enabled,
      RealtimeTranscriptionProtocol protocol,
      String url,
      String model,
      boolean apiKeySet,
      int maxSessions,
      OptionalRoleStatus status) {}

  /**
   * A change of the live upstream: {@code enabled} false removes it with its key; {@code apiKey}
   * {@code null} keeps the stored key for the same address, blank removes it; {@code maxSessions}
   * {@code null} keeps the stored number.
   */
  public record RealtimeChange(
      boolean enabled,
      RealtimeTranscriptionProtocol protocol,
      String url,
      String model,
      String apiKey,
      Integer maxSessions) {

    /**
     * The live upstream of an installation step, switched on; {@code apiKey} {@code null} for none
     * of its own.
     */
    public static RealtimeChange installed(
        RealtimeTranscriptionProtocol protocol, String url, String model, String apiKey) {
      return new RealtimeChange(true, protocol, url, model, apiKey == null ? "" : apiKey, null);
    }
  }

  /** The text recognition role as the page shows it, with the operation's limit. */
  public record TextRecognitionModelView(
      boolean enabled,
      TextRecognitionConnectionType connectionType,
      String baseUrl,
      String model,
      boolean apiKeySet,
      OptionalRoleStatus status,
      boolean textRecognitionAvailable,
      Instant updatedAt) {}

  /** A test result with a German message. */
  public record TestOutcome(boolean success, String message) {}

  /** A text recognition test result: what the test found, with a German message. */
  public record TextRecognitionTestOutcome(
      boolean success,
      TextRecognitionClient.TestOutcome outcome,
      TextRecognitionClient.FailedStage failedStage,
      String message) {}

  private final RoleTable<RerankModelSettings> rerankTable;
  private final RoleTable<TranscriptionModelSettings> transcriptionTable;
  private final RoleTable<TextRecognitionModelSettings> textRecognitionTable;
  private final ModelRoleEndpoints endpoints;
  private final OptionalRoleStatusResolver<RerankEndpoint> rerankRole;
  private final OptionalRoleStatusResolver<TranscriptionEndpoint> transcriptionRole;
  private final RealtimeTranscriptionStatusResolver realtimeRole;
  private final RealtimeTranscriptionClient realtimeClient;
  private final boolean speechInputAvailable;
  private final TextRecognitionModelRole textRecognitionRole;
  private final RerankClient rerankClient;
  private final TranscriptionClient transcriptionClient;
  private final TextRecognitionClient textRecognitionClient;
  private final SettingsEncryptor settingsEncryptor;
  private final ModelAddressPolicy modelAddresses;
  private final AuditEventRecorder auditEventRecorder;
  private final Environment environment;
  private final ApplicationEventPublisher events;
  private final TransactionTemplate transactions;
  private final JdbcTemplate jdbc;
  private final Clock clock;

  ModelRoleAdministration(
      RerankModelSettingsRepository rerankRows,
      TranscriptionModelSettingsRepository transcriptionRows,
      TextRecognitionModelSettingsRepository textRecognitionRows,
      ModelRoleEndpoints endpoints,
      OptionalRoleStatusResolver<RerankEndpoint> rerankRole,
      OptionalRoleStatusResolver<TranscriptionEndpoint> transcriptionRole,
      RealtimeTranscriptionStatusResolver realtimeRole,
      TextRecognitionModelRole textRecognitionRole,
      FeatureFlags featureFlags,
      RerankClient rerankClient,
      TranscriptionClient transcriptionClient,
      RealtimeTranscriptionClient realtimeClient,
      TextRecognitionClient textRecognitionClient,
      SettingsEncryptor settingsEncryptor,
      ModelAddressPolicy modelAddresses,
      AuditEventRecorder auditEventRecorder,
      Environment environment,
      ApplicationEventPublisher events,
      PlatformTransactionManager transactionManager,
      JdbcTemplate jdbc,
      Clock clock) {
    boolean speechInput = featureFlags.isEnabled(Feature.SPEECH_INPUT);
    this.rerankTable =
        new RoleTable<>(
            "rerank_model",
            RERANK,
            rerankRows,
            RerankModelSettings::new,
            AuditEventType.RERANK_MODEL_CHANGED,
            "Rerank-Modell",
            ModelRoleSettingsChangedEvent.Role.RERANK,
            () -> true,
            null,
            ModelRoleAdministration::rerankPayload);
    this.transcriptionTable =
        new RoleTable<>(
            "transcription_model",
            TRANSCRIPTION,
            transcriptionRows,
            TranscriptionModelSettings::new,
            AuditEventType.TRANSCRIPTION_MODEL_CHANGED,
            "Transkriptionsmodell",
            ModelRoleSettingsChangedEvent.Role.TRANSCRIPTION,
            () -> speechInput,
            SPEECH_INPUT_OFF,
            ModelRoleAdministration::transcriptionPayload);
    this.textRecognitionTable =
        new RoleTable<>(
            "text_recognition_model",
            TEXT_RECOGNITION,
            textRecognitionRows,
            TextRecognitionModelSettings::new,
            AuditEventType.TEXT_RECOGNITION_MODEL_CHANGED,
            "Texterkennung",
            ModelRoleSettingsChangedEvent.Role.TEXT_RECOGNITION,
            textRecognitionRole::operationEnabled,
            TEXT_RECOGNITION_OFF,
            ModelRoleAdministration::textRecognitionPayload);
    this.endpoints = endpoints;
    this.rerankRole = rerankRole;
    this.transcriptionRole = transcriptionRole;
    this.realtimeRole = realtimeRole;
    this.realtimeClient = realtimeClient;
    this.speechInputAvailable = speechInput;
    this.textRecognitionRole = textRecognitionRole;
    this.rerankClient = rerankClient;
    this.transcriptionClient = transcriptionClient;
    this.textRecognitionClient = textRecognitionClient;
    this.settingsEncryptor = settingsEncryptor;
    this.modelAddresses = modelAddresses;
    this.auditEventRecorder = auditEventRecorder;
    this.environment = environment;
    this.events = events;
    this.transactions = new TransactionTemplate(transactionManager);
    this.jdbc = jdbc;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public RerankModelView rerank() {
    return rerankTable.rows().findStored().map(this::rerankView).orElseGet(() -> rerankView(null));
  }

  @Transactional(readOnly = true)
  public TranscriptionModelView transcription() {
    return transcriptionTable
        .rows()
        .findStored()
        .map(this::transcriptionView)
        .orElseGet(() -> transcriptionView(null));
  }

  @Transactional(readOnly = true)
  public TextRecognitionModelView textRecognition() {
    return textRecognitionTable
        .rows()
        .findStored()
        .map(this::textRecognitionView)
        .orElseGet(() -> textRecognitionView(null));
  }

  /**
   * Stores the rerank role. {@code apiKey} {@code null} keeps the stored key for the same endpoint,
   * blank removes it. The address is checked before the transaction opens, since the check resolves
   * names; writers of the row are serialized by a table lock, so two first saves cannot collide.
   *
   * @throws FieldValidationException naming every violated field under {@code rerank.}
   */
  public RerankModelView changeRerank(
      UUID organizationId,
      UUID actorUserId,
      boolean enabled,
      String baseUrl,
      String model,
      String apiKey) {
    String address = trimmed(baseUrl);
    String identifier = trimmed(model);
    List<FieldError> errors = new ArrayList<>();
    ModelEndpointRules.endpointErrors(RERANK, address, identifier, apiKey, enabled, errors);
    blockedAddressError(RERANK, address, storedAddress(rerankTable), errors);
    requireNoErrors(errors);
    return transactions.execute(
        status ->
            rerankView(
                store(
                    rerankTable,
                    organizationId,
                    AuditActor.user(actorUserId),
                    enabled,
                    address,
                    identifier,
                    changedSecret(RERANK, apiKey, address),
                    (row, stored) -> OwnColumns.none())));
  }

  /**
   * Switches the rerank role on with the checked rerank step of the installation, in the caller's
   * transaction and audited under {@code systemActor}. {@code apiKey} is the clear key, {@code
   * null} for none, encrypted here; the installation has run the address check before. A change to
   * the same values writes nothing, as in {@link #changeRerank}.
   *
   * @throws FieldValidationException for an endpoint the rules of the role refuse
   */
  public RerankModelView installRerank(
      UUID organizationId, String systemActor, String baseUrl, String model, String apiKey) {
    String address = trimmed(baseUrl);
    String identifier = trimmed(model);
    List<FieldError> errors = new ArrayList<>();
    ModelEndpointRules.endpointErrors(RERANK, address, identifier, null, true, errors);
    requireNoErrors(errors);
    return transactions.execute(
        status ->
            rerankView(
                store(
                    rerankTable,
                    organizationId,
                    AuditActor.systemProcess(systemActor),
                    true,
                    address,
                    identifier,
                    row -> encryptInstalled(RERANK, apiKey),
                    (row, stored) -> OwnColumns.none())));
  }

  /** The key a change stores, derived from the stored row as {@link #secretOf} decides it. */
  private Function<OptionalRoleSettings, String> changedSecret(
      String prefix, String apiKey, String address) {
    return row -> secretOf(prefix, apiKey, row.getBaseUrl(), address, row.getApiKeyCiphertext());
  }

  /** The key of an installation step, encrypted for the row of the role, or none. */
  private String encryptInstalled(String prefix, String apiKey) {
    return apiKey == null || apiKey.isBlank()
        ? null
        : settingsEncryptor.encrypt(apiKey, rowOf(prefix));
  }

  /** Who a change of a role is recorded under: a person, or a system process. */
  private record AuditActor(UUID userId, String systemProcess) {

    static AuditActor user(UUID userId) {
      return new AuditActor(userId, null);
    }

    static AuditActor systemProcess(String name) {
      return new AuditActor(null, name);
    }
  }

  /**
   * The write path of every role, under the table lock: {@code ciphertextOf} derives the key to
   * store from the stored row, {@code own} the role's own columns from the stored row and whether
   * there is one. Refuses to switch on a role the operation does not allow, unless it is on
   * already; a change to the same values writes nothing.
   *
   * @return the row as stored, {@code null} for an unchanged factory default
   */
  private <R extends OptionalRoleSettings> R store(
      RoleTable<R> table,
      UUID organizationId,
      AuditActor actor,
      boolean enabled,
      String address,
      String identifier,
      Function<? super R, String> ciphertextOf,
      BiFunction<R, Boolean, OwnColumns<R>> own) {
    jdbc.execute("LOCK TABLE " + table.name() + " IN SHARE ROW EXCLUSIVE MODE");
    R existing = table.rows().findStored().orElse(null);
    boolean stored = existing != null;
    R row = stored ? existing : table.newRow().apply(clock.instant());
    if (enabled && !table.operationAllows().getAsBoolean() && !(stored && row.isEnabled())) {
      throw FieldValidationException.of(
          table.prefix() + ".enabled", ModelEndpointRules.BLOCKED, table.operationOff());
    }
    OwnColumns<R> columns = own.apply(row, stored);
    String previousCiphertext = row.getApiKeyCiphertext();
    String ciphertext = ciphertextOf.apply(row);
    boolean unchanged =
        row.isEnabled() == enabled
            && Objects.equals(row.getBaseUrl(), emptyToNull(address))
            && Objects.equals(row.getModelIdentifier(), emptyToNull(identifier))
            && Objects.equals(previousCiphertext, ciphertext)
            && columns.unchanged();
    if (unchanged) {
      return existing;
    }
    Map<String, Object> before = table.payload().apply(existing);
    row.replaceEndpoint(
        enabled, emptyToNull(address), emptyToNull(identifier), ciphertext, clock.instant());
    columns.replace(row);
    table.rows().save(row);
    Map<String, Object> after = table.payload().apply(row);
    AuditSecretChange.put(after, "apiKey", previousCiphertext, ciphertext);
    columns.annotate(after);
    record(organizationId, actor, table.changed(), table.name(), table.label(), before, after);
    afterCommit(table.role());
    return row;
  }

  /** A role's own columns of one change: whether they stay as stored, and how to write them. */
  private interface OwnColumns<R> {

    boolean unchanged();

    void replace(R row);

    /** Adds what the audit state of the row cannot tell, such as a changed key of its own. */
    default void annotate(Map<String, Object> after) {}

    static <R> OwnColumns<R> none() {
      return new OwnColumns<>() {
        @Override
        public boolean unchanged() {
          return true;
        }

        @Override
        public void replace(R row) {}
      };
    }
  }

  /**
   * One role's row as the write path needs it: table, field prefix, audit event and label, the
   * change event, the operation's limit and the audit state of a row.
   */
  private record RoleTable<R extends OptionalRoleSettings>(
      String name,
      String prefix,
      OptionalRoleSettingsRepository<R> rows,
      Function<Instant, R> newRow,
      AuditEventType changed,
      String label,
      ModelRoleSettingsChangedEvent.Role role,
      BooleanSupplier operationAllows,
      String operationOff,
      Function<R, Map<String, Object>> payload) {}

  /**
   * Stores the transcription role, as {@link #changeRerank}. {@code language} {@code null} keeps
   * the stored language, blank lets the model detect it; {@code vocabulary} {@code null} keeps the
   * stored list, blank removes it.
   *
   * @throws FieldValidationException naming every violated field under {@code transcription.}
   */
  public TranscriptionModelView changeTranscription(
      UUID organizationId,
      UUID actorUserId,
      boolean enabled,
      String baseUrl,
      String model,
      String apiKey,
      String language,
      String vocabulary,
      RealtimeChange realtime) {
    String address = trimmed(baseUrl);
    String identifier = trimmed(model);
    String lang = TranscriptionEndpoint.normalisedLanguage(language);
    String terms = TranscriptionVocabulary.normalised(vocabulary);
    RealtimeChange live = normalised(realtime);
    List<FieldError> errors = transcriptionErrors(enabled, address, identifier, apiKey, lang);
    realtimeErrors(live, errors);
    if (!TranscriptionVocabulary.isValid(terms)) {
      errors.add(
          new FieldError(
              TRANSCRIPTION + ".vocabulary",
              ModelEndpointRules.INVALID,
              TranscriptionVocabulary.RULE));
    }
    blockedAddressError(TRANSCRIPTION, address, storedAddress(transcriptionTable), errors);
    blockedRealtimeAddressError(live, storedRealtimeUrl(), errors);
    if (errors.isEmpty()) {
      liveKeyError(live, address, apiKey, transcriptionTable.rows().findStored().orElse(null))
          .ifPresent(errors::add);
    }
    requireNoErrors(errors);
    return transactions.execute(
        status ->
            transcriptionView(
                store(
                    transcriptionTable,
                    organizationId,
                    AuditActor.user(actorUserId),
                    enabled,
                    address,
                    identifier,
                    changedSecret(TRANSCRIPTION, apiKey, address),
                    (row, stored) -> transcriptionColumns(row, stored, lang, terms, live))));
  }

  /**
   * Switches the transcription role on with the checked transcription step of the installation, as
   * {@link #installRerank} (ADR-0060, Entscheidung 16). The operation's limit applies as in {@link
   * #changeTranscription}: with speech input switched off this refuses, so the installation calls
   * it only while it offers the step.
   *
   * @throws FieldValidationException for an endpoint or language the rules of the role refuse
   */
  public TranscriptionModelView installTranscription(
      UUID organizationId,
      String systemActor,
      String baseUrl,
      String model,
      String apiKey,
      String language,
      RealtimeChange realtime) {
    String address = trimmed(baseUrl);
    String identifier = trimmed(model);
    String lang = TranscriptionEndpoint.normalisedLanguage(language);
    RealtimeChange live =
        normalised(
            realtime == null ? new RealtimeChange(false, null, null, null, null, null) : realtime);
    List<FieldError> errors = transcriptionErrors(true, address, identifier, null, lang);
    realtimeErrors(live, errors);
    if (errors.isEmpty()) {
      liveKeyError(live, address, apiKey == null ? "" : apiKey, null).ifPresent(errors::add);
    }
    requireNoErrors(errors);
    return transactions.execute(
        status ->
            transcriptionView(
                store(
                    transcriptionTable,
                    organizationId,
                    AuditActor.systemProcess(systemActor),
                    true,
                    address,
                    identifier,
                    row -> encryptInstalled(TRANSCRIPTION, apiKey),
                    (row, stored) -> transcriptionColumns(row, stored, lang, null, live))));
  }

  /**
   * The refusal of a switched-on live upstream that would run without a key it needs ({@link
   * RealtimeTranscriptionEndpoint#lacksRequiredKey}); a {@code null} key keeps the stored one, a
   * blank one removes it.
   */
  private static Optional<FieldError> liveKeyError(
      RealtimeChange live, String batchAddress, String batchKey, TranscriptionModelSettings row) {
    if (live == null || !live.enabled()) {
      return Optional.empty();
    }
    boolean ownKey =
        live.apiKey() == null
            ? row != null && row.getRealtimeApiKeyCiphertext() != null
            : !live.apiKey().isBlank();
    boolean anyBatchKey =
        batchKey == null ? row != null && row.getApiKeyCiphertext() != null : !batchKey.isBlank();
    if (!RealtimeTranscriptionEndpoint.lacksRequiredKey(
        live.protocol(), live.url(), ownKey, batchAddress, anyBatchKey)) {
      return Optional.empty();
    }
    return Optional.of(
        new FieldError(
            TRANSCRIPTION_REALTIME + ".apiKey",
            ModelEndpointRules.REQUIRED,
            RealtimeTranscriptionEndpoint.KEY_RULE));
  }

  /** The rules of a transcription endpoint and its language, as field errors. */
  private static List<FieldError> transcriptionErrors(
      boolean enabled, String address, String identifier, String apiKey, String language) {
    List<FieldError> errors = new ArrayList<>();
    ModelEndpointRules.endpointErrors(TRANSCRIPTION, address, identifier, apiKey, enabled, errors);
    if (!TranscriptionEndpoint.isValidLanguage(language)) {
      errors.add(
          new FieldError(
              TRANSCRIPTION + ".language",
              ModelEndpointRules.INVALID,
              TranscriptionEndpoint.LANGUAGE_RULE));
    }
    return errors;
  }

  /**
   * The transcription role's own columns: {@code null} keeps what is stored, the factory language
   * for a first row, blank removes it.
   */
  private OwnColumns<TranscriptionModelSettings> transcriptionColumns(
      TranscriptionModelSettings row,
      boolean stored,
      String requestedLanguage,
      String requestedVocabulary,
      RealtimeChange realtime) {
    String storedLanguage = stored ? row.getLanguage() : TranscriptionEndpoint.DEFAULT_LANGUAGE;
    String language = requestedLanguage == null ? storedLanguage : emptyToNull(requestedLanguage);
    String storedVocabulary = stored ? row.getVocabulary() : null;
    String vocabulary =
        requestedVocabulary == null ? storedVocabulary : emptyToNull(requestedVocabulary);
    RealtimeColumns live = realtimeColumns(row, realtime);
    String previousLiveCiphertext = row.getRealtimeApiKeyCiphertext();
    return new OwnColumns<>() {
      @Override
      public boolean unchanged() {
        return Objects.equals(storedLanguage, language)
            && Objects.equals(storedVocabulary, vocabulary)
            && live.sameAs(row);
      }

      @Override
      public void replace(TranscriptionModelSettings target) {
        target.replaceOwn(language, vocabulary);
        target.replaceRealtime(
            live.protocol(), live.url(), live.model(), live.ciphertext(), live.maxSessions());
      }

      @Override
      public void annotate(Map<String, Object> after) {
        AuditSecretChange.put(after, "realtimeApiKey", previousLiveCiphertext, live.ciphertext());
      }
    };
  }

  /** The live columns a change stores; {@code null} protocol means none. */
  private record RealtimeColumns(
      RealtimeTranscriptionProtocol protocol,
      String url,
      String model,
      String ciphertext,
      Integer maxSessions) {

    boolean sameAs(TranscriptionModelSettings row) {
      return protocol == row.getRealtimeProtocol()
          && Objects.equals(url, row.getRealtimeUrl())
          && Objects.equals(model, row.getRealtimeModel())
          && Objects.equals(ciphertext, row.getRealtimeApiKeyCiphertext())
          && Objects.equals(maxSessions, row.getRealtimeMaxSessions());
    }
  }

  /**
   * The live columns for {@code change} on {@code row}: {@code null} keeps all, off removes all,
   * the key follows the rule of the batch key ({@link #secretOf}) for the live address.
   */
  private RealtimeColumns realtimeColumns(TranscriptionModelSettings row, RealtimeChange change) {
    if (change == null) {
      return new RealtimeColumns(
          row.getRealtimeProtocol(),
          row.getRealtimeUrl(),
          row.getRealtimeModel(),
          row.getRealtimeApiKeyCiphertext(),
          row.getRealtimeMaxSessions());
    }
    if (!change.enabled()) {
      return new RealtimeColumns(null, null, null, null, null);
    }
    String ciphertext =
        secretOf(
            TRANSCRIPTION_REALTIME,
            change.apiKey(),
            RealtimeTranscriptionEndpoint.httpEquivalent(row.getRealtimeUrl()),
            RealtimeTranscriptionEndpoint.httpEquivalent(change.url()),
            row.getRealtimeApiKeyCiphertext());
    Integer sessions =
        change.maxSessions() == null ? row.getRealtimeMaxSessions() : change.maxSessions();
    return new RealtimeColumns(
        change.protocol(), change.url(), change.model(), ciphertext, sessions);
  }

  /** A change with stripped texts; {@code null} stays {@code null}. */
  private static RealtimeChange normalised(RealtimeChange change) {
    if (change == null) {
      return null;
    }
    return new RealtimeChange(
        change.enabled(),
        change.protocol(),
        trimmed(change.url()),
        trimmed(change.model()),
        change.apiKey(),
        change.maxSessions());
  }

  /** The rules of a live upstream, as field errors under {@code transcription.realtime.}. */
  public static void realtimeErrors(RealtimeChange change, List<FieldError> errors) {
    realtimeErrors(TRANSCRIPTION_REALTIME, change, errors);
  }

  /** The rules of a live upstream, as field errors under {@code prefix}. */
  public static void realtimeErrors(String prefix, RealtimeChange change, List<FieldError> errors) {
    if (change == null || !change.enabled()) {
      return;
    }
    String url = trimmed(change.url());
    String model = trimmed(change.model());
    if (change.protocol() == null) {
      errors.add(
          new FieldError(
              prefix + ".protocol", ModelEndpointRules.REQUIRED, "Das Protokoll fehlt."));
    }
    if (url.isEmpty()) {
      errors.add(
          new FieldError(prefix + ".url", ModelEndpointRules.REQUIRED, "Die Live-Adresse fehlt."));
    } else if (!RealtimeTranscriptionEndpoint.isWebSocketAddress(url)) {
      errors.add(
          new FieldError(
              prefix + ".url",
              ModelEndpointRules.INVALID,
              "Die Live-Adresse muss mit ws:// oder wss:// beginnen."));
    } else if (ModelEndpointUri.containsCredentials(url)) {
      errors.add(
          new FieldError(
              prefix + ".url",
              ModelEndpointRules.INVALID,
              "Die Live-Adresse darf keine Anmeldedaten enthalten; der Schlüssel gehört in das Feld"
                  + " Live-Schlüssel."));
    }
    if (model.isEmpty()) {
      errors.add(
          new FieldError(prefix + ".model", ModelEndpointRules.REQUIRED, "Das Live-Modell fehlt."));
    }
    ModelEndpointRules.requireAtMost(errors, prefix + ".url", url, ModelEndpointRules.MAX_ADDRESS);
    ModelEndpointRules.requireAtMost(errors, prefix + ".model", model, ModelEndpointRules.MAX_NAME);
    ModelEndpointRules.requireAtMost(
        errors, prefix + ".apiKey", change.apiKey(), ModelEndpointRules.MAX_API_KEY);
    if (change.apiKey() != null
        && change.apiKey().strip().chars().anyMatch(c -> c < 0x20 || c == 0x7f)) {
      errors.add(
          new FieldError(
              prefix + ".apiKey",
              ModelEndpointRules.INVALID,
              "Der Zugangsschlüssel enthält Steuerzeichen wie einen Zeilenumbruch."));
    }
    Integer sessions = change.maxSessions();
    if (sessions != null
        && (sessions < 1 || sessions > RealtimeTranscriptionEndpoint.MAX_MAX_SESSIONS)) {
      errors.add(
          new FieldError(
              prefix + ".maxSessions",
              ModelEndpointRules.INVALID,
              "Die Zahl gleichzeitiger Live-Sitzungen liegt zwischen 1 und "
                  + RealtimeTranscriptionEndpoint.MAX_MAX_SESSIONS
                  + "."));
    }
  }

  /** The address check of a valid live address, compared as its http(s) equivalent. */
  private void blockedRealtimeAddressError(
      RealtimeChange change, String storedUrl, List<FieldError> errors) {
    if (change == null || !change.enabled() || !errors.isEmpty()) {
      return;
    }
    String blocked =
        modelAddresses.blockedReason(
            RealtimeTranscriptionEndpoint.httpEquivalent(change.url()),
            RealtimeTranscriptionEndpoint.httpEquivalent(storedUrl));
    if (blocked != null) {
      errors.add(
          new FieldError(
              TRANSCRIPTION_REALTIME + ".url",
              ModelEndpointRules.BLOCKED,
              blocked.replace("Basis-Adresse", "Live-Adresse")));
    }
  }

  private String storedRealtimeUrl() {
    return transcriptionTable
        .rows()
        .findStored()
        .map(TranscriptionModelSettings::getRealtimeUrl)
        .orElse(null);
  }

  /**
   * Opens a session of the draft live upstream and closes it without audio. {@code apiKey} {@code
   * null} sends the stored live key for the stored live address, or, while no live key of its own
   * is stored, the stored batch key to the origin of the batch address. Call outside any
   * transaction: it waits for the network.
   *
   * @throws FieldValidationException for a draft the rules refuse, under {@code
   *     transcription.realtime.}
   */
  public TestOutcome testTranscriptionRealtime(
      RealtimeTranscriptionProtocol protocol, String url, String model, String apiKey) {
    RealtimeChange change =
        normalised(new RealtimeChange(true, protocol, url, model, apiKey, null));
    List<FieldError> errors = new ArrayList<>();
    realtimeErrors(change, errors);
    TranscriptionModelSettings row = transcriptionTable.rows().findStored().orElse(null);
    String storedUrl = row == null ? null : row.getRealtimeUrl();
    blockedRealtimeAddressError(change, storedUrl, errors);
    requireNoErrors(errors);
    String key;
    if (apiKey != null) {
      key = apiKey.strip();
    } else if (row != null && row.getRealtimeApiKeyCiphertext() != null) {
      if (!ModelEndpointRules.sameEndpoint(
          RealtimeTranscriptionEndpoint.httpEquivalent(storedUrl),
          RealtimeTranscriptionEndpoint.httpEquivalent(change.url()))) {
        throw FieldValidationException.of(
            TRANSCRIPTION_REALTIME + ".apiKey",
            ModelEndpointRules.REQUIRED,
            "Der gespeicherte Live-Schlüssel gilt nur für die gespeicherte Live-Adresse:"
                + " Bitte den Schlüssel erneut eingeben oder das Feld leeren, um ohne Schlüssel zu"
                + " prüfen.");
      }
      key = decryptForTest(row.getRealtimeApiKeyCiphertext(), TRANSCRIPTION_REALTIME);
    } else if (row != null
        && row.getApiKeyCiphertext() != null
        && RealtimeTranscriptionEndpoint.sharesOrigin(change.url(), row.getBaseUrl())) {
      key = decryptForTest(row.getApiKeyCiphertext(), TRANSCRIPTION);
    } else {
      key = "";
    }
    return probeTranscriptionRealtime(change.protocol(), change.url(), change.model(), key);
  }

  private String decryptForTest(String ciphertext, String prefix) {
    try {
      return settingsEncryptor.decrypt(ciphertext, rowOf(prefix));
    } catch (IllegalStateException e) {
      throw FieldValidationException.of(
          TRANSCRIPTION_REALTIME + ".apiKey",
          ModelEndpointRules.INVALID,
          "Der gespeicherte Zugangsschlüssel ist nicht lesbar; bitte den Schlüssel erneut"
              + " eingeben.");
    }
  }

  /**
   * The probe of {@link #testTranscriptionRealtime}, without an address check of its own; the same
   * precondition as {@link #probeRerank}. Sends no audio. Call outside any transaction.
   */
  public TestOutcome probeTranscriptionRealtime(
      RealtimeTranscriptionProtocol protocol, String url, String model, String apiKey) {
    RealtimeTranscriptionClient.ProbeFailure failure =
        realtimeClient.probe(
            new RealtimeTranscriptionEndpoint(protocol, url, model, apiKey, 1), TEST_TIMEOUT);
    return failure == null
        ? new TestOutcome(
            true, "Verbindung erfolgreich, der Live-Dienst hat eine Sitzung angenommen.")
        : new TestOutcome(false, german(failure.message(), failure.timedOut()));
  }

  /**
   * Stores the text recognition role, as {@link #changeRerank}; {@code model} is stored for a
   * vision model only. Switching the role on is refused while the operation's switch is off.
   *
   * @throws FieldValidationException naming every violated field under {@code textRecognition.}
   */
  public TextRecognitionModelView changeTextRecognition(
      UUID organizationId,
      UUID actorUserId,
      boolean enabled,
      TextRecognitionConnectionType connectionType,
      String baseUrl,
      String model,
      String apiKey) {
    TextRecognitionConnectionType type =
        connectionType == null ? TextRecognitionEndpoint.DEFAULT_CONNECTION_TYPE : connectionType;
    String address = trimmed(baseUrl);
    String identifier = TextRecognitionEndpoint.needsModel(type) ? trimmed(model) : "";
    List<FieldError> errors = textRecognitionErrors(enabled, type, address, identifier, apiKey);
    blockedAddressError(TEXT_RECOGNITION, address, storedAddress(textRecognitionTable), errors);
    requireNoErrors(errors);
    return transactions.execute(
        status ->
            textRecognitionView(
                store(
                    textRecognitionTable,
                    organizationId,
                    AuditActor.user(actorUserId),
                    enabled,
                    address,
                    identifier,
                    changedSecret(TEXT_RECOGNITION, apiKey, address),
                    (row, stored) -> textRecognitionColumns(row, type))));
  }

  /**
   * Switches the text recognition role on with the checked step of the installation, as {@link
   * #installRerank} (ADR-0060, Entscheidung 18). The operation's limit applies as in {@link
   * #changeTextRecognition}: while {@code opaa.ocr.enabled} is off this refuses, so the
   * installation calls it only while it offers the step.
   *
   * @throws FieldValidationException for an endpoint the rules of the role refuse
   */
  public TextRecognitionModelView installTextRecognition(
      UUID organizationId,
      String systemActor,
      TextRecognitionConnectionType connectionType,
      String baseUrl,
      String model,
      String apiKey) {
    TextRecognitionConnectionType type =
        connectionType == null ? TextRecognitionEndpoint.DEFAULT_CONNECTION_TYPE : connectionType;
    String address = trimmed(baseUrl);
    String identifier = TextRecognitionEndpoint.needsModel(type) ? trimmed(model) : "";
    requireNoErrors(textRecognitionErrors(true, type, address, identifier, null));
    return transactions.execute(
        status ->
            textRecognitionView(
                store(
                    textRecognitionTable,
                    organizationId,
                    AuditActor.systemProcess(systemActor),
                    true,
                    address,
                    identifier,
                    row -> encryptInstalled(TEXT_RECOGNITION, apiKey),
                    (row, stored) -> textRecognitionColumns(row, type))));
  }

  private static List<FieldError> textRecognitionErrors(
      boolean enabled,
      TextRecognitionConnectionType type,
      String address,
      String identifier,
      String apiKey) {
    return textRecognitionErrors(TEXT_RECOGNITION, enabled, type, address, identifier, apiKey);
  }

  /**
   * The rules of a text recognition endpoint as field errors under {@code prefix}: those of every
   * model endpoint, an address while switched on, and a model only for a vision model. {@code
   * address} and {@code identifier} are trimmed, never {@code null}.
   */
  public static List<FieldError> textRecognitionErrors(
      String prefix,
      boolean enabled,
      TextRecognitionConnectionType type,
      String address,
      String identifier,
      String apiKey) {
    List<FieldError> errors = new ArrayList<>();
    ModelEndpointRules.endpointErrors(prefix, address, identifier, apiKey, false, errors);
    if (enabled && address.isEmpty()) {
      errors.add(
          new FieldError(
              prefix + ".baseUrl", ModelEndpointRules.REQUIRED, "Die Basis-Adresse fehlt."));
    }
    if (enabled && TextRecognitionEndpoint.needsModel(type) && identifier.isEmpty()) {
      errors.add(
          new FieldError(
              prefix + ".model",
              ModelEndpointRules.REQUIRED,
              "Die Modell-Kennung fehlt; ein Vision-Modell braucht sie."));
    }
    return errors;
  }

  private static OwnColumns<TextRecognitionModelSettings> textRecognitionColumns(
      TextRecognitionModelSettings row, TextRecognitionConnectionType type) {
    return new OwnColumns<>() {
      @Override
      public boolean unchanged() {
        return row.getConnectionType() == type;
      }

      @Override
      public void replace(TextRecognitionModelSettings target) {
        target.replaceOwn(type);
      }
    };
  }

  /**
   * Tests a draft of the text recognition role with the shipped test page; never sends content of a
   * library. Refused while the operation's switch is off, so no service is contacted. Call outside
   * any transaction: it waits for the network.
   *
   * @throws FieldValidationException for a draft the rules refuse, under {@code textRecognition.}
   */
  public TextRecognitionTestOutcome testTextRecognition(
      TextRecognitionConnectionType connectionType, String baseUrl, String model, String apiKey) {
    if (!textRecognitionRole.operationEnabled()) {
      throw FieldValidationException.of(
          TEXT_RECOGNITION + ".enabled", ModelEndpointRules.BLOCKED, TEXT_RECOGNITION_OFF);
    }
    TextRecognitionConnectionType type =
        connectionType == null ? TextRecognitionEndpoint.DEFAULT_CONNECTION_TYPE : connectionType;
    String address = trimmed(baseUrl);
    String identifier = TextRecognitionEndpoint.needsModel(type) ? trimmed(model) : "";
    String key =
        testKey(
            TEXT_RECOGNITION,
            address,
            apiKey,
            storedAddress(textRecognitionTable),
            storedCiphertext(textRecognitionTable),
            textRecognitionErrors(true, type, address, identifier, apiKey));
    return probeTextRecognition(type, address, identifier, key);
  }

  /**
   * The test of {@link #testTextRecognition} with the shipped test page, without an address check
   * of its own; the same precondition as {@link #probeRerank}. Refused while the operation's switch
   * is off. Call outside any transaction: the first conversion may take minutes.
   */
  public TextRecognitionTestOutcome probeTextRecognition(
      TextRecognitionConnectionType connectionType, String baseUrl, String model, String apiKey) {
    if (!textRecognitionRole.operationEnabled()) {
      throw FieldValidationException.of(
          TEXT_RECOGNITION + ".enabled", ModelEndpointRules.BLOCKED, TEXT_RECOGNITION_OFF);
    }
    TextRecognitionConnectionType type =
        connectionType == null ? TextRecognitionEndpoint.DEFAULT_CONNECTION_TYPE : connectionType;
    TextRecognitionClient.TestResult result =
        textRecognitionClient.test(
            new TextRecognitionEndpoint(true, type, baseUrl, model, apiKey),
            TEXT_RECOGNITION_TEST_TIMEOUT);
    return new TextRecognitionTestOutcome(
        result.outcome() == TextRecognitionClient.TestOutcome.READY,
        result.outcome(),
        result.failedStage(),
        textRecognitionMessage(type, result));
  }

  /** The German message of a text recognition test result. */
  static String textRecognitionMessage(
      TextRecognitionConnectionType type, TextRecognitionClient.TestResult result) {
    return switch (result.outcome()) {
      case READY -> "Verbindung erfolgreich, der Dienst hat die Testseite gelesen.";
      case KEY_REJECTED ->
          "Der Dienst hat den Zugangsschlüssel abgelehnt (HTTP 401 oder 403). Prüfen Sie den"
              + " Zugangsschlüssel.";
      case ENGINE_MISSING ->
          type == TextRecognitionConnectionType.DOCLING_SERVE
              ? "Der Dienst ist erreichbar, kann die Testseite aber nicht mit RapidOCR lesen."
                  + " Vermutlich fehlen die OCR-Engine oder ihre Modelle."
              : "Der Endpunkt ist erreichbar, bietet das Modell aber nicht an oder kann mit ihm"
                  + " keine Bilder lesen. Prüfen Sie die Modell-Kennung.";
      case NO_TEXT -> "Der Dienst hat geantwortet, auf der Testseite aber keinen Text erkannt.";
      case UNREACHABLE ->
          result.failedStage() == TextRecognitionClient.FailedStage.RECOGNITION
              ? "Der Dienst ist erreichbar und hat den Zugangsschlüssel angenommen, beim Lesen der"
                  + " Testseite aber "
                  + (result.timedOut()
                      ? "nicht rechtzeitig geantwortet."
                      : "nicht verwertbar geantwortet: " + german(result.diagnostic(), false))
              : german(result.diagnostic(), result.timedOut());
    };
  }

  /**
   * Sends one rerank request with a test document to the draft endpoint. Call outside any
   * transaction: it waits for the network.
   */
  public TestOutcome testRerank(String baseUrl, String model, String apiKey) {
    String address = trimmed(baseUrl);
    String identifier = trimmed(model);
    String key =
        testKey(
            RERANK,
            address,
            identifier,
            apiKey,
            storedAddress(rerankTable),
            storedCiphertext(rerankTable));
    return probeRerank(address, identifier, key);
  }

  /**
   * The probe of {@link #testRerank}, without an address check of its own. Precondition: the caller
   * has passed {@code baseUrl} through {@link ModelAddressPolicy} and {@link ModelEndpointRules}
   * right before; a caller that cannot vouch for the address must use {@link #testRerank}. {@code
   * apiKey} is plain text, {@code null} or blank for none. Call outside any transaction.
   */
  public TestOutcome probeRerank(String baseUrl, String model, String apiKey) {
    RerankClient.ProbeFailure failure =
        rerankClient.probe(new RerankEndpoint(true, baseUrl, model, apiKey), TEST_TIMEOUT);
    return failure == null
        ? new TestOutcome(true, "Verbindung erfolgreich, der Endpunkt hat die Probe bewertet.")
        : new TestOutcome(false, german(failure.message(), failure.timedOut()));
  }

  /** Asks the draft endpoint for its models; sends no audio. Call outside any transaction. */
  public TestOutcome testTranscription(String baseUrl, String model, String apiKey) {
    String address = trimmed(baseUrl);
    String identifier = trimmed(model);
    String key =
        testKey(
            TRANSCRIPTION,
            address,
            identifier,
            apiKey,
            storedAddress(transcriptionTable),
            storedCiphertext(transcriptionTable));
    return probeTranscription(address, identifier, key);
  }

  /**
   * The probe of {@link #testTranscription}, without an address check of its own; the same
   * precondition as {@link #probeRerank}. Asks for the models, sends no audio. Call outside any
   * transaction.
   */
  public TestOutcome probeTranscription(String baseUrl, String model, String apiKey) {
    TranscriptionClient.ProbeFailure failure =
        transcriptionClient.probe(
            new TranscriptionEndpoint(true, baseUrl, model, apiKey, ""), TEST_TIMEOUT);
    return failure == null
        ? new TestOutcome(true, "Verbindung erfolgreich, der Endpunkt hat geantwortet.")
        : new TestOutcome(false, german(failure.message(), failure.timedOut()));
  }

  /**
   * Validates a draft and returns the key to send; an address other than the stored one passes the
   * address check first.
   */
  private String testKey(
      String prefix,
      String address,
      String identifier,
      String apiKey,
      String storedBaseUrl,
      String storedCiphertext) {
    List<FieldError> errors = new ArrayList<>();
    ModelEndpointRules.endpointErrors(prefix, address, identifier, apiKey, true, errors);
    return testKey(prefix, address, apiKey, storedBaseUrl, storedCiphertext, errors);
  }

  /** As above, with the role's own rule violations of the draft already in {@code errors}. */
  private String testKey(
      String prefix,
      String address,
      String apiKey,
      String storedBaseUrl,
      String storedCiphertext,
      List<FieldError> errors) {
    blockedAddressError(prefix, address, storedBaseUrl, errors);
    requireNoErrors(errors);
    if (apiKey != null) {
      return apiKey.strip();
    }
    if (storedCiphertext == null) {
      return "";
    }
    if (!ModelEndpointRules.sameEndpoint(storedBaseUrl, address)) {
      throw FieldValidationException.of(
          prefix + ".apiKey",
          ModelEndpointRules.REQUIRED,
          "Der gespeicherte Zugangsschlüssel gilt nur für die gespeicherte Basis-Adresse: Bitte den"
              + " Schlüssel erneut eingeben oder das Feld leeren, um ohne Schlüssel zu prüfen.");
    }
    try {
      return settingsEncryptor.decrypt(storedCiphertext, rowOf(prefix));
    } catch (IllegalStateException e) {
      throw FieldValidationException.of(
          prefix + ".apiKey",
          ModelEndpointRules.INVALID,
          "Der gespeicherte Zugangsschlüssel ist nicht lesbar; bitte den Schlüssel erneut"
              + " eingeben.");
    }
  }

  private RerankModelView rerankView(RerankModelSettings row) {
    RerankEndpoint endpoint =
        row == null
            ? RerankEndpoint.FACTORY_DEFAULT
            : new RerankEndpoint(row.isEnabled(), row.getBaseUrl(), row.getModelIdentifier(), "");
    return new RerankModelView(
        endpoint.enabled(),
        row == null ? null : row.getBaseUrl(),
        row == null ? null : row.getModelIdentifier(),
        row != null && row.getApiKeyCiphertext() != null,
        rerankRole.statusFor(endpoint),
        row == null ? null : row.getUpdatedAt(),
        RetiredModelRoleVariables.setIn(environment, RetiredModelRoleVariables.RERANK));
  }

  private TranscriptionModelView transcriptionView(TranscriptionModelSettings row) {
    TranscriptionEndpoint endpoint =
        row == null
            ? TranscriptionEndpoint.FACTORY_DEFAULT
            : new TranscriptionEndpoint(
                row.isEnabled(),
                row.getBaseUrl(),
                row.getModelIdentifier(),
                "",
                row.getLanguage(),
                row.getVocabulary());
    return new TranscriptionModelView(
        endpoint.enabled(),
        row == null ? null : row.getBaseUrl(),
        row == null ? null : row.getModelIdentifier(),
        row != null && row.getApiKeyCiphertext() != null,
        endpoint.language(),
        endpoint.vocabulary(),
        realtimeView(row, endpoint),
        transcriptionRole.statusFor(endpoint),
        speechInputAvailable,
        row == null ? null : row.getUpdatedAt(),
        RetiredModelRoleVariables.setIn(environment, RetiredModelRoleVariables.TRANSCRIPTION));
  }

  private RealtimeView realtimeView(TranscriptionModelSettings row, TranscriptionEndpoint batch) {
    if (row == null || row.getRealtimeProtocol() == null) {
      return new RealtimeView(
          false,
          null,
          null,
          null,
          false,
          RealtimeTranscriptionEndpoint.DEFAULT_MAX_SESSIONS,
          OptionalRoleStatus.disabled());
    }
    RealtimeTranscriptionEndpoint live =
        new RealtimeTranscriptionEndpoint(
            row.getRealtimeProtocol(),
            row.getRealtimeUrl(),
            row.getRealtimeModel(),
            "",
            row.getRealtimeMaxSessions() == null
                ? RealtimeTranscriptionEndpoint.DEFAULT_MAX_SESSIONS
                : row.getRealtimeMaxSessions());
    return new RealtimeView(
        true,
        live.protocol(),
        row.getRealtimeUrl(),
        row.getRealtimeModel(),
        row.getRealtimeApiKeyCiphertext() != null,
        live.maxSessions(),
        realtimeRole.realtimeStatusFor(
            new TranscriptionEndpoint(
                batch.enabled(),
                batch.baseUrl(),
                batch.model(),
                "",
                batch.language(),
                batch.vocabulary(),
                live)));
  }

  private TextRecognitionModelView textRecognitionView(TextRecognitionModelSettings row) {
    TextRecognitionEndpoint endpoint =
        row == null
            ? TextRecognitionEndpoint.FACTORY_DEFAULT
            : new TextRecognitionEndpoint(
                row.isEnabled(),
                row.getConnectionType(),
                row.getBaseUrl(),
                row.getModelIdentifier(),
                "");
    return new TextRecognitionModelView(
        endpoint.enabled(),
        endpoint.connectionType(),
        row == null ? null : row.getBaseUrl(),
        row == null ? null : row.getModelIdentifier(),
        row != null && row.getApiKeyCiphertext() != null,
        textRecognitionRole.statusFor(endpoint),
        textRecognitionRole.operationEnabled(),
        row == null ? null : row.getUpdatedAt());
  }

  private static String storedAddress(RoleTable<?> table) {
    return table.rows().findStored().map(OptionalRoleSettings::getBaseUrl).orElse(null);
  }

  private static String storedCiphertext(RoleTable<?> table) {
    return table.rows().findStored().map(OptionalRoleSettings::getApiKeyCiphertext).orElse(null);
  }

  /** The address check of a valid address, as a field error under {@code prefix}. */
  private void blockedAddressError(
      String prefix, String baseUrl, String storedBaseUrl, List<FieldError> errors) {
    if (errors.isEmpty() && !baseUrl.isEmpty()) {
      String blocked = modelAddresses.blockedReason(baseUrl, storedBaseUrl);
      if (blocked != null) {
        errors.add(new FieldError(prefix + ".baseUrl", ModelEndpointRules.BLOCKED, blocked));
      }
    }
  }

  /**
   * The ciphertext to store: a new key encrypted, a blank one removed, none kept from {@code
   * previousCiphertext} only for an unchanged endpoint.
   */
  private String secretOf(
      String prefix,
      String apiKey,
      String previousBaseUrl,
      String baseUrl,
      String previousCiphertext) {
    if (apiKey == null) {
      if (previousCiphertext != null
          && !ModelEndpointRules.sameEndpoint(previousBaseUrl, baseUrl)) {
        throw FieldValidationException.of(
            prefix + ".apiKey",
            ModelEndpointRules.REQUIRED,
            "Die Basis-Adresse hat sich geändert: Bitte den Zugangsschlüssel erneut eingeben oder"
                + " das Feld leeren, um ihn zu entfernen.");
      }
      return previousCiphertext;
    }
    if (apiKey.isBlank()) {
      return null;
    }
    try {
      return settingsEncryptor.encrypt(apiKey.strip(), rowOf(prefix));
    } catch (IllegalStateException e) {
      throw FieldValidationException.of(
          prefix + ".apiKey",
          ModelEndpointRules.INVALID,
          "Der Zugangsschlüssel kann nicht gespeichert werden: OPAA_SETTINGS_ENCRYPTION_KEY ist"
              + " nicht gesetzt oder ungültig.");
    }
  }

  /** The binding of the key of the role named by the field prefix {@code prefix}. */
  private static CipherBinding rowOf(String prefix) {
    return switch (prefix) {
      case RERANK -> LlmEncryptedColumns.rerankModelKey();
      case TRANSCRIPTION ->
          LlmEncryptedColumns.TRANSCRIPTION_MODEL_KEY.row(TranscriptionModelSettings.SINGLETON_ID);
      case TEXT_RECOGNITION -> LlmEncryptedColumns.textRecognitionModelKey();
      case TRANSCRIPTION_REALTIME -> LlmEncryptedColumns.transcriptionRealtimeKey();
      default -> throw new IllegalArgumentException("no model role " + prefix);
    };
  }

  private void record(
      UUID organizationId,
      AuditActor actor,
      AuditEventType type,
      String table,
      String label,
      Map<String, Object> before,
      Map<String, Object> after) {
    AuditEvent.Builder event =
        AuditEvent.builder()
            .organizationId(organizationId)
            .type(type)
            .object(
                AuditObjectType.SYSTEM_SETTING,
                UUID.nameUUIDFromBytes(table.getBytes(StandardCharsets.UTF_8)),
                label)
            .before(before)
            .after(after)
            .outcome(AuditOutcome.SUCCESS);
    if (actor.systemProcess() != null) {
      auditEventRecorder.recordSystemProcessAction(event.actorRef(actor.systemProcess()).build());
    } else {
      auditEventRecorder.recordUserAction(event.actor(actor.userId()).build());
    }
  }

  /**
   * The audit state of a row, the factory default for {@code null}; never the key itself, and the
   * address without userinfo, query and fragment, where a token could stand.
   */
  private static Map<String, Object> rerankPayload(RerankModelSettings row) {
    Map<String, Object> state = new LinkedHashMap<>();
    state.put("enabled", row != null && row.isEnabled());
    putIfPresent(
        state, "baseUrl", row == null ? null : ModelEndpointUri.withoutSecrets(row.getBaseUrl()));
    putIfPresent(state, "model", row == null ? null : row.getModelIdentifier());
    state.put("apiKeySet", row != null && row.getApiKeyCiphertext() != null);
    return state;
  }

  private static Map<String, Object> transcriptionPayload(TranscriptionModelSettings row) {
    Map<String, Object> state = new LinkedHashMap<>();
    state.put("enabled", row != null && row.isEnabled());
    putIfPresent(
        state, "baseUrl", row == null ? null : ModelEndpointUri.withoutSecrets(row.getBaseUrl()));
    putIfPresent(state, "model", row == null ? null : row.getModelIdentifier());
    state.put("apiKeySet", row != null && row.getApiKeyCiphertext() != null);
    String language = row == null ? TranscriptionEndpoint.DEFAULT_LANGUAGE : row.getLanguage();
    state.put("language", language == null ? "" : language);
    putIfPresent(state, "vocabulary", row == null ? null : row.getVocabulary());
    if (row != null && row.getRealtimeProtocol() != null) {
      state.put("realtimeProtocol", row.getRealtimeProtocol().name());
      putIfPresent(state, "realtimeUrl", ModelEndpointUri.withoutSecrets(row.getRealtimeUrl()));
      putIfPresent(state, "realtimeModel", row.getRealtimeModel());
      state.put("realtimeApiKeySet", row.getRealtimeApiKeyCiphertext() != null);
      state.put(
          "realtimeMaxSessions",
          row.getRealtimeMaxSessions() == null
              ? RealtimeTranscriptionEndpoint.DEFAULT_MAX_SESSIONS
              : row.getRealtimeMaxSessions());
    }
    return state;
  }

  private static Map<String, Object> textRecognitionPayload(TextRecognitionModelSettings row) {
    Map<String, Object> state = new LinkedHashMap<>();
    state.put("enabled", row != null && row.isEnabled());
    state.put(
        "connectionType",
        (row == null ? TextRecognitionEndpoint.DEFAULT_CONNECTION_TYPE : row.getConnectionType())
            .name());
    putIfPresent(
        state, "baseUrl", row == null ? null : ModelEndpointUri.withoutSecrets(row.getBaseUrl()));
    putIfPresent(state, "model", row == null ? null : row.getModelIdentifier());
    state.put("apiKeySet", row != null && row.getApiKeyCiphertext() != null);
    return state;
  }

  private static void putIfPresent(Map<String, Object> state, String key, String value) {
    if (value != null) {
      state.put(key, value);
    }
  }

  /**
   * Drops this instance's cache and announces the change once it is committed; the role's startup
   * check probes the new endpoint on the role's own scheduler.
   */
  private void afterCommit(ModelRoleSettingsChangedEvent.Role role) {
    TransactionCallbacks.afterCommitOrNow(
        () -> {
          endpoints.invalidateAll();
          events.publishEvent(new ModelRoleSettingsChangedEvent(role));
        });
  }

  /** A client failure, technical English by contract, as a German message for the page. */
  static String german(String diagnostic, boolean timedOut) {
    String text = diagnostic == null ? "" : diagnostic;
    if (timedOut || text.contains("timed out")) {
      return "Die Verbindung ist in ein Zeitlimit gelaufen.";
    }
    if (text.startsWith("HTTP 401")) {
      return "Die Authentifizierung ist fehlgeschlagen (HTTP 401). Prüfen Sie den Zugangsschlüssel.";
    }
    if (text.startsWith("HTTP 403")) {
      return "Der Zugriff wurde vom Server verweigert (HTTP 403).";
    }
    if (text.startsWith("HTTP 404")) {
      return "Endpunkt oder Modell wurde nicht gefunden (HTTP 404). Prüfen Sie Basis-Adresse und"
          + " Modell-Kennung.";
    }
    if (text.startsWith("HTTP ")) {
      String status = text.substring(5).split("[^0-9]", 2)[0];
      return "Der Server antwortete mit HTTP " + status + ".";
    }
    if (text.startsWith("host not found")) {
      return "Der Host konnte nicht gefunden werden (DNS-Auflösung fehlgeschlagen).";
    }
    if (text.startsWith("connection refused")) {
      return "Die Verbindung wurde vom Server abgelehnt.";
    }
    if (text.startsWith("TLS")) {
      return "Das Zertifikat des Servers konnte nicht geprüft werden.";
    }
    return "Der Endpunkt ist nicht erreichbar oder hat unerwartet geantwortet.";
  }

  private static void requireNoErrors(List<FieldError> errors) {
    if (!errors.isEmpty()) {
      throw new FieldValidationException(errors.get(0).message(), errors);
    }
  }

  private static String trimmed(String value) {
    return value == null ? "" : value.strip();
  }

  private static String emptyToNull(String value) {
    return value == null || value.isEmpty() ? null : value;
  }
}
