# Agenten verwenden

**Ziel:** Eine Frage an einen Agenten geben, seine Schritte verfolgen und danach nachlesen.
{: .kopf }

Ein Agent beantwortet eine Frage in mehreren Schritten. Er sucht etwa erst nach der
Dienstvereinbarung, dann nach den Fristen darin, und schreibt die Antwort aus allem, was er dabei
gefunden hat. Das dauert länger als eine gewöhnliche Antwort.

## Agent wählen

Agenten gibt es nur, wenn die Systemverwaltung sie eingeschaltet hat und dem Space ein Agent
zugeordnet ist, den Sie lesen dürfen ([Agent anlegen](agent-anlegen.md), Reiter „Zuordnungen“).

1. Im Eingabefeld `@` tippen. Das Menü zeigt zwei Gruppen: oben „Wissen“, darunter „Agenten“ mit
   Roboter-Symbol.
2. Einen Agenten wählen. Über dem Eingabefeld steht dann der eckige Chip „Agent: Name“.
3. Die Frage tippen und mit Enter senden.

An der gesendeten Frage steht „Agent: Name“, unter der Antwort die Schritte des Agenten und die
Kennzeichnung „Entwurf“ mit der Fassung des Agenten. Wie Sie das Ergebnis prüfen, steht unter
[Agentenergebnis prüfen](agentenergebnis-pruefen.md).

**Wo gesucht wird.** Der Agent ersetzt für diese Frage die Chip-Leiste: Er sucht in den Bibliotheken
des Space, die Sie lesen dürfen, eingegrenzt auf die Bibliotheken des Agenten, sofern er welche hat.
Mehr als Sie selbst lesen dürfen, liest er nie.

**Gut zu wissen zur Auswahl:**

- Der Agent gilt nur für die eine Frage. Für die nächste wählen Sie ihn erneut.
- Mit `/` setzen Sie zusätzlich einen Prompt ein: Der Prompt liefert den Text, der Agent bearbeitet
  ihn ([Prompts verwenden](prompts-verwenden.md)).
- Ein Klick auf das Kreuz im Chip nimmt den Agenten wieder ab; die Frage läuft dann wie gewohnt.

## Schritte lesen

Während der Agent arbeitet, nennt die Zeile über der Antwort, was er gerade tut, etwa „Sucht nach
„Dienstvereinbarung“ …“; eine lange Teilfrage steht dort gekürzt. Der Text bleibt stehen, bis der
nächste Schritt beginnt. Darunter wächst die Liste der Schritte. Jeder Schritt steht in einer
Zeile:

- **Name des Schritts:** „Suche“, „Lesen“, „Übersicht“, „Berechnung“ oder „Rückfrage“, dahinter
  worum es ging, etwa die Teilfrage einer Suche oder der Name eines gelesenen Dokuments.
- **Ergebnis in Zahlen:** wie viele Textstellen der Schritt gefunden hat.
- **Zustand**, wenn etwas anders lief: „Läuft“, „Leer“, „Eingegrenzt“ (eine Einschränkung außerhalb
  Ihres Bereichs wurde übergangen), „Ungültig“, „Unbekannt“, „Verweigert“, „Grenze“ oder „Fehler“.

Ist die Antwort geschrieben, steht in derselben Zeile „Belege werden geprüft … Vorläufig.“ Die
Antwort erscheint darunter, die Schritte schon zugeklappt an der Stelle, an der sie danach bleiben.
Bis OPAA die Belege geprüft und die Antwort gespeichert hat, lässt sie sich weder kopieren noch
exportieren. Danach erscheint sie wie jede Antwort mit ihren Belegen.

An der fertigen Antwort stehen die Schritte zugeklappt, etwa als „3 Schritte“. Ein Klick klappt die
Liste auf; sie bleibt im Chat, solange der Chat besteht. Die Laufkennung darunter brauchen Sie nur,
wenn die Systemverwaltung einem Fehler nachgeht.

Die Schritte gehören nur zu dieser Antwort. In spätere Fragen, in den Titel des Chats und in die
Suche über Ihre Chats gehen sie nicht ein.

## Grenze und Rückfrage

- **Grenze erreicht:** Jeder Lauf hat Grenzen für Schritte, Dauer und gesammelten Text. Erreicht
  der Agent eine, steht das in der Zeile über der Antwort. Er antwortet dann mit dem bis dahin
  Gefundenen, und die Antwort trägt den Vermerk „nicht geprüft“.
- **Rückfrage:** Braucht der Agent eine Angabe von Ihnen, endet der Lauf mit seiner Frage.
  Antworten Sie in einer neuen Nachricht; der Agent beginnt dann einen neuen Lauf.

**Gut zu wissen:**

- „Antwort abbrechen“ beendet den Agenten vor seinem nächsten Schritt, auch wenn er gerade auf
  das Modell wartet. Nichts von der Frage wird gespeichert, sie steht wieder im Eingabefeld.
- Reißt die Verbindung ab, endet der Lauf spätestens nach wenigen Sekunden; senden Sie die Frage
  erneut.
- Bildschirmleser sagen jeden neuen Schritt einmal an. Die Liste wird nicht vorgelesen und lässt
  sich in Ruhe lesen.
