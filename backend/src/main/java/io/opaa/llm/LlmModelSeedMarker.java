package io.opaa.llm;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * The permanent record that {@link LlmModelSeeder} has already attempted the one-time takeover of
 * the environment-configured chat model into {@code llm_models} (#756, PR #763 review).
 *
 * <p>Deliberately not inferred from "is {@code llm_models} currently empty?" - a Systemverwaltung
 * deleting every managed model must not resurrect a stale environment configuration on the next
 * restart, and {@code docs/features/llm-integration.md#übergang-aus-der-heutigen-konfiguration} is
 * explicit that "die Datenbank für das Chat-Modell führend [ist]. Die Umgebungsvariablen werden
 * nicht mehr ausgewertet." once the takeover has happened once. A single row here, inserted by
 * {@link LlmModelSeeder} rather than seeded by the migration itself, is what lets "never attempted"
 * and "attempted, found nothing to seed" be told apart.
 */
@Entity
@Table(name = "llm_model_seed_marker")
public class LlmModelSeedMarker {

  /** Always {@code 1} - enforced by {@code chk_llm_model_seed_marker_singleton}. */
  public static final int SINGLETON_ID = 1;

  @Id private Integer id;

  @Column(name = "seeded_at", nullable = false)
  private Instant seededAt;

  protected LlmModelSeedMarker() {}

  public LlmModelSeedMarker(Instant seededAt) {
    this.id = SINGLETON_ID;
    this.seededAt = seededAt;
  }

  public Integer getId() {
    return id;
  }

  public Instant getSeededAt() {
    return seededAt;
  }
}
