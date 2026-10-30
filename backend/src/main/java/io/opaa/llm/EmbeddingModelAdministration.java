package io.opaa.llm;

import io.opaa.common.ConflictException;
import io.opaa.common.FieldValidationException;
import io.opaa.common.FieldValidationException.FieldError;
import io.opaa.common.TransactionCallbacks;
import io.opaa.compliance.DataFlow;
import io.opaa.security.CipherBinding;
import io.opaa.security.EncryptedColumn;
import io.opaa.security.SettingsEncryptor;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The embedding model row as the administration changes it (ADR-0061): a draft validated like every
 * model endpoint ({@link ModelEndpointRules}, {@link ModelAddressPolicy} for an address other than
 * the stored one), the key kept only for the same endpoint, the probe compared with the reference
 * anchor, and the writes of a first model, a model change, a discard and an endpoint change. The
 * writes join the caller's transaction, which also changes the vector index; they flush at once, so
 * the caller's own statements see them.
 */
@DataFlow("embedding-model")
@Service
public class EmbeddingModelAdministration {

  /** The prefix of the draft's field errors. */
  public static final String PREFIX = "embeddingModel";

  public static final int MAX_INPUT_TOKENS = 1_000_000;

  /** Calls of the throughput probe, each with {@value #THROUGHPUT_TEXTS} texts at most. */
  static final int THROUGHPUT_CALLS = 3;

  static final int THROUGHPUT_TEXTS = 16;

  static final String CHANGED_MEANWHILE =
      "Das Embedding-Modell wurde zwischendurch geändert. Bitte die Probeeinbettung wiederholen.";

  private static final String FILLER =
      "Die Stadtverwaltung veröffentlicht Bekanntmachungen, Satzungen und Beschlüsse des Rates. ";

  /**
   * A model endpoint as entered.
   *
   * @param apiKey {@code null} keeps the stored key, only for the same endpoint; blank sends none
   * @param maxInputTokens {@code null} for the default input limit
   */
  public record Draft(String baseUrl, String model, String apiKey, Integer maxInputTokens) {

    /** Never names the key, only whether one was entered. */
    @Override
    public String toString() {
      return "Draft[baseUrl="
          + ModelEndpointUri.withoutSecrets(baseUrl)
          + ", model="
          + model
          + ", apiKeySet="
          + (apiKey != null && !apiKey.isBlank())
          + ", maxInputTokens="
          + maxInputTokens
          + "]";
    }
  }

  /**
   * A validated draft: the endpoint with the key to send, the ciphertext to store and how the key
   * changes against the stored row ({@code SET}, {@code CHANGED}, {@code REMOVED}, or {@code null}
   * for unchanged).
   */
  public record Resolved(
      EmbeddingEndpoint endpoint, String ciphertext, String apiKeyChange, String basis) {}

  /** What a probed draft is to the installed model. */
  public enum Kind {
    MODEL_CHANGE,
    ENDPOINT_CHANGE,
    /** No model is installed yet: the draft becomes the first one, nothing is re-embedded. */
    INITIAL
  }

  /**
   * @param similarity the smallest similarity to the anchor; {@code null} when the draft names
   *     another model or size, or when no anchor could be had
   * @param generation the generation of the row compared with, {@code 0} for {@link Kind#INITIAL}
   * @param modelIdentifier the model of the row compared with, {@code null} for {@link
   *     Kind#INITIAL}
   */
  public record Comparison(Kind kind, Double similarity, long generation, String modelIdentifier) {}

  /** The audit state of the row before and after a write, never with the key. */
  public record Change(Map<String, Object> before, Map<String, Object> after) {}

  /** The installed model as the administration shows it. */
  public record ModelView(
      String baseUrl,
      String model,
      int dimensions,
      int maxInputTokens,
      boolean apiKeySet,
      long generation,
      boolean anchorRecorded) {}

