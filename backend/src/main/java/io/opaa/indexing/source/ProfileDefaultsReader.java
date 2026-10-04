package io.opaa.indexing.source;

import io.opaa.common.ValidationException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The default reading of a profile's connector defaults against the declared {@link
 * ProfileDefaults}: an undeclared key or a value of the wrong kind is a German 400.
 */
final class ProfileDefaultsReader {

  private ProfileDefaultsReader() {}

  static ConnectorData read(ProfileDefaults declared, ConnectorData requested) {
    if (requested == null) {
      return null;
    }
    Map<String, Object> read = new LinkedHashMap<>();
    for (Map.Entry<String, Object> entry : requested.asMap().entrySet()) {
      String name = entry.getKey();
      DefaultKey key =
          declared
              .key(name)
              .orElseThrow(
                  () ->
                      new ValidationException(
                          "connectorSettings: das Feld "
                              + name
                              + " kann ein Zugang nicht vorgeben"));
      Object value = valueOf(key, entry.getValue());
      if (value != null) {
        read.put(name, value);
      }
    }
    return read.isEmpty() ? null : ConnectorData.of(read);
  }

  private static Object valueOf(DefaultKey key, Object value) {
    if (value == null) {
      return null;
    }
    String field = "connectorSettings." + key.key();
    return switch (key.kind()) {
      case TEXT -> {
        if (!(value instanceof String text)) {
          throw new ValidationException(field + " muss ein Text sein");
        }
        yield text.isBlank() ? null : text.trim();
      }
      case BOOLEAN -> {
        if (!(value instanceof Boolean)) {
          throw new ValidationException(field + " muss ja oder nein sein");
        }
        yield value;
      }
      case CHOICE -> {
        if (!(value instanceof String choice) || !key.choices().contains(choice)) {
          throw new ValidationException(
              field + " muss einer der Werte " + String.join(", ", key.choices()) + " sein");
        }
        yield choice;
      }
    };
  }
}
