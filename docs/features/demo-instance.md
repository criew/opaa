# Demo-Instanz „Stadt Rheinfurt"

> **Status: Konzept entschieden (Maintainer, 21.08.2026) — Umsetzung läuft im Epic #708.**
>
> **Abgrenzung:** Dieses Dokument beschreibt ausschließlich den **Demo-Korpus und seine
> Vorführbarkeit**. Das Rechte- und Space-Modell, das die Demo vorführt, ist in
> [Spaces, Assets & Zugangskontrolle](./spaces-and-assets.md) entschieden und wird hier **nicht
> wiederholt**; Identität und Kontenlebenszyklus stehen in
> [Identität, Rechte & Mandanten](./access-control.md). Der Eval-Korpus und die Retrieval-Regression
> stehen in [Suchqualität messbar machen](./search-quality-evaluation.md) — dessen Abschnitt
> „Öffentliche Demo" beschreibt den bisherigen Superhelden-Stand und wird durch dieses Konzept
> abgelöst. Die praktische Anwenderdokumentation — Installation mit einem Befehl, Nutzerkonten,
> öffentliche Instanz — steht in [`../../demo/README.md`](../../demo/README.md), das ausformulierte
> Vorführ-Drehbuch mit acht Fragen in [`../market/demo-drehbuch.md`](../market/demo-drehbuch.md);
> beide wiederholen dieses Konzept nicht.

## Motivation

