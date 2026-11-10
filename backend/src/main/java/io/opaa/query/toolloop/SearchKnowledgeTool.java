package io.opaa.query.toolloop;

import io.opaa.common.ValidationException;
import io.opaa.metadata.MetadataFilter;
import io.opaa.metadata.MetadataFilterValidator;
import io.opaa.query.answer.DocumentBlock;
import io.opaa.retrieval.KnowledgeRetrieval;
import io.opaa.retrieval.RetrievalPipelineResult;
import io.opaa.retrieval.context.NeighborChunkExpansion;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Searches the run's scope for a question the model formulated (ADR-0070): a thin shell around the
 * retrieval of {@code POST /api/v1/query} - the same pipeline, permission filter and neighbour
 * expansion, only without a second sub-question decomposition. The scope is the run's; the model
 * may narrow it to some of its libraries, Dokumentarten or a date window, never widen it. A library
 * id outside the scope - from the model or from a document it read - is ignored and the step says
 * so, without the id.
 */
@Component
class SearchKnowledgeTool implements AgentTool {

  static final int MAX_QUESTION_CHARS = 2000;

  static final String NO_HITS = "Keine Treffer.";

  static final String IGNORED_NOTE =
      "Hinweis: Eine Angabe zur Eingrenzung lag außerhalb des Suchbereichs und wurde ignoriert.\n\n";

  private final KnowledgeRetrieval knowledgeRetrieval;
  private final NeighborChunkExpansion neighborChunkExpansion;
  private final MetadataFilterValidator metadataFilterValidator;

  SearchKnowledgeTool(
      KnowledgeRetrieval knowledgeRetrieval,
      NeighborChunkExpansion neighborChunkExpansion,
      MetadataFilterValidator metadataFilterValidator) {
    this.knowledgeRetrieval = knowledgeRetrieval;
    this.neighborChunkExpansion = neighborChunkExpansion;
    this.metadataFilterValidator = metadataFilterValidator;
  }

  @Override
  public String name() {
    return ToolNames.SEARCH_KNOWLEDGE;
  }

  @Override
  public ToolRightsClass rightsClass() {
    return ToolRightsClass.READING;
  }

  @Override
  public String description() {
    return SearchKnowledgeDefinition.DESCRIPTION;
  }

  @Override
  public String inputSchema() {
    return SearchKnowledgeDefinition.SCHEMA;
  }

  @Override
  public ToolCallOutcome call(JsonNode arguments, AgentRun run) {
    String question = text(arguments.get(SearchKnowledgeDefinition.QUESTION));
    if (question == null) {
      return ToolCallOutcome.refused(ToolCallResult.INVALID_ARGUMENTS, "Die Frage fehlt.");
    }
    if (question.length() > MAX_QUESTION_CHARS) {
      return ToolCallOutcome.refused(
          ToolCallResult.INVALID_ARGUMENTS,
          "Die Frage ist zu lang (höchstens " + MAX_QUESTION_CHARS + " Zeichen).");
    }
    Narrowing narrowing = narrowing(arguments, run);
    if (narrowing.invalid() != null) {
      return new ToolCallOutcome(
          narrowing.invalid(), ToolCallResult.INVALID_ARGUMENTS, question, null, List.of());
    }
    List<Document> passages = List.of();
    if (!narrowing.scope().isEmpty()) {
      RetrievalPipelineResult result =
          knowledgeRetrieval.retrieveAsGiven(question, narrowing.scope(), narrowing.filter());
      passages = neighborChunkExpansion.expand(result.chunks(), narrowing.scope());
    }
    String body = passages.isEmpty() ? NO_HITS : DocumentBlock.render(run.blockId(), passages);
    ToolCallResult outcome =
        narrowing.ignored()
            ? ToolCallResult.ARGUMENT_IGNORED
            : passages.isEmpty() ? ToolCallResult.EMPTY : ToolCallResult.SUCCESS;
    return new ToolCallOutcome(
        (narrowing.ignored() ? IGNORED_NOTE : "") + body,
        outcome,
        question,
        passages.size(),
        passages);
  }

  /**
   * The scope and filter of this call: the run's, narrowed by the arguments. A library outside the
   * run's scope and a narrowing that would leave no Dokumentart are ignored rather than allowed to
   * empty the search; an unknown Dokumentart code or a malformed date is invalid.
   */
  private Narrowing narrowing(JsonNode arguments, AgentRun run) {
    boolean ignored = false;
    Set<UUID> scope = run.searchScope();
    Set<UUID> requested = new LinkedHashSet<>();
    for (String value : texts(arguments.get("bibliotheken"))) {
      UUID id = uuid(value);
      if (id != null && scope.contains(id)) {
        requested.add(id);
      } else {
        ignored = true;
      }
    }
    if (!requested.isEmpty()) {
      scope = Set.copyOf(requested);
    }

    MetadataFilter base = run.metadataFilter();
    Set<String> documentTypes = base.documentTypes();
    Set<String> requestedTypes = new LinkedHashSet<>(texts(arguments.get("dokumentarten")));
    if (!requestedTypes.isEmpty()) {
      if (documentTypes.isEmpty()) {
        documentTypes = requestedTypes;
      } else {
        Set<String> both = new HashSet<>(documentTypes);
        both.retainAll(requestedTypes);
        if (both.isEmpty()) {
          ignored = true;
        } else {
          documentTypes = both;
        }
      }
    }
    LocalDate from;
    LocalDate to;
    try {
      from = later(base.documentDateFrom(), date(arguments.get("datum_von")));
      to = earlier(base.documentDateTo(), date(arguments.get("datum_bis")));
    } catch (DateTimeParseException e) {
      return Narrowing.invalid("Ein Datum hat nicht die Form JJJJ-MM-TT.");
    }
    try {
      MetadataFilter filter =
          new MetadataFilter(documentTypes, from, to, base.libraryFields(), base.formatFields());
      if (!filter.equals(base)) {
        filter = metadataFilterValidator.validate(filter, run.searchScope());
      }
      return new Narrowing(scope, filter, ignored, null);
    } catch (ValidationException e) {
      return Narrowing.invalid("Die Eingrenzung ist ungültig: " + e.getMessage());
    }
  }

  private static LocalDate later(LocalDate base, LocalDate requested) {
    return base == null || (requested != null && requested.isAfter(base)) ? requested : base;
  }

  private static LocalDate earlier(LocalDate base, LocalDate requested) {
    return base == null || (requested != null && requested.isBefore(base)) ? requested : base;
  }

  private static LocalDate date(JsonNode node) {
    String value = text(node);
    return value == null ? null : LocalDate.parse(value);
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

  private static List<String> texts(JsonNode node) {
    if (node == null || !node.isArray()) {
      return List.of();
    }
    return node.valueStream().map(SearchKnowledgeTool::text).filter(v -> v != null).toList();
  }

  private record Narrowing(
      Set<UUID> scope, MetadataFilter filter, boolean ignored, String invalid) {

    static Narrowing invalid(String reason) {
      return new Narrowing(Set.of(), MetadataFilter.NONE, false, reason);
    }
  }
}
