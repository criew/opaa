package io.opaa.connection.profile;

import io.opaa.security.EncryptedColumn;
import io.opaa.security.EncryptedColumns;
import java.util.List;
import org.springframework.stereotype.Component;

/** The client secret of a connection profile, for the re-encryption (ADR-0064). */
@Component
class ProfileEncryptedColumns implements EncryptedColumns {

  @Override
  public List<EncryptedColumn> encryptedColumns() {
    return List.of(ConnectionProfile.CLIENT_SECRET);
  }
}
