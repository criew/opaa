package io.opaa.group.sync.connector;

import io.opaa.security.CredentialsEncryptor;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * JPA converter for {@link DirectoryConnector#getClientSecret()} (#1817, ADR-0036 Entscheidung 3):
 * encrypts on the way to the database, decrypts on the way back, transparent to the connector that
 * signs in with it. Same shape as {@code io.opaa.library.SourceCredentialsConverter}, and
 * deliberately the same encryptor the issue names - one key per deployment for stored access
 * credentials, not a second one to forget.
 *
 * <p><b>Both directions fail hard</b>, unlike the library converter's soft read failure. That one
 * is read on every {@code GET /api/v1/libraries} entry, where one undecryptable row must not take
 * the whole list down. A connector row is read by exactly two callers - the run and the connection
 * test - and for both, "the secret cannot be decrypted" must surface as the failure it is: a run
 * that silently continued with an absent secret would report the directory as unreachable for a
 * reason nobody could see, and {@code chk_directory_connectors_secret_encrypted} already rules out
 * the legacy-cleartext case the library column had.
 */
@Converter(autoApply = false)
public class DirectoryConnectorSecretConverter implements AttributeConverter<String, String> {

  private final CredentialsEncryptor credentialsEncryptor;

  public DirectoryConnectorSecretConverter(CredentialsEncryptor credentialsEncryptor) {
    this.credentialsEncryptor = credentialsEncryptor;
  }

  @Override
  public String convertToDatabaseColumn(String attribute) {
    return credentialsEncryptor.encrypt(attribute);
  }

  @Override
  public String convertToEntityAttribute(String dbData) {
    return credentialsEncryptor.decrypt(dbData);
  }
}
