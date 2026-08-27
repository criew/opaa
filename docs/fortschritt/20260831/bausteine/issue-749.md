# Issue #749 — fix(chat): Chat-Seite erzeugt äußere Scrollbar — Hauptbereich höher als der Viewport
- Geschlossen: 2026-08-22 (completed)
- Labels: bug, frontend, size:S
- PRs: #750 (2026-08-22)

**Laut Issue:** Maintainer-Beobachtung auf der Demo-Instanz: Beim Öffnen eines bestehenden Chats erschien eine äußere Seiten-Scrollbar zusätzlich zur inneren Scrollbar des Nachrichtenbereichs — Leerraum unterhalb der Fußzeile, Sidebar endete oberhalb des unteren Fensterrands. Gefordert war eine Layout-Korrektur mit identifizierter Ursache und Regressionstest.

**Geliefert:** Wie gefordert, mit präziser Ursachenanalyse. Der unsichtbare `aria-live`-Ankündigungsbereich (`visuallyHidden` → `position: absolute`) am Ende der Nachrichtenliste hatte ohne positionierten Vorfahren den Viewport als Containing Block statt den Scroll-Container — seine "statische Position" wuchs mit der Nachrichtenzahl und entzog sich dem `overflowY: auto`-Clipping. Fix: `position: relative` auf dem `message-list`-Container. Da jsdom keine Layout-Engine hat, wurde der Bug zunächst in echtem Chromium per Playwright-Skript reproduziert (scrollHeight wuchs parallel zur Nachrichtenzahl), dann ein jsdom-Regressionstest für die CSS-Eigenschaft ergänzt sowie eine E2E-Assertion in `space-chats.spec.ts`, die den tatsächlichen Seiten-Overflow in einem echten Browser prüft. Reproduktionsnachweis: `expected 'static' to be 'relative'` vor dem Fix, 7/7 Tests grün danach.

**Verifikation:** Nicht erneut im Code geprüft — Änderung ist eine einzelne CSS-Eigenschaft in `frontend/src/components/chat/MessageList.tsx`, ausführlich im PR belegt.

**Themen:** frontend, chat, layout, bugfix