  private static final Logger log = LoggerFactory.getLogger(EmbeddingModelAdministration.class);
  private static final CipherBinding EMBEDDING_ROW =
      LlmEncryptedColumns.EMBEDDING_MODEL_KEY.row(EmbeddingModelSettings.SINGLETON_ID);

  private final EmbeddingModelSettingsRepository rows;
  private final PreviousEmbeddingModelRepository previousRows;
  private final EmbeddingModelService embeddingModels;
  private final EmbeddingProbe probe;
  private final ModelAddressPolicy modelAddresses;
  private final SettingsEncryptor settingsEncryptor;
  private final EmbeddingGenerations generations;
  private final InstalledEmbeddingModel installedModel;
  private final Clock clock;

  EmbeddingModelAdministration(
      EmbeddingModelSettingsRepository rows,
      PreviousEmbeddingModelRepository previousRows,
      EmbeddingModelService embeddingModels,
      EmbeddingProbe probe,
      ModelAddressPolicy modelAddresses,
      SettingsEncryptor settingsEncryptor,
      EmbeddingGenerations generations,
      InstalledEmbeddingModel installedModel,
      Clock clock) {
    this.rows = rows;
    this.previousRows = previousRows;
    this.embeddingModels = embeddingModels;
    this.probe = probe;
    this.modelAddresses = modelAddresses;
    this.settingsEncryptor = settingsEncryptor;
    this.generations = generations;
    this.installedModel = installedModel;
    this.clock = clock;
  }

  /** The installed model, empty before the installation chose one. */
  @Transactional(readOnly = true)
  public Optional<ModelView> installed() {
    return rows.findInstalled().map(EmbeddingModelAdministration::view);
  }

  /** The model a discard would restore, empty while no change waits for its switch. */
  @Transactional(readOnly = true)
  public Optional<ModelView> previous() {
    return previousRows
        .findStored()
        .map(
            row ->
                new ModelView(
                    row.getBaseUrl(),
                    row.getModelIdentifier(),
                    row.getDimensions(),
                    row.getMaxInputTokens(),
                    row.getApiKeyCiphertext() != null,
                    row.getGeneration(),
                    row.getReferenceVectors() != null));
  }

  /**
   * Validates {@code draft} against the installed row and resolves its key; before an embedding
   * model is installed against no row, so there is no key to keep and the address check applies to
   * every address.
   *
   * @throws FieldValidationException naming every violated rule, fields under {@value #PREFIX}
   */
  @Transactional(readOnly = true)
  public Resolved resolve(Draft draft) {
    Optional<EmbeddingModelSettings> installed = rows.findInstalled();
    if (installed.isEmpty()) {
      return resolveFirst(draft);
    }
    EmbeddingModelSettings row = installed.get();
    String baseUrl = draft.baseUrl() == null ? "" : draft.baseUrl().strip();
    String model = draft.model() == null ? "" : draft.model().strip();
    int maxInputTokens = validated(draft, baseUrl, model, row.getBaseUrl());
    String apiKey = draft.apiKey();
    String stored = row.getApiKeyCiphertext();
    if (apiKey == null) {
      if (stored != null && !ModelEndpointRules.sameEndpoint(row.getBaseUrl(), baseUrl)) {
        throw FieldValidationException.of(
            PREFIX + ".apiKey",
            ModelEndpointRules.REQUIRED,
            "Die Basis-Adresse nennt einen anderen Endpunkt: Bitte den Zugangsschlüssel erneut"
                + " eingeben oder „ohne Schlüssel“ wählen.");
      }
      return new Resolved(
          new EmbeddingEndpoint(baseUrl, model, decrypt(stored), maxInputTokens),
          stored,
          null,
          row.fingerprint());
    }
    if (apiKey.isBlank()) {
      return new Resolved(
          new EmbeddingEndpoint(baseUrl, model, null, maxInputTokens),
          null,
          stored == null ? null : "REMOVED",
          row.fingerprint());
    }
    String key = apiKey.strip();
    return new Resolved(
        new EmbeddingEndpoint(baseUrl, model, key, maxInputTokens),
        encrypt(key),
        stored == null ? "SET" : "CHANGED",
        row.fingerprint());
  }

