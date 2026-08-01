# LLM-Integration

## Motivation

Die Qualität von OPAAs Antworten hängt nicht nur davon ab, die richtigen Dokumente zu finden, sondern auch davon, wie diese Dokumente zur Antwortgenerierung verwendet werden. Verschiedene Organisationen haben unterschiedliche Anforderungen:

- Einige möchten die neuesten Modelle von OpenAI für maximale Fähigkeit nutzen
- Einige benötigen Open-Source-Modelle für Datenschutz und Kosten
- Einige erfordern spezifische Modellversionen für Compliance
- Einige möchten Anbieter wechseln, ohne Code neu zu schreiben

Dieses Feature stellt sicher, dass OPAA modell-agnostisch und zum Deployment-Zeitpunkt vollständig konfigurierbar ist.

---

## Überblick

OPAAs LLM-Integration bietet:

1. **Modell-Flexibilität** — Unterstützung für mehrere LLM-Anbieter und -Modelle
2. **Konfiguration beim Deployment** — Modelle über Umgebungsvariablen wählen, keine Code-Änderungen
3. **Anbieter-Abstraktion** — Anbieter wechseln ohne Anwendungsänderungen
4. **Erweiterte Fähigkeiten** — Streaming-Antworten, Function Calling, Embeddings
5. **Kosten- & Leistungsoptimierung** — Verschiedene Modelle für verschiedene Aufgaben verwenden

---

## Unterstützte LLM-Anbieter

### OpenAI-kompatible APIs

Jeder Anbieter, der den OpenAI-API-Standard implementiert, wird unterstützt:

**Primäre Anbieter:**
- **OpenAI** (GPT-4, GPT-3.5-turbo)
- **Azure OpenAI** (verwaltetes OpenAI in Azure)
- **Anthropic Claude** (über Claude API)
- **Open-Source über OpenAI-kompatible Server:**
  - Ollama (lokal)
  - LM Studio (lokal)
  - Text Generation WebUI (lokal)
  - vLLM (selbst gehostet)
  - LocalAI (lokal)

**Warum OpenAI-kompatibel?**
- De-facto-Standard für LLM-APIs
- Gleiche Schnittstelle über viele Anbieter
- Minimale Abstraktionsschicht
- Einfach für Entwickler zu verstehen

### Konfigurationsmuster

Alle LLM-Anbieter identisch konfiguriert:

```
LLM_PROVIDER: "openai"           # oder "anthropic", "azure", "custom"
LLM_API_KEY: "${OPENAI_API_KEY}"
LLM_API_BASE: "https://api.openai.com/v1"  # kann jeder OpenAI-kompatible Endpunkt sein
LLM_MODEL: "gpt-4"
```

### Modellauswahlkriterien

Organisationen sollten basierend auf Folgendem wählen:

| Faktor | Überlegung |
|--------|------------|
| **Fähigkeit** | GPT-4 > GPT-3.5 > Open-Source-Modelle; basierend auf Antwortqualitätsbedarf wählen |
| **Kosten** | Open-Source/Llama günstiger; GPT-4 teurer; Embedding-Modelle günstigste |
| **Datenschutz** | Lokale Modelle am besten; on-premises vLLM gut; Cloud-Anbieter wenn Datenweitergabe OK |
| **Geschwindigkeit** | GPT-3.5 < 2 s; Ollama variiert je nach Hardware; muss SLA erfüllen |
| **Compliance** | Manche Branchen erfordern spezifische Modelle oder nur on-premises |

---

## Antwortgenerierung

### Antwortgenerierungs-Pipeline

Wenn Benutzer eine Frage stellt:

1. **Kontext-Vorbereitung:** Abgerufene Dokumente mit Metadaten formatiert
2. **Prompt-Konstruktion:** Benutzerfrage + Dokumente + Systemanweisungen
3. **Modell-Aufruf:** Konfiguriertes LLM aufrufen
4. **Streaming:** Antwort zurück zum Benutzer streamen (nicht auf vollständige Generierung warten)
5. **Nachverarbeitung:** Quellen extrahieren, Antwort formatieren

### Prompt-Struktur

System sendet an LLM:

