# Issue #860 — refactor(backend): DTO-Leak beheben — Services geben Domain-Typen zurück, Mapping in die API-Schicht
- Geschlossen: 2026-08-24 (completed)
- Labels: enhancement, backend, size:L
- PRs: keine direkt verlinkt (siehe Verifikation — Arbeit lief als PR-Serie)

**Laut Issue:** Teil von Epic #826, Phase 4 (Vorstufe zu Befund B1). ~34 öffentliche Service-Methoden nahmen generierte Request-DTOs entgegen und gaben generierte Response-DTOs zurück — Domänenschicht hing an der API-Schicht, zentraler Treiber der Paketzyklen. Als PR-Serie geplant: space → group → library → chat/query, mit Mapper-Konvention (package-private Mapper in `io.opaa.api`, Vorbild `BrandingResponseMapper`).

**Geliefert:** Kein PR ist auf Issue #860 selbst verlinkt, obwohl das Issue als „completed" geschlossen ist. Die im Issue beschriebene PR-Serie lief laut Folge-Issues real: #874 nennt „DTO-Leak-Serie #860" und PR #873; #877 verweist ebenfalls auf diesen Kontext. Der Zielzustand ist im heutigen Code erreicht: ein Grep nach `io.opaa.api.dto` außerhalb von `io.opaa.api` findet nur noch Javadoc-Erwähnungen (Kommentare, keine echten Imports) in `LibraryCreation.java`, `AssetGrantUpsert.java`, `JobTriggerSource.java`, `ChatService.java` sowie die vier `auth`-Controller — exakt die im Abnahmekriterium vorgesehene Ausnahme. Mapper-Klassen wie `SpaceResponseMapper`, `LibraryDocumentResponseMapper`, `ChatResponseMapper` existieren in `io.opaa.api`.

**Verifikation:** `grep -rl "io.opaa.api.dto" backend/src/main/java | grep -v /api/` liefert die vier auth-Controller plus vier Dateien mit reinen Javadoc-Referenzen (keine Imports). `io.opaa.api`-Paket enthält zahlreiche `*ResponseMapper`-Klassen. Zielbild damit erreicht, PR-Verlinkung im Datensatz aber lückenhaft.

**Themen:** backend, refactoring, dto, architektur, api
