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
import io.opaa.security.SettingsEncryptor;
import io.opaa.security.TargetAddressValidator;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * What the system administration does with the rerank and transcription model roles (ADR-0060,
 * Entscheidung 10): read, change and test them. A change is validated like every model endpoint
 * ({@link ModelEndpointRules}, target address check included), stored with the key encrypted, and
 * recorded with the values before and after (Entscheidung 11); a change to the same values writes
 * nothing. The speech-input feature is the operation's limit: the transcription role cannot be
 * switched on while it is off (Entscheidung 2).
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
  private final TargetAddressValidator modelAddresses;
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
      TargetAddressValidator modelAddresses,
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
    blockedAddressError(RERANK, address, errors);
    requireNoErrors(errors);
    return transactions.execute(
        status -> storeRerank(organizationId, actorUserId, enabled, address, identifier, apiKey));
  }

  private RerankModelView storeRerank(
      UUID organizationId,
      UUID actorUserId,
      boolean enabled,
      String address,
      String identifier,
      String apiKey) {
    jdbc.execute("LOCK TABLE rerank_model IN SHARE ROW EXCLUSIVE MODE");
    RerankModelSettings existing = rerankRows.findStored().orElse(null);
    RerankModelSettings row =
        existing == null ? new RerankModelSettings(clock.instant()) : existing;
    String previousCiphertext = row.getApiKeyCiphertext();
    String ciphertext = secretOf(RERANK, apiKey, row.getBaseUrl(), address, previousCiphertext);
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
        actorUserId,
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
    String lang = language == null ? null : language.strip().toLowerCase(Locale.ROOT);
    List<FieldError> errors = new ArrayList<>();
    ModelEndpointRules.endpointErrors(TRANSCRIPTION, address, identifier, apiKey, enabled, errors);
    if (lang != null && !lang.isEmpty() && !lang.matches("[a-z]{2}")) {
      errors.add(
          new FieldError(
              TRANSCRIPTION + ".language",
              ModelEndpointRules.INVALID,
              "Die Sprache ist ein Kürzel aus zwei Buchstaben nach ISO 639-1, etwa de oder en;"
                  + " leer erkennt das Modell die Sprache selbst."));
    }
    blockedAddressError(TRANSCRIPTION, address, errors);
    requireNoErrors(errors);
    return transactions.execute(
        status ->
            storeTranscription(
                organizationId, actorUserId, enabled, address, identifier, apiKey, lang));
  }

  private TranscriptionModelView storeTranscription(
      UUID organizationId,
      UUID actorUserId,
      boolean enabled,
      String address,
      String identifier,
      String apiKey,
      String requestedLanguage) {
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
    String ciphertext =
        secretOf(TRANSCRIPTION, apiKey, row.getBaseUrl(), address, previousCiphertext);
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
        actorUserId,
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
            rerankRows.findStored().map(RerankModelSettings::getBaseUrl).orElse(null),
            rerankRows.findStored().map(RerankModelSettings::getApiKeyCiphertext).orElse(null));
    RerankClient.ProbeFailure failure =
        rerankClient.probe(new RerankEndpoint(true, address, identifier, key), TEST_TIMEOUT);
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
            transcriptionRows.findStored().map(TranscriptionModelSettings::getBaseUrl).orElse(null),
            transcriptionRows
                .findStored()
                .map(TranscriptionModelSettings::getApiKeyCiphertext)
                .orElse(null));
    TranscriptionClient.ProbeFailure failure =
        transcriptionClient.probe(
            new TranscriptionEndpoint(true, address, identifier, key, ""), TEST_TIMEOUT);
    return failure == null
        ? new TestOutcome(true, "Verbindung erfolgreich, der Endpunkt hat geantwortet.")
        : new TestOutcome(false, german(failure.message(), failure.timedOut()));
  }

  /** Validates a draft and returns the key to send; checked against the target address check. */
  private String testKey(
      String prefix,
      String address,
      String identifier,
      String apiKey,
      String storedBaseUrl,
      String storedCiphertext) {
    List<FieldError> errors = new ArrayList<>();
    ModelEndpointRules.endpointErrors(prefix, address, identifier, apiKey, true, errors);
    blockedAddressError(prefix, address, errors);
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
      return settingsEncryptor.decrypt(storedCiphertext);
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

  /** The target address check of a valid address, as a field error under {@code prefix}. */
  private void blockedAddressError(String prefix, String baseUrl, List<FieldError> errors) {
    if (errors.isEmpty() && !baseUrl.isEmpty()) {
      String blocked = ModelEndpointRules.blockedModelAddress(modelAddresses, baseUrl);
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
      return settingsEncryptor.encrypt(apiKey.strip());
    } catch (IllegalStateException e) {
      throw FieldValidationException.of(
          prefix + ".apiKey",
          ModelEndpointRules.INVALID,
          "Der Zugangsschlüssel kann nicht gespeichert werden: OPAA_SETTINGS_ENCRYPTION_KEY ist"
              + " nicht gesetzt oder ungültig.");
    }
  }

  private void record(
      UUID organizationId,
      UUID actorUserId,
      AuditEventType type,
      String table,
      String label,
      Map<String, Object> before,
      Map<String, Object> after) {
    auditEventRecorder.recordUserAction(
        AuditEvent.builder()
            .organizationId(organizationId)
            .actor(actorUserId)
            .type(type)
            .object(
                AuditObjectType.SYSTEM_SETTING,
                UUID.nameUUIDFromBytes(table.getBytes(StandardCharsets.UTF_8)),
                label)
            .before(before)
            .after(after)
            .outcome(AuditOutcome.SUCCESS)
            .build());
  }

  /** The audit state of a row, the factory default for {@code null}; never the key itself. */
  private static Map<String, Object> rerankPayload(RerankModelSettings row) {
    Map<String, Object> state = new LinkedHashMap<>();
    state.put("enabled", row != null && row.isEnabled());
    putIfPresent(state, "baseUrl", row == null ? null : row.getBaseUrl());
    putIfPresent(state, "model", row == null ? null : row.getModelIdentifier());
    state.put("apiKeySet", row != null && row.getApiKeyCiphertext() != null);
    return state;
  }

  private static Map<String, Object> transcriptionPayload(TranscriptionModelSettings row) {
    Map<String, Object> state = new LinkedHashMap<>();
    state.put("enabled", row != null && row.isEnabled());
    putIfPresent(state, "baseUrl", row == null ? null : row.getBaseUrl());
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