```
System-Prompt:
  "Sie sind ein hilfreicher Assistent zur Beantwortung von Fragen über unsere Organisation.
   Verwenden Sie die bereitgestellten Dokumente für Antworten. Zitieren Sie immer Quellen.
   Wenn Informationen nicht in den Dokumenten sind, sagen Sie dies."

Kontext (abgerufene Dokumente):
  Dokument 1 (Titel, Auszug)
  Dokument 2 (Titel, Auszug)
  ...

Benutzerfrage:
  "Was ist unsere Richtlinie zu X?"

Aufgabenanweisungen:
  "Antworten Sie nur anhand der bereitgestellten Dokumente.
   Antwort formatieren als: [Direkte Antwort] Quellen: [Quellen auflisten]"
```

### Antwortformat

LLM generiert Antworten gemäß Prompt:

```
Antwort: "Laut unseren Richtliniendokumenten ist X unter folgenden
Bedingungen erlaubt:
1. Bedingung A
2. Bedingung B
3. Bedingung C

Zusätzlicher Kontext aus neuesten Aktualisierungen..."

Quellen:
- Unternehmensrichtlinie zu X (aktualisiert Jan 2024)
- Manager-Handbuch Abschnitt 3.2
```

OPAA dann:
- Parst die Antwort
- Verlinkt Quellen mit tatsächlichen Dokumenten
- Fügt klickbare Dokument-Links hinzu
- Zeigt dem Benutzer an

---

## Modellkonfiguration

### Temperatur & Parameter

Jeder Modell-Anwendungsfall kann benutzerdefinierte Einstellungen haben:

```
GenerierungsEinstellungen:
  model: "gpt-4"
  temperature: 0.5
  top_p: 0.9
  max_tokens: 1024
  frequency_penalty: 0.0
```

**Parameter-Leitfaden:**
- **temperature:**
  - Niedrig (0,1-0,3): Faktischer, weniger kreativ (gut für Frage-Antwort)
  - Hoch (0,7-0,9): Kreativer, weniger fokussiert (gut für Brainstorming)
  - Empfohlen für OPAA: 0,3-0,5 (Balance zwischen Kreativität und Genauigkeit)

- **max_tokens:**
  - Begrenzt Antwortlänge
  - Empfohlen: 1.024-2.048 für detaillierte Antworten
  - Kürzer (512) für Chat-Plattformen verwenden

- **top_p:**
  - Kontrolliert Vielfalt (0-1)
  - 0,9 ist guter Standard
  - Niedriger für konservativere Antworten

### Multi-Modell-Strategie

OPAA unterstützt verschiedene Modelle für verschiedene Aufgaben:

```
Modellauswahl:
  QA-Generierung: "gpt-4"                   # Beste Qualität
  Embeddings: "text-embedding-3-small"       # Günstig, schnell
  Zusammenfassung: "gpt-3.5-turbo"           # Schnell, ausreichend gut
  Klassifizierung: "gpt-3.5-turbo"           # Kosteneffektiv
```

Vorteile:
- Teure Modelle nur dort verwenden, wo nötig
- Kosten vs. Qualität pro Aufgabe optimieren
- Schnellere Antworten wo Geschwindigkeit wichtiger ist

### Fallback-Strategie

Wenn primäres Modell nicht verfügbar:

```
Primär: "gpt-4"
Fallback: "gpt-3.5-turbo"  # Etwas niedrigere Qualität, immer verfügbar
Fallback: "mistral-7b" (selbst gehostet)  # Letzter Ausweg
```

Wenn alle fehlschlagen, System:
- Gibt abgerufene Dokumente ohne Generierung zurück
- Zeigt Benutzer: "Ich fand relevante Dokumente, konnte aber keine Zusammenfassung generieren. Quellen unten."
- Protokolliert Fehler für Admin-Überprüfung

---

## Embedding-Modelle

### Embedding-Konfiguration

Getrennt vom Generierungsmodell:

```
EmbeddingEinstellungen:
  model: "text-embedding-3-small"  # OpenAI
  dimension: 1536
  batch_size: 100
```

### Embedding-Modell-Auswahl

Verschiedene Organisationen wählen basierend auf:
- **OpenAI-Embeddings:** Beste Qualität, Cloud-basiert
- **Open-Source:** All-MiniLM, ONNX-Modelle, lokale Alternativen
- **Spezialisiert:** Domänenspezifische Embeddings für technische Dokumente

