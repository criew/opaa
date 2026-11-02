# Ein Konto an einen Identitätsanbieter übergeben

> **Entwurf** — gegen den Code geprüft, vom Maintainer noch nicht abgenommen.

> **Wer darf das:** die Systemverwaltung stößt an, einlösen kann nur die betroffene Person.

Stellt ein Haus von lokalen Konten auf einen Identitätsanbieter um, nimmt die Übergabe Spaces,
Mitgliedschaften und Rolle mit. Danach meldet sich die Person nur noch über den Anbieter an.

1. Zeilenmenü des Kontos, „An Identitätsanbieter übergeben“.
2. Anbieter wählen und einen **Anlass** eintragen; eine Kennung beim Anbieter gibt niemand ein.
3. Die Person erhält einen Link, 72 Stunden und einmal gültig. Ohne Mailversand zeigt OPAA ihn
   einmal zur Weitergabe an. Bis zur Einlösung bleibt das Konto mit Passwort nutzbar.
4. Die Person sieht, was mitgeht, meldet sich beim Anbieter an und hat damit übergeben. Alle
   Sitzungen und Zugangstokens enden, das Passwort verfällt. **Einen Rückweg gibt es nicht.**

| Abgelehnt | Weil |
|---|---|
| das Notanker-Konto | es muss lokal bleiben |
| der letzte anmeldefähige Systemverwalter | geprüft beim Anstoßen und beim Einlösen |
| unter der Anbieteridentität besteht schon ein Konto | zwei Konten werden nie zusammengeführt |
| Anmeldung bei einem anderen Anbieter | die Übergabe gilt nur für den gewählten |
| Konto inzwischen gesperrt oder abgelaufen | eine Übergabe hebt keine Sperre auf |

**Gut zu wissen:**

- Adresswechsel, Sperre und ein erzeugtes Passwort schließen den offenen Link; dann neu anstoßen.
- Wer eine ganze Abteilung an einem Vormittag umstellt, lässt den Betrieb die Anfragegrenze für
  Übergaben vorübergehend anheben: Alle Arbeitsplätze teilen sich meist eine Adresse.
- Der Anlass steht nicht im Nachweisprotokoll; die Person liest ihn auf der Einlöseseite.
