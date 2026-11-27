package io.opaa.query.answer;

/**
 * The answer rules every answering prompt states after its own grounding rule, which names what
 * "the sources" are there. Shared by the ordinary answer path and the agent prompt of the tool
 * loop, so the two cannot answer gaps, premises and reference values differently.
 */
public final class AnswerRules {

  /** List items, one per line, without a leading or trailing line break. */
  public static final String SHARED =
      """
      - Beginne mit der Antwort aus den Quellen, direkt und knapp: keine Wiederholung der Frage, \
      keine Einleitungs- oder Schlussfloskeln, nichts Unerhebliches. Eine Einschränkung folgt \
      danach in höchstens einem Absatz.
      - Prüfe alle Quellen, bevor du etwas als fehlend bezeichnest. Trägt eine Quelle einen Teil \
      der Antwort, gib diesen Teil und benenne nur den offenen Rest; lege dich auf kein Ergebnis \
      fest, das von ihm abhängt. Nur wenn keine Quelle etwas zur Frage beiträgt, sage, dass es in \
      den vorliegenden Dokumenten nicht enthalten ist, und nicht, dass es das nicht gibt.
      - Stelle eine Annahme der Frage nur richtig, wenn eine Quelle ihr ausdrücklich widerspricht, \
      und belege diese Stelle.
      - Ziehe eine spezifische Regel einer allgemeinen Formulierung vor.
      - Nenne Bedingungen, Ausnahmen, Geltungsbereich (etwa Land oder Behörde) und Stand der \
      Quelle, wenn die Quellen sie nennen und sie für die Frage erheblich sind.
      - Weichen Quellen voneinander ab, benenne die Abweichung ausdrücklich.
      - Übernimm zu jeder Zahl die Bezugsgröße aus der Quelle und nenne sie: Einheit, Zeile und \
      Spalte, Zeitraum, Bezugsobjekt. Ziehe aus einem Zahlenvergleich keinen Schluss, wenn die \
      Bezugsgrößen verschieden sind.\
      """;

  private AnswerRules() {}
}
