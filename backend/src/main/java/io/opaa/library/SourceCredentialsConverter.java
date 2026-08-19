package io.opaa.library;

import io.opaa.security.CredentialsEncryptor;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

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
 * <p><b>Not a Spring bean, deliberately:</b> Hibernate instantiates an explicitly-named
 * {@code @Convert} converter itself, via its no-arg constructor, rather than asking Spring for the
 * bean this class's {@code @Convert(converter = ...)} names - this codebase's Hibernate/Spring Boot
 * combination does not wire {@code hibernate.resource.beans.container} to Spring's bean container,
 * so constructor injection here would never actually run (confirmed the hard way: every
 * {@code @DataJpaTest}-style slice that loads {@link KnowledgeLibrary}'s entity metadata still
 * needs a working converter instance, even one that {@code @Import}s nothing from {@code
 * io.opaa.security}). {@link #convertToDatabaseColumn}/{@link #convertToEntityAttribute} therefore
 * resolve the actual encryptor lazily, at call time, via {@link CredentialsEncryptor#current()} -
 * see that method's Javadoc for how it finds the real, Spring-configured instance in an actual
 * application context.
 */
@Converter(autoApply = false)
public class SourceCredentialsConverter implements AttributeConverter<String, String> {

  @Override
  public String convertToDatabaseColumn(String attribute) {
    return CredentialsEncryptor.current().encrypt(attribute);
  }

  @Override
  public String convertToEntityAttribute(String dbData) {
    return CredentialsEncryptor.current().decrypt(dbData);
  }
}
