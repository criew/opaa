# Benutzer-Frontends

## Motivation

Benutzer interagieren mit OPAA über verschiedene Kanäle, je nach Workflow und Präferenz. Ein Entwickler bevorzugt möglicherweise eine Kommandozeilen- oder IDE-Integration, während eine Führungskraft ein Web-Dashboard nutzt. Support-Teams möchten möglicherweise Integration mit ihrer Chat-Plattform (Mattermost), während Datenanalysten den REST-API-Zugang bevorzugen.

Dieses Feature stellt sicher, dass OPAA dort zugänglich ist, wo Benutzer bereits arbeiten, was Reibung reduziert und die Akzeptanz erhöht.

---

## Überblick

OPAA bietet drei primäre Schnittstellenkategorien:

1. **Web-Schnittstelle** — Browserbasierter Chat und Dokument-Browser
2. **Chat-Plattform-Integrationen** — Mattermost, RocketChat, Signal, Slack-kompatibel
3. **REST-API** — Programmatischer Zugang für benutzerdefinierte Integrationen

Alle Schnittstellen teilen:
- Einheitliche Authentifizierung (SSO, Token-basiert)
- Gemeinsames Berechtigungsmodell
- Konsistentes Antwortformat
- Quelldokument-Angabe
- Dokument-Upload-Fähigkeit (Dateien verarbeitet und indiziert)

---

## Web-Schnittstelle

### Benutzererfahrung

Die Web-Schnittstelle ist eine browserbasierte Chat-Anwendung mit Dokument-Browse-Fähigkeiten.

**Kern-Screens:**
- **Chat-Screen:** Fragen stellen, Antworten mit Quellen sehen
- **Dokument-Browser:** Indizierte Dokumente suchen und durchsuchen
- **Meine Dokumente:** Persönlicher Workspace mit Upload-, Verwaltungs- und Teilungsfunktionen
- **Verlauf:** Vergangene Gespräche und Suchen anzeigen
- **Einstellungen:** Benutzerpräferenzen, API-Tokens verwalten

### Features

#### Fragen stellen
Benutzer geben eine natürlichsprachige Frage ein. Das System antwortet mit:
- Einer generierten Antwort
- Liste der Quelldokumente (mit Links)
- Konfidenz-Score
- Option, Antwort mit anderen Parametern neu zu generieren
- Möglichkeit, in Quelldokumenten zu vertiefen

**Beispiel-Interaktion:**
```
Benutzer: "Was ist unsere Richtlinie zur Remote-Arbeit?"

OPAA-Antwort:
"Laut unseren HR-Richtlinien ist Remote-Arbeit an 3 Tagen
pro Woche mit Genehmigung des Vorgesetzten möglich. Siehe:
- HR-Handbuch (Abschnitt 4.2)
- Remote-Arbeit-Richtlinie 2024
- Vorgesetztengenehmigungsprozess"
```

#### Dokument-Browser
- Alle indizierten Dokumente durchsuchen
- Dokumente inline in der Vorschau anzeigen
- Vollständige Dokumente herunterladen
- Anzeigen, wann das Dokument zuletzt indiziert wurde
- Indizierungsstatus anzeigen (ausstehend, indiziert, fehlgeschlagen)

#### Gesprächsverwaltung
- Gespräche im Workspace speichern
- Gesprächs-Links mit Kollegen teilen
- Gespräch als PDF oder Markdown exportieren
- Chathistorie löschen

#### Suchfilter
- Nach Dokumenttyp filtern (Confluence-Seite, E-Mail, PDF)
- Nach Indizierungsdatum filtern
- Nach Workspace/Projekt filtern
- Nach Konfidenz-Score filtern

#### Dokument-Upload
- Drag-and-Drop-Datei-Upload-Bereich in der persönlichen Workspace-Ansicht
- Multi-Datei-Upload-Unterstützung (Batch)
- Upload-Fortschrittsanzeige mit Dateivalidierungs-Feedback
- Unterstütztes Format-Erkennung und Dateigröße-Validierung
- Nach dem Upload: Dokument erscheint in "Meine Dokumente" innerhalb von Sekunden
- Indizierungsstatus angezeigt (verarbeitung, indiziert, fehlgeschlagen)
- Schnell-Teilen-Aktion: Ziel-Workspace(s) direkt nach dem Upload auswählen