**Wichtig:** Embedding-Modell-Wahl beeinflusst Suchqualität. Das Ändern des Embedding-Modells erfordert eine Neu-Indizierung aller Dokumente.

### Modell-übergreifende Suche

Erweitert: Verschiedene Embedding- und Generierungsmodelle verwenden:
- Embedding von Jina.ai (technisch)
- Generierung von Claude (Qualität)
- Bessere Ergebnisse für spezialisierte Dokumente

---

## Erweiterte LLM-Features

### Streaming-Antworten

OPAA streamt Antworten während der Generierung:
- Benutzer sieht Antwort Zeichen-für-Zeichen erscheinen
- Bessere UX (fühlt sich schneller an, interaktiv)
- Kann Generierung stoppen, wenn Antwort abschweift

### Function Calling

Wenn LLM Function Calling unterstützt (GPT-4, Claude):

```
LLM kann Funktionen aufrufen:
  get_document(id)     → vollständiges Dokument abrufen
  search_more(query)   → eine weitere Suche durchführen
  format_table(data)   → Daten als Tabelle formatieren
```

OPAA kann dies nutzen, um:
- Automatisch vollständige Dokumente bei Bedarf abzurufen
- Mehrstufiges Reasoning durchzuführen
- Komplexe Antworten zu formatieren

### Vision/Multimodal-Unterstützung

Wenn Organisation visuelle Dokumente hat:
- GPT-4-vision kann Bilder/PDFs analysieren
- Kann Text aus gescannten Dokumenten extrahieren
- Kann Fragen zu Diagrammen beantworten

---

## Kostenoptimierung

### Kostentreiber

OPAA-Kosten hängen ab von:
- **Embedding-Modell:** Günstigste (Bruchteile von Cent pro 1.000 Token)
- **Generierungsmodell:** Teuerste (Dollar pro 1.000 Token)
- **Abfragevolumen:** Mehr Fragen = höhere Kosten

### Kostensenkungsstrategien

1. **Günstigere Modelle wo möglich verwenden:**
   - GPT-3.5-turbo statt GPT-4 verwenden (5x günstiger)
   - Embeddings-small statt large verwenden
   - Lokale Modelle verwenden (kostenlos nach Infrastrukturkosten)

2. **Caching implementieren:**
   - Häufige Fragen cachen
   - Dokument-Embeddings cachen
   - Unveränderte Dokumente nicht neu einbetten

3. **Hybridansatz:**
   - Lokale Modelle für 80% der Fragen verwenden
   - GPT-4 nur für komplexe Abfragen verwenden
   - Automatisches Routing basierend auf Fragenkomplexität

4. **Batching:**
   - Embedding-Generierung außerhalb der Stoßzeiten bündeln
   - Fragenbeantwortung für Berichtserstellung bündeln

**Typische Kosten:**
- Kleine Organisation (100 Abfragen/Tag): 50-200 €/Monat
- Große Organisation (10.000 Abfragen/Tag): 5.000-20.000 €/Monat
- Mit lokalen Modellen: Infrastrukturkosten + Strom

---

## Sicherheit & verantwortungsvolle Nutzung

### Jailbreak-Prävention

Das System ist so konzipiert, LLM-Jailbreaks zu verhindern:
- Strikte System-Prompts begrenzen Modellverhalten
- Abgerufene Dokumente beschränken Antworten auf Organisationswissen
- Benutzer kann Modellanweisungen nicht direkt manipulieren
- Systemanweisungen gesperrt (nicht über Chat änderbar)

### Halluzinations-Minderung

OPAA reduziert inhärent Halluzinationen:
- Alle Antworten in abgerufenen Dokumenten verankert
- Modell kann keine Fakten erfinden, die nicht in Quellen sind
- Konfidenz-Scores angezeigt (0 Konfidenz = keine Quellen)
- Benutzer können Behauptungen in Quelldokumenten verifizieren

### Inhaltsfilterung

Wenn Organisation es erfordert:
- Profanitäts-Filterung
- PII-Schwärzung
- Maskierung sensibler Informationen

