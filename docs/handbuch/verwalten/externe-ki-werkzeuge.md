# Externe KI-Werkzeuge zulassen

> **Entwurf** — gegen den Code geprüft, vom Maintainer noch nicht abgenommen.

> **Wer darf das:** die Systemverwaltung, unter „Administration → Anbindungen“, Reiter „Externe
> KI-Werkzeuge“. Ab Werk ist der Kanal aus; der Betrieb kann ihn ganz abschalten, dann fehlt der
> Reiter.

Werkzeuge wie Claude Code oder Copilot holen sich mit einem Zugangstoken Fundstellen aus
freigegebenen Bibliotheken. Ein Token kann nie mehr als seine Person: Jeder Aufruf sieht nur, was sie
heute lesen darf, was ihre Bibliothek freigegeben hat und was sie ins Token gewählt hat.

## Vor dem Einschalten

Der Kanal kann geeignet sein, Verhalten und Leistung zu überwachen. Vorher klären:

- die Beteiligung der Personalvertretung,
- die Unterrichtung der Beschäftigten und die Freiwilligkeit der Nutzung,
- mit dem Datenschutz, dass Fragen und Fundstellen im fremden Werkzeug mit dessen Modell,
  Protokoll und Telemetrie weiterverarbeitet werden,
- wer Bibliotheken freigibt ([Für externe KI-Werkzeuge freigeben](../pflegen/fuer-ki-werkzeuge-freigeben.md)).

## Einschalten

1. Seitenpanel „Einstellungen“ öffnen.
2. Höchstlaufzeit der Tokens, Anfragen je Token und Stunde, Abflussalarm und **Netzbereiche**
   prüfen. Ab Werk antwortet der Kanal nur aus dem Hausnetz.
3. „Externe KI-Werkzeuge zulassen“ einschalten und speichern.

Oben steht der Zustand mit Datum und handelnder Person. **„Ausschalten“** ist der Notaus: Er wirkt
beim nächsten Aufruf für alle Tokens, und die Tokens bleiben erhalten.

## Tokens verwalten

Die Liste „Zugangstokens“ zeigt Besitzer, Name, Bibliotheken, Ablauf und Zustand, bewusst ohne
Nutzungsdatum und ohne Filter nach Person. Sie können ein einzelnes Token oder alle Tokens einer
Person sperren; den Tokenwert sieht niemand. Sperre, Ablauf, Passwortwechsel und die Übergabe an
einen Identitätsanbieter beenden die Tokens eines Kontos von selbst.

Der **Abflussalarm** zählt die Abrufe des ganzen Kanals je Stunde und meldet eine Überschreitung
einmal an die Systemverwaltung. Die Meldung führt zur Tokenliste und dient Vorfall und Sperre, nicht
Leistungs- oder Disziplinarfragen.

**Gut zu wissen:**

- Einzelne Abfragen protokolliert OPAA nicht, weder Frage noch Treffer. Ins Nachweisprotokoll kommt,
  was die Reichweite ändert: Tokens, Freigaben, Einstellungen.
- Hinter einem Reverse Proxy braucht die Netzbeschränkung die vertrauten Proxys des Betriebs
  ([Vertraute Proxys](../betrieb/netz-und-sicherheit.md#vertraute-proxys-und-client-adresse)).
- Nach einer Rücksicherung Schalter, Grenzwerte, Freigaben und gesperrte Tokens prüfen; bis dahin
  ausschalten.
