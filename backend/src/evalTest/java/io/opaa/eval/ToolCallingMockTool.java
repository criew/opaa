package io.opaa.eval;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The mock tool of the tool-calling measurement ({@code eval/corpus/tool-calling/mock-tool.json}):
 * a search with two parameters whose answers are fixed per case. The model only ever sees its
 * definition; {@link ToolCallingRunner} answers each call itself, so {@link #callback()} never
 * executes anything.
 *
 * @param inputSchema the JSON schema exactly as offered to the model
 * @param limitReachedNotice appended to the result of the last call the limit allows
 */
record ToolCallingMockTool(
    String name,
    String description,
    String inputSchema,
    String limitReachedNotice,
    String unknownToolResult,
    String invalidArgumentsResult,
    String systemPromptTemplate) {

  static final String QUERY = "query";
  static final String MAX_RESULTS = "max_results";
  static final Set<String> PARAMETERS = Set.of(QUERY, MAX_RESULTS);
  static final int MAX_RESULTS_MIN = 1;
  static final int MAX_RESULTS_MAX = 10;
  static final String CALL_LIMIT_PLACEHOLDER = "{call_limit}";

  private static final JsonMapper JSON = JsonMapper.builder().build();

  static Path corpusDir() {
    return RepoPaths.evalDir().resolve("corpus").resolve("tool-calling");
  }

  static ToolCallingMockTool load(Path corpusDir) throws IOException {
    JsonNode tool =
        JSON.readTree(
            Files.readString(corpusDir.resolve("mock-tool.json"), StandardCharsets.UTF_8));
    return new ToolCallingMockTool(
        tool.get("name").asString(),
        tool.get("description").asString(),
        JSON.writeValueAsString(tool.get("input_schema")),
        tool.get("limit_reached_notice").asString(),
        tool.get("unknown_tool_result").asString(),
        tool.get("invalid_arguments_result").asString(),
        Files.readString(corpusDir.resolve("system-prompt.txt"), StandardCharsets.UTF_8).strip());
  }

  /** The system prompt with the case's call limit filled in. */
  String systemPrompt(int callLimit) {
    return systemPromptTemplate.replace(CALL_LIMIT_PLACEHOLDER, Integer.toString(callLimit));
  }

  /** The definition offered to the model; calling it is a harness error. */
  ToolCallback callback() {
    ToolDefinition definition =
        ToolDefinition.builder()
            .name(name)
            .description(description)
            .inputSchema(inputSchema)
            .build();
    return new ToolCallback() {
      @Override
      public ToolDefinition getToolDefinition() {
        return definition;
      }

      @Override
      public String call(String toolInput) {
        throw new IllegalStateException(
            "The mock tool is answered by ToolCallingRunner and never executed by Spring AI");
      }
    };
  }

  /**
   * Checks {@code arguments} against the schema of {@code mock-tool.json}: a JSON object, {@code
   * query} a non-blank string, {@code max_results} absent or an integer within its bounds, no other
   * property.
   */
  static ArgumentCheck check(String arguments) {
    JsonNode node;
    try {
      node = JSON.readTree(arguments == null || arguments.isBlank() ? "{}" : arguments);
    } catch (JacksonException e) {
      return ArgumentCheck.invalid("arguments are not JSON", List.of());
    }
    if (node == null || !node.isObject()) {
      return ArgumentCheck.invalid("arguments are not a JSON object", List.of());
    }
    List<String> unknown = new ArrayList<>();
    for (Map.Entry<String, JsonNode> property : node.properties()) {
      if (!PARAMETERS.contains(property.getKey())) {
        unknown.add(property.getKey());
      }
    }
    JsonNode query = node.get(QUERY);
    String queryText = query != null && query.isString() ? query.asString() : null;
    JsonNode maxResults = node.get(MAX_RESULTS);
    Integer maxResultsValue =
        maxResults != null && maxResults.isIntegralNumber() ? maxResults.asInt() : null;
    if (!unknown.isEmpty()) {
      return new ArgumentCheck(
          false, "unknown parameters " + unknown, List.copyOf(unknown), queryText, maxResultsValue);
    }
    if (queryText == null || queryText.isBlank()) {
      return new ArgumentCheck(
          false, "query missing or blank", List.of(), queryText, maxResultsValue);
    }
    if (maxResults != null && !maxResults.isNull()) {
      if (maxResultsValue == null) {
        return new ArgumentCheck(
            false, "max_results is not an integer", List.of(), queryText, null);
      }
      if (maxResultsValue < MAX_RESULTS_MIN || maxResultsValue > MAX_RESULTS_MAX) {
        return new ArgumentCheck(
            false, "max_results out of range", List.of(), queryText, maxResultsValue);
      }
    }
    return new ArgumentCheck(true, null, List.of(), queryText, maxResultsValue);
  }

  /**
   * The outcome of {@link #check}: whether the arguments fit the schema, why not, which properties
   * the schema does not know, and the parsed values where readable.
   */
  record ArgumentCheck(
      boolean valid,
      String problem,
      List<String> unknownParameters,
      String query,
      Integer maxResults) {

    static ArgumentCheck invalid(String problem, List<String> unknownParameters) {
      return new ArgumentCheck(false, problem, unknownParameters, null, null);
    }
  }
}
