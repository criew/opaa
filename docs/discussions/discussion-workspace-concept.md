# Discussion: Workspace-Konzept und Berechtigungsmodell

**Thema:** Konzeptionelles Design des Workspace-Modells, Dokument-Sharing, Verknüpfung von Indexing-Quellen mit Workspaces, und Rollen-Hierarchie

**Kontext:** Die Feature-Spezifikationen (`access-control-workspaces.md`, `data-indexing-rag.md`) beschreiben Workspaces und Berechtigungen auf Feature-Ebene. Dieses Dokument klärt offene konzeptionelle Fragen und hält Design-Entscheidungen fest, bevor die Feature-Specs aktualisiert werden.

---

## 1. Workspace-Modell: Flach, keine Verschachtelung

### Entscheidung

Workspaces sind **flach** — es gibt keine Parent-Child-Beziehungen oder Hierarchie zwischen Workspaces.

### Begründung

Projekte, die mehrere Teams umfassen, werden durch einen **gemeinsamen Workspace** abgebildet, dem alle beteiligten Teams beitreten. Das vermeidet die Komplexität von verschachtelten Workspaces mit Vererbungslogik.

### Beispiel: Projekt mit mehreren Teams

```
Projekt "Phoenix":
  → Workspace "Phoenix" (gemeinsam)
      Mitglieder: Team Frontend + Team Backend + Team QA

Teamspezifisches Wissen:
  → Workspace "Frontend-Team"
      Mitglieder: Nur Frontend-Entwickler
  → Workspace "Backend-Team"
      Mitglieder: Nur Backend-Entwickler

User "Alice" (Frontend-Entwicklerin):
  Workspaces: [My Documents, Company, Phoenix, Frontend-Team]
  → Suche liefert Ergebnisse aus allen vier Workspaces
```

Projektwissen (Architekturentscheidungen, Sprint-Ergebnisse, übergreifende Doku) liegt im "Phoenix"-Workspace. Teamspezifisches Wissen (Frontend-Patterns, Backend-API-Konventionen) liegt in den jeweiligen Team-Workspaces.

### Workspace-Typen

| Typ | Erstellung | Mitglieder | Beispiel |
|-----|-----------|------------|---------|
| **Personal** | Auto-created pro User | Nur der Owner | "My Documents (Alice)" |
| **Shared** | Manuell durch Admin/System-Admin | Beliebig viele | "Engineering", "Phoenix", "Company" |

Beide Typen sind technisch identisch (gleiche Entität), unterscheiden sich aber im Verhalten:
- Personal Workspaces können keine zusätzlichen Mitglieder haben
- Personal Workspaces werden nicht gelöscht, sondern bei Offboarding deaktiviert
- Der Typ wird als Attribut am Workspace gespeichert (`type: personal | shared`)

---

## 2. Rollen-Hierarchie: System-Admin als neue Ebene

### Entscheidung

Es gibt eine **System-Admin-Rolle** oberhalb der Workspace-Rollen, die systemweite Verwaltungsaufgaben übernimmt.

### Zwei-Ebenen-Modell

```
Ebene 1: System-Administration
  └─ System-Admin
       - Konnektoren erstellen und konfigurieren
       - Source-Mappings definieren (Quell-Untereinheit → Workspace)
       - Workspaces erstellen und löschen
       - User-Directory-Sync konfigurieren
       - Globale Einstellungen verwalten

Ebene 2: Workspace-Administration (unverändert aus Feature-Spec)
  └─ Owner → Admin → Editor → Viewer
       - Verwalten Dokumente und Mitglieder innerhalb ihres Workspaces
```

### Abgrenzung: Was darf wer?

| Aktion | Viewer | Editor | Admin | Owner | System-Admin |
|--------|--------|--------|-------|-------|-------------|
| Dokumente suchen/lesen | Workspace | Workspace | Workspace | Workspace | Alle |
| Dokumente hochladen (manuell) | - | Workspace | Workspace | Workspace | Alle |
| Dokumente teilen (Workspace→Workspace) | - | Wenn Editor in beiden | Wenn Editor in beiden | Wenn Editor in beiden | Alle |
| Workspace-Mitglieder verwalten | - | - | Workspace | Workspace | Alle |
| Workspace erstellen | - | - | - | - | Ja |
| Konnektoren konfigurieren | - | - | - | - | Ja |
| Source-Mappings definieren | - | - | - | - | Ja |
| User-Directory-Sync | - | - | - | - | Ja |

