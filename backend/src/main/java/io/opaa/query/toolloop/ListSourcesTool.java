package io.opaa.query.toolloop;

import io.opaa.api.types.DocumentStatus;
import io.opaa.knowledge.DocumentRepository;
import io.opaa.knowledge.DocumentRepository.LibraryDocumentCount;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import io.opaa.query.answer.DocumentBlock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Lists what the run may read (ADR-0070): the libraries of its scope with their number of indexed
 * documents, or the indexed documents of one of them by name. The libraries come from the run's
 * scope, never from an argument; a library id outside it is ignored and the step says so, without
 * the id. Names are data inside the run's block, never instructions, and no line is citable.
 */
@Component
class ListSourcesTool implements AgentTool {

  /** The most documents one call lists. */
  static final int MAX_DOCUMENTS = 50;

  static final int MAX_FILTER_CHARS = 200;

  static final String NO_LIBRARIES = "Dieser Agent hat keine lesbaren Bibliotheken.";

  private static final String SCHEMA =
      """
      {
        "type": "object",
        "properties": {
          "bibliothek": {
            "type": "string",
            "description": "Optional: die Kennung einer Bibliothek; dann werden ihre Dokumente aufgelistet."
          },
          "name_enthaelt": {
            "type": "string",
            "description": "Optional, mit bibliothek: nur Dokumente, deren Dateiname dies enthält."
          }
        }
      }
      """;

  private final KnowledgeLibraryRepository libraryRepository;
  private final DocumentRepository documentRepository;

  ListSourcesTool(
      KnowledgeLibraryRepository libraryRepository, DocumentRepository documentRepository) {
    this.libraryRepository = libraryRepository;
    this.documentRepository = documentRepository;
  }

  @Override
  public String name() {
    return ToolNames.LIST_SOURCES;
  }

  @Override
  public ToolRightsClass rightsClass() {
    return ToolRightsClass.READING;
  }

  @Override
  public String description() {
    return "Listet die Bibliotheken auf, die dieser Agent lesen darf, mit Kennung und Zahl der"
        + " Dokumente; mit einer Bibliothekskennung deren Dokumente nach Dateiname. Liefert keine"
        + " Inhalte - für Inhalte search_knowledge aufrufen.";
  }

  @Override
  public String inputSchema() {
    return SCHEMA;
  }

  @Override
  public ToolCallOutcome call(JsonNode arguments, AgentRun run) {
    Set<UUID> scope = run.searchScope();
    String requested = text(arguments.get("bibliothek"));
    String nameFilter = text(arguments.get("name_enthaelt"));
    if (nameFilter != null && nameFilter.length() > MAX_FILTER_CHARS) {
      return ToolCallOutcome.refused(
          ToolCallResult.INVALID_ARGUMENTS,
          "Der Namensfilter ist zu lang (höchstens " + MAX_FILTER_CHARS + " Zeichen).");
    }
    if (scope.isEmpty()) {
      return new ToolCallOutcome(NO_LIBRARIES, ToolCallResult.EMPTY, null, 0, List.of());
    }
    if (requested == null) {
      return libraries(run, false);
    }
    UUID libraryId = uuid(requested);
    if (libraryId == null || !scope.contains(libraryId)) {
      return libraries(run, true);
    }
    return documents(run, libraryId, nameFilter);
  }

  private ToolCallOutcome libraries(AgentRun run, boolean ignored) {
    Map<UUID, Long> counts =
        documentRepository
            .countByLibraryAndStatus(run.searchScope(), DocumentStatus.INDEXED)
            .stream()
            .collect(
                Collectors.toMap(
                    LibraryDocumentCount::getLibraryId, LibraryDocumentCount::getDocumentCount));
    List<KnowledgeLibrary> libraries =
        libraryRepository.findAllById(run.searchScope()).stream()
            .sorted(Comparator.comparing(KnowledgeLibrary::getName, String.CASE_INSENSITIVE_ORDER))
            .toList();
    List<String> lines = new ArrayList<>();
    for (KnowledgeLibrary library : libraries) {
      lines.add(
          "Bibliothek: "
              + library.getName()
              + " | Kennung: "
              + library.getId()
              + " | Dokumente: "
              + counts.getOrDefault(library.getId(), 0L));
    }
    String body =
        (ignored ? SearchKnowledgeTool.IGNORED_NOTE : "")
            + DocumentBlock.renderLines(run.blockId(), lines);
    ToolCallResult result =
        ignored
            ? ToolCallResult.ARGUMENT_IGNORED
            : lines.isEmpty() ? ToolCallResult.EMPTY : ToolCallResult.SUCCESS;
    return new ToolCallOutcome(body, result, "Bibliotheken", lines.size(), List.of());
  }

  private ToolCallOutcome documents(AgentRun run, UUID libraryId, String nameFilter) {
    String library =
        libraryRepository.findById(libraryId).map(KnowledgeLibrary::getName).orElse(null);
    Page<io.opaa.knowledge.Document> page =
        documentRepository.searchByStatus(
            libraryId,
            escape(nameFilter == null ? "" : nameFilter),
            List.of(DocumentStatus.INDEXED),
            PageRequest.of(0, MAX_DOCUMENTS, Sort.by("fileName").ascending()));
    List<String> lines =
        page.getContent().stream().map(document -> "Dokument: " + document.getFileName()).toList();
    String more =
        page.getTotalElements() > lines.size()
            ? "\n\nHinweis: "
                + page.getTotalElements()
                + " Dokumente passen, gezeigt sind die ersten "
                + lines.size()
                + "."
            : "";
    return new ToolCallOutcome(
        DocumentBlock.renderLines(run.blockId(), lines) + more,
        lines.isEmpty() ? ToolCallResult.EMPTY : ToolCallResult.SUCCESS,
        library,
        (int) Math.min(Integer.MAX_VALUE, page.getTotalElements()),
        List.of());
  }

  /** Backslash-escapes the LIKE metacharacters the repository's hand-written query expects. */
  static String escape(String value) {
    return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }

  private static UUID uuid(String value) {
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private static String text(JsonNode node) {
    if (node == null || !node.isString()) {
      return null;
    }
    String value = node.asString().strip();
    return value.isEmpty() ? null : value;
  }
}
