package io.opaa.permission;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Maps {@link AssetType} onto the {@code varchar(30)} column {@code asset_type}. {@code autoApply =
 * false} and applied per field, for the same reason as {@code SourceCredentialsConverter}: an
 * auto-applied converter would also catch a future field that happens to share the type pair.
 */
@Converter(autoApply = false)
public class AssetTypeConverter implements AttributeConverter<AssetType, String> {

  @Override
  public String convertToDatabaseColumn(AssetType attribute) {
    return attribute == null ? null : attribute.value();
  }

  @Override
  public AssetType convertToEntityAttribute(String dbData) {
    return dbData == null ? null : AssetType.of(dbData);
  }
}