#### Dokumentenverwaltung (Meine Dokumente)
- Listenansicht aller persönlich hochgeladenen Dokumente
- Sortieren nach Datum, Name, Größe oder Indizierungsstatus
- Hochgeladene Dokumente löschen
- Anzeigen, mit welchen Workspaces ein Dokument geteilt ist
- Dokumente mit Team-Workspaces teilen/entteilen
- Erneutes Hochladen (neue Version) eines vorhandenen Dokuments

### Konfiguration

Administratoren können anpassen:
- UI-Thema (Hell-/Dunkelmodus)
- Benutzerdefiniertes Branding (Logo, Farben)
- Gesprächs-Aufbewahrungsrichtlinie
- Ob Abfragen geloggt werden sollen
- API-Dokumentationsanzeige

---

## Chat-Plattform-Integrationen

### Unterstützte Plattformen

OPAA bietet native Plugins für:
- **Mattermost** — Selbst gehostete Team-Kommunikation
- **Slack** — Weit verbreitete Team-Messaging-Plattform
- **Telegram** — Cloud-basiertes Messaging mit Bot-API
- **RocketChat** — Open-Source-Chat-Plattform
- **Signal** — Sicheres Messaging (über Bot-API)
- **WhatsApp** — Business-Messaging (über WhatsApp Business API)
- **Benutzerdefinierte Chat-Bots** — Über REST-API (für proprietäre Systeme)

### Benutzer-Interaktionsmuster

Benutzer erwähnen den Bot und stellen eine Frage:

```
@opaa-bot Was ist unser Genehmigungsprozess für neue Tools?

OPAA-Antwort:
"Laut unseren Richtlinien: Alle neuen Tools müssen durch
Sicherheits-Review. Siehe: Tool-Genehmigungsprozess (aktualisiert Jan 2024)"
```

### Features

#### Gesprächsmodus
- Folgefragen im selben Thread
- Multi-Turn-Gespräche
- Kontextbewusstsein (erinnert sich an vorherige Fragen)
- Möglichkeit, letzte Antwort neu zu generieren

#### Rich-Message-Formatierung
- Markdown-Unterstützung
- Links zu Quelldokumenten (mit Vorschauen wenn möglich)
- Eingebettete Dokumenten-Ausschnitte
- Code-Block-Unterstützung (für technische Dokumentation)

#### Slash-Befehle
```
/opaa ask <frage>           — Frage stellen
/opaa search <suchbegriff>  — Volltextsuche
/opaa upload <anhang>       — Angehängte Datei in Meine Dokumente hochladen
/opaa share <dok> <workspace> — Dokument mit Workspace teilen
/opaa my-docs               — Aktuelle Uploads in Meine Dokumente auflisten
/opaa config                — Workspace-Einstellungen anzeigen
/opaa feedback <nachricht>  — Letzte Antwort bewerten
/opaa sources               — Quelldokumente der letzten Antwort anzeigen
```

#### Reaktionen & Feedback
Benutzer können auf Antworten mit Daumen hoch oder runter reagieren. Das System:
- Verfolgt Antwortqualität
- Ermöglicht Modellverbesserung im Laufe der Zeit
- Benachrichtigt Admins über möglicherweise schlechte Antworten

#### Datei-Upload über Chat
- Benutzer hängt eine Datei an eine Nachricht an, die den Bot erwähnt
- Bot bestätigt Empfang und beginnt Verarbeitung
- Benachrichtigung, wenn Indizierung abgeschlossen ist
- Datei wird standardmäßig im persönlichen Workspace des Benutzers gespeichert
- Benutzer kann Ziel-Workspace angeben: "@opaa-bot in Engineering hochladen"

#### Nachrichten-Threading
Alle Gespräche finden in einem einzigen Thread statt:
- Ursprüngliche Frage
- OPAAs Antwort
- Klärende Folgefragen
- Feedback-Reaktionen

### Konfiguration

Administratoren richten ein:
- Bot-Authentifizierung (Token, Webhook-URL)
- Welche Kanäle auf OPAA zugreifen können
- Workspace-Mapping (Mattermost-Team → OPAA-Workspace)
- Antwortformat (knapp vs. detailliert)
- Welche Mattermost-Teams welche Dokumente sehen

---

## REST-API

### Zweck

Für Entwickler stellt OPAA eine REST-API bereit für:
- Benutzerdefinierte Frontend-Entwicklung
- Integration mit vorhandenen Tools (Zapier, IFTTT, usw.)
- Programmatische Batch-Abfragen
- Aufbau spezialisierter Schnittstellen

