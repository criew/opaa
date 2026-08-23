# Issue #49 — fix: crypto.randomUUID fails on non-HTTPS connections
- Geschlossen: 2026-02-26 (completed)
- Labels: bug, mvp
- PRs: #51 (2026-02-26)

**Laut Issue:** Beim Zugriff auf das Frontend über HTTP von einer Nicht-Localhost-Adresse (z.B. LAN-IP) schlug das Senden einer Chat-Nachricht mit `crypto.randomUUID is not a function` fehl, da diese Web-Crypto-API nur in sicheren Kontexten (HTTPS/localhost) verfügbar ist. Gefordert war ein `generateId()`-Helper mit Fallback auf Timestamp+Zufallsstring.

**Geliefert:** PR #51 setzt den vorgeschlagenen Fix exakt um und behebt gemeinsam Issue #50 (Server-Bind-Adresse), da beide Probleme denselben LAN-Zugriffs-Anwendungsfall betreffen.

**Verifikation:** `frontend/src/stores/chatStore.ts` enthält weiterhin `function generateId(): string { return crypto.randomUUID?.() ?? ... }`, an zwei Stellen zur ID-Erzeugung genutzt.

**Themen:** frontend, bugfix, netzwerkzugriff, chat
