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
| Eigene Uploads löschen | - | Ja | Ja | Ja | Alle |
| Beliebige Uploads löschen | - | - | Workspace | Workspace | Alle |
| Connector-Dokumente excluden | - | - | Workspace | Workspace | Alle |
| Workspace-Mitglieder verwalten | - | - | Workspace | Workspace | Alle |
| Workspace erstellen | - | - | - | - | Ja |
| Konnektoren konfigurieren | - | - | - | - | Ja |
| Source-Mappings definieren | - | - | - | - | Ja |
| User-Directory-Sync | - | - | - | - | Ja |

### Wichtig: Dokumentenfluss

- **System-Admins** konfigurieren Konnektoren und definieren, welche Quell-Untereinheiten in welche Workspaces indiziert werden. Das ist der primäre Weg, wie Dokumente ins System gelangen (automatisch, bulk, geplant).
- **Normale User** (Editor-Rolle) können nur manuell einzelne Dokumente hochladen — in ihren Personal Workspace oder in Team-Workspaces, in denen sie Editor sind.

---

## 3. Dokument-Sharing

### Entscheidung

Cross-Workspace Document Sharing wird als separates Feature in einer späteren Design-Phase behandelt. Das initiale Konzept (Editor-Rolle in beiden Workspaces reicht zum Teilen) hat ein fundamentales Sicherheitsproblem: Ein Editor könnte vertrauliche Dokumente in einen Workspace mit niedrigerem Vertraulichkeitsniveau teilen.

Details, Konzept und offene Sicherheitsfragen sind dokumentiert in: `docs/features/document-sharing.md`

### Dokumente entfernen und löschen

Die Möglichkeit, Dokumente zu entfernen oder zu löschen, hängt von der **Herkunft** des Dokuments ab:

| Dokument-Herkunft | Editor | Admin | Aktion |
|---|---|---|---|
| **Manueller Upload** | Eigene Uploads löschen | Alle Uploads im Workspace löschen | Dokument + Chunks werden permanent entfernt |
| **Connector-indiziert** | - | Dokument excluden | Dokument wird aus dem Index entfernt und beim nächsten Sync nicht erneut aufgenommen (siehe Exclude-Mechanismus) |

#### Exclude-Mechanismus für Connector-Dokumente

Connector-indizierte Dokumente können nicht einfach gelöscht werden, da sie beim nächsten Indexing-Lauf wieder auftauchen würden. Stattdessen können Workspace-Admins einzelne Dokumente **excluden**:

1. Admin markiert ein Dokument als "excluded" im Workspace
2. Das Dokument wird aus dem Index entfernt (Chunks gelöscht)
3. Bei zukünftigen Indexing-Läufen wird das Dokument übersprungen
4. Die Exclude-Liste wird pro Source-Mapping gespeichert
5. System-Admins können Excludes einsehen und aufheben

**Anwendungsfälle:**
- Irrelevante Dokumente, die den Workspace "verrauschen"
- Veraltete Dokumente, die noch im Quellsystem stehen, aber nicht mehr gefunden werden sollen
- Dokumente, die fälschlicherweise über das Source-Mapping in den Workspace gelangt sind

---

## 4. Indexing-Quellen und Workspace-Mapping

### Entscheidung

Ein **Konnektor** definiert den Typ und gemeinsame Konfiguration (Credentials, Schedule). Ein Konnektor hat eine oder mehrere **Quellen (Sources)**, die jeweils **einem oder mehreren** Workspaces zugeordnet werden. Konnektoren und Quellen werden unabhängig von Workspaces konfiguriert — Workspace-Admins können sehen, welche Quellen in ihren Workspace indizieren. Je nach Konnektor-Typ sieht eine Quelle unterschiedlich aus.

### Konnektor-Modell

Manche Konnektor-Typen haben eine natürliche Instanz-Ebene mit Untereinheiten (z.B. Confluence-Server mit Spaces). Andere haben keine gemeinsame Instanz — jede Quelle ist eigenständig (z.B. individuelle Dateisystem-Pfade oder URLs).

