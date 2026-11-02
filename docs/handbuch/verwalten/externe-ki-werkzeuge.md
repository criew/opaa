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

## Einstellungen

| Einstellung | Werk | Grenzen |
|---|---|---|
| Zugangstokens höchstens gültig | 90 Tage | 1–365 |
| Anfragen je Token und Stunde | 60 | 1–10.000 |
| Abflussalarm ab Abrufen je Stunde | 600 | 1–1.000.000 |
| Erinnerung vor Ablauf einer Freigabe | 14 Tage | 0 (aus) bis knapp unter die längste Freigabefrist |
| Netzbereiche | private Adressbereiche und localhost | höchstens 50, nur CIDR; leer weist jeden Aufruf ab |
| Einleitungstext für fremde Werkzeuge | Vorgabetext | höchstens 4.000 Zeichen |

## Wenn ein Werkzeug keine Antwort bekommt

Einzelne Abfragen stehen in keinem Log; die Ursache steht in der Antwort, beim MCP-Server im Feld
`reason`.

| Antwort | Ursache | Abhilfe |
|---|---|---|
| `401 token_expired` | Token abgelaufen | neues Token anlegen |
| `401 token_revoked` | widerrufen, gesperrt oder Passwort gewechselt | neues Token anlegen |
| `401 account_not_active` | Konto gesperrt, abgelaufen oder übergeben | Konto klären |
| `401 sign_in_required` | letzte Anmeldung über den Identitätsanbieter zu lange her | einmal in OPAA anmelden |
| `401 invalid_token` | Wert unvollständig kopiert oder unbekannt | neues Token anlegen |
| `401 network_not_allowed` | Aufruf außerhalb der Netzbereiche, oder Proxy ohne Eintrag | Netz oder vertraute Proxys prüfen |
| `503` oder `401 channel_closed` | Kanal ausgeschaltet | einschalten |
| `404` am MCP-Server | Kanal aus und kein gültiges Token, oder falsche Adresse (`/mcp`) | mit gültigem Token wiederholen |
| `429` | Anfragen je Stunde erschöpft | warten oder Grenze anheben |
| Bibliothek fehlt in den Treffern | Leserecht, Freigabe oder Token-Auswahl fehlt | Freigabe erneuern, neues Token |
| HTML statt JSON | Reverse Proxy reicht `/mcp` nicht durch | Proxy-Regel |

**Fallen alle Werkzeuge des Hauses zugleich aus**, spricht ein Client eine MCP-Fassung, die OPAA
nicht bedient (Antwort `400` mit der Liste der bedienten Fassungen), meist nach einem Update von
Client oder OPAA. Abhilfe ist ein abgestimmter Client-Stand, kein Eingriff am Arbeitsplatz.

**Gut zu wissen:**

- Einzelne Abfragen protokolliert OPAA nicht, weder Frage noch Treffer. Ins Nachweisprotokoll kommt,
  was die Reichweite ändert: Tokens, Freigaben, Einstellungen.
- Hinter einem Reverse Proxy braucht die Netzbeschränkung die vertrauten Proxys des Betriebs
  ([Vertraute Proxys](../betreiben/netz-und-sicherheit.md#vertraute-proxys-und-client-adresse)).
- Nach einer Rücksicherung Schalter, Grenzwerte, Freigaben und gesperrte Tokens prüfen; bis dahin
  ausschalten. Eine von Hand zurückgenommene Freigabe und ein gesperrtes Token sind dann wieder da.
- Eine Rücknahme holt nichts zurück: Was ein Werkzeug schon abgerufen hat, bleibt dort. Darum sind
  Freigaben befristet.
