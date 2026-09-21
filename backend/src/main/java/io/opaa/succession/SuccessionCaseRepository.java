package io.opaa.succession;

import io.opaa.api.types.SuccessionKind;
import io.opaa.api.types.SuccessionObjectType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SuccessionCaseRepository extends JpaRepository<SuccessionCase, UUID> {

  /** The open cases of one organization and tab - what the list joins its ages from. */
  List<SuccessionCase> findByOrganizationIdAndKindAndClosedAtIsNull(
      UUID organizationId, SuccessionKind kind);

  /** Every open case of one organization, for the detection run's own comparison. */
  List<SuccessionCase> findByOrganizationIdAndClosedAtIsNull(UUID organizationId);

  Optional<SuccessionCase> findByKindAndObjectTypeAndObjectIdAndClosedAtIsNull(
      SuccessionKind kind, SuccessionObjectType objectType, UUID objectId);

  /** Every open case about one object, whatever the tab - what a transfer closes when it acts. */
  List<SuccessionCase> findByObjectTypeAndObjectIdAndClosedAtIsNull(
      SuccessionObjectType objectType, UUID objectId);

  Optional<SuccessionCase> findByIdAndOrganizationId(UUID id, UUID organizationId);
}
