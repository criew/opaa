package io.opaa.connection.profile;

import io.opaa.api.types.ConnectionOwnership;
import io.opaa.knowledge.SourceType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ConnectionProfileRepository extends JpaRepository<ConnectionProfile, UUID> {

  List<ConnectionProfile> findAllByOrderByNameAsc();

  List<ConnectionProfile> findBySourceTypeOrderByNameAsc(SourceType sourceType);

  boolean existsByOwnershipIn(Collection<ConnectionOwnership> ownerships);

  @Query(
      "select count(p) > 0 from ConnectionProfile p"
          + " where lower(p.name) = lower(:name) and (:excludedId is null or p.id <> :excludedId)")
  boolean existsByNameIgnoringCase(
      @Param("name") String name, @Param("excludedId") UUID excludedId);

  /**
   * Locks the row of {@code id} against every change and against a consent being stored on it until
   * the transaction ends; a change takes it before it discards the profile's secrets. Returns
   * {@code null} for no such profile.
   */
  @Query(value = "SELECT 1 FROM connection_profiles WHERE id = :id FOR UPDATE", nativeQuery = true)
  Integer lockForChange(@Param("id") UUID id);

  /**
   * The row version of {@code id}, the row held against a change until the transaction ends, so a
   * consent stored now stands for this version; {@code null} for no such profile.
   */
  @Query(
      value = "SELECT version FROM connection_profiles WHERE id = :id FOR SHARE",
      nativeQuery = true)
  Long lockedVersion(@Param("id") UUID id);

  /** Sets or ({@code at} {@code null}) lifts the rejection of the profile's own sign-in. */
  @Modifying
  @Query("update ConnectionProfile p set p.signInRejectedAt = :at where p.id = :id")
  int markSignInRejected(@Param("id") UUID id, @Param("at") Instant at);
}
