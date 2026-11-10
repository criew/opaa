# Datenschutz einstellen

**Ziel:** Fristen und Schalter für Daten von Personen entsprechen den Vorgaben des Hauses.
{: .kopf }

**Wer:** Systemverwaltung, „Administration → Datenschutz“
{: .kopf }

Jede Änderung wirkt ohne Neustart auf allen Instanzen und steht mit altem und neuem Wert im Nachweisprotokoll.

| Abschnitt | Was er regelt | Ab Werk |
|---|---|---|
| „Selbstauskunft“ | ob Personen ihre Daten herunterladen können (Notabschaltung) und wie oft am Tag | an; ein vollständiger Download je Tag |
| „Bereinigung der Chats in Spaces“ | ob inaktive Chats archiviert und gelöscht werden, und nach welcher Frist | siehe [Chats verwalten](../benutzer/chats.md#automatisches-aufräumen) |
| „Chats gesperrter Konten“ | Löschung der Chats nach einer Sperre durch die Verwaltung oder den Verzeichnisabgleich, mit Frist | an, nach 365 Tagen |
| „Anlassbezogene Klärungen“ | wie lange Anträge und Freigaben einer Klärung liegen bleiben | 1 Jahr, 1 bis 10 Jahre |
| „Protokolle der Agentenläufe“ | wie lange Schritt- und Sandboxprotokoll der Agenten liegen bleiben; die Löschung ist nicht abschaltbar, das Sandboxprotokoll nie länger als das Schrittprotokoll | je 7 Tage, 1 bis 30 Tage |

**Eine Verkürzung wirkt mit dem nächsten Löschlauf vollständig**, eine Verlängerung sofort, holt aber
Gelöschtes nicht zurück. Eine Dienstvereinbarung kann längere Fristen vorsehen.

Weitere Fristen stehen an ihrem Ort: die Aufbewahrung des Verbindungsprotokolls und die Löschfrist
privater Bibliotheken unter [Zugänge einrichten](zugaenge.md#einstellungen-der-anbindungen), die
Hervorhebung „lange offen“ unter [Ausscheiden und Nachfolge](ausscheiden.md).

## Protokoll eines Agentenlaufs einsehen

Für die Fehlersuche hält OPAA zu jedem Agentenlauf fest, welches Werkzeug ein Schritt mit welchem
Ergebnis und welcher Dauer lief, und je Sandbox die Zahl der Skripte, Dateien und Bytes. Konto,
Suchtext, Skript und Dateiinhalt stehen nicht darin.

Einsehen kann es nur die Systemverwaltung der eigenen Organisation, über die Schnittstelle
`GET /api/v1/admin/agent-runs/{Laufkennung}/log?reason=…`: genau ein Lauf, nur mit Begründung.
Die Laufkennung nennt die Person, die den Fehler meldet. Jeder Abruf, auch ein abgelehnter, steht im
Revisionsprotokoll. Eine Liste der Läufe, eine Suche nach Personen oder einen Export gibt es nicht.

**Gut zu wissen:**

- Wann eine Person zuletzt ihre Daten geladen hat, zeigt keine Ansicht der Verwaltung.
- Die Auskunft über die Datenerhebung dieser Installation, etwa für den Personalrat, beschreibt
  [Nachvollziehbarkeit](../betrieb/nachvollziehbarkeit.md#auskunft-über-die-datenerhebung-einer-installation).
- Alle Fristen lassen sich mit [Einstellungen übertragen](einstellungen-uebertragen.md) in eine
  andere Installation mitnehmen.
