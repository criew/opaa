package io.opaa.query.web;

import io.opaa.api.dto.LlmModelToolCheckCategory;
import io.opaa.api.dto.LlmModelToolCheckResponse;
import io.opaa.query.toolcheck.ToolCallingReport;
import io.opaa.query.toolcheck.ToolCheck;
import io.opaa.query.toolcheck.ToolCheckView;
import java.util.List;

/** Maps a {@link ToolCheckView} to its response; the report's runs stay in the Markdown report. */
final class ToolCheckResponseMapper {

  private ToolCheckResponseMapper() {}

  static LlmModelToolCheckResponse toResponse(ToolCheckView view) {
    ToolCheck check = view.check();
    ToolCallingReport report = view.report();
    LlmModelToolCheckResponse response =
        new LlmModelToolCheckResponse()
            .id(check.getId())
            .status(check.getStatus())
            .baseUrl(check.getBaseUrl())
            .modelIdentifier(check.getModelIdentifier())
            .currentCombination(view.currentCombination())
            .repetitions(check.getRepetitions())
            .startedAt(check.getStartedAt())
            .finishedAt(check.getFinishedAt())
            .completedRuns(check.getCompletedRuns())
            .totalRuns(check.getTotalRuns())
            .runId(check.getRunId())
            .failure(check.getFailure())
            .measurementRun(view.measurementRun());
    if (report == null) {
      return response.categories(List.of());
    }
    return response
        .categories(
            report.byCategory().entrySet().stream()
                .map(
                    entry ->
                        new LlmModelToolCheckCategory()
                            .category(entry.getKey())
                            .summary(entry.getValue().summary())
                            .runs(entry.getValue().runs())
                            .passed(entry.getValue().passed())
                            .failed(entry.getValue().failed())
                            .notReached(entry.getValue().notReached())
                            .followed(entry.getValue().followed())
                            .notFollowed(entry.getValue().notFollowed())
                            .truncated(entry.getValue().truncated())
                            .errors(entry.getValue().errors()))
                .toList())
        .floorMet(report.capabilityFloor().met())
        .floorStatement(report.capabilityFloor().statement())
        .medianModelCallMillis(report.durations().medianModelCallMillis())
        .totalSeconds(report.durations().totalSeconds());
  }
}