Diese können als Nachverarbeitungsschritte hinzugefügt werden.

---

## Rate Limiting & Kontingente

### Rate Limits

Konfigurierbar pro Benutzer/Workspace:

```
RateLimits:
  pro_benutzer: 100 Abfragen/Tag
  pro_team: 1.000 Abfragen/Tag
  global: 10.000 Abfragen/Tag
```

### Token-Kontingente

Kann auch nach Token kontingentieren (granularer):

```
TokenKontingente:
  pro_benutzer: 50.000 Token/Tag
  pro_team: 500.000 Token/Tag
```

Bei Überschreitung:
- Benutzer sieht: "Tageskontingent überschritten. Morgen erneut versuchen."
- Admin benachrichtigt
- Abfrage noch für Audit geloggt

---

## Monitoring & Observability

### Was geloggt wird

Für jede Abfrage loggt das System:
- Benutzer-ID
- Workspace
- Frage (optional, kann für Datenschutz deaktiviert werden)
- Anzahl abgerufener Dokumente
- Verwendetes Modell
- Generierungs-Token verwendet
- Antwortzeit
- Benutzer-Feedback (falls vorhanden)

### Metrik-Dashboards

Admins können sehen:
- Häufigste gestellte Fragen
- Modell-Leistung (Antwortqualität)
- Kostenaufschlüsselung nach Benutzer/Workspace
- API-Fehler und -Ausfälle
- Modell-Latenzverteilung

### Kosten-Tracking

Detaillierte Kostenaufschlüsselung:
- Kosten pro Abfrage
- Kosten pro Benutzer
- Kosten pro Modell
- Trends über Zeit

---

## LLM-Anbieter wechseln

### Wie man wechselt

1. **Konfiguration ändern:**
   ```
   ALT: LLM_API_KEY=sk-openai-xxx
   NEU: LLM_API_KEY=sk-claude-xxx
        LLM_MODEL="claude-3-sonnet"
   ```

2. **Dienst neu starten** (oder Hot-Reload)

3. **Testen:** Frage stellen, Antwortqualität verifizieren

**Das ist alles.** Keine Code-Änderungen, keine Datenmigration, keine Neu-Indizierung.

### Überlegungen

- Verschiedene Modelle können unterschiedliche Ausgabequalität haben
- Verschiedene Modelle haben unterschiedliche Geschwindigkeit/Kosten
- Neues Modell möglicherweise zuerst mit Teilmenge der Benutzer testen
- Embedding-Modell kann unabhängig geändert werden (erfordert Neu-Indizierung)

---

## Integrationspunkte

- **Daten-Indizierung:** Verwendet Embedding-Modell zur Erstellung von Dokument-Embeddings
- **Benutzer-Frontends:** Empfängt generierte Antworten, streamt an Benutzer
- **Zugangskontrolle:** Respektiert Dokument-Berechtigungen vor der Antwort
- **Deployment-Infrastruktur:** Verwaltet API-Anmeldeinformationen, Rate Limiting

---

## Offene Fragen / Zukünftige Erweiterungen

- Sollte OPAA feinabgestimmte Modelle spezifisch für die Organisation unterstützen?
- Sollten wir automatische Modellauswahl basierend auf Fragenkomplexität implementieren?
- Sollten wir Prompt-Engineering-Best-Practices unterstützen (Chain-of-Thought, usw.)?
- Sollten wir A/B-Testing anbieten (verschiedenen Benutzern verschiedene Modelle zeigen)?
- Sollte Kostenoptimierung automatisch sein (günstigstes funktionierendes Modell wählen)?
- Sollten wir lokales Modell-Serving (CUDA, Apple Metal) nativ unterstützen?

---

## Erfolgs-Metriken

- **Antwortqualität:** % der von Benutzern als hilfreich bewerteten Antworten
- **Kosteneffizienz:** Kosten pro Abfrage, Kosten pro erfolgreicher Interaktion
- **Latenz:** P95-Antwortzeit an Benutzer
- **Modell-Leistung:** Fehlerraten, Halluzinationsraten
- **API-Verfügbarkeit:** % erfolgreicher API-Aufrufe an LLM-Anbieter
- **Benutzerakzeptanz:** Wachstum der Anzahl von Abfragen über Zeit
