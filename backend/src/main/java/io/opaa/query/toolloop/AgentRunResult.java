package io.opaa.query.toolloop;

import io.opaa.chat.AgentRunRecord.EndState;
import java.util.Locale;

/** The outcome class of a run: the value of the metric tag {@code result} (ADR-0071). */
enum AgentRunResult {
  COMPLETED,
  LIMIT_CALLS,
  LIMIT_DURATION,
  LIMIT_VOLUME,
  ASK_USER,
  MODEL_NOT_CAPABLE,
  ABORTED,
  ERROR;

  static AgentRunResult of(EndState endState) {
    return switch (endState) {
      case COMPLETED -> COMPLETED;
      case LIMIT_CALLS -> LIMIT_CALLS;
      case LIMIT_DURATION -> LIMIT_DURATION;
      case LIMIT_VOLUME -> LIMIT_VOLUME;
      case ASK_USER -> ASK_USER;
    };
  }

  String tagValue() {
    return name().toLowerCase(Locale.ROOT);
  }
}
