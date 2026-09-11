package io.opaa.eval;

import io.opaa.query.QueryProperties;
import java.util.Optional;

/**
 * Decides whether a multi-turn run can measure what it would claim to measure (issue #1484,
 * docs/features/conversation-memory.md, "Messung": <i>„Ohne aktives Chat-Modell wird der Lauf als
 * ‚nicht ausgeführt' gemeldet"</i>).
 *
 * <p><b>Reports rather than throws, and never measures instead.</b> A missing chat model or a
 * switched-off decomposition does not make this path score worse - it makes it measure a different
 * thing: without decomposition a follow-up question reaches the search as the fallback's
 * concatenation, so every {@code anaphora_resolution} number would describe the fallback and be
 * booked against the committed baseline as a code change. The run is therefore reported as not
 * executed, in full, with the reason; the calling harness's other paths are untouched either way.
 */
public final class ConversationRunPrerequisites {

  private ConversationRunPrerequisites() {}

  /**
   * @param chatModel the systemwide active chat model of this run, or {@code null} if there is
   *     none.
   * @param caseCount how many curated cases the domain's multi-turn dataset holds.
   * @return the reason this run is not executed, or empty when it can be measured.
   */
  public static Optional<String> notExecutedReason(
      QueryProperties queryProperties, String chatModel, int caseCount) {
    if (!queryProperties.queryDecompositionEnabled()) {
      return Optional.of(
          "opaa.query.query-decomposition-enabled ist false. Der Mehrrunden-Pfad misst die "
              + "Auflösung von Bezügen durch die Teilfragen-Zerlegung; ohne sie erreicht eine "
              + "Rückfrage die Suche als Rückfall-Verkettung, und die Zahlen beschrieben den "
              + "Rückfall statt das Gesprächsgedächtnis. Lauf mit "
              + "-Dopaa.eval.queryDecomposition=true wiederholen.");
    }
    if (chatModel == null) {
      return Optional.of(
          "kein systemweit aktives Chat-Modell. Die Zerlegung schlüge je Anfrage fehl und fiele "
              + "stillschweigend auf die Einzelanfrage zurück — ein Lauf, der sich „mit Zerlegung\" "
              + "nennt und ohne misst (io.opaa.eval.EvalChatModel, Issue #1085).");
    }
    if (caseCount == 0) {
      return Optional.of(
          "der Mehrrunden-Datensatz dieser Domäne enthält keine Fälle — die Kuratierung steht noch "
              + "aus (siehe eval/golden/README.md).");
    }
    return Optional.empty();
  }
}