  /**
   * The input limit of a valid draft; the address check skips an address naming {@code
   * storedBaseUrl}'s endpoint.
   *
   * @throws FieldValidationException naming every violated rule
   */
  private int validated(Draft draft, String baseUrl, String model, String storedBaseUrl) {
    List<FieldError> errors = new ArrayList<>();
    ModelEndpointRules.endpointErrors(PREFIX, baseUrl, model, draft.apiKey(), true, errors);
    int maxInputTokens =
        draft.maxInputTokens() == null
            ? EmbeddingEndpoint.DEFAULT_MAX_INPUT_TOKENS
            : draft.maxInputTokens();
    if (maxInputTokens < 1 || maxInputTokens > MAX_INPUT_TOKENS) {
      errors.add(
          new FieldError(
              PREFIX + ".maxInputTokens",
              ModelEndpointRules.INVALID,
              "Die Eingabegrenze muss zwischen 1 und " + MAX_INPUT_TOKENS + " Tokens liegen."));
    }
    if (errors.isEmpty()) {
      String blocked = modelAddresses.blockedReason(baseUrl, storedBaseUrl);
      if (blocked != null) {
        errors.add(new FieldError(PREFIX + ".baseUrl", ModelEndpointRules.BLOCKED, blocked));
      }
    }
    if (!errors.isEmpty()) {
      throw new FieldValidationException("Die Angaben zum Embedding-Modell sind ungültig.", errors);
    }
    return maxInputTokens;
  }

  /** {@link #resolve} without an installed row: every address is checked, no key is kept. */
  private Resolved resolveFirst(Draft draft) {
    String baseUrl = draft.baseUrl() == null ? "" : draft.baseUrl().strip();
    String model = draft.model() == null ? "" : draft.model().strip();
    int maxInputTokens = validated(draft, baseUrl, model, null);
    String apiKey = draft.apiKey() == null ? "" : draft.apiKey().strip();
    if (apiKey.isEmpty()) {
      return new Resolved(
          new EmbeddingEndpoint(baseUrl, model, null, maxInputTokens), null, null, null);
    }
    return new Resolved(
        new EmbeddingEndpoint(baseUrl, model, apiKey, maxInputTokens),
        encrypt(apiKey),
        "SET",
        null);
  }

  /** The probe embedding of {@link EmbeddingModelService#probe}, the anchor texts included. */
  public EmbeddingModelService.ProbeOutcome probe(EmbeddingEndpoint endpoint) {
    return embeddingModels.probe(endpoint);
  }

