package io.opaa.indexing;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentRepository extends JpaRepository<Document, UUID> {

  Optional<Document> findByFilePath(String filePath);
}
