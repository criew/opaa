package io.opaa.succession;

import io.opaa.api.types.SuccessionKind;
import io.opaa.api.types.SuccessionObjectType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SuccessionCaseRepository extends JpaRepository<SuccessionCase, UUID> {

  /** The open cases of one organization and tab - what the list joins its ages from. */
  List<SuccessionCase> findByOrganizationIdAndKindAndClosedAtIsNull(
      UUID organizationId, SuccessionKind kind);

  /** Every open case of one organization, for the detection run's own comparison. */
  List<SuccessionCase> findByOrganizationIdAndClosedAtIsNull(UUID organizationId);

  Optional<SuccessionCase> findByKindAndObjectTypeAndObjectIdAndClosedAtIsNull(
      SuccessionKind kind, SuccessionObjectType objectType, UUID objectId);

  /**
   * The open cases of one tab about one object, whatever its object type - what a transfer closes
   * when it acts. Object ids are unique across object types.
   */
  List<SuccessionCase> findByKindAndObjectIdAndClosedAtIsNull(SuccessionKind kind, UUID objectId);

  /**
   * Records another sighting of a still open case. Writes nothing else and nothing once the case is
   * closed - a close committed after the caller loaded the case is never undone.
   *
   * @return 1 if the case was open, 0 if it had been closed meanwhile
   */
  @Modifying
  @Query("update SuccessionCase c set c.lastSeenAt = :at where c.id = :id and c.closedAt is null")
  int markSeenIfOpen(@Param("id") UUID id, @Param("at") Instant at);

  /**
   * Closes a still open case. {@code endedBy} is the person whose action ended the state where that
   * is known (a transfer names itself); a state that simply ceased to hold is closed by the run and
   * names nobody. The first close wins: a case already closed keeps its end and its person.
   *
   * @return 1 if this call closed the case, 0 if it had been closed meanwhile
   */
  @Modifying
  @Query(
      "update SuccessionCase c set c.closedAt = :at, c.closedByUserId = :endedBy"
          + " where c.id = :id and c.closedAt is null")
  int closeIfOpen(@Param("id") UUID id, @Param("at") Instant at, @Param("endedBy") UUID endedBy);

  /**
   * Deletes the records whose state ended before {@code cutoff}. An open record is never touched,
   * whatever its age - it still describes something that holds.
   */
  @Modifying
  @Query("delete from SuccessionCase c where c.closedAt is not null and c.closedAt < :cutoff")
  int deleteClosedBefore(@Param("cutoff") Instant cutoff);
}
