# Discussion: Wie Agentensysteme gebaut sind — Agent Loop, Frameworks und MCP (Tech-Report)

**Thema:** Bestandsaufnahme, wie führende Agentensysteme (Claude Code / Agent SDK, OpenAI Codex und Agents SDK, Google Gemini CLI / ADK) ihren Agent Loop implementieren, und was das Java-/Spring-Ökosystem (Spring AI 2.0, Embabel, LangChain4j, LangGraph4j) dafür anbietet. Grundlage für die OPAA-spezifische Architekturdiskussion in [discussion-agenten-architektur-opaa.md](discussion-agenten-architektur-opaa.md); die Laufzeit-/Sandbox-Frage ist ausgelagert nach [discussion-agenten-laufzeitumgebung-und-sandboxing.md](discussion-agenten-laufzeitumgebung-und-sandboxing.md).

**Status:** Rechercheergebnis (Stand August 2026). Keine Entscheidung, reine Faktensammlung mit Einordnung.

---

## 1. Der kanonische Agent Loop — überall derselbe

Die wichtigste Erkenntnis der Recherche vorweg: **Der Agent Loop selbst ist Commodity.** Alle untersuchten Systeme implementieren denselben Kern:

```
Systemprompt + Tool-Definitionen + Nachrichtenhistorie
        │
        ▼
   LLM-Aufruf ──► Antwort enthält Tool-Calls? ──nein──► fertig (Endantwort)
        ▲                    │ ja
        │                    ▼
        └──── Tool-Ergebnisse ◄── Harness führt Tools aus
```

Terminierungskriterium ist eine Modellantwort **ohne** Tool-Calls; begrenzt wird über Turn-Limits und Kosten-/Token-Budgets. Ein „Turn" ist ein Roundtrip Modell → Tools → Modell. Die Differenzierung der Systeme liegt nicht im Loop, sondern im Drumherum: Permission-/Policy-Schicht, Sandbox, Kontext-Ökonomie, Persistenz.

### Claude Code / Claude Agent SDK (Anthropic)

- **Loop:** wie oben; das SDK (TypeScript/Python, bündelt eine native Claude-Code-Binary) yieldet einen typisierten Message-Strom und ist **voll headless in eigene Produkte einbettbar** (`query()` als Async-Iterator). Begrenzung über `maxTurns` und `maxBudgetUsd`. Read-only-Tools laufen parallel, zustandsändernde sequenziell.
- **Built-in-Tools:** Dateioperationen (Read/Edit/Write), Suche (Glob/Grep), Shell (Bash), Web (WebFetch/WebSearch), Subagenten, Skills — plus MCP und Custom-Tools.
- **Permission-System:** mehrstufig ausgewertet — Hooks → Deny-Regeln → Allow-Regeln (pattern-basiert, z. B. `Bash(npm *)`) → Permission-Modus → Callback. Modi vom interaktiven Nachfragen bis zum harten Deny für Headless-Betrieb; neuerdings auch ein Modell-Klassifikator als Approval-Instanz.
- **Sandbox:** OS-erzwungene Kernel-Isolation für Bash (macOS Seatbelt; Linux bubblewrap + seccomp, Netzwerk-Namespace entfernt, aller Traffic über einen Allowlist-Proxy). Details im Sandbox-Report.
- **Kontext-Ökonomie:** Prompt-Caching stabiler Präfixe; **Auto-Compaction** (ältere Historie wird bei Fensterannäherung zusammengefasst); **Subagenten als Kontext-Firewall** — Teilaufgabe läuft in frischem Kontext, nur die Abschlusszusammenfassung kehrt als Tool-Result zurück; On-Demand-Laden von Tool-Schemata (ToolSearch) gegen Schema-Ballast.
- **Sessions:** resümier- und forkbar, mit externem Session-Store für stateless Hosts. Seit 2026 zusätzlich **Managed Agents**: Anthropic hostet Orchestrierung und (optional) Sandbox; bei „Self-hosted Sandboxes" pollt ein eigener Environment Worker die Work-Queue und führt Tool-Calls in der eigenen Infrastruktur aus.

### OpenAI Codex und Agents SDK

