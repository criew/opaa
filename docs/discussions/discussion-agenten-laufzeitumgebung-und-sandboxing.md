# Discussion: Laufzeitumgebung und Sandboxing für Agenten-Ausführung (Tech-Report)

**Thema:** Wo und wie führt eine selbstgehostete Docker-Compose-Anwendung nutzerdefinierte Agenten und deren Tools aus — Sandbox-Technologien, das Runner-Muster, Lehren aus Low-Code-Plattformen (n8n, Dify, Flowise, Langflow, OpenWebUI), Skills, langlebige Ausführung. Ergänzt [discussion-agentensysteme-und-frameworks.md](discussion-agentensysteme-und-frameworks.md); OPAA-Konsequenzen in [discussion-agenten-architektur-opaa.md](discussion-agenten-architektur-opaa.md).

**Status:** Rechercheergebnis (Stand August 2026). Keine Entscheidung.

---

## 1. Die wichtigste Erkenntnis zuerst

**Die Trennlinie verläuft nicht bei „Agent ja/nein", sondern bei „führt die Plattform fremden *Code* aus".**

Solange Agenten ausschließlich einen **vom Hersteller geschriebenen, kuratierten Tool-Katalog** nutzen (Java-Methoden mit deterministischer Berechtigungsprüfung pro Aufruf) und externe Systeme nur über **HTTP-MCP** ansprechen, ist In-Prozess-Ausführung im Backend vertretbar und branchenüblich. Das „Risiko" ist dann nur, dass das LLM Tools mit falschen Parametern aufruft — dagegen helfen Rechteprüfung und Freigabetore, keine Sandbox.

Isolation wird **zwingend**, sobald eines davon kommt:

