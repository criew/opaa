# Demo-Drehbuch „Stadt Rheinfurt"

Acht vorbereitete Fragen für die Vorführung der Demo-Instanz „Stadt Rheinfurt" (Epic #708). Dieses
Dokument ist Überzeugungsmaterial und leitet sich wie jedes andere Marketing-Asset aus
[`MESSAGING.md`](./MESSAGING.md) ab — der stärkste Vorführ-Moment (Frage 5) ist die dort zentrale
Botschaft **Belegbarkeit**, live erlebbar: zwei Nutzer stellen dieselbe Frage und bekommen
unterschiedliche, jeweils belegte Antworten, weil sie unterschiedliche Wissensbibliotheken lesen
dürfen.

Konzept, Behördenlandschaft, Bibliotheken und Rechtematrix hinter diesem Drehbuch stehen in
[`../features/demo-instance.md`](../features/demo-instance.md) und werden hier **nicht wiederholt**.
Installation, Nutzerkonten und Passwörter stehen in [`../../demo/README.md`](../../demo/README.md).

**Invariante: Frage 1 ist garantiert beantwortbar.** Issue #711 stellt sicher, dass der Rheinfurt-Korpus
für die Gebührenfrage (Frage 1 unten) immer einen Treffer liefert; `e2e/demo-smoke` prüft diese
Invariante bei jedem Lauf gegen den echten `demo`-Compose-Stack (Keycloak-Anmeldung, Seed, Suche,
belegte Antwort — siehe [`../../e2e/README.md`](../../e2e/README.md), Abschnitt „Demo-Smoke (#232)").
Fällt diese Invariante durch eine Korpus-Änderung weg, schlägt `e2e/demo-smoke` fehl, bevor die
Vorführung selbst es täte.

---

## Verifikationsgrundlage dieses Drehbuchs

Gegen einen isolierten Compose-Stack wurde der Seed ausgeführt und drei Fragen (die
Berechtigungs-Doppelfrage aus Frage 5 sowie Frage 6) per `POST /v1/query` geprüft — mit `ai-stub`
(`e2e/ai-stub/server.mjs`, Muster aus #712) anstelle eines echten Chat-/Embedding-Anbieters. Das
bestätigt deterministisch die Berechtigungsgrenze: Ein Konto ohne `VIEWER`-Recht auf einer Bibliothek
erhält nie eine Quelle daraus, unabhängig vom Frageinhalt. Weil `ai-stub` für jede Eingabe denselben
Embedding-Vektor liefert (Kosinus-Ähnlichkeit immer 1,0), lässt sich damit **keine** inhaltliche
Relevanz prüfen — welche der für ein Konto lesbaren Bibliotheken tatsächlich die thematisch treffenden
Quellen liefert, hängt vom echten Embedding-Modell ab. Die unten dokumentierten erwarteten Antworten
und Dateizitate beruhen deshalb auf manueller Prüfung der tatsächlichen Korpusdateien, nicht auf einem
`ai-stub`-Lauf; eine inhaltliche Relevanzmessung mit einem echten Embedding-Modell ist Aufgabe des
Eval-Korpus (Epic #224), nicht dieser Demo.

Die Fragen setzen auf konkreten Inhalten des Rheinfurt-Korpus auf (`demo/corpus/`) — bei einer
Korpus-Aktualisierung (siehe [`../../demo/README.md`, „Korpus neu erzeugen"](../../demo/README.md#korpus-neu-erzeugen))
sind sie mit dem neuen Stand gegenzuprüfen.

## 1. Gebührenfrage

- **Konto:** eines der vier Fachkonten, z. B. `maria.weber`
- **Frage:** „Was kostet ein Personalausweis für eine 22-Jährige?"
- **Erwartete Antwort:** 27,20 Euro (Gebührenrahmen „unter 24 Jahren") — belegt sowohl aus der
  Leistungsbeschreibung `001_personalausweis.md` (Bibliothek „Leistungen Meldewesen & Ausweise") als
  auch aus der Verwaltungsgebührensatzung (`01_verwaltungsgebuehrensatzung.pdf`, Bibliothek
  „Satzungen & Gebührenordnungen") — beide Quellen sind für jedes der vier Fachkonten lesbar.
- **Zeigt:** eine einfache, belegte Auskunft mit einer konkreten Zahl aus einem PDF-Dokument.

## 2. Verfahrensfrage

- **Konto:** `maria.weber` oder `selin.kaya`
- **Frage:** „Welche Unterlagen brauche ich für die Ummeldung?"
- **Erwartete Antwort:** gültiges Ausweisdokument, Wohnungsgeberbestätigung (mit den in
  `024_wohnsitz-anmelden-oder-ummelden.md` benannten Pflichtangaben) — belegt aus der
  Leistungsbeschreibung „Wohnsitz anmelden oder ummelden".
- **Zeigt:** eine mehrteilige Unterlagenliste korrekt aus einem `.md`-Dokument extrahiert.

## 3. Aktualitätsfrage

- **Konto:** ein beliebiges Fachkonto
- **Frage:** „Wann ist das Bürgerbüro wegen des Stadtfests geschlossen?"
- **Erwartete Antwort:** Freitag, 19. Juni 2026, ganztägig; ab Montag, 22. Juni 2026, wieder reguläre
  Öffnungszeiten — belegt aus der Pressemitteilung `buergerbuero-geschlossen-stadtfest.html`
  (Bibliothek „Pressemitteilungen Stadt Rheinfurt", `RSS_FEED`).
- **Zeigt:** dass eine tagesaktuelle Meldung aus dem RSS-Feed genauso durchsucht wird wie eine
  Leistungsbeschreibung — der Konnektortyp ist für die Antwort unsichtbar.

## 4. Kfz-Frage

- **Konto:** `thomas.klein`
- **Frage:** „Kann ich mein Wunschkennzeichen online reservieren?"
- **Erwartete Antwort:** ja, über das Internetangebot der Kfz-Zulassungsbehörde; die
  Online-Reservierung ist drei Monate gültig (gegenüber einem Monat bei Reservierung im Bürgerbüro),
  Gebühr 14,10 Euro bzw. 11,30 Euro bei Zulassung am Tag der Online-Reservierung — belegt aus
  `008_wunschkennzeichen.txt` (Bibliothek „Leistungen Kfz-Zulassung").
- **Zeigt:** dieselbe Antwortqualität für eine `.txt`-Quelle wie für `.md` — der Formatvorrat ist für
  Suche und Beleg gleichgültig.

## 5. Berechtigungs-Doppelfrage

- **Konten:** dieselbe Frage einmal als `maria.weber`, einmal als `thomas.klein`
- **Frage:** „Wann gilt bei der Ausstellung eines Personalausweises das Vier-Augen-Prinzip?"
- **Erwartete Antwort als Maria:** belegt aus der internen Dienstanweisung
  `07_vier-augen-prinzip-ausweisausstellung.docx` (Bibliothek „Interne Dienstanweisungen
  Meldewesen") — bei Erstbeantragung ohne Altdokument, bei Verlustanzeige mit Neubeantragung am
  selben Tag, bei Verdacht auf gefälschte Dokumente.
- **Erwartete Antwort als Thomas:** keine Quelle — Thomas hat keinen `VIEWER`-Zugriff auf diese
  Bibliothek, und die Anfrage durchsucht sie deshalb gar nicht erst.
- **Zeigt:** den stärksten Vorführ-Moment der Demo — dieselbe Frage, zwei Konten, zwei Antworten,
  weil beide unterschiedliche Wissensbibliotheken lesen dürfen.

## 6. Quer-Bibliotheks-Frage

- **Konten:** `maria.weber` und, zum Vergleich, `thomas.klein`
- **Frage:** „Was gilt bei Gebührenbefreiung wegen Bedürftigkeit?"
- **Erwartete Antwort als Maria:** die Rechtsgrundlage aus der Verwaltungsgebührensatzung (§ 3 VGS,
  „Satzungen & Gebührenordnungen") **plus** die praktische Schalter-Anleitung aus der internen
  Dienstanweisung `02_gebuehrenbefreiung-beduerftigkeit.docx` — die anerkannten Nachweise (Bürgergeld
  usw.) stehen bereits in § 3 VGS selbst; der echte Mehrwert der internen Dienstanweisung liegt in den
  Verfahrensschritten am Schalter (Antrag samt Nachweis vorlegen, Weiterleitung an die
  Sachgebietsleitung, Amtshandlung bereits vor der Entscheidung) und der Drei-Monats-Frist, innerhalb
  derer der Nachweis nicht älter sein darf.
- **Erwartete Antwort als Thomas:** nur die Rechtsgrundlage aus der Satzung, ohne die interne
  Verfahrensanleitung — beide Konten lesen dieselbe Satzung, nur Meldewesen-Konten lesen zusätzlich
  die interne Dienstanweisung.
- **Zeigt:** eine Antwort, die sich je nach Rechten nicht in Existenz, sondern in Vollständigkeit
  unterscheidet.

## 7. Amtsleitungs-Frage

- **Konto:** `andrea.vogt`
- **Frage:** „Wie ist die Terminvergabe im Bürgerbüro bei hohem Andrang zwischen Meldewesen und
  Kfz-Zulassung geregelt, und welche Frist gilt für eine online reservierte
  Wunschkennzeichen-Reservierung?"
- **Erwartete Antwort:** zwei Teile aus zwei exklusiven Bibliotheken, die sich erst zusammen zur
  vollständigen Antwort fügen:
  - Aus der internen Dienstanweisung `09_terminvergabe-wartezeitmanagement.docx` (Bibliothek
    „Interne Dienstanweisungen Meldewesen", nur für Meldewesen-Konten und Andrea lesbar): tägliche
    feste Terminkontingente je Sachgebiet plus ein kleines Kontingent für dringende Spontanfälle;
    bei hohem Andrang entscheidet die diensthabende Teamleitung über eine vorübergehende
    Personalumverteilung zwischen den Empfangsbereichen Meldewesen und Kfz-Zulassung.
  - Aus der Leistungsbeschreibung `008_wunschkennzeichen.txt` (Bibliothek „Leistungen
    Kfz-Zulassung", nur für Thomas und Andrea lesbar): eine online reservierte
    Wunschkennzeichen-Reservierung ist drei Monate gültig (gegenüber einem Monat bei Reservierung im
    Bürgerbüro selbst), Gebühr 14,10 Euro bzw. 11,30 Euro bei Zulassung am Tag der
    Online-Reservierung.
  - **Als Maria/Selin:** nur der erste Teil (Terminvergabe) belegt, zur Wunschkennzeichenfrist keine
    Quelle — die Kfz-Bibliothek ist ihnen nicht zugänglich.
  - **Als Thomas:** nur der zweite Teil (Wunschkennzeichenfrist) belegt, zur internen Terminvergabe
    keine Quelle — die interne Meldewesen-Bibliothek ist ihm nicht zugänglich.
  - **Als Andrea:** beide Teile belegt, da sie als einziges Fachkonto beide Bibliotheken lesen darf.
- **Zeigt:** dass die Amtsleitung als einziges Konto eine wirklich über beide Sachgebiete verteilte
  Antwort vollständig zusammensetzen kann — anders als bei Frage 6 unterscheidet sich hier nicht nur
  die Vollständigkeit einer einzelnen Quelle, sondern es fehlt je nach Konto eine ganze Antworthälfte
  aus einer anderen Bibliothek.

## 8. Bewusst unbeantwortbare Frage

- **Konto:** ein beliebiges Fachkonto
- **Frage:** „Wie beantrage ich in Rheinfurt eine Fischereierlaubnis?"
- **Erwartete Antwort:** keine Quelle wird genannt, keine wird erfunden — zu dieser Leistung liegt in
  keiner der fünf Bibliotheken etwas vor (geprüft: kein Treffer für „Fischer" im gesamten Korpus,
  Stand dieser Anleitung). Einen eigenen Verweigerungsmodus gibt es dafür nicht (mit #697 verworfen);
  die Belegvalidierung greift, sobald ein Beleg tatsächlich ungültig wäre, hier bleibt die
  Kontextmenge schlicht leer.
- **Zeigt:** dass OPAA bei fehlendem Wissen nichts erfindet — die Umkehrung des ersten
  Vorführ-Moments.

**Neunte Frage mit tatsächlich ungültigem Beleg:** Ein Szenario, in dem die Suche einen Treffer
liefert, dessen Beleg sich als ungültig herausstellt (statt schlicht keinen Treffer zu liefern), ließ
sich beim Konstruieren dieses Drehbuchs nicht reproduzierbar herstellen — die Belegvalidierung aus
#697 prüft rein deterministisch, ob eine im Antworttext genannte Fundstelle tatsächlich unter den
abgerufenen Chunks war; ein synthetischer Korpus ohne absichtlich widersprüchliche Inhalte produziert
diesen Fall nicht von selbst. Bleibt offen für eine spätere, gezielt konstruierte Ergänzung.

---

## Zugehörige Dokumentation

- [`../../demo/README.md`](../../demo/README.md) — Installation mit einem Befehl, Nutzerkonten mit
  Passwörtern, öffentliche Instanz, Korpus-Aktualisierung
- [`../features/demo-instance.md`](../features/demo-instance.md) — Konzept: Behördenlandschaft,
  Bibliotheken, Formate, Quellen und Lizenzen, Rechtemodell
- [`../handbuch/deployment.md`](../handbuch/deployment.md), Abschnitt „Härtung für erreichbare
  Deployments" — zwingend vor jedem über `localhost` hinaus erreichbaren Rollout dieser Demo
- [`../../e2e/README.md`](../../e2e/README.md), Abschnitt „Demo-Smoke (#232)" — der automatisierte
  Nachweis der Frage-1-Invariante
- [`MESSAGING.md`](./MESSAGING.md) — die Botschaften, aus denen dieses Drehbuch abgeleitet ist
