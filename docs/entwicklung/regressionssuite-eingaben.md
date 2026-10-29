# Regressionssuite für feindliche Eingaben

Die Suite hält fest, was die Gegenprüfungen zu präparierten Dateien, SSRF-Zielen und Log-Hygiene
gefunden haben: Jeder behobene Befund bekommt einen benannten Testfall, der auf dem fehlerhaften
Stand rot würde. Jeder Fall trägt einen Kommentar `// regression guard for #N: <Invariante>`, der
das abgesicherte Issue und die Invariante nennt.

**Ein neuer Parser oder ein neuer Konnektor mit ausgehender Verbindung erweitert die Suite im
selben PR.** Für Konnektoren erzwingt das ein Test (siehe unten), für Parser die Durchsicht im
Review.

## Präparierte Dateien

| Klasse | Weg | Lauf |
|---|---|---|
| `io.opaa.format.hostile.FormatHostileInputTest` | Format-Pipeline wie in Produktion: Weiterleitung nach erkanntem Inhalt, `DocumentFormatRunner`, Produktionsgrenzen | Gradle-Task `hostileInputTest`, eigene JVM mit **512 MB Heap**, je Fall **20 s** |
| `io.opaa.library.HostileUploadIntegrationTest` | Upload-Verarbeitung im gemeinsamen Spring-Kontext: Upload-Pool, Anhänge, Zeilenstatus | `./gradlew test` (Shard `api`), je Datei **90 s** bis zum Endstatus |

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
- PDF, das ein Form-XObject vielfach zeichnet (bisher nur Generator, ohne Testfall)
- DOCX-Kompressionsbombe
- EML mit verschachtelten Nachrichten und Anhängen
- MSG mit gemeinsam genutzten Anhangssektoren
- HTML-Titel, Markdown-Überschrift und tief verschachtelte Tabellen, an denen ein Ausdruck
  zurückgesetzt hat
- Text ohne Leerraum

Neue Fälle kommen als Fabrikmethode in `HostileFiles` und als Testfall in beide Klassen, sofern der
Weg über den Upload etwas zusätzlich prüft (Status, Anhänge, Folge-Upload).

Fälle, die bestehende Grenztests schon abdecken, wiederholt die Suite nicht:
`PdfAmplificationTest`, `ContextAmplificationTest`, `MsgAmplificationTest`,
`OoxmlPackageLimitsTest` und `BoundarySplitterTest` bleiben die Stelle für ihre Fälle.

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

Je Konnektor gibt es eine Unterklasse `<Typ>TargetValidationContractTest` im Testpaket des
Konnektors. Sie baut den echten Konnektor mit der übergebenen Zielprüfung und richtet jede Anfrage
auf das übergebene Ziel. Bei Konnektoren mit fester Adresse (Google Drive, Dropbox, SharePoint,
OneDrive) steht das Ziel für die API-Basis.

**Neuer Konnektor mit ausgehender Verbindung:** eine Unterklasse anlegen.
`TargetValidationContractCoverageTest` vergleicht die registrierten Konnektoren mit
`descriptor().remote()` mit den vorhandenen Unterklassen und schlägt fehl, solange eine fehlt.

## Log-Hygiene

- `io.opaa.api.RequestLogInjectionIntegrationTest`: Ein prozentkodierter Zeilenumbruch in Pfad oder
  Query-Parameter, gefolgt von einem gefälschten Logeintrag, beginnt keine eigene Logzeile.
- `UserServiceOrganizationBoundaryIntegrationTest.theMemberSearchTakesLikeWildcardsLiterally`:
  `%` und `_` in der Personensuche sind Literale.

Die Regeln dahinter stehen in `SafeLogMessagesTest`, `LogTextTest` und `RateLimitFilterTest`.
