# Das Verbindungsprotokoll lesen

> **Entwurf** — gegen den Code geprüft, vom Maintainer noch nicht abgenommen.

> **Wer darf das:** nur die Revision, unter „Revision → Verbindungsprotokoll“. Die Systemverwaltung
> stellt nur die Aufbewahrung ein und liest es nicht.

Das Protokoll hält fest, wann Personen ein Konto bei einer Quelle verbunden, neu verbunden oder
getrennt haben, wann eine Verbindung ablief und wann eine Handlung am Zugang sie beendet hat.
Solange niemand ein Konto verbunden hat, bleibt es leer.

1. „Von“, „Bis“ und „Anlass“ eintragen; alle drei sind Pflicht.
2. Bei Bedarf eingrenzen: „Ereignis (optional)“, „Zugang (optional)“. Ein gelöschter Zugang trägt
   „(gelöscht)“.
3. Abrufen und seitenweise blättern.

Die Tabelle zeigt Zeit, Ereignis, Grund des Endes, Besitzart, „Person“ bzw. Bibliothek mit
Kontoadresse, Zugang und wer ausgelöst hat: „Person“ oder „System“, **nie welche Person**.

**Gut zu wissen:**

- Einen Filter nach Person gibt es nicht. Kein Eintrag enthält ein Token.
- Jeder Abruf steht im Nachweisprotokoll, auch ein abgewiesener.
- Die Frist stellt die Systemverwaltung unter [Zugänge einrichten](zugaenge.md#einstellungen-der-anbindungen)
  ein; abgelaufene Monate löscht ein täglicher Lauf.
