package io.opaa.installation;

import io.opaa.security.CipherBinding;
import io.opaa.security.EncryptedColumn;
import io.opaa.security.EncryptedColumns;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * The secret of a saved installation step, encrypted with the settings key (ADR-0064) for model
 * keys and the mail password; the first account's step holds a password hash there, which the
 * re-encryption leaves alone because it carries no encryption marker.
 */
@Component
class InstallationEncryptedColumns implements EncryptedColumns {

  static final EncryptedColumn STEP_SECRET =
      EncryptedColumn.settings("installation_steps", "secret").keyedBy("step");

  /** The binding of the secret of {@code step}, whose row the enum name identifies. */
  static CipherBinding of(InstallationStep step) {
    return STEP_SECRET.row(step.name());
  }

  @Override
  public List<EncryptedColumn> encryptedColumns() {
    return List.of(STEP_SECRET);
  }
}