```
Beispiel 1: Confluence (Instanz mit Untereinheiten)
  Konnektor: "Confluence Produktion"
    Typ: confluence
    URL: https://wiki.company.com
    Credentials: service-account / API-token
    Schedule: Täglich 2:00 Uhr
    Quellen:
      Space "ENG"  → Workspaces: ["Engineering"]
      Space "MKT"  → Workspaces: ["Marketing"]
      Space "HR"   → Workspaces: ["HR", "Onboarding"]
      Space "ALL"  → Workspaces: ["Company"]

Beispiel 2: Dateisystem / Netzlaufwerk (je Pfad eine Quelle)
  Konnektor: "Netzlaufwerk Engineering"
    Typ: filesystem
    Schedule: Täglich 3:00 Uhr
    Quellen:
      Pfad "//fileserver/engineering/docs" → Workspaces: ["Engineering"]

  Konnektor: "Netzlaufwerk Marketing"
    Typ: filesystem
    Schedule: Wöchentlich
    Quellen:
      Pfad "//fileserver/marketing/guidelines" → Workspaces: ["Marketing"]

Beispiel 3: HTTP Directory (je URL eine Quelle)
  Konnektor: "Docs-Server Engineering"
    Typ: http
    Schedule: Täglich 4:00 Uhr
    Quellen:
      URL "https://docs.internal/engineering/" → Workspaces: ["Engineering"]
```

### Mapping-Regeln

- **1:N** — Jede Quelle kann auf einen oder mehrere OPAA-Workspaces gemappt werden. Dokumente aus dieser Quelle werden in alle zugeordneten Workspaces indiziert (ihre Chunks erhalten alle entsprechenden `workspace_ids`).
- **Nicht gemappte Untereinheiten** werden ignoriert (z.B. Confluence Spaces ohne Mapping werden nicht indiziert)
- **Mehrere Konnektoren** können in denselben Workspace indizieren (z.B. Confluence Space "ENG" + Netzlaufwerk-Pfad beide → "Engineering")

### Konnektor-Typen und ihre Quellen

| Konnektor-Typ | Gemeinsame Config (Konnektor) | Quelle (eine oder mehrere pro Konnektor) |
|---|---|---|
| Confluence | Server-URL, Credentials | Space-Key |
| Jira | Server-URL, Credentials | Projekt-Key |
| E-Mail (IMAP) | Server-URL, Credentials | Ordner / Label |
| Dateisystem / Netzlaufwerk | ggf. Schedule | Pfad (lokal oder UNC) |
| HTTP Directory | ggf. Proxy, Auth | URL |
| Git | ggf. Credentials | Repository-URL + Branch |

### Wer konfiguriert was?

- **System-Admin:** Erstellt Konnektoren, definiert Source-Mappings, legt Zeitpläne fest
- **Workspace-Admin:** Kann sehen, welche Quellen in seinen Workspace indiziert werden (read-only)
- **Editor/Viewer:** Kein Zugriff auf Konnektor-Konfiguration

---

## 5. Suche und Berechtigungen

### Übergreifende Suche

Wenn ein User eine Frage stellt:

1. **Workspace-IDs ermitteln:** Alle Workspace-IDs des Users laden (aus Memberships)
2. **Embedding** der Frage erzeugen
3. **Vektor-Suche mit Workspace-Filter:** Die Workspace-IDs werden direkt als Metadaten-Filter in die Vektor-Suche übergeben — es werden nur Chunks durchsucht, deren `workspace_ids` mindestens eine der erlaubten Workspace-IDs enthalten
4. **Re-Ranking und Deduplizierung**
5. **Ergebnis:** User sieht Treffer aus allen seinen Workspaces

Der Berechtigungsfilter ist kein nachgelagerter Schritt, sondern **Teil der Vektor-Suche selbst**. Dadurch werden unberechtigte Chunks gar nicht erst geladen oder gerankt.

### Dokumente in mehreren Workspaces

Wenn eine Quelle in mehrere Workspaces gemappt ist (z.B. Confluence Space "HR" → Workspaces "HR" + "Onboarding"), erscheint das Dokument für Mitglieder beider Workspaces. Der Workspace-Name wird im Suchergebnis angezeigt, damit der Kontext klar ist.

