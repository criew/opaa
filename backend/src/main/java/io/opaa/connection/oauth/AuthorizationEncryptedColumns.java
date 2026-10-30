package io.opaa.connection.oauth;

import io.opaa.security.EncryptedColumn;
import io.opaa.security.EncryptedColumns;
import java.util.List;
import org.springframework.stereotype.Component;

/** The PKCE verifier of a running sign-in at a provider, for the re-encryption (ADR-0064). */
@Component
class AuthorizationEncryptedColumns implements EncryptedColumns {

  @Override
  public List<EncryptedColumn> encryptedColumns() {
    return List.of(ConnectionAuthorization.CODE_VERIFIER);
  }
}
