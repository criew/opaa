# Zeitraumsbericht 30.09.–31.10.2026

> **Bericht zum Stichtag 31.10.2026.** Er beruht auf der Fortschreibung mit Datenstand 31.10.2026
> (`main@ANKER_KURZ`, dem Stand von `v0.1.0-rc.5`; 680 geschlossene Issues und 725 gemergte PRs im
> Zeitraum — siehe [anker.md](./anker.md)). Er enthält nur das **Delta zum Stichtag 30.09.2026**;
> den konsolidierten Stand führt [gesamtstand.md](../gesamtstand.md). Jede Aussage ist über
> Issue- und PR-Nummer auf Code rückführbar. Wo Lieferung und Vorgang auseinanderfallen, steht die
> Begründung im [Baustein mit Befund](./bausteine.md). Der Commit, der diesen Berichtsstand
> einführt, trägt das Git-Tag `inventur-20261031`.

## Management Summary

Der Oktober war der bisher umfangreichste Monat: 725 gemergte PRs, 27 abgeschlossene Epics und fünf
Release-Kandidaten (`v0.1.0-rc.1` bis `v0.1.0-rc.5`). Er hat OPAA von einer funktionsreichen
Anwendung zu einem **lieferfähigen Produkt** gemacht. Es gibt versionierte, signierte Releases mit
Update-Paket und Behördenunterlagen, einen Betrieb unter Kubernetes und mit mehreren Instanzen, eine
Installation per Assistent statt Umgebungsvariablen und ein abgeschlossenes Sicherheitsaudit. Auf
der Quellenseite kamen sechs Datei-Konnektoren und private Bibliotheken über verbundene Konten hinzu.

**Phase 1 der Produktvision („Souveräner Wissensassistent“) ist zu geschätzt rund 90 % umgesetzt
(Vormonat: rund 85 %).** Herleitung: Von der priorisierten Restliste des Vormonats sind die Punkte
zu Nachweis und Betrieb erledigt: Schadsoftwareprüfung, Integrität und Ausleitung des
Revisionsprotokolls, Kubernetes und Mehrinstanzbetrieb, signierte Builds und der Betrieb ohne
Internetzugang. Die DSGVO-Vollständigkeit ist mit der Selbstauskunft angegangen. Offen bleiben die
Lücken im Kern des Assistenten: Streaming der Antworten, Konfidenz und Leichte Sprache. Sie wiegen
schwer genug, dass die Schätzung trotz des Lieferumfangs nur um fünf Punkte steigt.

**Was nach diesem Zeitraum neu möglich ist:**

- **Einrichten ohne Konfigurationsdatei:** Eine neue Installation startet im Installationsmodus.
  Ein Browser-Assistent oder eine Antwortdatei legt das erste Konto, die Anmeldung, die Modelle,
  den Mailversand und das Branding fest. Fristen, Rhythmen und Modelle stellt danach die
  Systemverwaltung in der Oberfläche ein.
- **Unter Kubernetes und mit mehreren Instanzen betreiben:** ein Helm-Chart als attestiertes
  OCI-Paket, Images ohne root mit nur lesbarem Dateisystem für amd64 und arm64. Mehrere
  gleichrangige Backend-Instanzen teilen sich eine Datenbank, auch beim Update ohne Ausfall.
- **Versioniert und nachweisbar aktualisieren:** Releases mit festen Images, Supportlinien und LTS.
  Jedes Release bringt ein Update-Paket mit Manifest-Delta, Faktenblättern, Behördenvorlagen,
  Offline-Bündel und Handbuch mit. Das Paket ist attestiert, die Images sind signiert.
- **Mehr Quellen anbinden:** Google Drive, Nextcloud, SMB-Freigaben, SharePoint, Dropbox und
  OneDrive for Business, gemeinsam über einen Datei-Abgleich. Beschäftigte binden ihre eigenen
  Konten als **private Bibliothek** an. Ein installationsweiter Ausgangsproxy gilt für alle
  Quellen.
- **An der Fundstelle prüfen:** Ein Beleg springt im PDF-Viewer an die Fundseite und markiert sie.
  Markdown, Text und Confluence springen an den Abschnitt, die übrigen Formate öffnen in einer
  Textfassung mit Download des Originals.
