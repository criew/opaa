# Eine Bibliothek aktuell halten

> **Entwurf** — gegen den Code geprüft, vom Maintainer noch nicht abgenommen.

> **Wer darf das:** „Jetzt indizieren“, Dokumente hochladen und löschen ab der Rolle *Bearbeiter*;
> Zeitplan, Quelle und Läufe ab *Verwalter*. Ab Werk hat beides, wer die Bibliothek angelegt hat.

Sie sorgen dafür, dass die Suche den aktuellen Stand der Quelle findet, und beheben, was einen Lauf
aufhält.

## Den Zeitplan setzen

1. Reiter „Quelle“, Zeile „Zeitplan“, „Ändern“.
2. Stufe wählen: aus, stündlich, täglich mit Uhrzeit oder wöchentlich mit Wochentag und Uhrzeit.
3. „Speichern“. Der Plan beginnt mit seinem nächsten Termin.

Ein unveränderter Feed verträgt stündlich, ein großes Netzlaufwerk eher täglich oder wöchentlich.
Die Uhrzeit gilt in der Zeitzone des Servers.

## Sofort aktualisieren

„Jetzt indizieren“ im Kopf der Detailseite startet einen Lauf. Wo die Quelle es kennt, liest
„Vollabgleich starten“ alles neu und erkennt dabei auch Gelöschtes. Je Bibliothek läuft höchstens
ein Lauf.

## Den Zustand lesen

Der Kopf zeigt ein Wort, etwa „Aktuell · 23.10.2026“, „Läuft · 40 %“ oder „Teilweise
fehlgeschlagen · 3 Fehler“. Ein Klick führt zur Ursache. Unter „Läufe“ im Reiter „Quelle“ klappt
jeder Lauf seine Fundorte je Kategorie auf.

| Meldung | Was Sie tun |
|---|---|
| „Verbindung getrennt“ | Zugangsdaten in der Zeile „Verbindung“ neu eintragen |
| „Nicht verbunden“, „Abgelaufen: Die Verbindung der Quelle …“ | Quelle neu verbinden |
| „Zugang entfernt“ | einen anderen Zugang zuordnen |
| „Die Adresse der Bibliothek liegt nicht unter …“ | Adresse an den Zugang anpassen |
| „Gesperrt – Inhalt wird nicht mehr aktualisiert“ | die Systemverwaltung fragen |
| „Abgelaufen: Der Anbieter hat die Anmeldung des Zugangs … abgelehnt“ | die Systemverwaltung fragen |

Solange eine Bibliothek ruht, bleibt ihr Bestand durchsuchbar; Belege tragen „Stand vom …“.

**Gut zu wissen:**

- OPAA entfernt ein Dokument nur nach einem Lauf, der die Quelle vollständig gelesen hat. Ist die
  Quelle nicht erreichbar, verschwindet nichts.
- Scheitern zwei geplante Läufe hintereinander, zeigt die Bibliothek ein Warnbanner; der Zeitplan
  bleibt an.
- Was je Quelle gilt, steht auf ihrer Seite unter [Konnektoren](konnektoren/README.md).
