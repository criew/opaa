package io.opaa.llm;

import io.opaa.common.ConflictException;
import io.opaa.common.InstallationStatus;
import io.opaa.compliance.DataFlow;
import io.opaa.knowledge.VectorTableCreator;
import io.opaa.security.SettingsEncryptor;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The embedding model of the installation (ADR-0060, Entscheidung 10): the probe embedding that
 * determines the vector size, and the installation that stores the model and creates {@code
 * vector_store} with that size in one transaction. A later change goes through {@link
 * EmbeddingModelAdministration} and a re-embedding (ADR-0061).
 *
 * <p>The {@code dev} mode installs its model itself ({@link #adoptIfMissing}) under the schema lock
 * of the vector table (ADR-0053: "Sperre"): with an existing table only when its configuration
 * names the model the index records, with the size of the existing column; without a table with a
 * probe embedding, run outside the lock. Every other mode gets its model from the installation
 * only.
 */
@DataFlow("embedding-model")
@Service
public class EmbeddingModelService {

  private static final Logger log = LoggerFactory.getLogger(EmbeddingModelService.class);

  /**
   * The outcome of a probe embedding, with a German message for the person installing.
   *
   * @param dimensions the vector size the model returned; {@code null} unless it succeeded
   * @param vectors one vector per probe text, empty unless it succeeded
   * @param elapsedNanos how long the request took; {@code 0} when unknown
   */
  public record ProbeOutcome(
      boolean succeeded,
      String message,
      Integer dimensions,
      List<float[]> vectors,
      long elapsedNanos) {

    public ProbeOutcome {
      vectors = vectors == null ? List.of() : List.copyOf(vectors);
    }

    /** An outcome without vectors, for a probe that only reports the size. */
    public ProbeOutcome(boolean succeeded, String message, Integer dimensions) {
      this(succeeded, message, dimensions, List.of(), 0);
    }

    /** A success with {@code vectors}, all of the same width. */
    public static ProbeOutcome succeeded(List<float[]> vectors, long elapsedNanos) {
      return new ProbeOutcome(true, null, vectors.getFirst().length, vectors, elapsedNanos);
    }

    static ProbeOutcome failure(String message) {
      return new ProbeOutcome(false, message, null);
    }
  }

  private final EmbeddingModelSettingsRepository repository;
  private final EmbeddingProbe probe;
  private final SettingsEncryptor settingsEncryptor;
  private final VectorTableCreator vectorTable;
  private final DevelopmentEmbeddingConfiguration development;
  private final RecordedEmbeddingIndex recordedIndex;
  private final ApplicationEventPublisher events;
  private final InstallationStatus installationStatus;
  private final EmbeddingGenerations generations;
  private final TransactionTemplate transactions;
  private final Clock clock;

  EmbeddingModelService(
      EmbeddingModelSettingsRepository repository,
      EmbeddingProbe probe,
      SettingsEncryptor settingsEncryptor,
      VectorTableCreator vectorTable,
      DevelopmentEmbeddingConfiguration development,
      RecordedEmbeddingIndex recordedIndex,
      ApplicationEventPublisher events,
      InstallationStatus installationStatus,
      EmbeddingGenerations generations,
      PlatformTransactionManager transactionManager,
      Clock clock) {
    this.repository = repository;
    this.probe = probe;
    this.settingsEncryptor = settingsEncryptor;
    this.vectorTable = vectorTable;
    this.development = development;
    this.recordedIndex = recordedIndex;
    this.events = events;
    this.installationStatus = installationStatus;
    this.generations = generations;
    this.transactions = new TransactionTemplate(transactionManager);
    this.clock = clock;
  }

  /** The installed embedding model, if one is. */
  @Transactional(readOnly = true)
  public Optional<EmbeddingModelSettings> installed() {
    return repository.findInstalled();
  }

  /** Whether the installation is completed and still has no embedding model. */
  public boolean missingAfterInstallation() {
    return !installationStatus.isPending() && installed().isEmpty();
  }

  /**
   * Whether the vector table holds chunks although no embedding model is installed: a restored or
   * damaged database, an error the start and the health report - unlike a missing or empty table,
   * the regular state before the first model.
   */
  public boolean chunksWithoutModel() {
    return installed().isEmpty() && vectorTable.exists() && vectorTable.hasRows();
  }

  /**
   * Refuses a use of the embedding model before one is installed, before anything is written: an
   * indexing run, an upload or a search.
   *
   * @throws EmbeddingModelNotInstalledException while no embedding model is installed
   */
  public void requireInstalled() {
    if (installed().isEmpty()) {
      throw notInstalled();
    }
  }

  /** The refusal of a use before an embedding model is installed, as the installation stands. */
  EmbeddingModelNotInstalledException notInstalled() {
    return installationStatus.isPending()
        ? EmbeddingModelNotInstalledException.pending()
        : EmbeddingModelNotInstalledException.afterInstallation();
  }

  /**
   * Embeds the texts of {@link EmbeddingAnchor#TEXTS} with {@code endpoint} and reports the vector
   * size and the vectors it returned. A failure is a result, never an exception; the message names
   * what to check. Call it outside any transaction: it waits for the network.
   */
  public ProbeOutcome probe(EmbeddingEndpoint endpoint) {
    String addressProblem = addressProblem(endpoint.baseUrl());
    if (addressProblem != null) {
      return ProbeOutcome.failure(addressProblem);
    }
    ProbeOutcome outcome = probe.probe(endpoint, EmbeddingAnchor.TEXTS);
    if (!outcome.succeeded()) {
      return ProbeOutcome.failure(
          "Die Probeeinbettung ist fehlgeschlagen: "
              + outcome.message()
              + " Prüfen Sie Basis-Adresse, Modell-Kennung und Zugangsschlüssel.");
    }
    int size = outcome.dimensions();
    if (size > vectorTable.maxDimensions()) {
      return ProbeOutcome.failure(
          "Das Modell liefert Vektoren mit "
              + size
              + " Dimensionen; der Vektorindex nimmt höchstens "
              + vectorTable.maxDimensions()
              + " auf. Wählen Sie ein anderes Embedding-Modell.");
    }
    return new ProbeOutcome(
        true,
        "Probeeinbettung erfolgreich: Das Modell liefert Vektoren mit " + size + " Dimensionen.",
        size,
        outcome.vectors(),
        outcome.elapsedNanos());
  }

  /**
   * Stores {@code endpoint} as the embedding model and creates the vector table with {@code
   * dimensions}, in the caller's transaction or a new one. A table of another size is replaced
   * while it holds no chunk.
   *
   * @throws ConflictException when an embedding model is installed already, or when a vector table
   *     of another size holds chunks
   */
  @Transactional
  public EmbeddingModelSettings install(EmbeddingEndpoint endpoint, int dimensions) {
    return install(endpoint, dimensions, generations.next(), null);
  }

  /**
   * {@link #install(EmbeddingEndpoint, int)} under {@code generation} and with the reference anchor
   * of its probe, or {@code null} for the anchor to be taken from the first embedding.
   *
   * @throws ConflictException when an embedding model is installed already, or when a vector table
   *     of another size holds chunks
   */
  @Transactional
  public EmbeddingModelSettings install(
      EmbeddingEndpoint endpoint, int dimensions, long generation, float[] anchor) {
    vectorTable.lockSchemaChanges();
    if (repository.findInstalled().isPresent()) {
      throw new ConflictException("Das Embedding-Modell ist bereits eingerichtet.");
    }
    Integer existing = vectorTable.embeddingDimensions();
    if (existing != null && existing != dimensions) {
      if (vectorTable.hasRows()) {
        throw new ConflictException(
            "Die Vektortabelle besteht bereits mit "
                + existing
                + " Dimensionen und enthält Abschnitte; das Modell liefert "
                + dimensions
                + ".");
      }
      vectorTable.dropEmpty();
    }
    vectorTable.createIfAbsent(dimensions);
    return store(endpoint, dimensions, generation, anchor);
  }

  /**
   * Installs the embedding model of the {@code dev} mode in a completed installation that has none:
   * the one of {@link DevelopmentEmbeddingConfiguration}. Any other mode and a pending installation
   * install nothing; their model comes from the installation step. A refusal or a failed probe
   * leaves the model uninstalled, and a later call tries again.
   *
   * @return whether an embedding model is installed afterwards
   */
  public boolean adoptIfMissing() {
    if (installed().isPresent()) {
      return true;
    }
    if (installationStatus.isPending() || !development.active()) {
      return false;
    }
    EmbeddingEndpoint endpoint = development.endpoint();
    if (ModelEndpointUri.containsCredentials(endpoint.baseUrl())) {
      log.error(
          "The embedding base URL of the dev mode carries credentials and is not installed;"
              + " configure it without them and the key in spring.ai.openai.embedding.api-key");
      return false;
    }
    Integer probed = vectorTable.exists() ? null : probeForTakeover(endpoint);
    if (probed == null && !vectorTable.exists()) {
      return false;
    }
    try {
      return Boolean.TRUE.equals(transactions.execute(status -> adoptLocked(endpoint, probed)));
    } catch (IllegalStateException e) {
      log.error(
          "The embedding model {} could not be taken over ({}). Set OPAA_SETTINGS_ENCRYPTION_KEY;"
              + " until then indexing and search are refused",
          endpoint,
          e.getClass().getSimpleName());
      return false;
    }
  }

  private boolean adoptLocked(EmbeddingEndpoint endpoint, Integer probed) {
    vectorTable.lockSchemaChanges();
    if (repository.findInstalled().isPresent()) {
      return true;
    }
    Integer dimensions;
    long generation;
    if (vectorTable.exists()) {
      dimensions = dimensionsOfExistingIndex(endpoint);
      generation = generationOfExistingIndex(endpoint);
    } else if (probed != null) {
      dimensions = probed;
      generation = generations.next();
      vectorTable.createIfAbsent(dimensions);
    } else {
      dimensions = null;
      generation = 0;
    }
    if (dimensions == null) {
      return false;
    }
    store(endpoint, dimensions, generation, null);
    log.info(
        "Installed the embedding model '{}' of the dev mode at {} ({} dimensions) into"
            + " embedding_model",
        endpoint.modelIdentifier(),
        ModelEndpointUri.withoutSecrets(endpoint.baseUrl()),
        dimensions);
    return true;
  }

  /**
   * The size of the column the configured model's vectors are in, or {@code null} when the
   * configuration names a model the index does not record: then nothing is taken over, since the
   * stored vectors would no longer match their model.
   */
  private Integer dimensionsOfExistingIndex(EmbeddingEndpoint endpoint) {
    Integer current = vectorTable.dimensionsOf(VectorTableCreator.EMBEDDING_COLUMN);
    Optional<RecordedEmbeddingIndex.Recorded> recorded = recordedIndex.recorded();
    if (recorded.isEmpty()) {
      return current;
    }
    RecordedEmbeddingIndex.Recorded index = recorded.get();
    String model = endpoint.modelIdentifier();
    if (index.targetModel() != null && index.targetModel().equals(model)) {
      return vectorTable.dimensionsOf(VectorTableCreator.TARGET_COLUMN);
    }
    boolean sameModel =
        index.model() == null
            ? Objects.equals(current, development.dimensions())
            : index.model().equals(model);
    if (sameModel) {
      return current;
    }
    log.error(
        "The configured embedding model '{}' is not the one the vector index was built with ('{}',"
            + " {} dimensions; target '{}'); nothing is taken over and indexing and search are"
            + " refused. Configure the model of the index, or change the model as the handbook"
            + " describes",
        model,
        index.model(),
        current,
        index.targetModel());
    return null;
  }

  /**
   * The generation of the column the configured model's vectors are in, so the self-installation
   * matches the index instead of starting a re-embedding; the initial one when nothing is recorded.
   */
  private long generationOfExistingIndex(EmbeddingEndpoint endpoint) {
    return recordedIndex
        .recorded()
        .map(
            index ->
                index.targetModel() != null
                        && index.targetModel().equals(endpoint.modelIdentifier())
                        && index.targetGeneration() != null
                    ? index.targetGeneration()
                    : index.generation())
        .orElse(EmbeddingClients.INITIAL_GENERATION);
  }

  private Integer probeForTakeover(EmbeddingEndpoint endpoint) {
    ProbeOutcome outcome = probe(endpoint);
    if (!outcome.succeeded()) {
      log.error(
          "No embedding model is installed and the probe embedding of {} failed; indexing and"
              + " search are refused until a later attempt reaches the model",
          endpoint);
      return null;
    }
    return outcome.dimensions();
  }

  /**
   * Stores the model; without {@code anchor} the anchor is taken from the installed endpoint right
   * after ({@link EmbeddingAnchorRecorder}), since the installation step keeps only the size.
   */
  private EmbeddingModelSettings store(
      EmbeddingEndpoint endpoint, int dimensions, long generation, float[] anchor) {
    EmbeddingModelSettings settings =
        repository.save(
            new EmbeddingModelSettings(
                endpoint.baseUrl(),
                endpoint.modelIdentifier(),
                settingsEncryptor.encrypt(
                    endpoint.apiKey(),
                    LlmEncryptedColumns.EMBEDDING_MODEL_KEY.row(
                        EmbeddingModelSettings.SINGLETON_ID)),
                endpoint.maxInputTokens(),
                dimensions,
                clock.instant(),
                generation,
                anchor));
    events.publishEvent(new EmbeddingModelInstalledEvent(endpoint.modelIdentifier(), dimensions));
    return settings;
  }

  /** Why {@code baseUrl} cannot be probed, in German, or {@code null}. */
  private static String addressProblem(String baseUrl) {
    if (ModelEndpointUri.containsCredentials(baseUrl)) {
      return "Die Basis-Adresse darf keine Zugangsdaten enthalten; der Schlüssel gehört in das Feld"
          + " Zugangsschlüssel.";
    }
    URI uri;
    try {
      uri = new URI(baseUrl);
    } catch (URISyntaxException e) {
      return "Die Basis-Adresse ist keine gültige URL.";
    }
    String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
    if (!(scheme.equals("http") || scheme.equals("https")) || uri.getHost() == null) {
      return "Die Basis-Adresse muss mit http:// oder https:// beginnen.";
    }
    return null;
  }
}
