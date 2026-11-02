# Ein Konto verbinden

> **Entwurf** — gegen den Code geprüft, vom Maintainer noch nicht abgenommen.

> **Wer darf das:** jedes Konto, auf Zugängen für Personen, die die Systemverwaltung für Sie
> freigegeben hat; ab Werk gibt es keinen. Von den mitgelieferten Quellen bieten das Nextcloud und
> OneDrive.

Sie hinterlegen Ihre eigenen Zugangsdaten für einen Anbieter. OPAA nutzt sie nur für Ihre
[privaten Bibliotheken](private-bibliothek.md). Das Verbinden ist freiwillig; ohne verbundenes Konto
fehlt Ihnen nichts sonst.

1. „Einstellungen“, dann „Verbundene Konten“ öffnen.
2. Unter **„Weitere Zugänge“** den Zugang wählen.
3. Anmelden, je nach Zugang:
   - mit **„App-Passwort oder Token“** oder Benutzername und Passwort: OPAA prüft die Anmeldung
     beim Anbieter und speichert die Daten nur, wenn sie gelingt;
   - **beim Anbieter** (etwa Microsoft bei OneDrive): OPAA leitet weiter, Sie melden sich dort an,
     stimmen zu und landen wieder auf dieser Seite.
4. Die Verbindung steht unter **„Ihre verbundenen Konten“** mit dem Zustand „Verbunden“.

Fehlt ein Zugang, sagt der Abschnitt „Warum fehlt mein Zugang?“, wer ihn einrichtet.

**Zustände einer Verbindung:**

| Anzeige | Bedeutung | Was Sie tun |
|---|---|---|
| „Verbunden“ | Zugangsdaten gültig | nichts; nach einem Passwortwechsel beim Anbieter „Neu verbinden“ |
| „Abgelaufen“ | der Anbieter lehnt die Daten ab; Ihre Bibliotheken bleiben durchsuchbar, werden aber nicht aktualisiert | „Neu verbinden“ |
| „Getrennt“ | Zugangsdaten gelöscht; die Bibliotheken ruhen | „Neu verbinden“ |
| „Zustimmung endet am …“ | die Zustimmung beim Anbieter läuft in höchstens 14 Tagen ab | vorher „Neu verbinden“ |
| „Nicht mehr freigegeben“ | die Verbindung läuft weiter, ein neues Konto auf dem Zugang geht nicht mehr | wie oben |
| „Zugang gesperrt“ | die Systemverwaltung hat den Zugang gesperrt | nur Trennen |

Läuft eine Verbindung ab oder endet sie durch die Systemverwaltung, erhalten Sie eine
Benachrichtigung, die auf diese Seite führt.

## Trennen

„Trennen“ geht immer und **löscht die Zugangsdaten sofort**. Die Rückfrage nennt Ihre Bibliotheken
auf dieser Verbindung; sie ruhen danach, bis Sie wieder verbinden.

**Gut zu wissen:**

- Gespeicherte Zugangsdaten zeigt OPAA nie wieder an, auch nicht der Systemverwaltung.
- Bei einer Anmeldung beim Anbieter zieht OPAA die Zustimmung dort mit zurück, wo der Anbieter das
  erlaubt. Microsoft erlaubt es nicht: Bei OneDrive entziehen Sie die Zustimmung zusätzlich in Ihrem
  Microsoft-Konto.
- Die Meldungen beim Verbinden nennen die Ursache und die zuständige Stelle.
