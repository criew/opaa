package io.opaa.installation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * The one row of the installation state (ADR-0060, Entscheidung 4), shared by every instance. A
 * missing row means pending. Once {@link Phase#COMPLETED}, nothing in the application sets it back;
 * the token hash is cleared with the completion.
 */
@Entity
@Table(name = "installation_state")
public class InstallationState {

  /** Always {@code 1} - enforced by {@code chk_installation_state_singleton}. */
  public static final short SINGLETON_ID = 1;

  /** Pending until the completion, completed for good afterwards. */
  public enum Phase {
    PENDING,
    COMPLETED
  }

  /** Where the stored token hash came from. */
  public enum TokenSource {
    GENERATED,
    CONFIGURED
  }

  /** How the installation was completed. */
  public enum CompletionSource {
    /** Through {@code POST /api/v1/installation/complete}. */
    INSTALLATION_API,
    /** Through {@code POST /api/v1/installation/answer-file}. */
    ANSWER_FILE_API,
    /** From {@code OPAA_INSTALLATION_ANSWER_FILE} at start-up. */
    ANSWER_FILE_STARTUP,
    /** The {@code dev} mode installs itself. */
    DEVELOPMENT,
    /** A database an earlier version already started on. */
    EXISTING
  }

  @Id private Short id;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private Phase status;

  @Column(name = "token_hash", length = 64)
  private String tokenHash;

  @Enumerated(EnumType.STRING)
  @Column(name = "token_source", length = 20)
  private TokenSource tokenSource;

  @Enumerated(EnumType.STRING)
  @Column(name = "completion_source", length = 30)
  private CompletionSource completionSource;

  @Column(name = "completed_at")
  private Instant completedAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected InstallationState() {}

  static InstallationState pending(Instant now) {
    InstallationState state = new InstallationState();
    state.id = SINGLETON_ID;
    state.status = Phase.PENDING;
    state.updatedAt = now;
    return state;
  }

  public boolean isCompleted() {
    return status == Phase.COMPLETED;
  }

  String getTokenHash() {
    return tokenHash;
  }

  TokenSource getTokenSource() {
    return tokenSource;
  }

  CompletionSource getCompletionSource() {
    return completionSource;
  }

  Instant getCompletedAt() {
    return completedAt;
  }

  void storeToken(String hash, TokenSource source, Instant now) {
    this.tokenHash = hash;
    this.tokenSource = source;
    this.updatedAt = now;
  }

  void complete(CompletionSource source, Instant now) {
    this.status = Phase.COMPLETED;
    this.completionSource = source;
    this.completedAt = now;
    this.tokenHash = null;
    this.tokenSource = null;
    this.updatedAt = now;
  }
}
