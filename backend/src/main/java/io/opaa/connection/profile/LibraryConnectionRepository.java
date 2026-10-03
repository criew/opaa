package io.opaa.connection.profile;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LibraryConnectionRepository extends JpaRepository<LibraryConnection, UUID> {

  List<LibraryConnection> findByProfileId(UUID profileId);

  long countByProfileId(UUID profileId);
}