- **Codex CLI** (Rust): drei Approval-Modi (Read-only / Auto: Workspace frei, außerhalb und Netzwerk nur mit Freigabe / Full Access). Sandbox pro Plattform: macOS Seatbelt mit dynamisch generierten Profilen; Linux Landlock (Dateisystem-Whitelist) + seccomp (Netz-Syscalls); Netzwerk **standardmäßig aus**, optional Domain-Allowlist über Proxy. Scheitert ein Befehl an der Sandbox, folgt ein Eskalations-Prompt.
- **Codex Cloud:** pro Task ein frischer, ephemerer Container; **Netzwerk-Phasenmodell** (Setup mit Internet, Agent-Phase standardmäßig air-gapped); **Secrets nur in der Setup-Phase entschlüsselt**, vor der Agent-Phase entfernt. Container-Zustand wird gecacht.
- **Agents SDK** (Python/TS): Primitive *Agents* (Modell + Instructions + Tools), *Handoffs* (Delegation als Tool), *Guardrails* (parallel laufende Input-/Output-Validierung mit Abbruch-Tripwires), *Sessions*. Die Responses API liefert gehostete Tools (Web Search, File Search, Code Interpreter, Computer Use, Remote-MCP) — Tool-Ausführung auf OpenAI-Seite.

### Google Gemini CLI / ADK

- **Gemini CLI:** ReAct-Loop mit PolicyEngine (Richtlinien pro Tool) und SandboxManager (macOS Seatbelt mit fünf Profilen, plattformübergreifend Docker/Podman-Container, opt-in).
- **ADK** (Python/Java): Runner als async Ausführungs-Engine, **Events als fundamentale Informationseinheit** (alles ist ein Event, OpenTelemetry-instrumentiert), saubere Trennung Session (Konversation) / State (Arbeitswerte) / Memory (Langzeitwissen); Agenten komponierbar (Sequential/Parallel/Loop); langlaufende Agenten pausieren/resümieren über das Event-Log.

### Konvergente Muster

1. **Loop = Commodity**; differenziert wird über Policy, Sandbox, Kontext-Ökonomie.
2. **Sicherheit ist geschichtet:** Kernel-Sandbox unten (bemerkenswerte Konvergenz auf Seatbelt/bubblewrap/Landlock), Policy-Schicht mit Allow-/Deny-Pattern darüber, Eskalations-Prompts als Fallback, Netzwerk default-deny mit Allowlist-Proxy (auch gegen Exfiltration und DNS-Rebinding).
3. **Kontext wird aktiv bewirtschaftet:** Caching, Compaction, Subagenten, deferred Tool-Schemata.
4. **Langlebigkeit über persistierten Schrittzustand:** Session-Log bzw. Event-Log, aus dem sich ein Lauf fortsetzen lässt — nicht über langlebige Prozesse.
5. **Secrets-Hygiene:** Der Agent selbst bekommt Secrets möglichst nie zu sehen (Codex-Cloud-Phasenmodell als schärfste Ausprägung).

## 2. Das Java-/Spring-Ökosystem

### Spring AI 2.0 — der Tool-Loop ist schon da

Für OPAA die zentrale Nachricht: **Spring AI 2.0 (GA 06/2026, exakt die OPAA-Version) enthält den vollständigen Tool-Call-Loop als komponierbaren Baustein** — es muss kein Loop von Hand gebaut werden, wohl aber alles darüber.

