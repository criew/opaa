# Discussion: Agenten-Architektur für OPAA — Ist-Stand, Zielbild und Phasen

**Thema:** Technische Umsetzung von nutzerdefinierten Agenten und Skills (Phase 2) in OPAA: Was existiert heute, was fehlt, welche Architektur ist für den On-Prem-Compose-Betrieb realistisch, und in welchen Phasen kommt man dahin. Faktengrundlage: [Tech-Report Agentensysteme und Frameworks](discussion-agentensysteme-und-frameworks.md) und [Tech-Report Laufzeitumgebung und Sandboxing](discussion-agenten-laufzeitumgebung-und-sandboxing.md). Fachliches Zielbild: [docs/features/agents-and-tools.md](../features/agents-and-tools.md).

**Status:** Diskussionsvorschlag. Entscheidungen (insbesondere #349 MCP vs. Plugin, der Asset-Polymorphie-Schnitt und jede Runner-Investition) liegen beim Maintainer.

**Rahmenbedingungen:**

1. **ADR-0021 (Single Instance):** genau ein Backend-Prozess. Agent-Läufe dürfen darauf aufsetzen, aber jeder Neustart killt laufende Arbeit — die Restart-Semantik muss von Anfang an mitgedacht werden.
2. **ADR-0008 (Rechte in der Suche):** Ein Agent recherchiert immer mit den effektiven Rechten des Nutzers, in dessen Auftrag er läuft — nie mit eigenen, nie mit denen des Agent-Autors.
3. **On-Prem-Fähigkeit:** Keine Cloud-Sandbox, kein gehosteter Code-Interpreter als Voraussetzung. Cloud-Varianten sind zulässige Alternativen, nie Bedingung.
4. **Nachweisbarkeit:** Jede Agentenaktion ist auditierbar (bestehender `AuditEventRecorder`-Pfad); schreibende Aktionen laufen durch ein Freigabetor (Zielbild agents-and-tools.md).
5. **Kein fremder Code in Phase A/B:** Die Trennlinie aus dem Sandbox-Report ist verbindlich — solange kein generierter/nutzergelieferter Code und kein stdio-Prozess ausgeführt wird, braucht es keine Sandbox; ab dem ersten Skript ist der Runner Pflicht, nicht optional.

---

## 1. Ist-Stand: Was OPAA heute hat und was fehlt

OPAA ist heute ein **Single-Turn-RAG-Chat**: Frage → Retrieval-Pipeline → genau ein LLM-Call → Antwort mit deterministisch validierten Zitaten. Es gibt drei `ChatClient`-Aufrufstellen (`AnswerGenerationService`, `QueryDecompositionService`, `ChatTitleGenerationService`), alle einschüssig per `.call()` — **kein Tool-Calling, kein Streaming, kein MCP, keine Advisors** im gesamten Backend.

Die Andockpunkte sind aber ungewöhnlich gut:

| Baustein | Ist | Bewertung als Fundament |
|---|---|---|
| **Job-Muster** | `io.opaa.indexing`: `IndexingJob` (Status, Heartbeat `last_progress_at`), `IndexingRunEvent`-Ereignisprotokoll, eigene Thread-Pools, `IndexingJobRecoveryScheduler` (Stale-Sweep + Restart-Recovery) | Fast 1:1 kopierbar für `agent_runs`. Fehlt: Zwischenzustand (ein Indexing-Lauf ist zustandslos wiederholbar, ein Agent-Lauf nicht), Nutzer-Abbruch, Live-Fortschritt zum Browser |
| **Registry-Muster** | `IndexingSourceExecutorRegistry`: neue Quelle = ein Bean, Vollständigkeit beim Start geprüft | exakt die Form einer Tool-Registry |
| **Rechte-/Asset-Modell** | `AssetGrant`/`LibraryAccessService`/`SpaceAssetAssociation` — bewusst generisch benannt („asset", nicht „library"), aber hart auf `library_id` verdrahtet | „Agent als Asset" braucht polymorphe Asset-Identität `(asset_type, asset_id)` + Migration — der größte strukturelle Eingriff |
| **Modellauflösung** | `ActiveChatModelResolver`: genau **ein** aktives Modell systemweit, Laufzeit-Neubau des `ChatClient` aus der DB, verschlüsselte API-Keys | Keimzelle eines Modellkatalogs; für „Modellwahl je Agent, Vorgaben als Obergrenze" (llm-integration.md) muss aus dem Single-Slot ein Katalog mit Aufgabenarten/Policy werden |
| **Audit/Benachrichtigung** | `AuditEventRecorder`/`AuditLogService`, `NotificationService` (minimal, ADR-0019) | trägt Audit-Pflicht und Freigabetore |
| **SSRF-Härtung** | `TargetAddressValidator` (Egress-Prüfung der Indexing-Fetches) | der natürliche Ort für die Agenten-Egress-Allowlist |
| **Frontend** | reines Request/Response, kein SSE/WebSocket | Zwischenschritt-Anzeige eines Agent-Laufs braucht einen Streaming-/Event-Kanal — eigenes Arbeitspaket |

Dokumentarisch ist Phase 2 weit ausgearbeitet ([agents-and-tools.md](../features/agents-and-tools.md): Agent als teilbares Paket, geführtes Onboarding, Prüfstand, Prüfagenten, dreistufige Werkzeuge, MCP), aber es existieren **null Zeilen Code und keine Issues** ([discussion-backlog-neuausrichtung.md](discussion-backlog-neuausrichtung.md): „Bereich D schneiden, sobald #349 entschieden ist").

## 2. Architektur-Grundentscheidungen (Vorschlag)

**E1 — Der Agent Loop wird mit Spring-AI-Bordmitteln gebaut, kein Zusatzframework.** Spring AI 2.0 liefert den Tool-Call-Loop als `ToolCallingAdvisor` mit Erweiterungs-Hooks; für Checkpointing und Freigabetore wird der **user-controlled Loop** über den `ToolCallingManager` gefahren (automatische Schleife aus, eigene Iteration mit Schritt-Persistenz). Embabel (erst ab 1.5 Boot-4-fähig) und LangGraph4j (kleines Team) bleiben Merkpositionen für den Fall echter Multi-Agent-Planung — ihre Konzepte (Checkpoint pro Schritt, Interrupt/Resume) werden framework-frei übernommen. Ein Framework-Lock-in wäre heute eine Wette ohne Bedarfsnachweis.

**E2 — Agenten sind deklarative Objekte, kein Code.** Ein Agent = Systemprompt-Abschnitte aus dem geführten Onboarding + Wissensbindung (Library-Referenzen) + Toolauswahl aus dem kuratierten Katalog + Modellwahl + Parameter + Prüffälle — genau das Paket aus agents-and-tools.md. Das ist zugleich der Industriestandard aller vergleichbaren Plattformen und die Bedingung dafür, ohne Sandbox zu starten.

**E3 — Tool-Ausführung beginnt im Backend-Prozess, mit Rechteprüfung pro Aufruf.** Jedes Tool ist ein Spring-Bean (`ToolCallback`), das selbst autorisiert (aufrufender Nutzer, Space-Kontext, Pfad-/Egress-Allowlists) — Registry nach dem `IndexingSourceExecutorRegistry`-Muster, Vollständigkeit und Rechteklasse (lesend / isoliert / schreibend-mit-Freigabetor, die Dreistufung aus agents-and-tools.md) beim Start geprüft. **Ausnahmslos gilt:** kein Tool, das generierten Code oder nutzerkonfigurierte Kommandos ausführt, solange es keinen Runner gibt; **stdio-MCP ist dauerhaft ausgeschlossen**, MCP nur über Streamable HTTP mit Egress-Allowlist und `SettingsEncryptor`-verwalteten Credentials.

**E4 — Agent-Läufe sind checkpointete Jobs, keine langlebigen Prozesse.** `agent_runs`-Tabelle nach dem Indexing-Muster (Status, Heartbeat, Ereignisprotokoll, eigener Pool, Recovery-Scheduler), erweitert um das, was Indexing nicht braucht: persistierter Schrittzustand (Nachrichtenhistorie, Tool-Ergebnisse, Budgetzähler) nach **jedem abgeschlossenen Schritt**, Statusmaschine mit `WAITING_APPROVAL`, Nutzer-Abbruch, harte Budgets (Schritte, Tokens, Wall-Clock). Damit ist die ADR-0021-Restart-Frage gelöst (Fortsetzung ab letztem Schritt statt Fail-all wie bei Indexing), Human-in-the-loop und Audit fallen aus demselben Mechanismus ab. Kein Temporal, kein Message-Broker.

**E5 — Der Runner ist eine eigene, spätere Investition mit eigener ADR.** Wenn Code-/Skript-Ausführung kommt (Code-Interpreter, Skills mit Skripten, PDF-Rendering über Headless-Tools), dann als **Runner-Sidecar nach n8n-Vorbild** (eigener Compose-Service, non-root, kein DB-/Secret-Zugriff, Egress-Deny, schmale interne API; innen frischer Prozess pro Lauf mit bubblewrap/seccomp; optional dokumentierte gVisor-Härtung). Das entscheidet zugleich die offene Frage aus [discussion-plugin-architecture.md](discussion-plugin-architecture.md) in Richtung Variante A (Prozess-/Container-Isolation) für ausführende Erweiterungen — In-JVM-Plugins (PF4J) bleiben höchstens für herstellerkuratierte Tools denkbar.

**E6 — MCP in beide Richtungen, konsumierend später als anbietend.** Der schnellste MCP-Nutzen ist die **Server-Rolle**: OPAAs rechtegefilterte Suche als `@McpTool` (Streamable HTTP, Keycloak/OAuth 2.1) macht OPAA zur Wissensquelle für externe Agentensysteme — kleiner Baustein, großer Anschlusswert. Die Client-Rolle (fremde Tools für OPAA-Agenten) braucht Katalog-Kuratierung, Credential-UI und Egress-Policy und gehört in die spätere Phase. Das ist zugleich ein konkreter Entscheidungsvorschlag für **#349**: MCP als Erweiterungs-Schnittstelle, Plugins allenfalls für Hersteller-Tools.

## 3. Phasenvorschlag

### Phase A — Fundament im Chat: Tool-Loop, Streaming, Modellkatalog

Noch keine nutzerdefinierten Agenten — die Mechanik wird am bestehenden Chat eingeführt, wo sie sofort Nutzen stiftet:

- **A1 — Tool-Loop im Chat:** Umbau des Query-Flusses auf einen `ChatClient` mit ersten herstellerkuratierten Tools (naheliegend: `search_knowledge` — die bestehende Retrieval-Pipeline als Tool, womit das Modell selbst nachfassen kann; perspektivisch ersetzt das die heutige fest verdrahtete Teilfragen-Zerlegung). Iterationslimit, Schritt-Protokollierung, Zitatvalidierung bleibt unangetastet am Ende.
- **A2 — Streaming/Ereigniskanal:** SSE vom Backend (Zwischenschritte „suche …", „lese Dokument …", Token-Stream der Antwort) + Frontend-Anzeige. Ohne diesen Kanal ist jeder spätere Agent-Lauf eine Blackbox.
- **A3 — Modellkatalog:** `LlmModel` vom Single-Slot zum Katalog mit Aufgabenarten (Antwort, Zerlegung, Titel, später Agent), `resolveChatClient(task)`-API — llm-integration.md sieht das bereits vor; die Rerank-Rolle der Retrieval-Roadmap braucht denselben Umbau.
- **A4 — Textwerkzeuge/Prompt-Bibliotheken** (Phase-1-Substanz aus agents-and-tools.md): wiederverwendbare Prompts als erstes teilbares Nicht-Library-Objekt — der sanfte Einstieg in die Asset-Polymorphie am fachlich einfachsten Objekt.

### Phase B — Nutzerdefinierte Agenten, deklarativ, ohne Sandbox

- **B1 — Asset-Polymorphie:** Migration von `asset_grants.library_id`/`space_asset_associations.library_id` auf `(asset_type, asset_id)`; `LibraryAccessService`-Generalisierung. Der größte strukturelle Eingriff — vor allem Migrations- und Testarbeit, deshalb früh in der Phase.
- **B2 — Agent-Objekt + geführtes Onboarding:** Entity, Versionsstand als ein Paket, die sechs Onboarding-Abschnitte aus agents-and-tools.md; Ausführung zunächst **synchron im Chat** („diesen Agenten in diesem Chat verwenden") über den Phase-A-Loop mit der Toolauswahl des Agenten und den Rechten des Nutzers.
- **B3 — `agent_runs` für asynchrone Läufe:** das Checkpoint-Modell aus E4, Abbruch, Budgets, `WAITING_APPROVAL`-Statusmaschine mit Benachrichtigung — ab hier kann ein Agent auch ohne offenes Chat-Fenster arbeiten.
- **B4 — Erste schreibende Tools mit Freigabetor:** z. B. Dokumentexport (PDF/DOCX serverseitig aus Java erzeugt — dafür braucht es keinen Code-Interpreter, eine Java-Bibliothek genügt und bleibt in Rechteklasse „isoliert erzeugend"), Entwurf ablegen im Space. Freigabe-Objekt + UI.
- **B5 — Prüfstand:** Prüffälle je Agent gegen den Eval-Harness-Unterbau (search-quality-evaluation.md weiterverwenden), Pflicht vor Freigabe/Teilen — die Governance-Substanz aus agents-and-tools.md.
- **B6 — Skills ohne Skripte:** Agent-Skills-Format (SKILL.md + Referenzen + Toolauswahl) als teilbares Asset; Progressive Disclosure in den Loop. Kein Skript-Teil — damit sandbox-frei und trotzdem standardkonform/portabel.

### Phase C — Öffnung: MCP und Ausführungsumgebung

- **C1 — OPAA als MCP-Server:** rechtegefilterte Suche (und ggf. Dokumentzugriff) als Streamable-HTTP-MCP-Server mit Keycloak-OAuth.
- **C2 — MCP-Client:** Admin-kuratierter Katalog externer HTTP-MCP-Server (Credentials verschlüsselt, Egress-Allowlist über den `TargetAddressValidator`-Pfad, Tool-Namenskonflikte aufgelöst); Freigabe je Agent über die normale Toolauswahl. stdio bleibt ausgeschlossen.
- **C3 — Runner-Sidecar (eigene ADR):** erst mit dem ersten echten Code-Ausführungs-Bedarf (Code-Interpreter, Skills mit Skripten, OCR/Transkription aus agents-and-tools.md). Umfang siehe E5; gVisor als dokumentierte Härtungsoption.
- **C4 — Prüfagenten und Freigabeweg-Kopplung** (Phase-3-Substanz aus agents-and-tools.md): Zweitmeinungs-Agenten, Kopplung an DRAFT/IN_REVIEW/RELEASED, Versionsvergleich.

**Reihenfolge-Logik:** A vor B, weil Loop/Streaming/Modellkatalog auch ohne Agenten Wert stiften und jede spätere Stufe sie voraussetzt. B vor C, weil deklarative Agenten mit kuratiertem Katalog den Großteil des Nutzens ohne die teuerste Investition (Runner) liefern — und weil die Vergleichsprodukte zeigen, dass verfrühte Code-Ausführung ohne Isolation der teuerste Fehler des Feldes ist.

## 4. Bewusst nicht verfolgen

- **Visueller Prozessbaukasten** (n8n-artige Workflow-Kanten): in agents-and-tools.md bereits ausgeschlossen; die Recherche bestätigt, dass der Wert im deklarativen Agent-Paket liegt, nicht im Graphen-Editor.
- **stdio-MCP und nutzerkonfigurierbare Kommandos:** dauerhaft ausgeschlossen (Flowise CVE-2026-40933 als Anschauungsfall — „Nutzer darf Kommando konfigurieren" ist ein RCE-Feature).
- **Gehostete Sandboxes (E2B, Modal, Cloudflare, Code Interpreter der Modellanbieter)** als Voraussetzung: unvereinbar mit der On-Prem-Zielgruppe; allenfalls als optionale Alternative hinter derselben Runner-API.
- **Temporal/Message-Broker** für Agent-Läufe: überdimensioniert, solange ADR-0021 gilt; Postgres-Checkpoints genügen.
- **Framework-Adoption (Embabel, LangGraph4j, langchain4j-agentic) ohne Bedarfsnachweis:** erst wenn Multi-Agent-Planung konkret gefordert ist; bis dahin Spring-AI-Bordmittel.
- **Eigene Kernel-Sandbox-Entwicklung (Firecracker/Kata-Eigenbau):** kein Compose-Baustein; KVM auf Kunden-VMs oft nicht verfügbar. Beobachten: Docker Sandboxes (microVM ohne Eigenbau) als möglicher späterer Runner-Unterbau.

## 5. Offene Fragen für den Maintainer

1. **#349 jetzt entscheiden?** Der Vorschlag hier (E6: MCP als Erweiterungsweg, Runner statt In-JVM-Plugins für Ausführung) würde Bereich D der Backlog-Neuausrichtung schneidbar machen.
2. **Skills-Schnitt:** eigenes Asset (wie hier in B6 angenommen) oder benannter Abschnitt des Agent-Pakets? Das Agent-Skills-Standardformat spricht für ein eigenes, teilbares Objekt.
3. **Phase-A-Zuschnitt:** Soll A1 (Tool-Loop im Chat) die bestehende Teilfragen-Zerlegung ablösen oder zunächst parallel laufen? Berührt die Retrieval-Roadmap (Deep-Research-Modus 3a wäre derselbe Loop).
4. **Wie früh das Freigabetor?** B4 setzt es früh an (erste schreibende Tools); alternativ ließe sich Phase B rein lesend halten und alles Schreibende nach C verschieben.
