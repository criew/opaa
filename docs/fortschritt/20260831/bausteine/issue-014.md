# Issue #14 — feat(ui): implement chat interface with source references and feedback placeholders
- Geschlossen: 2026-02-20 (completed)
- Labels: enhancement, mvp, frontend, size:L
- PRs: #27 (2026-02-20)

**Laut Issue:** Chat-Q&A-Screen mit Nachrichtenverlauf, Quellenkarten (Dateiname, Relevanz, Textauszug), Feedback-Buttons (nur visuell) und Access-Level-Badges (Public/Internal/Confidential, statisch). Entwicklung gegen MSW-Mocks, Ladezustand, Fehlerzustand, Auto-Scroll, responsives Layout.

**Geliefert:** PR #27 liefert genau das plus zusätzlich Routing (Chat/Documents/Settings), Zustand-Store für Chat- und UI-State, eigenes dunkles MUI-Theme mit selbst gehosteten Ressourcen (ADR-0004, kein externes CDN) und Design-Referenzdateien unter `docs/design/`. Kein Abweichen vom Issue-Umfang, eher Erweiterung um Infrastruktur (Router, Stores, Theme), die für die spätere Entwicklung gebraucht wurde.

**Verifikation:** `ChatPage.tsx` existiert weiterhin (`frontend/src/pages/ChatPage.tsx`). Die einzelnen PR-Komponenten `SourceCard.tsx` und die ursprüngliche `MessageBubble`-Quellendarstellung wurden seither im Rahmen von Issue #37 und einer späteren Umstellung auf Fußnoten (`SourceFootnotes.tsx`, `SourceEvidenceDrawer.tsx`) ersetzt — die Grundstruktur (Chat, Nachrichtenliste, Eingabe) besteht fort, die Quellendarstellung ist mehrfach weiterentwickelt worden.

**Themen:** frontend, chat-ui, mvp, quellenreferenzen
