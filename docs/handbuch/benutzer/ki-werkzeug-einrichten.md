# KI-Werkzeug einrichten

Ihr KI-Werkzeug sucht danach über den MCP-Server von OPAA in Ihren Bibliotheken. Sie brauchen zwei
Angaben: die Adresse `https://<host>/mcp` und den Wert Ihres [Zugangstokens](zugangstoken.md). Der
Dialog nach dem Erzeugen des Tokens zeigt die Angaben schon mit der Adresse Ihrer Installation.

**OpenCode**, in `~/.config/opencode/opencode.json`, mit dem Tokenwert in der Umgebungsvariablen
`OPAA_TOKEN`:

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

`"oauth": false` sorgt dafür, dass OpenCode bei einem abgelaufenen Token den Grund nennt, statt eine
Anmeldung zu versuchen.

**Andere MCP-fähige Werkzeuge** brauchen dieselben zwei Angaben: Adresse als HTTP-Server und den
Kopf `Authorization: Bearer <Tokenwert>`.

**Eigenes Skript:** dieselbe Suche als REST-Aufruf mit demselben Kopf:

```bash
curl -s https://<host>/api/v1/search -H "Authorization: Bearer opaa_pat_…" \
  -H "Content-Type: application/json" -d '{"question":"Fristen bei der Anhörung"}'
```

**Gut zu wissen:**

- Den Tokenwert nie in eine Projektdatei schreiben, die in die Versionsverwaltung wandert.
- Verbindet nichts, fehlt meist eine Voraussetzung: Kanal eingeschaltet, Bibliothek freigegeben,
  Token gültig, Netzbereich zugelassen.
