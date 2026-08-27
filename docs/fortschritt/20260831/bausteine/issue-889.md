# Issue #889 — refactor(chat): Chat-Pfad als explizite Pipeline — Transaktions-Kartenhaus und COUNT(*)-Sequenz ablösen
- Geschlossen: 2026-08-25 (completed)
- Labels: enhancement, backend, size:L
- PRs: #890 (2026-08-25)

**Laut Issue:** Teil von Epic #826, Phase 4. Vier Punkte: (1) ein fragiles Transaktions-Kartenhaus im Chat-Pfad (NOT_SUPPORTED + manuelles TransactionTemplate + EAGER-Collection), (2) eine `nextSequenceFor`-Berechnung per `COUNT(*)`, die nach einer gelöschten Nachricht dauerhaft kollidiert, (3) ein Permission-History-Drift-Check, der bei jeder Anfrage drei Zusatz-Queries ausführt, nur um zu loggen, (4) eine `QueryConfiguration` mit 7 manuell verdrahteten Beans statt `@Service`.

**Geliefert:** Alle vier Punkte umgesetzt. Sequenz jetzt über `MAX(sequence)+1`. Pipeline in klar benannte Lese-/LLM-/Schreibphasen aufgeteilt, isolierter Schreibversuch in neuen `@Service ChatMessageWriter` (`REQUIRES_NEW`) ausgelagert, `appendTurn` behält `NOT_SUPPORTED` als strukturelle Garantie gegen die #299/#525-Deadlock-Konstellation. Permission-History-Stichprobe über neue Property `permissionHistorySampleRate` — **Default bewusst auf 1.0 belassen** (Koordinator-Entscheidung), also keine Verhaltensänderung ohne Maintainer-Freigabe, obwohl das Issue eine Absenkung nahelegte. 5 der 7 `QueryConfiguration`-Beans auf `@Service` umgestellt, `chatMemory`/`QueryMetrics` bleiben als `@Bean` (echte Konfiguration).

**Verifikation:** `backend/src/main/java/io/opaa/chat/ChatMessageWriter.java` existiert im Worktree.

**Themen:** chat, refactoring, transaktionen, epic-826, retrieval
