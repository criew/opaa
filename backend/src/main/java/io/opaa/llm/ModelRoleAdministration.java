package io.opaa.llm;

import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditOutcome;
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
import java.util.UUID;
import java.util.function.Function;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * What the system administration does with the rerank and transcription model roles (ADR-0060,
 * Entscheidung 10): read, change and test them; the installation stores a checked rerank or
 * transcription step through the same write path (Entscheidung 16 for transcription). A change is
 * validated like every model endpoint ({@link ModelEndpointRules}, {@link ModelAddressPolicy} for
 * an address other than the stored one), stored with the key encrypted, and recorded with the
 * values before and after (Entscheidung 11); a change to the same values writes nothing. The
 * speech-input feature is the operation's limit: the transcription role cannot be switched on while
 * it is off (Entscheidung 2).
 *
 * <p>The change reaches every instance through the triggers on the two tables; this instance drops
 * its cache and probes the new endpoint right after the commit, so the state on the page follows
 * without waiting for the next scheduled probe.
 */
@Service
public class ModelRoleAdministration {

  /** The budget of a test request from the administration, below the runtime timeouts. */
  static final Duration TEST_TIMEOUT = Duration.ofSeconds(30);

  static final String RERANK = "rerank";
  static final String TRANSCRIPTION = "transcription";

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
      RerankRoleStatus status,
      Instant updatedAt,
      List<String> retiredVariables) {}

  /** The transcription role as the page shows it, with the operation's limit. */
  public record TranscriptionModelView(
      boolean enabled,
      String baseUrl,
      String model,
      boolean apiKeySet,
      String language,
      TranscriptionRoleStatus status,
      boolean speechInputAvailable,
      Instant updatedAt,
      List<String> retiredVariables) {}

  /** A test result with a German message. */
  public record TestOutcome(boolean success, String message) {}

  private final RerankModelSettingsRepository rerankRows;
  private final TranscriptionModelSettingsRepository transcriptionRows;
  private final ModelRoleEndpoints endpoints;
  private final RerankRoleStatusResolver rerankRole;
  private final TranscriptionRoleStatusResolver transcriptionRole;
  private final boolean speechInputAvailable;
  private final RerankClient rerankClient;
  private final TranscriptionClient transcriptionClient;
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
      ModelRoleEndpoints endpoints,
      RerankRoleStatusResolver rerankRole,
      TranscriptionRoleStatusResolver transcriptionRole,
      FeatureFlags featureFlags,
      RerankClient rerankClient,
      TranscriptionClient transcriptionClient,
      SettingsEncryptor settingsEncryptor,
      ModelAddressPolicy modelAddresses,
      AuditEventRecorder auditEventRecorder,
      Environment environment,
      ApplicationEventPublisher events,
      PlatformTransactionManager transactionManager,
      JdbcTemplate jdbc,
      Clock clock) {
    this.rerankRows = rerankRows;
    this.transcriptionRows = transcriptionRows;
    this.endpoints = endpoints;
    this.rerankRole = rerankRole;
    this.transcriptionRole = transcriptionRole;
    this.speechInputAvailable = featureFlags.isEnabled(Feature.SPEECH_INPUT);
    this.rerankClient = rerankClient;
    this.transcriptionClient = transcriptionClient;
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
    return rerankRows.findStored().map(this::rerankView).orElseGet(() -> rerankView(null));
  }

  @Transactional(readOnly = true)
  public TranscriptionModelView transcription() {
    return transcriptionRows
        .findStored()
        .map(this::transcriptionView)
        .orElseGet(() -> transcriptionView(null));
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
    blockedAddressError(RERANK, address, storedRerankAddress(), errors);
    requireNoErrors(errors);
    return transactions.execute(
        status ->
            storeRerank(
                organizationId,
                AuditActor.user(actorUserId),
                enabled,
                address,
                identifier,
                row ->
                    secretOf(
                        RERANK, apiKey, row.getBaseUrl(), address, row.getApiKeyCiphertext())));
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
            storeRerank(
                organizationId,
                AuditActor.systemProcess(systemActor),
                true,
                address,
                identifier,
                row -> encryptInstalled(RERANK, apiKey)));
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

  /** {@code ciphertextOf} derives the key to store from the stored row, under the table lock. */
  private RerankModelView storeRerank(
      UUID organizationId,
      AuditActor actor,
      boolean enabled,
      String address,
      String identifier,
      Function<RerankModelSettings, String> ciphertextOf) {
    jdbc.execute("LOCK TABLE rerank_model IN SHARE ROW EXCLUSIVE MODE");
    RerankModelSettings existing = rerankRows.findStored().orElse(null);
    RerankModelSettings row =
        existing == null ? new RerankModelSettings(clock.instant()) : existing;
    String previousCiphertext = row.getApiKeyCiphertext();
    String ciphertext = ciphertextOf.apply(row);
    boolean unchanged =
        row.isEnabled() == enabled
            && Objects.equals(row.getBaseUrl(), emptyToNull(address))
            && Objects.equals(row.getModelIdentifier(), emptyToNull(identifier))
            && Objects.equals(previousCiphertext, ciphertext);
    if (unchanged) {
      return rerankView(existing);
    }
    Map<String, Object> before = rerankPayload(existing);
    row.replace(
        enabled, emptyToNull(address), emptyToNull(identifier), ciphertext, clock.instant());
    rerankRows.save(row);
    Map<String, Object> after = rerankPayload(row);
    AuditSecretChange.put(after, "apiKey", previousCiphertext, ciphertext);
    record(
        organizationId,
        actor,
        AuditEventType.RERANK_MODEL_CHANGED,
        "rerank_model",
        "Rerank-Modell",
        before,
        after);
    afterCommit(ModelRoleSettingsChangedEvent.Role.RERANK);
    return rerankView(row);
  }

  /**
   * Stores the transcription role, as {@link #changeRerank}. {@code language} {@code null} keeps
   * the stored language, blank lets the model detect it.
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
      String language) {
    String address = trimmed(baseUrl);
    String identifier = trimmed(model);
    String lang = TranscriptionEndpoint.normalisedLanguage(language);
    List<FieldError> errors = transcriptionErrors(enabled, address, identifier, apiKey, lang);
    blockedAddressError(TRANSCRIPTION, address, storedTranscriptionAddress(), errors);
    requireNoErrors(errors);
    return transactions.execute(
        status ->
            storeTranscription(
                organizationId,
                AuditActor.user(actorUserId),
                enabled,
                address,
                identifier,
                lang,
                row ->
                    secretOf(
                        TRANSCRIPTION,
                        apiKey,
                        row.getBaseUrl(),
                        address,
                        row.getApiKeyCiphertext())));
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
      String language) {
    String address = trimmed(baseUrl);
    String identifier = trimmed(model);
    String lang = TranscriptionEndpoint.normalisedLanguage(language);
    requireNoErrors(transcriptionErrors(true, address, identifier, null, lang));
    return transactions.execute(
        status ->
            storeTranscription(
                organizationId,
                AuditActor.systemProcess(systemActor),
                true,
                address,
                identifier,
                lang,
                row -> encryptInstalled(TRANSCRIPTION, apiKey)));
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

  /** {@code ciphertextOf} derives the key to store from the stored row, under the table lock. */
  private TranscriptionModelView storeTranscription(
      UUID organizationId,
      AuditActor actor,
      boolean enabled,
      String address,
      String identifier,
      String requestedLanguage,
      Function<TranscriptionModelSettings, String> ciphertextOf) {
    jdbc.execute("LOCK TABLE transcription_model IN SHARE ROW EXCLUSIVE MODE");
    TranscriptionModelSettings existing = transcriptionRows.findStored().orElse(null);
    boolean stored = existing != null;
    TranscriptionModelSettings row =
        stored ? existing : new TranscriptionModelSettings(clock.instant());
    if (enabled && !speechInputAvailable && !(stored && row.isEnabled())) {
      throw FieldValidationException.of(TRANSCRIPTION + ".enabled", "BLOCKED", SPEECH_INPUT_OFF);
    }
    String storedLanguage = stored ? row.getLanguage() : TranscriptionEndpoint.DEFAULT_LANGUAGE;
    String language = requestedLanguage == null ? storedLanguage : emptyToNull(requestedLanguage);
    String previousCiphertext = row.getApiKeyCiphertext();
    String ciphertext = ciphertextOf.apply(row);
    boolean unchanged =
        row.isEnabled() == enabled
            && Objects.equals(row.getBaseUrl(), emptyToNull(address))
            && Objects.equals(row.getModelIdentifier(), emptyToNull(identifier))
            && Objects.equals(previousCiphertext, ciphertext)
            && Objects.equals(storedLanguage, language);
    if (unchanged) {
      return transcriptionView(existing);
    }
    Map<String, Object> before = transcriptionPayload(existing);
    row.replace(
        enabled,
        emptyToNull(address),
        emptyToNull(identifier),
        ciphertext,
        language,
        clock.instant());
    transcriptionRows.save(row);
    Map<String, Object> after = transcriptionPayload(row);
    AuditSecretChange.put(after, "apiKey", previousCiphertext, ciphertext);
    record(
        organizationId,
        actor,
        AuditEventType.TRANSCRIPTION_MODEL_CHANGED,
        "transcription_model",
        "Transkriptionsmodell",
        before,
        after);
    afterCommit(ModelRoleSettingsChangedEvent.Role.TRANSCRIPTION);
    return transcriptionView(row);
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
            storedRerankAddress(),
            rerankRows.findStored().map(RerankModelSettings::getApiKeyCiphertext).orElse(null));
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
            storedTranscriptionAddress(),
            transcriptionRows
                .findStored()
                .map(TranscriptionModelSettings::getApiKeyCiphertext)
                .orElse(null));
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
                row.isEnabled(), row.getBaseUrl(), row.getModelIdentifier(), "", row.getLanguage());
    return new TranscriptionModelView(
        endpoint.enabled(),
        row == null ? null : row.getBaseUrl(),
        row == null ? null : row.getModelIdentifier(),
        row != null && row.getApiKeyCiphertext() != null,
        endpoint.language(),
        transcriptionRole.statusFor(endpoint),
        speechInputAvailable,
        row == null ? null : row.getUpdatedAt(),
        RetiredModelRoleVariables.setIn(environment, RetiredModelRoleVariables.TRANSCRIPTION));
  }

  private String storedRerankAddress() {
    return rerankRows.findStored().map(RerankModelSettings::getBaseUrl).orElse(null);
  }

  private String storedTranscriptionAddress() {
    return transcriptionRows.findStored().map(TranscriptionModelSettings::getBaseUrl).orElse(null);
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
