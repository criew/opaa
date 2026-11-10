# KI-Werkzeug einrichten

**Ziel:** Ein KI-Werkzeug wie OpenCode durchsucht Ihre Bibliotheken über OPAA.
{: .kopf }

Das Werkzeug bekommt nur Fundstellen und nie mehr, als Sie selbst lesen dürfen. Voraussetzung: Die
Systemverwaltung hat externe KI-Werkzeuge eingeschaltet, und die Bibliothek ist
[dafür freigegeben](arbeitsmittel-teilen.md#für-externe-ki-werkzeuge-freigeben).

## Token anlegen

1. „Einstellungen“, „Zugangstokens“, „Token erzeugen“.
2. Name (etwa „OpenCode auf dem Dienstrechner“), Bibliotheken und Ablauf wählen. Die Bibliotheken
   lassen sich später nicht ändern.
3. Erzeugen und **den Wert sofort kopieren**. Er wird nur einmal angezeigt, zusammen mit fertigen
   Ausschnitten für einige Werkzeuge.

Widerrufen geht in derselben Liste und wirkt sofort. Ein verlorener Wert lässt sich nicht wieder
anzeigen: neues Token erzeugen, altes widerrufen. Eine Bibliothek, die Sie nicht mehr lesen dürfen,
steht in der Liste als „(kein Leserecht)“ statt mit ihrem Namen.

Wie viele gültige Tokens Sie zugleich haben dürfen, ist begrenzt; ist die Grenze erreicht, erst ein
altes widerrufen ([Konfiguration](../referenz/konfiguration.md)). Das Kontingent an Anfragen je
Stunde gilt je Token und zugleich für alle Ihre Tokens zusammen.

## Beispiel OpenCode

In `~/.config/opencode/opencode.json`, den Tokenwert in der Umgebungsvariablen `OPAA_TOKEN`:

```json
{
  "mcp": {
    "opaa": {
      "type": "remote",
      "url": "https://<host>/mcp",
      "headers": { "Authorization": "Bearer {env:OPAA_TOKEN}" },
      "oauth": false
    }
  }
}
```

Andere MCP-fähige Werkzeuge brauchen dieselben Angaben: Adresse `https://<host>/mcp` und den Kopf
`Authorization: Bearer <Tokenwert>`.

**Gut zu wissen:**

- Der Tokenwert gehört in keine Datei, die in eine Versionsverwaltung wandert.
- Ein neues Passwort beendet alle Ihre Tokens.
- Jedes neue Token meldet OPAA per Mail. Nicht selbst erzeugt? Widerrufen und die Systemverwaltung
  informieren.
- Verbindet nichts, fehlt meist die Freigabe der Bibliothek, ein gültiges Token oder ein
  zugelassener Netzbereich.
