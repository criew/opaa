package io.opaa.connection.profile;

import io.opaa.knowledge.SourceType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ConnectionProfileRepository extends JpaRepository<ConnectionProfile, UUID> {

  List<ConnectionProfile> findAllByOrderByNameAsc();

  List<ConnectionProfile> findBySourceTypeOrderByNameAsc(SourceType sourceType);

  @Query(
      "select count(p) > 0 from ConnectionProfile p"
          + " where lower(p.name) = lower(:name) and (:excludedId is null or p.id <> :excludedId)")
  boolean existsByNameIgnoringCase(
      @Param("name") String name, @Param("excludedId") UUID excludedId);

  /** Sets or ({@code at} {@code null}) lifts the rejection of the profile's own sign-in. */
  @Modifying
  @Query("update ConnectionProfile p set p.signInRejectedAt = :at where p.id = :id")
  int markSignInRejected(@Param("id") UUID id, @Param("at") Instant at);
}