### Wichtig: Dokumentenfluss

- **System-Admins** konfigurieren Konnektoren und definieren, welche Quell-Untereinheiten in welche Workspaces indiziert werden. Das ist der primäre Weg, wie Dokumente ins System gelangen (automatisch, bulk, geplant).
- **Normale User** (Editor-Rolle) können nur manuell einzelne Dokumente hochladen — in ihren Personal Workspace oder in Team-Workspaces, in denen sie Editor sind.

---

## 3. Dokument-Sharing: Workspace-zu-Workspace

### Entscheidung

Das Sharing-Modell wird von "Personal → Team" auf **beliebige Workspace-Paare** generalisiert. Der Mechanismus bleibt identisch.

### Voraussetzungen für Sharing

Der teilende User muss **Editor-Rolle in beiden Workspaces** haben (Quelle UND Ziel). Das stellt sicher, dass:
- Der User berechtigt ist, Inhalte des Quell-Workspaces weiterzugeben
- Der User berechtigt ist, Inhalte zum Ziel-Workspace hinzuzufügen

### Mechanismus (unverändert)

1. User wählt ein Dokument im Quell-Workspace aus
2. User wählt Ziel-Workspace (muss dort Editor sein)
3. Die indizierten Chunks des Dokuments erhalten zusätzliche `workspace_id`-Tags für den Ziel-Workspace
4. Mitglieder des Ziel-Workspaces finden das Dokument in Suchergebnissen
5. Das Dokument bleibt im Home-Workspace (Single Source of Truth)

### Sharing-Szenarien

```
Szenario 1: Personal → Team (wie bisher)
  Alice teilt "Notizen.md" aus "My Documents" → "Frontend-Team"
  Voraussetzung: Alice ist Editor in "Frontend-Team"

Szenario 2: Team → Team
  Alice teilt "API-Spec.md" aus "Backend-Team" → "Frontend-Team"
  Voraussetzung: Alice ist Editor in BEIDEN Workspaces

Szenario 3: Team → Projekt
  Alice teilt "Sprint-Ergebnis.md" aus "Frontend-Team" → "Phoenix"
  Voraussetzung: Alice ist Editor in BEIDEN Workspaces
```

### Rücknahme des Sharings

- **Dokument-Owner** kann Sharing jederzeit widerrufen
- **Workspace-Admin des Ziels** kann ein geteiltes Dokument aus seinem Workspace entfernen
- Beim Widerruf verlieren die Chunks die `workspace_id`-Tags des Ziel-Workspaces

---

## 4. Indexing-Quellen und Workspace-Mapping

### Entscheidung

Konnektoren werden auf **Instanz-Ebene** konfiguriert. Innerhalb eines Konnektors werden **Quell-Untereinheiten** jeweils genau **einem** Workspace zugeordnet.

### Zwei-Ebenen-Konfiguration

```
Ebene 1: Konnektor (Instanz)
  Name: "Confluence Produktion"
  Typ: confluence
  URL: https://wiki.company.com
  Credentials: service-account / API-token
  Schedule: Täglich 2:00 Uhr

Ebene 2: Source-Mappings (pro Quell-Untereinheit)
  Confluence Space "ENG"  → Workspace "Engineering"
  Confluence Space "MKT"  → Workspace "Marketing"
  Confluence Space "PROJ" → Workspace "Phoenix"
  Confluence Space "ALL"  → Workspace "Company"
```

### Mapping-Regeln

- **1:1** — Jede Quell-Untereinheit mappt auf genau einen OPAA-Workspace
- **Nicht gemappte Untereinheiten** werden ignoriert (nicht indiziert)
- **Mehrere Konnektoren** können in denselben Workspace indizieren (z.B. Confluence Space "ENG" + Dateisystem-Ordner `/docs/engineering/` beide → "Engineering")

### Konnektor-Typen und ihre Untereinheiten

| Konnektor-Typ | Instanz-Ebene | Quell-Untereinheit | Mapping-Ziel |
|---------------|---------------|-------------------|-------------|
| Confluence | Server-URL | Space | Workspace |
| Dateisystem | Basis-Pfad | Unterordner (1. Ebene) | Workspace |
| HTTP Directory | Basis-URL | Unterverzeichnis (1. Ebene) | Workspace |
| Jira | Server-URL | Projekt | Workspace |
| Git | Repository-URL | Repository / Branch | Workspace |
| E-Mail (IMAP) | Server-URL | Ordner / Label | Workspace |