### Kern-Endpunkte

#### Frage stellen
```
POST /api/v1/ask
{
  "question": "string",
  "workspace": "string (optional)",
  "include_sources": "boolean",
  "max_results": "integer",
  "model_config": { "temperature": 0.7 }
}

Antwort:
{
  "answer": "string",
  "sources": [
    {
      "id": "doc-123",
      "title": "string",
      "excerpt": "string",
      "url": "string",
      "confidence": 0.95
    }
  ],
  "metadata": {
    "query_time_ms": 150,
    "sources_searched": 1500,
    "model_used": "gpt-4"
  }
}
```

#### Dokumente suchen
```
GET /api/v1/search?q=<abfrage>&type=<filter>&limit=20

Gibt Liste von Dokumenten zurück, die der Abfrage entsprechen mit:
- Dokument-Metadaten
- Vorschau/Auszug
- Zeitstempel der letzten Indizierung
```

#### Dokumentdetails abrufen
```
GET /api/v1/documents/<id>

Gibt zurück:
- Vollständiger Dokumentinhalt (oder Chunk-Ansicht)
- Metadaten
- Verwandte Dokumente
- Download-Links
```

#### Dokument hochladen
```
POST /api/v1/documents/upload
Content-Type: multipart/form-data

Felder:
  file: <binary>
  workspace: "string (optional, Standard: persönlicher Workspace)"
  tags: ["string"] (optional)
  description: "string" (optional)

Antwort:
{
  "document_id": "upload-456",
  "filename": "design-review.pdf",
  "workspace_id": "personal-user-123",
  "status": "processing",
  "storage_path": "s3://opaa-uploads/user-123/design-review.pdf",
  "estimated_index_time_seconds": 30
}
```

#### Dokument mit Workspace teilen
```
POST /api/v1/documents/{id}/share
{
  "target_workspace": "workspace-eng",
  "action": "share"
}

Antwort:
{
  "document_id": "upload-456",
  "shared_to": ["workspace-eng", "workspace-arch"],
  "status": "shared"
}
```

#### Teilen mit Workspace aufheben
```
POST /api/v1/documents/{id}/share
{
  "target_workspace": "workspace-eng",
  "action": "unshare"
}

Antwort:
{
  "document_id": "upload-456",
  "shared_to": ["workspace-arch"],
  "status": "unshared"
}
```

#### Eigene hochgeladene Dokumente auflisten
```
GET /api/v1/documents/my-uploads?status=indexed&limit=20

Antwort:
{
  "documents": [
    {
      "id": "upload-456",
      "filename": "design-review.pdf",
      "uploaded_at": "2026-02-16T10:30:00Z",
      "status": "indexed",
      "shared_to": ["workspace-eng"],
      "file_size_bytes": 2048576
    }
  ],
  "total": 42
}
```

#### Feedback
```
POST /api/v1/feedback
{
  "query_id": "string",
  "rating": "positive|negative|neutral",
  "comment": "optional string"
}
```

#### Rate Limiting
- Standardstufe: 100 Anfragen/Minute
- Premium-Stufe: 1.000 Anfragen/Minute
- Batch-Verarbeitung: 10.000 Anfragen/Tag

### Authentifizierung

Alle API-Anfragen erfordern Authentifizierung:
- **Token-basiert:** Bearer-Token im Authorization-Header
- **OAuth 2.0:** Für Web-Anwendungen
- **Service-Accounts:** Für Server-zu-Server-Integration

Beispiel:
```
Authorization: Bearer opaa_token_abcd1234efgh5678
```

### Anwendungsfälle

**Benutzerdefinierte Chat-Schnittstelle für domänenspezifische Zielgruppe**
Ein Kundensupport-Portal bettet die OPAA-API ein, um Kunden das Durchsuchen Ihrer Wissensbasis zu ermöglichen, ohne auf interne Dokumente zuzugreifen.

**Batch-Verarbeitung**
Ein Datenteam führt täglich Abfragen aus, um Berichte zu erstellen:
```
POST /api/v1/batch
[
  { "question": "Wie viele neue Features wurden letztes Quartal veröffentlicht?" },
  { "question": "Was waren die Top-3-Bug-Reports?" }
]
```

