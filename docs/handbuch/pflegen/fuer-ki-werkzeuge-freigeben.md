# Für externe KI-Werkzeuge freigeben

> **Entwurf** — gegen den Code geprüft, vom Maintainer noch nicht abgenommen.

> **Wer darf das:** Rolle *Verwalter* an der Wissensbibliothek und die Systemverwaltung. Ab Werk ist
> die Freigabe aus, und externe KI-Werkzeuge sind für die ganze Installation abgeschaltet, bis die
> Systemverwaltung sie einschaltet.

Wer die Bibliothek lesen darf, kann sie danach in ein [Zugangstoken](../arbeiten/zugangstoken.md)
aufnehmen und aus Werkzeugen wie Claude Code oder Copilot durchsuchen.

1. Detailseite der Bibliothek, Reiter „Freigaben“, Abschnitt „Externer Zugang“.
2. „Für externe KI-Werkzeuge nutzbar“ einschalten und ein Ablaufdatum wählen. Ohne Datum geht es
   nicht; die Höchstdauer legt die Installation fest.
3. Speichern.

**Vor dem Ablauf** kommt eine Wiedervorlage per Mail an die Person, die die Freigabe zuletzt gesetzt
hat. Erneuern Sie sie dann, sonst erlischt sie von selbst. Gehört die Bibliothek einer Gruppe,
kommt keine Mail: Das Ablaufdatum gehört dann in Ihre eigene Wiedervorlage.

**Zurücknehmen** wirkt sofort für alle Tokens. Ihre Auswahl gilt danach als „ausgesetzt“ und lebt
auch bei einer neuen Freigabe nicht wieder auf; dafür braucht es ein neues Token.

**Gut zu wissen:**

- Ein Werkzeug sieht nie mehr als die Person, der das Token gehört: nur, was sie lesen darf.
- Sie sehen die Zahl der Tokens, die Ihre Bibliothek enthalten, ohne Namen. Steht sie lange auf
  null, wird die Freigabe nicht gebraucht.
- Setzen, Zurücknehmen und Erlöschen stehen im Nachweisprotokoll.
