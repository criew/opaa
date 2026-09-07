package io.opaa.indexing.source;

import java.time.Duration;

/**
 * A run's own bound is reached - its request budget is spent or its {@code 429} waiting time is
 * used up. Not a failure of the source or the credentials: {@link IndexingRunTemplate} ends the run
 * in an orderly way as incomplete, and the next run continues. Unchecked so it passes every
 * per-item catch of an access layer untouched; an item catch that swallows {@code Exception} lets
 * it through via {@link IndexingRun#rethrowRunEnding}.
 *
 * <p>{@link #getMessage()} is the German head of the protocol note ("Anfragebudget von 500 Anfragen
 * erschöpft"); the frame appends the connector's continuation.
 */
public final class RequestBudgetExhaustedException extends RuntimeException {

  private final String insufficiencyPrefix;

  private RequestBudgetExhaustedException(String message, String insufficiencyPrefix) {
    super(message);
    this.insufficiencyPrefix = insufficiencyPrefix;
  }

  /** The run sent {@code budget} requests. */
  public static RequestBudgetExhaustedException requests(int budget) {
    return new RequestBudgetExhaustedException(
        "Anfragebudget von " + budget + " Anfragen erschöpft",
        "Das Anfragebudget von " + budget + " Anfragen reicht für diese Bibliothek nicht aus: ");
  }

  /** The run's waits on throttled answers would exceed {@code cap} in total. */
  public static RequestBudgetExhaustedException throttleWait(Duration cap) {
    String bound = german(cap);
    return new RequestBudgetExhaustedException(
        "Deckel der 429-Wartezeit von " + bound + " je Lauf erreicht",
        "Der Deckel der 429-Wartezeit von " + bound + " reicht für diese Bibliothek nicht aus: ");
  }

  /** Whole minutes as minutes, anything else as seconds. */
  private static String german(Duration duration) {
    long seconds = duration.toSeconds();
    if (seconds >= 60 && seconds % 60 == 0) {
      return (seconds / 60) + " Minuten";
    }
    return seconds + " Sekunden";
  }

  /**
   * The German {@code ERROR} note for a run that stored nothing before its bound was reached -
   * {@code advice} says what to change ("Budget anheben oder die Space-Auswahl aufteilen.").
   */
  public String insufficiencyNote(String advice) {
    return insufficiencyPrefix + advice;
  }
}
