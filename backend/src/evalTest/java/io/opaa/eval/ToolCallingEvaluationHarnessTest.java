package io.opaa.eval;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.llm.ChatResponses;
import io.opaa.query.toolcheck.ToolCallingCaseBase;
import io.opaa.query.toolcheck.ToolCallingMeasurement;
import io.opaa.query.toolcheck.ToolCallingReport;
import io.opaa.query.toolcheck.ToolCallingTool;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * Measures the tool-calling case base ({@code eval/corpus/tool-calling/SOURCE.md}) against one
 * OpenAI-compatible endpoint, offering the mock tool or, with {@code
 * -Dopaa.eval.toolCalling.tool=production}, the agent loop's {@code search_knowledge}, and writes {@code build/eval-reports/tool-calling.{json,md}}.
 *
 * <p>The model address is mandatory: {@code -Dopaa.eval.chatBaseUrl} and {@code
 * -Dopaa.eval.chatModel}, the key only from {@code OPAA_EVAL_CHAT_API_KEY}. Without them the run
 * fails instead of being skipped, so no earlier report passes for a fresh one. The descriptive
 * {@code opaa.eval.toolCalling.*} properties name the combination in the report; they change
 * nothing about the requests except {@code streaming}, {@code maxTokens}, {@code timeoutSeconds}
 * and {@code repetitions}.
 *
 * <p>Needs no Docker and no database. The run measures and reports; it fails only on a broken
 * precondition (manifest, unreachable endpoint), never on a result. Run with {@code ./gradlew
 * evaluateToolCalling}; not part of build or check.
 */
class ToolCallingEvaluationHarnessTest {

  private static final Logger log = LoggerFactory.getLogger(ToolCallingEvaluationHarnessTest.class);

  private static final String PREFIX = "opaa.eval.toolCalling.";
  static final int DEFAULT_REPETITIONS = 3;
  // Mirrors StepRules.DEFAULT_MAX_TOKENS (package-private in io.opaa.installation): the length
  // limit a new model entry gets, so a measurement runs with what an installation actually uses.
  static final int DEFAULT_MAX_TOKENS = 8000;
  static final int DEFAULT_TIMEOUT_SECONDS = 300;

  @Test
  void measuresTheCaseBaseAgainstTheConfiguredModel() throws IOException {
    EvalChatModel.External external =
        EvalChatModel.external()
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "evaluateToolCalling measures the model at -Dopaa.eval.chatBaseUrl with"
                            + " -Dopaa.eval.chatModel; both are missing. There is no pinned"
                            + " fallback model for this measurement."));
    ToolCallingCaseBase caseBase = ToolCallingCaseBase.load();
    ToolCallingTool tool = ToolCallingTool.of(toolDefinition(), caseBase);

    int repetitions = intProperty("repetitions", DEFAULT_REPETITIONS);
    int maxTokens = intProperty("maxTokens", DEFAULT_MAX_TOKENS);
    Duration timeout = Duration.ofSeconds(intProperty("timeoutSeconds", DEFAULT_TIMEOUT_SECONDS));
    boolean streaming = Boolean.getBoolean(PREFIX + "streaming");
    ChatModel model =
        ToolCallingChatModel.openAiCompatible(
            external.baseUrl(),
            external.model(),
            external.apiKey(),
            external.temperature(),
            maxTokens,
            timeout);
    String answeredModel = requireUsable(model, external.model());

    Instant start = Instant.now();
    List<ToolCallingReport.CaseRun> runs =
        ToolCallingMeasurement.run(
            caseBase,
            tool,
            model,
            repetitions,
            streaming,
            timeout,
            (done, total, run) ->
                log.info(
                    "{} ({}), Wdh. {}: {} - {}",
                    run.caseId(),
                    run.category(),
                    run.repetition(),
                    run.outcome(),
                    run.reason()));

    ToolCallingReport report =
        ToolCallingReport.of(
            start,
            new ToolCallingReport.Combination(
                external.model(),
                answeredModel,
                stated("quantization"),
                stated("runtime"),
                stated("runtimeVersion"),
                stated("hardware"),
                stated("contextLength"),
                EvalChatModel.endpointWithoutSecrets(external.baseUrl()),
                external.temperature().toPlainString(),
                maxTokens,
                streaming),
            new ToolCallingReport.CaseBase(
                ToolCallingCaseBase.SOURCE_PATH,
                caseBase.caseBaseVersion(),
                caseBase.manifestSha256(),
                caseBase.cases().size(),
                repetitions,
                tool.definition().name()),
            Duration.between(start, Instant.now()).toMillis() / 1000.0,
            runs);
    Path json = EvalReportDirectory.resolve("tool-calling.json");
    Path markdown = EvalReportDirectory.resolve("tool-calling.md");
    report.writeJson(json);
    report.writeMarkdown(markdown);
    report
        .byCategory()
        .forEach((category, result) -> log.info("{}: {}", category, result.summary()));
    log.info("{} Report: {}", report.capabilityFloor().statement(), markdown.toAbsolutePath());
  }

  /** Fails before any case runs when the endpoint does not answer; returns the answering model. */
  private static String requireUsable(ChatModel model, String ordered) {
    ChatResponse response = model.call(new Prompt("Antworte mit dem Wort: bereit"));
    String text = ChatResponses.text(response);
    if (text == null || text.isBlank()) {
      throw new IllegalStateException(
          "The model '" + ordered + "' returned no usable answer; check address and key.");
    }
    return ChatResponses.model(response);
  }

  /** {@code -Dopaa.eval.toolCalling.tool}: {@code mock} (default) or {@code production}. */
  static ToolCallingTool.Definition toolDefinition() {
    String value = System.getProperty(PREFIX + "tool");
    if (value == null || value.isBlank() || value.strip().equalsIgnoreCase("mock")) {
      return ToolCallingTool.Definition.MOCK;
    }
    if (value.strip().equalsIgnoreCase("production")) {
      return ToolCallingTool.Definition.PRODUCTION;
    }
    throw new IllegalArgumentException(PREFIX + "tool must be mock or production, got: " + value);
  }

  private static String stated(String key) {
    String value = System.getProperty(PREFIX + key);
    return value == null || value.isBlank() ? ToolCallingReport.NOT_STATED : value.strip();
  }

  static int intProperty(String key, int defaultValue) {
    String value = System.getProperty(PREFIX + key);
    if (value == null || value.isBlank()) {
      return defaultValue;
    }
    int parsed;
    try {
      parsed = Integer.parseInt(value.strip());
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(
          PREFIX + key + " must be a whole number, got: " + value, e);
    }
    if (parsed < 1) {
      throw new IllegalArgumentException(PREFIX + key + " must be at least 1, got: " + value);
    }
    return parsed;
  }
}
