# Issue #40 — feat(frontend): Markdown-Renderer für LLM-Antworten
- Geschlossen: 2026-02-26 (completed)
- Labels: enhancement, mvp, frontend
- PRs: #45 (2026-02-26)

**Laut Issue:** LLM-Antworten wurden als Plain-Text dargestellt, obwohl sie häufig Markdown enthalten (Überschriften, Listen, Code-Blöcke, Links). Gefordert war die Integration einer Markdown-Rendering-Bibliothek (z.B. `react-markdown`) mit Syntax-Highlighting für Code-Blöcke.

**Geliefert:** PR #45 integriert `react-markdown` + `remark-gfm` + `rehype-highlight` in einer neuen `MarkdownRenderer`-Komponente. Assistant-Nachrichten werden gerendert, User-Nachrichten bleiben bewusst Plain-Text. Zusätzlich (nicht explizit im Issue gefordert): Quellenreferenzen am Antwortende (`(dateiname.pdf)`) werden als separates "Quelle:"-Label dargestellt — eine Vorwegnahme von Aspekten aus #42/#37.

**Verifikation:** `frontend/src/components/chat/MarkdownRenderer.tsx` und `MessageBubble.tsx` existieren im heutigen Code weiterhin.

**Themen:** frontend, chat-ui, markdown, quellenanzeige
