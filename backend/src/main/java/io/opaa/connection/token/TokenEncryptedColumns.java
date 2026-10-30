package io.opaa.connection.token;

import io.opaa.security.EncryptedColumn;
import io.opaa.security.EncryptedColumns;
import java.util.List;
import org.springframework.stereotype.Component;

/** The encrypted columns of the token store and the revocation queue (ADR-0064). */
@Component
class TokenEncryptedColumns implements EncryptedColumns {

  @Override
  public List<EncryptedColumn> encryptedColumns() {
    return List.of(ConnectionToken.SECRET, ConnectionToken.ACCESS_TOKEN, GrantRevocations.ORDER);
  }
}
