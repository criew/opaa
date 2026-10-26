package io.opaa.llm;

import io.opaa.common.FieldValidationException;
import io.opaa.common.FieldValidationException.FieldError;
import io.opaa.compliance.DataFlow;
import io.opaa.security.SettingsEncryptor;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The embedding model row as the administration changes it (ADR-0061): a draft validated like every
 * model endpoint ({@link ModelEndpointRules}, {@link ModelAddressPolicy} for an address other than
 * the stored one), the key kept only for the same endpoint, the probe compared with the reference
 * anchor, and the writes of a model change, a discard and an endpoint change. The writes join the
 * caller's transaction, which also changes the vector index; they flush at once, so the caller's
 * own statements see them.
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

  private static final String FILLER =
      "Die Stadtverwaltung veröffentlicht Bekanntmachungen, Satzungen und Beschlüsse des Rates. ";

  /**
   * A model endpoint as entered.
   *
   * @param apiKey {@code null} keeps the stored key, only for the same endpoint; blank sends none
   * @param maxInputTokens {@code null} for the default input limit
   */
  public record Draft(String baseUrl, String model, String apiKey, Integer maxInputTokens) {}

  /**
   * A validated draft: the endpoint with the key to send, the ciphertext to store and how the key
   * changes against the stored row ({@code SET}, {@code CHANGED}, {@code REMOVED}, or {@code null}
   * for unchanged).
   */
  public record Resolved(EmbeddingEndpoint endpoint, String ciphertext, String apiKeyChange) {}

  /** What a probed draft is to the installed model. */
  public enum Kind {
    MODEL_CHANGE,
    ENDPOINT_CHANGE
  }

  /**
   * @param similarity the smallest similarity to the anchor; {@code null} when the draft names
   *     another model or size, or when no anchor could be had
   */
  public record Comparison(Kind kind, Double similarity) {}

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

  private final EmbeddingModelSettingsRepository rows;
  private final PreviousEmbeddingModelRepository previousRows;
  private final EmbeddingModelService embeddingModels;
  private final EmbeddingProbe probe;
  private final ModelAddressPolicy modelAddresses;
  private final SettingsEncryptor settingsEncryptor;
  private final EmbeddingGenerations generations;
  private final Clock clock;

  EmbeddingModelAdministration(
      EmbeddingModelSettingsRepository rows,
      PreviousEmbeddingModelRepository previousRows,
      EmbeddingModelService embeddingModels,
      EmbeddingProbe probe,
      ModelAddressPolicy modelAddresses,
      SettingsEncryptor settingsEncryptor,
      EmbeddingGenerations generations,
      Clock clock) {
    this.rows = rows;
    this.previousRows = previousRows;
    this.embeddingModels = embeddingModels;
    this.probe = probe;
    this.modelAddresses = modelAddresses;
    this.settingsEncryptor = settingsEncryptor;
    this.generations = generations;
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
   * Validates {@code draft} against the installed row and resolves its key.
   *
   * @throws FieldValidationException naming every violated rule, fields under {@value #PREFIX}
   * @throws EmbeddingModelNotInstalledException before an embedding model is installed
   */
  @Transactional(readOnly = true)
  public Resolved resolve(Draft draft) {
    EmbeddingModelSettings row = requireInstalled();
    String baseUrl = draft.baseUrl() == null ? "" : draft.baseUrl().strip();
    String model = draft.model() == null ? "" : draft.model().strip();
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
      String blocked = modelAddresses.blockedReason(baseUrl, row.getBaseUrl());
      if (blocked != null) {
        errors.add(new FieldError(PREFIX + ".baseUrl", ModelEndpointRules.BLOCKED, blocked));
      }
    }
    if (!errors.isEmpty()) {
      throw new FieldValidationException("Die Angaben zum Embedding-Modell sind ungültig.", errors);
    }
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
          new EmbeddingEndpoint(baseUrl, model, decrypt(stored), maxInputTokens), stored, null);
    }
    if (apiKey.isBlank()) {
      return new Resolved(
          new EmbeddingEndpoint(baseUrl, model, null, maxInputTokens),
          null,
          stored == null ? null : "REMOVED");
    }
    String key = apiKey.strip();
    return new Resolved(
        new EmbeddingEndpoint(baseUrl, model, key, maxInputTokens),
        encrypt(key),
        stored == null ? "SET" : "CHANGED");
  }

  /** The probe embedding of {@link EmbeddingModelService#probe}, the anchor texts included. */
  public EmbeddingModelService.ProbeOutcome probe(EmbeddingEndpoint endpoint) {
    return embeddingModels.probe(endpoint);
  }

  /**
   * Whether the probed endpoint is the installed model at another address: the same model name and
   * size, and the probe vectors at least {@link EmbeddingAnchor#MIN_SIMILARITY} similar to the
   * anchor. Without an anchor the stored endpoint is probed for one; when it does not answer
   * either, no comparison is possible and the draft counts as a model change. Call it outside any
   * transaction: it may wait for the network.
   */
  public Comparison compare(EmbeddingEndpoint endpoint, EmbeddingModelService.ProbeOutcome probed) {
    EmbeddingModelSettings row = requireInstalled();
    if (!probed.succeeded()
        || !row.getModelIdentifier().equals(endpoint.modelIdentifier())
        || probed.dimensions() == null
        || probed.dimensions() != row.getDimensions()) {
      return new Comparison(Kind.MODEL_CHANGE, null);
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
        return new Comparison(Kind.MODEL_CHANGE, null);
      }
      anchor = EmbeddingAnchor.flatten(current.vectors());
    }
    double similarity = EmbeddingAnchor.smallestSimilarity(anchor, probed.vectors());
    if (similarity < 0) {
      return new Comparison(Kind.MODEL_CHANGE, null);
    }
    return new Comparison(
        similarity >= EmbeddingAnchor.MIN_SIMILARITY ? Kind.ENDPOINT_CHANGE : Kind.MODEL_CHANGE,
        similarity);
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
    Map<String, Object> before = state(row);
    previousRows.saveAndFlush(new PreviousEmbeddingModel(row));
    row.replace(
        resolved.endpoint(),
        resolved.ciphertext(),
        dimensions,
        clock.instant(),
        generation,
        anchor);
    rows.saveAndFlush(row);
    return new Change(before, withKeyChange(state(row), resolved.apiKeyChange()));
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
    row.restore(previous.get());
    rows.saveAndFlush(row);
    previousRows.delete(previous.get());
    previousRows.flush();
    return Optional.of(new Change(before, state(row)));
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
    return new Change(before, withKeyChange(state(row), resolved.apiKeyChange()));
  }

  /** Gives the row a generation no column carries and returns it. */
  @Transactional(propagation = Propagation.MANDATORY)
  public long renumber() {
    EmbeddingModelSettings row = requireInstalled();
    long generation = generations.next();
    row.renumber(generation);
    rows.saveAndFlush(row);
    return generation;
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

  private EmbeddingModelSettings requireInstalled() {
    return rows.findInstalled().orElseThrow(EmbeddingModelNotInstalledException::afterInstallation);
  }

  private String decrypt(String ciphertext) {
    if (ciphertext == null) {
      return null;
    }
    try {
      return settingsEncryptor.decrypt(ciphertext);
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
      return settingsEncryptor.encrypt(key);
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
