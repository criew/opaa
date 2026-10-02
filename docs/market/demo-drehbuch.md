# Demo-Drehbuch „Stadt Rheinfurt"

Acht vorbereitete Fragen und acht Vorführschritte für die Demo-Instanz „Stadt Rheinfurt" (Epic #708,
Epic #2012, Epic #2070). Dieses Dokument ist Überzeugungsmaterial und leitet sich wie jedes andere
Marketing-Asset aus [`MESSAGING.md`](./MESSAGING.md) ab. Der stärkste Vorführ-Moment ist die dort
zentrale Botschaft **Belegbarkeit**, live erlebbar: Zwei Nutzer stellen dieselbe Frage und bekommen
unterschiedliche, jeweils belegte Antworten (Frage 5). Wer das Recht dahinter vergibt und wieder
nimmt, zeigen die Schritte A und B.

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

**Stand 27.09.2026**, geprüft gegen einen lokal aufgesetzten Stack: Compose-Profil `demo` mit dem
Overlay `e2e/docker-compose.demo-smoke.yml`, also mit `ai-stub` (`e2e/ai-stub/server.mjs`) statt
eines echten Chat- und Embedding-Anbieters. Codestand ist `main` mit den Keycloak-Gruppen aus #2029.
Geprüft wurde auf dem Branch des PRs kurz vor seinem Merge; der Merge hat danach nur noch die
Geheimnisbehandlung des Seeds geändert, nicht die Rechte. Der Seed (`python seed.py --profile demo`)
lief vollständig durch.

Geprüft wurde auf zwei Wegen:

- **Per API**, mit den Demo-Konten über Keycloak angemeldet:
  - lesbare Bibliotheken und Spaces je Konto
  - die durchsuchten Bibliotheken je Space (`POST /api/v1/query` in einem Chat des Space, Feld
    `metadata.searchedLibraries`) und die gelieferten Quellen
  - Treffer von `POST /api/v1/search`
  - Gruppen, Verzeichnisabgleich, Prompts, Ordner und Anhänge
- **In der Oberfläche** per Playwright mit Bildschirmfotos. So sind alle unten genannten
  Beschriftungen abgeglichen: „Meine Gruppen“, „Admin“ → „Gruppen“ und „Verzeichnisabgleich“,
  Slash-Menü und Variablenformular, Prompt-Nachweis im Verlauf, „Prompts“, „Katalog“,
  Ordnernavigation und Anhänge.

Die Schritte A und B wurden live durchgespielt und danach zurückgesetzt: Mitglied herausnehmen und
wieder aufnehmen, Keycloak-Mitgliedschaft entfernen, abgleichen, zurückgeben und erneut abgleichen.

**Schritt E zusätzlich mit echtem Modell:** Am 27.09.2026 wurden die Fachfragen aus Schritt E live
auf der Demo-Instanz opaa.ewerlin.com gestellt (Chat-Modell `claude-haiku-4-5`, Suche auf die
jeweilige Bibliothek eingeschränkt). Die beiden XLSX-Fragen (BB-3311, BB-3213), die CSV-Frage, die
Anhang-Frage zum Auftakttermin in Nordfeld (3 von 3 Läufen) und die Frage an die Feuerwache-Anlage aus
Schritt D lieferten korrekte, zitierte Antworten.

**Was `ai-stub` nicht belegen kann:**

- **Keinen Antworttext.** Der Stub liefert für jede Frage denselben Satz mit Quellenmarken. Die
  erwarteten Antworten unten beruhen auf den Korpusdateien selbst; einen Modelllauf gab es nur für
  Schritt E (siehe oben).
