package io.opaa.connection.request;

import io.opaa.knowledge.SourceType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConnectionProfileRequestRepository
    extends JpaRepository<ConnectionProfileRequest, UUID> {

  Optional<ConnectionProfileRequest> findByIdAndOrganizationId(UUID id, UUID organizationId);

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