  /**
   * Whether the probed endpoint is the installed model at another address: the same model name and
   * size, and the probe vectors at least {@link EmbeddingAnchor#MIN_SIMILARITY} similar to the
   * anchor. Without an anchor the stored endpoint is probed for one; when it does not answer
   * either, no comparison is possible and the draft counts as a model change. Without an installed
   * model the draft is {@link Kind#INITIAL}. Call it outside any transaction: it may wait for the
   * network.
   */
  public Comparison compare(EmbeddingEndpoint endpoint, EmbeddingModelService.ProbeOutcome probed) {
    Optional<EmbeddingModelSettings> installed = rows.findInstalled();
    if (installed.isEmpty()) {
      return new Comparison(Kind.INITIAL, null, 0, null);
    }
    EmbeddingModelSettings row = installed.get();
    if (!probed.succeeded()
        || !row.getModelIdentifier().equals(endpoint.modelIdentifier())
        || probed.dimensions() == null
        || probed.dimensions() != row.getDimensions()) {
      return new Comparison(Kind.MODEL_CHANGE, null, row.getGeneration(), row.getModelIdentifier());
    }
    float[] anchor = row.getReferenceVectors();
    if (anchor == null) {
      EmbeddingModelService.ProbeOutcome current =
          probe.probe(
              new EmbeddingEndpoint(
                  row.getBaseUrl(),
                  row.getModelIdentifier(),
                  decrypt(row.getApiKeyCiphertext()),
                  row.getMaxInputTokens()),
              EmbeddingAnchor.TEXTS);
      if (!current.succeeded() || current.vectors().size() != EmbeddingAnchor.TEXTS.size()) {
        return new Comparison(
            Kind.MODEL_CHANGE, null, row.getGeneration(), row.getModelIdentifier());
      }
      anchor = EmbeddingAnchor.flatten(current.vectors());
    }
    double similarity = EmbeddingAnchor.smallestSimilarity(anchor, probed.vectors());
    if (Double.isNaN(similarity)) {
      return new Comparison(Kind.MODEL_CHANGE, null, row.getGeneration(), row.getModelIdentifier());
    }
    return new Comparison(
        similarity >= EmbeddingAnchor.MIN_SIMILARITY ? Kind.ENDPOINT_CHANGE : Kind.MODEL_CHANGE,
        similarity,
        row.getGeneration(),
        row.getModelIdentifier());
  }

  /**
   * Chunks per second of {@code endpoint}, measured with at most {@value #THROUGHPUT_CALLS} calls
   * of {@value #THROUGHPUT_TEXTS} artificial texts of {@code targetCharacters} each; {@code null}
   * when no call succeeded. No content of the library reaches the endpoint.
   */
  public Double measureThroughput(EmbeddingEndpoint endpoint, int targetCharacters) {
    List<String> texts = Collections.nCopies(THROUGHPUT_TEXTS, artificialText(targetCharacters));
    long chunks = 0;
    long nanos = 0;
    for (int call = 0; call < THROUGHPUT_CALLS; call++) {
      EmbeddingModelService.ProbeOutcome outcome = probe.probe(endpoint, texts);
      if (!outcome.succeeded() || outcome.elapsedNanos() <= 0) {
        break;
      }
      chunks += texts.size();
      nanos += outcome.elapsedNanos();
    }
    return chunks == 0 ? null : chunks / (nanos / 1_000_000_000.0);
  }

  /**
   * Makes the row the resolved model under {@code generation}, keeping the row before as the
   * previous one.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public Change replace(Resolved resolved, int dimensions, float[] anchor, long generation) {
    EmbeddingModelSettings row = requireInstalled();
    String carried =
        carriedKey(
            row.getApiKeyCiphertext(),
            LlmEncryptedColumns.EMBEDDING_MODEL_KEY,
            LlmEncryptedColumns.PREVIOUS_EMBEDDING_MODEL_KEY);
    Map<String, Object> before =
        withKeyChange(state(row), dropped(row.getApiKeyCiphertext(), carried));
    previousRows.saveAndFlush(new PreviousEmbeddingModel(row, carried));
    row.replace(
        resolved.endpoint(),
        resolved.ciphertext(),
        dimensions,
        clock.instant(),
        generation,
        anchor);
    rows.saveAndFlush(row);
    dropClientAfterCommit();
    return new Change(before, withKeyChange(state(row), resolved.apiKeyChange()));
  }

  /**
   * Installs the resolved model as the first one under {@code generation}, with its anchor, and
   * creates the vector table of {@code dimensions} ({@link EmbeddingModelService#install}); there
   * is no previous model to keep. Called under the schema lock after {@link #requireUnchanged}.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public Change installFirst(Resolved resolved, int dimensions, float[] anchor, long generation) {
    EmbeddingModelSettings row =
        embeddingModels.install(resolved.endpoint(), dimensions, generation, anchor);
    rows.flush();
    dropClientAfterCommit();
    return new Change(null, withKeyChange(state(row), resolved.apiKeyChange()));
  }

  /** Puts the previous model back and deletes it; empty when there is none to restore. */
  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<Change> restorePrevious() {
    Optional<PreviousEmbeddingModel> previous = previousRows.findStored();
    if (previous.isEmpty()) {
      return Optional.empty();
    }
    EmbeddingModelSettings row = requireInstalled();
    Map<String, Object> before = state(row);
    String stored = previous.get().getApiKeyCiphertext();
    String carried =
        carriedKey(
            stored,
            LlmEncryptedColumns.PREVIOUS_EMBEDDING_MODEL_KEY,
            LlmEncryptedColumns.EMBEDDING_MODEL_KEY);
    row.restore(previous.get(), carried);
    rows.saveAndFlush(row);
    dropClientAfterCommit();
    previousRows.delete(previous.get());
    previousRows.flush();
    return Optional.of(new Change(before, withKeyChange(state(row), dropped(stored, carried))));
  }

