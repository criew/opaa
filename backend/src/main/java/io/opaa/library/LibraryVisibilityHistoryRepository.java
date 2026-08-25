package io.opaa.library;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LibraryVisibilityHistoryRepository
    extends JpaRepository<LibraryVisibilityHistory, UUID> {

  Optional<LibraryVisibilityHistory> findByLibraryIdAndValidToIsNull(UUID libraryId);

  /**
   * Every organization-wide library at {@code asOf} - the interval's {@code validFrom <= asOf} and
   * ({@code validTo IS NULL OR validTo > asOf}), with {@code visibility = ORGANIZATION}. The
   * organization-wide counterpart of {@link
   * KnowledgeLibraryRepository#findByOrganizationIdAndVisibility} for a past instant, mirroring
   * {@link LibraryAccessService#readableLibraryIds}'s third source.
   */
  @Query(
      "select h.libraryId from LibraryVisibilityHistory h "
          + "where h.organizationId = :organizationId and h.visibility = io.opaa.api.types.LibraryVisibility.ORGANIZATION "
          + "and h.validFrom <= :asOf and (h.validTo is null or h.validTo > :asOf)")
  Set<UUID> findOrganizationWideLibraryIdsAsOf(
      @Param("organizationId") UUID organizationId, @Param("asOf") Instant asOf);
}
