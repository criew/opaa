package io.opaa.eval;

import static org.assertj.core.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import tools.jackson.databind.json.JsonMapper;

/**
 * The multi-turn path's baseline verdict for one domain (issue #1553), shared by every domain's
 * test class exactly as {@link PipelineBaselineRegressionCheck} is — one implementation, not one
 * copy per domain.
 *
 * <p><b>Own verdict, own test class, own Gradle task.</b> The three paths are not interconvertible,
 * and this one additionally runs under a configuration the pipeline baseline was not drawn with
 * (decomposition on): it therefore never shares a Gradle task with {@code check…RetrievalBaseline}.
 *
 * <p><b>A missing multi-turn report is a failure here, by design</b> — same reasoning as the
 * pipeline path's: {@link ConversationHarnessSupport#runAndWriteGuarded} swallows a failure so the
 * two already-completed verdicts of the same harness run survive, not so a missing measurement goes
 * unnoticed. The Gradle task that runs this check switches the multi-turn step on itself, so "no
 * report" can only mean the step failed or reported itself as not executed.
 */
final class ConversationBaselineRegressionCheck {

  private ConversationBaselineRegressionCheck() {}

  static void run(EvalDomainConfig domain, Logger log) throws IOException {
    Path reportFile = ConversationHarnessSupport.reportFile(domain);
    Path markdownFile =
        Path.of(
            "build", "eval-reports", "conversation-baseline-comparison-" + domain.name() + ".md");

    if (!Files.exists(reportFile)) {
      fail(
          "No multi-turn report found at '"
              + reportFile.toAbsolutePath()
              + "'. Either the measurement run has not happened yet (run the domain's "
              + "'evaluate…Conversations' task first — this test only compares an existing report "
              + "against the baseline), or the multi-turn measurement path failed during that run "
              + "and only logged it (see ConversationHarnessSupport#runAndWriteGuarded), or it "
              + "reported itself as not executed (see ConversationRunPrerequisites). The other two "
              + "paths' verdicts are unaffected either way; look for the 'Mehrrunden-Messpfad "
              + "fehlgeschlagen' or 'Mehrrunden-Messpfad NICHT AUSGEFÜHRT' log entry of the run.");
    }

    ConversationEvaluationReport report =
        JsonMapper.builder()
            .build()
            .readValue(Files.readString(reportFile), ConversationEvaluationReport.class);
    Path baselineFile =
        RepoPaths.evalDir().resolve("baseline").resolve(domain.conversationBaselineFileName());
    ConversationBaselineComparator.requireBaselineComparable(report);

    ConversationBaseline baseline = ConversationBaseline.load(baselineFile);

    ConversationBaselineComparator.ComparisonResult result =
        ConversationBaselineComparator.compare(baseline, report);
    ConversationBaselineVerdict verdict = ConversationBaselineVerdict.of(result);

    String markdown =
        ConversationBaselineMarkdownWriter.render(
            result, verdict, domain.conversationBaselineFileName(), report.expectedStateAudit());
    ConversationBaselineMarkdownWriter.write(
        result,
        verdict,
        markdownFile,
        domain.conversationBaselineFileName(),
        report.expectedStateAudit());
    // Both outputs on purpose, matching the other two paths exactly: the logger reaches the test
    // report, System.out reaches Gradle's console via showStandardStreams.
    log.info(markdown);
    System.out.println(markdown);
    System.out.println("Delta-Tabelle geschrieben nach " + markdownFile.toAbsolutePath());

    if (verdict.failing()) {
      fail(verdict.headline() + " " + verdict.detail());
    }
  }
}
