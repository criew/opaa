package io.opaa.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.opaa.api.types.EvidenceLevel;
import java.io.InterruptedIOException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Encapsulates all Micrometer metrics of the chat question (ADR-0071): rate, errors and duration by
 * {@link Mode}, the time to the first visible text, the stages a question spends its time in, the
 * retrieval's hits, the answer's context and finish reason, and why a question ended without one.
 * Every tag takes a value of a fixed set; none names a person, chat, space or the question.
 */
public class QueryMetrics {

  /** How a question was answered; the {@code mode} tag. */
  public enum Mode {
    /** Answered from passages: the search scope or the chat's attachments held something. */
    RAG,
    /** Answered without a search: no space, a chat without knowledge, or an empty scope. */
    NO_SEARCH,
    /** Taken over by the tool loop. */
    AGENT;

    String tag() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  /** A section of one question's duration; the {@code stage} tag. The sections do not overlap. */
  public enum Stage {
    /** The sub-question decomposition, a call of the chat model. */
    DECOMPOSE,
    /** Embedding the search queries, a call of the embedding model per query. */
    EMBED_QUERY,
    /** The searches, fusion and selection of the pipeline, without the three stages beside it. */
    RETRIEVE,
    /** The rerank model's call and cut. */
    RERANK,
    /** The answer call of the chat model, streamed or not. */
    GENERATE,
    /** Writing the turn to the chat. */
    PERSIST;

    String tag() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  /** Why a question ended without an answer; the {@code cause} tag. */
  public enum AbortCause {
    /** The reader of the streamed answer went away. */
    CLIENT_CANCEL,
    /** A call the question depended on did not answer in time. */
    TIMEOUT,
    /** Any other failure. */
    ERROR;

    String tag() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  /** The finish reason of an answer call, reduced to a fixed set; the {@code reason} tag. */
  enum FinishReason {
    STOP,
    LENGTH,
    CONTENT_FILTER,
    TOOL,
    OTHER;

    String tag() {
      return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Reads the provider's finish reason in any case; {@code null} or unknown is {@link #OTHER}.
     */
    static FinishReason of(String providerReason) {
      if (providerReason == null) {
        return OTHER;
      }
      return switch (providerReason.trim().toLowerCase(Locale.ROOT)) {
        case "stop", "end_turn", "stop_sequence" -> STOP;
        case "length", "max_tokens" -> LENGTH;
        case "content_filter" -> CONTENT_FILTER;
        case "tool_calls", "tool_call", "function_call", "tool_use" -> TOOL;
        default -> OTHER;
      };
    }
  }

  private static final String STATUS_SUCCESS = "success";
  private static final String STATUS_ERROR = "error";
  private static final String STATUS_REJECTED = "rejected";

  /**
   * Every reason the sub-query decomposition fell back to the undecomposed question, on one counter
   * separated by {@code reason} so the shape of the failure is readable without three metric names.
   */
  private static final String DECOMPOSITION_FALLBACK = "opaa.query.decomposition.fallback";

  private final Map<Mode, Timer> durationTimers = new EnumMap<>(Mode.class);
  private final Map<Mode, Counter> successCounters = new EnumMap<>(Mode.class);
  private final Map<Mode, Counter> errorCounters = new EnumMap<>(Mode.class);
  private final Map<Mode, Counter> rejectedCounters = new EnumMap<>(Mode.class);
  private final Map<Mode, Timer> timeToFirstTokenTimers = new EnumMap<>(Mode.class);
  private final Map<Stage, Timer> stageTimers = new EnumMap<>(Stage.class);
  private final Map<FinishReason, Counter> finishReasonCounters = new EnumMap<>(FinishReason.class);
  private final Map<AbortCause, Counter> abortCounters = new EnumMap<>(AbortCause.class);
  private final DistributionSummary retrievalHits;
  private final Counter retrievalEmpty;
  private final DistributionSummary contextTokens;
  private final Counter contextTruncated;

  private final Counter degenerateDecompositionCounter;
  private final Counter prunedDecompositionCounter;
  private final Counter failedDecompositionCounter;
  private final Counter noSearchDecompositionCounter;