> **Hinweis:** Wenn Cross-Workspace Document Sharing in Zukunft implementiert wird, würde sich geteilte Dokumente analog verhalten — sie erscheinen im Kontext des jeweiligen Workspaces.

### Performance-Überlegung

Der Workspace-Filter wird direkt in die Vektor-Suche integriert (Metadaten-Filter in pgvector). Das ist effizient, weil:
- Die Anzahl der Workspaces pro User typischerweise klein ist (< 20)
- pgvector Metadaten-Filter auf indexierten Spalten unterstützt
- Kein nachgelagertes Filtern nötig — das Top-K-Ergebnis enthält nur berechtigte Chunks

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
  └── source_type: CONNECTOR | USER_UPLOAD
  # Zukünftig (wenn Document Sharing implementiert wird):
  # └── shared_to: [Workspace] (zusätzliche Workspaces)

Connector
  ├── id, name, type (confluence | filesystem | http | ...)
  ├── instance_url, credentials (encrypted)
  ├── schedule, enabled
  └── source_mappings: [SourceMapping]

SourceMapping
  ├── connector: Connector
  ├── source_unit: String (z.B. Confluence Space Key, Ordnerpfad)
  └── target_workspaces: [Workspace] (1:N — eine Quelle kann in mehrere Workspaces indizieren)

DocumentChunk (im Vektor-Store)
  ├── chunk_id, document_id, chunk_index, chunk_text
  ├── embedding: vector
  └── workspace_ids: [workspace_id] (alle Workspaces, in die das Dokument gemappt ist)
```

---

## 7. Geklärte Fragen

- **Storage-Quotas:** Ja, für manuelle Uploads. Upload-Limit wird als User-Einstellung mit globalem Default konfiguriert.
- **Dokument-Versionierung:** Ja, idealerweise. Beim Hochladen sollen außerdem ähnliche Dokumente, die der User sehen kann, angezeigt werden, um Duplikate zu erkennen (z.B. "Dieses Protokoll wurde bereits von jemand anderem hochgeladen").
- **Workspace-Löschung:** Alle Dokumente und Chunks des Workspaces werden entfernt. Konnektoren, die in den gelöschten Workspace mappen, loggen eine Warnung beim nächsten Indexing-Lauf und überspringen die betroffenen Quellen, bis das Mapping korrigiert wird.
- **Audit:** Ja, relevante Aktionen werden im Audit-Log erfasst.
- **Sharing-bezogene Fragen** (Bulk-Sharing, Benachrichtigungen): Sind im separaten Sharing-Konzept dokumentiert — siehe `docs/features/document-sharing.md`.

---

Außerdem in Abschnitt 7 verschoben:

- **Owner vs. Admin:** Beide Rollen bleiben bestehen. Owner (genau einer pro Workspace) kann zusätzlich zu Admin-Rechten den Workspace löschen und die Ownership übertragen.

---

## 8. Offene Fragen

- **Konnektor-Permissions aus Quellsystem:** Sollen z.B. Confluence-Space-Permissions zusätzlich zu Workspace-Permissions berücksichtigt werden? Grundsätzlich ja, aber die Umsetzung ist komplex: Berechtigungsmodelle und User-IDs zwischen Quellsystem und OPAA stimmen nicht notwendigerweise überein. Wird in einer separaten Diskussion vertieft.
- **User-to-User Sharing:** Im aktuellen Modell ist direktes Sharing zwischen Personal Workspaces nicht möglich, da Sharing Editor-Rechte in beiden Workspaces erfordert und Personal Workspaces keine fremden Mitglieder zulassen. Mögliche Lösungen: (a) Dokument in einen gemeinsamen Workspace teilen, (b) einen dedizierten Sharing-Mechanismus auf User-Ebene einführen (z.B. "Dokument für User X freigeben"), oder (c) den Umweg über einen gemeinsamen Workspace als bewusste Design-Entscheidung akzeptieren.
