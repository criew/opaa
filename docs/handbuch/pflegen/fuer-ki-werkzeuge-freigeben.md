# Für externe KI-Werkzeuge freigeben

> **Entwurf** — gegen den Code geprüft, vom Maintainer noch nicht abgenommen.

> **Wer darf das:** Rolle *Verwalter* an der Wissensbibliothek und die Systemverwaltung. Ab Werk ist
> die Freigabe aus, und externe KI-Werkzeuge sind für die ganze Installation abgeschaltet, bis die
> Systemverwaltung sie einschaltet. *Verwalter* ist ab Werk, wer die Bibliothek angelegt hat.

Wer die Bibliothek lesen darf, kann sie danach in ein [Zugangstoken](../arbeiten/zugangstoken.md)
aufnehmen und aus Werkzeugen wie Claude Code oder Copilot durchsuchen. Fundstellen verlassen damit
OPAA und werden im fremden Werkzeug mit dessen Modell weiterverarbeitet.

1. Detailseite der Bibliothek, Reiter „Freigaben“, Abschnitt „Externer Zugang“.
2. Unter „Freigabe bis“ ein Datum wählen; daneben steht die Grenze („höchstens N Tage“).
3. „Für externe KI-Werkzeuge nutzbar“ einschalten. Das wirkt sofort.

**Erneuern:** neues Datum wählen, „Ablaufdatum übernehmen“. Sonst erlischt die Freigabe von selbst.

**Vor dem Ablauf** geht eine Wiedervorlage per Mail an die Person, die die Freigabe zuletzt gesetzt
hat. Gibt es dieses Konto nicht mehr, geht sie an die Eigentümerin; gehört die Bibliothek einer
Gruppe, unterbleibt sie dann. Ohne eingerichteten Mailversand erlischt die Freigabe unangekündigt.

**Zurücknehmen:** Schalter aus. Das wirkt sofort für alle Tokens. Ihre Auswahl gilt danach als „ausgesetzt“ und lebt
auch bei einer neuen Freigabe nicht wieder auf; dafür braucht es ein neues Token.

**Gut zu wissen:**

- Ein Werkzeug sieht nie mehr als die Person, der das Token gehört: nur, was sie lesen darf.
- Sie sehen die Zahl der Tokens, die Ihre Bibliothek enthalten, ohne Namen. Steht sie lange auf
  null, wird die Freigabe nicht gebraucht.
- Setzen, Zurücknehmen und Erlöschen stehen im Nachweisprotokoll.