- **`ToolCallingAdvisor`** (auto-registriert in der Advisor-Kette des `ChatClient`) implementiert den Roundtrip Modell → Tool-Erkennung → Ausführung via `ToolCallingManager` → Ergebnis-Rückführung → Schleife bis zur Endantwort. Er ist **per Subklassifizierung erweiterbar** (`doInitializeLoop`, `doBeforeCall`, `doAfterCall`, `doFinalizeLoop`) — der kanonische Einhängepunkt für Iterationslimits, Schritt-Persistenz, Approval-Gates und Audit.
- **User-Controlled Execution:** Die automatische Schleife lässt sich abschalten (`AdvisorParams.toolCallingAdvisorAutoRegister(false)`); dann iteriert man selbst mit dem `ToolCallingManager` — nötig für Human-in-the-loop-Pausen, Streaming pro Schritt und Checkpointing.
- **`ToolCallback`** ist das einheitliche Interface für lokale `@Tool`-Methoden, Function-Beans und **Remote-MCP-Tools** — das Modell unterscheidet nicht, woher ein Tool kommt.
- **`ToolSearchToolCallingAdvisor`:** Progressive Tool Disclosure für große Kataloge (Tools werden dem Modell bedarfsgesteuert offengelegt; laut Spring 34–64 % Token-Ersparnis) — dasselbe Muster wie Claude Codes ToolSearch.
- **ChatMemory** mit auto-konfiguriertem `JdbcChatMemoryRepository` (Postgres-tauglich — würde den heutigen prozesslokalen Caffeine-Speicher ablösen können), **StructuredOutputValidationAdvisor** (Schema-Validierung mit Selbstkorrektur-Retry).
- **Bewusst kein höheres Agent-Framework:** Springs Position ist, dass Agentik durch Komposition von Advisors entsteht. Die fünf „Agentic Patterns" (Chain, Parallelization, Routing, Orchestrator-Workers, Evaluator-Optimizer — nach Anthropics „Building Effective Agents") sind Referenz-Code, kein Framework-Feature.
- **`spring-ai-agent-utils`** (Community, incubating): Claude-Code-inspirierte fertige Tools (FileSystemTools, ShellTools, Grep/Glob, WebFetch/WebSearch, AskUserQuestion) plus eine portable **Agent-Skills-Implementierung**. Als Steinbruch nützlich; ShellTools/Skripte laufen dort ausdrücklich **ohne Sandbox** — für OPAA nur mit Runner-Isolation denkbar (siehe Sandbox-Report).

### MCP — Stand der Spezifikation

- Spec-Linie: 2025-03-26 (Streamable HTTP) → 2025-06-18 (OAuth-Resource-Server, Elicitation) → 2025-11-25 (Basis des MCP Java SDK 2.0.0, das Spring AI 2.0 integriert) → **2026-07-28** (stateless Core, Cache-Metadaten, Extensions für Apps und langlaufende Tasks, Auth sauber auf OAuth 2.1/OIDC — relevant für Keycloak).
- Primitives: Tools, Resources, Prompts (+ Sampling, Elicitation). Transports: **stdio** (lokaler Kindprozess) und **Streamable HTTP** (remote) — die Unterscheidung ist sicherheitsentscheidend, siehe Sandbox-Report.
- Ökosystem: offizielle Registry mit ~19 000 Servern, fertige Server für Dateisystem, Postgres, PDF, Git, Browser, Suche — aber viele Karteileichen; Kuratierung nötig.
- **Spring AIs MCP-Support deckt beide Rollen ab:** Client-seitig werden Tools aller konfigurierten Server automatisch als `ToolCallback`s exponiert; server-seitig gibt es deklarative Annotationen (`@McpTool`, `@McpResource`, `@McpPrompt`) mit Boot-Startern für Streamable-HTTP- und Stateless-Endpoints. OPAA könnte also nicht nur fremde MCP-Server konsumieren, sondern **die eigene rechtegefilterte Suche als MCP-Server anbieten** — womit externe Agentensysteme (Claude Code, Codex …) OPAA als Wissensquelle nutzen könnten.

### Frameworks oberhalb von Spring AI

| Framework | Konzept | Reife | Einordnung für OPAA |
|---|---|---|---|
| **Embabel** (Rod Johnson) | Goal-Oriented Action Planning: typisierte `@Agent`/`@Action`-Bausteine mit Vorbedingungen/Effekten, deterministischer Planer, Neuplanung zur Laufzeit | 1.0 GA 08/2026; **erst 1.5 unterstützt Boot 4/Spring AI 2** | Das Spring-idiomatischste „echte" Agentenframework; baut direkt auf Spring AI auf. Kandidat, falls Planung/Multi-Agent gebraucht wird |
| **LangGraph4j** | Java-Port von LangGraph: StateGraph, **Checkpointer-Persistenz in Postgres**, Human-in-the-loop-Interrupts, Time-Travel; arbeitet mit Spring AI **und** LangChain4j | 1.8.x, aktiv, aber kleines Maintainer-Team (Bus-Faktor) | Naheliegendste Graph-Orchestrierung über Spring AI; vor allem das Checkpoint-Muster ist die Referenz |
| **LangChain4j / langchain4j-agentic** | AI Services, AgenticScope, Supervisor-Orchestrierung, Cross-Agent-Kompensation | Kern stabil; agentic-Modul **explizit experimentell** | Paralleles Ökosystem zu Spring AI (eigene Modell-Abstraktion) — Doppelstruktur, eher nicht |
| **ADK for Java** (Google) | LlmAgent + Workflow-Agents, A2A, Session-Services, Dev-UI | 1.0 | Stark Gemini-/GCP-zentriert, keine Spring-Integration — unpassend |

