package io.opaa.account;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

/** Persistence for the singleton {@link LocalAdminResetRecord} row. */
@Repository
public interface LocalAdminResetRecordRepository
    extends JpaRepository<LocalAdminResetRecord, Integer> {

  /**
   * Holds the start-up lock until the transaction ends, so instances that start together reset one
   * after another. Namespace {@code 211}, shared with the installation's start-up decision.
   */
  @Query(
      value = "SELECT 1 FROM (SELECT pg_advisory_xact_lock(211, 0)) acquired",
      nativeQuery = true)
  int lockStartupSeeds();
}
