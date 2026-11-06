package io.opaa.format.file.pdf;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The stored result of text recognition (OCR) of one scanned PDF, the input of {@link
 * PdfOcrDocumentFormat}: either the DoclingDocument docling-serve made of the whole file, or the
 * Markdown a vision model read from each page. Text and coordinates only - {@link #docling} drops
 * every embedded image - so it can be stored with the document and cut again without the service.
 *
 * @param connectionType how the recognising service was connected, {@code DOCLING_SERVE} or {@code
 *     OPENAI_VISION}
 * @param configurationId the recipient: names connection type, address and model of the role that
 *     delivered the text, never its key
 * @param document the DoclingDocument, {@code null} for a vision model
 * @param pages the Markdown per page in page order, {@code null} for docling-serve
 */
public record RecognizedPdf(
    String connectionType, String configurationId, JsonNode document, List<String> pages) {

  public static final String DOCLING_SERVE = "DOCLING_SERVE";
  public static final String OPENAI_VISION = "OPENAI_VISION";

  private static final int ENVELOPE_VERSION = 1;

  private static final JsonMapper JSON = JsonMapper.builder().build();

  public RecognizedPdf {
    Objects.requireNonNull(connectionType, "connectionType");
    Objects.requireNonNull(configurationId, "configurationId");
    if ((document == null) == (pages == null)) {
      throw new IllegalArgumentException("Exactly one of document and pages must be set");
    }
    pages = pages == null ? null : List.copyOf(pages);
  }

  /** A DoclingDocument as recognised, without any image it may embed. */
  public static RecognizedPdf docling(String configurationId, JsonNode document) {
    return new RecognizedPdf(DOCLING_SERVE, configurationId, withoutImages(document), null);
  }

  /** The Markdown a vision model read, one entry per page. */
  public static RecognizedPdf vision(String configurationId, List<String> pages) {
    return new RecognizedPdf(OPENAI_VISION, configurationId, null, pages);
  }

  /** The pages the recognition covered; {@code 0} when a DoclingDocument names none. */
  public int pageCount() {
    if (pages != null) {
      return pages.size();
    }
    return document.path("pages").size();
  }

  /** The stored form, read back by {@link #parse}. */
  public String toJson() {
    ObjectNode envelope = JSON.createObjectNode();
    envelope.put("version", ENVELOPE_VERSION);
    envelope.put("connectionType", connectionType);
    envelope.put("configurationId", configurationId);
    if (document != null) {
      envelope.set("document", document);
    } else {
      ArrayNode array = envelope.putArray("pages");
      pages.forEach(array::add);
    }
    return JSON.writeValueAsString(envelope);
  }

  /**
   * The recognition {@link #toJson} wrote.
   *
   * @throws IllegalArgumentException for anything else; the message never quotes the content
   */
  public static RecognizedPdf parse(String json) {
    JsonNode envelope;
    try {
      envelope = JSON.readTree(json);
    } catch (RuntimeException e) {
      throw new IllegalArgumentException("stored text recognition is not valid JSON");
    }
    if (envelope == null || !envelope.isObject()) {
      throw new IllegalArgumentException("stored text recognition is not a JSON object");
    }
    String connectionType = envelope.path("connectionType").asString("");
    String configurationId = envelope.path("configurationId").asString("");
    JsonNode document = envelope.get("document");
    if (document != null && document.isObject()) {
      return new RecognizedPdf(connectionType, configurationId, document, null);
    }
    JsonNode pageArray = envelope.get("pages");
    if (pageArray != null && pageArray.isArray()) {
      List<String> pages = new ArrayList<>(pageArray.size());
      pageArray.forEach(page -> pages.add(page.asString("")));
      return new RecognizedPdf(connectionType, configurationId, null, pages);
    }
    throw new IllegalArgumentException(
        "stored text recognition carries neither document nor pages");
  }

  /** A copy of {@code node} without any field named {@code image}, at any depth. */
  static JsonNode withoutImages(JsonNode node) {
    JsonNode copy = node.deepCopy();
    strip(copy);
    return copy;
  }

  private static void strip(JsonNode node) {
    if (node instanceof ObjectNode object) {
      object.remove("image");
      Iterator<Map.Entry<String, JsonNode>> fields = object.properties().iterator();
      while (fields.hasNext()) {
        strip(fields.next().getValue());
      }
    } else if (node instanceof ArrayNode array) {
      array.forEach(RecognizedPdf::strip);
    }
  }
}
