package io.opaa.group.sync.connector;

import io.opaa.security.CredentialsEncryptionKeyMissingException;
import io.opaa.security.CredentialsEncryptor;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JPA converter for {@link DirectoryConnector#getClientSecret()} (#1817, ADR-0036 Entscheidung 3):
 * encrypts on the way to the database, decrypts on the way back, transparent to the connector that
 * signs in with it. Same shape as {@code io.opaa.library.SourceCredentialsConverter}, and
 * deliberately the same encryptor the issue names - one key per deployment for stored access
 * credentials, not a second one to forget.
 *
 * <p><b>Write fails hard, read fails soft</b> - and for the same reason the library converter does
 * it: this is an attribute of the entity, so it is decrypted on <em>every</em> hydration of the
 * row, not only where the secret is actually wanted. Reading it hard would take down the provider
 * list ({@code GET /api/v1/admin/oidc-providers} maps every row), the delete and the re-store as
 * soon as one value cannot be decrypted (a rotated {@code OPAA_CREDENTIALS_ENCRYPTION_KEY}, a
 * restored backup) - and those last two are exactly the two repair paths {@code
 * docs/handbuch/deployment.md} points an operator at. A read failure therefore yields {@code null}
 * and a warning without any part of the value.
 *
 * <p><b>The two callers that need the secret check for {@code null} themselves</b> and say so:
 * {@link ProviderDirectoryClient} reports the directory as unreachable (so a run revokes nothing),
 * {@link DirectoryConnectorService#probe} refuses the probe with a German message asking for the
 * secret again. Silently signing in with an absent secret is what must not happen, and neither
 * does.
 */
@Converter(autoApply = false)
public class DirectoryConnectorSecretConverter implements AttributeConverter<String, String> {

  private static final Logger log =
      LoggerFactory.getLogger(DirectoryConnectorSecretConverter.class);

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
    try {
      return credentialsEncryptor.decrypt(dbData);
    } catch (CredentialsEncryptionKeyMissingException e) {
      log.warn(
          "Das Geheimnis eines Verzeichniszugangs konnte beim Lesen nicht entschluesselt werden -"
              + " der Zugang wird als unbrauchbar behandelt, bis er neu hinterlegt wird. Ursache:"
              + " {}",
          e.getMessage());
      return null;
    }
  }
}
