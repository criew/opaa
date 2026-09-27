package io.opaa.knowledge;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** Stores a {@link SourceType} as its key in the {@code source_type} columns. */
@Converter(autoApply = true)
public class SourceTypeConverter implements AttributeConverter<SourceType, String> {

  @Override
  public String convertToDatabaseColumn(SourceType attribute) {
    return attribute == null ? null : attribute.key();
  }

  @Override
  public SourceType convertToEntityAttribute(String dbData) {
    return dbData == null ? null : SourceType.of(dbData);
  }
}
