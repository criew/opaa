package io.opaa.api;

import io.opaa.api.dto.MetadataFilter;
import io.opaa.api.dto.MetadataFilterFormatFieldCondition;
import io.opaa.api.dto.MetadataFilterLibraryFieldCondition;
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
  private static final String LIBRARY_FIELDS = "libraryFields";
  private static final String FORMAT_FIELDS = "formatFields";
  private static final Set<String> KNOWN_FIELDS =
      Set.of(DOCUMENT_TYPES, DOCUMENT_DATE_FROM, DOCUMENT_DATE_TO, LIBRARY_FIELDS, FORMAT_FIELDS);
  private static final Set<String> KNOWN_FORMAT_FIELD_PROPERTIES = Set.of("fieldKey", "values");
  private static final Set<String> KNOWN_LIBRARY_FIELD_PROPERTIES =
      Set.of("libraryId", "fieldKey", "codes", "dateFrom", "dateTo", "value");

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
                + "' is not filterable; only documentTypes, documentDateFrom, documentDateTo,"
                + " libraryFields and formatFields are");
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
    JsonNode libraryFields = node.get(LIBRARY_FIELDS);
    if (libraryFields != null && !libraryFields.isNull()) {
      if (!libraryFields.isArray()) {
        return context.reportInputMismatch(
            MetadataFilter.class, "libraryFields must be an array of conditions");
      }
      List<MetadataFilterLibraryFieldCondition> conditions = new ArrayList<>();
      for (JsonNode entry : libraryFields) {
        if (!entry.isObject()) {
          return context.reportInputMismatch(
              MetadataFilter.class, "a library field condition must be a JSON object");
        }
        for (Map.Entry<String, JsonNode> property : entry.properties()) {
          if (!KNOWN_LIBRARY_FIELD_PROPERTIES.contains(property.getKey())) {
            return context.reportInputMismatch(
                MetadataFilter.class,
                "library field condition property '"
                    + property.getKey()
                    + "' is not filterable; only libraryId, fieldKey, codes, dateFrom, dateTo and"
                    + " value are");
          }
        }
        MetadataFilterLibraryFieldCondition condition =
            new MetadataFilterLibraryFieldCondition(
                toUuid(entry, context), text(entry, "fieldKey"));
        JsonNode codes = entry.get("codes");
        if (codes != null && !codes.isNull()) {
          if (!codes.isArray()) {
            return context.reportInputMismatch(
                MetadataFilter.class, "codes must be an array of value codes");
          }
          List<String> values = new ArrayList<>();
          for (JsonNode code : codes) {
            values.add(code.asString());
          }
          condition.setCodes(values);
        }
        condition.setDateFrom(text(entry, "dateFrom"));
        condition.setDateTo(text(entry, "dateTo"));
        condition.setValue(text(entry, "value"));
        conditions.add(condition);
      }
      filter.setLibraryFields(conditions);
    }
    JsonNode formatFields = node.get(FORMAT_FIELDS);
    if (formatFields != null && !formatFields.isNull()) {
      if (!formatFields.isArray()) {
        return context.reportInputMismatch(
            MetadataFilter.class, "formatFields must be an array of conditions");
      }
      List<MetadataFilterFormatFieldCondition> conditions = new ArrayList<>();
      for (JsonNode entry : formatFields) {
        if (!entry.isObject()) {
          return context.reportInputMismatch(
              MetadataFilter.class, "a format field condition must be a JSON object");
        }
        for (Map.Entry<String, JsonNode> property : entry.properties()) {
          if (!KNOWN_FORMAT_FIELD_PROPERTIES.contains(property.getKey())) {
            return context.reportInputMismatch(
                MetadataFilter.class,
                "format field condition property '"
                    + property.getKey()
                    + "' is not filterable; only fieldKey and values are");
          }
        }
        List<String> values = new ArrayList<>();
        JsonNode valueNode = entry.get("values");
        if (valueNode != null && !valueNode.isNull()) {
          if (!valueNode.isArray()) {
            return context.reportInputMismatch(
                MetadataFilter.class, "values must be an array of field values");
          }
          for (JsonNode value : valueNode) {
            values.add(value.asString());
          }
        }
        conditions.add(new MetadataFilterFormatFieldCondition(text(entry, "fieldKey"), values));
      }
      filter.setFormatFields(conditions);
    }
    return filter;
  }

  private static java.util.UUID toUuid(JsonNode node, DeserializationContext context) {
    String value = text(node, "libraryId");
    if (value == null) {
      return context.reportInputMismatch(
          MetadataFilter.class, "a library field condition needs its libraryId");
    }
    try {
      return java.util.UUID.fromString(value);
    } catch (IllegalArgumentException e) {
      return context.reportInputMismatch(MetadataFilter.class, "libraryId is not a UUID: " + value);
    }
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.get(field);
    return value == null || value.isNull() ? null : value.asString();
  }
}
