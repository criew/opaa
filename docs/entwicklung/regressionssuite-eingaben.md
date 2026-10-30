# Regressionssuite für feindliche Eingaben

Die Suite hält fest, was die Gegenprüfungen zu präparierten Dateien, SSRF-Zielen und Log-Hygiene
gefunden haben: Jeder behobene Befund bekommt einen benannten Testfall, der auf dem fehlerhaften
Stand rot wird. Jeder Wächter trägt einen Kommentar `// regression guard for #N: <Invariante>`, der
das abgesicherte Issue und die Invariante nennt. Ein neuer Fall ist erst fertig, wenn er mit
zurückgenommenem Fix nachweislich rot wird: Eingabegröße und Zeitgrenze so wählen, dass der
fehlerhafte Stand die Grenze klar reißt, und wo möglich nicht nur auf die Zeit prüfen, sondern auf
das, was der Fix sicherstellt (Meldung der Grenze, Länge der Wortläufe, fehlender eingebetteter
Text).

**Ein neuer Parser oder ein neuer Konnektor mit ausgehender Verbindung erweitert die Suite im
selben PR.** Für Konnektoren erzwingt das ein Test (siehe unten), für Parser die Durchsicht im
Review.

## Präparierte Dateien

| Klasse | Weg | Lauf |
|---|---|---|
| `io.opaa.format.hostile.FormatHostileInputTest` | Format-Pipeline wie in Produktion: alle registrierten Formate (`ProductionDocumentFormats.registry(...)`, gegen den Spring-Kontext gehalten von `DocumentFormatRegistryRoutingIntegrationTest`), Weiterleitung nach erkanntem Inhalt, `DocumentFormatRunner`, Produktionsgrenzen | Gradle-Task `hostileInputTest`, eigene JVM mit **512 MB Heap**, je Fall **5 s** (unter einer Sekunde im behobenen Stand) oder **20 s** |
| `io.opaa.library.HostileUploadIntegrationTest` | Upload-Verarbeitung im gemeinsamen Spring-Kontext: Upload-Pool, Anhänge, Zeilenstatus, Folge-Upload | `./gradlew test` (Shard `api`), je Datei **90 s** bis zum Endstatus |

Die Wächter der Parser-Fixes stehen in `FormatHostileInputTest`. Der Upload-Test prüft den Weg bis
zur Zeile; als Wächter zählt er nur, wo ein zurückgenommener Fix den Endstatus ändert
(DOCX-Kompressionsbombe: ohne Teilgrenze wird sie indiziert statt abgewiesen).

Die Erwartung je Datei: Die Verarbeitung endet innerhalb der Zeitgrenze und ohne
`OutOfMemoryError` in der kleinen JVM. Eine Datei, die eine Grenze verwirft, endet als
`PARSE_FAILED` bzw. als `FAILED` mit der Meldung der Upload-Liste („Die Datei konnte nicht
verarbeitet werden“). Ein regulärer Upload direkt danach wird indiziert. Eine Datei, deren Befund
durch Abschneiden behoben ist (zyklische Gliederung, Verschachtelung über der Tiefengrenze), wird
indiziert.

Der Task `hostileInputTest` ist Teil von `check`, läuft also im CI-Job `build -x :test` mit;
`./gradlew test` schließt `*HostileInputTest` aus. Lokal:

```bash
# aus backend/
./gradlew hostileInputTest
./gradlew :test --tests '*HostileUploadIntegrationTest'   # braucht Docker
```

Die Dateien erzeugt `io.opaa.format.hostile.HostileFiles` zur Laufzeit, es liegen keine Binärdateien
im Repository. Die Fabrikmethoden sind öffentlich, damit ein späteres Fuzzing-Korpus sie als
Ausgangsdateien nutzen kann:

