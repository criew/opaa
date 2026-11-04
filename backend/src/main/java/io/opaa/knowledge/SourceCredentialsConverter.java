package io.opaa.knowledge;

import io.opaa.security.CredentialsEncryptor;
import io.opaa.security.EncryptedCredentials;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * JPA {@link AttributeConverter} for the source credentials and the push secret of a {@link
 * KnowledgeLibrary} (ADR-0018 Entscheidung 4, ADR-0064): a new value is encrypted for its library
 * on the way to the database, a loaded one stays as stored until {@link
 * KnowledgeLibrary#getSourceCredentials()} decrypts it for the library's id - a converter never
 * learns the row it converts, so the binding travels in {@link EncryptedCredentials}.
 *
 * <p>{@code autoApply = false} and applied explicitly via {@code @Convert} on the fields it is
 * meant for.
 *
 * <p><b>A plain Spring bean, constructor-injected:</b> Spring Boot's {@code
 * HibernateJpaVendorAdapter} wires {@code hibernate.resource.beans.container} to Spring's own bean
 * container, so Hibernate asks Spring for the {@code @Convert(converter = ...)}-named converter
 * instance rather than instantiating it itself - normal constructor injection of {@link
 * CredentialsEncryptor} here works.
 *
 * <p><b>Read failures fail soft, write failures fail hard:</b> loading never decrypts, so a single
 * row whose credentials can no longer be decrypted (key lost, corrupted value) never takes a read
 * down - that would also break the one repair path ({@code PATCH /api/v1/libraries/{id}} setting
 * new credentials) documented in {@code docs/handbuch/betrieb/netz-und-sicherheit.md}, which loads
 * the same row first. The getter reads such a value as absent and logs it. Writing a new value
 * fails hard: silently dropping credentials on write would be far worse than a clear {@code 503}. A
 * loaded value is written back exactly as stored, and {@code KnowledgeLibrary} carries
 * {@code @DynamicUpdate}, so an unrelated change leaves the ciphertext readable once the key
 * returns.
 */
@Converter(autoApply = false)
public class SourceCredentialsConverter
    implements AttributeConverter<EncryptedCredentials, String> {

  private final CredentialsEncryptor credentialsEncryptor;

  public SourceCredentialsConverter(CredentialsEncryptor credentialsEncryptor) {
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
