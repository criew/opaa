package io.opaa.connection.profile;

import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.SourceType;
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

  /** Every library of {@code type} in {@code organizationId}, by name. */
  @Query(
      "select l from KnowledgeLibrary l where l.sourceType = :type"
          + " and l.organizationId = :organizationId order by l.name, l.id")
  List<KnowledgeLibrary> findLibrariesOfType(
      @Param("type") SourceType type, @Param("organizationId") UUID organizationId);

  /** One row of {@link #countByProfileIdIn}. */
  interface ProfileConnectionCount {
    UUID getProfileId();

    long getConnections();
  }
}
