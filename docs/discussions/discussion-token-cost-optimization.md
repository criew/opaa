# Token-Kosten-Optimierung bei der Issue-Implementierung

**Ziel:** Token-Verbrauch reduzieren, wenn KI-Agenten an GitHub-Issues arbeiten, um Entwicklungskosten zu senken und gleichzeitig die Code-Qualität zu erhalten.

---

## Schnelle Gewinne (Sofortige Umsetzung)

### 1. **Kompakte ADR-Zusammenfassungen im Agenten-Gedächtnis** ⭐⭐⭐

**Aktueller Zustand:** Agenten lesen vollständige ADR-Dateien (~500–1000 Token je Datei) wiederholt.

**Lösung:**
- `.claude/agent-memory/adr-summary.md` mit Aufzählungs-Zusammenfassungen erstellen (max. 200 Token)
- Nur bei Bedarf für Vertiefungen auf vollständige ADRs verlinken
- Zusammenfassungen vierteljährlich aktualisieren, wenn neue ADRs hinzukommen

**Beispielformat:**
```markdown
## ADR-Kurzreferenz

**0001 - Kollaborations-Workflow**
- PR-basierter Ablauf, kein direktes Pushen auf main
- Conventional Commits erforderlich

**0002 - MVP-Technologie-Stack**
- Backend: Spring Boot 21 + Gradle
- Frontend: React 19 + Vite
- DB: PostgreSQL 18 + pgvector

**0003 - Code-Formatierung**
- Spotless (Backend), Prettier (Frontend)
```

**Einsparungen:** ~400 Token pro Issue × 20 Issues/Monat = **8.000 Token/Monat**

---

### 2. **Smartes Test-Überspringen (Bereits aktualisiert!)** ⭐⭐⭐

Dies wurde bereits in `workflow.md` implementiert, aber die Nutzung sicherstellen:

**Vorher (vollständiger Stack):**
```bash
# Führt 6 Schritte aus = ~60 Sekunden, sogar bei Dokumentänderungen
./gradlew build && npm test && npm run build
```

**Nachher (nur Dokumentation):**
```bash
# Nur Formatierung (2 Schritte) = ~10 Sekunden
./gradlew spotlessApply && npm run format
```

**Einsparungen:** ~2 Minuten pro Dokumentations-PR × 5 Docs/Monat = **10 Token vermieden/Monat**
*(Zeit = geringerer Token-Verbrauch durch schnellere Iteration)*

---

### 3. **Vorgefilterte Glob-Muster für die Code-Suche** ⭐⭐

**Aktuell:** Agenten suchen oft mit breiten Mustern wie `**/*.java` im gesamten Backend (1000+ Dateien).

**Lösung:** `.claude/rules/search-patterns.md` verwenden

```markdown
## Häufige Suchmuster

### Backend-Struktur
- Spring Boot Config: `backend/src/main/resources/application*.yml`
- API-Controller: `backend/src/main/java/io/opaa/api/**/*Controller.java`
- Integrationstests: `backend/src/test/java/io/opaa/integration/**/*IntegrationTest.java`
- Domain-Modelle: `backend/src/main/java/io/opaa/domain/**/*.java`

### Frontend-Struktur
- Komponenten: `frontend/src/components/**/*.tsx`
- Hooks: `frontend/src/hooks/**/*.ts`
- API-Client: `frontend/src/api/client.ts`
- Tests: `frontend/src/**/*.test.tsx`
```

**Vorteil:** Agenten finden die richtigen Dateien 40% schneller und vermeiden das erneute Lesen falscher Dateien.

**Einsparungen:** ~3–5 Token pro Issue × 20 Issues = **60–100 Token/Monat**

---

### 4. **Abhängigkeits-Cache mit Versions-Snapshot** ⭐⭐

**Problem:** Jeder Gradle/npm-Rebuild kostet Token für das Parsen von Abhängigkeitsbäumen.

**Lösung:** `docs/DEPENDENCY-SNAPSHOT.md` erstellen

```markdown
## Aktuelle Abhängigkeiten (Stand: 2026-03-01)

### Backend (Gradle)
- Spring Boot: 3.5.10
- Spring AI: 1.1.2
- PostgreSQL Driver: 42.x.x
- Testcontainers: 1.x.x

### Frontend (npm)
- React: 19.x.x
- Material UI: 7.x.x
- Vitest: 2.x.x
- MSW: 2.x.x
```

