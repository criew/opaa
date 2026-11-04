# Teilen und für KI-Werkzeuge freigeben

> **Wer:** *Verwalter* oder *Eigentümer* der Bibliothek, übergeben nur der *Eigentümer*.

Andere Personen oder Gruppen lesen oder pflegen danach Ihre Wissens- oder Prompt-Bibliothek.

1. Die Detailseite öffnen, Reiter „Freigaben“, „Freigeben“.
2. Person, Gruppe oder „Alle Konten“ suchen und eine Rolle wählen.
3. „Freigeben“. Entziehen geht über das Menü „⋯“ der Zeile.

| Rolle | Darf zusätzlich |
|---|---|
| Leser | lesen, durchsuchen, nutzen |
| Bearbeiter | Dokumente hochladen und löschen, Läufe anstoßen, Metadaten setzen, Prompts schreiben |
| Verwalter | Name, Rechte, Quellverbindung, Freigabe für externe KI-Werkzeuge |
| Eigentümer | löschen und das Eigentum übergeben |

„Alle Konten“ erhalten höchstens *Bearbeiter*.

## Eigentum übergeben

Unter „Eigentümer“ „Übergeben“ wählen und Person oder Gruppe suchen. Das wirkt sofort, und Ihre
eigene Rolle endet, wenn Sie keine eigene Freigabe haben. Eine Gruppe als Eigentümerin übersteht den
Weggang einzelner Personen.

## Für externe KI-Werkzeuge freigeben

Wer die Bibliothek lesen darf, kann sie danach in ein [Zugangstoken](zugangstoken.md) aufnehmen und
aus Werkzeugen wie OpenCode durchsuchen. Fundstellen verlassen damit OPAA und werden im fremden
Werkzeug mit dessen Modell weiterverarbeitet. Das geht erst, wenn die Systemverwaltung externe
KI-Werkzeuge eingeschaltet hat, und braucht die Rolle *Verwalter*.

1. Im Reiter „Freigaben“, Abschnitt „Externer Zugang“, unter „Freigabe bis“ ein Datum wählen.
2. „Für externe KI-Werkzeuge nutzbar“ einschalten.

Die Freigabe erlischt am gewählten Datum; vorher kommt eine Erinnerung per Mail, sofern der
Mailversand eingerichtet ist. Schalter aus nimmt sie sofort für alle Tokens zurück.

**Gut zu wissen:**

- Jede Freigabe und jede Übergabe steht im Nachweisprotokoll.
- Ein KI-Werkzeug sieht nie mehr als die Person, der das Token gehört.
- Eine private Bibliothek lässt sich nicht teilen.
