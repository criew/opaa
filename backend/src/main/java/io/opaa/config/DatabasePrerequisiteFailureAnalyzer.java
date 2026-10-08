package io.opaa.config;

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/** Reports a {@link DatabasePrerequisiteException} as description and action, without a trace. */
public class DatabasePrerequisiteFailureAnalyzer
    extends AbstractFailureAnalyzer<DatabasePrerequisiteException> {

  @Override
  protected FailureAnalysis analyze(Throwable rootFailure, DatabasePrerequisiteException cause) {
    return new FailureAnalysis(cause.getMessage(), cause.getAction(), cause);
  }
}
