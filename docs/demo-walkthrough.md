# Demo-Instanz „Stadt Rheinfurt" vorführen

Anwenderdokumentation für Epic #708: mit einem Befehl installieren, sich anmelden, ein Drehbuch aus
acht vorbereiteten Fragen abspielen. Das Konzept dahinter — Behördenlandschaft, Bibliotheken,
Berechtigungsmatrix, Quellen und Lizenzen — steht in
[`features/demo-instance.md`](./features/demo-instance.md) und wird hier **nicht wiederholt**. Diese
Seite ist die praktische Anleitung dafür: installieren, anmelden, fragen.

Technische Details des Compose-Stacks und des Seed-Skripts (Ports, Idempotenz, Ratenbegrenzung,
Zielprüfung ausgehender Abrufe) stehen in [`../demo/README.md`](../demo/README.md); diese Seite
verweist darauf, statt sie zu duplizieren.

---

## Installation

Voraussetzung: Docker und Docker Compose, ein Checkout dieses Repositorys, Python 3 mit `pip` für den
Seed-Lauf.

### 1. Umgebung konfigurieren

```bash
cp .env.docker.example .env.docker
```

In der eigenen `.env.docker` zusätzlich setzen (siehe [`../demo/README.md`, „Compose-Stack
starten"](../demo/README.md#compose-stack-starten-229) für die Begründung jeder einzelnen Variable):

```env
SPRING_PROFILES_ACTIVE=docker,oidc
OPAA_INITIAL_ADMIN_EMAIL=admin@stadt-rheinfurt.example
OPAA_INDEXING_TARGET_VALIDATION_ALLOWLIST=demo-corpus,presse.stadt-rheinfurt.example
OPAA_CSP_CONNECT_SRC_EXTRA=http://localhost:8180
OPAA_PGVECTOR_DIMENSIONS=768
OPAA_UPLOAD_THREAD_POOL_QUEUE_CAPACITY=30
```

Ohne `OPAA_CSP_CONNECT_SRC_EXTRA` blockiert die Content-Security-Policy des Frontends die
Keycloak-Anmeldung im Browser still (#409/#670) — der Seed selbst läuft trotzdem durch, weil er
Keycloak direkt anspricht, aber niemand kann sich danach über die Oberfläche anmelden.

`OPAA_UPLOAD_THREAD_POOL_QUEUE_CAPACITY=30` hebt die Standard-Warteschlange von
`uploadTaskExecutor` (Default 20, `opaa.upload.thread-pool`) an: Der Seed lädt die 26 Dokumente
der Bibliothek „Interne Dienstanweisungen Meldewesen" sequentiell und ohne Pause hoch, und mit
lokal betriebenen Ollama-Embeddings (langsamer als ein Cloud-Anbieter) füllt sich die Warteschlange
eher als mit einem schnellen Anbieter — ohne die Anhebung kann der letzte Upload oder die letzten
zwei mit „Die Verarbeitung ist derzeit ausgelastet - bitte später erneut versuchen." fehlschlagen
(siehe [`../demo/README.md`, „Seed ausführen"](../demo/README.md#seed-ausführen-712) für den
Umgang, falls das trotzdem passiert).

`OPAA_PGVECTOR_DIMENSIONS=768` ist mit den unveränderten Vorlagenwerten **zwingend**: Voreingestellt
bleiben lokal betriebene Modelle über Ollama (`OPAA_AI_CHAT_PROVIDER=ollama`,
`OPAA_AI_EMBEDDING_PROVIDER=ollama`) mit dem Embedding-Modell `nomic-embed-text`, das 768
Dimensionen liefert — `.env.docker.example` setzt `OPAA_PGVECTOR_DIMENSIONS` dagegen auf den
Stack-Default 1536 (siehe [`deployment.md`, Zeile zu `OPAA_PGVECTOR_DIMENSIONS`](./deployment.md#alle-umgebungsvariablen)
für dieselbe Kopplung auf der öffentlichen Instanz). Ohne die Anpassung schlägt der letzte
Seed-Schritt fehl, weil die Vektorbreite nicht zum Modell passt. Der Wert muss zum jeweils
verwendeten Embedding-Modell passen; eine nachträgliche Änderung an einer bereits laufenden Instanz
erfordert `docker compose down -v` und eine vollständige Neuindizierung (siehe
[`deployment.md`, „Was ein Update mit dem Index macht"](./deployment.md#was-ein-update-mit-dem-index-macht)).
Wer stattdessen einen openai-kompatiblen Anbieter nutzt, setzt `OPAA_OPENAI_BASE_URL` zusätzlich
und passt `OPAA_PGVECTOR_DIMENSIONS` auf dessen Embedding-Dimension an (siehe
[`deployment.md`, „Erforderliche Variablen"](./deployment.md#erforderliche-variablen)).

### 2. Stack starten

```bash
docker compose --profile demo up
```

Startet zusätzlich zu `postgres`/`backend`/`frontend`: `keycloak` (Anmeldung, seit #712 auch ohne
separates `--profile oidc`), `demo-corpus` (drei `HTTP_DIRECTORY`-Bibliotheken) und `demo-presse`
(`RSS_FEED`-Bibliothek). Details je Service: [`../demo/README.md`](../demo/README.md).

Warten, bis `backend` und `keycloak` bereit sind (`docker compose logs -f backend`, Zeile
„Started OpaaApplication").

### 3. Seed ausführen

```bash
cd demo/seed
pip install -r requirements.txt
python seed.py --profile demo
```

Der Seed richtet über die öffentliche API alle vier Demo-Nutzer plus das Admin-Konto ein, legt die
vier Spaces und fünf Wissensbibliotheken an, vergibt die Leserechte, lädt die 26 Dokumente der
internen Upload-Bibliothek hoch und stößt die Indizierung der vier konnektorgespeisten Bibliotheken
an. Vollständiger Ablauf, Idempotenz und Fehlerfälle:
[`../demo/README.md`, „Seed ausführen (#712)"](../demo/README.md#seed-ausführen-712).

**Wie lange dauert die Erstindizierung, und wie erkennt man, dass sie fertig ist?** Der Seed selbst
wartet auf jede Indizierung und jeden Upload (Polling gegen `GET
/api/v1/libraries/{libraryId}/indexing/status` bzw. den Dokumentstatus) und bricht mit einer klaren
Fehlermeldung ab, wenn etwas schiefgeht — läuft `seed.py` bis zur Ausgabe „Seed-Profil 'demo'
abgeschlossen." durch, ist die Instanz vollständig gefüllt und durchsuchbar. Bei den 155 Dokumenten
des Korpus (46 + 37 + 19 + 27 in den vier konnektorgespeisten Bibliotheken, 26 Uploads) und lokal
betriebenen Modellen ist mit einigen Minuten zu rechnen, je nach Ollama-Hardware; ein zweiter Lauf
gegen dieselbe Instanz ist idempotent und legt nichts doppelt an.

### 4. Anmelden und loslegen

Frontend: <http://localhost:3000>. Anmeldung über Keycloak mit einem der Konten aus der Tabelle
unten.

---

## Nutzerkonten

Alle Passwörter sind offene **Demo-Werte, keine Secrets** — vor jedem erreichbaren Deployment gemäß
[`deployment.md`, „Härtung für erreichbare Deployments"](./deployment.md#härtung-für-erreichbare-deployments)
zu ersetzen.

| Konto | Rolle im Szenario | Spaces | Lesbare Bibliotheken | Passwort |
|---|---|---|---|---|
| `demo-admin` (admin@stadt-rheinfurt.example) | Systemadministration | eigener Default-Space | richtet ein, besitzt alle fünf Bibliotheken | `RheinfurtDemo!2026` |
| `maria.weber` | Sachbearbeiterin Meldewesen | „Meldewesen & Ausweise" (mit Selin), „Maria Weber – persönlich" (allein) | Leistungen Meldewesen & Ausweise, Satzungen & Gebührenordnungen, Pressemitteilungen, Interne Dienstanweisungen Meldewesen | `RheinfurtDemo!2026` |
| `selin.kaya` | Sachbearbeiterin Meldewesen | „Meldewesen & Ausweise" (mit Maria) | dieselben vier wie Maria | `RheinfurtDemo!2026` |
| `thomas.klein` | Sachbearbeiter Kfz-Zulassung | „Kfz-Zulassung" (allein) | Leistungen Kfz-Zulassung, Satzungen & Gebührenordnungen, Pressemitteilungen | `RheinfurtDemo!2026` |
| `andrea.vogt` | Amtsleitung Bürgerbüro | „Amtsleitung Bürgerbüro" (allein) | alle fünf Bibliotheken | `RheinfurtDemo!2026` |

Die Spalte „Lesbare Bibliotheken" zählt nur explizit vergebene `VIEWER`-Rechte; jeder Nutzer bekommt
beim ersten Login zusätzlich automatisch seinen eigenen Default-Space, der oben nicht eigens
aufgeführt ist. Begründung der Matrix: [`features/demo-instance.md`, „Nutzer, Spaces und
Berechtigungen"](./features/demo-instance.md#nutzer-spaces-und-berechtigungen).

**Der Vorführ-Kern:** Weil die Berechtigungsprüfung Teil der Vektorsuche ist und nicht ein
nachgeschalteter Filter, ist ein für einen Nutzer unzugänglicher Treffer nicht nur unterdrückt,
sondern nie geladen — Thomas' Anfrage nach einer internen Meldewesen-Dienstanweisung durchsucht
diese Bibliothek gar nicht erst, unabhängig davon, wie thematisch treffend ein Chunk daraus wäre.

---

## Öffentliche Demo-Instanz

Unter <https://opaa.ewerlin.com> läuft dieselbe Demo „Stadt Rheinfurt" öffentlich (#230, Epic #708) —
Anmeldung erforderlich, kein anonymer Zugang. Ein Unterschied zur Tabelle oben: **`demo-admin`
funktioniert dort nicht mit dem Passwort `RheinfurtDemo!2026`** — sein Passwort ist nach dem Rollout
bewusst auf einen serverseitig verwahrten Zufallswert rotiert (siehe
[`deployment.md`, „Öffentliche Testinstanz"](./deployment.md#öffentliche-testinstanz)). Die vier
Fach-Demokonten (`maria.weber`, `selin.kaya`, `thomas.klein`, `andrea.vogt`) gelten dort unverändert
mit dem oben dokumentierten Passwort und genügen für das komplette Drehbuch unten — keine der acht
Fragen setzt `demo-admin` voraus.

---

## Drehbuch

Acht vorbereitete Fragen. Bei jeder Frage: als wer anmelden, was fragen, was zu erwarten ist, was sie
zeigt. Die Fragen setzen auf konkreten Inhalten des Rheinfurt-Korpus auf (`demo/corpus/`) — bei einer
Korpus-Aktualisierung (siehe unten) sind sie mit dem neuen Stand gegenzuprüfen.

**Verifikationsgrundlage dieses Drehbuchs:** Gegen einen isolierten Compose-Stack wurde der Seed
ausgeführt und drei Fragen (die Berechtigungs-Doppelfrage aus Frage 5 sowie Frage 6) per
`POST /v1/query` geprüft — mit `ai-stub` (`e2e/ai-stub/server.mjs`, Muster aus #712) anstelle eines
echten Chat-/Embedding-Anbieters. Das bestätigt deterministisch die Berechtigungsgrenze: Ein Konto
ohne `VIEWER`-Recht auf einer Bibliothek erhält nie eine Quelle daraus, unabhängig vom Frageinhalt.
Weil `ai-stub` für jede Eingabe denselben Embedding-Vektor liefert (Kosinus-Ähnlichkeit immer 1,0),
lässt sich damit **keine** inhaltliche Relevanz prüfen — welche der für ein Konto lesbaren
Bibliotheken tatsächlich die thematisch treffenden Quellen liefert, hängt vom echten
Embedding-Modell ab. Die unten dokumentierten erwarteten Antworten und Dateizitate beruhen deshalb
auf manueller Prüfung der tatsächlichen Korpusdateien, nicht auf einem `ai-stub`-Lauf; eine
inhaltliche Relevanzmessung mit einem echten Embedding-Modell ist Aufgabe des Eval-Korpus (Epic
#224), nicht dieser Demo.

### 1. Gebührenfrage

- **Konto:** eines der vier Fachkonten, z. B. `maria.weber`
- **Frage:** „Was kostet ein Personalausweis für eine 22-Jährige?"
- **Erwartete Antwort:** 27,20 Euro (Gebührenrahmen „unter 24 Jahren") — belegt sowohl aus der
  Leistungsbeschreibung `001_personalausweis.md` (Bibliothek „Leistungen Meldewesen & Ausweise") als
  auch aus der Verwaltungsgebührensatzung (`01_verwaltungsgebuehrensatzung.pdf`, Bibliothek
  „Satzungen & Gebührenordnungen") — beide Quellen sind für jedes der vier Fachkonten lesbar.
- **Zeigt:** eine einfache, belegte Auskunft mit einer konkreten Zahl aus einem PDF-Dokument.

### 2. Verfahrensfrage

- **Konto:** `maria.weber` oder `selin.kaya`
- **Frage:** „Welche Unterlagen brauche ich für die Ummeldung?"
- **Erwartete Antwort:** gültiges Ausweisdokument, Wohnungsgeberbestätigung (mit den in
  `024_wohnsitz-anmelden-oder-ummelden.md` benannten Pflichtangaben) — belegt aus der
  Leistungsbeschreibung „Wohnsitz anmelden oder ummelden".
- **Zeigt:** eine mehrteilige Unterlagenliste korrekt aus einem `.md`-Dokument extrahiert.

### 3. Aktualitätsfrage

- **Konto:** ein beliebiges Fachkonto
- **Frage:** „Wann ist das Bürgerbüro wegen des Stadtfests geschlossen?"
- **Erwartete Antwort:** Freitag, 19. Juni 2026, ganztägig; ab Montag, 22. Juni 2026, wieder reguläre
  Öffnungszeiten — belegt aus der Pressemitteilung `buergerbuero-geschlossen-stadtfest.html`
  (Bibliothek „Pressemitteilungen Stadt Rheinfurt", `RSS_FEED`).
- **Zeigt:** dass eine tagesaktuelle Meldung aus dem RSS-Feed genauso durchsucht wird wie eine
  Leistungsbeschreibung — der Konnektortyp ist für die Antwort unsichtbar.

### 4. Kfz-Frage

- **Konto:** `thomas.klein`
- **Frage:** „Kann ich mein Wunschkennzeichen online reservieren?"
- **Erwartete Antwort:** ja, über das Internetangebot der Kfz-Zulassungsbehörde; die
  Online-Reservierung ist drei Monate gültig (gegenüber einem Monat bei Reservierung im Bürgerbüro),
  Gebühr 14,10 Euro bzw. 11,30 Euro bei Zulassung am Tag der Online-Reservierung — belegt aus
  `008_wunschkennzeichen.txt` (Bibliothek „Leistungen Kfz-Zulassung").
- **Zeigt:** dieselbe Antwortqualität für eine `.txt`-Quelle wie für `.md` — der Formatvorrat ist für
  Suche und Beleg gleichgültig.

### 5. Berechtigungs-Doppelfrage

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

### 6. Quer-Bibliotheks-Frage

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

### 7. Amtsleitungs-Frage

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

### 8. Bewusst unbeantwortbare Frage

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

## Korpus-Aktualisierung

Der Korpus wird von einem deterministischen Python-Generator erzeugt, nicht von Hand gepflegt:

```bash
cd demo/generator
pip install -r requirements.txt
python generate_corpus.py
```

Läuft erneut, wenn sich eine der fünf Bibliotheken inhaltlich ändern soll. Zwei Läufe erzeugen
byte-identische Dateien (Prüfsumme in `demo/corpus/MANIFEST.sha256`) — allerdings nur unter den in
[`generator/README.md`](../demo/generator/README.md) genannten Bedingungen: Netzzugriff auf den dort
verankerten HuggingFace-Commit beim allerersten Lauf (danach lokal zwischengespeichert) und exakt die
dort gepinnten Paketversionen. Details, Werkzeugwahl und Quellen:
[`../demo/README.md`, „Korpus neu erzeugen"](../demo/README.md#korpus-neu-erzeugen) und
[`../demo/corpus/SOURCE.md`](../demo/corpus/SOURCE.md).

**Was danach neu indiziert werden muss:** Ein erneuter `python seed.py --profile demo`-Lauf gegen
eine bereits laufende Instanz legt Nutzer, Spaces, Bibliotheken und Rechte nicht doppelt an
(idempotent), löst aber für jede konnektorgespeiste Bibliothek erneut die Indizierung aus — neue oder
geänderte Dateien werden anhand ihrer SHA-256-Prüfsumme erkannt und neu verarbeitet, unveränderte
übersprungen. Für die Upload-Bibliothek gilt dasselbe für neu hinzugekommene Dateien; eine geänderte,
bereits hochgeladene Datei müsste vor einem erneuten Lauf gelöscht werden, weil `seed.py` ein
vorhandenes, nicht fehlgeschlagenes Dokument anhand des Dateinamens überspringt (siehe
`demo/seed/seed.py`, `upload_documents`). Bei größeren inhaltlichen Änderungen ist die dazugehörige
Drehbuchfrage oben gegenzuprüfen — Antworten, die auf konkreten Zahlen oder Formulierungen beruhen
(Gebührenrahmen, Fristen), veralten sonst stillschweigend.

---

## Zugehörige Dokumentation

- [`features/demo-instance.md`](./features/demo-instance.md) — Konzept: Behördenlandschaft,
  Bibliotheken, Formate, Quellen und Lizenzen, Rechtemodell
- [`../demo/README.md`](../demo/README.md) — Compose-Stack, Seed-Mechanismus, technische Details
- [`deployment.md`](./deployment.md), Abschnitt „Härtung für erreichbare Deployments" — zwingend vor
  jedem über `localhost` hinaus erreichbaren Rollout dieser Demo, einschließlich des dort separat
  behandelten `opaa-seed`-Clients
- [`features/search-quality-evaluation.md`](./features/search-quality-evaluation.md), Abschnitt
  „Öffentliche Demo" — der frühere Superhelden-Korpus, durch dieses Konzept abgelöst