  /** Deletes the previous model, as the switch does. */
  @Transactional(propagation = Propagation.MANDATORY)
  public void dropPrevious() {
    previousRows.findStored().ifPresent(previousRows::delete);
    previousRows.flush();
  }

  /** Moves the row to the resolved endpoint; generation, size and anchor stay. */
  @Transactional(propagation = Propagation.MANDATORY)
  public Change moveEndpoint(Resolved resolved) {
    EmbeddingModelSettings row = requireInstalled();
    Map<String, Object> before = state(row);
    row.moveEndpoint(
        resolved.endpoint().baseUrl(), resolved.ciphertext(), resolved.endpoint().maxInputTokens());
    rows.saveAndFlush(row);
    dropClientAfterCommit();
    return new Change(before, withKeyChange(state(row), resolved.apiKeyChange()));
  }

  /** Gives the row a generation no column carries and returns it. */
  @Transactional(propagation = Propagation.MANDATORY)
  public long renumber() {
    EmbeddingModelSettings row = requireInstalled();
    long generation = generations.next();
    row.renumber(generation);
    rows.saveAndFlush(row);
    dropClientAfterCommit();
    return generation;
  }

  /**
   * Refuses a write decided on a row that changed since: the row must still be the one {@code
   * resolved} was validated against and {@code comparison} compared with, and for {@link
   * Kind#INITIAL} there must still be none. Called under the schema lock, which every writer of the
   * row holds.
   *
   * @throws ConflictException when the row changed meanwhile
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void requireUnchanged(Resolved resolved, Comparison comparison) {
    Optional<EmbeddingModelSettings> installed = rows.findInstalled();
    if (comparison.kind() == Kind.INITIAL) {
      if (installed.isPresent()) {
        throw new ConflictException(CHANGED_MEANWHILE);
      }
      return;
    }
    EmbeddingModelSettings row =
        installed.orElseThrow(() -> new ConflictException(CHANGED_MEANWHILE));
    if (!row.fingerprint().equals(resolved.basis())
        || row.getGeneration() != comparison.generation()
        || !row.getModelIdentifier().equals(comparison.modelIdentifier())) {
      throw new ConflictException(CHANGED_MEANWHILE);
    }
  }

  /** A generation drawn for a model change before its transaction. */
  public long nextGeneration() {
    return generations.next();
  }

  /** The probe vectors one after the other, as the anchor stores them. */
  public static float[] anchorOf(EmbeddingModelService.ProbeOutcome outcome) {
    return outcome.vectors().size() == EmbeddingAnchor.TEXTS.size()
        ? EmbeddingAnchor.flatten(outcome.vectors())
        : null;
  }

  /**
   * The client of this instance follows the row at the commit, before the bus reports it; other
   * instances follow through the bus.
   */
  private void dropClientAfterCommit() {
    TransactionCallbacks.afterCommitOrNow(installedModel::invalidateAll);
  }

