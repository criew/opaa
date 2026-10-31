# Bausteine mit Befund — Zeitraum 30.09.–31.10.2026

Die Fortschreibung zum Stichtag 31.10.2026 hat **846 Vorgänge** geprüft — die 681 im Zeitraum
geschlossenen Issues und die 165 gemergten Pull Requests ohne Issue-Verknüpfung (davon 73
Renovate-Updates). Diese Datei führt die **72 Vorgänge mit Befund**: solche, bei denen die
Lieferung wesentlich vom Vorgang abweicht. Aufnahmekriterium ist eines der folgenden drei
(Kennziffer in der Zeile „Befund"):

1. **Nicht geliefert.** Der Vorgang wurde geschlossen, ohne dass das Beschriebene entstand — als
   `not planned`, als Duplikat oder als `completed` durch Ablösung.
2. **Anders geschnitten.** Die Lieferung weicht in Umfang oder Zuschnitt wesentlich ab: Wesentliches
   fehlt, ein Abnahmekriterium blieb unerfüllt, oder der eingeschlagene Weg ist ein anderer als der
   beschriebene — bis hin zum gegenteiligen.
3. **Nicht mehr vorhanden.** Das Gelieferte existiert zum Stichtag nicht mehr oder wurde ersetzt —
   auch eine Lieferung des Vorzeitraums, die in diesem Zeitraum zurückgebaut wurde.

Die übrigen **774 Vorgänge sind ohne Befund**: geliefert wie beschrieben, und das Gelieferte steht
noch. Ihre Nummern stehen am Ende dieser Datei.

Suche mit `Issue #NNN` bzw. `PR #NNN`; jeder Abschnitt trägt den Anker `#issue-NNN` bzw. `#pr-NNN`.

---

<a id="issue-1293"></a>

## Issue #1293 — feat: E2E-Testkonzept für Konnektoren mit aufwändigen Quellsystemen
- Geschlossen: 2026-10-24 (completed)
- Labels: enhancement, epic, ci
- PRs: #3115 (2026-10-24)
- Befund: 2 (Phase 2 bewusst entfallen, Konzept schreibt Bestand fest)

**Laut Issue:** Konzept-Epic in drei Phasen: ein Diskussionspapier und ein ADR mit einer Vorlage für einen Konnektor-Vertragstest (Phase 1); ein Vertragstest für FILESYSTEM, HTTP_DIRECTORY und RSS_FEED mit Ersatzsystemen in Testcontainers (Phase 2); die Pflicht für neue Konnektoren in AGENTS.md und in den Konnektor-Epics (Phase 3).

**Geliefert:** PR #3115 bringt ADR-0057 „Teststufen für Konnektoren“. Es schreibt fest, was im Code schon gebaut war: Pflicht sind ein Testdoppel (`Fake<System>Server`) und die gemeinsamen Verträge (`RunSecretContract`, `FileStoreContract`, `<Typ>LogLeakTest`). Dazu kommen je nach System ein leichter Container in `test`, eine schwere nächtliche Suite oder ein Lauf gegen eine echte Instanz, der nur lokal läuft. Die Stufenpflicht steht auch im Modul-`AGENTS.md` der Konnektoren. **Abweichungen:** Einen eigenen Datei-Vertragstest für Dateisystem, Webverzeichnis und RSS gibt es bewusst nicht (ADR-0057, „Verworfene Alternativen“). Record/Replay und CI-Läufe gegen echte SaaS-Mandanten sind verworfen. Ein Diskussionspapier entstand nicht. Folge-Issues gibt es keine.

**Verifikation:** `docs/decisions/0057-teststufen-fuer-konnektoren.md` liegt vor.

**Themen:** testkonzept, konnektoren, adr, teststufen, verworfen

---

<a id="issue-1441"></a>

## Issue #1441 — feat: UI-Politur-Runde Oktober 2026 — Sammel-Epic
- Geschlossen: 2026-10-02 (completed)
- Labels: enhancement, epic, frontend
- PRs: keine (Lieferung über Sub-Issues)
- Befund: 2 (Restpunkte unentschieden, Stakeholder-Bewertung fehlt)

**Laut Issue:** Sammel-Epic für die UI-Politur nach der Durchsicht vom 24.09.2026. Es enthält Grundsatzentscheidungen (Badges statt Icons, Begriff „Space“ bleibt, helle Untermenüleiste, eigene Bestände je Asset-Typ in der Hauptnavigation) und eine Liste gesammelter Punkte.

**Geliefert:** Alle 14 Sub-Issues sind geliefert: #1449, #1910–#1922 (Anmeldeseite, zuletzt genutzter Space, Übersichtskomponente, Space-Kachel, Hauptnavigation, Bibliotheksübersicht, Space-Einstellungen mit Reitern, Chatliste, Chat-Titel, Eingabefeld, Fußzeile, Seitenleistenfarbe). Diese Sub-Issues wurden schon im Vorzeitraum (24.–27.09.) geschlossen. Herausgelöst wurden #1448, #1927, #1928 und die neuen Funktionen #1923–#1926. **Laut Abschlusskommentar ohne Entscheidung geblieben:** die Verständlichkeit der Hilfetexte und die Reduktion der Metadaten-Anzeige an der Fundstelle (teilweise durch #1449). Die Bewertung durch die Stakeholder-Rolle Sachbearbeiter liegt nicht vor.

**Verifikation:** Nicht einzeln geprüft. Die Hauptnavigation „Wissen“/„Prompts“ aus #1915 ist im selben Zeitraum durch den einen Katalog (#2094) ersetzt worden.

**Themen:** ui-politur, epic, frontend, offene-punkte

---

<a id="issue-1860"></a>

## Issue #1860 — fix(security): Adressprüfung ist gegen DNS-Rebinding zwischen Prüfzeit und Verbindungsaufbau offen
- Geschlossen: 2026-10-02 (completed)
- Labels: bug, backend, size:M, security
- PRs: #2101 (2026-10-02)
- Befund: 1 (als hingenommene Grenze geschlossen), im Zeitraum überholt durch #3239

**Laut Issue:** `TargetAddressValidator` löst den Hostnamen zur Prüfzeit auf, der `HttpClient` beim Verbindungsaufbau erneut. Ein Name mit kurzer TTL kann dazwischen auf eine interne Adresse umschwenken (DNS-Rebinding). Gefordert war, die geprüfte Adresse an den Verbindungsaufbau zu binden.

**Geliefert:** Nach Entscheidung des Maintainers kam kein Code. Ein Lösungsversuch in #2100 hätte die Verbindungsschicht auf Apache HttpClient umgebaut (39 Dateien) und wurde verworfen. PR #2101 dokumentiert die Restlücke als bewusst hingenommene Grenze in `security-and-compliance.md` und im Handbuch. Als Gegenmaßnahme auf Netzebene nennt er Egress-Regeln und Proxy.

**Verifikation:** Diese Entscheidung ist im selben Zeitraum überholt. #3239 bzw. PR #3323 (29.10.2026) bindet geprüfte Namen JVM-weit über `io.opaa.security.PinnedAddressResolverProvider` an ihre Adressen. Grundlage ist ADR-0063 „Bindung und Bewachung geprüfter Namen gegen DNS-Rebinding“. Zum Stichtag ist der Schutz damit gebaut; die Doku-Lösung aus #2101 gilt nicht mehr.

**Themen:** sicherheit, ssrf, dns-rebinding, hingenommen, später-ersetzt

---

<a id="issue-1923"></a>

## Issue #1923 — feat(space): Auto-Archivierung und Auto-Löschung von Chats je Space
- Geschlossen: 2026-10-02 (completed)
- Labels: enhancement, backend, frontend, size:M
- PRs: #2112 (2026-10-02)
- Befund: 2 (Schalter mit festen Fristen statt frei wählbarer Fristen)

**Laut Issue:** Je Space zwei optionale Einstellungen, „Chats archivieren nach N Tagen“ und „Archivierte Chats löschen nach M Tagen“, beide ab Werk aus. Ein täglicher Lauf ohne angeheftete Chats, Löschen über den bestehenden Löschpfad, Audit der Einstellung und eine Personalrats-Bewertung.

**Geliefert:** PR #2112 folgt ausdrücklich der Maintainer-Entscheidung vom 02.10.2026 und der Personalrats-Auflage, nicht dem Issue-Text. Je Space gibt es nur **einen Schalter** „Inaktive Chats automatisch archivieren und löschen“. Die Fristen legt der Betreiber fest (`opaa.chat.auto-cleanup.*`, 90/365 Tage, Untergrenzen 90/30); je Space sind sie nicht wählbar. Die Fristen wirken nicht rückwirkend (sie laufen ab dem Einschaltzeitpunkt), „Wird am … gelöscht“ wird angezeigt, und das Audit läuft über `SPACE_CHANGED`. Der Lauf `ChatAutoCleanupScheduler` schreibt selbst keinen Protokolleintrag.

**Verifikation:** `io.opaa.chat.ChatAutoCleanupScheduler` und `ChatAutoCleanupService` bestehen. Seit dem Mehrinstanzbetrieb (#1292) läuft der Scheduler unter der Sperre für geplante Läufe statt unter der Single-Instance-Annahme.

**Themen:** spaces, chat, aufbewahrung, löschung, personalrat, anders-geschnitten

---

<a id="issue-1926"></a>

## Issue #1926 — feat(chat): Spracheingabe — Batch-Diktat über OpenAI-kompatible Endpunkte
- Geschlossen: 2026-10-13 (completed)
- Labels: enhancement, backend, frontend, size:L, feature-flag
- PRs: keine verknüpft (geliefert mit #2629, 2026-10-13)
- Befund: 2 (nur Stufe 1, Stufe 2 geparkt)

**Laut Issue:** Fragen einsprechen; der Text landet bearbeitbar im Eingabefeld. Vorgesehen waren zwei Stufen, „beide sollen gebaut werden“: Stufe 1 als Batch-Diktat, Stufe 2 als Live-Transkript über einen geeigneten Endpunkt. Angebunden wird nur über eine OpenAI-kompatible Transkriptionsrolle.

**Geliefert:** PR #2629 liefert Stufe 1, das Batch-Diktat über die Transkriptionsrolle, verifiziert gegen OpenRouter (`whisper-large-v3-turbo`). Die Muss-Anforderungen der Personalrats-Bewertung sind umgesetzt. Der Oberflächenhinweis auf einen externen Endpunkt (`external`, `OPAA_TRANSCRIPTION_EXTERNAL`) entfiel per Entscheidung vom 13.10.; der Datenschutzhinweis steht nur im Handbuch. **Nicht geliefert:** Stufe 2 (Live-Transkript), ausgelagert nach #2615 und dort geparkt (offen). Die Verifikation gegen speaches wurde bewusst nicht abgenommen. Weitere Folge-Issues sind #2632 und #2633.

**Verifikation:** `frontend/src/shared/ui/transcription/` und `backend/.../llm/ModelRoleEndpoints.java` (Transkriptionsrolle) bestehen.

**Themen:** chat, spracheingabe, transkription, modellrolle, teil-schnitt

---

<a id="issue-1928"></a>

## Issue #1928 — feat: UI-Redesign der Admin-Seiten
- Geschlossen: 2026-10-24 (completed)
- Labels: enhancement, epic, frontend
- PRs: keine (Lieferung über Sub-Issues)
- Befund: 2 (Sichtung und Abnahmekriterien nicht belegt, nur Teil der Seiten)

**Laut Issue:** Den ganzen Adminbereich sichten: Benutzer, Gruppen, Verzeichnisdienste, Modelle, Branding, Systemeinstellungen, Diagnose. Vorgesehen waren eine gemeinsame Sichtung mit Befundliste (Phase 1), höchstens acht Sub-Issues (Phase 2) und die Umsetzung. Abnahmekriterien: eine Befundliste, alle Befunde entschieden, eine Bewertung der Stakeholder-Rolle Betrieb, Handbuch auf dem Ist-Stand.

**Geliefert:** Acht Sub-Issues sind geschlossen: die UI-Politur von Benutzer- und Gruppenverwaltung (#1978, schon im Vorzeitraum), Anlegerechte (#2321), Lebenszyklus (#2336, dazu #2340 und #2341), Verzeichnisabgleich (#2498) und Zugänge/Freigaben (#2504). Dazu kommt der Begriff „Externe KI-Werkzeuge“ statt „Fremdzugang“ (#2506). **Abweichung:** Die Seiten Modelle, Branding, Systemeinstellungen und Diagnose haben kein Sub-Issue. Weder eine Befundliste aus einer Sichtung noch eine Betrieb-Bewertung ist am Epic dokumentiert; der Abschlusskommentar nennt nur die Sub-Issues und „keine Folge-Issues“.

**Verifikation:** Nicht im Code geprüft. Am Epic steht nur der Abschlusskommentar.

**Themen:** admin, ui-politur, epic, abnahme-offen

---

<a id="issue-2041"></a>

## Issue #2041 — fix(query): Chatmodell liest Tabellenzeilen falsch zu, obwohl der Chunk sauber strukturiert ist
- Geschlossen: 2026-10-02 (not planned)
- Labels: bug, backend, size:M, evaluation
- PRs: keine
- Befund: 1 (nicht weiter untersucht)

**Laut Issue:** Die Tabelle eines Einsatzplans kommt seit #2038 sauber strukturiert beim Modell an. Trotzdem ordnet das Demo-Modell (claude-haiku-4-5) Zeilen falsch zu. Geplant waren Kontrollversuche mit identischem Prompt gegen Haiku und ein stärkeres Modell sowie die Klärung, ob Prompt oder Chunk-Format nachgebessert werden muss.

**Geliefert:** Nicht umgesetzt. Begründung des Maintainers: Haiku ist nur das Modell der Demo, im realen Einsatz laufen andere Modelle. Eine Untersuchung der Tabellen-Lesefähigkeit gerade dieses Modells lohne nicht.

**Verifikation:** Entfällt.

**Themen:** antwortqualität, tabellen, demo-modell, nicht-geplant

---

<a id="issue-2092"></a>

## Issue #2092 — refactor(assets): Schaufenster `listed` vollständig entfernen
- Geschlossen: 2026-10-02 (completed)
- Labels: enhancement, backend, frontend, size:M, demo
- PRs: #2106 (2026-10-02)
- Befund: 3 (Rückbau einer Lieferung des Vorzeitraums)

**Laut Issue:** Das Schaufenster `listed` – Assets, die im Katalog sichtbar, aber nicht lesbar sind – entfällt vollständig.

**Geliefert:** Wie beschrieben. Im Katalog erscheint nur noch, was die Person lesen darf. Damit ist eine Lieferung des Vorzeitraums zurückgebaut; Teil der Neuordnung zu einem Katalog (Epic #2070, ADR-0039).

**Verifikation:** Kein `listed` mehr in Asset-Modell und API.

**Themen:** katalog, assets, rückbau

---

<a id="issue-2094"></a>

## Issue #2094 — feat(frontend): Katalog als einziger Einstieg — nur Kacheln, generisches „Neu“ mit Typwahl
- Geschlossen: 2026-10-02 (completed)
- Labels: enhancement, frontend, size:L, e2e
- PRs: #2116 (2026-10-02)
- Befund: 3 (ersetzt die Hauptnavigation „Wissen“/„Prompts“ des Vorzeitraums)

**Laut Issue:** Der Katalog wird der einzige Einstieg zu allen Assets, mit Kacheln und einem „Neu“ mit Typwahl.

**Geliefert:** Wie beschrieben. Die getrennten Navigationspunkte „Wissen“ und „Prompts“ aus dem Vorzeitraum (#1915) entfallen.

**Verifikation:** Katalog mit Typ-Registry im Frontend vorhanden.

**Themen:** katalog, navigation, rückbau

---

<a id="issue-2096"></a>

## Issue #2096 — feat(spaces): Zuordnung als harte Grenze — Übergangsregel und Strikt-Modus entfallen, @Space-Wissen
- Geschlossen: 2026-10-02 (completed)
- Labels: enhancement, backend, frontend, size:L, demo, e2e
- PRs: #2107 (2026-10-02)
- Befund: 3 (Rückbau einer Lieferung des Vorzeitraums)

**Laut Issue:** Die Space-Zuordnung wird zur harten Grenze der Suche; die Übergangsregel „Space ohne Zuordnung durchsucht alles“ und der Strikt-Modus entfallen.

**Geliefert:** Wie beschrieben, mit Chip „@Space-Wissen“ im Chat. Übergangsregel und Strikt-Modus aus dem Vorzeitraum sind zurückgebaut (ADR-0039).

**Verifikation:** Retrieval filtert ausschließlich nach Space-Zuordnung.

**Themen:** spaces, retrieval, rückbau

---

<a id="issue-2099"></a>

## Issue #2099 — feat(spaces): Persönliche Spaces abschaffen — nur noch eine Art Space
- Geschlossen: 2026-10-13 (not planned)
- Labels: enhancement, backend, frontend, size:L, workspace
- PRs: keine
- Befund: 1 (verworfen)

**Laut Issue:** Persönliche Spaces (`is_default`) abschaffen. Beim Anmelden entsteht kein Space mehr, bestehende werden normale Ein-Personen-Spaces. Offen waren der Einstieg ohne `CREATE_SPACE`, die Kontolöschung, Nachfolge und Historie. Das Zielbild stammt aus Epic #2070, wurde dort aber bewusst herausgenommen.

**Geliefert:** Nicht umgesetzt. Entscheidung des Maintainers vom 13.10.2026: Der persönliche Standard-Space bleibt. Eine Abschaffung brächte kaum Vereinfachung, weil Löschung, Nachfolge und Historie für Ein-Personen-Spaces wieder Sonderregeln bräuchten. Das Handbuch beschreibt den Standard-Space seit #2584.

**Verifikation:** `Space.isDefault` (`is_default`) besteht weiter.

**Themen:** spaces, persönlicher-space, nicht-geplant

---

<a id="issue-2127"></a>

## Issue #2127 — Verbindungstest des Verzeichnis-Konnektors dauert bei großen Bäumen sehr lange
- Geschlossen: 2026-10-03 (completed)
- Labels: enhancement, backend
- PRs: #2186 (2026-10-03)
- Befund: 3 (Dokumentzählung des Vorzeitraums entfernt)

**Laut Issue:** Der Verbindungstest FILESYSTEM läuft bei großen Bäumen zu lange.

**Geliefert:** Der Test prüft nur noch, ob die Wurzel geöffnet werden kann; Rekursion und Dokumentzählung entfallen.

**Verifikation:** Verbindungstest ohne Zählung im Konnektor.

**Themen:** konnektoren, verbindungstest, rückbau

---

<a id="issue-2134"></a>

## Issue #2134 — feat(spaces): Mitgliederliste aufräumen, Wachstumssignal für Gruppen entfällt
- Geschlossen: 2026-10-03 (completed)
- Labels: enhancement, backend, frontend, size:M
- PRs: #2138 (2026-10-03)
- Befund: 3 (Rückbau einer Lieferung des Vorzeitraums)

**Laut Issue:** Die Mitgliederliste wird aufgeräumt, das Wachstumssignal für Gruppen entfällt.

**Geliefert:** Wie beschrieben; das Wachstumssignal bei Mitgliedern und Freigaben aus dem Vorzeitraum ist entfernt.

**Verifikation:** Kein Wachstumssignal mehr in Space-Mitgliedern und Freigaben.

**Themen:** spaces, gruppen, rückbau

---

<a id="issue-2139"></a>

## Issue #2139 — fix(frontend): Gefahr-Farbe als Text erreicht den Mindestkontrast nicht
- Geschlossen: 2026-10-03 (not planned)
- Labels: bug, frontend, size:S
- PRs: keine
- Befund: 1 (Duplikat)

**Laut Issue:** Die Gefahrfarbe `#E5484D` erreicht als Text auf Weiß nur 3,91:1. Gefordert war eine eigene Textrolle mit ≥ 4,5:1 in beiden Schemata und das Entfernen der axe-Ausnahme in `space-members.spec.ts`.

**Geliefert:** Hier nicht. Als Duplikat von #2150 geschlossen; dort lieferte PR #2157 die Rolle `danger` je Farbschema (hell `#CE2C31`, dunkel `#FF6369`). Die axe-Ausnahme nimmt laut Kommentar #2150 mit heraus.

**Verifikation:** Siehe #2150 (ohne Befund).

**Themen:** barrierefreiheit, kontrast, duplikat

---

<a id="issue-2147"></a>

## Issue #2147 — feat: OAuth-Verbindungen und mehrstufige Konnektoren — Quellen-Freigabe, persönliche Konten, Konnektor-Steuerung
- Geschlossen: 2026-10-14 (completed)
- Labels: enhancement, epic, backend, frontend, security, auth, feature-flag
- PRs: keine (Lieferung über Sub-Issues)
- Befund: 2 (Exchange-Kriterium ausgelagert, Vorfallszugriff verworfen)

**Laut Issue:** Grundlage für Konnektoren, die nicht nur ein Admin einrichtet. Dazu gehören die Konnektor-Freigabe je Zugang bzw. Typ mit Sperre, mehrstufige Konnektoren mit persönlichen Konten und privaten Bibliotheken sowie OAuth-Verbindungen (zuerst Dropbox) und persönliche Konten wie ein Outlook-Postfach.

**Geliefert:** Phase 1 brachte Zugänge (Modul `connections`, ADR-0041), die Lauf-SPI, Freigabe und Sperre, die Profilpflicht, den Zugangswunsch und die Konnektor-Naht (#2159–#2162, #2178, #2218–#2221, #2226, #2236). Phase 2 brachte verbundene Konten mit persönlichem Geheimnis, das Verbindungsprotokoll, private Bibliotheken mit nachweisbarer Löschung und Kontingent sowie Nextcloud per App-Passwort (#2163–#2167 und Nacharbeiten). Phase 3 brachte den OAuth-Baustein, „Quelle verbinden“ und die MCP-Grundlage (#2168, #2169, #2173). Dazu kamen die Postfach-Prüfliste (#2170), der Spike #2174 und E2E (#2175). **Abweichungen:** Das Abnahmekriterium „Konto per OAuth (Exchange) verbinden“ ist in das eigene, offene Epic #2172 (Exchange/Outlook) ausgelagert. Der Vorfallszugriff (#2171) ist nicht geplant. Dropbox lief unter Epic #2146.

**Verifikation:** `backend/src/main/java/io/opaa/connection/` mit `account`, `consent`, `log`, `oauth`, `profile` und `token` besteht. #2172 ist offen.

**Themen:** konnektoren, zugänge, oauth, verbundene-konten, private-bibliotheken, epic, ausgelagert

---

<a id="issue-2156"></a>

## Issue #2156 — refactor(spaces): Space-Sichtbarkeit auch im Backend entfernen
- Geschlossen: 2026-10-03 (completed)
- Labels: enhancement, backend, size:S
- PRs: #2179 (2026-10-03)
- Befund: 3 (Rückbau einer Lieferung des Vorzeitraums)

**Laut Issue:** Nach der Oberfläche (#2131) entfällt die Space-Sichtbarkeit (PRIVATE/DISCOVERABLE/OPEN) auch in Backend und API.

**Geliefert:** Wie beschrieben. Zusammen mit #2131 ist die Space-Sichtbarkeit des Vorzeitraums vollständig zurückgebaut; Reichweite entsteht nur über Mitgliedschaft und Freigaben.

**Verifikation:** Kein Sichtbarkeitsfeld mehr an Spaces.

**Themen:** spaces, sichtbarkeit, rückbau

---

<a id="issue-2170"></a>

## Issue #2170 — docs(security): Rechtsklärung für indexierte Postfächer — Aktenführung, Aufbewahrung, Vertretung
- Geschlossen: 2026-10-11 (completed)
- Labels: documentation, size:M, security
- PRs: #2466 (2026-10-11)
- Befund: 2 (Prüfliste für Betreiber statt Rechtsklärung)

**Laut Issue:** Bevor der erste Postfach-Konnektor ausgeliefert wird, sollten Aktenführung, Aufbewahrungspflichten und Vertretungsregeln für indexierte Postfächer rechtlich geklärt sein, mit einer eigenen Regelung für Funktionspostfächer. Das war eine verbindliche Bedingung aus der Stakeholder-Runde zu #2147.

**Geliefert:** Nach Maintainer-Entscheidung vom 11.10.2026 klärt das Projekt die Rechtslage nicht selbst. PR #2466 ergänzt im Handbuch die „Prüfliste vor einem Postfach-Konnektor (Vorbereitung)“: fünf Punkte für den Betreiber, ausdrücklich keine Rechtsberatung. Im selben PR stehen die Doku zu „kein Vorfallszugriff“ (#2171) und die Grenze des Änderungsprotokolls.

**Verifikation:** `docs/handbuch/konten-und-rechte/berechtigungen.md`, Abschnitt „Prüfliste vor einem Postfach-Konnektor (Vorbereitung)“, besteht. Einen Postfach-Konnektor gibt es nicht (#2172 offen).

**Themen:** postfach, recht, aktenführung, betreiberpflicht, anders-geschnitten

---

<a id="issue-2171"></a>

## Issue #2171 — feat(auth): Vorfallszugriff auf private Bibliotheken mit vier Augen, Benachrichtigung und Personalrat
- Geschlossen: 2026-10-11 (not planned)
- Labels: enhancement, backend, frontend, size:M, security, auth
- PRs: keine
- Befund: 1 (verworfen)

**Laut Issue:** Eine neue Befugnis für den Zugriff auf den Inhalt privater Bibliotheken: Antrag durch `AUDITOR`, Bestätigung durch eine zweite benannte Rolle als Vier-Augen-Prüfung in der Datenbank, befristet und auf eine Bibliothek beschränkt, mit Benachrichtigung der Betroffenen und Beteiligung des Personalrats.

**Geliefert:** Nicht umgesetzt. Am 04.10. zurückgestellt (Beschluss 18), am 11.10.2026 endgültig verworfen. OPAA hält nur eine Kopie; ein Vorfall wird in der Originalquelle über die Verfahren des Hauses untersucht (eDiscovery, Admin-Zugriff des Anbieters). Handbuch und Spezifikation wurden mit PR #2466 angepasst.

**Verifikation:** Entfällt. Es gibt keinen Inhaltszugriff Dritter auf private Bibliotheken.

**Themen:** private-bibliotheken, vorfallszugriff, vier-augen, personalrat, nicht-geplant

---

<a id="issue-2204"></a>

## Issue #2204 — docs(adr): Google-Drive-Befunde gegen ein echtes Workspace belegen und in ADR-0040 nachtragen
- Geschlossen: 2026-10-29 (completed)
- Labels: documentation, backend, size:S
- PRs: #3369 (2026-10-29)
- Befund: 2 (kein Beleg gegen echtes Workspace)

**Laut Issue:** ADR-0040 führt mehrere Befunde zum Verhalten von Google als „unsicher“. Sie sollten mit der lokalen Stufe-3-Suite `googleDriveIntegrationTest` gegen ein echtes Workspace belegt und im ADR nachgetragen werden.

**Geliefert:** Der Maintainer entschied am 29.10.2026: kein Lauf gegen ein echtes Workspace, nur das Testdoppel. PR #3369 hält je Befund fest, warum er „begründet unsicher“ bleibt und wie sich der Konnektor verhält, falls die Annahme nicht stimmt. Die prüfbaren Fälle sichert er im Testdoppel ab (Stufe 1). Nebenbei behebt er einen Defekt: Eine Weiterleitung von Drive auf einen fremden Host wurde als `FORBIDDEN` gewertet.

**Verifikation:** `docs/decisions/0040-google-drive-konnektor.md` liegt vor. Die Befunde bleiben unbelegt.

**Themen:** google-drive, adr, beleg-offen, testdoppel, anders-geschnitten

---

<a id="issue-2270"></a>

## Issue #2270 — Maskierung nach Mindestgruppengröße: Teilzahlen je Organisation bilden und Rest 0 auch bei abgelaufenen Verbindungen als „wenige“ behandeln
- Geschlossen: 2026-10-05 (completed)
- Labels: backend, size:S, security
- PRs: keine
- Befund: 2 (ohne PR-Verknüpfung, Restpunkt offen)

**Laut Issue:** Zwei Restlücken der Maskierung nach Mindestgruppengröße aus der Nachprüfung von PR #2259: (a) Teil und Rest einer Teilzahl am Zugang wurden über verschiedene Organisationen gezählt, (b) ein Rest 0 sollte auch bei abgelaufenen Verbindungen als „wenige“ gelten.

**Geliefert:** Kein verknüpfter PR. Laut Abschlusskommentar (05.10.2026) sind (a) und (b) mit PR #2287 geliefert, die Schwellenwarnung nach Maintainer-Entscheidung mit PR #2292; das Differenz-Orakel (Abbruchzahlen, `scheduledErasureCount`) steht als akzeptiertes Restrisiko in ADR-0041. Offen bleibt ein Merkposten aus dem Review von #2287: Der Rückfall „weniger als N“ zählt Besitzerinnen weiter über alle Organisationen – erst mit mehreren Organisationen erreichbar, nur als Kommentar festgehalten.

**Verifikation:** PR #2287 und #2292 sind gemergt (beide in diesem Teilstück ohne Befund). Der Merkposten hat kein eigenes Issue.

**Themen:** maskierung, mindestgruppengröße, datenschutz, zugänge, restrisiko

---

<a id="issue-2305"></a>

## Issue #2305 — feat: Nutzerwünsche Prozesse & Kommunikation aus Feedbackrunde (zur Bewertung)
- Geschlossen: 2026-10-14 (not planned)
- Labels: enhancement, epic
- PRs: keine
- Befund: 1 (not planned, zurückgestellt)

**Laut Issue:** Sammel-Epic für Funktionswünsche einer Anwenderin aus einer Feedbackrunde (06.10.2026) in Richtung Arbeitsabläufe und Kommunikation: Prozessautomatisierung, elektronische Signatur, OZG-artiges Außenfrontend, Kurznachrichten. Ausdrücklich nur aufgenommen, nicht bewertet; Phase 0 wäre die Bewertung je Sub-Issue gewesen.

**Geliefert:** Nicht umgesetzt, auch keine Bewertung. Am 14.10.2026 mit allen Sub-Issues #2306–#2309 ohne Umsetzung geschlossen: „Wir warten ab, bis reale Anforderungen von Behörden vorliegen; dann wird neu bewertet.“

**Verifikation:** Kein Code. Die Wünsche bleiben nur als geschlossene Issues dokumentiert.

**Themen:** produktvision, nutzerwünsche, prozesse, kommunikation, zurückgestellt

---

<a id="issue-2306"></a>

## Issue #2306 — feat: Interne Verwaltungsprozesse mit OPAA automatisieren (Urlaubsanträge, Materialbestellungen)
- Geschlossen: 2026-10-14 (not planned)
- Labels: enhancement
- PRs: keine
- Befund: 1 (not planned, zurückgestellt)

**Laut Issue:** Interne Verwaltungsprozesse (Urlaubsanträge, Materialbestellungen) über Chat oder Maske anstoßen, strukturiert erfassen, weiterleiten und Status zurückmelden; zu bewerten war u. a. Eigenbau vs. Anbindung (Workflow-/BPMN-Engine), Abhängigkeit von #1747, Mitbestimmung. Sub-Issue von Epic #2305, nur zur Bewertung aufgenommen.

**Geliefert:** Nicht umgesetzt und nicht bewertet. Am 14.10.2026 mit dem Epic #2305 geschlossen: zurückgestellt, bis reale Anforderungen von Behörden vorliegen.

**Verifikation:** Kein Code.

**Themen:** produktvision, nutzerwünsche, zurückgestellt

---

<a id="issue-2307"></a>

## Issue #2307 — feat: Erstellte Dokumente elektronisch signieren (z. B. DocuSign)
- Geschlossen: 2026-10-14 (not planned)
- Labels: enhancement
- PRs: keine
- Befund: 1 (not planned, zurückgestellt)

**Laut Issue:** Mit OPAA erstellte Dokumente elektronisch signieren (z. B. DocuSign); zu bewerten waren Dokumentarten, Anbieter (eIDAS-QES, Behörden-PKI) und Datenabfluss an US-Anbieter. Sub-Issue von Epic #2305, nur zur Bewertung aufgenommen.

**Geliefert:** Nicht umgesetzt und nicht bewertet. Am 14.10.2026 mit dem Epic #2305 geschlossen: zurückgestellt, bis reale Anforderungen von Behörden vorliegen.

**Verifikation:** Kein Code.

**Themen:** produktvision, nutzerwünsche, zurückgestellt

---

<a id="issue-2308"></a>

## Issue #2308 — feat: Außenfrontend nach Art des OZG — Formulare nach außen geben, die Prozesse in OPAA anstoßen
- Geschlossen: 2026-10-14 (not planned)
- Labels: enhancement
- PRs: keine
- Befund: 1 (not planned, zurückgestellt)

**Laut Issue:** Bürger-/Außenfrontend nach Art des OZG: Formulare nach außen, eingehende Anträge stoßen Prozesse in OPAA an; zu bewerten waren Abgrenzung zu OZG-Plattformen (FIT-Connect, EfA) und öffentliche Angriffsfläche. Sub-Issue von Epic #2305, nur zur Bewertung aufgenommen.

**Geliefert:** Nicht umgesetzt und nicht bewertet. Am 14.10.2026 mit dem Epic #2305 geschlossen: zurückgestellt, bis reale Anforderungen von Behörden vorliegen.

**Verifikation:** Kein Code.

**Themen:** produktvision, nutzerwünsche, zurückgestellt

---

<a id="issue-2309"></a>

## Issue #2309 — feat: Kurznachrichten an andere Nutzer
- Geschlossen: 2026-10-14 (not planned)
- Labels: enhancement
- PRs: keine
- Befund: 1 (not planned, zurückgestellt)

**Laut Issue:** Kurznachrichten an andere Nutzer in OPAA; zu bewerten waren Anwendungsfall, Abgrenzung zu E-Mail/Teams/Matrix und Grundlage im Benachrichtigungssystem (#1297). Sub-Issue von Epic #2305, nur zur Bewertung aufgenommen.

**Geliefert:** Nicht umgesetzt und nicht bewertet. Am 14.10.2026 mit dem Epic #2305 geschlossen: zurückgestellt, bis reale Anforderungen von Behörden vorliegen.

**Verifikation:** Kein Code.

**Themen:** produktvision, nutzerwünsche, zurückgestellt

---

<a id="issue-2320"></a>

## Issue #2320 — fix(query): Antworten mit fremder Schrift (z. B. chinesische Zeichen) erkennen und einmal neu generieren
- Geschlossen: 2026-10-29 (not planned)
- Labels: bug, backend, size:M
- PRs: keine
- Befund: 1 (not planned)

**Laut Issue:** Deutsche Antworten enthielten gelegentlich chinesische Schriftzeichen (2 von 71 Antworten, `qwen2.5` 7B Q4 über Ollama, Temperatur 0,7). Gefordert war, Fremdschrift in Antworten zu erkennen und die Antwort einmal neu zu generieren.

**Geliefert:** Nicht umgesetzt. Am 29.10.2026 nach Rücksprache geschlossen: Tritt praktisch nur bei sehr kleinen, quantisierten Modellen auf; Abhilfe ist die Modellwahl, keine Absicherung im Antwortweg.

**Verifikation:** Keine Fremdschrift-Erkennung im Antwortweg. Die Sprachregel aus #1635/#1675 (Systemprompt plus `LANGUAGE_REMINDER`) bleibt die einzige Maßnahme.

**Themen:** antwortqualität, sprache, modellwahl, not-planned

---

<a id="issue-2329"></a>

## Issue #2329 — fix(indexing): PDF mit Text-Deckblatt und eingescannten Folgeseiten gilt als vollständig indexiert
- Geschlossen: 2026-10-29 (completed)
- Labels: bug, backend, size:M
- PRs: keine
- Befund: 2 (Teil geliefert, Rest verworfen)

**Laut Issue:** Die Scan-Erkennung griff nur bei PDFs ganz ohne Text. Ein PDF mit Text-Deckblatt und eingescannten Folgeseiten galt als indexiert (ein Mini-Chunk). Gefordert: seitenweise Erkennung – überwiegend Bildseiten wie Scan abweisen, bei einzelnen betroffenen Seiten sichtbarer Hinweis.

**Geliefert:** Kein verknüpfter PR. Zunächst am 08.10. zurückgestellt (Bindung an OCR #1062, weil Leer-/Grafikseiten fälschlich getroffen würden). Am 29.10. dann die Abweisung bei mehr als der Hälfte Bildseiten ohne Text mit PR #3351 umgesetzt (samt Handbuch). Der sichtbare Hinweis „N von M Seiten ohne Textebene“ (neues Feld, Spec, Dokumentansicht) wird nach Rücksprache nicht weiterverfolgt.

**Verifikation:** Abweisung über PR #3351 auf main (29.10.2026). Kein Seitenhinweis-Feld in Spec oder Oberfläche.

**Themen:** indexierung, pdf, scan-erkennung, ocr, teil-schnitt

---

<a id="issue-2358"></a>

## Issue #2358 — test(deploy): Installation nach Anleitung auf einem echten Kubernetes-Cluster erproben
- Geschlossen: 2026-10-10 (completed)
- Labels: enhancement, size:M
- PRs: keine
- Befund: 2 (Erprobung auf lokalem k3d statt echtem Cluster)

**Laut Issue:** Abschluss von Epic #2346: Installation, Betrieb und Upgrade nach Handbuch auf einem echten Cluster (nicht kind), z. B. k3s auf VM oder verwalteter Cluster mit Ingress, cert-manager, StorageClass, Objektspeicher; fachlicher Durchlauf mit OIDC und Konnektor, Knotenwechsel, `helm upgrade`, Sicherung/Wiederherstellung. Damit sollte auch das Kriterium „läuft nachweislich auf Kubernetes“ aus #1439 erfüllt werden.

**Geliefert:** Kein eigener PR. Auf Entscheidung des Maintainers lief die Erprobung **nicht auf einem echten Cluster**, sondern auf dessen lokalem k3d-Cluster (ein Server-, ein Agent-Knoten, arm64, Traefik, `local-path`, Erprobungsdatenbank des Charts). Protokolliert: Installation von `0.1.0-rc.1` aus GHCR, Wiederherstellung, Versionssprung rc.1 → rc.2 samt Rückweg. Lücken behoben in #2448, #2449, PR #2450, PR #2459; offen als #2458, #2460 (beide im Zeitraum geschlossen). Ein Test mit verwaltetem Cluster, cert-manager und Objektspeicher fand nicht statt.

**Verifikation:** Handbuch `docs/handbuch/` (Kubernetes-Kapitel) trägt die Nachträge aus PR #2450/#2459. Später kam die Mehrinstanz-Variante (ADR-0053) hinzu.

**Themen:** kubernetes, helm, erprobung, k3d, deployment, teil-schnitt

---

<a id="issue-2363"></a>

## Issue #2363 — eval: Golden-Fälle mit kleinteiligen, tabellenlastigen Dokumenten (Haushaltsplan-artig) für die Verwaltungsdomäne
- Geschlossen: 2026-10-14 (not planned)
- Labels: backend, size:M, evaluation
- PRs: keine
- Befund: 1 (not planned)

**Laut Issue:** Synthetische, frei verwendbare Dokumente (Haushaltsplan-artig, breite Tabellen, lange Abschnitte, Feldtabellen) mit Golden-Fällen in die Verwaltungsdomäne des Eval-Korpus aufnehmen, um die Wirkung von #2331, #2327/#2328 und #2334 messbar zu machen.

**Geliefert:** Nicht umgesetzt. Maintainer-Entscheidung 14.10.2026: Die Wirkung auf kleinteilige, tabellenlastige Dokumente wird inzwischen am realen FV-Testkorpus gemessen (Messungen in #2386 und #2507); synthetische Zusatzdokumente sind derzeit nicht nötig.

**Verifikation:** Keine neuen Golden-Fälle unter `eval/` dafür. Die Messung am FV-Korpus liegt außerhalb des Repositorys (Korpus-Repo).

**Themen:** evaluation, golden-dataset, tabellen, not-planned

---

<a id="issue-2365"></a>

## Issue #2365 — fix(indexing): Tabellen mit Kontext+Kopfzeile zwischen 7.800 und 8.000 Zeichen verlieren in Folgeteilen die Kopfzeile
- Geschlossen: 2026-10-14 (not planned)
- Labels: bug, backend, size:S
- PRs: keine
- Befund: 1 (not planned, Fehler besteht fort)

**Laut Issue:** Restbefund aus dem Review von PR #2362: `HeadingSectionSplitter#bodyBudget` fällt bei Kontext- plus Kopfzeile zwischen ca. 7.800 und 8.000 Zeichen auf 2.000 Zeichen Rumpf zurück; die harte Teilung trennt dann Datenzeilen von der Kopfzeile.

**Geliefert:** Nicht behoben. Maintainer-Entscheidung 14.10.2026: Der Fehler besteht auf main weiter, tritt aber nur auf, wenn Kontext und Kopfzeile fast die harte Grenze von 8.000 Zeichen erreichen; in den bekannten Korpora kommt das nicht vor, der Aufwand lohnt derzeit nicht.

**Verifikation:** `HeadingSectionSplitter` (`io.opaa.format.shared`) unverändert in diesem Punkt; bekannter, akzeptierter Fehler.

**Themen:** indexierung, chunking, tabellen, bekannter-fehler, not-planned

---

<a id="issue-2374"></a>

## Issue #2374 — eval: Kleinere Chunks mit Nachladen der Nachbar-Chunks bewerten
- Geschlossen: 2026-10-11 (completed)
- Labels: backend, size:M, evaluation
- PRs: #2505 (2026-10-11)
- Befund: 2 (nur Nachbar-Chunks, keine kleineren Chunks)

**Laut Issue:** Bewertung mit Messung, ob kleinere Chunks (1.500–2.000 statt 4.000 Zeichen Zielgröße) zusammen mit dem Nachladen der Nachbar-Chunks bessere Antworten liefern als die Dokument-Vervollständigung (`max-chunks-per-document` 2). Noch keine Umsetzungsentscheidung verlangt.

**Geliefert:** Gemessen wurde das Nachladen der Nachbarn (bester Fall ±1 mit mcpd 1: 36 statt 18 richtige von 60 Antworten). PR #2505 baut `NeighborChunkExpansion`: Nachbarn der Endauswahl (`chunk_index ± radius`) werden bis zu einem Zeichenbudget nur für den Antwortkontext geladen, nicht als Pipeline-Stufe. Die kleinere Chunk-Zielgröße wurde nicht umgesetzt; Folge-Issue #2502 setzte mcpd auf 1.

**Verifikation:** `io.opaa.retrieval.context.NeighborChunkExpansion` besteht. `HeadingSectionSplitter.DEFAULT_SOFT_CHUNK_CHAR_LIMIT` steht weiter auf 4.000.

**Themen:** retrieval, chunking, nachbar-chunks, evaluation, teil-schnitt

---

<a id="issue-2386"></a>

## Issue #2386 — feat(search): bge-m3 als Default-Embedding-Modell für deutschsprachige Installationen
- Geschlossen: 2026-10-14 (completed)
- Labels: enhancement, backend, size:M, evaluation
- PRs: #2636 (2026-10-14)
- Befund: 2 (Fehlzuordnung der Verknüpfung) und 3 (Umgebungsvorgabe später durch Installationswahl ersetzt)

**Laut Issue:** bge-m3 (1.024 Dimensionen, 8.192 Token Kontext) als Default-Embedding-Modell bzw. Empfehlung für deutschsprachige Installationen statt `nomic-embed-text`, begründet mit der Messung aus #2375 (Kernfakten unter Top 20 der Vektorliste 37 % → 85 %).

**Geliefert:** Der verknüpfte PR #2636 liefert eine gemeinsame Eval-Baseline nach dem Retrieval-Umbau (Fusion und Relevanzschnitt als Festpunkte) und hält ausdrücklich fest: „Die Harnesse pinnen weiterhin `nomic-embed-text:v1.5`. Ein Wechsel auf `bge-m3` ist nach Maintainer-Entscheidung nicht Teil dieses PRs.“ Die eigentliche Umstellung kam über den nicht verknüpften PR #2618 („bge-m3 als Vorgabe des Embedding-Modells“, Commit `4f1a99918`), vorbereitet durch #2599 (Neueinbettung bei Modellwechsel). Die Issue-Kommentare enthalten zudem die Messreihen K1–K5 zum Retrieval-Umbau.

**Verifikation:** Mit ADR-0060 (Commits `ca6cad4fb`, `c47859260`) ist das Embedding-Modell keine Umgebungsvorgabe mehr, sondern eine Zeile `embedding_model`, die in der Installation gewählt wird (`InstalledEmbeddingModel`). bge-m3 bleibt Vorgabe des Compose-Stacks (`docker-compose.yml` zieht und konfiguriert es) und Empfehlung in `deploy/helm/opaa/values.yaml`; die Eval-Harnesse nutzen weiterhin nomic.

**Themen:** embedding, bge-m3, evaluation, fehlzuordnung, adr-0060, ersetzt

---

<a id="pr-2409"></a>

## PR #2409 — docs(adr): SharePoint und CHANGE_FEED-Abschluss als Nachträge in ADR-0040
- Gemergt: 2026-10-09
- Labels: documentation
- Bezug: #2153, #2408
- Befund: 3 (Form ersetzt, Inhalt fortgeführt)

**Laut PR:** Reiner Doku-PR: zwei Nachträge am Ende von ADR-0040 – Zielbild, Belege, verworfene Alternativen und Maintainer-Entscheidungen zum SharePoint-Konnektor sowie der Schnitt des `CHANGE_FEED`-Abschlusses.

**Geliefert:** Gemergt wie beschrieben (09.10.2026).

**Verifikation:** Die Nachträge existieren nicht mehr: PR #2742 („ADR-0040 und ADR-0041 aufteilen und auf den gültigen Endstand neu fassen“) hat ADR-0040 auf Google Drive zurückgeführt; der Inhalt steht heute in ADR-0045 (gemeinsamer Datei-Abgleich, `CHANGE_FEED`) und ADR-0047 (SharePoint-Konnektor). Hintergrund ist die neue ADR-Regel „Endstand im Kerntext, keine Nachträge“.

**Themen:** adr, sharepoint, change-feed, dokumentation, ersetzt

---

<a id="issue-2413"></a>

## Issue #2413 — feat(indexing): Abschluss einer Abgleichsrunde und Änderungslauf mit mehr Protokollseiten als Anfragebudget
- Geschlossen: 2026-10-11 (completed)
- Labels: enhancement, backend, size:M
- PRs: #2466 (2026-10-11)
- Befund: 1 (nicht gebaut, als Einschränkung dokumentiert)

**Laut Issue:** Seit #2408 schließt eine Abgleichsrunde unter `AbsenceProof.CHANGE_FEED` mit dem Lesen des Änderungsprotokolls ab. Braucht das Protokoll mehr Anfragen, als ein Lauf Budget hat, kommt die Runde nie zum Ende (keine neuen Dateien im gelisteten Teil, kein Vollabgleich mehr). Gefordert war ein Abschluss über mehrere Läufe.

**Geliefert:** Nicht gebaut. Maintainer-Entscheidung 11.10.2026: Die Grenze kommt als bekannte Einschränkung ins Handbuch (SharePoint, Google Drive); der initiale Abgleich ist nicht betroffen, nur stark angesammelte Änderungen. Der verknüpfte PR #2466 ist reine Dokumentation zu drei Entscheidungen (Prüfliste für Postfächer #2170, kein Vorfallszugriff, Grenze des Änderungsprotokolls).

**Verifikation:** Kein Code für mehrteiliges Protokolllesen; Einschränkung im Handbuch beschrieben.

**Themen:** indexierung, abgleich, change-feed, anfragebudget, einschränkung, dokumentiert

---

<a id="pr-2444"></a>

## PR #2444 — feat(frontend): SharePoint-Formular für Anlage und Bearbeitung (S2)
- Gemergt: 2026-10-10
- Labels: documentation, enhancement, frontend
- Bezug: #2153, #2441
- Befund: 3 (Formular im Zeitraum ersetzt)

**Laut PR:** Schnitt S2 des SharePoint-Konnektors: `SharePointSourceForm` für Anlage und Bearbeitung einer SharePoint-Bibliothek in der Oberfläche (Zugangswahl, Site über Adresse oder Suche, Dokumentbibliotheken ankreuzen).

**Geliefert:** Gemergt wie beschrieben (10.10.2026).

**Verifikation:** Das Formular existiert nicht mehr: Mit Epic #3104 kam die Quelleinrichtung im Zeilengerüst nach UI-Lab-Variante F (PR #3123, 24.10.2026), PR #3138 entfernte den alten Pfad der Quellformulare (−8.119 Zeilen, darunter `SharePointSourceForm.tsx`, `DropboxSourceForm.tsx`). SharePoint wird heute über die Zeilen der Quelleinrichtung eingerichtet. Die Funktion „SharePoint in der Oberfläche einrichten“ steht, die gelieferte Form nicht.

**Themen:** sharepoint, frontend, formular, variante-f, ersetzt

---

<a id="issue-2494"></a>

## Issue #2494 — refactor(connections): Zugangswunsch vollständig entfernen
- Geschlossen: 2026-10-11 (completed)
- Labels: enhancement, backend, frontend, size:M
- PRs: #2497 (2026-10-11)
- Befund: 3 (Rückbau, ersatzlos)

**Laut Issue:** Der Zugangswunsch (Personen bitten um einen Zugang zu einer Quelle) entfällt vollständig.

**Geliefert:** Wie beschrieben, ersatzlos; Teil von Epic #2492 „Verbindungen vereinfachen“. Der Zugangswunsch war erst in diesem Zeitraum mit Epic #2147 entstanden und erscheint im Report deshalb nicht als Leistung.

**Verifikation:** Kein Zugangswunsch mehr in API und Oberfläche.

**Themen:** verbindungen, zugänge, rückbau

---

<a id="issue-2496"></a>

## Issue #2496 — feat(connections): Globaler Ausgangsproxy in der Verwaltung statt Proxy je Zugang und Bibliothek
- Geschlossen: 2026-10-11 (completed)
- Labels: enhancement, backend, frontend, size:L, security
- PRs: #2520 (2026-10-11)
- Befund: 3 (Proxy je Zugang und Bibliothek ersetzt)

**Laut Issue:** Ein globaler Ausgangsproxy in der Verwaltung ersetzt den Proxy je Zugang und Bibliothek.

**Geliefert:** Wie beschrieben (dazu PR #2503, ADR-0050); die Spalte `source_proxy` ist entfernt. Der Proxy je Zugang und Bibliothek ist damit zurückgebaut.

**Verifikation:** Ausgangsproxy als installationsweite Einstellung, kein Proxy je Bibliothek.

**Themen:** verbindungen, proxy, sicherheit, rückbau

---

<a id="issue-2507"></a>

## Issue #2507 — Chunk-Zielgröße 1.500/2.000/4.000 Zeichen am FV-Korpus vergleichen
- Geschlossen: 2026-10-13 (completed)
- Labels: enhancement, backend, size:M, evaluation
- PRs: keine (Lieferung über PR #2592, 2026-10-13, ohne „Closes“-Verknüpfung)
- Befund: 2 (Umfang verkleinert, Variante 1.500 entfallen)

**Laut Issue:** Den FV-Korpus mit den Zielgrößen 1.500, 2.000 und 4.000 Zeichen indexieren, die erwarteten Fundstellen je Zielgröße über den Fakttext neu zuordnen und die Arbeitsmenge v1 mit Nachbarn ±1 in je drei Läufen messen. Ergebnis sollte eine Empfehlung zur Zielgröße samt Kosten sein. Bei einer Änderung des Defaults sollten Konfiguration, Handbuch und Harness-Baseline nachgezogen werden.

**Geliefert:** Am 13.10.2026 hat der Maintainer den Umfang verkleinert: keine Optimierung über drei Größen (Gefahr der Überanpassung an 20 Fragen), sondern eine Bestätigungsmessung mit 4.000/±1, 2.000/±1 und 2.000/±2. Die Variante 1.500 entfiel. Dafür wurde die Zielgröße konfigurierbar gemacht (PR #2592). Messung mit je 60 Läufen: 4.000/±1 schnitt am besten ab (36/60 Läufe mit allen Kernfakten, 34 richtige Antworten), 2.000/±1 und 2.000/±2 schlechter (je 30/60, 27 richtig). Der Bericht liegt im Korpus-Repo unter `testlaeufe/2026-10-13-chunkgroesse/`. Empfehlung: Default 4.000 bleibt, kein Wechsel auf 2.000 mit #2386. Folge-Issues sind #2611 (stille Kürzung) und #2612 (Neuzerlegung über den Nachzug), beide im Zeitraum geliefert. Die Abnahmekriterien im Issue-Body blieben unangehakt.

**Verifikation:** `application.yml` führt `chunk-target-characters: ${OPAA_INDEXING_CHUNK_TARGET_CHARACTERS:4000}`. Es gibt keinen Messaufbau im Repo, die Messung liegt außerhalb (Korpus-Repo).

**Themen:** chunking, evaluation, fv-korpus, zielgroesse, umfang-gekuerzt

---

<a id="issue-2542"></a>

## Issue #2542 — feat: Beschaffungs- und Einführungsunterstützung für Behörden — Fakten, Update-Paket und Unterlagen, die mit dem Produkt mitziehen
- Geschlossen: 2026-10-24 (completed)
- Labels: documentation, enhancement, epic, size:L, security
- PRs: keine (Epic; Lieferung über die Teil-Epics #2543, #2552, #2560, #2570 und die Issues #2575, #3017, #3018, #3019, #3021)
- Befund: 2 (drei Punkte aus dem Epic ausgelagert, offen)

**Laut Issue:** Das Dach-Epic bündelt vier Epics und ein Einzel-Issue. OPAA soll einer Behörde für jede Phase (Entscheidung, Markterkundung, Pilot, Vergabe, Einführung mit Datenschutz, Mitbestimmung, Informationssicherheit, KI-Verordnung und Barrierefreiheit, Betrieb, Update) verlässliche Unterlagen liefern. Was aus dem Code folgt, wird generiert und von CI-Wächtern abgesichert. Zu jedem Release liegt eine aktuelle Fassung mit Delta vor.

**Geliefert:** Alle Teil-Epics und Sub-Issues sind geschlossen: Fakten-Manifest mit Wächtern, Datenflüssen, Konfigurationsmetadaten, Faktenblättern und Lizenzprüfung (#2543), Update-Paket je Release mit Delta, Betriebsfolgen, Attestierung, Offline-Bündel, Instanzabgleich und LTS-Delta (#2552), Behördenvorlagen unter `docs/behoerden/` (#2560), Verankerung in der Agentenorganisation (#2570), Release-Zyklus mit Supportlinien (ADR-0054, #2575) samt Wartungsbranches, CVE-Scan je Linie, Renovate auf den Linien und Roadmap-Entscheidung (#3017–#3021). Auf Entscheidung des Maintainers wurden drei Punkte als eigenständige Issues aus den Teil-Epics gelöst: #3006 (Barrierefreiheits-Audit einer veröffentlichten Version durch eine Prüfstelle), #3041 (Ablage auf openCode, Anfrage beim Betreiber offen) und #2825 (Lizenz von Liquibase 5).

**Verifikation:** #3006 und #3041 sind zum Stichtag offen, #2825 ist geschlossen. `docs/behoerden/`, `compliance/manifest/` und ADR-0052/ADR-0054 bestehen.

**Themen:** behoerden, compliance, update-paket, epic, teil-schnitt, barrierefreiheit, opencode

---

<a id="issue-2576"></a>

## Issue #2576 — feat(indexing): Wechsel des Embedding-Modells erkennen und Chunks ohne Datenbank-Reset neu einbetten
- Geschlossen: 2026-10-13 (completed)
- Labels: enhancement, backend, frontend, size:L
- PRs: #2599 (2026-10-13)
- Befund: 3 (Auslöser und Steuerung im Zeitraum ersetzt, Mechanik besteht)

**Laut Issue:** Ein Wechsel des Embedding-Modells hatte keinen vorgesehenen Weg. Bei anderer Vektorgröße verhinderte `PgVectorDimensionsGuard` den Start, sodass nur Hand-SQL oder ein Datenbank-Reset blieben. Bei gleicher Größe wurde der Wechsel nicht erkannt, und die Suche lieferte still unbrauchbare Treffer. Gefordert war Erkennung des Wechsels und Neueinbettung ohne Reset.

**Geliefert:** PR #2599: Das Modell bleibt in der Konfiguration. Die einzeilige Tabelle `embedding_index` hält Modell, Endpunkt und Vektorgröße. `EmbeddingIndexReconciler` ersetzt `PgVectorDimensionsGuard` und markiert bei Abweichung den Index als veraltet und legt eine Zielspalte `embedding_next` an. Die Schreibwege füllen die Zielspalte, `EmbeddingReindexService` bettet im Hintergrund neu ein, ohne die Quellen zu lesen. Die Oberfläche zeigt den Zustand und steuert die Neueinbettung. Nachbesserungen im Zeitraum: #2606/#2614 (PR #2613).

**Verifikation:** Die Mechanik besteht: `EmbeddingIndexReconciler`, `EmbeddingIndexState`, `embedding_next` (in `VectorIndexSchema`/`VectorTableCreator`) und `EmbeddingReindexService`. Der Auslöser „Modell in der Konfiguration, Abweichung beim Start erkannt“ ist ersetzt: Seit dem 25.10.2026 ist das Embedding-Modell eine Datenbankzeile aus der Installation (ADR-0060), und PR #3232 (Konzept #3231, Commit `b7abcbecc`, 26.10.2026; ADR-0061) baut den geführten Wechsel mit Generationen, Vorgängerstand und Läufen. Der Reconciler behandelt eine außerhalb dieses Wegs geänderte Modellzeile nur noch als Rückfall.

**Themen:** embedding, modellwechsel, neueinbettung, reindex, weiterentwickelt, ersetzt-teilweise

---

<a id="pr-2618"></a>

## PR #2618 — feat(search): bge-m3 als Vorgabe des Embedding-Modells
- Gemergt: 2026-10-13
- Labels: documentation, enhancement, backend, evaluation
- Bezug: #2386, #2599, #2607, #2611, #2617
- Befund: 3 (Konfigurationsvorgabe im Zeitraum ersetzt, teilweise)

**Laut PR:** Maintainer-Beschluss vom 13.10.2026: bge-m3 wird Vorgabe-Embedding-Modell. In `application.yml` wechseln `OPAA_OPENAI_EMBEDDING_MODEL` von `nomic-embed-text` auf `bge-m3` und `OPAA_PGVECTOR_DIMENSIONS` von 1536 auf 1024, der Embedding-Timeout steigt auf 180 s. Der Compose-Schritt `ollama-pull` legt bge-m3 mit `num_ctx`/`num_batch` 8192 an.

**Geliefert:** Wie beschrieben gemergt.

**Verifikation:** Die Konfigurationsvorgabe besteht zum Stichtag nicht mehr. Seit dem Installationsschritt für das Embedding-Modell (Commits `ca6cad4fb`, 25.10.2026, und `269a922ba`, 27.10.2026; ADR-0060, Entscheidung 10) ist das Embedding-Modell eine Zeile der Tabelle `embedding_model`, die bei der Installation gewählt wird. `OPAA_OPENAI_EMBEDDING_MODEL` und `OPAA_PGVECTOR_DIMENSIONS` liest nichts mehr (`deploy/helm/ci/static-check.sh` prüft das ausdrücklich), die Vektortabelle entsteht mit der ermittelten Dimension (`VectorTableCreator`). bge-m3 bleibt Vorgabe in `docker-compose.yml` (`ollama-pull` mit Modelfile), im Dev-Modus (`DevelopmentEmbeddingConfiguration.DEFAULT_MODEL`) und als Beispiel in der Modellerkennung der Oberfläche. Der Timeout von 180 s besteht.

**Themen:** embedding, bge-m3, vorgabe, installation, ersetzt-teilweise

---

<a id="issue-2761"></a>

## Issue #2761 — refactor(backend): Kleinbefunde aus den Reviews des Architektur-Epics beheben
- Geschlossen: 2026-10-14 (completed)
- Labels: backend, size:S
- PRs: #2776 (2026-10-14)
- Befund: 2 (zwei von sieben Punkten bewusst belassen)

**Laut Issue:** Das Issue sammelte sieben kleine Befunde aus den Reviews zu Epic #2678: eine doppelte Registrierung von `RateLimitProperties` samt ArchUnit-Regel, `ZoneId.systemDefault()` am `Clock` vorbei, ein fehlendes `400` an `listNotifications`, einen kopierten `enc:`-Marker, SMTP-Versand innerhalb einer Transaktion, Abbruchprüfungen und Nur-null-Defaults in `*Properties` sowie `FOREIGN_CONTEXT`. Maßgabe war netto weniger Code.

**Geliefert:** PR #2776 erledigt die Punkte 1, 3, 4 und 5. Punkt 2 ist nur teilweise erledigt (9 von 12 Stellen). Die Punkte 6 (Abbruchprüfungen und Null-Defaults in `*Properties`) und 7 (`FOREIGN_CONTEXT`) bleiben mit Begründung im PR offen. Neu ist die Regel `everyConfigurationPropertiesClassIsRegisteredOnce`.

**Verifikation:** Nicht weiter geprüft. Der Teil-Schnitt ist im PR dokumentiert.

**Themen:** architektur, review-nacharbeit, teil-schnitt, clock, properties

---

<a id="issue-2783"></a>

## Issue #2783 — @Value außerhalb von Properties-Klassen abbauen (Ratsche check_value_annotations.sh auf 0)
- Geschlossen: 2026-10-14 (completed)
- Labels: backend, size:S, architecture
- PRs: #2791 (2026-10-14)
- Befund: 2 (Ziel 0 verfehlt, Ratsche steht bei 4)

**Laut Issue:** Die Ratsche `check_value_annotations.sh` (ADR-0051, Entscheidung 2) sollte von 15 Vorkommen in 10 Dateien auf **Obergrenze 0** sinken. Konfiguration sollte durchgängig über `@ConfigurationProperties`-Records kommen.

**Geliefert:** PR #2791 bindet 11 der 15 `@Value` über vorhandene Properties-Typen und senkt die Ratsche auf **4**. Bewusst belassen sind `SecurityCorsConfig`, `VectorChunkStore`, `EmbeddingRateEstimator` und `WebConnectorConfiguration`, weil der Ersatz mehr Code bräuchte, als er spart. Das Gegenstück #2782 (`Instant.now()`) hat sein Ziel 0 dagegen erreicht.

**Verifikation:** `.github/scripts/check_value_annotations.sh` steht auf `LIMIT=4`, `check_instant_now.sh` auf `LIMIT=0`.

**Themen:** architektur, ratsche, konfiguration, adr-0051, teil-schnitt

---

<a id="issue-2819"></a>

## Issue #2819 — docs(agents): Wöchentliche Compliance-Drift-Routine nach Vorbild ADR-0051, Entscheidung 7
- Geschlossen: 2026-10-15 (completed)
- Labels: documentation, size:S, security
- PRs: keine
- Befund: 2 (Lieferung außerhalb des Repositorys)

**Laut Issue:** Schicht 3 der Drift-Erkennung für die Behördenunterlagen: eine wöchentliche, nur lesende Routine, die Abweichungen zwischen Code und Fakten-Manifest meldet. Erfasst werden sollten Verhaltensänderungen ohne Schemaänderung und PRs mit Datenwirkung ohne Kennzeichnung. Der Maintainer sollte einen kurzen Bericht erhalten.

**Geliefert:** Ohne PR geschlossen. Laut Abschlusskommentar ist die Routine als geplante Claude-Routine eingerichtet (sonntags 08:37 Uhr, nach der Architektur-Prüfroutine), nur lesend und mit Bericht per Push-Benachrichtigung. Beschrieben ist sie in `docs/features/behoerdenunterlagen.md` (PR #2818, Abschnitt „Automatisierung und Drift-Erkennung“, Schicht 3).

**Verifikation:** Die Routine selbst liegt nicht im Repository und lässt sich aus dem Code nicht nachprüfen. Belegt ist nur die Beschreibung in der Spezifikation.

**Themen:** compliance, drift-erkennung, routine, ohne-pr, behoerdenunterlagen

---

<a id="issue-2825"></a>

## Issue #2825 — build(deps): Lizenz von Liquibase 5 (FSL-1.1-ALv2) klären – Pin auf 4.33.x oder Alternative
- Geschlossen: 2026-10-24 (completed)
- Labels: backend, size:M, dependencies
- PRs: #3095 (2026-10-24)
- Befund: 2 (Werkzeugwechsel ohne Abwärtskompatibilität statt Pin)

**Laut Issue:** Liquibase 5.0.3 steht unter FSL-1.1-ALv2 und verträgt sich nicht ohne Weiteres mit der AGPL-3.0. Zu prüfen war, ob ein Pin auf 4.33.x (letzte Apache-2.0-Version) trägt, sonst eine Alternative. Verlangt war eine begründete Entscheidung, die umgesetzt oder vorbereitet ist.

**Geliefert:** Der Pin auf 4.33.0 trug nicht (erste Fassung des PRs). PR #3095 stellt deshalb die gesamte Schemamigration auf **Flyway Community** um (ADR-0056). Die Liquibase-Historie ist je Modul zu einer SQL-Baseline zusammengefasst. **Eine Abwärtskompatibilität gibt es nicht**: Bestehende Installationen und die Demo werden neu aufgesetzt (Upgrade-Hinweis `0.1.0-rc.5.md`). 23 Delta-Tests auf Liquibase-Spezifika entfallen, 19 Tests sind auf den Endstand umgestellt. Die Wächter heißen jetzt `Migration*Test`, `backend/docs/liquibase.md` heißt jetzt `backend/docs/migrationen.md`. Fortgeschrieben wurden ADR-0002, 0034, 0041, 0042, 0045, 0051, 0052 und 0053.

**Verifikation:** Commit `d642b3af4`. `backend/docs/migrationen.md` besteht, `backend/docs/liquibase.md` nicht mehr. Die Folgekorrektur `cef1169ef` lässt die Zwei-Instanzen-Suite frühestens ab der Flyway-Version starten (#3183). Damit sind Liquibase-gebundene Lieferungen desselben Zeitraums ersetzt: #2944 (Expand/Contract-Wächter, `vector_store` über Liquibase), #2766 (`backend/docs/liquibase.md`) und der Liquibase-Teil der Kommentarregel aus #2759.

**Themen:** datenbank, migration, flyway, liquibase, lizenz, bruch, ersetzung

---

<a id="issue-2899"></a>

## Issue #2899 — fix(backend): Start-Recovery verfehlt junge Uploads und bricht eigene Indexierungsläufe ab
- Geschlossen: 2026-10-17 (completed)
- Labels: bug, backend, size:S
- PRs: #2916 (2026-10-17)
- Befund: 3 (Mechanismus drei Tage später ersetzt)

**Laut Issue:** Zwei Start-Recoveries entschieden über eine Zeitschwelle bzw. ganz ohne Zeitbedingung, ob etwas „vom alten Prozess übrig“ ist. Folgen: Ein junger Upload blieb nach einem Neustart für immer `PENDING`, und eigene Indexierungsläufe wurden abgebrochen.

**Geliefert:** PR #2916 führt die Bean `io.opaa.common.ProcessStart` (JVM-Start) ein. Die Recoveries von Upload und Indexierung messen danach am Prozessstart statt an einem Alter.

**Verifikation:** `ProcessStart` existiert zum Stichtag nicht mehr. #2948 (PR #2961, 20.10.2026, Commit `f791c4ba0`) hat den Ansatz durch Laufbesitz und Recovery über Heartbeats ersetzt (`owner_instance_id`, `IndexingJobRecoveryScheduler`, `InstanceRegistry#isAlive`). Begründung dort: Im Mehrinstanzbetrieb würde eine neu startende Instanz über den Prozessstart gesunde Läufe anderer Instanzen abbrechen. Die Fehlerbilder aus #2899 bleiben behoben, nur auf anderem Weg. `UploadPendingRecoveryRunner` besteht weiter.

**Themen:** recovery, upload, indexierung, prozessstart, heartbeat, ersetzung, mehrinstanz

---

<a id="issue-2904"></a>

## Issue #2904 — fix(mail): Mailversand blockiert den Scheduler-Thread ohne Zeitbudget
- Geschlossen: 2026-10-21 (completed)
- Labels: bug, backend, size:S
- PRs: keine
- Befund: 2 (ohne eigenen PR, über die Mail-Outbox gelöst)

**Laut Issue:** Drei Pfade (`LibraryExternalAccessReminderService`, `InactivityLockStep`, `ExternalAccessTokenExpiryReminderStep`) versandten Mails synchron im Default-Scheduler-Thread und ohne Zeitbudget. Bei einem nicht erreichbaren SMTP-Server konnte das andere geplante Läufe für 30–60 Minuten blockieren. Vorschlag: Versand über `MailDispatchExecutor` und ein `MaintenanceBudget`.

**Geliefert:** Ohne eigenen PR geschlossen. Laut Abschlusskommentar ist das Problem durch PR #2955 erledigt (Mail-Outbox, Teil von #2949, Mehrinstanzbetrieb). Die drei Pfade schreiben ihre Mails jetzt per `MailService#enqueue` in die Outbox, und `MailOutboxWorker` stellt sie auf eigenem Scheduler mit Wiederholungen zu. Das ist ein anderer Weg als vorgeschlagen; das Abnahmeziel ist erreicht. Synchron versendet werden nur noch die vom Admin ausgelösten Link-Mails.

**Verifikation:** Laut Kommentar auf main `3f410abc2` geprüft. Stichprobe nicht vertieft.

**Themen:** mail, scheduler, outbox, ohne-pr, mehrinstanz

---

<a id="issue-2944"></a>

## Issue #2944 — feat(migration): Migrationen ohne Bruch beim Rolling Update — Expand/Contract, Wächter, eigener Migrationsschritt
- Geschlossen: 2026-10-20 (completed)
- Labels: enhancement, backend, size:L
- PRs: #2954 (2026-10-20)
- Befund: 3 (Liquibase-Umsetzung im Zeitraum auf Flyway umgebaut)

**Laut Issue:** Bauteil B8 aus Epic #1292. Verlangt waren eine Expand/Contract-Regel über zwei Releases mit maschinellem Wächter und ein eigener Migrationsschritt. Bislang brach jede noch laufende alte Version, sobald eine neue migrierte.

**Geliefert:** PR #2954 liefert die Regel mit Wächter (`RollingUpdateGuardTest`) und ein Verzeichnis persistierter Enums. Dazu kommen `lock_timeout` je Changeset, der Startmodus `OPAA_MODE=migrate`, `vector_store` aus Liquibase statt aus Spring AI und Start-Seeds unter Advisory-Lock. Beschrieben ist das in `backend/docs/liquibase.md`. Die Contract-Markierung lief über Liquibase-Labels.

**Verifikation:** Vier Tage später hat PR #3095 (#2825, 24.10.2026) Liquibase durch Flyway ersetzt. Die Regel lebt in `backend/docs/migrationen.md` fort („Rolling Update: Expand/Contract über zwei Releases“). Contract-Schritte sind jetzt am Dateipräfix `contract_` erkennbar, weil Flyway keine Labels kennt. `RollingUpdateGuardTest` und `MigrationLockTimeoutTest` prüfen auf SQL-Skripten, `vector_store` kommt aus der Flyway-Baseline. Inhalt und Zweck stehen also, das Werkzeug und die Markierung sind ersetzt.

**Themen:** migration, rolling-update, expand-contract, liquibase, flyway, mehrinstanz, ersetzung

---

<a id="issue-2992"></a>

## Issue #2992 — feat(chat): Selbstauskunft-Exporteure für Chats, Prompts, Uploads-Verzeichnis und Diagnose
- Geschlossen: 2026-10-23 (completed)
- Labels: enhancement, backend, size:L, security, data-impact
- PRs: #3031 (2026-10-23)
- Befund: 2 (Prompts nicht als eigene Kategorie)

**Laut Issue:** Exporteure der Module assistant, library und workspace für die Selbstauskunft (Epic #2986). Gefordert waren Chats mit Nachrichten und Fundstellen nur mit Titel und Ort, **eigene Prompts und Prompt-Bibliotheken** (Asset-Daten nach Manifest), private Bibliotheken und Uploads nur als Verzeichnis sowie Diagnosefreigaben und Diagnoseprotokoll.

**Geliefert:** PR #3031 liefert die Kategorien `chats`, `library-settings`, `documents`, `indexing-runs`, `diagnostic-impersonation` und `diagnostic-context-log`. Eine eigene Prompt-Kategorie gibt es nicht. Prompt-Bibliotheken erscheinen nur als Assets mit Art und Name in der Kategorie `rights` aus #2991 (PR #3029). Prompt-Texte sind laut Manifest ausdrücklich ausgeschlossen: „Sachdaten ohne Personenbezug“. In Chats steht nur der Titel des verwendeten Prompts. Abweichend vom Issue enthält `indexing-runs` nur die Neueinbettung, weil `indexing_jobs` keinen Personenbezug führt.

**Verifikation:** `backend/src/main/resources/compliance/manifest/rights.yaml` (Z. 25–26) hält den Ausschluss der Prompt-Texte fest. Einen Exporteur mit „Prompt“ im Namen gibt es nicht.

**Themen:** selbstauskunft, dsgvo, export, prompts, zuschnitt

---

<a id="issue-2997"></a>

## Issue #2997 — feat(chat): Chats gesperrter Konten nach einer Frist löschen
- Geschlossen: 2026-10-23 (completed)
- Labels: enhancement, backend, size:M, data-impact
- PRs: #3027 (2026-10-23)
- Befund: 2 (auf Verwaltungssperre lokaler Konten eingegrenzt)

**Laut Issue:** Nach einer Frist ab der Sperre eines Kontos sollten dessen Chats automatisch gelöscht werden, in allen Spaces und genau einmal über alle Instanzen. Der Fristbeginn sollte aus der Zustandshistorie des Kontos kommen. Offen war unter anderem, ob Konten eines Identitätsanbieters einbezogen sind (Punkt 5).

**Geliefert:** PR #3027 baut `LockedAccountChatCleanupService` mit nächtlichem Lauf unter ShedLock nach den Entscheidungen des Maintainers. Auslöser ist aber **allein** `local_credentials.locked_reason = 'ADMIN'`, also die Sperre eines lokalen Kontos durch die Verwaltung. Der Fristbeginn ist `GREATEST(locked_at, counting_since)`. Die Zustandshistorie wird bewusst nicht gelesen (ADR-0036, Entscheidung 8: einziger Leser ist die Stichtagsauskunft). Nicht erfasst sind damit Sperren wegen Inaktivität, Verzeichnissperren und Anbieterkonten.

**Verifikation:** `LockedAccountChatCleanupService` besteht.

**Themen:** chat, loeschfrist, gesperrte-konten, dsgvo, zuschnitt

---

<a id="issue-3110"></a>

## Issue #3110 — feat(indexing): OneDrive-Konnektor für private Microsoft-Konten (driveType personal)
- Geschlossen: 2026-10-29 (not planned)
- Labels: enhancement, backend, size:S, auth
- PRs: keine
- Befund: 1 (Maintainer-Entscheidung gegen private Konten)

**Laut Issue:** Hielt fest, ob und wie der OneDrive-Konnektor aus #2467 neben OneDrive for Business (`driveType = business`) auch private Microsoft-Konten (`driveType = personal`) zulässt. Vorher zu klären waren Datenschutz und Personalrat, die fehlende Kontrolle durch die Verwaltung (kein Entra, kein Admin-Widerruf), die Anmeldung über `common`/`consumers` ohne Mandantenbindung und Graph-Unterschiede. Zum Umfang gehörten `personal` in der Laufwerksregel des `DriveFileStore`, Testfälle im `FakeGraphServer` sowie das Nachziehen von Handbuch und ADR-0057.

**Geliefert:** Nicht umgesetzt. Am 29.10.2026 entschied der Maintainer „not planned“: OneDrive bleibt auf Business-Konten beschränkt, wie in M2 zu #2467 festgelegt. Gebaut wurde im Zeitraum nur der Business-Weg: PR #3130 öffnete den Drive-Store für weitere Laufwerksarten, PR #3148 liest OneDrive for Business als verbundenes Konto einer Person.

**Verifikation:** `DriveFileStore` besteht (`git log -S driveType`: #3130, #3148, #3398). ADR-0058 (`0058-onedrive-konnektor.md`) beschreibt den Konnektor. Einen Zweig für `driveType = personal` gibt es nicht.

**Themen:** konnektor, onedrive, microsoft-graph, private-konten, datenschutz, zurückgestellt

---

<a id="issue-3119"></a>

## Issue #3119 — refactor(audit): Ereignistypen ohne Nachweiswert nicht mehr schreiben
- Geschlossen: 2026-10-24 (not planned)
- Labels: enhancement, backend, size:S
- PRs: keine
- Befund: 1 (Prüfung hat die Streichung nicht bestätigt)

**Laut Issue:** Nach einer Inventur der 144 Ereignistypen sollten `EMBEDDING_REINDEX_COMPLETED` und `BRANDING_SETTINGS_CHANGED` nicht mehr geschrieben werden; alte Einträge sollten lesbar bleiben. Zusätzlich sollte die Javadoc von `DocumentMetadataCorrectionService` das Protokoll nicht mehr als Quelle für die Wiederherstellung nennen. Teil von #1296.

**Geliefert:** Ohne Codeänderung geschlossen. Die Prüfung im Code bestätigte keinen der Kandidaten, alle 144 Typen bleiben. `BRANDING_SETTINGS_CHANGED` bleibt, weil das Branding seit #3011 den nachweispflichtigen Hinweistext zur KI-Nutzung (Art. 50 KI-VO) trägt. `EMBEDDING_REINDEX_COMPLETED` bleibt, weil es den einzigen dauerhaften Beleg dafür liefert, ab wann die Antworten auf neuen Vektoren beruhen. Auch der Wiederherstellungszweck von `DOCUMENT_METADATA_CHANGED` bleibt („Abgleich nach Restore“, spezifiziert, getestet und im Runbook beschrieben). Das Ergebnis der Inventur steht im ADR aus #3122 (ADR-0059).

**Verifikation:** `docs/decisions/0059-ereignisvertrag-aufbewahrung-und-integritaet-des-revisionsprotokolls.md` besteht. Der Ereigniskatalog (`AuditEventCatalog`, #3118) führt die Typen weiter.

**Themen:** audit, revisionsprotokoll, ereigniskatalog, ki-verordnung, nicht-umgesetzt

---

<a id="issue-3189"></a>

## Issue #3189 — feat(setup): Installationsmodus, Token, Installations-API und Antwortdatei
- Geschlossen: 2026-10-25 (completed)
- Labels: enhancement, backend, setup, size:L, security
- PRs: #3199 (2026-10-25)
- Befund: 3 (Übergangsweg im Zeitraum zurückgebaut)

**Laut Issue:** Teilschritt 1 von Epic #3160 (ADR-0060). Bei leerer Installation startet die Anwendung im Installationsmodus. Bis zum Abschluss sind nur Installations-API, öffentliche Konfiguration und Health erreichbar, alles andere antwortet `503`. Dazu ein Installations-Token (aus der Umgebung oder beim Start erzeugt) und eine Antwortdatei für die unbeaufsichtigte Installation.

**Geliefert:** PR #3199 brachte `installation_state`, den `InstallationGateFilter` (`503 INSTALLATION_PENDING`), Token, Installations-API und Antwortdatei mit den Schritten erstes Konto, Anmeldeverfahren und Chat-Modell. Bewusst behielt der PR zusätzlich einen **Übergang**: Im Modus `oidc` ohne Antwortdatei schloss ein gesetztes `OPAA_INITIAL_ADMIN_EMAIL` eine leere Installation aus den Startwerten ab. Abgeschlossene Installationen führten weiter `LocalAdminSeeder`, `OidcProviderSeeder` und `LlmModelSeeder` aus, und `OidcAddressPolicy` ließ die Bootstrap-Adressen ohne Allowlist-Eintrag zu.

**Verifikation:** Kern besteht: `InstallationGateFilter` und der Installationsassistent (#3190, `e2e/tests/installation-assistant.spec.ts`). Den Übergang gibt es zum Stichtag nicht mehr. Commit `1d8c03359` („Übergang mit Startwerten und die drei Seeder entfernen“, Teil von PR #3202 zu #3194, 26.10.2026) hat Seeder, Marker und Übergangsweg gelöscht. `git grep LocalAdminSeeder|OidcProviderSeeder|LlmModelSeeder` findet nichts mehr. `OPAA_INITIAL_ADMIN_EMAIL` steht nur noch in `RetiredSettingVariables`, als entfallene Variable mit Hinweis. Das eigens dafür angelegte Issue #3200 wurde als not planned geschlossen, weil der Rückbau in #3194 aufging.

**Themen:** installation, einrichtungsassistent, antwortdatei, übergang, rückbau, adr-0060

---

<a id="issue-3191"></a>

## Issue #3191 — feat(setup): Embedding-Modell im Assistenten, Vektortabelle beim Abschluss
- Geschlossen: 2026-10-25 (completed)
- Labels: enhancement, backend, setup, size:M
- PRs: #3206 (2026-10-25)
- Befund: 3 (Übernahme-Weg zurückgebaut, „nicht wechselbar“ durch #3214 ersetzt; Regression #3226)

**Laut Issue:** Teilschritt 3 von #3160 (ADR-0060, Entscheidung 10). Das Embedding-Modell wird Schritt des Assistenten bzw. der Antwortdatei, mit Probeeinbettung. `vector_store` entsteht erst beim Abschluss, mit der dort ermittelten Dimension; danach ist das Modell über die Oberfläche nicht wechselbar. Bestehende Installationen sollten unverändert lauffähig bleiben. Das Kriterium „Startwert-Variablen des Embedding-Modells entfernen“ blieb beim Schließen offen.

**Geliefert:** PR #3206 brachte die Tabelle `embedding_model`, `InstalledEmbeddingModel` als `EmbeddingModel`-Bean (Spring-AI-Autokonfiguration abgeschaltet) und die Vektortabelle mit ermittelter Dimension. Für bestehende Installationen kam ein Übernahme-Weg hinzu (`LegacyEmbeddingConfiguration`, `opaa.embedding.takeover.*`, `OPAA_OPENAI_EMBEDDING_*`).

**Verifikation:** `InstalledEmbeddingModel` und `embedding_model` bestehen. Im selben Zeitraum gab es drei Änderungen:
1. Der Übernahme-Weg wurde mit Commit `c47859260` („Embedding-Übernahme entfernen“, PR #3202 zu #3194) gelöscht; `LegacyEmbeddingConfiguration` gibt es nicht mehr.
2. „Danach nicht wechselbar“ gilt nicht mehr: #3214 (PR #3232, ADR-0061, 27.10.2026) brachte den geführten Modellwechsel mit Neueinbettung.
3. #3206 hatte eine Regression: `InstalledEmbeddingModel` bettete den Kontextpräfix nicht mehr ein, und die nächtliche Retrieval-Regression schlug an (#3226). PR #3233 behob das.

`RetiredSettingVariables` beschreibt das Modell noch als „fixed afterwards by its vector size“; das ist seit #3214 überholt.

**Themen:** installation, embedding, vektortabelle, übergang, rückbau, regression, adr-0060

---

<a id="issue-3200"></a>

## Issue #3200 — refactor(setup): Übergang mit Startwerten und die drei Seeder entfernen
- Geschlossen: 2026-10-25 (not planned)
- Labels: enhancement, backend, setup, size:M
- PRs: keine
- Befund: 1 (in #3194 aufgegangen)

**Laut Issue:** Folge aus #3194. Folgendes sollte entfernt werden: der Übergangsweg `OPAA_INITIAL_ADMIN_*` in `InstallationStartup`, die Seeder `LocalAdminSeeder`, `OidcProviderSeeder` und `LlmModelSeeder` samt Markern, die Variablen `OPAA_OIDC_*`/`OPAA_OPENAI_CHAT_*` aus `application.yml` und die Ausnahme für Bootstrap-Adressen in `OidcAddressPolicy`. Profil `dev` und Testkontexte sollten über eine eingebaute Antwortdatei installieren.

**Geliefert:** Als eigenes Issue nicht umgesetzt. Laut Kommentar (25.10.2026) gehört der Rückbau laut Koordination zu #3194 und wurde dort gebaut. Inhaltlich ist er geliefert, mit Commit `1d8c03359` in PR #3202.

**Verifikation:** Seeder und Übergangsweg fehlen im Code (`git grep` ohne Treffer). Entfallene Variablen meldet `RetiredSettingVariables` beim Start.

**Themen:** installation, seeder, übergang, rückbau, duplikat-zuschnitt

---

<a id="issue-3260"></a>

## Issue #3260 — feat: „Dokument öffnen“ springt an die Fundstelle und markiert sie
- Geschlossen: 2026-10-30 (completed)
- Labels: enhancement, epic, backend, frontend
- PRs: keine (Epic, Lieferung über Sub-Issues)
- Befund: 2 (Teil-Schnitt: formatspezifische Ansichten verworfen)

**Laut Issue:** „Dokument öffnen“ im Belegfenster sollte für **jedes aufgenommene Format** direkt an die zitierte Stelle springen und sie markieren, mit festgelegtem Rückfall. Grundlage sollte ein maschinenlesbarer Fundstellen-Anker je Chunk sein. Vorgesehen waren Stufen: Anker, Textfassung als Rückfall, PDF-Viewer, Markdown/TXT/Quelle, dazu eigene Ansichten für Tabellen/Mail, DOCX und Folien. Das Epic wurde aus dem UI-Review-Epic #3269 herausgelöst und war zunächst geparkt.

**Geliefert:** Fundstellen-Anker je Chunk (#3315; Kern in PR #3322, Bereinigung in PR #3324, ADR-0062), eigener pdf.js-Viewer mit Sprung und Markierung (#3317, PR #3361), Sprung in Markdown/TXT und Textfragment für Confluence (#3318, PR #3373), Textfassung an der Fundstelle für alle übrigen Formate samt „Original herunterladen“ (#3316 und #3445, PR #3458). **Nicht gebaut:** die originalnahen Ansichten für Tabellen/Mail (#3319), DOCX (#3320) und Folien (#3321). Sie wurden als `not planned` geschlossen, weil die Textfassung diese Formate abdeckt. Folge-Issues gibt es laut Abschlusskommentar keine.

**Verifikation:** `docs/decisions/0062-fundstellen-anker-je-chunk.md` besteht. Der Endpunkt `GET /api/v1/documents/{documentId}/text` steht in `opaa-api/src/main/openapi/search.yaml`. DOCX, XLSX, PPTX und Mails öffnen nur in der Textfassung, nicht originalnah.

**Themen:** fundstelle, belegfenster, pdf-viewer, textfassung, anker, teil-schnitt

---

<a id="issue-3263"></a>

## Issue #3263 — feat(space): Space-Übersicht schlanker – Kachel ohne Rolle und Mitglieder, Seitenkopf wie im Katalog
- Geschlossen: 2026-10-28 (completed)
- Labels: enhancement, frontend, size:S
- PRs: #3274 (2026-10-28)
- Befund: 3 (Rückbau von Lieferungen des Vorzeitraums #1914, #1970)

**Laut Issue:** Die Space-Kachel sollte nur noch Titel, Beschreibung und „n Chats“ zeigen. Rollen-Chip und Mitgliederzeile sollten entfallen. Der Seitenkopf sollte wie im Katalog aussehen („Spaces · n Einträge“), ohne Untertitel. Das Issue nennt sich ausdrücklich „teilweise Rücknahme von #1914 (Rolle als Badge) und #1970 (Mitgliederangabe)“.

**Geliefert:** PR #3274 wie beschrieben. Rollen-Chip und Mitgliederangabe entfallen, die Zustände „Archiviert“ und „Nachfolge offen“ bleiben. Den Übersichtskopf setzt jetzt die gemeinsame Komponente `OverviewPage` für jede Übersicht gleich.

**Verifikation:** Die Rollenanzeige aus #1914 und die Mitgliederangabe aus #1970 (beide im Zeitraum bis 30.09.2026 ohne Befund geliefert) gibt es auf der Space-Kachel nicht mehr. Rolle und Mitglieder stehen nur noch in den Space-Einstellungen.

**Themen:** space-übersicht, ui-review, entschlackung, rückbau-vorzeitraum

---

<a id="issue-3266"></a>

## Issue #3266 — feat(frontend): UI-Prototyp Seitenleiste – Spaces als Liste statt Dropdown, zuklappbare Bereiche
- Geschlossen: 2026-10-28 (completed)
- Labels: enhancement, frontend, size:L
- PRs: #3293 (2026-10-28)
- Befund: 2 (Prototyp nicht gemergt, Lieferung als Leitlinie)

**Laut Issue:** Ein UI-Prototyp sollte zeigen, ob Spaces als Liste (statt Dropdown) und zuklappbare Bereiche für Spaces, Chats und Angeheftet in der Seitenleiste besser funktionieren. Vorbilder waren Claude (Projekte) und Mattermost.

**Geliefert:** Der Prototyp entstand als UI-Lab-Variante im PR #3280 (`feature/3266_seitenleiste-prototyp`). Dieser PR wurde **ungemergt geschlossen**. Gemergt hat PR #3293 nur die Entscheidung („B mit Feinschliff“) als neue Gestaltungsleitlinie 5.13 „Seitenleiste des Space“ in `docs/design/guidelines.md`. Den Umbau im Produktcode lieferte erst #3292 (PR #3296).

**Verifikation:** `docs/design/guidelines.md` enthält Abschnitt 5.13. Im Produktcode gibt es keinen Variantenschalter. Die Seitenleiste besteht aus `frontend/src/app/shell/Sidebar.tsx` und `SidebarSpaceList.tsx`.

**Themen:** seitenleiste, prototyp, ui-lab, leitlinie, ui-review

---

<a id="issue-3268"></a>

## Issue #3268 — [BUG] Chatsuche im privaten Bereich liefert sporadisch 502
- Geschlossen: 2026-10-28 (not planned)
- Labels: bug, backend, size:S
- PRs: keine

**Laut Issue:** Im UI-Review vom 28.10.2026 lieferte „Chats durchsuchen“ im privaten Bereich der Demo-Instanz einmal einen 502. Vermutet wurde Last durch eine parallele Chat-Anlage (Space mit ~100 Chats). Reproduziert war der Fehler nicht.

**Geliefert:** Nicht umgesetzt, weil kein Fehler vorlag. Die Log-Auswertung (Kommentar vom 28.10.2026) zeigt: Der einzige API-502 des Tages (`GET …/chats/archived`) fiel genau in die Lücke eines geplanten Backend-Neustarts. Laut Instanzregister stoppte die alte Instanz um 10:27:59, die neue startete um 10:28:55. Die Chatsuche selbst ist nicht fehlerhaft.

**Verifikation:** Entfällt, keine Codeänderung.

**Themen:** demo-instanz, 502, neustart, kein-fehler

---

<a id="issue-3279"></a>

## Issue #3279 — Zitierposition und Wirkstelle „Beleg-Anzeige“ der Metadaten: beibehalten oder entfernen
- Geschlossen: 2026-10-28 (completed)
- Labels: enhancement, backend, frontend, size:S
- PRs: #3301 (2026-10-28)
- Befund: 3 (Funktion des Vorzeitraums entfernt)

**Laut Issue:** Seit #3259 (PR #3273) zeigt das Belegfenster keine Metadaten mehr. Damit wirkten die Wirkstelle „Beleg-Anzeige“ und die Zitierposition (`citationPosition`) eines Bibliotheksfelds nur noch in der Ausgabe für externe KI-Werkzeuge (Suchschnittstelle, MCP). Zu entscheiden war, ob beides bleibt oder entfällt.

**Geliefert:** Entscheidung für Weg B. PR #3301 entfernt `citationPosition` vollständig aus Spezifikation (`library-metadata.yaml`), Backend und Oberfläche. Die Ausgabe an externe Werkzeuge enthält jetzt jedes gesetzte Bibliotheksfeld in `sortOrder`. Die Änderung hat Datenwirkung (Z7).

**Verifikation:** `citationPosition` kommt in `backend/src/main`, `opaa-api/src` und `frontend/src` nicht mehr vor. Eingeführt wurde die Zitierposition mit #1326 (Commit `b2647de17`), entfernt mit Commit `113af8490`.

**Themen:** metadaten, zitierposition, belegfenster, mcp, rückbau-vorzeitraum

---

<a id="issue-3292"></a>

## Issue #3292 — feat(frontend): Seitenleiste nach Variante B umsetzen – Spaces als Liste, Gruppen mit Zählern, Rail-Eintrag „Chat“
- Geschlossen: 2026-10-28 (completed)
- Labels: enhancement, frontend, size:L
- PRs: #3296 (2026-10-28)
- Befund: 3 (ersetzt den Space-Wechsler des Vorzeitraums, #1912)

**Laut Issue:** Die Desktop-Seitenleiste im Space sollte nach Variante B des Prototyps umgebaut werden: Spaces als feste Liste oben statt Dropdown, darunter die Chats als einzige Scrollfläche, zuklappbare Gruppen mit Zählern, einheitlich Plus und Lupe. Der Rail-Eintrag „Spaces“ sollte „Chat“ heißen.

**Geliefert:** PR #3296 wie beschrieben, als einzige Ausführung ohne Variantenschalter. Ersetzt wird damit das Dropdown mit den zuletzt genutzten Spaces aus #1912 (Vorzeitraum). Gleich danach feingeschliffen: #3306 (Zahnrad je Space-Zeile statt Fußeintrag „Einstellungen“), #3307 (Pinnadel nur im Kopf) und #3300 (Fokus in der Schublade).

**Verifikation:** `frontend/src/app/shell/SidebarSpaceList.tsx` besteht. Einen Dropdown-Space-Wechsler gibt es nicht mehr.

**Themen:** seitenleiste, navigation, spaces, ui-review, rückbau-vorzeitraum

---

<a id="issue-3319"></a>

## Issue #3319 — feat(frontend): Tabellen- und Mail-Ansicht mit Sprung an die Fundstelle (XLSX/ODS, EML/MSG)
- Geschlossen: 2026-10-30 (not planned)
- Labels: enhancement, backend, frontend, size:L, parked
- PRs: keine
- Befund: 1 (verworfen)

**Laut Issue:** XLSX/ODS sollten in einer eigenen Tabellenansicht auf Blatt und Zeilen der Fundstelle öffnen, Mails in einer Mail-Ansicht auf der zitierten Nachricht, jeweils markiert. Teil von Epic #3260.

**Geliefert:** Nicht umgesetzt. Laut Abschluss des Epics #3260 deckt die Textfassung (#3316, PR #3458) diese Formate ab, die eigene Ansicht ist nicht mehr geplant.

**Verifikation:** Tabellen und Mails öffnen in der Textfassung, eine Tabellen- oder Mail-Ansicht gibt es nicht.

**Themen:** fundstelle, tabellen, mail, verworfen

---

<a id="issue-3320"></a>

## Issue #3320 — feat(frontend): DOCX-Ansicht mit Sprung an die Fundstelle
- Geschlossen: 2026-10-30 (not planned)
- Labels: enhancement, frontend, size:M, parked
- PRs: keine
- Befund: 1 (verworfen)

**Laut Issue:** DOCX sollte originalnah im Browser gerendert werden (etwa mit docx-preview), an der Fundstelle öffnen und sie markieren. Teil von Epic #3260.

**Geliefert:** Nicht umgesetzt. Laut Abschluss des Epics #3260 reicht die Textfassung (#3316, PR #3458).

**Verifikation:** DOCX öffnet in der Textfassung, einen DOCX-Renderer gibt es im Frontend nicht.

**Themen:** fundstelle, docx, verworfen

---

<a id="issue-3321"></a>

## Issue #3321 — feat: PPTX/ODP-Folienansicht mit Sprung auf die Folie (Konvertierung nach PDF klären)
- Geschlossen: 2026-10-30 (not planned)
- Labels: enhancement, backend, frontend, size:L, parked
- PRs: keine
- Befund: 1 (verworfen)

**Laut Issue:** Präsentationen sollten originalgetreu auf der zitierten Folie öffnen. Vorher sollte entschieden werden, ob serverseitig nach PDF konvertiert wird. Teil von Epic #3260.

**Geliefert:** Nicht umgesetzt. Die Textfassung zeigt Präsentationen Folie für Folie, die Konvertierungsfrage wurde nicht entschieden.

**Verifikation:** PPTX/ODP öffnen in der Textfassung.

**Themen:** fundstelle, präsentationen, verworfen

---

<a id="issue-3360"></a>

## Issue #3360 — fix(security): Zielprüfung um eingebettete IPv4-Präfixe erweitern und geprüfte Adresse verbinden
- Geschlossen: 2026-10-30 (completed)
- Labels: bug, backend, size:S, security
- PRs: #3441 (2026-10-30)
- Befund: 2 (Punkt 2 auf anderem Weg erledigt)

**Laut Issue:** (1) Die SSRF-Zielprüfung sollte zusätzlich `64:ff9b:1::/48`, `2002::/16`, `2001::/32`, `::ffff:0:0/96`, `100::/64` und `0.0.0.0/8` sperren. (2) SMB sollte die geprüfte `InetAddress` verbinden und S3 einen prüfenden `DnsResolver` nutzen, damit DNS-Rebinding nicht greift.

**Geliefert:** PR #3441 erledigt Punkt 1 mit einer Präfixtabelle in `TargetAddressValidator` und Tests auch für benachbarte erlaubte Adressen. Punkt 2 baut der PR nicht wie beschrieben. Er verweist auf #3323 / ADR-0063: Ein JVM-weiter `PinnedAddressResolverProvider` bindet jeden geprüften Namen an die geprüften Adressen. Das gilt auch für SMB und den Apache-Client des AWS SDK.

**Verifikation:** `docs/decisions/0063-bindung-gepruefter-namen-gegen-dns-rebinding.md` besteht. Die Rebinding-Commits (u. a. `9153dacfc`) liegen auf `main`.

**Themen:** ssrf, ipv6, dns-rebinding, security-audit

---

<a id="issue-3378"></a>

## Issue #3378 — test(security): Allowlist für anonym erreichbare Pfade erzwingen
- Geschlossen: 2026-10-29 (duplicate)
- Labels: backend, size:S, security
- PRs: keine
- Befund: 1 (Duplikat)

**Laut Issue:** Ein Test sollte jeden Pfad der OpenAPI-Spezifikation und alle Handler-Mappings ohne Anmeldung aufrufen und gegen eine Allowlist anonym erreichbarer Pfade prüfen. Teil von #3325.

**Geliefert:** Als Duplikat geschlossen. Die inhaltsgleiche Anforderung liefert #3382 (PR #3415, `AnonymousSurfaceIntegrationTest`), dort zusammen mit Security-Headern und Compose-Vorgaben.

**Verifikation:** Siehe #3382.

**Themen:** anonyme-pfade, allowlist, duplikat, security-audit

---

<a id="issue-3381"></a>

## Issue #3381 — test(security): Rechte-Matrix über alle API-Operationen und Rollen
- Geschlossen: 2026-10-29 (duplicate)
- Labels: backend, size:L, security, auth
- PRs: keine
- Befund: 1 (Duplikat)

**Laut Issue:** Ein Integrationstest sollte jede OpenAPI-Operation gegen jede Rolle prüfen, auch mit Objekt-IDs aus einem fremden Space. Eine neue Operation ohne Matrix-Eintrag sollte den Test scheitern lassen.

**Geliefert:** Als Duplikat geschlossen. Die Anforderung liefert #3377 (PR #3421, `permission-matrix.yaml`).

**Verifikation:** Siehe #3377.

**Themen:** rechte-matrix, duplikat, security-audit

---

<a id="issue-3385"></a>

## Issue #3385 — docs(security): Security-Checkliste im Review und wöchentliche Security-Prüfroutine
- Geschlossen: 2026-10-29 (duplicate)
- Labels: documentation, size:S, security
- PRs: keine
- Befund: 1 (Duplikat)

**Laut Issue:** Die Security-Prüfung im Code-Reviewer sollte eine Checkliste mit festen Auslösern bekommen, dazu eine wöchentliche Security-Prüfroutine und einen festgelegten Rhythmus für das volle Audit.

**Geliefert:** Als Duplikat geschlossen. Geliefert wurde es unter #3380 (PR #3437, Routine seit 30.10.2026 eingerichtet).

**Verifikation:** Siehe #3380. `docs/security-pruefung.md` besteht.

**Themen:** review-checkliste, prüfrhythmus, duplikat

---

<a id="issue-3391"></a>

## Issue #3391 — Gradle Dependency Verification (verification-metadata.xml) einführen
- Geschlossen: 2026-10-29 (completed)
- Labels: size:M, ci, security, dependencies
- PRs: #3417 (2026-10-29)
- Befund: 3 (Begleitregel im selben Zeitraum zurückgenommen)

**Laut Issue:** Der Gradle-Build sollte die Prüfsummen aller geladenen Artefakte und Plugins gegen `verification-metadata.xml` prüfen. Das Issue war als Folgepunkt aus #3340 ausgegliedert.

**Geliefert:** PR #3417 führt die Prüfung ein (`backend/gradle/verification-metadata.xml`, SHA-256). Zusätzlich führte er zwei Renovate-Regeln ein: 14 Tage Wartefrist und kein Auto-Merge für Gradle-Plugins, `spring-boot-dependencies` und Jackson-BOMs. Diese Regeln hat PR #3440 (#3439, 30.10.2026) auf Entscheidung des Maintainers wieder entfernt. Plugins laufen seither wie Bibliotheken mit 3 Tagen Wartefrist und Auto-Merge.

**Verifikation:** `backend/gradle/verification-metadata.xml` besteht. #3430 (PR #3449) hat die Datei gegen einen frischen Lauf geprüft: 0 abweichende Prüfsummen, und die Datei wurde bewusst nicht ersetzt. `renovate.json5` enthält keine 14-Tage-Regel für Plugins mehr.

**Themen:** lieferkette, gradle, prüfsummen, renovate, teil-rückbau

---

<a id="pr-3443"></a>

## PR #3443 — ci(security): Abhängigkeits-Gate sieht transitive npm- und Gradle-Abhängigkeiten
- Gemergt: 2026-10-30
- Labels: ci, security, dependencies, security-relevant
- Bezug: #3427
- Befund: 3 (Gate wirkungslos, Prüfschritt ersetzt)

**Laut PR:** Jeder PR-Head bekommt Snapshots des Gradle-Graphen und aller transitiven pnpm-Pakete (`pnpm_dependency_snapshot.py`). Ein neuer Workflow `dependency-graph-submit.yml` reicht sie per `workflow_run` ohne Schreib-Token ein. `dependency-review` sollte damit auch transitive Abhängigkeiten sehen.

**Geliefert:** Die Snapshot-Erzeugung wurde wie beschrieben gemergt. Das Gate selbst griff laut PR #3461 jedoch **nie**. Der Nachweis-PR #3459 zeigte, dass `dependency-review` wegen der nur schließlich konsistenten Vergleichs-API mit „No snapshots were found“ grün durchlief. PR #3461 (Closes #3427) ersetzt die Prüfentscheidung deshalb durch das eigene Skript `.github/scripts/dependency_review_gate.py`: Warten auf Snapshots mit wechselndem `per_page` und 6 Stichproben, fail-closed.

**Verifikation:** `.github/workflows/ci.yml`, Job `dependency-review`, ruft `dependency_review_gate.py` auf. Die Action `actions/dependency-review-action` ist dort weiter eingebunden. Für den Report gilt der Endzustand nach #3461.

**Themen:** abhängigkeits-gate, dependency-review, transitive-abhängigkeiten, ersetzt

---

<a id="issue-3445"></a>

## Issue #3445 — feat(frontend): Original herunterladen aus Vorschaufenstern und Belegfenster
- Geschlossen: 2026-10-30 (duplicate)
- Labels: enhancement, frontend, size:S
- PRs: keine
- Befund: 1 (als Duplikat geschlossen, Inhalt aber geliefert)

**Laut Issue:** Seit dem eigenen PDF-Viewer und der Textvorschau war ein Download des Originals nicht mehr erreichbar. Er sollte überall, wo ein Dokument geöffnet wird, mit einem Klick möglich sein. Teil von Epic #3260.

**Geliefert:** Als `duplicate` geschlossen, ohne eigenen PR. Die Funktion kam mit PR #3458 („Textfassung an der Fundstelle und Original herunterladen“, Closes #3316). Der Abschlusskommentar des Epics #3260 führt #3445 ausdrücklich als dort geliefert.

**Verifikation:** Die E2E-Spec `e2e/tests/evidence-viewer.spec.ts` (#3463, PR #3472) prüft „Herunterladen“ des Originals.

**Themen:** original-download, vorschau, duplikat-statt-completed

---

<a id="issue-3462"></a>

## Issue #3462 — test(e2e): Lücken der E2E-Suite schließen
- Geschlossen: 2026-10-30 (completed)
- Labels: enhancement, epic, e2e
- PRs: keine (Epic, Lieferung über Sub-Issues)
- Befund: 2 (bewusster Teil-Schnitt ohne Folge-Issues)

**Laut Issue:** Eine Bestandsaufnahme vom 30.10.2026 fand Features ohne Durchstich Browser → nginx → Backend → DB. Diese Lücken sollten in den Phasen P1 (Belege, Rechte-Härtung, Revision, Einstellungen übertragen) und P2 (Aufnahme-Breite, Chat-Robustheit, Lebenszyklus) geschlossen werden.

**Geliefert:** Alle 7 Sub-Issues: #3463 (PR #3472), #3464 (PR #3470), #3465 (PR #3468), #3466 (PR #3473), #3467 (PR #3469), #3474 Firefox-Smoke-Lauf (PR #3477, ursprünglich außerhalb des Umfangs) und #3475 axe am Endzustand (PR #3476). Die Laufzeit auf `main` stieg von 17:25 auf 19:22 min. **Bewusst offen ohne Folge-Issues** blieben die P2/P3-Reste der Bestandsaufnahme: Diagnosezugang fachlich, Rechteübertragung, Antwort abbrechen, automatische Chat-Löschung, Vorfall-Scopes, weitere Konnektoren, 320 px für Chat und Bibliotheksdetail.

**Verifikation:** `e2e/playwright.config.ts` kennt `E2E_BROWSER=firefox`. Die neuen Specs sind in `e2e/README.md` beschrieben.

**Themen:** e2e, testlücken, firefox, axe, teil-schnitt

---

## Anhang: geprüfte Vorgänge ohne Befund

**Issues (613):**

#1282, #1292, #1296, #1637, #1905, #1925, #1987, #1988, #1991, #1995, #2057, #2068, #2070, #2071,
#2077, #2079, #2082, #2085, #2089, #2091, #2093, #2095, #2097, #2098, #2103, #2121, #2123, #2124,
#2128, #2129, #2130, #2131, #2135, #2146, #2148, #2149, #2150, #2151, #2152, #2153, #2154, #2155,
#2159, #2160, #2161, #2162, #2163, #2164, #2165, #2166, #2167, #2168, #2169, #2173, #2174, #2175,
#2178, #2180, #2184, #2192, #2193, #2194, #2201, #2202, #2205, #2207, #2208, #2215, #2216, #2218,
#2219, #2220, #2221, #2226, #2236, #2238, #2241, #2246, #2249, #2251, #2252, #2255, #2260, #2263,
#2265, #2266, #2268, #2271, #2273, #2276, #2279, #2281, #2284, #2289, #2294, #2295, #2297, #2298,
#2300, #2302, #2303, #2313, #2321, #2327, #2328, #2330, #2331, #2334, #2336, #2338, #2340, #2341,
#2342, #2343, #2345, #2346, #2347, #2348, #2349, #2350, #2351, #2352, #2353, #2354, #2355, #2356,
#2357, #2364, #2368, #2373, #2375, #2376, #2377, #2387, #2388, #2393, #2394, #2395, #2396, #2397,
#2398, #2399, #2401, #2408, #2410, #2415, #2416, #2417, #2418, #2422, #2423, #2428, #2430, #2432,
#2433, #2436, #2437, #2448, #2449, #2458, #2460, #2463, #2467, #2470, #2472, #2479, #2480, #2482,
#2486, #2488, #2490, #2492, #2493, #2495, #2498, #2502, #2504, #2506, #2509, #2511, #2514, #2516,
#2518, #2527, #2528, #2530, #2531, #2535, #2537, #2538, #2540, #2543, #2544, #2545, #2546, #2547,
#2548, #2549, #2550, #2551, #2552, #2553, #2554, #2555, #2556, #2557, #2558, #2559, #2560, #2561,
#2562, #2563, #2564, #2565, #2566, #2567, #2568, #2569, #2570, #2571, #2572, #2573, #2574, #2575,
#2585, #2586, #2587, #2588, #2589, #2590, #2596, #2600, #2604, #2606, #2611, #2612, #2614, #2619,
#2620, #2621, #2622, #2623, #2624, #2632, #2634, #2641, #2642, #2643, #2644, #2645, #2646, #2647,
#2648, #2649, #2650, #2651, #2652, #2653, #2654, #2655, #2656, #2657, #2658, #2659, #2660, #2661,
#2662, #2663, #2672, #2678, #2679, #2680, #2681, #2682, #2683, #2684, #2685, #2686, #2687, #2688,
#2689, #2690, #2691, #2692, #2693, #2694, #2695, #2696, #2697, #2698, #2699, #2700, #2701, #2702,
#2703, #2704, #2705, #2711, #2715, #2716, #2720, #2724, #2741, #2749, #2752, #2753, #2758, #2759,
#2760, #2762, #2766, #2770, #2772, #2775, #2777, #2781, #2782, #2785, #2789, #2793, #2794, #2796,
#2798, #2799, #2803, #2804, #2806, #2811, #2812, #2814, #2823, #2824, #2826, #2827, #2831, #2838,
#2840, #2843, #2844, #2845, #2847, #2850, #2851, #2852, #2853, #2854, #2855, #2856, #2858, #2859,
#2860, #2868, #2870, #2876, #2877, #2884, #2887, #2889, #2894, #2895, #2898, #2900, #2901, #2902,
#2903, #2905, #2906, #2907, #2908, #2909, #2910, #2911, #2912, #2913, #2941, #2943, #2945, #2946,
#2947, #2948, #2949, #2950, #2951, #2952, #2962, #2968, #2972, #2973, #2975, #2977, #2979, #2981,
#2984, #2985, #2986, #2987, #2988, #2989, #2990, #2991, #2993, #2994, #2996, #2998, #2999, #3001,
#3004, #3007, #3008, #3009, #3010, #3011, #3012, #3013, #3014, #3017, #3018, #3019, #3020, #3021,
#3022, #3024, #3026, #3028, #3039, #3040, #3042, #3044, #3046, #3047, #3048, #3049, #3050, #3051,
#3052, #3053, #3059, #3062, #3079, #3083, #3084, #3093, #3096, #3098, #3100, #3102, #3104, #3105,
#3106, #3107, #3108, #3109, #3113, #3117, #3118, #3120, #3121, #3122, #3124, #3125, #3127, #3129,
#3133, #3135, #3136, #3139, #3140, #3144, #3147, #3152, #3156, #3159, #3160, #3162, #3163, #3164,
#3165, #3168, #3169, #3170, #3171, #3172, #3175, #3179, #3182, #3190, #3192, #3193, #3194, #3195,
#3201, #3204, #3207, #3208, #3209, #3210, #3211, #3212, #3214, #3217, #3218, #3220, #3224, #3226,
#3230, #3234, #3235, #3237, #3239, #3240, #3242, #3244, #3245, #3246, #3250, #3251, #3252, #3253,
#3256, #3257, #3258, #3259, #3261, #3262, #3264, #3265, #3267, #3269, #3276, #3282, #3283, #3284,
#3285, #3286, #3287, #3289, #3298, #3300, #3305, #3306, #3307, #3311, #3315, #3316, #3317, #3318,
#3325, #3326, #3327, #3328, #3329, #3330, #3331, #3332, #3333, #3335, #3336, #3337, #3338, #3339,
#3340, #3341, #3342, #3343, #3344, #3345, #3346, #3347, #3352, #3353, #3354, #3355, #3356, #3357,
#3358, #3359, #3374, #3375, #3376, #3377, #3379, #3380, #3382, #3383, #3384, #3387, #3388, #3394,
#3395, #3396, #3397, #3405, #3418, #3419, #3420, #3426, #3427, #3428, #3430, #3431, #3432, #3434,
#3439, #3450, #3452, #3460, #3463, #3464, #3465, #3466, #3467, #3474, #3475

**Pull Requests ohne Issue-Verknüpfung (161, davon 73 Renovate-Updates):**

PR#2072, PR#2073, PR#2074, PR#2075, PR#2076, PR#2083, PR#2084, PR#2108, PR#2109, PR#2110, PR#2111,
PR#2114, PR#2115, PR#2119, PR#2140, PR#2141, PR#2142, PR#2143, PR#2144, PR#2158, PR#2185, PR#2187,
PR#2189, PR#2191, PR#2195, PR#2196, PR#2197, PR#2199, PR#2211, PR#2212, PR#2217, PR#2223, PR#2224,
PR#2225, PR#2227, PR#2228, PR#2229, PR#2230, PR#2231, PR#2233, PR#2234, PR#2235, PR#2242, PR#2243,
PR#2250, PR#2254, PR#2259, PR#2261, PR#2262, PR#2264, PR#2269, PR#2272, PR#2274, PR#2286, PR#2287,
PR#2288, PR#2292, PR#2315, PR#2316, PR#2317, PR#2318, PR#2319, PR#2323, PR#2324, PR#2325, PR#2326,
PR#2379, PR#2380, PR#2381, PR#2382, PR#2383, PR#2450, PR#2451, PR#2452, PR#2453, PR#2454, PR#2455,
PR#2459, PR#2469, PR#2474, PR#2475, PR#2476, PR#2477, PR#2478, PR#2503, PR#2508, PR#2523, PR#2524,
PR#2525, PR#2526, PR#2578, PR#2579, PR#2580, PR#2581, PR#2582, PR#2584, PR#2592, PR#2629, PR#2667,
PR#2668, PR#2669, PR#2670, PR#2745, PR#2764, PR#2768, PR#2807, PR#2808, PR#2809, PR#2836, PR#2871,
PR#2872, PR#2873, PR#2874, PR#2875, PR#2878, PR#2879, PR#2880, PR#2881, PR#2882, PR#2919, PR#2921,
PR#2926, PR#2928, PR#2930, PR#2931, PR#2933, PR#2934, PR#2936, PR#2937, PR#2938, PR#2939, PR#2940,
PR#2955, PR#2957, PR#2966, PR#3067, PR#3074, PR#3082, PR#3087, PR#3089, PR#3097, PR#3130, PR#3143,
PR#3148, PR#3149, PR#3151, PR#3157, PR#3185, PR#3186, PR#3188, PR#3202, PR#3203, PR#3231, PR#3297,
PR#3322, PR#3348, PR#3351, PR#3371, PR#3390, PR#3422, PR#3437
