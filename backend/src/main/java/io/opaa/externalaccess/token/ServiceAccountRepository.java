package io.opaa.externalaccess.token;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ServiceAccountRepository extends JpaRepository<ServiceAccount, UUID> {

  List<ServiceAccount> findByOrganizationId(UUID organizationId);

  List<ServiceAccount> findByOrganizationIdAndDeactivatedAtIsNull(UUID organizationId);

  Optional<ServiceAccount> findByIdAndOrganizationId(UUID id, UUID organizationId);

  /**
   * The same row locked for the transaction: issuing a token and deactivating the account take it
   * in turn, so no token is issued past a deactivation that has already revoked the others.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT s FROM ServiceAccount s WHERE s.id = :id AND s.organizationId = :organizationId")
  Optional<ServiceAccount> lockByIdAndOrganizationId(
      @Param("id") UUID id, @Param("organizationId") UUID organizationId);
}
