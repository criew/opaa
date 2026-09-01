package io.opaa.audit;

import io.opaa.api.types.AuditSubjectKind;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves the stable pseudonym id a caller writing an {@link AuditLogEntry} uses as {@code
 * actorRef} (or {@code subjectRef} for a {@link AuditSubjectKind#USER} subject) for a given user -
 * minted once per user and reused afterwards, not re-minted per event, so entries sharing one
 * (unnamed) actor remain recognisable as such across the log
 * (docs/features/security-and-compliance.md#der-protokollsatz).
 */
@Service
public class AuditActorPseudonymService {

  private final AuditActorPseudonymRepository repository;

  public AuditActorPseudonymService(AuditActorPseudonymRepository repository) {
    this.repository = repository;
  }

  /**
   * Returns the pseudonym id for {@code userId}, creating one on first use. {@code organizationId}
   * is only recorded on first creation; it is not re-validated against an existing row.
   *
   * <p>{@code @Transactional} here only wraps this method's own read/insert-if-absent/re-read
   * sequence - required for the {@code @Modifying} query {@link
   * AuditActorPseudonymRepository#insertIfAbsent} to run at all. Unlike {@link
   * AuditLogService#record}, there is no rollback-visibility requirement to preserve here: this
   * lookup has no side effect on {@code audit_log} itself, and simply joining an ambient caller
   * transaction (the default propagation) is exactly what should happen when one is already open.
   */
  @Transactional
  public UUID pseudonymFor(UUID userId, UUID organizationId) {
    return repository
        .findByUserId(userId)
        .map(AuditActorPseudonym::getPseudonymId)
        .orElseGet(
            () -> {
              repository.insertIfAbsent(UUID.randomUUID(), userId, organizationId);
              return repository
                  .findByUserId(userId)
                  .map(AuditActorPseudonym::getPseudonymId)
                  .orElseThrow(
                      () ->
                          new IllegalStateException(
                              "Pseudonym for user " + userId + " missing after insertIfAbsent"));
            });
  }

  /**
   * Looks up {@code userId}'s pseudonym without minting one - unlike {@link #pseudonymFor}, a
   * read-only caller (e.g. {@link AuditQueryService#byIncidentScope}) must never have the side
   * effect of creating a re-identification row for a person who never triggered a write themselves.
   * Empty if the person has no audit activity of their own yet.
   */
  public Optional<UUID> findExistingPseudonym(UUID userId) {
    return repository.findByUserId(userId).map(AuditActorPseudonym::getPseudonymId);
  }

  /**
   * The reverse direction: which person a pseudonym belongs to. Deliberately narrow - the only
   * caller is the Einsichtsrecht of docs/features/hybrid-retrieval.md, Leitplanke (h), which
   * requires a person to see <em>by whom</em> their rights context was assumed. No revision access
   * path resolves a pseudonym this way.
   */
  public Optional<UUID> findUserByPseudonym(UUID pseudonymId) {
    return repository.findById(pseudonymId).map(AuditActorPseudonym::getUserId);
  }
}
