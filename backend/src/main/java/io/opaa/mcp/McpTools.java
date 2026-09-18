package io.opaa.mcp;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.opaa.auth.CurrentUser;
import io.opaa.chat.ChatSourceMetadataEntry;
import io.opaa.common.NotFoundException;
import io.opaa.common.TooManyRequestsException;
import io.opaa.search.FetchedPassage;
import io.opaa.search.PassageFetchService;
import io.opaa.search.SearchHit;
import io.opaa.search.SearchOutcome;
import io.opaa.search.SearchService;
import io.opaa.search.SearchableLibrary;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * The three tools of the MCP server, each one translation and nothing more: it reads the arguments,
 * calls the domain service of {@code io.opaa.search} and shapes the result. No permission decision
 * of its own - the effective view is resolved inside those services, per call, and the quota and
 * the channel alert are counted there as well.
 *
 * <p>Every result is delivered twice: as {@code structuredContent} for a client that reads it, and
 * as one JSON text block for the many that only read text.
 *
 * <p>A refusal from the domain - an exhausted quota, an unknown or foreign hit - becomes an error
 * <em>result</em>, not a transport error: a tool result is what the calling model can act on, and a
 * foreign hit answers exactly like an unknown one, so nothing confirms that it exists.
 */
@Component
class McpTools {

  private final SearchService searchService;
  private final PassageFetchService passageFetchService;
  private final JsonMapper jsonMapper;
  private final McpToolCatalog catalog;

  McpTools(
      SearchService searchService,
      PassageFetchService passageFetchService,
      JsonMapper jsonMapper,
      McpToolCatalog catalog) {
    this.searchService = searchService;
    this.passageFetchService = passageFetchService;
    this.jsonMapper = jsonMapper;
    this.catalog = catalog;
  }

  /**
   * The registered specifications. Their {@link Tool} definitions carry the schema and a neutral
   * description; the description a client sees comes from {@link McpToolCatalog} per request, which
   * is why {@code tools/list} has a handler of its own ({@link OpaaMcpHandler}).
   */
  List<SyncToolSpecification> specifications() {
    List<Tool> tools = catalog.toolsFor(List.of());
    List<SyncToolSpecification> specifications = new ArrayList<>();
    for (Tool tool : tools) {
      specifications.add(
          SyncToolSpecification.builder().tool(tool).callHandler(handlerFor(tool.name())).build());
    }
    return List.copyOf(specifications);
  }

  private java.util.function.BiFunction<McpTransportContext, CallToolRequest, CallToolResult>
      handlerFor(String name) {
    return switch (name) {
      case McpToolCatalog.SEARCH -> this::search;
      case McpToolCatalog.FETCH -> this::fetch;
      case McpToolCatalog.LIST_LIBRARIES -> this::listLibraries;
      default -> throw new IllegalStateException("No handler for MCP tool " + name);
    };
  }

  private CallToolResult search(McpTransportContext context, CallToolRequest request) {
    CurrentUser caller = McpRequestContext.callerOf(context);
    Map<String, Object> arguments = request.arguments() == null ? Map.of() : request.arguments();
    String query = text(arguments.get("query"));
    if (query == null || query.isBlank()) {
      return error("Die Suchfrage fehlt: „query“ ist erforderlich.");
    }
    List<UUID> libraryIds;
    try {
      libraryIds = uuids(arguments.get("libraryIds"));
    } catch (IllegalArgumentException e) {
      return error("„libraryIds“ enthält eine ungültige Kennung.");
    }
    Integer maxHits = integer(arguments.get("maxHits"));
    try {
      SearchOutcome outcome = searchService.search(caller, query, libraryIds, null, maxHits);
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("results", outcome.hits().stream().map(McpTools::asMap).toList());
      result.put(
          "searchedLibraries",
          outcome.searchedLibraries().stream()
              .map(
                  library -> {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("id", library.id().toString());
                    entry.put("name", library.name());
                    return entry;
                  })
              .toList());
      return result(result);
    } catch (TooManyRequestsException e) {
      return error(e.getMessage());
    }
  }

