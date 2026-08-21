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
> abgelöst.

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
3. **Alle Konnektortypen und mehrere Dateiformate:** HTTP-Verzeichnis (Markdown und PDF), RSS-Feed
   (selbst gehostete, statische XML) und manueller Upload (DOCX, PDF, PPTX).
4. **Ein Befehl installiert alles:** Compose-Profil `demo` plus Seed-Skript richten Nutzer, Spaces,
   Bibliotheken, Berechtigungen und Indizierung ein. Zielplattform ist gleichgültig — lokal,
   opaa.ewerlin.com oder ein alternativer Host.
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
Dateiformat. Der Formatvorrat ist durch `SupportedDocumentFormats` gesetzt — `.md`, `.txt`, `.pdf`,
`.docx`, `.doc` und `.pptx`; `.csv` ist nicht darunter:

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
| dev-admin / Maintainer | Systemadministration | richtet ein und indiziert (`SYSTEM_ADMIN`) |

Leserechte auf den Bibliotheken — vergeben als Asset-Rolle `VIEWER`, die Bibliotheken selbst gehören dem
Admin-Konto:

| Bibliothek | Meldewesen (Maria, Selin) | Kfz (Thomas) | Amtsleitung (Andrea) |
|---|---|---|---|
| Leistungen Meldewesen & Ausweise | ✔ | — | ✔ |
| Leistungen Kfz-Zulassung | — | ✔ | ✔ |
| Satzungen & Gebührenordnungen | ✔ | ✔ | ✔ |
| Pressemitteilungen | ✔ | ✔ | ✔ |
| Interne Dienstanweisungen Meldewesen | ✔ | — | ✔ |

Damit sind die Vorführmomente konstruierbar: Maria und Thomas stellen dieselbe Frage zu einer internen
Dienstanweisung — Maria erhält die belegte Antwort, Thomas die Auskunft, dass dazu nichts vorliegt. Die
Amtsleitung sieht amtsweit alles. Weil die Berechtigungsprüfung Teil der Vektorsuche ist und nicht ein
Nachfilter, ist der unberechtigte Treffer bei Thomas nicht nur unterdrückt, sondern nie geladen.

---

## Demo-Drehbuch (Skizze)

Etwa acht vorbereitete Fragen, dokumentiert mit erwartetem Antwortcharakter und Quellbibliothek:

1. **Gebührenfrage** („Was kostet ein Personalausweis für eine 22-Jährige?") — Beleg aus Satzungs-PDF
2. **Verfahrensfrage** („Welche Unterlagen brauche ich für die Ummeldung?") — Leistungsbeschreibung
3. **Aktualitätsfrage** („Wann ist das Bürgerbüro wegen des Stadtfests geschlossen?") — RSS-Meldung
4. **Kfz-Frage** („Kann ich mein Wunschkennzeichen online reservieren?") — Kfz-Bibliothek
5. **Berechtigungs-Doppelfrage** (identische Frage als Maria und als Thomas, siehe oben) — Dienstanweisung
6. **Quer-Bibliotheks-Frage** („Was gilt bei Gebührenbefreiung wegen Bedürftigkeit?") — Satzung +
   Dienstanweisung, je nach Rechten unterschiedlich vollständig
7. **Amtsleitungs-Frage** über beide Sachgebiete hinweg — nur Andrea bekommt die vollständige Antwort
8. **Bewusst unbeantwortbare Frage** („Wie beantrage ich in Rheinfurt eine Fischereierlaubnis?") — zeigt,
   dass keine Quellen erfunden werden und ungültige Belege gekennzeichnet sind (Belegvalidierung, #697)

Das ausformulierte Drehbuch entsteht mit dem Seed und wird in `docs/` neben der Installationsanleitung
gepflegt.

---

## Installation und Seed

- `docker compose --profile demo up` startet den Stack inklusive Korpus-Webserver (Apache httpd mit
  `IndexOptions FancyIndexing HTMLTable`, siehe Befund in #229 — das `autoindex` von nginx erzeugt eine
  `<pre>`-Liste, die der `AutoindexCrawlerService` nicht auswerten kann) und des statischen RSS-Feeds.
- Ein Seed-Skript legt Nutzer (Keycloak-Realm-Import), Spaces, Bibliotheken und Berechtigungen an, lädt
  die Upload-Dokumente hoch und stößt die Indizierung je Bibliothek an. Seit
  [ADR-0018](../decisions/0018-quellkonfiguration-in-der-bibliothek.md) trägt die Bibliothek Quellentyp
  und Quellkonfiguration selbst; der Seed legt also je Bibliothek einmalig die Quelle an und löst
  anschließend deren Lauf aus.
- Der Seed-Mechanismus ist als wiederverwendbarer Baustein mit zwei Datenprofilen geschnitten: `demo`
  und `e2e`. E2E-Feature-Tests laufen ausschließlich gegen das minimale `e2e`-Profil; gegen das
  `demo`-Profil läuft genau ein Smoke-Test (Setup läuft durch, eine Suche liefert eine belegte Antwort).
- **Zu klären in der Umsetzung:** Die mit #699 eingeführte Zielprüfung ausgehender Abrufe
  (`opaa.indexing.target-validation`, standardmäßig aktiv) lehnt Adressen in privaten und
  Loopback-Bereichen ab — genau dort liegen die Compose-internen Ziele der Demo. Das Demo-Profil braucht
  deshalb voraussichtlich einen Eintrag in der Hostnamen-Allowlist für Korpus-Webserver und Feed.
- Dokumentation in `docs/`: Installation, Nutzerkonten mit Passwörtern (Demo-Werte, keine Secrets),
  Korpus-Aktualisierung, Drehbuch.

---

## Integrationspunkte

- **Epic #708** führt die Umsetzung; die aus #224 übernommenen Tickets #229, #230, #232 und #233 werden
  auf dieses Konzept nachgezogen. #229 verweist heute noch auf einen Bind-Mount von `eval/corpus/` mit
  1.000 Superhelden-Dateien und muss auf `demo/` und die fünf Bibliotheken umgeschrieben werden.
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
  befüllt werden. Offen ist in **#207** nur noch die Obergrenze der Freigabe für konnektorgespeiste
  Bibliotheken; für die Demo genügen gezielte Grants an die vier Nutzer.
- **E2E-Suite:** Die heutige Suite unter `e2e/` bringt ihre Testdaten als eigene Fixtures mit
  (`e2e/fixtures/`). Das `e2e`-Profil des gemeinsamen Seeds tritt an die Stelle dieser Einzellösung.

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
  durchsuchbare Instanz mit vier Konten.
- Die Berechtigungsgrenze ist live vorführbar: dieselbe Frage liefert je Konto nachvollziehbar
  unterschiedliche Antworten.
- Jede der acht Drehbuchfragen führt zu einer belegten Antwort — außer der bewusst unbeantwortbaren,
  die als solche gekennzeichnet wird.
- Der Smoke-Test gegen das `demo`-Profil läuft in der CI durch, ohne die E2E-Feature-Tests an den
  Demo-Inhalten zu koppeln.
