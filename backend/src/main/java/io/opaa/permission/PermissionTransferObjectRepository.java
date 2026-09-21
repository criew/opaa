package io.opaa.permission;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PermissionTransferObjectRepository
    extends JpaRepository<PermissionTransferObject, UUID> {

  List<PermissionTransferObject> findByTransferId(UUID transferId);
}