- **Keine inhaltliche Rangfolge.** Der Stub liefert für jede Eingabe denselben Embedding-Vektor. Die
  Rangfolge trägt deshalb allein die Volltextsuche, und ein Chunk steht als Artefakt immer vorn
  (`03_auskunftssperren-bearbeitung.docx`). Die meisten Belegdateien kamen trotzdem unter die ersten
  Quellen, ausdrücklich geprüft für die Fragen 4 bis 6 und die Schritte B bis E. Zwei nicht:
  `09_terminvergabe-wartezeitmanagement.docx` bei Frage 7 und, für Maria, die Gebührenübersicht bei
  der Personalausweisfrage aus #2028. Beide Dateien sind indiziert; die Rangfolge mit einem echten
  Embedding-Modell misst der Eval-Korpus (Epic #224), nicht diese Demo.
- **Keine leere Kontextmenge.** Bei Frage 8 liefert der Stub trotzdem Quellen, weil jeder Chunk als
  gleich ähnlich gilt.

**Was `ai-stub` sicher belegt:** Die Grenze aus Leserecht und Space-Zuordnung hängt nicht vom Modell
ab. Welche Bibliotheken eine Anfrage durchsucht, steht in `searchedLibraries`, und keine Quelle kommt
aus einer anderen Bibliothek.

Die Fragen setzen auf konkreten Inhalten des Rheinfurt-Korpus auf (`demo/corpus/`) — bei einer
Korpus-Aktualisierung (siehe [`../../demo/README.md`, „Korpus neu erzeugen"](../../demo/README.md#korpus-neu-erzeugen))
sind sie mit dem neuen Stand gegenzuprüfen.

## Wegweiser durch die Oberfläche

Die Klickwege unten benutzen diese Begriffe:

- **Linke Leiste:** „Spaces“ und „Katalog“. Der Katalog ist der eine Einstieg zu Wissen und Prompts
  (#2114). Bei `demo-admin` kommt „Admin“ hinzu; auf
  opaa.ewerlin.com meldet sich damit nur der Maintainer an (Passwort rotiert), sonst am lokalen Stack.
  Unten sitzt der Avatar mit dem Kontomenü (Bedienhilfe „Profil und Einstellungen“) und den
  Einträgen „Einstellungen“ und „Abmelden“.
- **Space wählen:** über „Spaces“ in der Leiste oder oben in der Space-Spalte (Feld „Space“). Ein
  gewählter Space öffnet einen neuen Chat. Einen weiteren beginnt „Neu“ neben „Chats“.
- **Persönlicher Space:** Jedes Konto hat seinen persönlichen Space „Meine Dokumente“. Der Seed hat
  ihm das Wissen des eigenen Sachgebiets und die Textbausteine zugeordnet.
- **Quellen einer Antwort:** „Belege anzeigen“ unter der Antwort öffnet das Belegfenster rechts;
  ein Klick auf eine Fußnotenziffer im Text öffnet es direkt an dieser Fundstelle. Jede Fundstelle
  hat „Im Dokument öffnen“.

**Vor den Fragen 5 bis 7 wichtig:** Ein Space enthält genau, was ihm zugeordnet ist.
`@Space-Wissen` durchsucht nur die zugeordneten Bibliotheken, und davon nur die für das Konto
lesbaren; die `/`-Auswahl bietet nur zugeordnete Prompts an. Wo eine Frage gestellt wird,
entscheidet deshalb mit über die Antwort. Deshalb nennt jede Frage ihren Space. Wie ein Space ohne
Wissen aussieht, zeigt Schritt G.

## 1. Gebührenfrage

- **Konto und Space:** `maria.weber` im Space „Meldewesen & Ausweise“ (ebenso `selin.kaya` oder
  `andrea.vogt` in ihrem Sachgebiets- bzw. Amtsleitungs-Space)
- **Frage:** „Was kostet ein Personalausweis für eine 22-Jährige?"
- **Erwartete Antwort:** 26,20 Euro (Gebührenrahmen „unter 24 Jahren"). Belegt ist das zweifach:
  - aus der Leistungsbeschreibung `001_personalausweis.md` (Bibliothek „Leistungen Meldewesen &
    Ausweise", lesbar für Maria und Selin über die Keycloak-Gruppe „Meldewesen" und für Andrea)
  - aus der Verwaltungsgebührensatzung `01_verwaltungsgebuehrensatzung.pdf` (Bibliothek „Satzungen &
    Gebührenordnungen", für „Alle Konten" freigegeben)

  Seit #2028 kann zusätzlich die Gebührenübersicht `20_gebuehrenuebersicht-buergerbuero.xlsx` als
  Quelle erscheinen. Thomas liest die Leistungsbeschreibung nicht; er bekommt die Zahl nur aus Satzung
  und Übersicht.
- **Zeigt:** eine einfache, belegte Auskunft mit einer konkreten Zahl aus einem PDF-Dokument.

## 2. Verfahrensfrage

- **Konto und Space:** `maria.weber` oder `selin.kaya` im Space „Meldewesen & Ausweise"
- **Frage:** „Welche Unterlagen brauche ich für die Ummeldung?"
- **Erwartete Antwort:** gültiges Ausweisdokument, Wohnungsgeberbestätigung (mit den in
  `024_wohnsitz-anmelden-oder-ummelden.md` benannten Pflichtangaben) — belegt aus der
  Leistungsbeschreibung „Wohnsitz anmelden oder ummelden".
- **Zeigt:** eine mehrteilige Unterlagenliste korrekt aus einem `.md`-Dokument extrahiert.

## 3. Aktualitätsfrage

- **Konto und Space:** ein beliebiges Fachkonto, in jedem seiner Spaces außer „Infotheke
  Bürgerbüro"
- **Frage:** „Wann ist das Bürgerbüro wegen des Stadtfests geschlossen?"
- **Erwartete Antwort:** Freitag, 19. Juni 2026, ganztägig; ab Montag, 22. Juni 2026, wieder reguläre
  Öffnungszeiten — belegt aus der Pressemitteilung `buergerbuero-geschlossen-stadtfest.html`
  (Bibliothek „Pressemitteilungen Stadt Rheinfurt", `RSS_FEED`). Selin und Thomas lesen die
  Pressemitteilungen über die interne Gruppe „Presseverteiler Bürgerbüro"; allen fachlichen Spaces
  ist die Bibliothek zugeordnet. Die „Infotheke Bürgerbüro" führt nur öffentliches Wissen, die
  geschlossenen Pressemitteilungen gehören nicht dazu.
- **Zeigt:** dass eine tagesaktuelle Meldung aus dem RSS-Feed genauso durchsucht wird wie eine
  Leistungsbeschreibung — der Konnektortyp ist für die Antwort unsichtbar.

## 4. Kfz-Frage

- **Konto und Space:** `thomas.klein` im Space „Kfz-Zulassung"
- **Frage:** „Kann ich mein Wunschkennzeichen online reservieren?"
- **Erwartete Antwort:** ja, über das Internetangebot der Kfz-Zulassungsbehörde; die
  Online-Reservierung ist drei Monate gültig (gegenüber einem Monat bei Reservierung im Bürgerbüro),
  Gebühr 14,70 Euro bzw. 11,70 Euro bei Zulassung am Tag der Online-Reservierung — belegt aus
  `008_wunschkennzeichen.txt` (Bibliothek „Leistungen Kfz-Zulassung"). Thomas liest sie
  ausschließlich über die Keycloak-Gruppe „Kfz-Zulassung", siehe Schritt B.
- **Zeigt:** dieselbe Antwortqualität für eine `.txt`-Quelle wie für `.md` — der Formatvorrat ist für
  Suche und Beleg gleichgültig.

## 5. Berechtigungs-Doppelfrage

- **Konten und Spaces:** dieselbe Frage einmal als `maria.weber` im Space „Meldewesen & Ausweise",
  einmal als `thomas.klein` im Space **„Kfz-Zulassung"**
- **Frage:** „Wann gilt bei der Ausstellung eines Personalausweises das Vier-Augen-Prinzip?"
- **Erwartete Antwort als Maria:** belegt aus der internen Dienstanweisung
  `07_vier-augen-prinzip-ausweisausstellung.docx` (Bibliothek „Interne Dienstanweisungen
  Meldewesen", Ordner `02 Pass- und Ausweiswesen/01 Antrag und Ausstellung`) — bei Erstbeantragung
  ohne Altdokument, bei Verlustanzeige mit Neubeantragung am selben Tag, bei Verdacht auf gefälschte
  Dokumente.
- **Erwartete Antwort als Thomas:** keine Quelle aus der internen Dienstanweisung. Die Anfrage
  durchsucht sie gar nicht erst. Durchsucht werden nur die vier Bibliotheken, die dem Space
  „Kfz-Zulassung" zugeordnet sind: Leistungen Kfz-Zulassung, Satzungen & Gebührenordnungen,
  Pressemitteilungen, Ratsinformationen.
- **Wichtig für die Vorführung:** Thomas darf die interne Bibliothek lesen, ausschließlich über die
  interne Gruppe „Vertretung Meldewesen". Er findet die Dienstanweisung deshalb überall dort, wo sie
  zur Auswahl steht:
  - im Space „Meldewesen & Ausweise", in dem er über dieselbe Gruppe Mitglied ist
  - in seinem persönlichen Space „Meine Dokumente"

  Im Space „Dienstbesprechung Bürgerbüro" findet er sie nicht, auch diesem Space ist sie nicht
  zugeordnet. Die Frage deshalb unbedingt im Space „Kfz-Zulassung" stellen. Wer den Unterschied
  allein am Leserecht zeigen will, nimmt Thomas vorher aus der Gruppe (Schritt A). Danach findet er
  die Dienstanweisung in keinem Space mehr.
- **Zeigt:** den stärksten Vorführ-Moment der Demo — dieselbe Frage, zwei Konten, zwei Antworten.
  Maßgeblich sind zwei Grenzen: was das Konto lesen darf und was der Space zur Auswahl stellt. Keine
  der beiden lässt sich durch geschicktes Fragen umgehen.

## 6. Quer-Bibliotheks-Frage

- **Konten und Spaces:** `maria.weber` im Space „Meldewesen & Ausweise" und, zum Vergleich,
  `thomas.klein` im Space „Kfz-Zulassung"
- **Frage:** „Was gilt bei Gebührenbefreiung wegen Bedürftigkeit?"
- **Erwartete Antwort als Maria:** die Rechtsgrundlage aus der Verwaltungsgebührensatzung (§ 3 VGS,
  „Satzungen & Gebührenordnungen") **plus** die praktische Schalter-Anleitung aus der internen
  Dienstanweisung `02_gebuehrenbefreiung-beduerftigkeit.docx`.
  - Die anerkannten Nachweise (Bürgergeld usw.) stehen bereits in § 3 VGS selbst.
  - Der echte Mehrwert der internen Dienstanweisung liegt in den Verfahrensschritten am Schalter:
    Antrag samt Nachweis vorlegen, Weiterleitung an die Sachgebietsleitung, Amtshandlung bereits vor
    der Entscheidung.
  - Dazu kommt die Drei-Monats-Frist, innerhalb derer der Nachweis nicht älter sein darf.
- **Erwartete Antwort als Thomas:** nur die Rechtsgrundlage aus der Satzung, ohne die interne
  Verfahrensanleitung, denn dem Space „Kfz-Zulassung" ist die interne Bibliothek nicht zugeordnet.
  Seit #2028 kann als zweite Quelle das Blatt „Hinweise" der Gebührenübersicht
  `20_gebuehrenuebersicht-buergerbuero.xlsx` erscheinen. Es sagt nur, dass die Kasse erst nach der
  Entscheidung der Sachgebietsleitung bucht. Nachweisalter und Amtshandlung vor der Entscheidung
  stehen weiterhin nur in der internen Dienstanweisung.
- **Zeigt:** eine Antwort, die sich je nach Kontext nicht in Existenz, sondern in Vollständigkeit
  unterscheidet.

## 7. Amtsleitungs-Frage

- **Konto und Space:** `andrea.vogt` im Space „Amtsleitung Bürgerbüro"
- **Frage:** „Wie ist die Terminvergabe im Bürgerbüro bei hohem Andrang zwischen Meldewesen und
  Kfz-Zulassung geregelt, und welche Frist gilt für eine online reservierte
  Wunschkennzeichen-Reservierung?"
- **Erwartete Antwort:** zwei Teile aus zwei Bibliotheken, die sich erst zusammen zur vollständigen
  Antwort fügen:
  - Aus der internen Dienstanweisung `09_terminvergabe-wartezeitmanagement.docx` (Bibliothek
    „Interne Dienstanweisungen Meldewesen"): tägliche feste Terminkontingente je Sachgebiet plus ein
    kleines Kontingent für dringende Spontanfälle. Bei hohem Andrang entscheidet die diensthabende
    Teamleitung über eine vorübergehende Personalumverteilung zwischen den Empfangsbereichen
    Meldewesen und Kfz-Zulassung.
  - Aus der Leistungsbeschreibung `008_wunschkennzeichen.txt` (Bibliothek „Leistungen
    Kfz-Zulassung", nur für Thomas und Andrea lesbar): eine online reservierte
    Wunschkennzeichen-Reservierung ist drei Monate gültig (gegenüber einem Monat bei Reservierung im
    Bürgerbüro selbst), Gebühr 14,70 Euro bzw. 11,70 Euro bei Zulassung am Tag der
    Online-Reservierung.
- **Als Maria/Selin im Space „Meldewesen & Ausweise":** nur der erste Teil (Terminvergabe) belegt,
  zur Wunschkennzeichenfrist keine Quelle — die Kfz-Bibliothek dürfen sie nicht lesen.
- **Als Thomas im Space „Kfz-Zulassung":** nur der zweite Teil (Wunschkennzeichenfrist) belegt, zur
  internen Terminvergabe keine Quelle — die interne Bibliothek ist diesem Space nicht zugeordnet.
- **Als Andrea:** beide Teile belegt. Nur ihrem Space „Amtsleitung Bürgerbüro" sind beide
  Bibliotheken zugeordnet.
- **Hinweis:** Thomas liest als Vertretung beide Bibliotheken. In seinem persönlichen Space „Meine
  Dokumente" durchsucht er deshalb ebenfalls beide. Für den Kontrast diesen Space nicht benutzen.
- **Zeigt:** dass die Amtsleitung die über beide Sachgebiete verteilte Antwort in ihrem Arbeitsraum
  vollständig zusammensetzt. Anders als bei Frage 6 unterscheidet sich nicht nur die Vollständigkeit
  einer einzelnen Quelle, sondern es fehlt je nach Konto eine ganze Antworthälfte aus einer anderen
  Bibliothek.

## 8. Bewusst unbeantwortbare Frage

- **Konto und Space:** ein beliebiges Fachkonto in einem seiner Spaces
- **Frage:** „Wie beantrage ich in Rheinfurt eine Fischereierlaubnis?"
- **Erwartete Antwort:** keine Quelle wird genannt, keine wird erfunden. Zu dieser Leistung liegt in
  keiner der sieben Bibliotheken etwas vor (geprüft: kein Treffer für „Fischer" im gesamten Korpus,
  Stand 27.09.2026). Einen eigenen Verweigerungsmodus gibt es dafür nicht (mit #697 verworfen). Die
  Belegvalidierung greift, sobald ein Beleg tatsächlich ungültig wäre; hier bleibt die Kontextmenge
  schlicht leer. Das zeigt nur ein echtes Embedding-Modell, siehe „Verifikationsgrundlage".
- **Zeigt:** dass OPAA bei fehlendem Wissen nichts erfindet — die Umkehrung des ersten
  Vorführ-Moments.

**Neunte Frage mit tatsächlich ungültigem Beleg:** Ein Szenario, in dem die Suche einen Treffer
liefert, dessen Beleg sich als ungültig herausstellt (statt schlicht keinen Treffer zu liefern), ließ
sich beim Konstruieren dieses Drehbuchs nicht reproduzierbar herstellen — die Belegvalidierung aus
#697 prüft rein deterministisch, ob eine im Antworttext genannte Fundstelle tatsächlich unter den
abgerufenen Chunks war; ein synthetischer Korpus ohne absichtlich widersprüchliche Inhalte produziert
diesen Fall nicht von selbst. Bleibt offen für eine spätere, gezielt konstruierte Ergänzung.

---

## Vorführschritte zu Gruppen, Prompts, Ordnern, Fachformaten, Chats, leeren Spaces und Katalog

Die Schritte A und B ändern Rechte. Beide enden deshalb mit dem Zurücksetzen. Ohne es fehlt danach:

- nach A Thomas' Zugriff als Vertretung: die Gegenfälle zu Frage 5 und 7 sowie die internen
  Dienstanweisungen in Schritt D,
- nach B die Kfz-Quellen: Frage 4 und die Kfz-Hälfte von Frage 7.

Für A und B lohnen sich zwei Browserfenster, eines davon privat, damit zwei Konten gleichzeitig
angemeldet sind.

**Auf der öffentlichen Instanz opaa.ewerlin.com** wirkt jede Rechteänderung sofort für alle
Besucher und bleibt bestehen; die Instanz wird nachts nur aktualisiert, nicht zurückgesetzt. Zwei
gleichzeitige Vorführungen von Schritt A stören sich deshalb gegenseitig. Unterbleibt das
Zurücksetzen von A, stellt nur ein erneuter Seed durch den Maintainer die Gruppenmitgliedschaft
wieder her. Schritt B und die Verwaltungsteile (alles unter „Admin“) kann dort nur der Maintainer
vorführen; alle anderen nutzen dafür den lokalen Stack.

### A. Interne Gruppe: Das Recht hängt an der Mitgliedschaft

- **Konten:** `maria.weber` als Verantwortliche der internen Gruppe „Vertretung Meldewesen",
  `thomas.klein` als ihr einziges Mitglied
- **Ausgangslage:** Thomas hat auf „Interne Dienstanweisungen Meldewesen" kein eigenes Recht. Er liest
  sie ausschließlich über die Gruppe und ist nur über sie Mitglied des Space „Meldewesen & Ausweise".
- **Klickweg:**
  1. Als Thomas: Der „Katalog" zeigt die Kachel „Interne Dienstanweisungen Meldewesen". Die Space-Auswahl
     („Spaces" oder das Feld „Space" oben in der Space-Spalte) führt „Meldewesen & Ausweise".
  2. Als Maria: Avatar unten links → „Einstellungen" → Reiter „Meine Gruppen". Die Gruppe
     „Vertretung Meldewesen" steht dort mit den Kennzeichen „freigegeben" und „1 Mitglied".
     Aufklappen, dann im Abschnitt „Mitglieder" bei „Thomas Klein" auf „Entfernen" klicken.
  3. Als Thomas die Seite neu laden.
- **Erwartetes Ergebnis:**
  - Im „Katalog" fehlt „Interne Dienstanweisungen Meldewesen".
  - In der Space-Auswahl fehlt „Meldewesen & Ausweise".
  - Frage 5 liefert jetzt auch in „Meine Dokumente" keine Quelle aus der internen Bibliothek.
  - Kein Recht an der Bibliothek und keine Space-Mitgliedschaft wurde angefasst; weggefallen ist nur
    die Mitgliedschaft in der Gruppe.
- **Gegenprobe in der Verwaltung** (`demo-admin`, „Admin" → „Gruppen"): „Vertretung Meldewesen" hat
  die Herkunft „Intern" und in der Spalte „Verwendung" den Eintrag „1 Bibliothek · 1 Space". Auf
  opaa.ewerlin.com nur durch den Maintainer vorführbar (das Passwort von `demo-admin` ist dort
  rotiert); sonst am lokalen Stack.
- **Zurücksetzen:** als Maria im selben Abschnitt „Mitglieder" im Feld „Person suchen …" Thomas Klein
  wählen und „Mitglied hinzufügen" klicken.
- **Zeigt:** Die Fachseite pflegt ihre Vertretung selbst, ohne Systemverwaltung. Ein Recht über eine
  Gruppe endet mit der Mitgliedschaft, ohne dass jemand es einzeln entziehen muss.

### B. Anbietergruppe aus Keycloak: Herkunft, Verzeichnisabgleich, Recht über das Verzeichnis

- **Wer vorführen kann:** Auf opaa.ewerlin.com nur der Maintainer: Das Passwort von `demo-admin` ist
  dort nach jedem Seed rotiert, und den Keycloak-Adminzugang hat nur er. Alle anderen führen
  Schritt B am lokalen Stack vor.
- **Konten:** `demo-admin`, dazu `thomas.klein` zur Kontrolle und die Keycloak-Adminkonsole. Lokal
  läuft sie unter <http://localhost:8180/admin>, Anmeldung mit `admin`/`admin` (Vorgabe in
  `docker-compose.yml`).
- **Ausgangslage:** Die Keycloak-Gruppen „Bürgerbüro Rheinfurt", „Meldewesen" und „Kfz-Zulassung"
  kommen über den Verzeichnisabgleich des Anbieters „Verzeichnisdienst" nach OPAA. Was an ihnen
  hängt, hängt allein an ihnen: Thomas liest „Leistungen Kfz-Zulassung" nur über „Kfz-Zulassung",
  „Meldewesen" ist Eigentümerin der „Leistungen Meldewesen & Ausweise", und „Bürgerbüro Rheinfurt"
  bringt alle fünf Konten in den Space „Infotheke Bürgerbüro".
- **Klickweg, Herkunft:** „Admin" → „Gruppen".
  - Die drei Keycloak-Gruppen haben die Herkunft „Verzeichnisdienst", die internen Gruppen die
    Herkunft „Intern". Unter dem Namen steht bei Keycloak-Gruppen der Quellpfad aus dem Verzeichnis,
    etwa `/Kfz-Zulassung`.
  - Das Info-Symbol neben „Verzeichnisdienst" erklärt die Herkunft: „OPAA liest Gruppen und
    Mitglieder von „Verzeichnisdienst“ selbst aus, zurzeit stündlich. Änderungen dort gelten hier
    nach dem nächsten Abgleich, auch für Personen, die sich nicht anmelden."
  - Die Spalte „Verwendung" zeigt für „Bürgerbüro Rheinfurt" einen Space, für „Meldewesen" Space
    und Eigentum, für „Kfz-Zulassung" eine Bibliothek.
  - Der Filter „Herkunft" trennt „Intern" und „Alle Identitätsanbieter".
- **Klickweg, Abgleich:** „Admin" → „Verzeichnisabgleich".
  - Die Karte „Verzeichnisdienst" zeigt den Stand „Angewendet", den Schalter „Verzeichnisabgleich
    eingeschaltet" und das Intervall 60 Minuten.
  - Darunter steht der hinterlegte Verzeichniszugang (KEYCLOAK, Realm opaa, Dienstkonto
    `opaa-directory`).
  - „Trockenlauf" meldet „Trockenlauf - keine Änderung." und „Mitgliedschaften: +0 / −0".
- **Klickweg, Rechteänderung im Verzeichnis:**
  1. In der Keycloak-Adminkonsole (englische Oberfläche) im Realm `opaa` den Benutzer
     `thomas.klein` öffnen und auf dem Reiter „Groups" die Gruppe „Kfz-Zulassung" verlassen
     („Leave").
  2. In OPAA auf „Trockenlauf" klicken: „Mitgliedschaften je Gruppe" nennt „Kfz-Zulassung", entfernt
     Thomas Klein. Der Anteil liegt unter der Schwelle von 30 %.
  3. Auf „Jetzt abgleichen" klicken: Stand „Angewendet", „Mitgliedschaften: +0 / −1".
- **Erwartetes Ergebnis:**
  - Als Thomas (neu laden) fehlt im „Katalog" die Bibliothek „Leistungen Kfz-Zulassung".
  - Frage 4 liefert keine Quelle mehr aus `008_wunschkennzeichen.txt`.
  - In OPAA wurde kein Recht angefasst.
- **Zurücksetzen (Pflicht):** in Keycloak Thomas wieder in „Kfz-Zulassung" aufnehmen („Join
  Group"), in OPAA „Jetzt abgleichen" klicken. Ohne den Klick holt der stündliche Abgleich es erst
  später nach. Unterbleibt dagegen die Rückgabe in Keycloak, macht der stündliche Abgleich den
  Verlust dauerhaft: Frage 4 und die Kfz-Hälfte von Frage 7 fallen für alle aus. Auch ein erneuter
  Seed stellt das nicht wieder her, denn er ändert keine Keycloak-Mitgliedschaften, und ein Keycloak
  mit eigenem Volume liest den Realm-Export nicht erneut.
- **Zeigt:** Rechte folgen dem Verzeichnis der Organisation. Wer dort die Abteilung wechselt, verliert
  das Leserecht der alten Abteilung mit dem nächsten Abgleich, auch ohne sich neu anzumelden.

### C. Prompt-Bibliothek: Slash-Befehl, Variablenformular, Nachweis im Verlauf

- **Konto und Space:** `maria.weber` im Space „Meldewesen & Ausweise"
- **Klickweg:**
  1. „Spaces" → „Meldewesen & Ausweise"; es öffnet sich ein neuer Chat.
  2. Im Eingabefeld „/" tippen. Die Liste „Prompt einsetzen" zeigt die Gruppe „Textbausteine
     Bürgerbüro" mit vier Einträgen, jeder mit dem Kennzeichen „mit Formular".
  3. `/gebuehrenauskunft-personalausweis · Gebührenauskunft Personalausweis` wählen. Es öffnet sich
     der Dialog „Prompt einsetzen: Gebührenauskunft Personalausweis" mit den Auswahlfeldern „Alter der
     antragstellenden Person" (vorbelegt „24 Jahre und älter") und „Anlass" (vorbelegt „Neuausstellung
     nach Ablauf der Gültigkeit").
  4. „Alter der antragstellenden Person" auf „unter 24 Jahre" und „Anlass" auf „Erstausstellung"
     stellen, dann „Einsetzen" klicken.
  5. Der fertige Text steht im Eingabefeld. Darüber erscheint der Chip „Prompt: Gebührenauskunft
     Personalausweis". Absenden.
- **Erwartetes Ergebnis:**
  - Über der gestellten Frage steht im Verlauf die Zeile „Prompt: Gebührenauskunft Personalausweis".
    Sie bleibt auch nach dem Neuladen des Chats stehen.
  - Die Antwort nennt 26,20 Euro wie Frage 1 und ist aus der Leistungsbeschreibung bzw. der Satzung
    belegt.
- **Vorlagen nur für die Amtsleitung:**
  - `andrea.vogt` im Space „Amtsleitung Bürgerbüro": „/" zeigt zusätzlich die Gruppe „Vorlagen
    Amtsleitung" mit `/wochenbericht-dezernentin` und `/stellungnahme-hauptausschuss`.
    Der „Katalog" führt für sie beide Prompt-Bibliotheken; „Vorlagen Amtsleitung" trägt das
    Kennzeichen „nur Sie".
  - `andrea.vogt` im Space „Dienstbesprechung Bürgerbüro": „/" zeigt nur die Textbausteine. Andrea
    darf die Vorlagen lesen, dieser Space führt sie aber nicht — und was ein Space nicht führt, bietet
    sein Chat nicht an.
  - `thomas.klein`: Der „Katalog" führt die Textbausteine und seine eigenen „Arbeitshilfen
    Kfz-Zulassung", aber keine „Vorlagen Amtsleitung". Im Space „Kfz-Zulassung" bietet „/" beide
    Bibliotheken an, darunter `/auskunft-sonderkennzeichen` mit der Auswahl der Kennzeichenart.
  - `selin.kaya` im Space „Dienstbesprechung Bürgerbüro": „/" zeigt dieselben Textbausteine.
- **Zeigt:** Prompts gehören wie Wissen einer Person, werden freigegeben und Spaces zugeordnet. Der
  Chat eines Space bietet nur dessen Prompts an. Im Chat bleibt nachvollziehbar, aus welchem Prompt
  eine Frage entstand. Wer nicht lesen darf, bekommt den Prompt weder angezeigt noch kann er ihn
  benutzen; die API lehnt ihn ab, ebenso einen Prompt aus einer Bibliothek, die dem Space nicht
  zugeordnet ist.

### D. Ordner: Aktenplan, Jahrgang und Gremium, Anhänge an den Ratsvorlagen

- **Konto:** `maria.weber`. Die Ratsinformationen sind für „Alle Konten" freigegeben, die internen
  Dienstanweisungen lesen Maria, Selin, Andrea und über die Vertretung Thomas.
- **Klickweg, Aktenplan:**
  1. „Katalog" → „Interne Dienstanweisungen Meldewesen", Reiter „Dokumente". Die Wurzel zeigt die
     Aktenplan-Ordner `01 Melderecht` bis `06 Aus- und Fortbildung`.
  2. `05 Bürgerbüro` → `04 Mobiles Bürgerbüro` öffnen. Der Pfad über der Liste lautet „Wurzel / 05
     Bürgerbüro / 04 Mobiles Bürgerbüro".
  3. Das Rundschreiben `27_rundschreiben-mobiles-buergerbuero-nordfeld.eml` hat den Aufklapper
     „1 Anhang". Er zeigt `einsatzplan-mobiles-buergerbuero-2026-q3.pdf` mit dem Kennzeichen „Anhang".
- **Klickweg, Ratsinformationen:**
  1. „Katalog" → „Ratsinformationen Stadt Rheinfurt", Reiter „Dokumente". Die Wurzel zeigt die
     Jahrgänge `2024`, `2025` und `2026`, darin je `Bauausschuss`, `Hauptausschuss` und `Stadtrat`.
  2. `2026` → `Stadtrat` öffnen. Die Vorlage `2026-02-24-stadtrat-vorlage-feuerwache-sued.eml` hat den
     Aufklapper „2 Anhänge": Lageplan-Erläuterung und Kostenaufstellung.
  3. Dasselbe in `2024` → `Bauausschuss` mit der Brunnen-Vorlage und einer Anlage.
- **Bekannter Anzeigefehler:** In den Ratsinformationen steht eine Anlage zusätzlich als eigene Zeile
  über ihrer Mail (#2031). Bei der Vorführung auf die Zeile der Mail zeigen.
- **Fachfrage an eine Anlage** (Konto `thomas.klein` im Space „Kfz-Zulassung" oder jedes andere
  Fachkonto): „Wie viel entfällt beim Neubau der Feuerwache Süd auf die Kostengruppe 300 Bauwerk,
  Baukonstruktionen?"
  - Erwartet: 7,6 Millionen Euro, aus
    `2026-02-24-stadtrat-vorlage-feuerwache-sued-anlage-2-kostenaufstellung.pdf`.
  - Die Vorlage selbst nennt nur den Kostenrahmen von 14,5 Millionen Euro.
- **Zeigt:** OPAA übernimmt die Ablage so, wie die Verwaltung sie führt: Aktenplan beim Hochladen,
  Jahrgang und Gremium aus dem Objektspeicher. Eine Mail kommt mit ihren Anlagen, und jede Anlage ist
  ein eigenes, belegbares Dokument.

### E. Fachfragen aus Tabelle, Mail-Anhang und CSV

Drei Fragen, die sich nur aus einem Nicht-Text-Format beantworten lassen (#2028):

| Format | Konto und Space | Frage | Erwartete Antwort | Belegt aus |
|---|---|---|---|---|
| XLSX | `thomas.klein` in „Kfz-Zulassung" | „Mit welchem Buchungsschlüssel erfasst die Kasse des Bürgerbüros die Reservierung eines Wunschkennzeichens?" | BB-3311, Tarifstelle 4.1, 14,70 Euro | `20_gebuehrenuebersicht-buergerbuero.xlsx` („Satzungen & Gebührenordnungen") |
| PDF-Anhang einer Mail | `selin.kaya` in „Meldewesen & Ausweise" | „Wer begleitet den Auftakttermin des mobilen Bürgerbüros in Nordfeld, und wann ist er?" | Mittwoch, 8. Juli 2026; Maria Weber und Andrea Vogt (Leitung) | `einsatzplan-mobiles-buergerbuero-2026-q3.pdf`, Anhang von `27_rundschreiben-mobiles-buergerbuero-nordfeld.eml` („Interne Dienstanweisungen Meldewesen") |
| CSV | `selin.kaya` in „Meldewesen & Ausweise" | „Zu welcher Uhrzeit hält das mobile Bürgerbüro im Bürgertreff Weststadt seinen Sprechtag, und in welchem Raum?" | jeden dritten Donnerstag im Monat, 14:00 bis 17:30 Uhr, Saal im ersten Obergeschoss | `047_sprechtage-mobiles-buergerbuero.csv` („Leistungen Meldewesen & Ausweise") |

- **Zur XLSX-Frage:** Betrag und Tarif nennt auch die Satzung, den Buchungsschlüssel nur die
  Übersicht. Für Maria, Selin und Andrea gibt es dieselbe Frage zum vorläufigen Personalausweis:
  BB-3213, Tarifstelle 1.3, 11,50 Euro.
- **Zur Anhang-Frage:** Das Datum nennt auch der Nachrichtentext, wer den Termin begleitet, steht
  nur im PDF-Anhang. Fragen nach einer einzelnen Zeile mitten in der Einsatztabelle (etwa „Wer ist am
  12. August 2026 in Nordfeld eingeteilt?") beantwortete das Modell der Demo-Instanz trotz korrekt
  gefundener Fundstelle wiederholt mit einer verrutschten Zeile; sie eignen sich nicht für die
  Vorführung. Für den
  Nachrichtentext selbst: „Wann und wo holt man den Bürgerkoffer für einen Sprechtag ab, und bis wann
  muss er zurück?" Antwort: spätestens eine Stunde vor Beginn bei der IT-Leitstelle; Rückgabe nach
  dem Sprechtag, nach einem Nachmittagstermin wie in Weststadt bis 9:00 Uhr am folgenden Werktag.
- **Zur CSV-Frage:** Den Wochentag nennt auch die Niederschrift des Hauptausschusses vom 11.02.2025,
  Uhrzeit und Raum nur die Tabelle.
- **Gegenprobe zur Anhang-Frage:** Thomas stellt sie im Space „Kfz-Zulassung". Er bekommt keine Quelle
  aus Mail oder Anhang, denn dem Space ist die interne Bibliothek nicht zugeordnet. Die CSV liegt in
  einer Bibliothek, die er gar nicht lesen darf.
- **Zeigt:** Tabellen, Mails und ihre Anhänge sind für Suche und Beleg gewöhnliche Dokumente. Das
  Format entscheidet nicht darüber, ob eine Antwort belegt ist.

### F. Viele Chats: Chatliste, Suche, Archiv und ein langer Verlauf

- **Konto und Space:** `andrea.vogt` im Space „Amtsleitung Bürgerbüro"
- **Ausgangslage:** Der Seed hat dort 81 vorbereitete Chats eingespielt, verteilt über die acht
  Wochen vor dem Seed-Lauf (#2071, [`../../demo/README.md`](../../demo/README.md), Abschnitt
  „Vorbereitete Chats"). Fünf sind angeheftet, elf archiviert; „Jahresbericht Bürgerbüro 2026
  vorbereiten" hat 32 Runden. Alle Antworten sind belegt – aus Satzungen, Leistungsbeschreibungen,
  internen Dienstanweisungen, Pressemitteilungen und Ratsinformationen.
- **Klickweg, Chatliste:**
  1. „Spaces" → „Amtsleitung Bürgerbüro". Die Seitenleiste zeigt oben die Gruppe „Angeheftet" mit
     fünf Chats, darunter die übrigen aktiven Chats, zuletzt verwendete zuerst.
  2. Einen Chat öffnen, etwa „Auskunftssperre bei häuslicher Gewalt": Die Antworten tragen Fußnoten,
     „Belege anzeigen" öffnet die zitierten Dokumente mit Dateiname und Bibliothek.
  3. Über „Aktionen für Chat …" einen nicht angehefteten Chat, etwa „Trauung im Standesamt“,
     anheften: Er erscheint unter „Angeheftet“. Über dasselbe Menü wieder „Lösen“.
  4. Über „Aktionen für Chat …" denselben Chat „Archivieren“: Er verschwindet aus der Seitenleiste.
     Auf der Seite „Chats durchsuchen“ (siehe unten) im Reiter „Archiv“ steht er jetzt als zwölfter
     Eintrag.
- **Klickweg, Suche und Archiv:**
  1. In der Seitenleiste auf „Chats durchsuchen" klicken. Die Seite „Chats in „Amtsleitung
     Bürgerbüro“" zeigt die Reiter „Aktiv (69)" und „Archiv (12)" – im Ausgangszustand, ohne den in
     Schritt 4 archivierten Chat, „Aktiv (70)" und „Archiv (11)".
  2. Im Suchfeld „Auskunftssperre" eingeben. Die Treffer kommen aus Titeln, Fragen und Antworten,
     darunter auch „Amtshilfeersuchen anderer Meldebehörden" aus dem Archiv.
  3. Reiter „Archiv": die archivierten Chats mit „Archiviert am" – nach Schritt 4 oben zwölf statt
     elf.
- **Klickweg, langer Verlauf:** „Jahresbericht Bürgerbüro 2026 vorbereiten" (angeheftet) öffnen und
  durch die 32 Runden scrollen – vom Überblick über die Ratsbeschlüsse bis zur Gliederung und den
  Lücken des Berichts. Bei einer Antwort mit Tabelle, etwa den Kennzahlen, „Antwort kopieren"
  zeigen.
- **Hinweis:** Die eingespielten Chats haben keine Gesprächsnotiz; sie entsteht erst, wenn im Chat
  eine neue Frage gestellt wird. Eine neue Frage ist mit dem Modell der Instanz jederzeit möglich
  und reiht den Chat in der Liste nach oben.
- **Zurücksetzen (Pflicht):** den in Schritt 4 archivierten Chat zurückholen – im Reiter „Archiv“
  beim Chat „Trauung im Standesamt“ im Aktionsmenü „Zurückholen“ wählen (oder ihn auswählen und die
  Sammelaktion „Zurückholen“ nutzen). Danach zeigen die Reiter wieder „Aktiv (70)“ und
  „Archiv (11)“, und „Angeheftet“ hat fünf Einträge. Ein erneuter Seed hilft hier nicht: Er setzt nur
  fehlende Markierungen, die er selbst vorgibt, nimmt aber keine Archivierung und kein zusätzliches
  Anheften zurück. Auf der öffentlichen Instanz teilen sich alle Besuchenden Andreas Konto; ohne das
  Zurücksetzen stimmen die Zahlen dieses Schritts bei der nächsten Vorführung nicht mehr.
- **Zeigt:** Auch nach Wochen mit vielen Gesprächen bleibt die eigene Chatablage beherrschbar:
  Anheften, Archiv und Volltextsuche über Titel, Fragen und Antworten. Chats bleiben privat – kein
  anderes Konto sieht Andreas Chats, auch nicht die Systemverwaltung.

### G. Space ohne Wissen: Hinweis statt stiller Leerlauf

- **Konto:** `maria.weber`
- **Klickweg:**
  1. „Spaces" → „Neuer Space". Name „Vorführung leerer Space", alle weiteren Schritte mit „Weiter"
     übergehen — keine Mitglieder, keine Datenquellen — und „Space anlegen".
  2. Den neuen Space in der Space-Spalte wählen; es öffnet sich ein neuer Chat. Über dem Eingabefeld
     steht „Diesem Space ist kein Wissen zugeordnet." mit der Schaltfläche „Wissen zuordnen".
  3. Die Gebührenfrage aus Frage 1 stellen. Die Antwort trägt den Hinweis „Diesem Space ist kein
     Wissen zugeordnet. Diese Antwort stützt sich auf keine Dokumente." und keine Belege.
  4. „Wissen zuordnen" klicken. Es öffnet sich der Reiter „Wissen" der Space-Einstellungen. Dort
     „Leistungen Meldewesen & Ausweise" zuordnen.
  5. Zurück in den Space, einen neuen Chat beginnen und dieselbe Frage stellen: Die Antwort nennt
     26,20 Euro, belegt aus der Leistungsbeschreibung.
- **Zurücksetzen:** in den Space-Einstellungen, Reiter „Stammdaten", im Gefahrenbereich „Space
  löschen". Auf der öffentlichen Instanz bleibt ein nicht gelöschter Space im Konto von Maria stehen.
- **Zeigt:** Ein Space enthält genau, was ihm zugeordnet ist — auch der persönliche. Ein Space ohne
  Wissen antwortet nie so, als wäre die Antwort belegt; er sagt, was fehlt, und führt mit einem
  Klick zur Zuordnung. Wer nur Mitglied ist, liest stattdessen, wer zuordnen kann.

### H. Katalog: Eigentum, Sichtbarkeit, Favoriten und Zuordnung

Wem welches Asset gehört, was öffentlich ist, wer welche Favoriten hat und welcher Space was
zugeordnet hat, steht als Übersicht in
[`../features/demo-instance.md`](../features/demo-instance.md#eigentum-sichtbarkeit-favoriten-und-zuordnung).

**Stand der Oberfläche:** Die Seed-Daten dieses Schritts gibt es, der Seed setzt sie. Den Stern und
den Filter „nur Favoriten" im Katalog, Eigentum und Sichtbarkeit an der Kachel sowie den Hinweis auf
nicht lesbare Zuordnungen baut Epic #2070 noch (#2095, #2094, #2097). Die Beschriftungen unten folgen
der Spezifikation und sind nach dem Neuaufsetzen der Demo gegenzuprüfen.

Für die Teile 1 und 3 lohnen sich zwei Browserfenster, eines davon privat.

- **1. Favoriten sind persönlich:**
  1. Als `maria.weber` den „Katalog" öffnen. Oben stehen ihre vier Favoriten: „Interne
     Dienstanweisungen Meldewesen", „Leistungen Meldewesen & Ausweise", „Satzungen &
     Gebührenordnungen" und „Textbausteine Bürgerbüro".
  2. Als `thomas.klein` dasselbe: Oben stehen „Leistungen Kfz-Zulassung", „Ratsinformationen Stadt
     Rheinfurt" und seine „Arbeitshilfen Kfz-Zulassung".
  3. Als `selin.kaya`: Sie hat keine Favoriten. Der Filter „nur Favoriten" bleibt leer, der Katalog
     ist nach Name geordnet.
  - **Zeigt:** Jede Person ordnet ihren Katalog selbst. Niemand sieht die Favoriten anderer, auch
    nicht als Zahl am Asset.
- **2. Eigentum und Sichtbarkeit an der Kachel** (Konto `maria.weber`):
  - „Satzungen & Gebührenordnungen" gehört Andrea Vogt und ist für alle Konten freigegeben.
  - „Leistungen Meldewesen & Ausweise" gehört der Keycloak-Gruppe „Meldewesen" und ist
    eingeschränkt. Maria hat die Bibliothek für die Gruppe angelegt; Selin verwaltet sie als
    Gruppenmitglied mit.
  - „Interne Dienstanweisungen Meldewesen" gehört Maria selbst und ist eingeschränkt.
  - Als `thomas.klein` fehlen „Leistungen Meldewesen & Ausweise" und die „Vorlagen Amtsleitung" im
    Katalog ganz: Was eine Person nicht lesen darf, sieht sie nirgends.
  - **Zeigt:** Wissen gehört Fachleuten und Fachgruppen, nicht der Systemverwaltung. Sichtbar ist
    genau, was lesbar ist.
- **3. Nicht alles Zugeordnete ist für jedes Mitglied lesbar:**
  1. Als `thomas.klein` den Space „Dienstbesprechung Bürgerbüro" wählen und die Space-Einstellungen
     öffnen, Reiter „Wissen". Er sieht vier Bibliotheken und den Hinweis „Nicht alle zugeordneten
     Inhalte sind für Sie lesbar." Ohne Namen und ohne Zahl: Ihm fehlen die „Leistungen
     Meldewesen & Ausweise".
  2. Als `maria.weber` im selben Space: derselbe Hinweis. Ihr fehlen die „Leistungen
     Kfz-Zulassung".
  3. Als `andrea.vogt` (Eigentümerin) im selben Space: alle fünf Bibliotheken, kein Hinweis.
  4. Zur Gegenprobe Frage 4 als Maria in „Dienstbesprechung Bürgerbüro" stellen: keine Quelle aus
     `008_wunschkennzeichen.txt`. Die Zuordnung gewährt kein Leserecht.
  - Dasselbe gilt für Thomas im Space „Meldewesen & Ausweise": Er ist dort über die Vertretung
    Mitglied, liest die Leistungen Meldewesen aber nicht.
- **4. Ein Space nur mit öffentlichem Wissen:**
  - Als ein beliebiges Konto den Space „Infotheke Bürgerbüro" wählen. Mitglied sind alle fünf Konten
    über die Keycloak-Gruppe „Bürgerbüro Rheinfurt", Eigentümerin ist Selin Kaya.
  - Zugeordnet sind nur „Satzungen & Gebührenordnungen" und „Ratsinformationen Stadt Rheinfurt".
    Kein Mitglied sieht den Hinweis aus Teil 3.
  - Die Gebührenfrage aus Frage 1 liefert dort für jedes Konto dieselbe Antwort aus der Satzung. Die
    Aktualitätsfrage aus Frage 3 bleibt ohne Pressemitteilung.
- **Zurücksetzen:** Wer einen Favoriten setzt oder entfernt, ändert nur die eigene Ordnung. Ein
  erneuter Seed setzt die Favoriten des Profils wieder, entfernt aber keinen zusätzlich gesetzten.
  Auf der öffentlichen Instanz teilen sich alle Besuchenden die Konten; einen in der Vorführung
  gesetzten Favoriten deshalb wieder entfernen.
- **Zeigt:** Eigentum, Sichtbarkeit, persönliche Ordnung und Space-Zuordnung sind vier getrennte
  Dinge. Nur die Leserechte entscheiden, was eine Person findet. Der Space bestimmt, wo gesucht wird.

---

## Zugehörige Dokumentation

- [`../../demo/README.md`](../../demo/README.md) — Installation mit einem Befehl, Nutzerkonten mit
  Passwörtern, Gruppen, Prompt-Bibliotheken und vorbereitete Chats der Demo, öffentliche Instanz,
  Korpus-Aktualisierung
- [`../features/demo-instance.md`](../features/demo-instance.md) — Konzept: Behördenlandschaft,
  Bibliotheken, Formate, Quellen und Lizenzen, Rechtemodell mit internen und Keycloak-Gruppen
- [`../handbuch/deployment.md`](../handbuch/deployment.md), Abschnitt „Härtung für erreichbare
  Deployments" — zwingend vor jedem über `localhost` hinaus erreichbaren Rollout dieser Demo
- [`../../e2e/README.md`](../../e2e/README.md), Abschnitt „Demo-Smoke (#232)" — der automatisierte
  Nachweis der Frage-1-Invariante
- [`MESSAGING.md`](./MESSAGING.md) — die Botschaften, aus denen dieses Drehbuch abgeleitet ist