  private EmbeddingModelSettings requireInstalled() {
    return rows.findInstalled().orElseThrow(EmbeddingModelNotInstalledException::afterInstallation);
  }

  /**
   * The key {@code stored} of the singleton row of {@code from}, encrypted for the one of {@code
   * to}. A key no key can read moves as stored only in the unbound format {@code enc:v1}; a bound
   * one is dropped with a warning, because at the other row it could never be read again - the
   * model it belongs to then has no key until one is entered for it.
   */
  private String carriedKey(String stored, EncryptedColumn from, EncryptedColumn to) {
    if (stored == null) {
      return null;
    }
    try {
      return settingsEncryptor.rebind(
          stored,
          from.row(EmbeddingModelSettings.SINGLETON_ID),
          to.row(EmbeddingModelSettings.SINGLETON_ID));
    } catch (IllegalStateException e) {
      if (SettingsEncryptor.isKeyed(stored)) {
        log.warn(
            "The stored key of the embedding model in {} cannot be decrypted ({}); it is dropped"
                + " rather than moved to {}, enter the key of that model again",
            from.table(),
            e.getClass().getSimpleName(),
            to.table());
        return null;
      }
      log.warn(
          "The stored key of the embedding model cannot be decrypted ({}); it moves as stored",
          e.getClass().getSimpleName());
      return stored;
    }
  }

  private String decrypt(String ciphertext) {
    if (ciphertext == null) {
      return null;
    }
    try {
      return settingsEncryptor.decrypt(ciphertext, EMBEDDING_ROW);
    } catch (IllegalStateException e) {
      throw FieldValidationException.of(
          PREFIX + ".apiKey",
          ModelEndpointRules.INVALID,
          "Der gespeicherte Zugangsschlüssel ist nicht lesbar; bitte den Schlüssel erneut"
              + " eingeben.");
    }
  }

  private String encrypt(String key) {
    try {
      return settingsEncryptor.encrypt(key, EMBEDDING_ROW);
    } catch (IllegalStateException e) {
      throw FieldValidationException.of(
          PREFIX + ".apiKey",
          ModelEndpointRules.INVALID,
          "Der Zugangsschlüssel kann nicht gespeichert werden: OPAA_SETTINGS_ENCRYPTION_KEY ist"
              + " nicht gesetzt oder ungültig.");
    }
  }

  private static ModelView view(EmbeddingModelSettings row) {
    return new ModelView(
        row.getBaseUrl(),
        row.getModelIdentifier(),
        row.getDimensions(),
        row.getMaxInputTokens(),
        row.hasApiKey(),
        row.getGeneration(),
        row.hasReferenceAnchor());
  }

  /** The audit state of the row; the address only without credentials, never the key. */
  private static Map<String, Object> state(EmbeddingModelSettings row) {
    Map<String, Object> state = new LinkedHashMap<>();
    state.put("model", row.getModelIdentifier());
    state.put("baseUrl", ModelEndpointUri.withoutSecrets(row.getBaseUrl()));
    state.put("dimensions", row.getDimensions());
    state.put("maxInputTokens", row.getMaxInputTokens());
    state.put("generation", row.getGeneration());
    state.put("apiKeySet", row.hasApiKey());
    return state;
  }

  /** {@code DROPPED} when {@link #carriedKey} let a stored key go, otherwise no key change. */
  private static String dropped(String stored, String carried) {
    return stored != null && carried == null ? "DROPPED" : null;
  }

  private static Map<String, Object> withKeyChange(Map<String, Object> state, String change) {
    if (change != null) {
      state.put("apiKeyChange", change);
    }
    return state;
  }

  private static String artificialText(int targetCharacters) {
    StringBuilder text = new StringBuilder(targetCharacters + FILLER.length());
    while (text.length() < targetCharacters) {
      text.append(FILLER);
    }
    return text.substring(0, Math.max(1, targetCharacters));
  }
}
