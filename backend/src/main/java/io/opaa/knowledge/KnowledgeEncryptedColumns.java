package io.opaa.knowledge;

import io.opaa.security.EncryptedColumn;
import io.opaa.security.EncryptedColumns;
import java.util.List;
import org.springframework.stereotype.Component;

/** The encrypted columns of a knowledge library, for the re-encryption (ADR-0064). */
@Component
class KnowledgeEncryptedColumns implements EncryptedColumns {

  @Override
  public List<EncryptedColumn> encryptedColumns() {
    return List.of(KnowledgeLibrary.SOURCE_CREDENTIALS, KnowledgeLibrary.WEBHOOK_SECRET);
  }
}
