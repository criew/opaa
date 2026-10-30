package io.opaa.security;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Where a ciphertext lives: table, column and row id. It is the associated data of the keyed format
 * {@code enc:v2:}, so a ciphertext decrypts only at the place it was written for; one copied to
 * another row or column fails authentication instead of revealing its plaintext there. Built from
 * the column's {@link EncryptedColumn} with {@link EncryptedColumn#row(Object)}.
 */
public record CipherBinding(String table, String column, String rowId) {

  public CipherBinding {
    Objects.requireNonNull(table, "table");
    Objects.requireNonNull(column, "column");
    Objects.requireNonNull(rowId, "rowId");
  }

  /** The associated data of the keyed format: {@code table/column/rowId} in UTF-8. */
  byte[] associatedData() {
    return (table + "/" + column + "/" + rowId).getBytes(StandardCharsets.UTF_8);
  }

  @Override
  public String toString() {
    return table + "." + column + "[" + rowId + "]";
  }
}
