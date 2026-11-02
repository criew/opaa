# Ein Zugangstoken anlegen

> **Wer darf das:** jedes Konto, sobald die Systemverwaltung externe KI-Werkzeuge eingeschaltet hat.
> Wählbar sind nur Bibliotheken, die Sie lesen dürfen und die für externe KI-Werkzeuge freigegeben
> sind.

Ein externes KI-Werkzeug, etwa Claude Code oder Copilot, holt sich danach Fundstellen aus Ihren
Bibliotheken. Es bekommt nur Fundstellen, keine Antworten von OPAA, und nie mehr, als Sie selbst
lesen dürfen.

1. „Einstellungen“, dann „Zugangstokens“ öffnen und ein neues Token anlegen.
2. **Name:** den Zweck, etwa „Claude Code auf dem Dienstrechner“. Daran erkennen Sie später, was Sie
   widerrufen.
3. **Bibliotheken:** mindestens eine Kachel wählen. Die Auswahl lässt sich später nicht ändern;
   „alle, auch künftige“ gibt es nicht.
4. **Ablauf:** ein Datum, höchstens so weit wie die Installation zulässt.
5. Erzeugen. Liegt Ihre Anmeldung länger zurück, fragt OPAA erst nach Passwort oder Anmeldung.
6. **Den Wert sofort übernehmen.** Er wird genau einmal angezeigt, zusammen mit fertigen
   Ausschnitten für die Einrichtung ([Ein KI-Werkzeug einrichten](ki-werkzeug-einrichten.md)).

Danach steht das Token in Ihrer Liste mit Bibliotheken, Zustand, Ablauf und „zuletzt benutzt“
(nur das Datum).

**Widerrufen** wirkt sofort und endgültig: Der nächste Aufruf des Werkzeugs wird abgewiesen. Ein
verlorener Wert lässt sich nicht wieder anzeigen, auch nicht durch die Systemverwaltung: neues Token
erzeugen, altes widerrufen.

**Ist keine Bibliothek wählbar**, fehlt entweder Ihr Leserecht oder die Freigabe der Bibliothek für
externe KI-Werkzeuge. Beides entscheidet, wer die Bibliothek verwaltet.

**Gut zu wissen:**

- OPAA meldet jedes neue Token per Mail. Haben Sie es nicht selbst erzeugt: widerrufen und die
  Systemverwaltung informieren.
- Vor dem Ablauf erinnert eine Mail.
- Der Wert gehört in keine Datei, die in eine Versionsverwaltung wandert.
