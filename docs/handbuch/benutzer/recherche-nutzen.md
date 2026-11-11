# Recherche nutzen

**Ziel:** Sie lassen den Agenten „Recherche“ eine Frage aus Ihrem Wissen in mehreren Schritten untersuchen und erhalten einen gegliederten Bericht mit Fundstellen als Entwurf.
{: .kopf }

„Recherche“ ist ein Agent, der mit OPAA mitgeliefert wird, sobald die Systemverwaltung Agenten einschaltet. Er liest nur, was Sie selbst lesen dürfen, und schreibt nichts nach außen. Ein Lauf dauert eine Anfrage lang und ist begrenzt: Er ersetzt keine mehrstündige Recherche, sondern trägt in einigen Minuten zusammen, was die Bibliotheken zu Ihrer Frage hergeben. Hat die Systemverwaltung ihn gesperrt, erscheint er nicht in der Auswahl.

## Recherche starten

1. Im Eingabefeld `@` tippen und unter „Agenten“ „Recherche“ wählen ([Agenten verwenden](agenten-verwenden.md)).
2. Die Frage so genau stellen, wie Sie sie beantwortet haben möchten, und senden.

## Plan bestätigen oder ändern

„Recherche“ arbeitet erst, wenn Sie seinen Plan gesehen haben. Der erste Lauf endet mit einer nummerierten Liste der Teilfragen und der Stellen, an denen er suchen will. Unter dem Plan stehen zwei Schaltflächen:

- **„Bestätigen“** startet einen neuen Lauf, der den Plan abarbeitet.
- **„Ändern“** wählt „Recherche“ wieder für die nächste Nachricht und setzt den Cursor ins Eingabefeld. Schreiben Sie, was anders sein soll, etwa nur bestimmte Bibliotheken, einen Zeitraum oder eine Dokumentart. Der Agent arbeitet dann mit Ihrer Änderung. Legt er stattdessen erneut einen Plan vor, ohne zu suchen, endet der Lauf wieder als Plan mit den beiden Schaltflächen: Prüfen Sie den neuen Plan und bestätigen Sie ihn. Das geschieht höchstens einmal hintereinander; bei der nächsten Bestätigung läuft der Agent in jedem Fall.

Der Plan nennt weder Dauer noch Kosten; die Schritte zeigt der Lauf selbst, sobald er arbeitet.

## Rückfrage beantworten

Ist die Frage unklar, fragt „Recherche“ nach, bevor er sucht. Der Lauf endet mit der Frage; antworten Sie in einer neuen Nachricht, „Recherche“ ist dafür schon gewählt. Den Chip im Eingabefeld können Sie abnehmen, dann läuft die Nachricht ohne Agenten.

## Bericht lesen

Der Bericht beginnt mit einer kurzen Zusammenfassung, dann folgen die Teilfragen mit ihren Ergebnissen, jede Aussage mit ihrer Fundstelle. Widersprechen sich Fundstellen, nennt der Agent beide, statt sich für eine zu entscheiden; was er nicht gefunden hat, steht als Lücke im Bericht. Eine Fundstelle, die OPAA beim Prüfen nicht in den gelesenen Stellen findet, ist gekennzeichnet ([Belege prüfen](belege-pruefen.md)).

## Grenzen und Entwurf

- Erreicht ein Lauf seine Grenze für Schritte, Dauer oder gesammelten Text, antwortet der Agent mit dem bis dahin Gefundenen, die Antwort trägt „nicht geprüft“. Dann hilft eine engere Frage oder ein geänderter Plan.
- Der Bericht ist ein **Entwurf, nicht geprüft** mit dem Fassungsstempel des Agenten. Prüfen Sie Fundstellen und Zahlen, bevor Sie ihn weitergeben ([Agentenergebnis prüfen](agentenergebnis-pruefen.md)).
- Eine Datei nach Vorlage liefert „Recherche“ nur, wenn ihm jemand eine Vorlage zugeordnet hat; ohne Vorlage antwortet er im Chat.

**Gut zu wissen:**

- Die Grenzen von „Recherche“ liegen über denen eines neu angelegten Agenten, aber innerhalb der Grenzen der Installation.
- Er hat keine feste Bibliothek, sondern sucht in allen, die Sie im Space lesen dürfen.
- Wer ihn pflegt, steht in der verantwortlichen Stelle; ab Werk ist das die Systemverwaltung.