  private CallToolResult fetch(McpTransportContext context, CallToolRequest request) {
    CurrentUser caller = McpRequestContext.callerOf(context);
    Map<String, Object> arguments = request.arguments() == null ? Map.of() : request.arguments();
    String id = text(arguments.get("id"));
    if (id == null || id.isBlank()) {
      return error("Die Trefferkennung fehlt: „id“ ist erforderlich.");
    }
    boolean whole = Boolean.TRUE.equals(arguments.get("whole"));
    try {
      FetchedPassage passage = passageFetchService.fetch(caller, id, whole);
      return result(asMap(passage));
    } catch (NotFoundException | TooManyRequestsException e) {
      return error(e.getMessage());
    }
  }

  private CallToolResult listLibraries(McpTransportContext context, CallToolRequest request) {
    CurrentUser caller = McpRequestContext.callerOf(context);
    try {
      List<SearchableLibrary> libraries = searchService.libraries(caller);
      return result(Map.of("libraries", libraries.stream().map(McpTools::asMap).toList()));
    } catch (TooManyRequestsException e) {
      return error(e.getMessage());
    }
  }

  private static Map<String, Object> asMap(SearchHit hit) {
    Map<String, Object> entry = new LinkedHashMap<>();
    entry.put("id", hit.hitId());
    entry.put("title", hit.title());
    entry.put("text", hit.excerpt());
    entry.put("libraryId", hit.libraryId() == null ? null : hit.libraryId().toString());
    entry.put("library", hit.libraryName());
    entry.put("documentId", hit.documentId() == null ? null : hit.documentId().toString());
    entry.put("document", hit.fileName());
    entry.put("location", hit.location());
    entry.put("score", hit.relevanceScore());
    entry.put("metadata", metadata(hit.metadata()));
    return entry;
  }

  private static Map<String, Object> asMap(FetchedPassage passage) {
    Map<String, Object> entry = new LinkedHashMap<>();
    entry.put("id", passage.hitId());
    entry.put("title", passage.title());
    entry.put("text", passage.text());
    entry.put("libraryId", passage.libraryId() == null ? null : passage.libraryId().toString());
    entry.put("library", passage.libraryName());
    entry.put("documentId", passage.documentId() == null ? null : passage.documentId().toString());
    entry.put("document", passage.fileName());
    entry.put("location", passage.location());
    entry.put("headingPath", passage.headingPath());
    entry.put("whole", passage.whole());
    entry.put("truncated", passage.truncated());
    entry.put("metadata", metadata(passage.metadata()));
    return entry;
  }

  private static Map<String, Object> asMap(SearchableLibrary library) {
    Map<String, Object> entry = new LinkedHashMap<>();
    entry.put("id", library.id().toString());
    entry.put("name", library.name());
    entry.put("description", library.description());
    return entry;
  }

  private static List<Map<String, Object>> metadata(List<ChatSourceMetadataEntry> entries) {
    if (entries == null || entries.isEmpty()) {
      return List.of();
    }
    return entries.stream()
        .map(
            entry -> {
              Map<String, Object> mapped = new LinkedHashMap<>();
              mapped.put("label", entry.label());
              mapped.put(
                  "value", entry.displayValue() == null ? entry.value() : entry.displayValue());
              return mapped;
            })
        .toList();
  }

  private CallToolResult result(Map<String, Object> structured) {
    return CallToolResult.builder()
        .content(List.of(TextContent.builder(jsonMapper.writeValueAsString(structured)).build()))
        .structuredContent(structured)
        .build();
  }

  private static CallToolResult error(String message) {
    return CallToolResult.builder()
        .content(List.of(TextContent.builder(message).build()))
        .isError(true)
        .build();
  }

  private static String text(Object value) {
    return value == null ? null : value.toString();
  }

  private static Integer integer(Object value) {
    if (value instanceof Number number) {
      return number.intValue();
    }
    if (value instanceof String string && !string.isBlank()) {
      try {
        return Integer.valueOf(string.strip());
      } catch (NumberFormatException e) {
        return null;
      }
    }
    return null;
  }

  private static List<UUID> uuids(Object value) {
    if (value == null) {
      return List.of();
    }
    List<?> raw = value instanceof List<?> list ? list : List.of(value);
    List<UUID> ids = new ArrayList<>();
    for (Object element : raw) {
      if (element != null && !element.toString().isBlank()) {
        ids.add(UUID.fromString(element.toString().strip()));
      }
    }
    return List.copyOf(ids);
  }
}
