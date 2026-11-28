package io.opaa.query.absence;

/**
 * The message an agent run sends its model after an answer claiming something is missing, when the
 * {@link AbsenceRecheck} found new passages: they follow the answer they are to correct, in the
 * same loop. Part of the agent run's prompt in the compliance manifest.
 */
public final class AbsenceFollowUp {

  private static final String TEXT =
      "Prüfhinweis: Deine Antwort sagt, etwas sei in den Quellen nicht enthalten. Eine Suche nach"
          + " den Begriffen der Frage hat im Suchbereich weitere Textstellen gefunden, die du noch"
          + " nicht gesehen hast:\n\n%s\n\nPrüfe deine Antwort anhand dieser Textstellen und gib"
          + " sie vollständig neu; sie ersetzt die vorige. Belege mit den Zitiermarken der"
          + " Textstellen. Tragen auch sie die Angabe nicht, bleibt es bei der Fehlanzeige.";

  private AbsenceFollowUp() {}

  /** The message around {@code passageBlock}, the rendered block of the new passages. */
  public static String render(String passageBlock) {
    return TEXT.formatted(passageBlock);
  }
}
