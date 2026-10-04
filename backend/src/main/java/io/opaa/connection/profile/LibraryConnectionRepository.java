package io.opaa.connection.profile;

import io.opaa.connection.token.LibrariesOnProfile;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.SourceType;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LibraryConnectionRepository
    extends JpaRepository<LibraryConnection, UUID>, LibrariesOnProfile {

  List<LibraryConnection> findByProfileId(UUID profileId);

  @Override
  @Query("select c.libraryId from LibraryConnection c where c.profileId = :profileId")
  List<UUID> libraryIdsOnProfile(@Param("profileId") UUID profileId);

  /**
   * The connections of shared libraries on {@code profileId}: a private library's connection is its
   * owner's, and counting it would tell the administration that a person is connected.
   */
  @Query(
      "select count(c) from LibraryConnection c, KnowledgeLibrary l where l.id = c.libraryId"
          + " and c.profileId = :profileId and l.ownerOnly = false")
  long countSharedByProfileId(@Param("profileId") UUID profileId);

  /** {@link #countSharedByProfileId} of each of {@code profileIds} that has any, in one query. */
  @Query(
      "select c.profileId as profileId, count(c) as connections from LibraryConnection c,"
          + " KnowledgeLibrary l where l.id = c.libraryId and c.profileId in :profileIds"
          + " and l.ownerOnly = false group by c.profileId")
  List<ProfileConnectionCount> countSharedByProfileIdIn(
      @Param("profileIds") Collection<UUID> profileIds);

  /** The private libraries connected through {@code profileId}, of every owner. */
  @Query(
      "select c from LibraryConnection c, KnowledgeLibrary l where l.id = c.libraryId"
          + " and c.profileId = :profileId and l.ownerOnly = true")
  List<LibraryConnection> findPrivateOn(@Param("profileId") UUID profileId);

  /**
   * Every shared library of {@code type} in {@code organizationId}, by name - private libraries are
   * their owner's alone, also while they have no profile.
   */
  @Query(
      "select l from KnowledgeLibrary l where l.sourceType = :type"
          + " and l.organizationId = :organizationId and l.ownerOnly = false order by l.name, l.id")
  List<KnowledgeLibrary> findLibrariesOfType(
      @Param("type") SourceType type, @Param("organizationId") UUID organizationId);

  /** The private (owner-only) libraries of {@code userId} connected through {@code profileId}. */
  @Query(
      "select l from KnowledgeLibrary l, LibraryConnection c where c.libraryId = l.id"
          + " and c.profileId = :profileId and l.ownerOnly = true and l.ownerUserId = :userId"
          + " order by l.name, l.id")
  List<KnowledgeLibrary> findPrivateLibrariesOn(
      @Param("profileId") UUID profileId, @Param("userId") UUID userId);

  /** One row of {@link #countSharedByProfileIdIn}. */
  interface ProfileConnectionCount {
    UUID getProfileId();

    long getConnections();
  }
}