### Wer konfiguriert was?

- **System-Admin:** Erstellt Konnektoren, definiert Source-Mappings, legt Zeitpläne fest
- **Workspace-Admin:** Kann sehen, welche Quellen in seinen Workspace indiziert werden (read-only)
- **Editor/Viewer:** Kein Zugriff auf Konnektor-Konfiguration

---

## 5. Suche und Berechtigungen

### Übergreifende Suche

Wenn ein User eine Frage stellt:

1. **Embedding** der Frage erzeugen
2. **Vektor-Suche** über alle Chunks (workspace-übergreifend)
3. **Permission-Filter:** Nur Chunks behalten, deren `workspace_id` einem Workspace des Users entspricht
4. **Re-Ranking und Deduplizierung**
5. **Ergebnis:** User sieht Treffer aus allen seinen Workspaces, inkl. geteilter Dokumente

### Geteilte Dokumente in Suchergebnissen

Ein Dokument, das aus "Backend-Team" → "Phoenix" geteilt wurde:
- Erscheint für Phoenix-Mitglieder als Treffer im Kontext von "Phoenix"
- Erscheint für Backend-Team-Mitglieder als Treffer im Kontext von "Backend-Team"
- Der Workspace-Name wird im Suchergebnis angezeigt

### Performance-Überlegung

Der Permission-Filter arbeitet auf `workspace_id`-Ebene. Bei der Suche wird die Menge der Workspace-IDs des Users als Filter in die Vektor-Suche übergeben (Metadaten-Filter in pgvector). Das ist effizient, weil:
- Die Anzahl der Workspaces pro User typischerweise klein ist (< 20)
- pgvector Metadaten-Filter auf indexierten Spalten unterstützt

---

## 6. Zusammenfassung: Datenmodell (konzeptionell)

```
User
  ├── id, name, email, auth_provider_id
  ├── system_role: USER | SYSTEM_ADMIN
  └── workspace_memberships: [WorkspaceMembership]

Workspace
  ├── id, name, description, type (personal | shared)
  ├── owner: User
  └── members: [WorkspaceMembership]

WorkspaceMembership
  ├── user: User
  ├── workspace: Workspace
  └── role: VIEWER | EDITOR | ADMIN | OWNER

Document
  ├── id, title, file_name, content_type, file_size
  ├── home_workspace: Workspace (wo das Dokument "lebt")
  ├── owner: User (wer es hochgeladen/erstellt hat)
  ├── source_type: CONNECTOR | USER_UPLOAD
  └── shared_to: [Workspace] (zusätzliche Workspaces)

Connector
  ├── id, name, type (confluence | filesystem | http | ...)
  ├── instance_url, credentials (encrypted)
  ├── schedule, enabled
  └── source_mappings: [SourceMapping]

SourceMapping
  ├── connector: Connector
  ├── source_unit: String (z.B. Confluence Space Key, Ordnerpfad)
  └── target_workspace: Workspace

DocumentChunk (im Vektor-Store)
  ├── chunk_id, document_id, chunk_index, chunk_text
  ├── embedding: vector
  └── workspace_ids: [workspace_id] (Home + geteilte Workspaces)
```

---

## 7. Offene Fragen

- **Storage-Quotas:** Soll es Speicherlimits pro Workspace oder pro User geben?
- **Dokument-Versionierung:** Soll eine neue Version eines bereits indizierten Dokuments erkannt werden?
- **Bulk-Sharing:** Soll man mehrere Dokumente auf einmal teilen können?
- **Sharing-Benachrichtigungen:** Soll der Ziel-Workspace benachrichtigt werden, wenn ein Dokument geteilt wird?
- **Konnektor-Permissions aus Quellsystem:** Sollen z.B. Confluence-Space-Permissions zusätzlich zu Workspace-Permissions berücksichtigt werden?
- **Workspace-Löschung:** Was passiert mit Dokumenten und Chunks, wenn ein Shared Workspace gelöscht wird?
- **Audit:** Sollen Sharing-Aktionen im Audit-Log erfasst werden?
