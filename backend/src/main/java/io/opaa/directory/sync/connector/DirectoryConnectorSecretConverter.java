package io.opaa.directory.sync.connector;

import io.opaa.security.CredentialsEncryptor;
import io.opaa.security.EncryptedCredentials;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * JPA converter for {@link DirectoryConnector#getClientSecret()} (ADR-0036 Entscheidung 3,
 * ADR-0064): a new secret is encrypted for its connector on the way to the database, a loaded one
 * stays as stored until the getter decrypts it for the connector's id. Same shape as {@code
 * SourceCredentialsConverter}, and deliberately the same encryptor - one key per deployment for
 * stored access credentials, not a second one to forget.
 *
 * <p><b>Write fails hard, read fails soft</b> - and for the same reason the library converter does
 * it: the provider list ({@code GET /api/v1/admin/oidc-providers} maps every row), the delete and
 * the re-store must keep working when one value cannot be decrypted (a lost key, a restored backup)
 * - the last two are exactly the repair paths {@code docs/handbuch/betrieb/authentifizierung.md}
 * points an operator at. Loading never decrypts; the getter reads such a value as {@code null} and
 * logs a warning without any part of the value.
 *
 * <p><b>The two callers that need the secret check for {@code null} themselves</b> and say so:
 * {@link ProviderDirectoryClient} reports the directory as unreachable (so a run revokes nothing),
 * {@link DirectoryConnectorService#probe} refuses the probe with a German message asking for the
 * secret again. Silently signing in with an absent secret is what must not happen, and neither
 * does.
 */
@Converter(autoApply = false)
public class DirectoryConnectorSecretConverter
    implements AttributeConverter<EncryptedCredentials, String> {

  private final CredentialsEncryptor credentialsEncryptor;

  public DirectoryConnectorSecretConverter(CredentialsEncryptor credentialsEncryptor) {
    this.credentialsEncryptor = credentialsEncryptor;
  }

  @Override
  public String convertToDatabaseColumn(EncryptedCredentials attribute) {
    return attribute == null ? null : attribute.toColumn(credentialsEncryptor);
  }

  @Override
  public EncryptedCredentials convertToEntityAttribute(String dbData) {
    return EncryptedCredentials.loaded(dbData, credentialsEncryptor);
  }
}