- **Den Katalog als einen Einstieg nutzen:** ein Katalog für alle Assets mit Favoriten. Die
  Space-Zuordnung begrenzt die Suche hart, jeder Space kann eine eigene Anweisung tragen.
- **Gegenüber Behörden belegen:** Revisionsprotokoll mit Ereigniskatalog, monatlicher
  Prüfsummenkette und optionaler SIEM-Ausleitung. Dazu Selbstauskunft für jede Person,
  Kennzeichnung KI-erzeugter Antworten nach Art. 50 KI-VO, Schutz gegen Prompt-Injection und eine
  optionale Virenprüfung jeder aufgenommenen Datei.
- **Sprechen statt tippen und Chats mitnehmen:** Spracheingabe über ein eigenes
  Transkriptionsmodell und Chatexport.

---

## 1 · Wissensschicht & Retrieval (A)

- **Abfragestrecke nach Messung nachgeschärft:** top-k von 8 auf 20 (#2373), Nachbar-Chunks im
  Antwortkontext (#2374), ein Chunk je Dokument statt zwei (#2502), Quote je Teilfrage (#2619),
  Spitzentreffer je Liste (#2634), ein Relevanzschnitt, ab Werk aus (#2622), dazu #2620 und #2621.
  Die Volltextsuche schützt Bezeichner (#2334), die HNSW-Abfrage liefert die volle Trefferzahl
  (#2345). Die Chunkgröße bleibt nach einer Bestätigungsmessung bei 4.000 Zeichen (#2507).
- **Embedding-Modell bge-m3** gemessen und empfohlen (#2375). Seit der schlanken Konfiguration wird
  das Modell bei der Installation gewählt und kann geführt gewechselt werden (siehe E).
- Die Zitierposition der Bibliotheksfelder entfällt; die externe Ausgabe enthält alle Felder in
  fester Reihenfolge (#3279).

## 2 · Wissensquellen & Indizierung (B)

- **Datei-Konnektoren** (Epic #2146) auf einem gemeinsamen Datei-Abgleich `filesync` (#2149,
  ADR-0045): Google Drive (#2151, ADR-0040), Nextcloud (#2152), SMB (#2155, ADR-0046), SharePoint
  (#2153, ADR-0047) und Dropbox (#2154, Team-App #2472). Dazu kamen OneDrive for Business als
  verbundenes Konto (#2467, PR#3148, ADR-0058), der Umfang „Alle“ für Confluence, S3, Google Drive und
  SharePoint (#3117) und der Abschluss von Änderungsläufen über Change-Feeds (#2408).
- **Zugänge, verbundene Konten und private Bibliotheken** (Epic #2147, ADR-0041, ADR-0048,
  ADR-0049): Zugänge mit Freigabe und Sperre (#2160–#2162), verbundene Konten und private
  Bibliotheken mit Kontingent und nachweisbarer Löschung (#2163–#2167, PR#2259, PR#2274), ein
  OAuth-Kern mit PKCE (#2168, PR#2264) und „Quelle verbinden“ (#2169).
- **Verbindungen vereinfacht** (Epic #2492): globaler Ausgangsproxy mit Zertifikatsprüfung statt
  Proxy je Zugang (#2496, PR#2503, ADR-0050), ein Assistent, der nur Nutzbares zeigt (#2495),
  Verbindungstests, die Netz- und TLS-Fehler von abgelehnter Anmeldung unterscheiden (#3156,
  #3175).
- **Fundstellen-Anker je Chunk** (#3315, PR#3322, ADR-0062) als Grundlage für den Sprung an die
  Fundstelle; seitenweise Scan-Erkennung im PDF (PR#3351); Inhaltsselektor und Anlagenprofil je
  RSS-Bibliothek (#3396).
- Konnektoren laufen nur noch in einem vom Kern gestarteten Lauf (#2772, #2785); Auflistungen
  brechen nicht mehr nach zehn Seiten ab (#3083).

## 3 · Spaces, Assets & Agenten (C/D)

- **Ein Katalog** (Epic #2070, ADR-0039): Der Katalog ist der einzige Einstieg zu allen Assets, mit
  Favoriten (#2092–#2098). Die Space-Zuordnung ist eine harte Grenze der Suche, im Chat sichtbar
  als „@Space-Wissen“ (#2096).
- **Anweisung je Space** (#1637, ADR-0043), automatisches Aufräumen inaktiver Chats je Space
  (#1923), vereinfachte Space-Anlage und -Einstellungen (#2129–#2134), der persönliche Space heißt
  „Privater Bereich“ und ist immer privat (#2302, #2585).
- **D:** MCP-Server als Profilart von Zugängen, Grundlage für spätere Werkzeuge (#2173).

## 4 · Modelle & zentrale Steuerung (E)

- **Installationsweite Feature-Flags** (Epic #2586, ADR-0044), erstmals genutzt für externe
  KI-Werkzeuge (#2749).
- **Geführter Wechsel des Embedding-Modells** mit Generationen und Neueinbettung im Hintergrund
  (#3214, PR#3232, ADR-0061). Rerank- und Transkriptionsmodell stehen in der Modellverwaltung
  (#3207). Modelle lassen sich am Endpunkt ermitteln (#2488), die Temperatur ist optional (#2486),
  der Denktext von Reasoning-Modellen wird entfernt (#2509, #2511).

## 5 · Identität, Rechte & Mandanten (F)

- Erneute Anmeldung vor sensiblen Vorgängen (#2996), Fehlversuchssperre als Einstellung (#3394),
  Notanker-Schutz (#1988), Konten nach Inaktivität ruhen statt gesperrt zu werden (#2260), Übergabe
  mit Zeilensperre (#2979), Folgen für verbundene Konten vor dem Abschalten eines
  Anmeldeanbieters (#2251).
- Die Seiten „Anlegerechte“, „Ohne Zuständigkeit“ und „Verzeichnisabgleich“ wurden überarbeitet
  (#2321, #2336, #2498), ein gescheiterter Verzeichnisabgleich zeigt seinen Status (#2902).

## 6 · Sicherheit, Nachweis & Prüfbarkeit (G)

- **Revisionsprotokoll** (Epic #1296, ADR-0059): Ereigniskatalog mit Schemaversion (#3118),
  monatliche Prüfsummenkette mit Siegel (#3121), Metriken zu Größe und Löschlauf (#3120) und
  Ausleitung an ein Sicherheitsmonitoring per Syslog, ab Werk aus (#3009, ADR-0055).
  Kontokennungen im Protokoll sind pseudonymisiert (#3040, #3096).
- **Fakten-Manifest und Update-Paket** (Epics #2543, #2552, ADR-0052): Manifest mit Wächter
  (#2546, #2547), per `@DataFlow` erzwungene Datenflüsse (#2549, PR#2836), Faktenblätter
  (#2550), Lizenzprüfung (#2551), Update-Paket mit Delta, Betriebsfolgen und Attestierung (#2554,
  #2555, #2558, #2559). Das Manifest wurde auf den Ist-Stand gebracht (#2850–#2855, #2860).
- **Schutz der KI-Funktionen:** Schutz gegen Prompt-Injection (Epic #3008), Kennzeichnung
  KI-erzeugter Antworten (Epic #3010).
- **Schutz der Aufnahme und der Ziele:** optionale Schadprogrammprüfung über clamd, ab Werk aus
  (#3007). Ein Schutz gegen DNS-Rebinding bindet geprüfte Namen (#3239, PR#3323, ADR-0063), Modell-
  Endpunkte haben eine eigene Freigabeliste (#3224), die Zertifikatsprüfung ist nicht mehr
  abschaltbar (#2537).
- **Security-Audit 2026** (Epic #3325, 50 Sub-Issues): acht Prüfbereiche (#3326–#3333), gegen
  Ressourcen-Erschöpfung gehärtete Parser (#3336, #3375, #3376, #3387, #3434), geschlossene
  Rechtekanten (#3346, #3352–#3358) und ein Schlüsselwechsel verschlüsselter Geheimnisse (#3347,
  #3431, ADR-0064). Dauerhaft abgesichert durch Rechte-Matrix (#3377), Prüfung anonymer Pfade und
  Header (#3382), Architekturtests und Parser-Fuzzing (#3379) und einen ZAP-Scan (#3384).
  Lieferkette: CodeQL (#3341, #3422), signierte Images (#3342), Gradle Dependency Verification
  (#3391).
- Härtung aus einem Code-Review: Rate-Limits (#2900, #2901), Workflows (#2905), nginx (#2906),
  MCP-Suchumfang fail-closed (#2909), Quell-Links nur http(s) (#2912). Dazu die Maskierung nach
  Mindestgruppengröße (#2270, PR#2287, PR#2292) und ein geschlossenes Leck im Chat-Hinweis über
  fremde private Bibliotheken (#2470).

## 7 · Kanäle & Oberflächen (I)

- **Sprung an die Fundstelle** (Epic #3260): PDF-Viewer mit markierter Fundseite (#3317),
  Markdown, Text und Confluence (#3318), Textfassung mit Original-Download für alle übrigen
  Formate (#3316).
- **Quelleinrichtung nach UI-Lab-Variante F** (Epic #3104, #3105–#3109) und Detailansicht der
  Bibliothek mit Statusfilter (#3162, #3165, #3186). Der alte Pfad ist entfernt (#3125).
- **UI-Politur und Review** (Epics #1441, #1928, #3269): überarbeitete Admin-Seiten, Belegfenster
  (#3259), Seitenleiste mit Spaces als Liste (#3292), einheitliches Zeilenmenü und Etiketten (#3284,
  #3283), Konsistenz-Audit (#3265).
- **Spracheingabe Stufe 1** (#1926, PR#2629), **Chatexport** (#1925), Bereich „Anbindungen“ mit
  „Externe KI-Werkzeuge“ (PR#2508, #2504, #2506).
- **Barrierefreiheit:** WCAG-AA-Kontrast als Regel (#2493), Fokusring mit mindestens 3:1 (#3242),
  WCAG 2.5.3 (#2941).

## 8 · Betrieb & Deployment (J)

- **Kubernetes mit Helm** (Epic #2346, 34 Sub-Issues, ADR-0042): Images ohne root mit nur lesbarem
  Dateisystem (#2348, #2349), Chart als OCI-Paket in GHCR (#2356), Chart-CI auf kind (#2355),
  arm64-Images (#2401) und ein lokaler Erprobungsaufbau (#2423).
- **Mehrinstanzbetrieb aktiv/aktiv** (Epic #1292, ADR-0053): Instanzregister (#2945),
  Invalidierungsbus (#2946), ShedLock (#2947), Laufbesitz über Heartbeats (#2948), Zustand in der
  Datenbank (#2949), installationsweite Budgets (#2950), Helm und Compose mit mehreren Instanzen
  (#2951), eine Zwei-Instanzen-Suite (#2952) und das Handbuchkapitel (#2975).
- **Schlanke Konfiguration** (Epic #3160, ADR-0060): Installationsmodus mit Token, API und
  Antwortdatei (#3189), Browser-Assistent mit optionalen Schritten für Mail, Modelle, Branding,
  Rerank und Transkription (#3190, #3237, #3245, #3246, #3253, #3257, #3258). Fachliche Werte liegen in
  der Datenbank (#3192, #3203, #3208–#3210), mit Einstellungsexport (#3193, #3201). Helm und
  Compose sind umgestellt (#3194, PR#3202).
- **Releases:** versionierte Releases (#2350), Release-Zyklus mit Supportlinien und LTS (#2575,
  ADR-0054), Wartungsbranches und Backport (#3017), Offline-Bündel (#2557), Handbuch als
  Offline-Fassung im Update-Paket (#3028), automatisch belegter Betrieb ohne Internet (#3004),
  automatische Wiederherstellungsprobe (#2840).

## 9 · Verwaltungs-Spezifika (K)

- **Behördenunterlagen** (Dach-Epic #2542): redaktionelle Vorlagen für Datenschutz, Mitbestimmung,
  Sicherheit und KI-Verordnung (Epic #2560, #2565–#2568), gekürzt und ins Update-Paket aufgenommen
  (#2984, #2985).
- **Selbstauskunft Stufe 1** (Epic #2986): jede Person lädt ihre Daten als ZIP herunter
  (#2987–#2994).
- KI-Kennzeichnung nach Art. 50 KI-VO (#2826, #2827); Auskunft über die Datenerhebung für die
  Personalvertretung mit Klärungszahlen (#2895); Chats lokaler Konten werden nach Frist ab einer
  Verwaltungssperre gelöscht (#2997).

## 10 · Technisches Fundament und Arbeitsweise (T1–T3, V, P)

- **Architektur:** Architektur-Review des Backends (Epic #2678, 30 Sub-Issues) mit
  Architekturvertrag und Ratschen (#2777, ADR-0051). Das Frontend ist nach Themen gegliedert, die
  Grenzen prüft ESLint (#2641–#2663). Liquibase ist durch Flyway ersetzt, mit einer Baseline je
  Modul (#2825, ADR-0056).
- **Tests:** Teststufen für Konnektoren (Epic #1293, ADR-0057), E2E-Lücken geschlossen (Epic #3462)
  mit Firefox-Lauf (#3474) und axe-Prüfung am Endzustand (#3475).
- **Arbeitsweise:** Rolle „Behördenkontakt“ (Epic #2570, #2573), Kommentarregel repo-weit per CI
  (#2759), alle ADRs auf den gültigen Endstand neu gefasst (#2696–#2702), UI-Lab als
  wiederholbarer Ablauf (#3100).
- **Projekt als Produkt:** Produkthandbuch fertig und als Kapitelbaum gegliedert (Epic #1282,
  #2527–#2530), Roadmap mit GitHub-Meilensteinen (#3021), openCode und `publiccode.yml` (PR#3087).
  ADRs: 62 gepflegt, 26 davon im Zeitraum neu (ADR-0039 bis ADR-0064).

## 11 · Lücken und bewusste Schnitte

- **Datenbank neu aufsetzen:** Der Wechsel zu Flyway fasst die Historie je Modul zu einer
  Baseline zusammen. Eine Datenbank von `v0.1.0-rc.4` lässt sich nicht übernehmen (#2825). Die
  Upgrade-Notes von `v0.1.0-rc.5` verlangen deshalb Dump und Sicherung der Originale vor dem
  Update (#3483).
- **Kurswechsel im Zeitraum**, im Report nur mit dem Endzustand geführt:
  - Übergangsweg mit Startwerten und Seedern gebaut und nach einer Woche entfernt (#3189); die
    Übernahme des Embedding-Modells aus der Konfiguration ebenso (#3191)
  - Zugangswunsch gebaut und ersatzlos entfernt (#2494)
  - Recovery über `ProcessStart` durch den Laufbesitz über Heartbeats ersetzt (#2899, #2948)
  - Abhängigkeits-Gate ersetzt, weil es nie griff (PR#3443, PR#3461)
- **Zurückgebaut aus dem Vorzeitraum:** Schaufenster `listed` (#2092), Space-Sichtbarkeit (#2131,
  #2156), Übergangsregel „Space ohne Zuordnung durchsucht alles“ (#2096), Navigation
  „Wissen“/„Prompts“ (#2094), Wachstumssignal für Gruppen (#2134), Proxy je Zugang (#2496),
  Dokumentzählung im Verbindungstest (#2127).
- **Nicht gebaut:**
  - OneDrive für private Microsoft-Konten (#3110)
  - eigene Ansichten für Tabellen, Mail, DOCX und Folien; die Textfassung deckt sie ab (#3319–#3321)
  - Feedbackwünsche zu Prozessen und Kommunikation, bis reale Behördenanforderungen vorliegen
    (Epic #2305)
- **Teil-Schnitte:**
  - Die Chat-Löschung nach Sperre greift nur bei einer Verwaltungssperre lokaler Konten (#2997).
  - Die Selbstauskunft führt Prompts nur als Asset, ohne ihren Text (#2992).
  - Die Ratsche für `@Value` steht bei 4, nicht bei 0 (#2783).
  - Kubernetes ist auf k3d erprobt, nicht auf einem produktiven Cluster (#2358).
- **Ausgelagert und offen:** Exchange/Outlook-Konnektor (Epic #2172), Live-Transkript (#2615),
  Barrierefreiheits-Audit einer veröffentlichten Version (#3006), Ablage auf openCode (#3041).
- **Aus dem Release-Lauf:** Das Stakeholder-Review vor `v0.1.0-rc.5` bewertet die Änderungen der
  Stufe Zustimmung als tragfähig mit Auflagen. Offen ist das Anhalten der Chat-Löschung gesperrter
  Konten durch eine einzelne Person ohne Begründung und Höchstdauer. Die Personalvertretung
  verlangt dafür eine Regelung wie bei der anlassbezogenen Klärung.

## 12 · Offen für Phase 1 — priorisierte Restliste

Gegen die Phase-1-Definition der [Vision](../../VISION.md), nach Gewicht:

**Kern des Wissensassistenten**
1. **Streaming der Antworten** — größter Einzelfaktor der gefühlten Antwortzeit, weiter
   nicht gebaut
2. **Konfidenz als erklärte Größe** — Erklärprotokoll, Chunk-Ansicht und Fundstellen-Anker liefern
   die Grundlage, eine Konfidenzaussage an der Antwort fehlt
3. **Textwerkzeuge einschließlich Leichter Sprache** — Bereich K, weiter ohne Vorgang
4. **Reranking produktiv machen** — es ist als Modellrolle in der Verwaltung einstellbar, die
   Kosten sind begrenzt (#2703), ein Hardwareprofil für eine Voreinstellung fehlt

**Nachweis**
5. **DSGVO-Vollständigkeit** — die Selbstauskunft Stufe 1 ist gebaut. Es fehlen das Löschrecht als
   Ablauf und die Selbstauskunft für Protokolldaten über die eigene Person hinaus
6. **Regelung des Anhaltens der Chat-Löschung** (aus dem Stakeholder-Review vor `v0.1.0-rc.5`)

**Betrieb**
7. **Erprobung auf einem produktiven Cluster** und Abnahme des ersten finalen Release `v0.1.0`
8. Mandantenfähigkeit mit mehreren Organisationen in einer Installation (Epic #1442, geparkt)

**Nachrangig, aus der Restliste des Vormonats fortgeführt**
9. Sitzungsverwaltung mit erzwungener Neuanmeldung (die erneute Anmeldung vor sensiblen Vorgängen
   ist gebaut, #2996)
10. Antwort-Bewertung mit Speicherung — weiter ohne Konzept
11. Modell-Obergrenze je Space und Bibliothek

Erledigt gegenüber der Restliste des Vormonats: Schadsoftwareprüfung des Uploads (6), Integrität
des Audit-Trails und extern anbindbarer Ereignisstrom (7), Kubernetes, Mehrinstanzbetrieb,
signierte Builds und Betrieb ohne Internetzugang (8). Reranking produktiv machen ist von Platz 3 auf
4 gerückt, weil es jetzt in der Oberfläche einstellbar ist.

**Phase 2:** Fortschritt nur am Rand. Der MCP-Server ist als Profilart von Zugängen Grundlage für
Werkzeuge (#2173). Das Fundament für Werkzeugaufrufe (Epic #1747) und Skills als Objektart
(Epic #1727) sind geparkt.

## 13 · Zahlen zum Zeitraum

| | |
|---|---|
| Gemergte Pull Requests | 725, davon 73 Renovate-Updates |
| Geschlossene Issues | 680, davon 19 „not planned“, 4 Duplikate und 30 automatisch geöffnete und wieder geschlossene Alarm-Issues aus CI-Läufen |
| Abgeschlossene Epics | 27 (dazu Epic #2305 als „not planned“) |
| Vorgänge mit Befund | 72 von 845 geprüften (9 %; Vorgänger-Stichtag: 43 von 616) |
| Releases | fünf Release-Kandidaten, `v0.1.0-rc.1` bis `v0.1.0-rc.5` |
| Architecture Decision Records | 62 gepflegt, 26 im Zeitraum neu |
| Produkthandbuch | 59 Kapitel unter `docs/handbuch/` (Vorgänger-Stichtag: 25) |
| Themenbereiche ohne Vorgang im Zeitraum | H (Monitoring/Kosten) |

---

*Erstellt nach dem Verfahren in [../README.md](../README.md) (Issue #3482). Datenstand:
`main@ANKER_KURZ`, GitHub-Abfrage vom 31.10.2026. Folgende Fortschreibungen erheben das Delta ab
den Marken in [anker.md](./anker.md).*