**Drittanbieter-Integration**
Zapier-Integration: "Wenn ein Support-Ticket erstellt wird, OPAA nach relevanten Antworten fragen und an das Ticket anhängen."

---

## Schnittstellen-übergreifende Features

### Einheitliche Authentifizierung

Alle Frontends verwenden dieselbe Authentifizierung:
- Single Sign-On (SSO)-Unterstützung (OIDC, SAML)
- Token-basierte Authentifizierung
- Session-Verwaltung
- API-Schlüssel-Verwaltung

### Gemeinsames Berechtigungsmodell

Unabhängig vom Frontend:
- Benutzer können nur Dokumente in ihrem Workspace sehen
- Berechtigungen auf Dokumentenebene respektiert
- API-Tokens erben Benutzerberechtigungen
- Audit-Logs verfolgen alle Zugriffe

### Konsistenz des Antwortformats

Jede Antwort enthält:
- Die eigentliche Antwort/Daten
- Quelldokumente mit Links
- Metadaten (Konfidenz, Abrufzeit, verwendetes Modell)
- Vorschlag für nächste Schritte (verwandte Fragen, Dokumente)

### Suchverhalten

Über alle Schnittstellen:
- Semantische Suche (nicht nur Schlüsselwort-Matching)
- Re-Ranking nach Relevanz
- Konfidenz-Scores angezeigt
- Option zum Filtern nach Dokumenttyp, Datum, Quelle

---

## Design-Überlegungen

### Barrierefreiheit
- WCAG-2.1-AA-Compliance für Web-Schnittstelle
- Tastaturnavigation-Unterstützung
- Screenreader-Kompatibilität
- Chat-Befehle für Benutzer, die Kommandozeilen-Stil bevorzugen

### Leistung
- Web-Chat lädt in < 2 Sekunden
- Antworten werden an den Benutzer gestreamt (nicht auf vollständige Generierung warten)
- Suchergebnisse in < 500 ms zurückgegeben
- API-Antworten in < 1 Sekunde für typische Abfragen

### Einschränkungen & Sonderfälle

**Was wenn eine Frage keine relevanten Quellen hat?**
- System gibt Konfidenz-Score von 0 zurück
- Benutzer wird explizit mitgeteilt "Ich konnte keine relevanten Informationen finden"
- System schlägt vor, die Frage zu verfeinern
- Option, alle Dokumente als Fallback zu durchsuchen

**Was wenn mehrere Dokumente widersprüchliche Informationen haben?**
- System zeigt alle relevanten Quellen und lässt Benutzer entscheiden
- Markiert widersprüchliche Abschnitte
- Gibt Score für Relevanz jeder Quelle an

**Was mit sehr langen Dokumenten?**
- System zeigt Auszug, nicht vollständigen Text
- Benutzer kann vollständiges Dokument herunterladen
- Chunking-Strategie dem Benutzer erklärt (Transparenz)

---

## Integrationspunkte

- **Authentifizierung:** Integriert mit organisatorischem SSO
- **Benutzerverzeichnis:** Synchronisiert mit LDAP/Active Directory für Benutzer-/Rollenverwaltung
- **Analytics:** Exportiert Interaktionsdaten in Business-Intelligence-Tools
- **Logging:** Sendet Abfrage-Logs an SIEM-Systeme
- **Dokumentenquellen:** Zieht Dokumente aus der Daten-Indizierungs-Pipeline

---

## Offene Fragen / Zukünftige Überlegungen

- Sollte die Web-Schnittstelle Spracheingabe unterstützen?
- Sollten Mobile-Apps nativ oder als Progressive Web App gebaut werden?
- Sollten wir SMS/WhatsApp-Integration für Umgebungen mit geringer Bandbreite unterstützen?
- Sollten Chat-Integrationen reiche Interaktivität unterstützen (Buttons, Formulare)?
- Sollte es ein IDE-Plugin geben (VS Code, IntelliJ)?

---

## Erfolgs-Metriken

- **Akzeptanz:** % der Organisation, die OPAA mindestens wöchentlich nutzt
- **Abfragequalität:** % der von Benutzern positiv bewerteten Antworten
- **Leistung:** P95-Antwortzeit < 2 Sekunden
- **Verfügbarkeit:** 99,9% Verfügbarkeit für Web-Schnittstelle
- **API-Nutzung:** Anzahl der Drittanbieter-Integrationen, die REST-API nutzen