  /** One counter per Belegstand level; anonymous, it carries no person, chat or space. */
  private final Map<EvidenceLevel, Counter> evidenceCounters = new EnumMap<>(EvidenceLevel.class);

  public QueryMetrics(MeterRegistry meterRegistry) {
    for (Mode mode : Mode.values()) {
      durationTimers.put(
          mode,
          Timer.builder("opaa.query.duration")
              .tag("mode", mode.tag())
              .description("Query latency")
              .register(meterRegistry));
      successCounters.put(
          mode,
          Counter.builder("opaa.query.count")
              .tag("status", STATUS_SUCCESS)
              .tag("mode", mode.tag())
              .description("Queries by outcome")
              .register(meterRegistry));
      errorCounters.put(
          mode,
          Counter.builder("opaa.query.count")
              .tag("status", STATUS_ERROR)
              .tag("mode", mode.tag())
              .description("Queries by outcome")
              .register(meterRegistry));
      rejectedCounters.put(
          mode,
          Counter.builder("opaa.query.count")
              .tag("status", STATUS_REJECTED)
              .tag("mode", mode.tag())
              .description("Queries by outcome")
              .register(meterRegistry));
      timeToFirstTokenTimers.put(
          mode,
          Timer.builder("opaa.query.time_to_first_token")
              .tag("mode", mode.tag())
              .description("Time from the question to the first visible text of the answer")
              .register(meterRegistry));
    }
    for (Stage stage : Stage.values()) {
      stageTimers.put(
          stage,
          Timer.builder("opaa.query.stage.duration")
              .tag("stage", stage.tag())
              .description("Time one question spent in a stage")
              .register(meterRegistry));
    }
    for (FinishReason reason : FinishReason.values()) {
      finishReasonCounters.put(
          reason,
          Counter.builder("opaa.query.finish_reason")
              .tag("reason", reason.tag())
              .description("Answers by the finish reason the model reported")
              .register(meterRegistry));
    }
    for (AbortCause cause : AbortCause.values()) {
      abortCounters.put(
          cause,
          Counter.builder("opaa.query.aborted")
              .tag("cause", cause.tag())
              .description("Questions that ended without an answer")
              .register(meterRegistry));
    }
    this.retrievalHits =
        DistributionSummary.builder("opaa.retrieval.hits")
            .description("Passages the retrieval selected for one question")
            .register(meterRegistry);
    this.retrievalEmpty =
        Counter.builder("opaa.retrieval.empty")
            .description("Questions whose retrieval selected no passage")
            .register(meterRegistry);
    this.contextTokens =
        DistributionSummary.builder("opaa.query.context.tokens")
            .description("Input tokens of the answer call")
            .register(meterRegistry);
    this.contextTruncated =
        Counter.builder("opaa.query.context.truncated")
            .description("Answers whose chat history was longer than the conversation window")
            .register(meterRegistry);
    this.degenerateDecompositionCounter =
        Counter.builder(DECOMPOSITION_FALLBACK)
            .tag("reason", "degenerate")
            .description("Query decompositions in which no sub-query related to the question")
            .register(meterRegistry);
    this.prunedDecompositionCounter =
        Counter.builder(DECOMPOSITION_FALLBACK)
            .tag("reason", "pruned")
            .description("Query decompositions in which some sub-queries were unrelated")
            .register(meterRegistry);
    this.failedDecompositionCounter =
        Counter.builder(DECOMPOSITION_FALLBACK)
            .tag("reason", "failed")
            .description("Query decompositions that produced no usable output at all")
            .register(meterRegistry);
    this.noSearchDecompositionCounter =
        Counter.builder("opaa.query.decomposition.no-search")
            .description("Query decompositions that found nothing to search for")
            .register(meterRegistry);
    for (EvidenceLevel level : EvidenceLevel.values()) {
      evidenceCounters.put(
          level,
          Counter.builder("opaa.query.evidence")
              .tag("level", level.name().toLowerCase(Locale.ROOT))
              .description("Answers per Belegstand level")
              .register(meterRegistry));
    }
  }

