# Issue #658 — feat(frontend): Typografie, Dichte und Komponentenmetrik an Mockup 1a angleichen (Quicksand, Feinraster, weiße Menüs)
- Geschlossen: 2026-08-20 (completed)
- Labels: enhancement, frontend, size:M
- PRs: #660 (2026-08-20)

**Laut Issue:** Forensischer Abgleich mit Mockup 1a zeigte systematische Abweichungen bei Schriftart (Inter statt "Sklow"), UI-Grundgrößen, Seitenleisten-Metrik, Menü-Optik über Navy, Chat-Bubble-Gestaltung und Control-Metrik (Radius, Höhe, Padding). Gefordert: Quicksand als Schrift, Feinraster nach Mockup-Werten, Seitenleiste auf 272 px, helle Menü-Panels, Chatfläche optisch an Mockup angeglichen (nur Optik, kein Funktionsvorgriff auf #590/#591).

**Geliefert:** Deckt sich mit der Forderung — Quicksand via `@fontsource/quicksand` mit Inter-Fallback, Typografie-Feinraster (14,5/1.65 Fließtext, 13 px UI, 9,5 px Eyebrows), Controls auf Radius 6/Höhe 34, Seitenleiste auf 272 px mit hellen Menü-Panels über dem Navy-Block, Chatfläche als reiner Fließtext ohne Avatar/Bubble. Guidelines synchron aktualisiert, Barrierefreiheit (Tab-Reihenfolge trotz Hover-Aktionen, `aria-hidden` auf der sichtbaren Kopfzeile) im PR dokumentiert.

**Verifikation:** `frontend/src/components/chat/MessageBubble.tsx` und `frontend/src/components/chat/ChatInput.tsx` existieren im Worktree.

**Themen:** frontend, design, typografie, mockup, accessibility
