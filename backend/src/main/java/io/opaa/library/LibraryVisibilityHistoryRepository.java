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
   * KnowledgeLibraryRepository#findIdsByOrganizationIdAndVisibility} for a past instant, mirroring
   * {@link LibraryAccessService#readableLibraryIds}'s third source.
   */
  @Query(
      "select h.libraryId from LibraryVisibilityHistory h "
          + "where h.organizationId = :organizationId and h.visibility = io.opaa.api.types.LibraryVisibility.ORGANIZATION "
          + "and h.validFrom <= :asOf and (h.validTo is null or h.validTo > :asOf)")
  Set<UUID> findOrganizationWideLibraryIdsAsOf(
      @Param("organizationId") UUID organizationId, @Param("asOf") Instant asOf);

  /**
   * The one state interval of {@code libraryId} covering {@code asOf}. Zero-length event markers
   * ({@code validFrom = validTo}) never satisfy {@code validTo > asOf} together with {@code
   * validFrom <= asOf}, so they are excluded by the condition itself rather than by a filter - the
   * same reason {@link #findOrganizationWideLibraryIdsAsOf} needs none.
   */
  @Query(
      "select h from LibraryVisibilityHistory h where h.libraryId = :libraryId "
          + "and h.validFrom <= :asOf and (h.validTo is null or h.validTo > :asOf)")
  Optional<LibraryVisibilityHistory> findStateAsOf(
      @Param("libraryId") UUID libraryId, @Param("asOf") Instant asOf);
}
