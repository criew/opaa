package io.opaa.library;

import io.opaa.security.CredentialsEncryptionKeyMissingException;
import io.opaa.security.CredentialsEncryptor;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JPA {@link AttributeConverter} for {@link KnowledgeLibrary#getSourceCredentials()} (#483,
 * ADR-0018 Entscheidung 4): encrypts on the way to the database, decrypts on the way back, entirely
 * transparent to callers of {@link KnowledgeLibrary#getSourceCredentials()} - {@code
 * UrlIndexingExecutor}/{@code RssFeedIndexingExecutor} keep reading the plain {@code
 * KnowledgeLibrary} entity and see the decrypted value, exactly as before this issue.
 *
 * <p>{@code autoApply = false} and applied explicitly via {@code @Convert} on the one field it is
 * meant for - {@code source_credentials} is the only column with this shape, and an auto-applied
 * converter would silently also catch any future {@code String} field that happens to share the
 * {@code String}/{@code String} type pair.
 *
 * <p><b>A plain Spring bean, constructor-injected:</b> Spring Boot's {@code
 * HibernateJpaVendorAdapter} wires {@code hibernate.resource.beans.container} to Spring's own bean
 * container, so Hibernate asks Spring for the {@code @Convert(converter = ...)}-named converter
 * instance rather than instantiating it itself - normal constructor injection of {@link
 * CredentialsEncryptor} here works.
 *
 * <p><b>Read failures fail soft, write failures fail hard (PR #504 review):</b> {@link
 * #convertToEntityAttribute} runs on every hydration of a {@link KnowledgeLibrary} row, including
 * every {@code GET /api/v1/libraries} list entry - a single row whose {@code sourceCredentials} can
 * no longer be decrypted (key lost, key rotated without re-encrypting existing rows, corrupted
 * value) must not take the whole read down with a {@code 503}, since that would also break the one
 * repair path ({@code PATCH /api/v1/libraries/{id}} setting new credentials) documented in {@code
 * docs/handbuch/deployment.md}, which loads the same row first. This converter therefore catches
 * {@link CredentialsEncryptionKeyMissingException} on read and treats the field as absent (logs a
 * warning with no library id - the converter is not given the owning entity's identity - and,
 * deliberately, no part of the stored or decrypted value). {@link #convertToDatabaseColumn} keeps
 * failing hard: silently dropping credentials on write would be far worse than a clear {@code 503}.
 */
@Converter(autoApply = false)
public class SourceCredentialsConverter implements AttributeConverter<String, String> {

  private static final Logger log = LoggerFactory.getLogger(SourceCredentialsConverter.class);

  private final CredentialsEncryptor credentialsEncryptor;

  public SourceCredentialsConverter(CredentialsEncryptor credentialsEncryptor) {
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
          "Zugangsdaten einer Wissensbibliothek konnten beim Lesen nicht entschluesselt werden -"
              + " Feld wird als nicht gesetzt behandelt. Ursache: {}",
          e.getMessage());
      return null;
    }
  }
}
