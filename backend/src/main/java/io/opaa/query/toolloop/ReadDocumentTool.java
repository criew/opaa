package io.opaa.query.toolloop;

import io.opaa.query.answer.DocumentBlock;
import io.opaa.retrieval.context.NeighborChunkLookup;
import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Reads further around a passage the run found (ADR-0070): its neighbouring sections or the whole
 * document, as far as {@link #MAX_READ_CHARS} and the run's remaining volume allow. Only a document
 * a step of this run already handed the model may be read - found in the run's scope under its
 * filter -, and the lookup is bounded by the run's scope again. Any other document id, from the
 * model or from a document's text, is ignored and the step says so, without the id. The sections
 * read become citable like those of a search.
 */
@Component
class ReadDocumentTool implements AgentTool {

  /** The sections before and after the named one that {@code umgebung} reads. */
  static final int NEIGHBOURHOOD = 2;

  /** The most characters of section text one call hands the model. */
  static final int MAX_READ_CHARS = 30_000;

  /** The most sections one call loads; a guard for documents of many tiny sections. */
  static final int MAX_SECTIONS = 400;

  static final String NOT_FOUND_IN_RUN =
      "Dieses Dokument hat in diesem Lauf keine Suche geliefert; es wurde nicht gelesen. Lies nur"
          + " Dokumente, deren Zitiermarke ein Werkzeug geliefert hat.";

  static final String NOTHING_LEFT = "Für weiteren Text ist in diesem Lauf kein Platz mehr.";

  static final String TRUNCATED_NOTE =
      "\n\nHinweis: Das Dokument geht weiter; gelesen wurde nur bis zum letzten Abschnitt oben.";

  private static final String SCHEMA =
      """
      {
        "type": "object",
        "properties": {
          "dokument": {
            "type": "string",
            "description": "Die Dokumentkennung aus einer Zitiermarke, der Teil vor dem #."
          },
          "abschnitt": {
            "type": "integer",
            "description": "Die Abschnittsnummer aus der Zitiermarke, der Teil nach dem #."
          },
          "umfang": {
            "type": "string",
            "enum": ["umgebung", "dokument"],
            "description": "umgebung: die Abschnitte davor und danach; dokument: das ganze Dokument ab dem Anfang."
          }
        },
        "required": ["dokument"]
      }
      """;

  private final NeighborChunkLookup lookup;

  ReadDocumentTool(NeighborChunkLookup lookup) {
    this.lookup = lookup;
  }

  @Override
  public String name() {
    return ToolNames.READ_DOCUMENT;
  }

  @Override
  public ToolRightsClass rightsClass() {
    return ToolRightsClass.READING;
  }

  @Override
  public String description() {
    return "Liest mehr aus einem Dokument, das eine Suche in diesem Lauf geliefert hat: die"
        + " Abschnitte rund um eine Fundstelle oder das ganze Dokument. Aufrufen, wenn eine"
        + " Fundstelle mitten im Zusammenhang abbricht. Die Kennung stammt aus der Zitiermarke.";
  }

  @Override
  public String inputSchema() {
    return SCHEMA;
  }

  @Override
  public ToolCallOutcome call(JsonNode arguments, AgentRun run) {
    String documentId = text(arguments.get("dokument"));
    if (documentId == null) {
      return ToolCallOutcome.refused(
          ToolCallResult.INVALID_ARGUMENTS, "Die Dokumentkennung fehlt.");
    }
    JsonNode sectionNode = arguments.get("abschnitt");
    if (sectionNode != null && !sectionNode.isNull() && !sectionNode.canConvertToInt()) {
      return ToolCallOutcome.refused(
          ToolCallResult.INVALID_ARGUMENTS, "Die Abschnittsnummer ist keine ganze Zahl.");
    }
    int section = sectionNode == null || sectionNode.isNull() ? 0 : sectionNode.asInt();
    String scope = text(arguments.get("umfang"));
    boolean whole = "dokument".equals(scope);
    if (scope != null && !whole && !"umgebung".equals(scope)) {
      return ToolCallOutcome.refused(
          ToolCallResult.INVALID_ARGUMENTS, "Der Umfang ist „umgebung“ oder „dokument“.");
    }
    if (section < 0) {
      return ToolCallOutcome.refused(
          ToolCallResult.INVALID_ARGUMENTS, "Die Abschnittsnummer ist negativ.");
    }
    if (!run.found(documentId)) {
      return ToolCallOutcome.refused(ToolCallResult.ARGUMENT_IGNORED, NOT_FOUND_IN_RUN);
    }
    long budget = Math.min(MAX_READ_CHARS, run.remainingChars());
    if (budget <= 0) {
      return ToolCallOutcome.refused(ToolCallResult.EMPTY, NOTHING_LEFT);
    }

    int from = whole ? 0 : Math.max(0, section - NEIGHBOURHOOD);
    int to = whole ? from + MAX_SECTIONS - 1 : section + NEIGHBOURHOOD;
    List<Document> loaded = lookup.findRange(documentId, from, to, run.searchScope());
    List<Document> passages = new ArrayList<>();
    long used = 0;
    boolean truncated = whole && loaded.size() >= MAX_SECTIONS;
    for (Document chunk : loaded) {
      // Counted as the model reads it, so the run's volume holds what the call adds to it.
      int length = DocumentBlock.render(run.blockId(), List.of(chunk)).length();
      if (used + length > budget) {
        truncated = true;
        break;
      }
      used += length;
      passages.add(chunk);
    }
    String detail = fileName(loaded);
    if (passages.isEmpty()) {
      return new ToolCallOutcome(
          loaded.isEmpty() ? SearchKnowledgeTool.NO_HITS : NOTHING_LEFT,
          ToolCallResult.EMPTY,
          detail,
          0,
          List.of());
    }
    String body = DocumentBlock.render(run.blockId(), passages) + (truncated ? TRUNCATED_NOTE : "");
    return new ToolCallOutcome(body, ToolCallResult.SUCCESS, detail, passages.size(), passages);
  }

  /** The document's name for the step the person sees, {@code null} when nothing was loaded. */
  private static String fileName(List<Document> loaded) {
    return loaded.stream()
        .map(chunk -> chunk.getMetadata().get("file_name"))
        .filter(name -> name != null)
        .map(Object::toString)
        .findFirst()
        .orElse(null);
  }

  private static String text(JsonNode node) {
    if (node == null || !node.isString()) {
      return null;
    }
    String value = node.asString().strip();
    return value.isEmpty() ? null : value;
  }
}
