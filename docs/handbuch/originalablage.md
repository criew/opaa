# Originalablage

Ein hochgeladenes Dokument wird nicht nur indiziert — sein **Original** bleibt greifbar, damit aus
einer Fundstelle die Datei wird, die ein Prüfer in die Hand nehmen kann. Die Originalablage ist der
Ort, an dem diese Dateien liegen. Sie gehört der Anwendung: OPAA schreibt, ersetzt und löscht dort,
und nur dort.

Betroffen sind ausschließlich **Uploads**. Dokumente, die über einen Konnektor hereinkommen, bleiben
Eigentum ihrer Quelle; OPAA liest sie dort und legt keine Kopie an. Wer die Originalablage
umstellt, ändert nichts an Verzeichnissen, Webverzeichnissen, Feeds, Confluence-Instanzen oder
S3-Quellen einer Bibliothek.

Jede Dokumentzeile in der Datenbank trägt den **Verweis** auf ihr Original. Beim Verzeichnisweg ist
das ein Dateipfad, beim Objektspeicher eine Adresse der Form `s3://<Bucket>/<Schlüssel>`. Dieser
Verweis und die Bytes sind zwei Dinge: Wer das eine ohne das andere umzieht, hat eine Installation,
die auf nicht vorhandene Originale zeigt.

