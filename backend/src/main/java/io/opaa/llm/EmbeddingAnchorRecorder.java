package io.opaa.llm;

import io.opaa.compliance.DataFlow;
import java.sql.Array;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Takes the reference anchor of an installed model that has none (ADR-0061, Entscheidung 5): after
 * the installation, at the start and after the first successful embedding of the installed client,
 * the status probe among them, it embeds {@link EmbeddingAnchor#TEXTS} with the installed client
 * and stores them only while the row still has no anchor and still names the same generation and
 * address. So the anchor is there before an outage asks for an endpoint change. One attempt at a
 * time per instance, off the calling thread (ADR-0053: "je Instanz"); the conditional write makes
 * concurrent instances harmless.
 */
@DataFlow("embedding-model")
@Component
public class EmbeddingAnchorRecorder {

  private static final Logger log = LoggerFactory.getLogger(EmbeddingAnchorRecorder.class);

  private final EmbeddingModelSettingsRepository repository;
  private final EmbeddingClients clients;
  private final JdbcTemplate jdbcTemplate;
  private final AtomicBoolean running = new AtomicBoolean();

  EmbeddingAnchorRecorder(
      EmbeddingModelSettingsRepository repository,
      EmbeddingClients clients,
      JdbcTemplate jdbcTemplate) {
    this.repository = repository;
    this.clients = clients;
    this.jdbcTemplate = jdbcTemplate;
  }

  @EventListener(ApplicationReadyEvent.class)
  void atStart() {
    recordIfMissingInBackground();
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  void afterInstallation(EmbeddingModelInstalledEvent event) {
    recordIfMissingInBackground();
  }

  @EventListener
  void afterASuccessfulEmbedding(EmbeddingAnchorMissingEvent event) {
    recordIfMissingInBackground();
  }

  /** Starts {@link #recordIfMissing} on its own thread unless one is running here already. */
  public void recordIfMissingInBackground() {
    if (running.compareAndSet(false, true)) {
      Thread.ofVirtual()
          .name("embedding-anchor")
          .start(
              () -> {
                try {
                  recordIfMissing();
                } finally {
                  running.set(false);
                }
              });
    }
  }

  /**
   * Embeds the anchor texts with the installed client and stores them when the row still lacks an
   * anchor. Never throws: an unreachable endpoint leaves the anchor missing for a later attempt.
   *
   * @return whether this call stored the anchor
   */
  public boolean recordIfMissing() {
    try {
      EmbeddingModelSettings row = repository.findInstalled().orElse(null);
      if (row == null || row.hasReferenceAnchor()) {
        return false;
      }
      EmbeddingClients.Pinned pinned = clients.pin();
      if (pinned.generation() != row.getGeneration()) {
        return false;
      }
      List<float[]> vectors = pinned.client().embed(EmbeddingAnchor.TEXTS);
      if (vectors.size() != EmbeddingAnchor.TEXTS.size()
          || vectors.stream().anyMatch(vector -> vector.length != row.getDimensions())) {
        log.warn("The embedding endpoint answered the anchor texts with unexpected vectors");
        return false;
      }
      float[] anchor = EmbeddingAnchor.flatten(vectors);
      int stored =
          jdbcTemplate.update(
              "UPDATE embedding_model SET reference_vectors = ? WHERE id = 1"
                  + " AND reference_vectors IS NULL AND generation = ? AND base_url = ?",
              ps -> {
                Array array = ps.getConnection().createArrayOf("real", boxed(anchor));
                ps.setArray(1, array);
                ps.setLong(2, row.getGeneration());
                ps.setString(3, row.getBaseUrl());
              });
      if (stored == 1) {
        log.info(
            "Recorded the reference anchor of embedding model '{}' (generation {})",
            row.getModelIdentifier(),
            row.getGeneration());
      }
      return stored == 1;
    } catch (RuntimeException e) {
      log.info(
          "The reference anchor of the embedding model could not be taken yet: {}",
          e.getClass().getSimpleName());
      return false;
    }
  }

  private static Float[] boxed(float[] values) {
    Float[] boxed = new Float[values.length];
    for (int i = 0; i < values.length; i++) {
      boxed[i] = values[i];
    }
    return boxed;
  }
}