  /** One question that ended, answered ({@code success}) or not, after {@code duration}. */
  public void recordQuery(Mode mode, boolean success, Duration duration) {
    durationTimers.get(mode).record(duration);
    (success ? successCounters : errorCounters).get(mode).increment();
  }

  /**
   * One question refused as a request error - invalid, not permitted, archived, or no chat model
   * set up. It counts as {@code status=rejected} only: no duration and no abort, so it does not
   * weigh on the chat SLO of {@code success / (success + error)}.
   */
  public void recordRejected(Mode mode) {
    rejectedCounters.get(mode).increment();
  }

  /**
   * The first visible text of a streamed answer arrived {@code sinceQuestion} after the question.
   */
  public void recordTimeToFirstToken(Mode mode, Duration sinceQuestion) {
    timeToFirstTokenTimers.get(mode).record(sinceQuestion);
  }

  /** One question spent {@code duration} in {@code stage}; a negative duration counts as zero. */
  public void recordStage(Stage stage, Duration duration) {
    stageTimers
        .get(stage)
        .record(duration.isNegative() ? 0 : duration.toNanos(), TimeUnit.NANOSECONDS);
  }

  /** The retrieval of one question selected {@code hits} passages; zero also counts as empty. */
  public void recordRetrieval(int hits) {
    retrievalHits.record(hits);
    if (hits == 0) {
      retrievalEmpty.increment();
    }
  }

  /** The input tokens of one answer call; a provider that reports none records nothing. */
  public void recordContextTokens(Integer inputTokens) {
    if (inputTokens != null && inputTokens > 0) {
      contextTokens.record(inputTokens);
    }
  }

  /** The chat history of one answered question was longer than the conversation window. */
  public void recordContextTruncated() {
    contextTruncated.increment();
  }

  /** The finish reason the model reported for one answer, reduced to the fixed set. */
  public void recordFinishReason(String providerReason) {
    finishReasonCounters.get(FinishReason.of(providerReason)).increment();
  }

  /** One question ended without an answer for {@code cause}. */
  public void recordAborted(AbortCause cause) {
    abortCounters.get(cause).increment();
  }

  /**
   * {@link AbortCause#TIMEOUT} when a timeout is anywhere in {@code failure}'s cause chain, {@link
   * AbortCause#ERROR} otherwise. A reader that went away is the caller's to recognize.
   */
  public static AbortCause abortCause(Throwable failure) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof TimeoutException
          || cause instanceof InterruptedIOException
          || cause instanceof HttpTimeoutException) {
        return AbortCause.TIMEOUT;
      }
      if (cause.getCause() == cause) {
        break;
      }
    }
    return AbortCause.ERROR;
  }

  /**
   * The model answered, but no sub-query related to the question - it replaced the question instead
   * of restating it. Separate from {@link #recordFailedDecomposition()} because a rising count here
   * points at the prompt or the chat model, not at availability.
   */
  public void recordDegenerateDecomposition() {
    degenerateDecompositionCounter.increment();
  }

  /**
   * Some but not all sub-queries were unrelated. Counted apart from {@link
   * #recordDegenerateDecomposition()} because it is the milder signal: the model understood the
   * question and drifted on part of it.
   */
  public void recordPrunedDecomposition() {
    prunedDecompositionCounter.increment();
  }

  /**
   * No usable output at all: no active chat model, timeout, call error, or an empty/unparsable
   * answer.
   */
  public void recordFailedDecomposition() {
    failedDecompositionCounter.increment();
  }

  /**
   * The model found nothing to search for in the message - an instruction on the answer form, a
   * statement about the asking person, thanks. The turn is still searched with the single-query
   * fallback, but only the answer is told why, so this has its own counter rather than a reason on
   * {@code opaa.query.decomposition.fallback}.
   */
  public void recordNoSearchDecomposition() {
    noSearchDecompositionCounter.increment();
  }

  /** Counts one answer's Belegstand; {@code null} - nothing assessed - counts nowhere. */
  public void recordEvidence(EvidenceLevel level) {
    if (level != null) {
      evidenceCounters.get(level).increment();
    }
  }
}
