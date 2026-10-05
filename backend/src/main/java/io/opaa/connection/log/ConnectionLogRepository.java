package io.opaa.connection.log;

import io.opaa.api.types.ConnectionLogEventType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * The connection log table. Package-private: {@link ConnectionLog} writes, {@link
 * ConnectionLogQueryService} reads and {@link ConnectionLogRetention} deletes, nobody else. No
 * query filters by person.
 */
interface ConnectionLogRepository extends JpaRepository<ConnectionLogEntry, UUID> {

  @Query(
      "SELECT e FROM ConnectionLogEntry e WHERE e.organizationId = :organizationId"
          + " AND e.recordedAt >= :from AND e.recordedAt <= :to"
          + " AND (:eventType IS NULL OR e.eventType = :eventType)"
          + " AND (:profileId IS NULL OR e.profileId = :profileId)")
  Page<ConnectionLogEntry> findInRange(
      @Param("organizationId") UUID organizationId,
      @Param("from") Instant from,
      @Param("to") Instant to,
      @Param("eventType") ConnectionLogEventType eventType,
      @Param("profileId") UUID profileId,
      Pageable pageable);

  /** Every profile the organization's log names, with the name of its newest entry. */
  @Query(
      value =
          "SELECT DISTINCT ON (profile_id) profile_id AS \"profileId\", profile_name AS \"name\""
              + " FROM connection_log WHERE organization_id = :organizationId"
              + " ORDER BY profile_id, recorded_at DESC",
      nativeQuery = true)
  List<LoggedProfile> findLoggedProfiles(@Param("organizationId") UUID organizationId);

  /** One profile of the log, by its last logged name. */
  interface LoggedProfile {
    UUID getProfileId();

    String getName();
  }

  /** Drops every fully expired monthly partition and returns their names, possibly none. */
  @Query(
      value = "SELECT * FROM opaa_connection_log_delete_expired_partitions()",
      nativeQuery = true)
  List<String> deleteExpiredPartitions();
}
