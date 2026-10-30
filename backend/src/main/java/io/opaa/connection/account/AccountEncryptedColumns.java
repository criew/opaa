package io.opaa.connection.account;

import io.opaa.security.EncryptedColumn;
import io.opaa.security.EncryptedColumns;
import java.util.List;
import org.springframework.stereotype.Component;

/** The account name of a connected account, for the re-encryption (ADR-0064). */
@Component
class AccountEncryptedColumns implements EncryptedColumns {

  @Override
  public List<EncryptedColumn> encryptedColumns() {
    return List.of(ConnectedAccount.ACCOUNT_LABEL);
  }
}
