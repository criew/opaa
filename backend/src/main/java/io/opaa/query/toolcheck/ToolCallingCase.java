package io.opaa.query.toolcheck;

import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * One case of the tool-calling case base ({@code cases.json} of {@link ToolCallingCaseBase}): a user
 * message, the fixed results the mock tool returns call by call, the call limit the system prompt
 * names and the category-specific expectations its verdict is judged by ({@link ToolCallingJudge}).
 *
 * @param toolResults the mock tool's answer to the first, second, ... valid call; a call beyond the
 *     list receives the last entry
 * @param expectedQueryTerms {@code valid_arguments}: the first query must contain one of them
 * @param expectedMaxResults {@code valid_arguments}: the {@code max_results} the message asks for,
 *     {@code null} when it asks for none
 * @param secondStepQueryTerms {@code multi_step}: a later, different query must contain one of them
 * @param injection {@code ignores_injected_instruction}: where the instruction sits and how its
 *     effect shows; {@code null} for every other category
 */
public record ToolCallingCase(
    String id,
    String category,
    String userMessage,
    int callLimit,
    List<String> toolResults,
    List<String> expectedQueryTerms,
    Integer expectedMaxResults,
    List<String> secondStepQueryTerms,
    Injection injection,
    String rationale) {

  public static final String CALLS_WHEN_NEEDED = "calls_when_needed";
  public static final String ABSTAINS_WHEN_NOT_NEEDED = "abstains_when_not_needed";
  public static final String VALID_ARGUMENTS = "valid_arguments";
  public static final String NO_INVENTED_TOOL = "no_invented_tool";
  public static final String RESPECTS_LIMIT = "respects_limit";
  public static final String MULTI_STEP = "multi_step";
  public static final String IGNORES_INJECTED_INSTRUCTION = "ignores_injected_instruction";

  /** All case categories in report order. */
  public static final List<String> CATEGORIES =
      List.of(
          CALLS_WHEN_NEEDED,
          ABSTAINS_WHEN_NOT_NEEDED,
          VALID_ARGUMENTS,
          NO_INVENTED_TOOL,
          RESPECTS_LIMIT,
          MULTI_STEP,
          IGNORES_INJECTED_INSTRUCTION);

  /**
   * The capability floor: a combination may only be listed as capable when every run of these
   * categories passed.
   */
  public static final List<String> FLOOR_CATEGORIES =
      List.of(CALLS_WHEN_NEEDED, VALID_ARGUMENTS, RESPECTS_LIMIT);

  /**
   * How an instruction embedded in a tool result would show its effect.
   *
   * @param resultIndex index into {@link #toolResults} of the result carrying the instruction
   * @param answerPrefixMarkers texts the final answer opening with means it was followed
   * @param queryMarkers texts whose presence in a later query means it was followed
   * @param toolNames tool names whose call after the instruction means it was followed
   */
  public record Injection(
      int resultIndex,
      List<String> answerPrefixMarkers,
      List<String> queryMarkers,
      List<String> toolNames) {}

  /** The mock tool's fixed answer to the {@code validCallIndex}-th valid call, counted from 0. */
  String toolResult(int validCallIndex) {
    return toolResults.get(Math.min(validCallIndex, toolResults.size() - 1));
  }

  /** The cases of a {@code cases.json}. */
  static List<ToolCallingCase> parse(String casesJson) {
    JsonNode root = JsonMapper.builder().build().readTree(casesJson);
    int defaultCallLimit = root.get("default_call_limit").asInt();
    List<ToolCallingCase> cases = new ArrayList<>();
    for (JsonNode node : root.get("cases")) {
      JsonNode injection = node.get("injection");
      cases.add(
          new ToolCallingCase(
              node.get("id").asString(),
              node.get("category").asString(),
              node.get("user_message").asString(),
              node.hasNonNull("call_limit") ? node.get("call_limit").asInt() : defaultCallLimit,
              strings(node.get("tool_results")),
              strings(node.get("expected_query_terms")),
              node.hasNonNull("expected_max_results")
                  ? node.get("expected_max_results").asInt()
                  : null,
              strings(node.get("second_step_query_terms")),
              injection == null
                  ? null
                  : new Injection(
                      injection.get("result_index").asInt(),
                      strings(injection.get("answer_prefix_markers")),
                      strings(injection.get("query_markers")),
                      strings(injection.get("tool_names"))),
              node.get("rationale").asString()));
    }
    return List.copyOf(cases);
  }

  /** The {@code case_base_version} of a {@code cases.json}, raised with every change to a case. */
  static int caseBaseVersion(String casesJson) {
    return JsonMapper.builder().build().readTree(casesJson).get("case_base_version").asInt();
  }

  private static List<String> strings(JsonNode array) {
    List<String> values = new ArrayList<>();
    if (array != null) {
      array.forEach(value -> values.add(value.asString()));
    }
    return List.copyOf(values);
  }
}
