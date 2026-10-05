package io.opaa.connection.profile;

import io.opaa.api.types.ConnectionOwnership;
import io.opaa.knowledge.SourceType;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * The profiles. A list is always of one {@link ProfileKind}: the connector paths list only {@code
 * CONNECTOR} profiles, so an MCP server never reaches a path that expects a source type.
 */
public interface ConnectionProfileRepository extends JpaRepository<ConnectionProfile, UUID> {

  List<ConnectionProfile> findByKindOrderByNameAsc(ProfileKind kind);

  /** The connector profiles by name - the list every connector path starts from. */
  default List<ConnectionProfile> findConnectorsByName() {
    return findByKindOrderByNameAsc(ProfileKind.CONNECTOR);
  }

  /** {@code id} if it names a profile of {@code kind}. */
  Optional<ConnectionProfile> findByIdAndKind(UUID id, ProfileKind kind);

  List<ConnectionProfile> findBySourceTypeOrderByNameAsc(SourceType sourceType);

  boolean existsByOwnershipIn(Collection<ConnectionOwnership> ownerships);

  @Query(
      "select count(p) > 0 from ConnectionProfile p"
          + " where lower(p.name) = lower(:name) and (:excludedId is null or p.id <> :excludedId)")
  boolean existsByNameIgnoringCase(
      @Param("name") String name, @Param("excludedId") UUID excludedId);

  /**
   * Holds the row of {@code id} until the transaction ends against every other change and every
   * {@link #lockedVersion} - a library connected, created, released or changed through it, a
   * consent stored; a row inserted with a reference to it still passes. A change takes it as its
   * first statement, before any row it writes. Returns {@code null} for no such profile.
   */
  @Query(
      value = "SELECT 1 FROM connection_profiles WHERE id = :id FOR NO KEY UPDATE",
      nativeQuery = true)
  Integer lockForChange(@Param("id") UUID id);

  /**
   * {@link #lockForChange} for deleting the row, which also holds off every row inserted with a
   * reference to it.
   */
  @Query(value = "SELECT 1 FROM connection_profiles WHERE id = :id FOR UPDATE", nativeQuery = true)
  Integer lockForDeletion(@Param("id") UUID id);

  /**
   * The row version of {@code id}, the row held against a change until the transaction ends, so
   * what is written now stands for this version; {@code null} for no such profile. Holders do not
   * wait for one another.
   */
  @Query(
      value = "SELECT version FROM connection_profiles WHERE id = :id FOR SHARE",
      nativeQuery = true)
  Long lockedVersion(@Param("id") UUID id);

  /**
   * The profiles holding a client secret whose expiry date is {@code lastDay} or earlier and not
   * warned of yet, locked so that a parallel run waits and then skips them.
   */
  @Query(
      value =
          "SELECT id FROM connection_profiles WHERE client_secret_ciphertext IS NOT NULL"
              + " AND client_secret_expires_on <= :lastDay"
              + " AND client_secret_expiry_warned_at IS NULL FOR UPDATE",
      nativeQuery = true)
  List<UUID> lockSecretsExpiringUnwarned(@Param("lastDay") LocalDate lastDay);

  /** Marks the expiry of the client secret of {@code ids} as warned of, without a new version. */
  @Modifying
  @Query("update ConnectionProfile p set p.clientSecretExpiryWarnedAt = :at where p.id in :ids")
  int markSecretExpiryWarned(@Param("ids") Collection<UUID> ids, @Param("at") Instant at);

  /** Sets or ({@code at} {@code null}) lifts the rejection of the profile's own sign-in. */
  @Modifying
  @Query("update ConnectionProfile p set p.signInRejectedAt = :at where p.id = :id")
  int markSignInRejected(@Param("id") UUID id, @Param("at") Instant at);
}
