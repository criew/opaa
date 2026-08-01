# Zugangskontrolle & Workspaces

## Motivation

Nicht alles Organisationswissen ist für jeden bestimmt. Ein Unternehmen hat:
- Öffentliche Richtlinien, auf die jeder zugreifen kann
- Team-spezifische Dokumentation, die nur für Teams relevant ist
- Sensible Informationen (Gehaltsinformationen, proprietäre Daten), die auf bestimmte Rollen beschränkt sind
- Compliance-Dokumente nur für Prüfer

Dieses Feature beschreibt, wie OPAA kontrolliert, wer was sehen kann, und ermöglicht Multi-Team-, Multi-Rollen-Zugang mit feinkörnigen Berechtigungen.

---

## Überblick

OPAA bietet Zugangskontrolle auf mehreren Ebenen:

1. **Workspaces** — Logische Gruppierungen von Dokumenten und Benutzern (Teams, Abteilungen)
2. **Persönliche Workspaces** — Automatisch erstellte private Bereiche für individuelle Benutzerdokumente
3. **Rollen** — Aufgabenfunktionen mit spezifischen Berechtigungen
4. **Dokument-Berechtigungen** — Feinkörnige Kontrolle über einzelne Dokumente
5. **Berechtigungsdurchsetzung zur Abfragezeit** — Berechtigungen werden beim Suchen des Benutzers geprüft

---

## Workspaces

### Konzept

Ein **Workspace** ist ein eigenständiger Bereich von OPAA:
- Hat eigene Dokumente, Benutzer und Rollen
- Hat eigene Einstellungen und Konfigurationen
- Benutzer in einem Workspace sehen keine Dokumente aus einem anderen
- Jeder Workspace kann seinen eigenen Indizierungszeitplan haben

