# Issue #1016 — fix(frontend): Markdown-Überschriften in Chat-Antworten pro Nachricht auf gültige Ebenen normalisieren

- Geschlossen: 2026-08-28 (completed)
- Labels: bug, frontend, size:S
- PRs: #1019 (2026-08-28)

**Laut Issue:** Beim Review von #958 bestätigter, vorbestehender Befund derselben Klasse:
Der `MarkdownRenderer` bildet Markdown-Überschriften aus Assistenten-Antworten auf tiefe
Heading-Elemente ab (`#` → `h5` usw.), wodurch datenabhängig Ebenen übersprungen werden —
weder vom Abschluss-Audit (prüfte den Leerzustand) noch von #1015 erfasst.

**Geliefert:** PR #1019 normalisiert die Überschriften je Nachricht über ein
rehype-Plugin (`rehypeNormalizeHeadings`): Die Rang-Folge wird pro Nachricht auf eine gültige,
lückenlose Ebenenfolge komprimiert, die visuelle Größe bleibt von der semantischen Ebene
entkoppelt.

**Verifikation:** Commit `a4dac7e2` auf `main`; `MarkdownRenderer.tsx` nutzt
`rehypeNormalizeHeadings` aus `markdownHeadings`.

**Themen:** Barrierefreiheit, Markdown, Chat, Überschriftenstruktur
