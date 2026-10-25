package io.opaa.installation;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

/** Persistence for the saved {@link InstallationStepRecord}s. */
@Repository
public interface InstallationStepRepository
    extends JpaRepository<InstallationStepRecord, InstallationStep> {

  /** The saved step under a row lock until the transaction ends. */
  @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT s FROM InstallationStepRecord s WHERE s.step = :step")
  Optional<InstallationStepRecord> findForUpdate(InstallationStep step);
}