**Nutzung:** Wenn ein Agent fragt „Welche Spring-Boot-Version verwenden wir?", kein Parsen von Dateien notwendig.

**Einsparungen:** ~2–3 Token pro Issue = **40–60 Token/Monat**

---

## Mittlerer Aufwand

### 5. **Feature-Checklisten für häufige Aufgaben erstellen** ⭐⭐

Viele Issues sind ähnlich (API-Endpoint hinzufügen, React-Komponente hinzufügen, Test hinzufügen). Wiederverwendbare Templates erstellen.

**Datei:** `.claude/issue-templates/api-endpoint.md`

```markdown
# Checkliste für API-Endpoint-Implementierung

## 1. Controller-Methode
- [ ] Methode in `backend/src/main/java/io/opaa/api/**/*Controller.java` erstellen
- [ ] `@PostMapping("/api/v1/...")` Annotation verwenden
- [ ] `ResponseEntity<?>` mit korrekten Status-Codes zurückgeben

## 2. Service-/Domain-Logik
- [ ] Logik in `backend/src/main/java/io/opaa/[feature]/` hinzufügen (nicht im Controller!)
- [ ] Unit-Test in `backend/src/test/java/io/opaa/[feature]/` schreiben

## 3. Integrationstest
- [ ] Test in `backend/src/test/java/io/opaa/integration/` hinzufügen
- [ ] `DocumentIndexingIntegrationTest` als Template verwenden
- [ ] Testcontainers-Setup bei Bedarf einbeziehen

## 4. Frontend-Client
- [ ] Methode zu `frontend/src/api/client.ts` hinzufügen
- [ ] TypeScript-Typen in `frontend/src/types/` aktualisieren
- [ ] React-Query-Hook bei Bedarf hinzufügen

## 5. Pre-Push-Checkliste
- [ ] Ausführen: `cd backend && ./gradlew spotlessApply`
- [ ] Ausführen: `cd backend && ./gradlew build`
- [ ] Ausführen: `cd frontend && npm run format && npm run lint`
```

**Vorteil:** Kein Suchen/Lesen von Dateien zum Verstehen des Musters notwendig. Agent verwendet Template direkt.

**Einsparungen:** ~10–15 Token pro API-Issue × 10 Issues/Jahr = **100–150 Token/Jahr**

---

### 6. **Häufige Fehlermuster dokumentieren** ⭐⭐

`docs/COMMON-ERRORS.md` erstellen

```markdown
# Häufige Entwicklungsfehler & Lösungen

## 1. "Cannot find symbol" im Gradle-Build
**Ursache:** Fehlender Import oder Tippfehler im Klassennamen
**Lösung:** `libs.versions.toml` auf korrekten Artefaktnamen prüfen
**Beispiel:** `io.opaa.api.QueryController` nicht `io.opaa.api.Query` (fehlende `Controller`-Endung)

## 2. Prettier-Formatierung schlägt fehl
**Ursache:** Windows-CRLF-Zeilenenden vs. Unix-LF
**Lösung:** `npm run format` ausführen — korrigiert automatisch

## 3. Testcontainers-Tests schlagen fehl
**Ursache:** Docker läuft nicht
**Lösung:** `docker ps` — falls fehlschlägt, Docker Desktop starten

## 4. TypeScript-Typ-Fehler im Frontend
**Ursache:** Backend-Response geändert, aber Frontend-Typen nicht aktualisiert
**Lösung:** `npx openapi-generator-cli` ausführen, falls OpenAPI-Spec verwendet wird
```

**Einsparungen:** ~5–10 Token pro Issue (Debug-Erkundung vermeiden) × 15 Issues = **75–150 Token/Monat**

---

## Architekturverbesserungen (Größerer Aufwand)

### 7. **Test-Suiten nach Geschwindigkeit trennen** ⭐

**Problem:** Agenten führen die vollständige Test-Suite aus (`./gradlew build`), einschließlich langsamer Integrationstests.

**Lösung:** Test-Suiten trennen:

```bash
# Nur schnelle Unit-Tests (~5 Sekunden)
./gradlew testUnit

# Langsame Integrationstests (Testcontainers, ~30 Sekunden)
./gradlew testIntegration

# Alle Tests
./gradlew test
```

**Auswirkung:** Agenten überspringen langsame Tests für Nicht-Backend-Issues, was CI-Zeit spart = weniger Token-Wiederholungen.

---

### 8. **Boilerplate mit Code-Generatoren reduzieren** ⭐⭐

Ein Code-Generierungsskript für sich wiederholende Muster erstellen:

```bash
#!/bin/bash
# scripts/generate-api-endpoint.sh <feature-name>
# Generiert: Controller, Service, DTO, Test-Skelett
```

**Vorteil:** Agenten rufen das Skript auf, anstatt manuell zu coden → weniger Iterationen, weniger Token-Writes.

---

## Überwachung & Messung

### Token-Verbrauch pro Issue verfolgen

Eine `.claude/token-log.csv` erstellen:

```csv
Datum,Issue#,Feature,Token,Status,Anmerkungen
2026-03-01,42,API-Endpoint,4200,✅,ADR-Zusammenfassung verwendet, vollständigen ADR-Read vermieden
2026-03-01,43,UI-Komponente,3100,✅,Checklisten-Template befolgt
2026-03-01,44,Bug-Fix,5200,⚠️,Lange Debug-Erkundung, könnte optimiert werden
```

**Analyse:**
- Durchschnittliche Token/Issue: Ziel ist < 3500 bis 2026-06
- Identifizieren, welche Features „teuer" sind → dafür Templates erstellen

---

## Empfohlene schnelle Umsetzungsreihenfolge

1. **Woche 1:** `adr-summary.md` erstellen (30 Min) → **8.000 Token/Monat einsparen**
2. **Woche 1:** `search-patterns.md` erstellen (30 Min) → **60–100 Token/Monat einsparen**
3. **Woche 1:** `COMMON-ERRORS.md` dokumentieren (1 Stunde) → **75–150 Token/Monat einsparen**
4. **Woche 2:** API-Endpoint-Checkliste erstellen (1 Stunde) → **100+ Token/Monat einsparen**
5. **Laufend:** Token-Verbrauch überwachen und häufige Pfade optimieren

---

## Agenten-spezifische Tipps

### Bei der Implementierung von Issues:

**ADR-Zusammenfassung verwenden, nicht vollständige Dateien:**
```
❌ "Ich lese alle ADRs, um die Architektur zu verstehen..."
✅ "Ich prüfe adr-summary.md für schnellen Kontext..."
```

**Checklisten nutzen:**
```
❌ "Ich suche nach ähnlichen API-Endpoints, um das Muster zu verstehen..."
✅ "Ich folge der api-endpoint.md-Checkliste, die ich erstellt habe..."
```

**Neuberechnungen vermeiden:**
```
❌ "Ich führe die vollständige Test-Suite zur Validierung aus..."
✅ "Ich führe zuerst Unit-Tests aus, dann bei Bedarf Integrationstests..."
```

---

## Projizierte Einsparungen

| Optimierung | Token/Monat | Umsetzung | Priorität |
|---|---|---|---|
| ADR-Zusammenfassung | 8.000 | 30 Min | Hoch |
| Suchmuster | 60–100 | 30 Min | Hoch |
| Häufige-Fehler-Dok. | 75–150 | 1 Stunde | Mittel |
| API-Endpoint-Template | 100–150 | 1 Stunde | Mittel |
| Test-Suite-Trennung | 500–1.000 | 2 Stunden | Mittel |
| Fehlermuster-Dok. | 100–200 | 1 Stunde | Niedrig |
| **Gesamtpotenzial** | **~8.900–9.700/Monat** | **~6,5 Stunden** | — |

**Konservative Schätzung:** 20–25% Token-Reduktion mit den ersten 3 Optimierungen in Woche 1.

---

## Weitere Ressourcen

- `AGENTS.md` — Erwartungen an das Agentenverhalten
- `CLAUDE.md` — Claude-spezifische Anweisungen
- `STATUS.md` — Aktueller Umsetzungsstand je Themenbereich (löst das frühere `MVP-STATUS.md` ab)
- `.claude/agent-memory/` — Persistente Wissensbasis