OPAA soll ohne Vorbereitung präsentierbar sein: eine mit einem Befehl installierbare Instanz, die mit
realistischen Verwaltungsinhalten, mehreren Nutzern, Spaces und Berechtigungskonstellationen zeigt, was
das Produkt kann. Der Eval-Korpus (Superhelden, Epic #224) bleibt davon getrennt — er ist für die Messung
eingefroren und für ein Behördenpublikum als Bildsprache unbrauchbar.

Die Demo bildet **eine** zusammenhängende Behördenlandschaft ab. Ein Publikum aus der Verwaltung erkennt
sein Tagesgeschäft wieder; der stärkste Vorführ-Moment ist die Berechtigungsgrenze: zwei Nutzer stellen
dieselbe Frage und bekommen unterschiedliche Antworten, weil sie unterschiedliche Wissensbibliotheken
lesen dürfen.

---

## Überblick

1. **Fiktive Stadt:** Rheinfurt, kreisfreie Mittelstadt am Rhein (~120.000 Einwohner), fiktive Domain
   `stadt-rheinfurt.example`. Alle Inhalte sind synthetisch; echte Behördennamen und personenbezogene
   Daten kommen nicht vor. Die Glaubwürdigkeit trägt der Verwaltungsstil: Aktenzeichen,
   Gebührenordnungen, Formularnummern, Amtsdeutsch.
2. **Szenario:** das **Bürgerbüro Rheinfurt** mit mehreren Teams (Sachgebieten) — keine konstruierte
   amtsübergreifende Leitungsrolle, sondern die realistische Binnenstruktur eines Amtes mit Amtsleitung.
3. **Alle Konnektortypen und mehrere Dateiformate:** HTTP-Verzeichnis (Markdown, Klartext und PDF),
   RSS-Feed (selbst gehostete, statische XML) und manueller Upload (DOCX, PDF, PPTX).
4. **Ein Befehl installiert alles:** Compose-Profil `demo` plus Seed-Skript richten Nutzer, Spaces,
   Bibliotheken, Berechtigungen und Indizierung ein. Angemeldet wird sich über Keycloak, wie in einer
   echten Installation. Zielplattform ist gleichgültig — lokal, opaa.ewerlin.com oder ein alternativer
   Host.
5. **E2E-Tests getrennt, Seed-Infrastruktur geteilt:** Der Seed-Mechanismus kennt zwei Datenprofile —
   `demo` (reich, darf sich weiterentwickeln) und `e2e` (minimal, eingefroren). Die Demo selbst wird nur
   durch einen Smoke-Test abgesichert.

---

## Quellen und Lizenzen

Der Korpus wird synthetisch generiert; als Rohmaterial und Stilvorlage dienen recherchierte, lizenzierte
Quellen (Recherche vom 21.08.2026, Issue #709):

| Quelle | Lizenz | Verwendung |
|---|---|---|
| [LHM-Dienstleistungen-Corpus](https://huggingface.co/datasets/it-at-m/LHM-Dienstleistungen-Corpus) (Stadt München, ~740 Leistungsbeschreibungen) | MIT | **Rohmaterial:** Leistungsbeschreibungen werden auf die Stadt Rheinfurt umgeschrieben (Namen, Adressen, Gebühren, Aktenzeichen ersetzt). Namensnennung in der Quellendatei des Korpus |
| [FIM-Portal / LeiKa](https://fimportal.de/) | ungeklärt | **Nur Katalog und Stilreferenz:** sichert ab, dass die Leistungsauswahl einer echten Mittelstadt entspricht. Keine Textübernahme |
| [Pressemeldungen Stadt Köln](https://offenedaten-koeln.de/dataset/pressemeldungen) | DL-DE-BY-2.0 | Stilvorlage für die Pressemitteilungen; bei Bedarf Direktmaterial mit Quellenvermerk |
| [RSS-Feed Stadt Düsseldorf](https://www.duesseldorf.de/rss-feed) | keine offene Lizenz | **Nur Formatvorlage** für Feed-Struktur und Meldungstypen (Sperrung, Öffnungszeiten, Veranstaltung, Jubiläum) |
| Kommunale Satzungen (Gebühren-, Straßenreinigungssatzung beliebiger Städte) | gemeinfrei (§ 5 Abs. 1 UrhG) | Strukturvorlage für die synthetischen Satzungen Rheinfurts |

Fertige synthetische Verwaltungskorpora existieren nicht; die Eigengenerierung ist alternativlos. Das
QA-Schwesterset [LHM-Dienstleistungen-QA](https://huggingface.co/datasets/it-at-m/LHM-Dienstleistungen-QA)
(MIT) ist für eine spätere deutsche Eval-Domäne in Epic #224 vorgemerkt, gehört aber nicht zur Demo.

---

## Behördenlandschaft, Bibliotheken und Formate

Das Bürgerbüro Rheinfurt gliedert sich in Sachgebiete; dazu kommt die Pressestelle der Stadt als externe
Quelle, die alle lesen. Jede Wissensbibliothek demonstriert einen Konnektortyp und mindestens ein
Dateiformat. Der Formatvorrat ist durch `SupportedDocumentFormats` gesetzt — `.md`, `.txt`, `.csv`,
`.pdf`, `.docx`, `.doc`, `.xlsx`, `.pptx`, `.odt`, `.ods`, `.odp` und `.html` (#1058/#1057/#1059); die
Demo-Bibliotheken unten nutzen davon nur eine Teilmenge:

| Wissensbibliothek | Inhalt | Formate | Quellentyp |
|---|---|---|---|
| Leistungen Meldewesen & Ausweise | Leistungsbeschreibungen (Ummeldung, Personalausweis, Reisepass, Führungszeugnis, Beglaubigungen, …) | `.md` | `HTTP_DIRECTORY` |
| Leistungen Kfz-Zulassung | Leistungsbeschreibungen (Zulassung, Umschreibung, Wunschkennzeichen, Führerschein) | `.md`, `.txt` | `HTTP_DIRECTORY` |
| Satzungen & Gebührenordnungen | Verwaltungsgebührensatzung, Satzungsauszüge mit Gebührentabellen | `.pdf` | `HTTP_DIRECTORY` |
| Pressemitteilungen Stadt Rheinfurt | ~20–30 Meldungen (Sperrungen, geänderte Öffnungszeiten, Stadtfest, Jubiläen) | RSS-XML, je Eintrag eine HTML-Detailseite auf demselben Host | `RSS_FEED` (statisch, selbst gehostet) |
| Interne Dienstanweisungen Meldewesen | Dienstanweisungen, Eskalationsregeln, interne FAQ, Schulungsfolien | `.docx`, `.pdf`, `.pptx` | `UPLOAD` (manueller Upload, im Seed automatisiert) |

Zielgröße: 150–300 Dokumente insgesamt — genug für glaubwürdige, belegte Antworten, klein genug für eine
schnelle Demo-Indizierung. Der Generator folgt dem Muster aus `eval/` (deterministisch, versioniert),
aber ohne Ground-Truth-Zwang: Die Demo misst nichts. Ablage unter einem eigenen Top-Level-Verzeichnis
`demo/` (Korpus, Feed, Seed, Webserver-Konfiguration), damit `eval/` unangetastet bleibt.

---

## Nutzer, Spaces und Berechtigungen

Vier fiktive Nutzer plus das Admin-Konto, mit bewusst unterschiedlichen Space-Zuschnitten. „Persönlich",
„Team" und „Einzel" sind dabei keine Space-Arten — alle Spaces sind gleich gebaut und unterscheiden sich
nur darin, wer Mitglied ist (siehe [spaces-and-assets.md](./spaces-and-assets.md), „Es gibt nur eine Art
von Space"):

| Nutzer | Rolle im Szenario | Spaces |
|---|---|---|
| Maria Weber | Sachbearbeiterin Meldewesen | Space „Meldewesen & Ausweise" (gemeinsam mit Selin Kaya), zusätzlich ihr eigener Space, in dem niemand sonst Mitglied ist |
| Selin Kaya | Sachbearbeiterin Meldewesen | Space „Meldewesen & Ausweise" |
| Thomas Klein | Sachbearbeiter Kfz-Zulassung | Space „Kfz-Zulassung", alleiniges Mitglied |
| Andrea Vogt | Amtsleitung Bürgerbüro | Space „Amtsleitung Bürgerbüro", alleiniges Mitglied |
| Administrationskonto | Systemadministration | richtet ein und indiziert (`SYSTEM_ADMIN`) |

Das Administrationskonto ist ein reguläres Konto aus dem Keycloak-Realm der Demo; die Systemrolle
erhält es über `OPAA_INITIAL_ADMIN_EMAIL` wie in jeder anderen Installation. Die Space-Spalte zählt nur
die fachlich gestellten Spaces auf: Jeder Nutzer bekommt beim ersten Login zusätzlich automatisch seinen
Default-Space (`SpaceService#ensureDefaultSpace`, `isDefault`), der nicht eigens eingerichtet wird.

Leserechte auf den Bibliotheken — vergeben als Asset-Rolle `VIEWER`, die Bibliotheken selbst gehören dem
Admin-Konto:

| Bibliothek | Meldewesen (Maria, Selin) | Kfz (Thomas) | Amtsleitung (Andrea) |
|---|---|---|---|
| Leistungen Meldewesen & Ausweise | ✔ | — | ✔ |
| Leistungen Kfz-Zulassung | — | ✔ | ✔ |
| Satzungen & Gebührenordnungen | ✔ | ✔ | ✔ |
| Pressemitteilungen | ✔ | ✔ | ✔ |
| Interne Dienstanweisungen Meldewesen | ✔ | — | ✔ |

Dieselben Bibliotheken sind den fachlichen Spaces zusätzlich als **Datenquellen zugeordnet**
(Space↔Bibliothek-Assoziation als reine Kuratierung, #706): „Meldewesen & Ausweise" trägt die vier
für das Sachgebiet lesbaren Bibliotheken, „Kfz-Zulassung" seine drei, „Amtsleitung Bürgerbüro" alle
fünf. `@Alles-Wissen` durchsucht in diesen Spaces genau die zugeordneten Bibliotheken, geschnitten
mit den Leserechten der fragenden Person. Marias persönlicher Space bleibt bewusst ohne Zuordnung —
dort greift `@Alles-Wissen` auf alle für sie lesbaren Bibliotheken zurück. Die Zuordnung gewährt
keinerlei Zugriff; die Matrix oben bleibt die alleinige Rechtequelle.

Damit sind die Vorführmomente konstruierbar: Maria und Thomas stellen dieselbe Frage zu einer internen
Dienstanweisung — Maria erhält die belegte Antwort, Thomas die Auskunft, dass dazu nichts vorliegt. Die
Amtsleitung sieht amtsweit alles. Weil die Berechtigungsprüfung Teil der Vektorsuche ist und nicht ein
Nachfilter, ist der unberechtigte Treffer bei Thomas nicht nur unterdrückt, sondern nie geladen.

---

## Demo-Drehbuch (Skizze)

Etwa acht vorbereitete Fragen, jede so gewählt, dass sie eine Eigenschaft der Rechtematrix oder der
Konnektorvielfalt oben vorführt — von der einfachen belegten Auskunft (Frage 1) über die
Berechtigungs-Doppelfrage (Frage 5, der stärkste Vorführ-Moment) bis zur bewusst unbeantwortbaren
Frage (Frage 8). Das ausformulierte Drehbuch mit allen acht Fragen (Konto, erwartete Antwort,
Quellbibliothek) steht in [`../market/demo-drehbuch.md`](../market/demo-drehbuch.md).

---

## Installation und Seed

Anmeldung über OIDC/Keycloak (Realm-Import), Startbefehl, Seed-Mechanismus mit den beiden
Datenprofilen `demo`/`e2e` und die Zielprüfung der Compose-internen Adressen sind in
[`../../demo/README.md`](../../demo/README.md) beschrieben — Installation, Nutzerkonten mit
Passwörtern (Demo-Werte, keine Secrets), öffentliche Instanz, Korpus-Aktualisierung.

---

## Integrationspunkte

- **Epic #708** führt die Umsetzung; die aus #224 übernommenen Tickets #229, #230, #232 und #233 werden
  auf dieses Konzept nachgezogen. #229 verweist heute noch auf einen Bind-Mount von `eval/corpus/` mit
  1.000 Superhelden-Dateien und muss auf `demo/` und die fünf Bibliotheken umgeschrieben werden. Beim
  selben Durchgang fällt sein „Wichtiger Befund" weg, nginx-`autoindex` sei für den Crawler unlesbar —
  seit #550 stimmt das nicht mehr.
- **Epic #224 (Eval)** bleibt unberührt; der Superhelden-Korpus ist ausschließlich Messartefakt. Der
  Abschnitt „Öffentliche Demo" in [search-quality-evaluation.md](./search-quality-evaluation.md)
  beschreibt den abgelösten Stand und wird mit der Umsetzung auf dieses Dokument verwiesen.
- **#370 (Landing-Page-Screenshots)** wird durch den Verwaltungskorpus entblockt, bleibt aber
  Marketing-Arbeit außerhalb des Epics.
- **Rechtemodell:** Die Demo nutzt Wissensbibliotheken, Asset-Rollen und Spaces gemäß
  [spaces-and-assets.md](./spaces-and-assets.md), Konten und Anmeldung gemäß
  [access-control.md](./access-control.md) — sie führt keinen neuen Mechanismus ein, sie führt die
  vorhandenen vor.
- **Quellzuordnung:** Dass eine Konnektorquelle zu genau einer Wissensbibliothek gehört, ist seit
  ADR-0018 strukturell gegeben und damit Voraussetzung dafür, dass die fünf Bibliotheken sauber getrennt
  befüllt werden. Offen sind in **#207** unter anderem die Obergrenze der Freigabe für
  konnektorgespeiste Bibliotheken und der Ausschluss einzelner Konnektordokumente; für die Demo genügen
  gezielte Grants an die vier Nutzer.
- **E2E-Suite:** Seit **#233** bezieht die Suite unter `e2e/` ihre Ausgangsdaten aus dem
  `e2e`-Datenprofil des gemeinsamen Seeds (`demo/seed/seed.py --profile e2e`, von
  `scripts/run-e2e.mjs` vor der Playwright-Suite ausgeführt) statt aus einer eigenen
  Testdatenbereitstellung. Deren frühere Inhalte liegen jetzt unter `demo/seed/e2e-data/`
  (`rss-feed/`, `test-documents/`), neben `demo/seed/profiles.py`s `E2E_PROFILE`, das sie
  referenziert; `e2e/fixtures/` enthält nur noch echte Playwright-Fixtures (Anmeldung, Chat,
  Barrierefreiheit).

---

## Offene Fragen / Zukünftige Erweiterungen

- Werkzeug für die PDF/DOCX/PPTX-Erzeugung im Generator (z. B. pandoc) — Entscheidung im Generator-Ticket
- Genauer Umfang je Bibliothek und Feinschnitt der Leistungsauswahl — beim Umschreiben des LHM-Materials
- Ob die Kölner Pressemeldungen direkt einfließen (DL-DE-BY-2.0, Quellenvermerk) oder rein als
  Stilvorlage dienen
- Späterer Ausbau: Verwaltungskorpus als deutsche Eval-Domäne in #224 (mit LHM-QA als Golden-Grundlage)

---

## Erfolgs-Metriken

- Ein Interessent startet den Stack mit einem Befehl und bekommt ohne weitere Handgriffe eine gefüllte,
  durchsuchbare Instanz mit vier Konten hinter der regulären Keycloak-Anmeldung.
- Die Berechtigungsgrenze ist live vorführbar: dieselbe Frage liefert je Konto nachvollziehbar
  unterschiedliche Antworten.
- Jede der acht Drehbuchfragen führt zu einer belegten Antwort — außer der bewusst unbeantwortbaren,
  bei der keine Quelle genannt und keine erfunden wird.
- Der Smoke-Test gegen das `demo`-Profil läuft in der CI durch, ohne die E2E-Feature-Tests an den
  Demo-Inhalten zu koppeln.
