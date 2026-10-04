package io.opaa.connection.request;

import io.opaa.knowledge.SourceType;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ConnectionProfileRequestRepository
    extends JpaRepository<ConnectionProfileRequest, UUID> {

  /**
   * Namespace of {@link #lockSubmissionsOf}'s advisory locks - registered in the list at {@code
   * AssetGrantRepository#ASSET_GRANT_MUTATION_LOCK_NAMESPACE}.
   */
  int PROFILE_REQUEST_SUBMISSION_LOCK_NAMESPACE = 206;

  /**
   * Serializes the submissions of one person for the rest of the transaction: the duplicate check,
   * the hourly budget and the ceiling of open requests count only what no parallel submission is
   * about to add.
   */
  @Query(
      value =
          "SELECT 1 FROM (SELECT pg_advisory_xact_lock("
              + PROFILE_REQUEST_SUBMISSION_LOCK_NAMESPACE
              + ", hashtext(CAST(:requestedBy AS text)))) acquired",
      nativeQuery = true)
  int lockSubmissionsOf(@Param("requestedBy") UUID requestedBy);

  /** The request, locked against a parallel resolution until the transaction ends. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<ConnectionProfileRequest> findLockedByIdAndOrganizationId(UUID id, UUID organizationId);

  Optional<ConnectionProfileRequest> findByRequestedByAndSourceTypeAndServerUrlAndState(
      UUID requestedBy, SourceType sourceType, String serverUrl, ProfileRequestState state);

  long countByRequestedByAndState(UUID requestedBy, ProfileRequestState state);

  /** The person's requests since {@code since}, oldest first. */
  List<ConnectionProfileRequest> findByRequestedByAndCreatedAtAfterOrderByCreatedAtAsc(
      UUID requestedBy, Instant since);

  List<ConnectionProfileRequest> findTop50ByRequestedByOrderByCreatedAtDesc(UUID requestedBy);

  Page<ConnectionProfileRequest> findByOrganizationId(UUID organizationId, Pageable pageable);

  Page<ConnectionProfileRequest> findByOrganizationIdAndState(
      UUID organizationId, ProfileRequestState state, Pageable pageable);
}
