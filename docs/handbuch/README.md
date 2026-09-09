# OPAA-Handbuch: Betrieb und Verwaltung

Dieses Handbuch beschreibt, wie eine OPAA-Installation betrieben und verwaltet wird: was die
Software tut, wie sie konfiguriert wird und wo der Betrieb nachsieht, wenn etwas nicht wie erwartet
läuft. Es beschreibt den gebauten Ist-Stand, nicht Zielbilder.

## 1. Für wen das Handbuch ist

| Rolle | Typische Aufgaben | Einstieg |
|---|---|---|
| **Betrieb** | installiert, aktualisiert, sichert, überwacht; konfiguriert Modelle und Grenzwerte; geht Störungen nach | [Deployment](deployment.md), danach [Indexierung](indexierung.md) und [Suche](suche.md) |
| **Verwaltung** | legt Bibliotheken an, schließt Quellen an, vergibt Rechte, pflegt Metadaten, prüft Antworten mit dem Diagnosewerkzeug | [Indexierung](indexierung.md), die Konnektor-Kapitel, [Metadaten](metadaten.md), [Suche](suche.md) Abschnitt 8 |

Nicht Zielgruppe sind Entwickler (Spezifikationen und Entscheidungen liegen im Repository unter
`docs/features/` und `docs/decisions/`) und Endnutzende (die Oberfläche erklärt sich an Ort und
Stelle). Wer das Handbuch liest, braucht das Repository nicht.

## 2. Was OPAA aus Betriebssicht ist

OPAA nimmt Dokumente aus angeschlossenen Quellen in einen Index auf und beantwortet Fragen dazu mit
Fundstellen. Beides läuft in **einem Backend-Prozess** gegen **eine PostgreSQL-Datenbank**; nach
außen ruft das Backend nur die konfigurierten Modelle und die Quellen.

```mermaid
flowchart LR
    subgraph Quellen
        Q1[Dateisystem]
        Q2[Webverzeichnis]
        Q3[Feed]
        Q4[Confluence]
        Q5[S3]
        Q6[Upload]
    end
    Q1 --> I[Indexierung<br/>Aufzählen, Parsen, Chunken, Embedden]
    Q2 --> I
    Q3 --> I
    Q4 --> I
    Q5 --> I
    Q6 --> I
    I --> DB[(PostgreSQL<br/>Vektoren, Volltext, Metadaten)]
    DB --> S[Suche<br/>Rechtefilter, zwei Pfade, Fusion]
    F[Frage im Chat] --> S
    S --> A[Antwort mit Fundstellen]
    I -. Embedding-Modell .-> M[(Modelle)]
    S -. Chat-, Embedding-,<br/>Rerank-Modell .-> M
```

Drei Eigenschaften prägen alles Weitere:

- **Die Datenbank ist die einzige Wahrheit.** Dokumentzeilen, Chunks samt Vektoren, Volltext,
  Metadaten, Protokolle, Chats: alles liegt in PostgreSQL. Ein Backup der Datenbank ist ein Backup
  der Installation.
- **Genau eine Backend-Instanz.** Zeitpläne, Wiederanlauf und Thread-Pools sind prozesslokal.
  Skalierung ist eine Frage der Hardware dieser einen Instanz.
- **Rechte wirken in der Suche, nicht dahinter.** Jede Suchabfrage trägt den Filter auf die
  lesbaren Bibliotheken in sich; ein fremder Chunk wird nie geladen. Es gibt keinen
  Administrator-Durchgriff.

## 3. Funktionen im Überblick

| Funktion | Ein Satz | Kapitel |
|---|---|---|
| Quellen anschließen | Verzeichnisse, Webverzeichnisse, Feeds, Confluence-Spaces und S3-Buckets werden per Lauf gespiegelt; Uploads sofort verarbeitet | [Indexierung](indexierung.md), Abschnitte 2 bis 4 |
| Formate verarbeiten | PDF, Office, OpenDocument, Tabellen, HTML, Markdown, E-Mail samt Anhängen; Zulassung nach Inhalt, nicht nach Endung | [Indexierung](indexierung.md), Formatübersicht |
| Änderungen und Löschungen | Prüfsummen, Löscherkennung nur nach vollständiger Aufzählung, Anhänge als eigene Dokumente | [Indexierung](indexierung.md), Abschnitte 6 und 7 |
| Metadaten | Titel, Dokumentart, Datum/Stand je Dokument, dazu Bibliotheks- und Formatfelder; Filter, Kontextpräfix, Beleg | [Metadaten](metadaten.md) |
| Suche und Antwort | Rechtefilter, Teilfragen, Vektor- und Volltextsuche, Fusion, Reranking, Dokument-Vervollständigung, Belegprüfung | [Suche](suche.md) |
| Diagnose | Testfrage im gewählten Rechtekontext, jede Stufe einzeln, „Dokument verfolgen" | [Suche](suche.md), Abschnitt 8 |
| Modelle | Chat-, Embedding- und Rerank-Rolle, Endpunkte, Zugangsdaten | [Deployment](deployment.md), Abschnitte „LLM-Anbieter" und „Reranking einschalten" |
| Authentifizierung | Entwicklungsmodus und OIDC mit Keycloak | [Deployment](deployment.md), Abschnitt „Authentifizierung" |
| Installation und Update | Docker Compose, Umgebungsvariablen, Härtung, Update-Verhalten des Index | [Deployment](deployment.md) |