Ein spezieller Workspace-Typ, der **Persönliche Workspace** ("Meine Dokumente"), wird automatisch für jeden Benutzer erstellt. Er funktioniert identisch zu einem regulären Workspace, gehört aber ausschließlich einem einzelnen Benutzer und ist nur für diesen sichtbar. Benutzer können nicht als Mitglieder des persönlichen Workspaces eines anderen Benutzers hinzugefügt werden. Siehe [Persönliche Workspaces](#persönliche-workspaces) unten.

### Workspace-Beispiele

| Workspace | Mitglieder | Dokumente | Sichtbarkeit |
|-----------|------------|-----------|--------------|
| Engineering | Entwickler, Architekten | Code-Docs, ADRs, Design-Docs | Nur Engineers |
| Marketing | Marketing-Team | Markenrichtlinien, Kampagnenpläne | Nur Marketing |
| HR | HR-Personal | Richtlinien, Handbücher | Nur HR (sensibel) |
| Unternehmen | Alle Mitarbeiter | Öffentliche Richtlinien, All-Hands-Notizen | Alle |
| Meine Dokumente (Sarah) | Nur Sarah | Hochgeladene Recherche, Notizen, Entwürfe | Nur Sarah |

### Workspace-Verwaltung

#### Workspaces erstellen

**Nur System-Admins können Workspaces erstellen.** (Siehe [Systemverwaltung](#systemverwaltung) unten.)

System-Admin-Workflow:

```
1. Workspace-Namen wählen: "Engineering"
2. Workspace-Owner setzen: Sarah Chen
3. Anfangsmitglieder hinzufügen: Aus Benutzerverzeichnis auswählen
4. Beschreibung setzen: "Für Engineering-Team-Dokumentation"
5. Standards konfigurieren:
   - Standardrolle für neue Mitglieder: "viewer"
   - Aufbewahrungsrichtlinie: 2 Jahre aufbewahren
6. Speichern
```

Hinweis: Indizierungszeitpläne werden auf Konnektor-Ebene konfiguriert, nicht auf Workspace-Ebene. Siehe [Daten-Indizierung & RAG — Konnektor-Modell](./data-indexing-rag.md#connector-model-and-workspace-mapping).

#### Workspace-Mitglieder verwalten

Benutzer hinzufügen/entfernen:
```
Workspace: Engineering

Mitglieder:
  Sarah Chen (owner)      → Kann Mitglieder verwalten, Einstellungen ändern
  Alex Johnson (editor)   → Kann Dokumente hinzufügen, Berechtigungen ändern
  Jamie Lee (viewer)      → Kann lesen, suchen, herunterladen
  Pat Miller (denied)     → Kein Zugang
```

#### Workspace-Isolierung

Standardverhalten: **Vollständige Isolierung**
- Benutzer können nur Dokumente in ihren Workspaces abfragen
- Können andere Workspaces in der UI nicht sehen
- Suchergebnisse enthalten nur Dokumente ihres Workspaces
- API-Tokens erben Workspace-Zugang

Optional: **Gemeinsame Workspaces** (für teamübergreifende Bedürfnisse)
- Mehrere Teams einem einzelnen Workspace hinzugefügt
- Rollenbasierte Berechtigungen innerhalb des gemeinsamen Workspaces
- Audit-Logging verfolgt, wer was gesucht hat

### Persönliche Workspaces

#### Automatische Erstellung

Wenn ein Benutzer sich zum ersten Mal anmeldet oder sein erstes Dokument hochlädt, erstellt OPAA automatisch einen persönlichen Workspace:

```
Workspace: "Meine Dokumente"
  Typ: personal
  Owner: [Benutzer]
  Mitglieder: [Benutzer] (kann nicht geändert werden)
  Sichtbarkeit: privat (nur der Owner)
  Automatisch erstellt: true
  Löschbar: nein (existiert, solange Benutzerkonto existiert)
```

#### Eigenschaften

- Einer pro Benutzer, kann nicht dupliziert werden
- Benutzer ist immer Owner mit voller Kontrolle
- Kann andere Mitglieder nicht direkt einladen
- Workspace-übergreifendes Dokument-Teilen ist ein geplantes zukünftiges Feature — siehe [Dokument-Teilen](./document-sharing.md)
- Hat seinen eigenen Indizierungsumfang für RAG-Abfragen
- In workspace-übergreifenden Suchergebnissen enthalten (nur für den besitzenden Benutzer)

#### Speicherung

Persönliche Workspace-Dokumente werden auf dem konfigurierten Speicher-Backend des Deployments gespeichert (S3, Netzlaufwerk oder lokales Dateisystem). Der Speicherort ist für den Benutzer transparent. Siehe [Daten-Indizierung & RAG — Benutzer-Dokument-Upload](./data-indexing-rag.md#user-document-upload) für Details.

---

## Workspace-übergreifendes Dokument-Teilen

Workspace-übergreifendes Dokument-Teilen ist als zukünftiges Feature geplant. Das Konzept und seine offenen Sicherheitsbedenken sind separat in [Dokument-Teilen](./document-sharing.md) dokumentiert.

---

## Direkt in Team-Workspace hochladen

Benutzer mit Editor-Rolle in einem Team-Workspace können Dokumente auch direkt in diesen Workspace hochladen (unter Umgehung des persönlichen Workspaces). In diesem Fall:
- Der Heimat-Workspace des Dokuments ist der Team-Workspace
- Der hochladende Benutzer ist der Dokument-Owner
- Standard-Workspace-Berechtigungen gelten

---

## Dokumenten-Löschung und -Entfernung

Die Möglichkeit, Dokumente zu löschen oder zu entfernen, hängt von ihrer **Herkunft** ab:

| Dokument-Herkunft | Editor | Admin | Auswirkung |
|---|---|---|---|
| **Manueller Upload** | Kann eigene Uploads löschen | Kann jeden Upload im Workspace löschen | Dokument + Chunks dauerhaft entfernt |
| **Konnektor-indiziert** | — | Kann Dokument ausschließen | Dokument aus Index entfernt und bei zukünftigen Syncs übersprungen (siehe unten) |

#### Ausschluss-Mechanismus für Konnektor-Dokumente

Konnektor-indizierte Dokumente können nicht einfach gelöscht werden, da sie beim nächsten Indizierungslauf wieder auftauchen würden. Stattdessen können Workspace-Admins einzelne Dokumente **ausschließen**:

1. Admin markiert ein Dokument als "ausgeschlossen" im Workspace
2. Das Dokument wird aus dem Index entfernt (Chunks gelöscht)
3. Zukünftige Indizierungsläufe überspringen das ausgeschlossene Dokument
4. Die Ausschlussliste wird pro Quell-Mapping gespeichert
5. System-Admins können Ausschlüsse anzeigen und aufheben

**Anwendungsfälle:**
- Irrelevante Dokumente, die zu Lärm in Suchergebnissen führen
- Veraltete Dokumente, die noch im Quellsystem vorhanden sind
- Dokumente, die über Quell-Mapping in den falschen Workspace indiziert wurden

---

## Rollen & Berechtigungen

### Eingebaute Rollen

#### Viewer
Nur-Lesen-Zugang.

Kann:
- Fragen stellen (OPAA durchsucht Dokumente)
- Dokumente herunterladen
- Gesprächshistorie anzeigen
- Antworten bewerten

Kann nicht:
- Dokumente hinzufügen/ändern
- Berechtigungen ändern
- Benutzer verwalten
- Andere Workspaces anzeigen

#### Editor
Kann Dokumente ändern.

Kann:
- Alles, was Viewer können
- Neue Dokumente hochladen
- Dokument-Metadaten bearbeiten
- Dokumente hinzufügen/entfernen
- Eigene hochgeladene Dokumente löschen
- Dokument-Berechtigungen ändern

Kann nicht:
- Dokumente anderer Benutzer löschen
- Konnektor-indizierte Dokumente ausschließen
- Benutzer verwalten
- Workspace-Einstellungen ändern
- Workspace löschen

#### Admin
Volle Workspace-Kontrolle.

Kann:
- Alles, was Editoren können
- Jedes Dokument im Workspace löschen
- Konnektor-indizierte Dokumente ausschließen
- Benutzer und Rollen verwalten
- Workspace-Einstellungen ändern
- Integrationen verwalten
- Audit-Logs anzeigen

Hinweis: Konnektor- und Indizierungskonfiguration ist System-Admins vorbehalten. Workspace-Admins können sehen, welche Quellen in ihren Workspace indizieren (nur lesend).

#### Owner
Nur einer pro Workspace.

Kann:
- Workspace-Ownership übertragen
- Workspace löschen
- Alle Admin-Berechtigungen

### Benutzerdefinierte Rollen

Organisationen können benutzerdefinierte Rollen erstellen:

```yaml
CustomRole:
  name: "Research Lead"
  inherits_from: "Editor"
  permissions:
    - read_documents
    - create_documents
    - edit_documents_own  # Nur eigene
    - manage_indexing
    - view_analytics
  restrictions:
    - cannot_delete_published
    - cannot_access_sensitive_tag
```

### Berechtigungsmatrix

| Aktion | Viewer | Editor | Admin | Owner | System-Admin |
|--------|--------|--------|-------|-------|-------------|
| Dokumente suchen | ✅ | ✅ | ✅ | ✅ | ✅ (alle) |
| Dokumente herunterladen | ✅ | ✅ | ✅ | ✅ | ✅ (alle) |
| Quellen anzeigen | ✅ | ✅ | ✅ | ✅ | ✅ (alle) |
| Dokumente hochladen (manuell) | ❌ | ✅ | ✅ | ✅ | ✅ (alle) |
| Dokumente bearbeiten | ❌ | ✅* | ✅ | ✅ | ✅ (alle) |
| Eigene Uploads löschen | ❌ | ✅ | ✅ | ✅ | ✅ (alle) |
| Jeden Upload löschen | ❌ | ❌ | ✅ | ✅ | ✅ (alle) |
| Konnektor-Dokumente ausschließen | ❌ | ❌ | ✅ | ✅ | ✅ (alle) |
| Berechtigungen ändern | ❌ | ❌ | ✅ | ✅ | ✅ (alle) |
| Benutzer verwalten | ❌ | ❌ | ✅ | ✅ | ✅ (alle) |
| Ownership übertragen | ❌ | ❌ | ❌ | ✅ | ✅ (alle) |
| Workspace löschen | ❌ | ❌ | ❌ | ✅ | ✅ |
| Workspaces erstellen | ❌ | ❌ | ❌ | ❌ | ✅ |
| Konnektoren konfigurieren | ❌ | ❌ | ❌ | ❌ | ✅ |
| Quell-Mappings definieren | ❌ | ❌ | ❌ | ❌ | ✅ |
| Benutzerverzeichnis-Synchronisation | ❌ | ❌ | ❌ | ❌ | ✅ |

*Editor kann eigene Dokumente bearbeiten, wenn Berechtigung gesetzt

---

## Berechtigungen auf Dokumentenebene

### Berechtigungs-Granularität

Über Rollen hinaus können einzelne Dokumente haben:
- **Owner** — Benutzer, der das Dokument hinzugefügt/besitzt
- **Reader** — Benutzer/Rollen, die anzeigen können
- **Editor** — Benutzer/Rollen, die ändern können
- **Tags** — Metadaten für die Gruppierung von Berechtigungen

### Berechtigungsmodelle

#### Modell 1: Von Quelle erben
Confluence-Dokumente erben Confluence-Space-Berechtigungen:
- Nur Benutzer mit Wiki-Zugang sehen Wiki-Dokumente
- Automatisch aktualisiert, wenn sich Wiki-Berechtigungen ändern
- Für OPAA-Benutzer transparent

#### Modell 2: Explizite OPAA-Berechtigungen
Feinkörnige Kontrolle innerhalb von OPAA:

```
Dokument: "Gehaltsüberprüfungsprozess"
  Owner: HR-Manager
  Reader: [role:hr_team, role:managers]
  Editor: [HR-Manager]
  Tags: [sensibel, eingeschränkt]
```

#### Modell 3: Tag-basiert
Massenberechtigungen über Tags:

```
Tag: "public"         → Reader: all_authenticated_users
Tag: "team_only"      → Reader: [current_workspace_members]
Tag: "managers"       → Reader: [role:manager, role:director]
Tag: "sensitive"      → Reader: [role:hr, role:compliance]
Tag: "public_website" → Reader: [anonymous, authenticated]
```

---

## Berechtigungsdurchsetzung zur Abfragezeit

### Wie es funktioniert

Berechtigungen werden **als Teil der Vektorsuche selbst** durchgesetzt, nicht als Nachfilter. Wenn ein Benutzer sucht:

1. **Workspace-IDs:** System lädt alle Workspace-IDs, in denen der Benutzer Mitglied ist
2. **Abfrage:** "Was ist unsere HR-Richtlinie?"
3. **Vektorsuche mit Workspace-Filter:** Die Workspace-IDs des Benutzers werden als Metadaten-Filter direkt in die Vektorsuche übergeben — nur Chunks, deren `workspace_ids` mindestens eine der Workspaces des Benutzers enthalten, werden durchsucht
4. **Re-Ranking und Deduplizierung**
5. **Antwort:** "Basierend auf HR-Richtlinien, auf die Sie Zugang haben..."

Da der Filter in die Vektorsuche integriert ist, werden nicht autorisierte Chunks niemals geladen oder gerankt. Das Top-K-Ergebnis enthält nur autorisierte Chunks ohne Informationsleck.

**Schlüsselprinzip:** Benutzer weiß nie, dass Dokumente existieren, auf die er nicht zugreifen kann. Ergebnisse wirken vollständig, sind aber gefiltert.

### Berechtigungs-Caching

Für Leistung:
- Benutzer-Workspace-Mitgliedschaften gecacht (10 Minuten TTL)
- Berechtigungs-Widerruf löscht Cache sofort
- Admin-Aktionen löschen Cache

---

## Systemverwaltung

### System-Admin-Rolle

Über workspace-ebenen Rollen hinaus hat OPAA eine **System-Admin**-Rolle für organisationsweite Administration. Dies ist eine systemweite Rolle (keine Workspace-Rolle) und wird auf der Benutzer-Entität gespeichert.

System-Admins können:
- Workspaces erstellen und löschen
- Konnektoren konfigurieren (Datenquellen-Verbindungen)
- Quell-Mappings definieren (welche Quell-Untereinheit in welchen Workspace indiziert)
- Benutzerverzeichnis-Synchronisation konfigurieren
- Globale Einstellungen verwalten
- Auf alle Workspaces zugreifen

### Dokumentenfluss: Konnektoren vs. Benutzer-Uploads

Die zwei Pfade, auf denen Dokumente in OPAA gelangen, haben unterschiedliche Autorisierungsanforderungen:

- **Konnektoren (System-Admin):** System-Admins konfigurieren Konnektoren und definieren, welche Quell-Untereinheiten (z. B. Confluence-Spaces, Dateipfade) in welche Workspaces gemappt werden. Dies ist der primäre Pfad für Massen-, automatisierte Dokumentenaufnahme.
- **Manuelle Uploads (Editor):** Benutzer mit Editor-Rolle können einzelne Dokumente hochladen — entweder in ihren persönlichen Workspace oder in Team-Workspaces, in denen sie Editor-Zugang haben. Dies ist für persönliche Dokumente, Notizen und Ad-hoc-Inhalte gedacht.

### Workspace-Löschung

Wenn ein System-Admin oder Owner einen gemeinsamen Workspace löscht:

1. Alle Dokumente und Chunks, die zum Workspace gehören, werden dauerhaft entfernt
2. Konnektoren, die Quellen auf den gelöschten Workspace mappen, protokollieren beim nächsten Indizierungslauf eine Warnung und überspringen diese Quellen, bis das Mapping korrigiert ist
3. Ein Audit-Log-Eintrag zeichnet die Löschung auf

---

## Benutzerverwaltung

### Benutzeridentität

Benutzer können sich authentifizieren über:
- **Single Sign-On (SSO)** — OIDC, SAML (empfohlen)
- **Lokale Konten** — Benutzername/Passwort (nur Fallback)
- **API-Tokens** — Für programmatischen Zugang

**Empfohlen:** SSO-Integration mit Active Directory/Okta

### Benutzerverzeichnis-Synchronisation

OPAA kann mit Verzeichnis synchronisieren:

```
Sync-Häufigkeit: Alle 6 Stunden

Von Active Directory:
  - Benutzernamen und E-Mails
  - Gruppenmitgliedschaften
  - Abteilung
  - Berufsbezeichnung
  - Vorgesetzter

Auto-Mapping:
  - AD-Gruppe "engineering" → opaa-workspace: Engineering
  - AD-Gruppe "hr" → opaa-workspace: HR
  - AD-Attribut "department" → Workspace-Zuweisung
```

Wenn Benutzer die Organisation verlässt:
- Verzeichnis-Sync entfernt ihn
- Seine Dokumente bleiben (ihm gehörend)
- Kann sich nicht mehr anmelden
- Option zur Übertragung der Dokument-Ownership

### API-Tokens & Service-Accounts

Für Integrationen:

```
API-Token erstellen:
  Name: "Slack Bot"
  Workspace: Engineering
  Umfang: [read_documents, ask_questions]
  Rotation: 90 Tage
  IP-Whitelist: 10.0.1.0/24 (optional)
  Rate-Limit: 1.000 Anfragen/Tag

Token: opaa_token_abc123xyz_def456uvw
```

Service-Accounts:
- Kein interaktiver Login
- Reiner API-Zugang
- Keine Benutzeroberfläche
- Verwendet für Bots, Integrationen, Skripte

---

## Audit & Compliance

### Audit-Logging

Jede Aktion geloggt:

```json
{
  "timestamp": "2024-02-16T14:30:15Z",
  "user_id": "user-123",
  "action": "search",
  "workspace": "engineering",
  "query": "system architecture",  // Standardmäßig nicht geloggt
  "results_count": 5,
  "documents_accessed": ["doc-1", "doc-2", "doc-3"],
  "result": "success",
  "ip_address": "10.0.1.45",
  "user_agent": "Chrome/120.0"
}
```

Logs aufbewahrt:
- Minimum: 1 Jahr (konfigurierbar)
- Kann an SIEM exportiert werden (Splunk, ELK, usw.)
- Kann nicht gelöscht werden (unveränderliches Append-only)

### Compliance-Berichte

Berichte erstellen:
- **Benutzer-Zugangs-Bericht:** Wer hat was wann abgerufen
- **Berechtigungsänderungen:** Wer hat Berechtigungen geändert
- **Sensible Dokument-Zugang:** Wer hat eingeschränkte Dokumente angesehen
- **Fehlgeschlagene Zugriffsversuche:** Berechtigungsverweigerungen

Verwendet für:
- SOC-2-Audit-Trail
- HIPAA-Compliance
- DSGVO-Datenzugriffsanfragen
- Interne Untersuchungen

### Datenlöschung (DSGVO-Recht auf Vergessenwerden)

Wenn Benutzerkonto gelöscht:

```
1. Benutzer aus allen Workspaces entfernen
2. Ownership seiner Dokumente übertragen (optional)
3. Benutzerkonto und Auth-Tokens löschen
4. Audit-Logs behalten (aus Compliance-Gründen), aber Benutzerinfo schwärzen
5. Personenbezogene Daten anonymisieren
```

Dokumenten-Löschung:
- Kann nur von Workspace-Admin durchgeführt werden
- Erstellt Audit-Eintrag (wann, wer, warum)
- Option für dauerhafte Löschung (nach Aufbewahrungsfrist)

---

## Workspace-Strategien

### Strategie 1: Ein Workspace pro Team
Jedes Team hat isolierten Workspace:
- **Vorteile:** Einfach, klare Isolierung, Team-Ownership
- **Nachteile:** Keine teamübergreifende Suche, Datenduplizierung
- **Geeignet für:** Organisationen mit silomäßig organisierten Teams

### Strategie 2: Einzelner Unternehmens-Workspace
Alle Dokumente in einem Workspace, rollenbasierte Berechtigungen:
- **Vorteile:** Teamübergreifende Suche, einheitliches Wissen
- **Nachteile:** Komplexe Berechtigungsverwaltung, ein Admin für alle
- **Geeignet für:** Kleinere Unternehmen mit guter teamübergreifender Zusammenarbeit

### Strategie 3: Hybrid (Empfohlen)
Mehrere Workspaces + teamübergreifende Suchen:
- **Persönliche Workspaces:** Jeder Benutzer hat "Meine Dokumente" (privat, automatisch erstellt)
- **Öffentlicher Workspace:** Alle eingeschlossen (Richtlinien, All-Hands-Notizen)
- **Team-Workspaces:** Isoliert nach Team (Engineering, Marketing, HR)
- **Projekt-Workspaces:** Gemeinsame Workspaces, denen mehrere Teams beitreten (z. B. "Phoenix"-Projekt mit Frontend-, Backend- und QA-Teams)
- **Spezielle Workspaces:** Funktionsübergreifend (Vorstand, Geschäftsführung)

**Hinweis:** Das Workspace-Modell ist **flach** — es gibt keine Hierarchie oder Verschachtelung zwischen Workspaces. Projekte, die mehrere Teams umspannen, werden als gemeinsame Workspaces dargestellt, denen alle relevanten Teammitglieder beitreten. Projektweites Wissen lebt im Projekt-Workspace, während teamspezifisches Wissen in den Team-Workspaces verbleibt.

Benutzer-Zugangsbeispiel:
```
Mitarbeiter: Sarah Chen
  Workspaces: [Meine Dokumente, Unternehmen, Engineering, Vorstand]
  Rollen: [owner in Meine Dokumente, viewer in Unternehmen, editor in Engineering, viewer in Vorstand]
```

Teamübergreifende Suche:
- Sarah kann alle vier Workspaces gleichzeitig durchsuchen
- Ergebnisse gefiltert nach ihrer Rolle in jedem Workspace
- Privat hochgeladene Dokumente erscheinen neben Team- und Unternehmensdokumenten
- Workspace-Name in Ergebnissen angezeigt

---

## Sonderfälle

### Führungskräfte-Zugang

Führungskräfte benötigen breiten Zugang:

```
Rolle: Führungskraft
Erbt: Admin
Workspaces: [Unternehmen, Alle]
Berechtigungen:
  - Alle Workspaces gleichzeitig durchsuchen
  - Teamübergreifende Berichte erstellen
  - Nutzungsanalysen für gesamtes Unternehmen anzeigen
```

### Audit- & Compliance-Teams

Compliance-Personal benötigt Zugang für Audits:

```
Rolle: Auditor
Workspaces: [Alle] (nur lesend)
Berechtigungen:
  - Alle Workspaces durchsuchen
  - Audit-Logs anzeigen
  - Compliance-Berichte erstellen
  - Kann nichts ändern
```

### Externe Berater

Begrenzte Zeit, begrenzter Zugang:

```
Benutzer: Externer Berater
Workspaces: [Projekt-X]
Rolle: viewer (temporär)
Ablauf: 2024-03-31
Einschränkungen:
  - Kann nur Dokumente mit Tag "consultant-access" anzeigen
  - Kein API-Token-Zugang
  - Downloads geloggt
```

### Benutzer-Offboarding mit persönlichem Workspace

Wenn ein Benutzer die Organisation verlässt, erfordert sein persönlicher Workspace besondere Behandlung:

```
1. Persönliche Workspace-Dokumente können:
   - Auf einen anderen Benutzer oder Workspace übertragen werden
   - Archiviert werden
   - Gelöscht werden (nach Aufbewahrungsfrist)
2. Persönlicher Workspace wird deaktiviert (nicht gelöscht, aus Audit-Gründen)
```

---

## Integrationspunkte

- **Authentifizierung:** Integriert mit SSO-Anbieter
- **Benutzer-Frontends:** Berechtigungen an jeder Schnittstelle durchsetzen
- **Daten-Indizierung:** Quell-Berechtigungen respektieren (Confluence, usw.)
- **RAG-Engine:** Ergebnisse nach Benutzerberechtigungen filtern
- **Deployment-Infrastruktur:** Benutzer-/Gruppendaten aus Verzeichnis

---

## Offene Fragen / Zukünftige Erweiterungen

- Sollten wir attribut-basierte Zugangskontrolle (ABAC) unterstützen?
- Sollten wir zeitbasierte Berechtigungen unterstützen (nur 9-17 Uhr Zugang)?
- Sollten wir Geo-Fencing unterstützen (IP-Einschränkungen)?
- Sollten wir Genehmigungsworkflows für sensible Dokumente unterstützen?
- Sollten wir Delegation unterstützen (Benutzer A delegiert Berechtigungen an B)?
- Sollten wir Dokumentenklassifizierung unterstützen (öffentlich/intern/vertraulich)?
- **Konnektor-Berechtigungen aus Quellsystemen:** Sollten Quellsystem-Berechtigungen (z. B. Confluence-Space-Berechtigungen) zusätzlich zu Workspace-Berechtigungen durchgesetzt werden? Erwünscht aber komplex — Benutzer-IDs und Berechtigungsmodelle stimmen möglicherweise nicht zwischen Quellsystem und OPAA überein. Separat zu diskutieren.
- **Dokument-Teilen:** Workspace-übergreifendes Teilen hat erhebliche offene Sicherheitsfragen — siehe [Dokument-Teilen](./document-sharing.md).

---

## Erfolgs-Metriken

- **Akzeptanz:** % der Benutzer ohne "owner"-Rolle (gesunde Verteilung)
- **Compliance:** 100% der Audit-Logs aufbewahrt und zugänglich
- **Leistung:** Berechtigungsprüfung fügt < 50 ms zur Abfragezeit hinzu
- **Genauigkeit:** 0 unbeabsichtigte Zugangsvorfälle
- **Benutzerfreundlichkeit:** Neue Benutzer verstehen Workspace-/Rollenmodell in < 5 Minuten
