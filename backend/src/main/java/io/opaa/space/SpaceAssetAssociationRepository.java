package io.opaa.space;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SpaceAssetAssociationRepository
    extends JpaRepository<SpaceAssetAssociation, UUID> {

  List<SpaceAssetAssociation> findBySpaceIdOrderByCreatedAtAsc(UUID spaceId);

  List<SpaceAssetAssociation> findByLibraryIdOrderByCreatedAtAsc(UUID libraryId);

  Optional<SpaceAssetAssociation> findBySpaceIdAndLibraryId(UUID spaceId, UUID libraryId);

  boolean existsBySpaceIdAndLibraryId(UUID spaceId, UUID libraryId);

  /**
   * Every library id associated with {@code spaceId} - the set {@code
   * ChatService#effectiveLibraryScope} intersects with the caller's readable libraries for the
   * default @Alles-Wissen search scope (docs/features/spaces-and-assets.md#suchbereich-je-chatart).
   * An empty result means "no association exists yet", which the caller must treat as "do not
   * narrow" (the permanent transition rule), not as "search nothing".
   */
  @Query("select a.libraryId from SpaceAssetAssociation a where a.spaceId = :spaceId")
  Set<UUID> findLibraryIdsBySpaceId(@Param("spaceId") UUID spaceId);

  /**
   * Every association of the given spaces in one query - the overview card's "Quellen" figure
   * (#682) is counted from this in memory, because a plain MEMBER's figure must only include the
   * libraries they may read (same rule as {@code SpaceAssetAssociationService#listForSpace}), which
   * no grouped SQL count can express without the caller's readable set.
   */
  List<SpaceAssetAssociation> findBySpaceIdIn(Collection<UUID> spaceIds);
}