## 4. Kapitel

### Vorhanden

| Kapitel | Inhalt |
|---|---|
| [Deployment](deployment.md) | Installation aus Images, Update-Ablauf und Folgen für den Index, alle Umgebungsvariablen, Härtung, Modellanbieter, Authentifizierung, Fehlerbehebung |
| [Indexierung](indexierung.md) | Aufnahmestrecke: Bibliothek, Quelle, Lauf, Dokument; Zeitplan; Dokumentstrecke Schritt für Schritt; Anhänge; Löscherkennung; Protokoll; Pipeline-Versionen und Nachzug; Formatübersicht |
| [Suche](suche.md) | Abfragestrecke: Suchbereich, Filter, Teilfragen, zwei Suchpfade, Fusion, Reranking, Vervollständigung, Antwort, Belegprüfung, Diagnose, Konfiguration |
| [Metadaten](metadaten.md) | Kernfelder, Format- und Bibliotheksfelder, Vokabular, Ermittlung, Bestandslauf, Pflege, Wirkung in Filter, Kontextpräfix und Beleg |
| Konnektoren: [Dateisystem](konnektor-filesystem.md), [Webverzeichnis](konnektor-http-directory.md), [Feed](konnektor-rss-feed.md), [Confluence](konnektor-confluence.md), [S3](konnektor-s3.md) | je Quellentyp: Einrichtung, Schutzmechanismen, Betriebsarten, Löschsemantik, Grenzwerte, Fehlerbilder |
| Formate: [PDF](format-pdf.md), [Word](format-docx.md), [PowerPoint](format-pptx.md), [Tabellen](format-tabular.md), [OpenDocument Text](format-odt.md), [OpenDocument Präsentation](format-odp.md), [HTML](format-html.md), [Markdown](format-markdown.md), [E-Mail](format-mail.md), [Confluence-Seite](format-confluence.md), [Auffang-Pipeline](format-fallback.md) | je Format: Zulassung, erkannte Struktur, Zuschnitt, Metadaten, Grenzen |

### In Vorbereitung

Diese Kapitel sind im Epic #1282 vorgesehen; bis dahin steht der jeweilige Inhalt verstreut in
[Deployment](deployment.md) oder nur im Repository.

| Kapitel | Vorgesehener Inhalt |
|---|---|
| Bibliotheken und Berechtigungen | Organisationen, Räume, Bibliotheken, Rollen, Freigaben, Ordner in Upload-Bibliotheken, Speicherkontingent, Löschen und Ausschluss von Dokumenten |
| Modelle | Chat-, Embedding- und Rerank-Modellrolle, verwaltete Modelle, Endpunkte, Fehlerbilder |
| Authentifizierung | Auth-Modi, Keycloak-Anbindung, Erstadministrator, Härtung des Realms |
| Betrieb | Backup und Wiederherstellung, Update-Ablauf, Log- und Metrik-Übersicht, Single-Instance-Annahme, Grenzwerte auf einer Seite |

## 5. Lesewege nach Anlass

| Anlass | Reihenfolge |
|---|---|
| Erste Installation | [Deployment](deployment.md) Schnellstart und Konfiguration → [Deployment](deployment.md) Härtung → [Indexierung](indexierung.md) Abschnitt 2 |
| Neue Quelle anschließen | das Konnektor-Kapitel des Quellentyps → [Indexierung](indexierung.md) Abschnitte 3 und 7 (Zeitplan, Löscherkennung) |
| Dokumente fehlen oder sind veraltet | [Indexierung](indexierung.md) Abschnitte 7 bis 9 → Laufprotokoll der Bibliothek → Format-Kapitel des Dokuments |
| Eine Antwort ist schlecht | [Suche](suche.md) Abschnitte 8 und 9 → Diagnosewerkzeug → je nach Befund [Indexierung](indexierung.md) oder [Metadaten](metadaten.md) |
| Filter oder Beleg zeigen falsche Werte | [Metadaten](metadaten.md) Abschnitte 4, 7 und 8 |
| Update steht an | [Deployment](deployment.md) „Aktualisierung" und „Was ein Update mit dem Index macht" → [Indexierung](indexierung.md) Abschnitt 9 (Nachzug) |
| Reranking einschalten | [Deployment](deployment.md) „Reranking einschalten" → [Suche](suche.md) Stufe 8 |

