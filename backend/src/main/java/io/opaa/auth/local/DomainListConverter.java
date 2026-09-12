package io.opaa.auth.local;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Maps {@link LocalAuthSettings#getSelfRegistrationAllowedDomains()} to its comma-separated {@code
 * varchar} column. Domains are stored lowercase and trimmed; an empty column is an empty list.
 */
@Converter
class DomainListConverter implements AttributeConverter<List<String>, String> {

  static final String SEPARATOR = ",";

  static List<String> normalize(List<String> domains) {
    if (domains == null) {
      return List.of();
    }
    return domains.stream()
        .filter(domain -> domain != null)
        .map(domain -> domain.trim().toLowerCase(Locale.ROOT))
        .filter(domain -> !domain.isEmpty())
        .distinct()
        .toList();
  }

  @Override
  public String convertToDatabaseColumn(List<String> domains) {
    return String.join(SEPARATOR, normalize(domains));
  }

  @Override
  public List<String> convertToEntityAttribute(String column) {
    if (column == null || column.isBlank()) {
      return List.of();
    }
    return normalize(Arrays.asList(column.split(SEPARATOR)));
  }
}
