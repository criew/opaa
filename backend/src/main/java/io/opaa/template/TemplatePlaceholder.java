package io.opaa.template;

import java.util.List;

/**
 * One value the data for a document template must provide.
 *
 * @param fields for a {@link PlaceholderKind#LIST} the fields of each entry, for a {@link
 *     PlaceholderKind#FIELD} with parts ({@code {{ firma.name }}}) those parts; else empty.
 */
public record TemplatePlaceholder(String name, PlaceholderKind kind, List<String> fields) {

  public TemplatePlaceholder {
    fields = List.copyOf(fields);
  }
}
