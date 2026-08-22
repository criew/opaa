# Issue #352 — Zielbild der Chat-Kanäle festlegen
- Geschlossen: 2026-08-14 (completed)
- Labels: documentation, size:S
- PRs: #379 (2026-08-14)

**Laut Issue:** Teil von #344. README und `docs/features/user-frontends.md` nannten Mattermost, RocketChat, Slack, Telegram, Signal und WhatsApp als Kanäle. Zu klären war, welche davon im Zielbild bleiben, welche entfallen — mit dem Hinweis, dass für die öffentliche Verwaltung Matrix/Element und verbreitete self-hosted Team-Chats tragend sind, Consumer-Messenger dagegen wenig Wert und Datenabfluss-Fragen bringen. Ergebnis sollte eine Entscheidungsvorlage sein, ohne Umsetzung.

**Geliefert:** Reine Dokumentationsänderung. Im Zielbild bleiben ausschließlich selbst betriebene Team-Chats — der Matrix-basierte Chat-Baustein des souveränen Arbeitsplatzes, Mattermost und Rocket.Chat, alle in Phase 3. Slack, Telegram, Signal, WhatsApp entfallen ersatzlos, begründet mit dem Identitätsargument (Kanal muss auf ein OPAA-Konto abbildbar sein) und der Übermittlungsproblematik. Die REST-API bleibt als offener Weg für weitere Kanäle. Geändert wurden `docs/STATUS.md`, ADR-0014 (Nachtrag) und `docs/features/user-frontends.md`. Kein Anwendungscode betroffen — passt zur Vorgabe des Issues.

**Verifikation:** `docs/features/user-frontends.md` existiert im Worktree. Reine Dokumentationsentscheidung, keine Codeverifikation nötig.

**Themen:** kanäle, doku, produktausrichtung, chat, öffentliche-verwaltung
