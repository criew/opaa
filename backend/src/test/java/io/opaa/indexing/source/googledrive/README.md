# Stufe 3: Google-Drive-Konnektor gegen ein echtes Workspace

`GoogleDriveWorkspaceIntegrationTest` prüft den Konnektor gegen ein echtes Google Workspace. Er
belegt die als „unsicher“ markierten Befunde aus ADR-0040, etwa das Fehlerbild über der
Exportgrenze. Die Suite läuft **nur lokal beim Maintainer**. Sie hat keinen CI-Eintrag, kein Label
und kein Secret. Sie liest nur: Sie legt nichts an, ändert nichts und löscht nichts.

## Einmalige Einrichtung

1. **GCP-Projekt anlegen:** In der Google Cloud Console ein eigenes Projekt für die Tests anlegen,
   etwa `opaa-drive-test`.
2. **Drive-API aktivieren:** „APIs & Dienste“ → „Bibliothek“ → „Google Drive API“ → „Aktivieren“.
3. **Dienstkonto anlegen:** „IAM & Verwaltung“ → „Dienstkonten“ → „Dienstkonto erstellen“. Das
   Konto braucht keine Projektrolle.
4. **Schlüssel erzeugen:** Beim Dienstkonto unter „Schlüssel“ → „Schlüssel hinzufügen“ → „JSON“.
   - Verbietet die Organisationsrichtlinie `iam.managed.disableServiceAccountKeyCreation` das,
     setzt der Administrator der Organisation für genau dieses Projekt eine Ausnahme.
   - Die Datei **außerhalb des Repositorys** ablegen, etwa unter `~/.config/opaa/gdrive-it.json`.
     Der Test bricht ab, wenn die Datei im Repository liegt.
5. **Testordner freigeben:** In Google Drive einen Ordner `OPAA-Test` anlegen und ihn für die
   Adresse des Dienstkontos (`…@…iam.gserviceaccount.com`) als **Betrachter** freigeben. Für eine
   geteilte Ablage das Dienstkonto stattdessen als Mitglied mit der Rolle „Betrachter“ aufnehmen.
   Domänenweite Delegation ist nicht nötig.

## Testdaten im Ordner `OPAA-Test`

Der Maintainer legt sie einmal von Hand an. Die Namen müssen genau so lauten:

| Eintrag | Art | Erwartung |
|---|---|---|
| `Protokoll` | Google Doc, kurzer Text | als `Protokoll.docx` exportiert |
| `Großes Protokoll` | Google Doc, Export als docx über 10 MB (etwa viele eingefügte Bilder oder sehr viel Text) | als Text exportiert, Protokolleintrag „Als Text exportiert“ |
| `Zahlen` | Google Sheet mit zwei Blättern | als `Zahlen.xlsx` exportiert |
| `Verknüpfung` | Verknüpfung auf `Protokoll` (Rechtsklick → „Verknüpfung hinzufügen“) | übersprungen, Sammelhinweis „Verknüpfungen übersprungen“ |
| `ohne-endung` | hochgeladene Textdatei ohne Dateiendung; Drive meldet `text/plain` | über den Medientyp erkannt und aufgenommen |
| `Unterordner/Notiz.txt` | Unterordner mit einer Textdatei | aufgenommen, Ordner gespiegelt |

## Start

```bash
# aus backend/
OPAA_GDRIVE_IT_KEY_FILE=~/.config/opaa/gdrive-it.json \
OPAA_GDRIVE_IT_FOLDER_ID=<ID des Ordners OPAA-Test> \
./gradlew googleDriveIntegrationTest
```

- Die Ordner-ID steht in der Adresse des Ordners hinter `/folders/`.
- Optional: `OPAA_GDRIVE_IT_DRIVE_ID` prüft zusätzlich eine geteilte Ablage mit denselben Testdaten.
- Optional: `OPAA_GDRIVE_IT_SUBJECT` prüft die domänenweite Delegation. Dafür muss die Client-ID des
  Dienstkontos in der Admin-Konsole unter „Sicherheit“ → „API-Steuerung“ → „Domainweite
  Delegation“ mit dem Scope `https://www.googleapis.com/auth/drive.readonly` eingetragen sein.
- Ohne `OPAA_GDRIVE_IT_KEY_FILE` überspringt die Task die Suite.

Schlüssel, Assertion und Zugriffstoken erscheinen weder in Logs noch in Reports oder
Fehlermeldungen. Der Test prüft die mitgeschnittenen Logzeilen am Ende jeder Methode. Fällt Google
aus, scheitert schon die Anmeldung. Das ist dann „nicht prüfbar“ und kein Befund über OPAA.

## Was der Lauf zusätzlich belegen soll

Die folgenden Befunde gehören danach als Nachtrag in ADR-0040:

- das Fehlerbild über der Exportgrenze (`403 exportSizeLimitExceeded`),
- ob `alt=media` auf einen anderen Host weiterleitet,
- ob der Strom `user` Änderungen geteilter Ablagen liefert,
- wie oft Ordneränderungen im Strom `user` einen Vollabgleich auslösen, besonders mit `subject`,
- ob der Deep Link `open?id=` zum richtigen Editor führt.
