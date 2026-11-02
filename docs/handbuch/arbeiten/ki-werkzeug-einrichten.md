# Ein KI-Werkzeug einrichten

> **Entwurf** — gegen den Code geprüft, vom Maintainer noch nicht abgenommen.

> **Wer darf das:** wer ein gültiges [Zugangstoken](zugangstoken.md) hat und an einem Arbeitsplatz
> in den zugelassenen Netzbereichen sitzt. Ab Werk sind externe KI-Werkzeuge abgeschaltet; die
> Systemverwaltung schaltet sie ein.

Ihr KI-Werkzeug sucht danach über den MCP-Server von OPAA in Ihren Bibliotheken. Sie brauchen zwei
Angaben: die Adresse `https://<host>/mcp` und den Tokenwert. Der Dialog nach dem Erzeugen des Tokens
zeigt die Ausschnitte schon mit der Adresse Ihrer Installation.

**Claude Code**, für alle Projekte:

```bash
claude mcp add --transport http --scope user opaa https://<host>/mcp \
  --header "Authorization: Bearer opaa_pat_…"
```

Ohne `--scope user` landet der Eintrag im Projekt, mit `--scope project` samt Token in einer
Datei, die eingecheckt wird. Prüfen mit `claude mcp list`, entfernen mit
`claude mcp remove opaa --scope user`.

**VS Code mit Copilot**, in der Benutzerkonfiguration (Befehlspalette, „MCP: Open User
Configuration“). VS Code fragt den Wert beim ersten Start ab und speichert ihn sicher:

```json
{
  "inputs": [{ "type": "promptString", "id": "opaa-token",
               "description": "OPAA-Zugangstoken", "password": true }],
  "servers": {
    "opaa": { "type": "http", "url": "https://<host>/mcp",
              "headers": { "Authorization": "Bearer ${input:opaa-token}" } }
  }
}
```

**Cursor**, in `~/.cursor/mcp.json`, mit dem Wert in der Umgebungsvariablen `OPAA_TOKEN`:

```json
{
  "mcpServers": {
    "opaa": { "url": "https://<host>/mcp",
              "headers": { "Authorization": "Bearer ${env:OPAA_TOKEN}" } }
  }
}
```

**OpenCode**, in `~/.config/opencode/opencode.json`: Eintrag `"type": "remote"` mit derselben Adresse,
`"headers": { "Authorization": "Bearer {env:OPAA_TOKEN}" }` und **`"oauth": false`**. Ohne diese
Angabe versucht OpenCode bei einem abgelaufenen Token eine Anmeldung, statt den Grund zu nennen.

**Eigenes Skript:** dieselben Lesewege als REST-Aufruf mit demselben Kopf, etwa

```bash
curl -s https://<host>/api/v1/search -H "Authorization: Bearer opaa_pat_…" \
  -H "Content-Type: application/json" -d '{"question":"Fristen bei der Anhörung"}'
```

Das Feld heißt `question`, nicht `query`.

**Gut zu wissen:**

- Projektdateien wie `.vscode/mcp.json`, `.cursor/mcp.json` oder ein `opencode.json` im Projekt
  wandern in die Versionsverwaltung: den Wert dort nie eintragen.
- Die Ausschnitte wurden am 18.09.2026 gegen die Herstellerdokumentation geprüft; diese Werkzeuge
  ändern sich, im Zweifel gilt deren Dokumentation.
- Verbindet nichts, fehlt meist eine der Voraussetzungen: Kanal eingeschaltet, Bibliothek
  freigegeben, Token gültig, Netzbereich zugelassen.