- **generierter oder nutzergelieferter Code** (Code-Interpreter, Skripte in Skills, „PDF per Python-Skript"),
- **stdio-MCP-Server** — ein per Nutzer konfigurierbares stdio-Kommando ist faktisch „beliebiger Prozess auf dem Server" (Flowise-Lektion, unten),
- Tools mit systemnahen Effekten jenseits definierter Workspaces.

Alle untersuchten Sicherheitsvorfälle in Low-Code-Agent-Plattformen entstanden genau an dieser Linie — und n8n, Dify und OpenWebUI mussten die Ausführung **nachträglich unter Schmerzen** aus dem Hauptprozess herauslösen (bzw. haben es bis heute nicht).

## 2. Wie es die vergleichbaren Produkte machen

Die Low-Code-Plattformen sind das relevanteste Vergleichsfeld, weil sie dem Szenario „Verwaltungsmitarbeiter definiert sich Agenten" am nächsten kommen. Durchgängiges Muster: **Nutzer definieren Agenten überall deklarativ** — Prompt + Modellwahl + Toolauswahl + Parameter. Niemand lässt Endnutzer Code schreiben; wo es doch geht, ist es die Schwachstelle.

| Produkt | Agent-Definition | Ausführung | Befund |
|---|---|---|---|
| **n8n** (2.0) | deklarativ (AI-Agent-Node: Modell, Prompt, Tool-Nodes) | Code-Nodes seit 2.0 standardmäßig in **„Task Runners"**: externem Sidecar-Container (distroless, non-root), Kommunikation über Broker | **Das Compose-taugliche Referenzmuster.** Ohne die Trennung konnte jeder Workflow-Editor DB, Encryption-Key und Credentials der Instanz auslesen |
| **Dify** | deklarativ (Prompt, Modell, Toolauswahl aus 50+ Katalog-Tools) | Code im **DifySandbox-Sidecar**: ein dauerhafter Container hegt viele Läufe intern per seccomp-Allowlist + chroot + Netz-Proxy ein | Reicht für Single-Tenant-Self-Hosting; für Multi-Tenant diskutiert die Community bereits microVM-Backends |
| **Flowise** | deklarativ | Custom-MCP-Tools mit stdio-Transport starten **ungesandboxte Kindprozesse** | 1-Click-RCE **CVE-2026-40933** — Negativbeispiel: „Nutzer darf MCP-Kommando konfigurieren" = RCE-Feature |
| **Langflow** | deklarativ + Custom-Python-Komponenten | im Serverprozess | dokumentiert selbst: keine Isolation zwischen Nutzern, kein Schutz von Disk/Netz |
| **OpenWebUI** | Tools/Functions/Pipelines als Python | **im Backend-Prozess mit vollen Rechten**, bewusst | CVE-2025-64496: Account-Takeover→RCE-Kette |

## 3. Sandbox-Technologien im Vergleich

| Technologie | Prinzip | Start | Anforderung | Compose-Tauglichkeit |
|---|---|---|---|---|
| Docker/runc, gehärtet (non-root, read-only rootfs, cap-drop ALL, no-new-privileges, seccomp-Default, eigenes Netz, Limits, ephemer) | Namespaces + cgroups, geteilter Kernel | ~100–500 ms | keine | **hoch — die akzeptierte Baseline** für Single-Tenant-On-Prem mit Agenten des eigenen Personals |
| **gVisor** (runsc) | User-Space-Kernel fängt Syscalls ab | 50–100 ms | Runtime-Installation auf dem Host, **kein KVM nötig** | mittel-hoch: `runtime: runsc` pro Service im Compose-File; Preis: I/O spürbar langsamer + ein Host-Paket mehr |
| Kata Containers / **Firecracker** | microVM pro Container — Goldstandard für wirklich fremden Code | ~125–300 ms | **KVM** (Nested Virtualization in Behörden-VMs oft deaktiviert), Eigenbau-Orchestrierung | niedrig — Infrastrukturprojekt, kein Compose-Baustein |
| WASM/WASI | Capability-Sandbox | <10 ms | — | niedrig für den Anwendungsfall: kein `pip install`, keine nativen Extensions — für PDF-/Bibliotheks-Workloads 2026 unrealistisch |
| bubblewrap/Landlock/seccomp (z. B. Anthropics `sandbox-runtime`) | OS-Primitives ohne Container | ~0 | Linux | als **innere Schicht im Runner** sinnvolles Defense-in-Depth, kein Ersatz |
| Sysbox | sicheres Docker-in-Docker ohne Privilegierung | wie runc | eigenes Runtime-Paket auf dem Host | Betriebs-Hürde; allenfalls dokumentierte Härtungsoption |
| **Docker Sandboxes** (neu 03/2026) | Wegwerf-microVM mit eigenem Daemon, auch macOS/Windows | schnell | Docker-Produkt | noch Dev-orientiert — beobachten, adressiert genau die Lücke „microVM ohne Eigenbau" |

**Windows:** Alle genannten Isolationsmechanismen sind Linux-only; serverseitige Agent-Ausführung findet auf dem Linux-Host statt, nie auf dem Client — für das Compose-Deployment-Modell unkritisch.

## 4. Wie startet ein Compose-Backend überhaupt isolierte Läufe?

Das ist die eigentliche Architekturfrage — vier Wege, klar sortiert:

1. **Docker-Socket ins Backend mounten: nie.** `/var/run/docker.sock` ist root-äquivalent; ein kompromittiertes Backend übernimmt den Host.
2. **Docker-Socket-Proxy** (z. B. Tecnativa): Sidecar, der nur die benötigten API-Endpunkte durchlässt. Etabliert, aber „Container erstellen dürfen" bleibt eskalierbar, wenn die Policy Mounts/Privileged im Create-Request nicht verbietet.
3. **Sidecar-Runner-Container (Referenzmuster, n8n-Vorbild):** Ein dediziertes Runner-Image läuft dauerhaft als Compose-Service. Das Backend spricht mit ihm über eine schmale interne API („führe Job X mit Workspace Y aus"); der Runner führt pro Lauf einen frischen Prozess aus (innen zusätzlich bubblewrap/seccomp) — oder ist als Einziger an einen Socket-Proxy angebunden und startet Wegwerf-Sibling-Container. Runner hat **keinen Zugriff auf Backend-DB und -Secrets**, eigenes internes Netz mit Egress-Deny. Kein Docker-in-Docker nötig.
4. **Docker-in-Docker:** klassisch nur privilegiert → ungeeignet; Sysbox-Variante nur als optionale Härtung.

## 5. Gehostete Sandbox-Dienste — Design-Vorbild, kein Baustein

E2B (Firecracker-microVMs, ~150 ms), Daytona (AGPL, self-hostbar, schnellste Starts), Modal, Fly, Cloudflare Sandboxes, Anthropic Code Execution Tool, OpenAI Code Interpreter — alle liefern dasselbe Produktversprechen: ephemere Sandbox mit definierter API (Dateien rein, Kommandos ausführen, Artefakte raus, Timeout, Netz-Policy).

Für die OPAA-Zielgruppe scheiden sie als Laufzeit aus: Verarbeitungsdaten mit Personenbezug in US-Clouds, keine On-Prem-AVV-Konstellation, Netzabhängigkeit, Beschaffungs-/BSI-Hürden. Relevant bleiben sie als **API-Design-Vorbild** für den eigenen Runner; Daytona (AGPL, Standard-Isolation Docker, optional Kata/Sysbox) ist die einzige Merkposition, falls je eine ausgewachsene selbstgehostete Sandbox-Infrastruktur nötig wird.

## 6. Skills — das Format für teilbare Fähigkeiten

- **Agent Skills** (Ordner + `SKILL.md` mit YAML-Frontmatter, optional Skripte/Referenzen/Templates; Progressive Disclosure: nur Metadaten dauerhaft im Kontext) sind seit 12/2025 **offener De-facto-Standard** — Anthropic, OpenAI, Microsoft/VS Code, Cursor, Gemini CLI u. v. m.; Spring AI hat eine portable Implementierung.
- **Sicherheitsentscheidend ist die Zweiteilung:** Der *Instruktionsteil* (Markdown + Referenzdokumente + Toolauswahl) ist harmlos — er wird nur in den Kontext geladen; Restrisiko ist Prompt-Injection, nicht Codeausführung. Der *Skript-Teil* setzt Dateisystem + Shell voraus — und reißt sofort die Sandbox-Frage auf (Spring warnt bei der eigenen Implementierung ausdrücklich: Skripte laufen ungesandboxt).
- **Konsequenz:** Ein gestufter Einstieg ist möglich und liefert früh Nutzen: **Skills ohne Skripte** brauchen keine Sandbox und decken bereits den Großteil des „wiederverwendbare, teilbare Fähigkeit"-Szenarios ab; **Skills mit Skripten** sind der Punkt, ab dem der Runner Pflicht wird.

## 7. Langlebige Ausführung: Checkpoint statt langlebiger Prozess

- **Der Kniff ist nicht die Job-Queue, sondern das Zustandsmodell.** Ein Agent-Lauf ist eine Schleife, deren Zustand (Nachrichtenhistorie, Tool-Ergebnisse, Budgetzähler) nach **jedem abgeschlossenen Schritt** in die Datenbank checkpointet wird. Ein Mechanismus löst drei Anforderungen:
  1. **Wiederaufnahme nach Neustart:** verwaiste `RUNNING`-Läufe werden ab dem letzten abgeschlossenen Schritt fortgesetzt — nie ein halber Schritt wiederholt (LLM-Calls sind nicht idempotent).
  2. **Human-in-the-loop:** Statusübergang `WAITING_APPROVAL` mit persistiertem Pending-Payload; die Freigabe lädt den Zustand und setzt fort — Stunden oder Tage später. (LangGraphs `interrupt()`-Muster; in Spring AI Eigenbau über den user-controlled Tool-Loop.)
  3. **Budgets:** harte Limits pro Lauf (max. Schritte, Tokens, Wall-Clock, Sandbox-CPU), im Checkpoint mitgeführt, vor jedem LLM-Call geprüft, deterministischer Abbruch.
- **Infrastruktur:** Für Single-Instance (ADR-0021) genügt Postgres — handgeschriebene Lauf-/Checkpoint-Tabelle oder JobRunr/db-scheduler als Unterbau. **Temporal/Restate sind überdimensioniert**, solange es genau eine Backend-Instanz gibt.
- **Audit:** Jeder Schritt (Modell, Tool-Aufruf mit Parametern, Freigabe mit Nutzer und Zeitstempel, Token-Zähler) als Append-only-Log — für die Zielgruppe ohnehin Pflicht; die Checkpoint-Tabelle ist zugleich die Audit-Quelle.

## 8. Fazit: realistische Stufung für ein On-Prem-Compose-Produkt

1. **Stufe 0 — ohne neue Infrastruktur:** deklarative Agenten (Prompt, Modell, Toolauswahl, Wissensquellen) über einen kuratierten Java-Tool-Katalog im Backend-Prozess, jede Tool-Ausführung deterministisch autorisiert; externe Systeme nur über HTTP-MCP mit Egress-Allowlist; **niemals stdio-MCP**; Skills ohne Skripte.
2. **Stufe 1 — sobald Code-/Skript-Ausführung kommt:** dedizierter **Runner-Sidecar** nach n8n-Vorbild (eigenes Minimal-Image, non-root, kein DB-/Secret-Zugriff, Egress-Deny, schmale interne API; pro Lauf frischer Prozess mit bubblewrap/seccomp; Wegwerf-Container nur über Socket-Proxy, den ausschließlich der Runner erreicht).
3. **Stufe 2 — optionale Härtung:** dokumentierte gVisor-Option (`runtime: runsc`) für sicherheitsbewusste Betreiber; Kata/Firecracker/Docker Sandboxes nur beobachten.
4. **Orchestrierung:** Postgres-Checkpoint-Tabelle, Statusmaschine mit `WAITING_APPROVAL`, harte Budgets, Append-only-Audit. Kein Temporal, solange ADR-0021 gilt.

## Quellen (Auswahl)

- Sandbox-Vergleiche: https://northflank.com/blog/kata-containers-vs-firecracker-vs-gvisor · https://dev.to/aiagentengineering/how-to-sandbox-ai-agents-in-2026-firecracker-gvisor-runtimes-isolation-strategies-14pk · https://www.beam.cloud/blog/how-to-self-host-code-sandbox
- Docker-Socket/Runner: https://cheatsheetseries.owasp.org/cheatsheets/Docker_Security_Cheat_Sheet.html · https://github.com/Tecnativa/docker-socket-proxy · https://github.com/nestybox/sysbox · https://www.docker.com/blog/why-microvms-the-architecture-behind-docker-sandboxes/
- Anthropic sandbox-runtime: https://github.com/anthropic-experimental/sandbox-runtime
- n8n Task Runners: https://docs.n8n.io/hosting/configuration/task-runners/ · https://docs.n8n.io/hosting/securing/hardening-task-runners/
- DifySandbox: https://dify.ai/blog/difysandbox-goes-open-source-secure-execution-of-code
- Flowise CVE-2026-40933: https://www.obsidiansecurity.com/blog/when-is-stdio-mcp-actually-a-vulnerability · Langflow: https://docs.langflow.org/security · OpenWebUI CVE-2025-64496: https://github.com/open-webui/open-webui/security/advisories/GHSA-cm35-v4vp-5xvx
- Gehostete Sandboxes: https://blog.logrocket.com/comparing-ai-agent-sandbox-platforms-e2b-modal-daytona-and-more/ · https://www.marktechpost.com/2026/08/27/best-agent-sandboxes-2026-cold-start-pricing-network-policy/
- Agent Skills: https://agentskills.io/home · https://www.anthropic.com/engineering/equipping-agents-for-the-real-world-with-agent-skills · https://spring.io/blog/2026/01/13/spring-ai-generic-agent-skills
- Durable Execution/HITL: https://learn.temporal.io/tutorials/ai/building-durable-ai-applications/human-in-the-loop/ · https://www.jobrunr.io/en/ · https://medium.com/@ali.gelenler/human-in-the-loop-for-ai-agents-a-checkpoint-based-pause-resume-pattern-with-spring-ai-134700afc36c
