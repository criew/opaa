package io.opaa.installation;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

/** Persistence for the singleton {@link InstallationState} row. */
@Repository
public interface InstallationStateRepository extends JpaRepository<InstallationState, Short> {

  /** Writes the pending row unless it exists; a concurrent writer leaves one row either way. */
  @Modifying
  @Query(
      value =
          "INSERT INTO installation_state (id, status, updated_at)"
              + " VALUES (1, 'PENDING', clock_timestamp()) ON CONFLICT (id) DO NOTHING",
      nativeQuery = true)
  int insertPendingIfAbsent();

  /** The row under a row lock until the transaction ends. */
  @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT s FROM InstallationState s WHERE s.id = 1")
  Optional<InstallationState> findForUpdate();

  /**
   * Holds the start-up lock until the transaction ends, after the row lock: the completion runs one
   * at a time with the emergency reset. Namespace {@code 211}, shared with it.
   */
  @Query(
      value = "SELECT 1 FROM (SELECT pg_advisory_xact_lock(211, 0)) acquired",
      nativeQuery = true)
  int lockStartupSeeds();

  /** Whether a row says completed; read on every request while the installation is pending. */
  @Query(
      value = "SELECT count(*) > 0 FROM installation_state WHERE id = 1 AND status = 'COMPLETED'",
      nativeQuery = true)
  boolean isCompleted();
}