### LangGraph (Python) als konzeptionelle Referenz

LangGraph 1.0 ist der Industriestandard für langlebige Agenten (Uber, LinkedIn, Klarna). Drei Ideen sind unabhängig vom Framework übertragbar:

1. **Checkpointing pro Schritt:** Thread-ID → persistierte Zustandshistorie; „durable execution" heißt: Neustart mitten im Lauf setzt exakt am letzten abgeschlossenen Schritt fort.
2. **`interrupt()`/Resume** als Human-in-the-loop-Primitive: Der Lauf pausiert, der Zustand wird beim Checkpointer persistiert, die menschliche Eingabe setzt fort — beliebig lange später.
3. **Workflows vs. Agenten** (Anthropic-Taxonomie): vordefinierte Abläufe mit LLM-Schritten sind etwas anderes als autonome Loops; vieles, was nach „Agent" klingt, ist als Workflow zuverlässiger und billiger.

## 3. Fazit des Reports

1. Der Agent Loop ist gelöst — von Spring AI 2.0 direkt mitgeliefert und an den richtigen Stellen erweiterbar. **Kein Zusatzframework nötig für Single-Agent mit Tool-Katalog.**
2. Die eigentliche Ingenieursarbeit liegt in vier Schichten, die kein Framework schenkt: **Policy/Permissions je Tool**, **Persistenz/Checkpointing des Laufs**, **Kontext-Ökonomie** und **Ausführungsisolation** (letzteres im Sandbox-Report).
3. **MCP ist die richtige Erweiterungs-Schnittstelle** — in beide Richtungen, mit Spring-AI-Boot-Startern für beides. Die Auth-Seite der Spec ist inzwischen OAuth-2.1-sauber und passt zu Keycloak.
4. Framework-Wetten (Embabel, LangGraph4j) sind **erst dann** fällig, wenn Multi-Agent-Planung oder Graph-Orchestrierung nachweislich gebraucht wird; die Konzepte (Checkpoint, Interrupt/Resume, Subagent als Kontext-Firewall) lassen sich vorher framework-frei übernehmen.

## Quellen (Auswahl)

- Claude Agent SDK: https://code.claude.com/docs/en/agent-sdk/overview · https://code.claude.com/docs/en/agent-sdk/agent-loop · https://code.claude.com/docs/en/sandboxing
- Anthropic Managed Agents / Self-hosted Sandboxes: https://platform.claude.com/docs/en/managed-agents/self-hosted-sandboxes
- Codex: https://learn.chatgpt.com/docs/agent-approvals-security · https://learn.chatgpt.com/docs/environments/cloud-environment.md · https://simonwillison.net/2025/Nov/9/codex-sandbox-investigation/
- OpenAI Agents SDK: https://openai.github.io/openai-agents-python/
- Gemini CLI / ADK: https://google-gemini.github.io/gemini-cli/docs/cli/sandbox.html · https://github.com/google/adk-java
- Spring AI 2.0: https://spring.io/blog/2026/06/12/spring-ai-2-0-0-GA-available-now/ · https://spring.io/blog/2026/06/15/spring-ai-composable-tool-calling/ · https://spring.io/blog/2025/01/21/spring-ai-agentic-patterns/
- Spring AI MCP: https://docs.spring.io/spring-ai/reference/api/mcp/mcp-overview.html
- spring-ai-agent-utils: https://github.com/spring-ai-community/spring-ai-agent-utils · https://spring.io/blog/2026/01/13/spring-ai-generic-agent-skills
- Embabel: https://www.infoq.com/news/2026/08/embabel-1/ · https://docs.embabel.com/
- LangGraph4j: https://github.com/langgraph4j/langgraph4j
- LangGraph: https://changelog.langchain.com/announcements/langgraph-1-0-is-now-generally-available
- MCP-Spec-Revision 07/2026: https://blog.modelcontextprotocol.io/posts/2026-07-28/