- PDF mit zyklischer Gliederung
- PDF, das ein Form-XObject vielfach auf eine Seite zeichnet (scheitert an der Zeichengrenze je Seite, #3434)
- DOCX-Kompressionsbombe
- EML mit verschachtelten Nachrichten und Anhängen
- MSG mit gemeinsam genutzten Anhangssektoren
- HTML-Titel und -Absatz sowie Markdown-Überschrift, an denen ein Ausdruck zurückgesetzt hat
- tief verschachtelte Tabellen mit vielen Zeilen je Ebene (jsoup nimmt höchstens 512
  Elementebenen an, also rund 120 Tabellenebenen; die Last kommt aus der Breite je Ebene)
- HTML mit sehr vielen Inhaltswurzeln (`<article>`)
- Text ohne Leerraum
- `.doc` mit Textpaketen im Objektpool; Grundlage ist das `.doc` des Demo-Korpus
  (`demo/corpus/formate/13_rahmenvertrag-scandienstleister.doc`), weil keine Bibliothek im
  Klassenpfad das alte Word-Format schreibt — erzeugt wird nur der Objektpool
- PPTX, das auf jeder Folie ein Word-Dokument als OLE-Objekt einbettet

Neue Fälle kommen als Fabrikmethode in `HostileFiles` und als Testfall in beide Klassen, sofern der
Weg über den Upload etwas zusätzlich prüft (Status, Anhänge, Folge-Upload).

Fälle, die bestehende Grenztests schon abdecken, wiederholt die Suite nicht:
`PdfAmplificationTest`, `ContextAmplificationTest`, `MsgAmplificationTest`,
`OoxmlPackageLimitsTest` und `BoundarySplitterTest` bleiben die Stelle für ihre Fälle. Eingebettete
Objekte deckt neben dem PPTX-Fall `DocumentServiceTest.parseDocumentDoesNotParseEmbeddedDocuments`
ab.

Eingebettete Objekte prüft die Suite auf beiden Wegen, die Produktion nimmt:

- **`.doc` über den Tika-Fallback:** `TikaFallbackFormat` nimmt nur `.txt` und `.doc` an; der
  `.doc`-Fall sichert, dass `DocumentService.parseDocument` kein eingebettetes Objekt parst
  (`SKIP_EMBEDDED`, #3376).
- **`.pptx` über `PptxDocumentFormat`:** Das Format liest nur Text aus Folienformen und Notizen und
  öffnet keinen eingebetteten Teil. Es gibt dort keinen Fix, den man zurücknehmen könnte; der Fall
  hält die Eigenschaft fest und wird rot, sobald das Format OLE-Objekte liest.

Bricht `hostileInputTest` mit „Gradle Test Executor … Java heap space“ ab, hat ein Wächter
gegriffen, der Speicher begrenzt (Wortläufe ohne Leerraum, zyklische Gliederung). Die abgebrochene
JVM schreibt keinen Bericht für den laufenden Fall; welcher es war, zeigt ein Einzellauf, etwa
`./gradlew hostileInputTest --tests '*WithoutWhitespace*'` oder `--tests '*CyclicOutline*'`.

**Neuer Parser:** mindestens eine Datei, die seine teuerste Struktur (Verschachtelung, Verweise,
Kompression, Wiederholung) über die Produktionsgrenze treibt, in `FormatHostileInputTest`.

## SSRF-Ziele

`io.opaa.indexing.spi.TargetValidationContract` ist der Vertrag, den jeder Konnektor mit
ausgehender Verbindung im Verbindungstest einhält. Er prüft ein Ziel in einem gesperrten
Adressbereich (Loopback, Link-Local/Cloud-Metadaten, private Netze, Carrier-Grade-NAT, IPv6-Loopback,
-Link-Local und -ULA, IPv4-mapped/-compatible, NAT64), einen Namen, der auf eine interne Adresse
auflöst, und eine Weiterleitung auf ein internes Ziel. Die Erwartung: abgelehnt mit der Meldung der
Zielprüfung („gesperrten Adressbereich“), bevor eine Verbindung das interne Ziel erreicht. Ein
Kontrollfall belegt, dass der Konnektor ein zugelassenes Ziel tatsächlich erreicht.

Der Vertrag prüft den Verbindungstest. Der Lauf eines Konnektors baut seinen Client mit derselben
Klasse bzw. Fabrik und derselben Zielprüfung; was nur der Lauf tut, prüfen die Executor-Tests und
die `*RebindingTest`-Klassen des Tasks `dnsRebindingTest`.

Je Konnektor gibt es eine Unterklasse `<Typ>TargetValidationContractTest` im Testpaket des
Konnektors. Sie baut den echten Konnektor mit der übergebenen Zielprüfung und richtet jede Anfrage
auf das übergebene Ziel. Bei Konnektoren mit fester Adresse (Google Drive, Dropbox, SharePoint,
OneDrive) steht das Ziel für die API-Basis.

**Neuer Konnektor mit ausgehender Verbindung:** eine Unterklasse anlegen.
`TargetValidationContractCoverageTest` vergleicht die registrierten Konnektoren mit
`descriptor().remote()` mit den vorhandenen Unterklassen und schlägt fehl, solange eine fehlt.

## Log-Hygiene

- `io.opaa.observability.ForgedLogEntryIntegrationTest`: Ein dekodierter Zeilenumbruch aus einer
  Anfrage, gefolgt von einem gefälschten Logeintrag, steht im Klartextlog der laufenden Anwendung nur
  als eingerückte Fortsetzung, im Argument einer Meldung wie in einer Exception-Meldung. Der Test
  schreibt über Logger der Anwendung, weil heute keine Anfrage einen dekodierten Pfad- oder
  Query-Wert ins Log bringt: Ein Pfad mit Steuerzeichen wird vorher mit 400 abgewiesen
  (`RateLimitSecurityChainIntegrationTest`), das Anfrageprotokoll schreibt den Pfad kodiert und
  ohne Query.
- `UserServiceOrganizationBoundaryIntegrationTest.theMemberSearchTakesLikeWildcardsLiterally`:
  `%` und `_` in der Personensuche sind Literale.

Die Regeln dahinter stehen in `SafeLogMessagesTest`, `LogTextTest` und `RateLimitFilterTest`.
