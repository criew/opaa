package io.opaa.security;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A database column that holds ciphertexts of one {@link EncryptionPurpose}, with the column that
 * identifies its rows. The owning module declares it once and uses it both to bind every encrypt
 * and decrypt to the row ({@link #row(Object)}) and to announce it to {@link CiphertextRewrap}
 * through an {@link EncryptedColumns} bean. Names are plain SQL identifiers, because the rewrap
 * puts them into its statements.
 *
 * @param cleartextRefused whether every reader of the column treats legacy cleartext as no value;
 *     the rewrap then neither encrypts such cleartext, which would make it usable, nor waits for it
 *     before retiring the unbound forms ({@link LegacyCipherRetirement})
 */
public record EncryptedColumn(
    EncryptionPurpose purpose,
    String table,
    String column,
    String idColumn,
    boolean cleartextRefused) {

  private static final Pattern IDENTIFIER = Pattern.compile("[a-z_][a-z0-9_]*");

  public EncryptedColumn {
    Objects.requireNonNull(purpose, "purpose");
    requireIdentifier(table);
    requireIdentifier(column);
    requireIdentifier(idColumn);
  }

  /** A column of {@link CredentialsEncryptor} whose rows are identified by {@code id}. */
  public static EncryptedColumn credentials(String table, String column) {
    return new EncryptedColumn(EncryptionPurpose.CREDENTIALS, table, column, "id", false);
  }

  /** A column of {@link SettingsEncryptor} whose rows are identified by {@code id}. */
  public static EncryptedColumn settings(String table, String column) {
    return new EncryptedColumn(EncryptionPurpose.SETTINGS, table, column, "id", false);
  }

  /** The same column with rows identified by {@code idColumn} instead of {@code id}. */
  public EncryptedColumn keyedBy(String idColumn) {
    return new EncryptedColumn(purpose, table, column, idColumn, cleartextRefused);
  }

  /** The same column, read only by code that treats legacy cleartext as no value. */
  public EncryptedColumn refusingCleartext() {
    return new EncryptedColumn(purpose, table, column, idColumn, true);
  }

  /**
   * Whether the rewrap encrypts legacy cleartext of this column: credentials whose readers use it.
   */
  boolean acceptsCleartext() {
    return purpose == EncryptionPurpose.CREDENTIALS && !cleartextRefused;
  }

  /** The binding of the row whose {@link #idColumn} holds {@code id}. */
  public CipherBinding row(Object id) {
    return new CipherBinding(table, column, String.valueOf(Objects.requireNonNull(id, "id")));
  }

  private static void requireIdentifier(String name) {
    if (name == null || !IDENTIFIER.matcher(name).matches()) {
      throw new IllegalArgumentException("not a plain SQL identifier: " + name);
    }
  }
}
