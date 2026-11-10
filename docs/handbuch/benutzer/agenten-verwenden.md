# Agenten verwenden

**Ziel:** Eine Frage, die ein Agent in mehreren Schritten mit seinen Werkzeugen bearbeitet.
{: .kopf }

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

**Gut zu wissen:**

- Der Agent gilt nur für die eine Frage. Für die nächste wählen Sie ihn erneut.
- Mit `/` setzen Sie zusätzlich einen Prompt ein: Der Prompt liefert den Text, der Agent bearbeitet
  ihn ([Prompts verwenden](prompts-verwenden.md)).
- Ein Klick auf das Kreuz im Chip nimmt den Agenten wieder ab; die Frage läuft dann wie gewohnt.
