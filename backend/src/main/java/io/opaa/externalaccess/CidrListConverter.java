package io.opaa.externalaccess;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.List;

/**
 * Maps {@link ExternalAccessSettings#getAllowedCidrs()} to its comma-separated {@code varchar}
 * column. An empty column is an empty list - and an empty list means "no address reaches the
 * channel", never "every address".
 */
@Converter
class CidrListConverter implements AttributeConverter<List<String>, String> {

  @Override
  public String convertToDatabaseColumn(List<String> cidrs) {
    return String.join(CidrList.SEPARATOR, CidrList.normalize(cidrs));
  }

  @Override
  public List<String> convertToEntityAttribute(String column) {
    return CidrList.parseColumn(column);
  }
}
