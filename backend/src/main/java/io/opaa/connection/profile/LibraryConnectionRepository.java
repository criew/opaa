package io.opaa.connection.profile;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LibraryConnectionRepository extends JpaRepository<LibraryConnection, UUID> {

  List<LibraryConnection> findByProfileId(UUID profileId);

  long countByProfileId(UUID profileId);

  /** The connection count of each of {@code profileIds} that has any, in one query. */
  @Query(
      "select c.profileId as profileId, count(c) as connections from LibraryConnection c"
          + " where c.profileId in :profileIds group by c.profileId")
  List<ProfileConnectionCount> countByProfileIdIn(@Param("profileIds") Collection<UUID> profileIds);

  /** One row of {@link #countByProfileIdIn}. */
  interface ProfileConnectionCount {
    UUID getProfileId();

    long getConnections();
  }
}
