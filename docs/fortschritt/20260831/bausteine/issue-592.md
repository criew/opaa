# Issue #592 — feat(frontend): Belegfenster — seitliche Leiste mit allen Fundstellen einer Antwort
- Geschlossen: 2026-08-20 (completed)
- Labels: enhancement, frontend, size:L
- PRs: #676 (2026-08-20)

**Laut Issue:** Neue seitliche Leiste (Mockup 1i) mit allen Fundstellen einer Antwort — Kopf, Suchfeld, Filter „Nur zitierte", Gruppierung nach Dokument, je Stelle Ziffer/Zitat/Fundort/„Im Dokument öffnen", Fußzeile „Stand der Antwort". Fokusführung: Öffnen fängt Fokus, Escape schließt mit Rückkehr zum Auslöser. PDF-Export ausdrücklich außerhalb des Umfangs.

**Geliefert:** Wie gefordert umgesetzt (`SourceEvidenceDrawer`, Suchfeld, `aria-pressed`-Filter, Dokumentzeilen nach Relevanz sortiert, Fokusfang/Escape-Rückkehr testbelegt, mobil Vollbild/Desktop 440px). Bewusst offen gelassen, im Issue selbst so vorgesehen: wörtliche Zitate und Fundorte je Stelle brauchen Chunk-Metadaten der API, die noch fehlen — als Folge-Issue #667 (gemeinsam mit #590) festgehalten; bis dahin trägt jede Zeile nur, wofür die API heute bürgt. Zusätzlicher Nebenbefund im PR behoben: Ein Zitier-Flag-Konflikt in `buildCitationIndex` führte zu doppelt gelisteten Dokumenten — mit Test abgesichert.

**Verifikation:** `frontend/src/components/chat/SourceEvidenceDrawer.tsx` existiert im aktuellen Code.

**Themen:** frontend, chat, ui, retrieval, barrierefreiheit, quellenangaben
