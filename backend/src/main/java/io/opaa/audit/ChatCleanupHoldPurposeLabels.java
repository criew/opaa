package io.opaa.audit;

import io.opaa.api.types.ChatCleanupHoldPurpose;
import java.util.Map;

/** The purposes of a hold of the chat deletion of a locked account in words, for every reader. */
public final class ChatCleanupHoldPurposeLabels {

  private static final Map<ChatCleanupHoldPurpose, String> LABELS =
      Map.of(
          ChatCleanupHoldPurpose.AUTHORITY_OR_COURT_ORDER,
              "Behördliche oder gerichtliche Anordnung",
          ChatCleanupHoldPurpose.SECURITY_INCIDENT_EVIDENCE,
              "Sicherung von Beweismitteln nach einem Sicherheitsvorfall",
          ChatCleanupHoldPurpose.DATA_SUBJECT_REQUEST, "Laufender Auskunfts- oder Löschantrag");

  private ChatCleanupHoldPurposeLabels() {}

  /** The purpose in words; for {@code null}, a hold placed before purposes existed. */
  public static String label(ChatCleanupHoldPurpose purpose) {
    return purpose == null
        ? "Ohne Zweckangabe (vor Einführung der Freigabe angehalten)"
        : LABELS.get(purpose);
  }
}
