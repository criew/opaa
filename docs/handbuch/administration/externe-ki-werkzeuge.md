# Externe KI-Werkzeuge zulassen

**Ziel:** Personen durchsuchen freigegebene Bibliotheken aus Werkzeugen wie OpenCode, kontrolliert und befristet.
{: .kopf }

**Wer:** Systemverwaltung, „Administration → Anbindungen“, Reiter „Externe KI-Werkzeuge“ und „Dienstkonten“
{: .kopf }

Werkzeuge wie OpenCode holen sich mit einem Zugangstoken Fundstellen aus
freigegebenen Bibliotheken. Ein Token kann nie mehr als seine Person: Jeder Aufruf sieht nur, was sie
heute lesen darf, was ihre Bibliothek freigegeben hat und was sie ins Token gewählt hat.

## Vor dem Einschalten

Der Kanal kann geeignet sein, Verhalten und Leistung zu überwachen. Vorher klären:

- die Beteiligung der Personalvertretung,
- die Unterrichtung der Beschäftigten und die Freiwilligkeit der Nutzung,
- mit dem Datenschutz, dass Fragen und Fundstellen im fremden Werkzeug mit dessen Modell,
  Protokoll und Telemetrie weiterverarbeitet werden,
- wer Bibliotheken freigibt ([Bibliothek teilen und für KI-Werkzeuge freigeben](../benutzer/arbeitsmittel-teilen.md#für-externe-ki-werkzeuge-freigeben)).

## Einschalten

1. Seitenpanel „Einstellungen“ öffnen.
2. Höchstlaufzeit der Tokens, Anfragen je Token und Stunde, Abflussalarm und **Netzbereiche**
   prüfen. Ab Werk antwortet der Kanal nur aus dem Hausnetz.
3. „Externe KI-Werkzeuge zulassen“ einschalten und speichern.

Oben steht der Zustand mit Datum und handelnder Person. **„Ausschalten“** ist der Notaus: Er wirkt
beim nächsten Aufruf für alle Tokens, und die Tokens bleiben erhalten.

## Tokens verwalten

Die Liste „Zugangstokens“ zeigt Besitzer, Name, Bibliotheken, Ablauf und Zustand, bewusst ohne
Nutzungsdatum und ohne Filter nach Person; auch die Tokens der Dienstkonten stehen darin, mit dem
Dienstkonto als Besitzer. Sie können ein einzelnes Token oder alle Tokens einer Person oder eines
Dienstkontos sperren; den Tokenwert sieht niemand. Sperre, Ablauf, Passwortwechsel und die Übergabe
an einen Identitätsanbieter beenden die Tokens eines Kontos von selbst. Die Zahl der Tokens an
einer Bibliothek zählt die Tokens der Dienstkonten mit. Wie viele gültige Tokens ein Konto
höchstens hat, legt `OPAA_EXTERNAL_ACCESS_MAX_ACTIVE_TOKENS_PER_ACCOUNT` fest.

„Anfragen je Token und Stunde“ gilt je Token und zusätzlich für alle Tokens einer Person zusammen:
Ein weiteres Token bringt ihr keine weiteren Anfragen.

Der **Abflussalarm** zählt die Abrufe des ganzen Kanals je Stunde und meldet eine Überschreitung
einmal an die Systemverwaltung. Die Meldung führt zur Tokenliste und dient Vorfall und Sperre, nicht
Leistungs- oder Disziplinarfragen.

## Dienstkonten

Ein Fachverfahren wie ein Antragsportal hängt nicht am Token einer Person, sondern an einem
**Dienstkonto** mit eigenen Tokens. Es erreicht nur Bibliotheken, deren Verwaltung ihm zugestimmt
hat, je Bibliothek befristet und jederzeit widerrufbar; die Freigabe für Werkzeuge der Beschäftigten
zählt hier nicht ([Dienstkonto aufnehmen](../benutzer/arbeitsmittel-teilen.md#dienstkonto-aufnehmen)).

1. Reiter „Dienstkonten“, „Dienstkonto anlegen“: den Namen des Verfahrens, die verantwortliche
   Person und bei Bedarf „Anfragen je Stunde“ für alle Tokens zusammen, dann „Weiter“.
2. „Erstes Token ausstellen“: Name und Ablauf, „Ausstellen“. Den Wert mit „Kopieren“ übernehmen und
   auf sicherem Weg an den Betrieb des Verfahrens geben; er erscheint nur dieses eine Mal. „Ohne
   Token beenden“ legt das Dienstkonto ohne Token an.
3. Die Verwaltung jeder Bibliothek, die das Verfahren durchsuchen soll, nimmt das Dienstkonto auf.

Ein Klick auf eine Zeile der Liste öffnet das Dienstkonto mit Angaben, Tokens und Bibliotheken.
**Erneuern ohne Ausfall:** „Token ausstellen“, im Verfahren umstellen, dann das alte Token über „⋯“
„Widerrufen“. „Dienstkonto deaktivieren“ beendet alle Tokens sofort und lässt sich nicht
zurücknehmen.

Die verantwortliche Person ist nur Ansprechpartnerin für Erinnerungen und Erneuerung: Fällt sie
weg, laufen die Tokens weiter, und die Liste zeigt „Verantwortlich fehlt“. Grenzen: Eine Rotation
von `OPAA_AUTH_JWT_SECRET` entwertet auch diese Tokens; eine Rücksicherung belebt widerrufene Tokens
und Zustimmungen wieder; der Kanal bleibt im Hausnetz. Was das Fachverfahren selbst mit den
Fundstellen protokolliert, klärt die Behörde vor der Anbindung.

## Einstellungen

| Einstellung | Werk | Grenzen |
|---|---|---|
| Zugangstokens höchstens gültig | 90 Tage | 1–365 |
| Anfragen je Token und Stunde | 60 | 1–10.000 |
| Abflussalarm ab Abrufen je Stunde | 600 | 1–1.000.000 |
| Erinnerung vor Ablauf einer Freigabe oder Zustimmung für ein Dienstkonto | 14 Tage | 0 (aus) bis knapp unter die längste Freigabefrist |
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
| `429`, am MCP-Server Fehler `-32000` oder ein Ergebnis mit „Kontingent“ | Anfragen je Token und Stunde erschöpft, auch über alle Tokens der Person zusammen (schon das Verbinden zählt); bei Suche und Trefferabruf auch die Grenze je Konto oder der Suche insgesamt | warten, bei Bedarf Grenze anheben |
| Bibliothek fehlt in den Treffern | Leserecht, Freigabe oder Token-Auswahl fehlt | Freigabe erneuern, neues Token |
| HTML statt JSON | Reverse Proxy reicht `/mcp` nicht durch | Proxy-Regel |

**Fallen alle Werkzeuge des Hauses zugleich aus**, spricht ein Client eine MCP-Fassung, die OPAA
nicht bedient (Antwort `400` mit der Liste der bedienten Fassungen), meist nach einem Update von
Client oder OPAA. Abhilfe ist ein abgestimmter Client-Stand, kein Eingriff am Arbeitsplatz.

**Gut zu wissen:**

- Einzelne Abfragen protokolliert OPAA nicht, weder Frage noch Treffer. Ins Nachweisprotokoll kommt,
  was die Reichweite ändert: Tokens, Freigaben, Einstellungen.
- Hinter einem Reverse Proxy braucht die Netzbeschränkung die vertrauten Proxys des Betriebs
  ([Vertraute Proxys](../betrieb/netz-und-sicherheit.md#vertraute-proxys-und-client-adresse)).
- Nach einer Rücksicherung Schalter, Grenzwerte, Freigaben und gesperrte Tokens prüfen; bis dahin
  ausschalten. Eine von Hand zurückgenommene Freigabe und ein gesperrtes Token sind dann wieder da.
- Eine Rücknahme holt nichts zurück: Was ein Werkzeug schon abgerufen hat, bleibt dort. Darum sind
  Freigaben befristet.