## 6. Glossar

Begriffe, die in allen Kapiteln in genau dieser Bedeutung verwendet werden.

| Begriff | Bedeutung |
|---|---|
| **Wissensbibliothek** (Bibliothek) | Verwaltungseinheit für Dokumente: gehört zu einer Organisation, trägt Berechtigungen und genau eine Quelle |
| **Quelle** | Woher eine Bibliothek ihre Dokumente bezieht: Upload oder ein Quellentyp mit Konnektor (Dateisystem, Webverzeichnis, Feed, Confluence, S3) |
| **Konnektor** | Der Teil der Indexierung, der die Eigenheiten eines Quellentyps kennt: Aufzählen, Abrufen, Betriebsarten |
| **Indexierungslauf** (Lauf) | Ein Durchgang über die Quelle einer Bibliothek mit Status, Zählern und Protokoll |
| **Betriebsart** | Ob ein Lauf die Quelle vollständig auflistet (und Verschwundenes entfernen darf) oder nur ergänzt |
| **Dokument** | Eine Zeile in der Dokumenttabelle, eindeutig über Bibliothek und Quellpfad; Anhänge sind eigene Dokumente mit Verweis auf das Elterndokument |
| **Chunk** | Ein Textstück eines Dokuments mit Vektor, Volltext und Metadaten; die Einheit, auf der die Suche arbeitet |
| **Format-Pipeline** | Die Verarbeitung eines Dateiformats: Parsen und Zuschnitt in Chunks, mit Kennung und Versionsnummer |
| **Nachzug** | Neuverarbeitung von Dokumenten, deren Chunks mit einer älteren Pipeline-Version oder Volltextfassung entstanden sind; von einem Systemadministrator angestoßen |
| **Kernfelder** | Titel, Dokumentart und Datum/Stand je Dokument |
| **Kontextpräfix** | Text, der jedem Chunk beim Einbetten und im Volltextindex vorangestellt wird (Titel, ausgewählte Felder, Gliederungspfad), ohne den gespeicherten Text zu ändern |
| **Raum** (Space) | Arbeitsbereich, in dem Chats liegen; kann Bibliotheken zuordnen und damit den Standard-Suchbereich verengen |
| **Suchbereich** | Die Bibliotheken, in denen eine Frage sucht; steht in der Chip-Leiste des Chats und ist nie weiter als die Leserechte |
| **Fundstelle** (Beleg) | Eine Quellenangabe unter einer Antwort, eine je Dokument, mit Rang, Ortsangabe und Kernfeldern |
| **Ortsangabe** | Die Stelle eines Chunks im Dokument, etwa „S. 3 · Abschn. Fristen" |
| **Modellrolle** | Eine der drei Aufgaben, für die ein externes Modell konfiguriert wird: Chat, Embedding, Reranking |
| **Rechteprofil** | Eine Gruppe samt der Bibliotheken, die sie lesen darf; der voreingestellte Kontext des Diagnosewerkzeugs |
| **Diagnosesperre** | Grundzustand jeder Bibliothek, der sie aus einer Diagnose im Rechtekontext einer benannten Person heraushält |

## 7. Konventionen

- **Deutsch, Ist-Stand, keine Entstehungsgeschichte.** Warum etwas so gebaut ist, steht in den
  Spezifikationen und Entscheidungen im Repository, nicht hier.
- **Verweise nur innerhalb des Handbuchs.** Die einzige Ausnahme ist dieser Hinweis auf das
  Repository.
- **Ticketverweise nur für ausdrücklich noch nicht Gebautes**, etwa in „Was nicht gebaut ist".
- **Konfigurierbare Werte stehen nur in den Konfigurationstabellen** der Kapitel und in der
  Variablenliste des Deployment-Kapitels. Fließtext und Diagramme nennen Parameternamen oder
  bleiben neutral, damit ein geänderter Default nicht an fünf Stellen veraltet.
- **Grafiken sind Mermaid**, damit sie ohne Werkzeug im Repository gerendert werden.
- **Entwurf** am Kapitelanfang bedeutet: geschrieben und gegen den Code geprüft, aber noch nicht
  vom Maintainer abgenommen. Der Hinweis fällt mit der Abnahme weg.
- **Kapitel werden mit dem Code gepflegt.** Wer ein Verhalten ändert, das ein Kapitel beschreibt,
  ändert das Kapitel im selben Pull Request.
