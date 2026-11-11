package io.opaa.query.toolcheck;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.chat.model.ChatModel;

/**
 * Runs every case of the case base {@code repetitions} times against one model and judges each run:
 * the one measurement both the evaluation harness and the model administration drive.
 */
public final class ToolCallingMeasurement {

  /** Told after each judged run, e.g. to record progress. */
  @FunctionalInterface
  public interface Progress {
    void runJudged(int done, int total, ToolCallingReport.CaseRun run);
  }

  private ToolCallingMeasurement() {}

  public static List<ToolCallingReport.CaseRun> run(
      ToolCallingCaseBase caseBase,
      ToolCallingTool tool,
      ChatModel model,
      int repetitions,
      boolean streaming,
      Duration streamTimeout,
      Progress progress) {
    ToolCallingRunner runner = new ToolCallingRunner(tool, streaming, streamTimeout);
    int total = caseBase.cases().size() * repetitions;
    List<ToolCallingReport.CaseRun> runs = new ArrayList<>();
    for (int repetition = 1; repetition <= repetitions; repetition++) {
      for (ToolCallingCase c : caseBase.cases()) {
        ToolCallingRunner.Transcript transcript = runner.run(c, model);
        ToolCallingJudge.Verdict verdict = ToolCallingJudge.judge(c, transcript, tool);
        ToolCallingReport.CaseRun run =
            new ToolCallingReport.CaseRun(
                c.id(),
                c.category(),
                repetition,
                verdict.outcome(),
                verdict.reason(),
                transcript.toolCalls().stream().map(t -> t.name() + " " + t.arguments()).toList(),
                transcript.finalAnswer(),
                transcript.cutOff(),
                transcript.limitReached(c.callLimit()),
                transcript.finishReasons(),
                transcript.modelCallMillis());
        runs.add(run);
        progress.runJudged(runs.size(), total, run);
      }
    }
    return List.copyOf(runs);
  }
}
