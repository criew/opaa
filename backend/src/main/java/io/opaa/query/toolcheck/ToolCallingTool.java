package io.opaa.query.toolcheck;

import io.opaa.query.toolloop.SearchKnowledgeDefinition;
import io.opaa.query.toolloop.ToolNames;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The tool a tool-calling measurement offers the model: either the mock search of the case base
 * ({@code mock-tool.json}, two parameters) or the definition of the agent loop's own {@code
 * search_knowledge}. The model only ever sees the definition; {@link ToolCallingRunner} answers
 * each call from the case, so {@link #callback()} never executes anything.
 *
 * @param inputSchema the JSON schema exactly as offered to the model
 * @param queryParameter the argument carrying the search text
 * @param maxResultsParameter the argument limiting the hits, {@code null} when the tool has none
 * @param limitReachedNotice appended to the result of the last call the limit allows
 */
public record ToolCallingTool(
    Definition definition,
    String name,
    String description,
    String inputSchema,
    String queryParameter,
    String maxResultsParameter,
    String limitReachedNotice,
    String unknownToolResult,
    String invalidArgumentsResult,
    String systemPromptTemplate) {

  /** Which tool definition the model is offered. */
  public enum Definition {
    /** The mock search of the case base, two parameters. */
    MOCK,
    /** The agent loop's own {@code search_knowledge}. */
    PRODUCTION
  }

  static final String CALL_LIMIT_PLACEHOLDER = "{call_limit}";

  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** The mock search of the case base. */
  public static ToolCallingTool mock(ToolCallingCaseBase caseBase) {
    JsonNode tool = JSON.readTree(caseBase.mockToolJson());
    return new ToolCallingTool(
        Definition.MOCK,
        tool.get("name").asString(),
        tool.get("description").asString(),
        JSON.writeValueAsString(tool.get("input_schema")),
        "query",
        "max_results",
        tool.get("limit_reached_notice").asString(),
        tool.get("unknown_tool_result").asString(),
        tool.get("invalid_arguments_result").asString(),
        caseBase.systemPromptTemplate());
  }

  /**
   * The agent loop's {@code search_knowledge}: its description and schema, with the case base's
   * system prompt and notices. It has no hit limit, so {@code valid_arguments} skips the expected
   * {@code max_results} of a case.
   */
  public static ToolCallingTool production(ToolCallingCaseBase caseBase) {
    ToolCallingTool mock = mock(caseBase);
    return new ToolCallingTool(
        Definition.PRODUCTION,
        ToolNames.SEARCH_KNOWLEDGE,
        SearchKnowledgeDefinition.DESCRIPTION,
        JSON.writeValueAsString(JSON.readTree(SearchKnowledgeDefinition.SCHEMA)),
        SearchKnowledgeDefinition.QUESTION,
        null,
        mock.limitReachedNotice(),
        mock.unknownToolResult(),
        "Fehler: Die Argumente passen nicht zum Schema von search_knowledge. Pflicht ist "
            + SearchKnowledgeDefinition.QUESTION
            + " (Text); die übrigen Angaben sind optional.",
        caseBase.systemPromptTemplate());
  }

  public static ToolCallingTool of(Definition definition, ToolCallingCaseBase base) {
    return definition == Definition.PRODUCTION ? production(base) : mock(base);
  }

  /** The system prompt with the case's call limit filled in. */
  String systemPrompt(int callLimit) {
    return systemPromptTemplate.replace(CALL_LIMIT_PLACEHOLDER, Integer.toString(callLimit));
  }

  /** The parameter names the schema declares. */
  Set<String> parameters() {
    Set<String> names = new TreeSet<>();
    JsonNode properties = JSON.readTree(inputSchema).get("properties");
    if (properties != null) {
      properties.propertyNames().forEach(names::add);
    }
    return names;
  }

  /** The definition offered to the model; calling it is an error of the measurement. */
  ToolCallback callback() {
    ToolDefinition toolDefinition =
        ToolDefinition.builder()
            .name(name)
            .description(description)
            .inputSchema(inputSchema)
            .build();
    return new ToolCallback() {
      @Override
      public ToolDefinition getToolDefinition() {
        return toolDefinition;
      }

      @Override
      public String call(String toolInput) {
        throw new IllegalStateException(
            "The tool is answered by ToolCallingRunner and never executed by Spring AI");
      }
    };
  }

  /**
   * Checks {@code arguments} against this tool's schema, as far as the measured schemas use it: a
   * JSON object; the query argument a non-blank string; every declared property of its type
   * (string, integer within {@code minimum}/{@code maximum}, array of strings); every required
   * property present; an undeclared property makes the arguments invalid only where {@code
   * additionalProperties} is {@code false}, but is always reported as invented.
   */
  ArgumentCheck check(String arguments) {
    JsonNode node;
    try {
      node = JSON.readTree(arguments == null || arguments.isBlank() ? "{}" : arguments);
    } catch (JacksonException e) {
      return ArgumentCheck.invalid("arguments are not JSON");
    }
    if (node == null || !node.isObject()) {
      return ArgumentCheck.invalid("arguments are not a JSON object");
    }
    JsonNode schema = JSON.readTree(inputSchema);
    JsonNode properties = schema.get("properties");
    boolean closed =
        schema.has("additionalProperties") && !schema.get("additionalProperties").asBoolean();
    List<String> unknown = new ArrayList<>();
    String problem = null;
    for (Map.Entry<String, JsonNode> property : node.properties()) {
      JsonNode declared = properties == null ? null : properties.get(property.getKey());
      if (declared == null) {
        unknown.add(property.getKey());
      } else if (problem == null) {
        problem = typeProblem(property.getKey(), declared, property.getValue());
      }
    }
    JsonNode query = node.get(queryParameter);
    String queryText = query != null && query.isString() ? query.asString() : null;
    JsonNode maxResults = maxResultsParameter == null ? null : node.get(maxResultsParameter);
    Integer maxResultsValue =
        maxResults != null && maxResults.isIntegralNumber() ? maxResults.asInt() : null;
    if (closed && !unknown.isEmpty()) {
      problem = "unknown parameters " + unknown;
    } else if (queryText == null || queryText.isBlank()) {
      problem = queryParameter + " missing or blank";
    } else if (problem == null && schema.get("required") != null) {
      for (JsonNode required : schema.get("required")) {
        if (!node.has(required.asString())) {
          problem = required.asString() + " missing";
          break;
        }
      }
    }
    return new ArgumentCheck(
        problem == null, problem, List.copyOf(unknown), queryText, maxResultsValue);
  }

  private static String typeProblem(String name, JsonNode declared, JsonNode value) {
    String type = declared.has("type") ? declared.get("type").asString() : "";
    switch (type) {
      case "string" -> {
        return value.isString() ? null : name + " is not a string";
      }
      case "integer" -> {
        if (!value.isIntegralNumber()) {
          return name + " is not an integer";
        }
        int number = value.asInt();
        if ((declared.has("minimum") && number < declared.get("minimum").asInt())
            || (declared.has("maximum") && number > declared.get("maximum").asInt())) {
          return name + " out of range";
        }
        return null;
      }
      case "array" -> {
        if (!value.isArray()) {
          return name + " is not an array";
        }
        for (JsonNode item : value) {
          if (!item.isString()) {
            return name + " holds a value that is not a string";
          }
        }
        return null;
      }
      default -> {
        return null;
      }
    }
  }

  /**
   * The outcome of {@link #check}: whether the arguments fit the schema, why not, which properties
   * the schema does not declare, and the parsed values where readable.
   */
  public record ArgumentCheck(
      boolean valid,
      String problem,
      List<String> unknownParameters,
      String query,
      Integer maxResults) {

    static ArgumentCheck invalid(String problem) {
      return new ArgumentCheck(false, problem, List.of(), null, null);
    }
  }
}
