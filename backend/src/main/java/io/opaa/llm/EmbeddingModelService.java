package io.opaa.llm;

import io.opaa.common.ConflictException;
import io.opaa.compliance.DataFlow;
import io.opaa.knowledge.VectorTableCreator;
import io.opaa.security.SettingsEncryptor;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Clock;
import java.util.Locale;
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
 * vector_store} with that size in one transaction. Installed once; there is no way to change it
 * through the administration, since every stored vector depends on it.
 *
 * <p>An installation from before ADR-0060 takes over its configured model once at the start ({@link
 * #adoptAtStartup()}), under the schema lock of the vector table (ADR-0053: "Sperre"): with the
 * vector size its configuration declared when the table exists, with a probe embedding when it does
 * not.
 */
@DataFlow("embedding-model")
@Service
public class EmbeddingModelService {

  /** The text of the probe embedding. */
  static final String PROBE_TEXT = "OPAA Probeeinbettung";

  private static final Logger log = LoggerFactory.getLogger(EmbeddingModelService.class);

  /**
   * The outcome of a probe embedding, with a German message for the person installing.
   *
   * @param dimensions the vector size the model returned; {@code null} unless it succeeded
   */
  public record ProbeOutcome(boolean succeeded, String message, Integer dimensions) {

    static ProbeOutcome failure(String message) {
      return new ProbeOutcome(false, message, null);
    }
  }

  private final EmbeddingModelSettingsRepository repository;
  private final EmbeddingClientFactory clientFactory;
  private final SettingsEncryptor settingsEncryptor;
  private final VectorTableCreator vectorTable;
  private final LegacyEmbeddingConfiguration legacy;
  private final ApplicationEventPublisher events;
  private final TransactionTemplate transactions;
  private final Clock clock;

  EmbeddingModelService(
      EmbeddingModelSettingsRepository repository,
      EmbeddingClientFactory clientFactory,
      SettingsEncryptor settingsEncryptor,
      VectorTableCreator vectorTable,
      LegacyEmbeddingConfiguration legacy,
      ApplicationEventPublisher events,
      PlatformTransactionManager transactionManager,
      Clock clock) {
    this.repository = repository;
    this.clientFactory = clientFactory;
    this.settingsEncryptor = settingsEncryptor;
    this.vectorTable = vectorTable;
    this.legacy = legacy;
    this.events = events;
    this.transactions = new TransactionTemplate(transactionManager);
    this.clock = clock;
  }

  /** The installed embedding model, if one is. */
  @Transactional(readOnly = true)
  public Optional<EmbeddingModelSettings> installed() {
    return repository.findInstalled();
  }

  /**
   * Embeds a probe text with {@code endpoint} and reports the vector size it returned. A failure is
   * a result, never an exception; the reason goes to the log, the message names what to check.
   */
  public ProbeOutcome probe(EmbeddingEndpoint endpoint) {
    String addressProblem = addressProblem(endpoint.baseUrl());
    if (addressProblem != null) {
      return ProbeOutcome.failure(addressProblem);
    }
    float[] vector;
    try {
      vector = clientFactory.create(endpoint).embed(PROBE_TEXT);
    } catch (RuntimeException e) {
      log.warn(
          "Probe embedding failed for model '{}' at {}: {}",
          endpoint.modelIdentifier(),
          ModelEndpointUri.withoutSecrets(endpoint.baseUrl()),
          e.getMessage());
      return ProbeOutcome.failure(
          "Die Probeeinbettung ist fehlgeschlagen. Prüfen Sie Basis-Adresse, Modell-Kennung und"
              + " Zugangsschlüssel und ob das Modell Einbettungen liefert.");
    }
    if (vector == null || vector.length == 0) {
      return ProbeOutcome.failure(
          "Das Modell hat bei der Probeeinbettung keinen Vektor geliefert.");
    }
    if (vector.length > vectorTable.maxDimensions()) {
      return ProbeOutcome.failure(
          "Das Modell liefert Vektoren mit "
              + vector.length
              + " Dimensionen; der Vektorindex nimmt höchstens "
              + vectorTable.maxDimensions()
              + " auf. Wählen Sie ein anderes Embedding-Modell.");
    }
    return new ProbeOutcome(
        true,
        "Probeeinbettung erfolgreich: Das Modell liefert Vektoren mit "
            + vector.length
            + " Dimensionen.",
        vector.length);
  }

  /**
   * Stores {@code endpoint} as the embedding model and creates the vector table with {@code
   * dimensions}, in the caller's transaction or a new one.
   *
   * @throws ConflictException when an embedding model is installed already, or when a vector table
   *     of another size exists
   */
  @Transactional
  public EmbeddingModelSettings install(EmbeddingEndpoint endpoint, int dimensions) {
    vectorTable.lockSchemaChanges();
    if (repository.findInstalled().isPresent()) {
      throw new ConflictException("Das Embedding-Modell ist bereits eingerichtet.");
    }
    Integer existing = vectorTable.embeddingDimensions();
    if (existing != null && existing != dimensions) {
      throw new ConflictException(
          "Die Vektortabelle besteht bereits mit "
              + existing
              + " Dimensionen; das Modell liefert "
              + dimensions
              + ".");
    }
    vectorTable.createIfAbsent(dimensions);
    return store(endpoint, dimensions);
  }

  /**
   * Takes over the embedding model of an installation that has none stored yet: the configured one
   * of {@link LegacyEmbeddingConfiguration}. An existing vector table keeps the size it was created
   * with; without one, a probe embedding decides it and the table is created. A failed probe leaves
   * the model uninstalled and is retried at the next start.
   *
   * @return whether an embedding model is installed afterwards
   */
  public boolean adoptAtStartup() {
    if (installed().isPresent()) {
      return true;
    }
    EmbeddingEndpoint endpoint = legacy.endpoint();
    if (ModelEndpointUri.containsCredentials(endpoint.baseUrl())) {
      log.error(
          "The configured embedding base URL carries credentials and is not taken over; configure"
              + " it without them and the key via OPAA_OPENAI_EMBEDDING_API_KEY");
      return false;
    }
    Integer probed = vectorTable.exists() ? null : probeForStartup(endpoint);
    if (!vectorTable.exists() && probed == null) {
      return false;
    }
    try {
      return Boolean.TRUE.equals(transactions.execute(status -> adoptLocked(endpoint, probed)));
    } catch (IllegalStateException e) {
      log.error(
          "The embedding model {} could not be taken over: {}. Set OPAA_SETTINGS_ENCRYPTION_KEY and"
              + " restart; until then indexing and search are refused",
          endpoint,
          e.getMessage());
      return false;
    }
  }

  private boolean adoptLocked(EmbeddingEndpoint endpoint, Integer probed) {
    vectorTable.lockSchemaChanges();
    if (repository.findInstalled().isPresent()) {
      return true;
    }
    Integer existing = vectorTable.embeddingDimensions();
    int dimensions;
    if (existing != null) {
      dimensions = legacy.dimensions();
    } else if (probed != null) {
      dimensions = probed;
      vectorTable.createIfAbsent(dimensions);
    } else {
      return false;
    }
    store(endpoint, dimensions);
    log.info(
        "Took over the configured embedding model '{}' at {} ({} dimensions) into embedding_model;"
            + " its configuration variables have no effect from now on",
        endpoint.modelIdentifier(),
        ModelEndpointUri.withoutSecrets(endpoint.baseUrl()),
        dimensions);
    return true;
  }

  private Integer probeForStartup(EmbeddingEndpoint endpoint) {
    ProbeOutcome outcome = probe(endpoint);
    if (!outcome.succeeded()) {
      log.error(
          "No embedding model is installed and the probe embedding of {} failed: {} Indexing and"
              + " search are refused until a start succeeds with a reachable model",
          endpoint,
          outcome.message());
      return null;
    }
    return outcome.dimensions();
  }

  private EmbeddingModelSettings store(EmbeddingEndpoint endpoint, int dimensions) {
    EmbeddingModelSettings settings =
        repository.save(
            new EmbeddingModelSettings(
                endpoint.baseUrl(),
                endpoint.modelIdentifier(),
                settingsEncryptor.encrypt(endpoint.apiKey()),
                endpoint.maxInputTokens(),
                dimensions,
                clock.instant()));
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