Welche Rolle die Originalablage neben der Datenbank spielt, steht in der
[Betriebsübersicht](betrieb.md); einen Objektspeicher zum Erproben bringt der
[Compose-Stapel](docker-compose.md#der-mitgelieferte-objektspeicher-im-compose-stapel) mit.

## Die drei Wege

| Weg | Wofür | Was der Betrieb tut | Grenzen |
|---|---|---|---|
| **Verzeichnis** | kleine Installationen, Erprobung, Betrieb ohne Netzanbindung | Ein Verzeichnis bereitstellen und einhängen (`OPAA_UPLOAD_STORAGE_PATH_HOST` auf `/app/uploads`) | Wächst mit dem Volume des Wirtsystems; ohne eingehängtes Verzeichnis ist der Bestand beim Neuaufsetzen des Containers weg |
| **Netzlaufwerk** | Häuser, deren Bestände ohnehin auf einem Dateiserver liegen | Die Freigabe (SMB/NFS) auf genau dieses Verzeichnis einhängen; in OPAA nichts weiter zu konfigurieren | Rechte des Dienstkontos auf der Freigabe; ein Verbindungsabbruch erscheint als Ein-/Ausgabefehler, nicht als „Datei fehlt". **`chown` wirkt auf einem CIFS/SMB-Mount nicht** — das Schreibrecht der Kennung 65532 kommt dort aus den Mount-Optionen (`uid=65532,gid=65532`) oder über den `user:`-Eintrag, siehe [„Nicht-root-Betrieb des Backend-Containers"](docker-compose.md#nicht-root-betrieb-des-backend-containers) |
| **Objektspeicher** | Rechenzentrumsbetrieb, große Bestände, Verschlüsselung und Versionierung aus dem Speicher | `OPAA_UPLOAD_STORE=s3` und die `OPAA_UPLOAD_S3_*`-Variablen setzen; Bucket vorher anlegen | Keine Bereichsanfragen beim Download (kein Fortsetzen eines abgebrochenen Downloads); zusätzlicher Platzbedarf für Zwischendateien; ein zweites Sicherungsziel |

Die ersten beiden Wege sind für die Anwendung **derselbe** Weg: Sie sieht ein Verzeichnis und weiß
nicht, was darunterliegt. Nur der Objektspeicher ist eine andere Ablage — und nur er verlangt eine
Umstellung, wenn ein Bestand schon existiert.

## Auswahl

- **Ein Haus, eine Installation, überschaubarer Bestand** → Verzeichnis. Das ist die Voreinstellung;
  wer nichts konfiguriert, bekommt sie.
- **Die Dateien sollen dort liegen, wo die übrigen Bestände des Hauses liegen** → Netzlaufwerk.
  Kein Umstellen, kein zweites Sicherungsziel, kein zusätzlicher Dienst.
- **Der Bestand soll unabhängig vom Volume einer Maschine wachsen, oder Verschlüsselung ruhender
  Daten, Versionierung, Aufbewahrungsregeln und Replikation sollen aus dem Speichersystem
  kommen** → Objektspeicher.

Drei Eigenschaften des Objektspeicherwegs gehören in die Entscheidung, weil sie sich nicht
konfigurieren lassen:

- **Fehlende Konfiguration bricht den Start ab**, ein nicht erreichbarer Speicher nicht. Fehlt bei
  `OPAA_UPLOAD_STORE=s3` der Endpunkt, der Bucket oder ein Zugangsschlüssel, endet der Start mit
  einer Meldung, die die fehlende Variable nennt. Ist der Speicher dagegen nur gerade nicht
  erreichbar, läuft die Installation weiter: Chat, Suche und alles bereits Indizierte funktionieren
  ohne ihn, und der Zustand erscheint in einer eigenen Health Group
  (`GET /actuator/health/upload-store`), bewusst nicht im Gesamtstatus — sonst nähme ein gestörter
  Speicher die Instanz aus dem Load Balancing, obwohl sie Fragen beantworten kann. Mit
  `OPAA_UPLOAD_STORE=filesystem` gibt es diese Gruppe nicht; der Endpunkt antwortet dann mit
  „nicht gefunden". Beim Start prüft die Anwendung den Speicher einmal und protokolliert eine
  Warnung, wenn er nicht benutzbar ist — ein nicht erreichbarer Endpunkt, ein fehlender Bucket und
  abgelehnte Zugangsdaten fallen dabei alle auf, bevor der erste Upload es tut.
- **Der Abruf eines Originals unterscheidet zwei Fälle.** Ist der Speicher nicht erreichbar,
  antwortet der Abruf mit „Dienst nicht verfügbar" und einer Meldung; ist das Objekt nicht
  vorhanden, antwortet er wie bisher mit „nicht gefunden" — ununterscheidbar von einem fremden oder
  unbekannten Dokument, damit der Abruf keine Auskunft über fremde Bestände gibt. **Der Upload
  antwortet auf dieselbe Störung gleich** („Dienst nicht verfügbar", später erneut versuchen), statt
  einen Anwendungsfehler zu melden; von dem gescheiterten Versuch bleibt weder eine Datei noch eine
  neue Dokumentzeile zurück. Woran es lag — auch ein Zugangsschlüssel ohne Schreibrecht — steht im
  Log; die Health Group sieht nur die Leserichtung.
- **Zwischendateien brauchen Platz, und zwar auf zwei Dateisystemen.** Jeder Upload wird zunächst
  als Arbeitsdatei abgelegt und erst danach in den Bucket geschrieben; ein Nachzug, ein
  Metadatenlauf oder das Öffnen eines Anhangs holt sich eine lokale Kopie für die Dauer der Aktion.
  Dazu kommt im Temp-Verzeichnis der Anwendung der Zwischenspeicher jeder eingehenden Datei. Die
  Rechnung und die beiden Verzeichnisse stehen bei `OPAA_UPLOAD_S3_TEMP_DIRECTORY` in der
  Variablenliste.

## Was im Objektspeicher liegt

Ein Bucket, ein Objekt je hochgeladenem Dokument. Der Schlüssel ist
`<Präfix><Organisations-ID>/<Bibliotheks-ID>/<Zufallsname><Endung>` — **dieselbe Struktur wie auf
der Platte**, dort `<Pfad>/<Organisations-ID>/<Bibliotheks-ID>/<Zufallsname><Endung>`. Das Präfix
ist leer, solange `OPAA_UPLOAD_S3_KEY_PREFIX` nichts anderes sagt; es trennt mehrere Installationen,
die sich einen Bucket teilen.

**Warum die Organisation im Schlüssel steht.** Damit trägt jedes abgelegte Original seine
Zugehörigkeit bei sich, statt sie nur in der Datenbank zu haben. Wer in einen Bucket schaut, sieht
an jedem Objekt, zu welchem Haus es gehört — auch dann, wenn die zugehörige Bibliothek längst
gelöscht ist und die Dokumentzeile nicht mehr existiert. Der Zugriff prüft beide Ebenen: Ein
Verweis, dessen Organisations- oder Bibliotheksteil nicht zum abgerufenen Dokument passt, antwortet
mit „nicht gefunden" — ununterscheidbar von einem unbekannten Dokument.

Der Bucket **muss vorhanden sein** — OPAA legt keinen an. Ordner darin legt OPAA ebenfalls nicht an:
Objektspeicher kennt keine, der Schrägstrich im Schlüssel ist Teil des Namens.

**Was OPAA am Bucket nicht tut**, und was deshalb der Betrieb dort einstellt: Verschlüsselung
ruhender Daten, Versionierung, Aufbewahrungs- und Lebenszyklusregeln, Replikation. OPAA setzt keine
Verschlüsselungskopfzeilen und verwaltet keine Schlüssel. **Empfohlen sind Verschlüsselung ruhender
Daten und Versionierung**: Versionierung ist zugleich der Schutz gegen ein versehentliches Löschen
in der Oberfläche und gegen ein fehlgeleitetes Kopierkommando bei der Umstellung.

**Wie ein Prüfer ein Original wiederfindet.** Der Weg führt über die Dokumentzeile, nicht über den
Dateinamen — im Bucket steht ein Zufallsname:

```bash
docker compose exec postgres psql -U opaa -d opaa \
  -c "SELECT file_name, file_path FROM documents WHERE file_name = 'Bescheid.pdf';"
```

`file_path` nennt Bucket und Schlüssel. Damit lässt sich das Objekt in der Konsole des
Objektspeichers ansehen (Host-Port siehe [Services](docker-compose.md#services), Pfad `/rustfs/console/`) oder mit dem
Kommandozeilenwerkzeug des Speichers herausholen, etwa `aws s3 cp`. Der Weg über die Oberfläche
bleibt der einfachere: Die Fundstelle unter einer Antwort führt zum Dokument, und dessen Original
lädt sich dort herunter.

**Der umgekehrte Weg funktioniert ebenfalls**, und dafür ist das zusätzliche Segment da: Wer im
Bucket auf ein Objekt stößt, liest an seinem Schlüssel ab, zu welcher Organisation und zu welcher
Bibliothek es gehört — das erste Segment hinter dem Präfix ist die Organisations-ID, das zweite die
Bibliotheks-ID. Beide lassen sich nachschlagen, ohne dass ein Verweis in der Datenbank dafür
gebraucht wird:

```bash
docker compose exec postgres psql -U opaa -d opaa \
  -c "SELECT o.name AS organisation, l.name AS bibliothek
        FROM knowledge_libraries l JOIN organizations o ON o.id = l.organization_id
       WHERE l.id = '<Bibliotheks-ID aus dem Schlüssel>';"
```

Liefert die Abfrage keine Zeile, ist die Bibliothek gelöscht — die Organisation steht dann trotzdem
noch im Schlüssel selbst. Für die Platte gilt dasselbe, dort sind es die beiden Ordnerebenen
unterhalb des Upload-Verzeichnisses.

> In allen `psql`-Aufrufen dieses Kapitels steht `opaa` für den konfigurierten Datenbankbenutzer
> (`OPAA_DB_USERNAME`, Voreinstellung `opaa`) und für die Datenbank. Wer ein eigenes Konto
> eingerichtet hat, setzt es ein — sonst antwortet `psql` mit `FATAL: role "opaa" does not exist`,
> und zwar auch mitten in der Umstellung. Bei einem eigenen Schema (`OPAA_DB_SCHEMA`) gehört
> `PGOPTIONS='-c search_path=<schema>'` vor den Aufruf, sonst antwortet `psql` mit
> `relation "documents" does not exist`.

**Anhänge sind die Ausnahme.** Eine E-Mail mit Anhängen wird zu mehreren Dokumentzeilen: die Mail
selbst und je ein Dokument pro Anhang. Nur die Mail liegt als Objekt im Speicher; der Verweis der
Anhangzeilen ist zusammengesetzt aus dem Verweis der Mail, einer laufenden Nummer und dem
Dateinamen des Anhangs. Der Anhang wird beim Abruf aus der Mail herausgelöst. Das ist der Grund,
warum die Umstellung unten den Verweis **ersetzt** statt ihn neu zu bilden — die Anhangzeilen
wandern dabei ohne Sonderbehandlung mit.

## Sicherung und Wiederherstellung

Beim Verzeichnisweg gibt es zwei Sicherungsziele: die Datenbank und das Upload-Verzeichnis. Beim
Objektspeicher ebenfalls zwei: die Datenbank und der Bucket. In beiden Fällen gilt dieselbe
Reihenfolge.

**Zeile und Bytes gehören zusammen, fallen aber nicht gemeinsam an.** Wird während des
Sicherungslaufs hochgeladen oder gelöscht, driften die beiden Ziele um die Dauer des Laufs
auseinander. Wer einen genau zusammenpassenden Stand braucht, hält die Uploads für die Dauer an
(Backend stoppen). Sonst gilt: **erst die Datenbank sichern, dann die Originale.** Ein in der
Zwischenzeit hochgeladenes Dokument ist dann ein Objekt ohne Zeile — verwaist, kostet Speicherplatz
und stört sonst nichts; eingesammelt wird es von [„Verwaiste Originale
aufräumen"](#verwaiste-originale-aufräumen), dem nächsten Abschnitt. In der anderen Reihenfolge wäre
es eine Zeile ohne Bytes, und die ist im Betrieb ein Fehlerbild.

**Wiederherstellen dagegen umgekehrt: erst die Originale, dann die Datenbank.** Sonst zeigen die
Zeilen auf Objekte, die es noch nicht gibt — jeder Abruf eines Originals scheitert, bis die Kopie
durch ist, und das sieht für die Dauer der Wiederherstellung nach Datenverlust aus.

**Bei der Dateisystem-Ablage danach das Eigentum zurückgeben.** Was von außen in `uploads/`
eingespielt wird — ein als `root` entpacktes Archiv, ein `rsync` von einem Sicherungsserver, ein
`docker cp` —, gehört anschließend dem einspielenden Konto und nicht mehr `65532`. Das Backend
startet dann zwar und liefert jedes Original aus, aber der nächste Upload scheitert. Ein
abschließendes `sudo chown -R 65532:65532 uploads` schließt das ab (siehe [„Nicht-root-Betrieb des
Backend-Containers"](docker-compose.md#nicht-root-betrieb-des-backend-containers)).

Zu sichern ist außerdem die Konfiguration. Ein Verweis in der Datenbank nennt Bucket und Schlüssel,
aber nicht, an welchem Endpunkt dieser Bucket liegt; ohne die `OPAA_UPLOAD_S3_*`-Werte ist nach
einem Ausfall des Wirtsystems nicht mehr abzuleiten, worauf die Installation gezeigt hat.

## Verwaiste Originale aufräumen

Ein verwaistes Original ist eine abgelegte Datei beziehungsweise ein abgelegtes Objekt, auf das keine
Zeile mehr zeigt: ein fehlgeschlagenes Löschen, ein zwischen Ablegen und Eintragen abgebrochener
Upload, ein zurückgespielter Datenbankstand — und der planmäßige Fall aus dem Abschnitt darüber, ein
Upload während des Sicherungslaufs. Auf der Platte fällt so etwas beim Hineinschauen auf, in einem
Bucket sieht niemand nach.

Zwei Läufe räumen es auf, beide `SYSTEM_ADMIN`, beide in zwei Schritten — melden und löschen sind
bewusst getrennt, damit ein Fehler in der Zuordnung nicht unumkehrbar wird. Der erste arbeitet **je
Bibliothek** und hält deren Ablage gegen deren Dokumentzeilen; er ist der Regelfall. Der zweite
arbeitet **je Organisation** und findet, was der erste nicht erreichen kann: die Ablage einer
Bibliothek, die es nicht mehr gibt. Beide bleiben in der Organisation des Aufrufers.

### Originale einer vorhandenen Bibliothek

Erster Schritt: melden, ohne etwas anzufassen.

```bash
curl -X POST http://localhost:8081/api/v1/admin/upload-store/orphan-originals/report \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <token>" \
  -d '{"libraryId": "<Bibliotheks-ID>"}'
```

Die Antwort nennt zu jedem gefundenen Original seinen `locator` (genau den Wert, den `file_path`
einer Zeile trüge), Änderungszeit und Größe, dazu `orphanCount` (alle Funde), `scannedCount` (alle
angesehenen Originale), `referencedCount` (davon die, auf die eine Zeile zeigt) und
`withinGracePeriodCount` (zu junge, die der Lauf nicht anfasst). Optional hebt `minimumAgeMinutes`
die Schonfrist für diesen Aufruf an — unterschreiten lässt sie sich nicht.

**Der erste Blick gilt `referencedCount`.** Meldet der Bericht abgelegte Originale, aber `0`
referenzierte, haben Zeilen und Ablage den Bezug zueinander verloren — dann ist der Bericht kein
Aufräumauftrag, sondern ein Befund (siehe den letzten Punkt unten).

Zweiter Schritt: die geprüften Locator löschen, und nur diese.

```bash
curl -X POST http://localhost:8081/api/v1/admin/upload-store/orphan-originals/delete \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <token>" \
  -d '{"libraryId": "<Bibliotheks-ID>", "locators": ["<locator aus dem Bericht>"]}'
```

Zeilen und Ablage werden zu Beginn des Aufrufs einmal gelesen, und jeder genannte Locator wird
gegen diesen Stand geprüft: Wo eine Zeile darauf zeigt, wo die Schonfrist noch läuft oder wo die
Ablage nichts hält, steht der Locator mit seinem Grund unter `skipped` statt unter `deleted`. Wird
die Ablage mitten im Lauf unerreichbar, gilt das für die restlichen Locator als fehlgeschlagene
Entfernung (`DELETE_FAILED`) — der Aufruf bricht nicht ab, damit die Antwort und der
Prüfprotokoll-Eintrag weiterhin benennen, was tatsächlich entfernt wurde. Jeder Löschaufruf
hinterlässt genau einen Eintrag im Prüfprotokoll, auch ein abgelehnter; der Melde-Aufruf hinterlässt
keinen.

Drei Dinge sind im Betrieb wichtig:

- **Schonfrist.** Ein Original, das jünger ist als `OPAA_UPLOAD_ORPHAN_GRACE_MINUTES`, wird weder
  gemeldet noch gelöscht — ein Upload, dessen Zeile gerade entsteht, ist kein verwaistes Original.
- **`truncated: true` heißt: zweiter Durchgang nötig.** Ein Bericht listet nur einen Ausschnitt der
  Funde, zählt in `orphanCount` aber alle. Nach dem Löschen des gelisteten Ausschnitts denselben
  Bericht erneut abrufen, bis `truncated` auf `false` steht.
- **Nach einer Umstellung der Ablage meldet der erste Bericht alles.** Wurden die Bytes umgezogen,
  aber die Verweise nicht umgeschrieben, gilt der gesamte Bestand als verwaist — erkennbar an
  `referencedCount: 0`. Das ist das Signal, die
  [Umstellung](#eine-laufende-installation-auf-den-objektspeicher-umstellen) zu prüfen — nicht, zu
  löschen.

Was der Lauf **nicht** sieht: alles, was tiefer liegt als die Ebene der Bibliothek. Die Ablage legt
dort nie etwas an; was ein anderer Schreiber unter demselben Präfix abgelegt hat, wird deshalb weder
gemeldet noch je gelöscht. Ebenso wenig erreicht dieser Lauf die Originale einer bereits gelöschten
Bibliothek — er arbeitet je Bibliothek, und ohne deren Zeile antwortet er mit „nicht gefunden".
Dafür gibt es den zweiten Lauf.

### Originale gelöschter Bibliotheken

Schlägt das Löschen eines Originals fehl und wird die Bibliothek danach entfernt, bleiben Bytes
zurück, die über keine Bibliothek mehr ansprechbar sind. Der zweite Lauf setzt deshalb nicht an der
Bibliothek an, sondern an der Ablage: Er sieht auf der Ebene **unter der eigenen Organisation** nach
und meldet jeden Bibliotheks-Ordner, zu dem es keine Bibliothekszeile mehr gibt. Die Organisation
kommt vom Aufrufer und steht im Schlüssel selbst — deshalb braucht dieser Lauf
keine einzige Datenbankzeile, um die Mandantengrenze zu ziehen.

```bash
curl -X POST http://localhost:8081/api/v1/admin/upload-store/orphan-libraries/report \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <token>" \
  -d '{}'
```

Die Antwort nennt je gemeldetem Ordner seine `libraryId`, die enthaltenen Originale mit `locator`,
Änderungszeit und Größe, dazu `orphanCount`, `scannedCount`, `withinGracePeriodCount` und
`totalSize` (die Bytes, die das Löschen freigäbe). Auf Berichtsebene stehen `libraryCount` (alle
gefundenen Ordner ohne Zeile), `scannedLibraryCount` (alle Ordner der Organisation),
`knownLibraryCount` (davon die mit Zeile) und `truncated`. `minimumAgeMinutes` hebt auch hier die
Schonfrist für diesen Aufruf an.

**Der erste Blick gilt `knownLibraryCount`, und hier noch strenger als beim ersten Lauf.** Meldet
der Bericht Ordner, aber `0` bekannte, ist das kein Aufräumauftrag, sondern ein Befund: Dann zeigen
`OPAA_UPLOAD_STORAGE_PATH` beziehungsweise `OPAA_UPLOAD_S3_BUCKET`/`OPAA_UPLOAD_S3_KEY_PREFIX`
vermutlich auf den Bereich einer anderen Installation — dort ist jeder Ordner „ohne Zeile", und der
Löschendpunkt würde ihren gesamten Bestand in 500er-Schritten entfernen. Erst die Konfiguration
prüfen, dann löschen.

Gelöscht wird wie beim ersten Lauf: ausdrücklich genannte Locator, höchstens 500 je Aufruf.

```bash
curl -X POST http://localhost:8081/api/v1/admin/upload-store/orphan-libraries/delete \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <token>" \
  -d '{"libraryId": "<libraryId aus dem Bericht>", "locators": ["<locator aus dem Bericht>"]}'
```

Vier Dinge unterscheiden diesen Lauf vom ersten:

- **Er fasst nichts an, wozu es eine Bibliothekszeile gibt** — auch keine aus einer anderen
  Organisation. Nennt der Löschaufruf eine `libraryId`, die es noch gibt, wird er mit einer Meldung
  abgewiesen und nichts entfernt; für eine existierende Bibliothek ist der erste Lauf zuständig.
- **Die Schonfrist zählt ab dem Schreiben des Originals, nicht ab dem Löschen der Bibliothek.**
  Gemessen wird die Änderungszeit der Datei beziehungsweise des Objekts — genau wie beim ersten
  Lauf. Ein im Juni hochgeladenes Original, dessen Bibliothek heute gelöscht wurde, steht deshalb
  sofort unter `orphanCount` und ist sofort löschbar; nur ein eben erst geschriebenes erscheint
  unter `withinGracePeriodCount`. **Das Löschen einer Bibliothek eröffnet kein Zeitfenster, in dem
  sich ihre Originale noch zurückholen ließen** — wer eine Bibliothek versehentlich gelöscht hat,
  findet ihre Originale nur in der Sicherung wieder.
- **Ein leerer Bibliotheks-Ordner wird nicht gemeldet.** Der Lauf entfernt Originale, keine Ordner;
  ein leer gewordenes Verzeichnis auf der Platte stünde sonst in jedem künftigen Bericht. In einem
  Objektspeicher gibt es den Fall nicht — dort ist ein Präfix ohne Objekte nicht vorhanden.
- **Die oberste Ebene erreicht er nicht.** Ein Eintrag direkt unter dem Upload-Pfad beziehungsweise
  dem Schlüsselpräfix ist eine Organisation; zu welcher Organisation ein Eintrag gehörte, der keine
  ist, steht nirgends mehr, und ein Lauf innerhalb einer Organisation darf ihn deshalb weder melden
  noch löschen. Ein solcher Eintrag stammt nicht von OPAA; er findet sich im Abgleich mit
  `SELECT id FROM organizations;` und wird von Hand entfernt.

## Eine laufende Installation auf den Objektspeicher umstellen

Die Umstellung ist ein **Wartungsfenster**, kein laufender Betriebszustand: Bytes kopieren, dann die
Verweise in der Datenbank umschreiben, dann umschalten. Es gibt bewusst keinen Zustand, in dem beide
Ablagen gleichzeitig gelesen werden — eine unvollständige Kopie soll auffallen, nicht aufgefangen
werden.

Vorher: einen Datenbank-Dump ziehen und den Bucket bereitstellen (mit dem mitgelieferten Dienst legt
ihn `upload-store-init` an).

**1. Uploads anhalten.** Das Backend stoppen, damit während des Kopierens keine neue Datei
dazukommt:

```bash
docker compose stop backend
```

**2. Objektspeicher starten** (nur beim mitgelieferten Dienst; ein hauseigener Speicher läuft schon):

```bash
docker compose --profile upload-s3 up -d upload-store upload-store-init
```

**3. Bytes kopieren.** Die Verzeichnisstruktur wandert unverändert in den Bucket — ein Unterordner
je Organisation, darin einer je Bibliothek. Mit dem mitgelieferten Dienst genügt ein
Wegwerf-Container, der das Upload-Verzeichnis und den Speicher gleichzeitig sieht:

```bash
docker compose --profile upload-s3 run --rm -v ./uploads:/uploads:ro upload-store-init \
  "mc alias set store http://upload-store:9000 <zugangsschlüssel> '<geheimer schlüssel>' &&
   mc mirror /uploads store/<bucket> &&
   mc ls --recursive store/<bucket>"
```

`./uploads` ist dabei das Verzeichnis, das der Stapel in den Backend-Container einhängt; wurde es
über `OPAA_UPLOAD_STORAGE_PATH_HOST` verlegt, steht dort der verlegte Pfad. Gegen einen hauseigenen
Speicher tut es dasselbe vom Host aus, etwa `aws s3 sync ./uploads s3://<bucket>/` oder
`mc mirror ./uploads <alias>/<bucket>`. Ist ein Schlüsselpräfix konfiguriert, ist das Ziel
`<bucket>/<präfix>` statt `<bucket>`.

Die abschließende Auflistung ist die Probe: So viele Objekte wie Dateien. Der Schritt ist
wiederholbar — kopiert wird nur, was im Ziel fehlt.

**4. Zählen, was umzuschreiben ist.** Der zu ersetzende Präfix ist installationsabhängig — im
Container `/app/uploads/`, bei einem Betrieb ohne Container der dort konfigurierte absolute Pfad.
Die Zählabfrage zeigt, ob er stimmt: Beide Zahlen müssen gleich sein.

```bash
docker compose exec postgres psql -U opaa -d opaa -c \
  "SELECT count(*) AS zeilen,
          count(*) FILTER (WHERE file_path LIKE '/app/uploads/%') AS mit_praefix
     FROM documents WHERE source_type = 'UPLOAD';"
```

**5. Verweise umschreiben.** Eine Präfixersetzung, kein Neubilden des Werts — dadurch wandern die
Anhangzeilen ohne Sonderfall mit:

```bash
docker compose exec postgres psql -U opaa -d opaa -c \
  "UPDATE documents
      SET file_path = replace(file_path, '/app/uploads/', 's3://<bucket>/')
    WHERE source_type = 'UPLOAD' AND file_path LIKE '/app/uploads/%';"
```

Die Zahl der geänderten Zeilen muss der Zählabfrage aus Schritt 4 entsprechen. Mit Schlüsselpräfix
lautet das Ziel `s3://<bucket>/<präfix>`.

**6. Umschalten und starten.** In `.env.docker` die Ablage auf den Objektspeicher stellen und die
`OPAA_UPLOAD_S3_*`-Werte eintragen, dann:

```bash
docker compose up -d backend
```

**7. Prüfen.** Vier Proben, in dieser Reihenfolge. Die ersten beiden von der Kommandozeile:

```bash
curl -s http://localhost:8081/actuator/health/upload-store   # Endpunkt und Bucket, Status UP
docker compose exec postgres psql -U opaa -d opaa -c \
  "SELECT file_name, file_path FROM documents WHERE source_type = 'UPLOAD' LIMIT 5;"
```

Endpunkt, Bucket und im Störungsfall der Grund erscheinen dabei nur einem angemeldeten Aufrufer —
in einem OIDC-Deployment antwortet ein anonymes `curl` nur mit dem Status, wie unter
[„Diagnose ohne Shell"](ueberwachung-und-fehlerbehebung.md#diagnose-ohne-shell) beschrieben.

Die dritte ist die eigentliche: In der Oberfläche ein Dokument öffnen und sein Original
herunterladen — am besten eine E-Mail mit Anhang und den Anhang gleich mit, weil daran sichtbar
wird, dass auch die zusammengesetzten Verweise noch stimmen. Die vierte prüft den Schreibweg: eine
neue Datei hochladen und nachsehen, dass sie als Objekt im Bucket ankommt.

**Das alte Verzeichnis bleibt zunächst stehen.** Es ist der Rückweg, solange die Proben nicht durch
sind. Wer es entfernt, tut das erst nach einem erfolgreichen Sicherungslauf der neuen Ablage.

## Der Rückweg: vom Objektspeicher zurück auf das Dateisystem

Dieselben Schritte in umgekehrter Richtung.

```bash
docker compose stop backend

docker compose --profile upload-s3 run --rm -v ./uploads:/uploads upload-store-init \
  "mc alias set store http://upload-store:9000 <zugangsschlüssel> '<geheimer schlüssel>' &&
   mc mirror --overwrite store/<bucket> /uploads"

docker compose exec postgres psql -U opaa -d opaa -c \
  "UPDATE documents
      SET file_path = replace(file_path, 's3://<bucket>/', '/app/uploads/')
    WHERE source_type = 'UPLOAD' AND file_path LIKE 's3://<bucket>/%';"

sudo chown -R 65532:65532 uploads
```

Danach in `.env.docker` die Ablage zurück auf das Dateisystem stellen und das Backend starten.

Die letzte Zeile ist keine Formsache: Der Kopierschritt läuft in einem Container ohne `user:`, also
als `root`, und alle zurückgespielten Organisations- und Bibliotheksordner gehören danach `root`.
Ohne sie sieht alles richtig aus — der Start ist grün, jedes Original lässt sich herunterladen (die
Dateien sind `0644`) —, aber der erste Upload in eine **bestehende** Bibliothek und jedes Löschen
eines Originals scheitern mit `AccessDeniedException` (siehe [„Nicht-root-Betrieb des
Backend-Containers"](docker-compose.md#nicht-root-betrieb-des-backend-containers)).

Drei Unterschiede zum Hinweg:

- **Ein Schlüsselpräfix gehört in beide Befehle**, und zwar in die Quelle des Kopierens und in den
  zu ersetzenden Wert. Das Verzeichnis muss danach wieder unmittelbar die Unterordner der
  Organisationen enthalten — liegt darunter erst noch ein Ordner mit dem Namen des Präfixes, gilt
  keine Datei mehr als zur Bibliothek gehörend und **jedes** Original antwortet mit „nicht
  gefunden". Mit Präfix lauten die beiden Zeilen deshalb
  `mc mirror --overwrite store/<bucket>/<präfix> /uploads` und
  `replace(file_path, 's3://<bucket>/<präfix>', '/app/uploads/')` mit
  `LIKE 's3://<bucket>/<präfix>%'`.
- **`--overwrite` ist nötig**, wenn das alte Verzeichnis noch steht. Ohne diesen Schalter überträgt
  `mc mirror` zwar alles Fehlende, meldet aber für jede bereits vorhandene Datei einen Fehlschlag —
  und **endet trotzdem mit dem Rückgabewert `0`**. Eine Skriptierung bemerkt den Unterschied
  deshalb nicht. In ein leeres Verzeichnis kopiert der Befehl auch ohne den Schalter.
- **Der Bestand ist größer als beim Hinweg.** Alles, was seit der Umstellung hochgeladen wurde,
  liegt nur im Bucket. Die Zählabfrage aus Schritt 4 gehört deshalb auch hier davor, mit
  `s3://<bucket>/%` als Muster.

## Wenn etwas nicht stimmt

| Bild | Wahrscheinliche Ursache |
|---|---|
| Alle Originale antworten mit „nicht gefunden", die Suche funktioniert | Die Verweise wurden nicht umgeschrieben, oder Bucket bzw. Schlüsselpräfix in der Konfiguration passen nicht zu dem, was in den Verweisen steht. Nach einem Rückweg mit Schlüsselpräfix kommt eine dritte Ursache dazu: Die Dateien liegen dann eine Ebene zu tief, unter einem Ordner mit dem Namen des Präfixes |
| Einzelne Originale fehlen, andere nicht | Die Kopie war unvollständig — Kopierschritt wiederholen, er überträgt nur, was fehlt |
| Jeder Abruf antwortet mit „Dienst nicht verfügbar", die Health Group steht auf `DOWN` | Der Objektspeicher ist nicht erreichbar; die Gruppe nennt den Grund |
| Der Start bricht mit einer Meldung über eine fehlende Variable ab | `OPAA_UPLOAD_STORE=s3` ohne Endpunkt, Bucket oder Zugangsschlüssel |
| Die Health Group antwortet mit „nicht gefunden" | Es ist der Verzeichnisweg konfiguriert; die Gruppe gibt es nur beim Objektspeicher |
| Uploads scheitern mit „Dienst nicht verfügbar", der Speicher ist erreichbar | Der Bucket fehlt oder der Zugangsschlüssel darf nicht schreiben — die Anwendung meldet beides wie eine Störung des Speichers. Den Grund nennt das **Log**, mit Bucket und fehlender Berechtigung (etwa `s3:PutObject`). Ein fehlender Bucket fällt auch der Health Group auf (`DOWN`); ein reiner Schreibrechte-Fehler nicht — ihre Sondierung prüft nur die Leserichtung und bleibt dann auf `UP` |
| `docker cp … /app/uploads` liefert ein leeres Verzeichnis | Die Originale liegen im Objektspeicher, nicht im Container |
| Der Bericht über verwaiste Originale meldet den gesamten Bestand, `referencedCount` ist `0` | Zeilen und Ablage passen nicht zueinander — meist eine Umstellung, bei der die Bytes kopiert, die Verweise aber nicht umgeschrieben wurden. Nichts löschen, bevor das geklärt ist |
