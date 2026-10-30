package io.opaa.security;

import java.util.List;

/**
 * The encrypted columns of one module, as a bean: {@link CiphertextRewrap} re-encrypts exactly the
 * columns all such beans name. A column missing here keeps values under a retired key, which stop
 * decrypting once that key leaves the decrypt-only keys.
 */
public interface EncryptedColumns {

  List<EncryptedColumn> encryptedColumns();
}
