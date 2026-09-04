package io.opaa.diagnosticaccess;

import io.opaa.api.types.DiagnosticTargetKind;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Persistence for {@link DiagnosticContextLogEntry}. Exactly three read queries exist, and the
 * omissions are the point (Leitplanke (g), Zweckbindung):
 *
 * <ul>
 *   <li>{@link #findOwnEntries} - the Einsichtsrecht of Leitplanke (h). Filters by one {@code
 *       targetRef}, and the only caller passes the requesting person's own pseudonym.
 *   <li>{@link #findByTimeRange} - the Gesamtprotokoll for the named Stellen. Takes a time range
 *       and nothing else: <b>there is no parameter for a target person on this path</b>, so "alle
 *       Diagnosen zu Person X" is not expressible through it.
 *   <li>{@link #findSingleEntry} - one entry by its own id, for the einzelfall- und anlassbezogene
 *       Auswertung. It selects a single row that the caller must already know of, so it adds no way
 *       to select rows <em>about someone</em>.
 * </ul>
 *
 * <p>No aggregate query of any kind is declared here - no {@code count}, no {@code group by}, no
 * {@code distinct} over {@code targetRef}. {@code DiagnosticContextLogPurposeLimitationTest} fails
 * the build if one is added.
 */
public interface DiagnosticContextLogRepository
    extends JpaRepository<DiagnosticContextLogEntry, UUID> {

  @Query(
      "SELECT e FROM DiagnosticContextLogEntry e WHERE e.organizationId = :organizationId"
          + " AND e.targetKind = :targetKind AND e.targetRef = :targetRef"
          + " ORDER BY e.recordedAt DESC")
  Page<DiagnosticContextLogEntry> findOwnEntries(
      @Param("organizationId") UUID organizationId,
      @Param("targetKind") DiagnosticTargetKind targetKind,
      @Param("targetRef") String targetRef,
      Pageable pageable);

  @Query(
      "SELECT e FROM DiagnosticContextLogEntry e WHERE e.organizationId = :organizationId"
          + " AND e.recordedAt >= :from AND e.recordedAt < :to ORDER BY e.recordedAt DESC")
  Page<DiagnosticContextLogEntry> findByTimeRange(
      @Param("organizationId") UUID organizationId,
      @Param("from") Instant from,
      @Param("to") Instant to,
      Pageable pageable);

  @Query(
      "SELECT e FROM DiagnosticContextLogEntry e WHERE e.organizationId = :organizationId"
          + " AND e.eventId = :eventId")
  Optional<DiagnosticContextLogEntry> findSingleEntry(
      @Param("organizationId") UUID organizationId, @Param("eventId") UUID eventId);
}
