package io.opaa.api;

import io.opaa.api.dto.MetadataFilter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.jackson.JacksonComponent;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ValueDeserializer;

/**
 * Reads the generated {@code MetadataFilter} request object strictly (#1070): a JSON field it does
 * not declare - a keyword, a title, any field that is not a filterable core field - is a 400, not a
 * silently ignored key. The application's mapper tolerates unknown properties everywhere else,
 * which is right for every other request but would turn "only core fields filter, keywords never"
 * into a filter that quietly does nothing.
 */
@JacksonComponent
public class MetadataFilterDeserializer extends ValueDeserializer<MetadataFilter> {

  private static final String DOCUMENT_TYPES = "documentTypes";
  private static final String DOCUMENT_DATE_FROM = "documentDateFrom";
  private static final String DOCUMENT_DATE_TO = "documentDateTo";
  private static final Set<String> KNOWN_FIELDS =
      Set.of(DOCUMENT_TYPES, DOCUMENT_DATE_FROM, DOCUMENT_DATE_TO);

  @Override
  public MetadataFilter deserialize(JsonParser parser, DeserializationContext context) {
    JsonNode node = context.readTree(parser);
    if (!node.isObject()) {
      return context.reportInputMismatch(
          MetadataFilter.class, "metadata filter must be a JSON object");
    }
    for (Map.Entry<String, JsonNode> property : node.properties()) {
      if (!KNOWN_FIELDS.contains(property.getKey())) {
        return context.reportInputMismatch(
            MetadataFilter.class,
            "metadata filter field '"
                + property.getKey()
                + "' is not filterable; only documentTypes, documentDateFrom and documentDateTo"
                + " are");
      }
    }
    MetadataFilter filter = new MetadataFilter();
    JsonNode types = node.get(DOCUMENT_TYPES);
    if (types != null && !types.isNull()) {
      if (!types.isArray()) {
        return context.reportInputMismatch(
            MetadataFilter.class, "documentTypes must be an array of vocabulary codes");
      }
      List<String> codes = new ArrayList<>();
      for (JsonNode code : types) {
        codes.add(code.asString());
      }
      filter.setDocumentTypes(codes);
    }
    filter.setDocumentDateFrom(text(node, DOCUMENT_DATE_FROM));
    filter.setDocumentDateTo(text(node, DOCUMENT_DATE_TO));
    return filter;
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.get(field);
    return value == null || value.isNull() ? null : value.asString();
  }
}
