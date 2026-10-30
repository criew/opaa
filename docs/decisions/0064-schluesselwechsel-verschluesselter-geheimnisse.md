# ADR-0064: Schlüsselwechsel verschlüsselter Geheimnisse

## Status

Akzeptiert · zuletzt geändert 2026-10-30 · ergänzt ADR-0018 Entscheidung 4 und ADR-0053 Entscheidung 4

## Kontext

OPAA verschlüsselt gespeicherte Geheimnisse mit AES-256-GCM unter zwei Schlüsseln aus der Umgebung.
`OPAA_CREDENTIALS_ENCRYPTION_KEY` schützt Zugangsdaten von Quellen und Zugängen, Token verbundener
Konten, Webhook-Geheimnisse, das Dienstkonto des Verzeichnisabgleichs und OAuth-Zwischenstände.
`OPAA_SETTINGS_ENCRYPTION_KEY` schützt Modellschlüssel, das SMTP-Passwort, wartende Mails und die
Geheimnisse der Installationsschritte.

Das Format `enc:v1:<base64(iv‖ct‖tag)>` nannte den Schlüssel nicht und band den Wert nicht an seinen
Ort. Ein Schlüsselwechsel hieß deshalb, jeden Wert neu einzutragen: nach einem möglicherweise
bekannt gewordenen Schlüssel, beim Wechsel weg von einem veröffentlichten Entwicklungsschlüssel und
bei einem turnusmäßigen Wechsel nach dem Sicherheitskonzept des Hauses. Zugleich ließ sich ein
Geheimtext in der Datenbank in eine andere Zeile kopieren, etwa die Zugangsdaten einer fremden
Bibliothek, und wurde dort entschlüsselt. Während eines Updates laufen mehrere Instanzen mit zwei
Versionen nebeneinander (ADR-0053).

## Entscheidung

### 1. Format `enc:v2:<kid>:<payload>` mit Schlüsselkennung

Neue Werte haben die Form `enc:v2:<kid>:<base64(iv‖ct‖tag)>`, AES-256-GCM mit 96-Bit-IV und
128-Bit-Tag. `kid` sind die ersten 8 Byte von HMAC-SHA256(Schlüssel, `opaa-encryption-key-id`) als
Hex (`EncryptionKeyring`). `enc:v1:` bleibt lesbar, ebenso Klartext ohne `enc:` aus der Zeit vor der
Verschlüsselung, bis die Umstellung abgeschlossen ist (Entscheidung 6). Begründung: Die Kennung bestimmt den Schlüssel eindeutig und ohne Register, und sie
gibt den Schlüssel nicht preis.

### 2. Bindung an Tabelle, Spalte und Zeile

Die Associated Data von `enc:v2` ist `<tabelle>/<spalte>/<zeilen-id>` (`CipherBinding`). Jede
verschlüsselte Spalte ist einmal als `EncryptedColumn` im Modul der Tabelle deklariert; Ver- und
Entschlüsseln nennen die Zeile. Wandert ein Wert in eine andere Zeile, wird er umgeschlüsselt
(`rebind`). Einen unlesbaren `v2`-Wert lehnt der Code mit einer Meldung ab, statt ihn mitzunehmen: am
neuen Ort wäre er auch mit wiederhergestelltem Schlüssel nie mehr lesbar. Ein unlesbarer `v1`-Wert
wandert unverändert. Ein JPA-Konverter kennt seine Zeile nicht, deshalb trägt `EncryptedCredentials`
die Bindung vom Entity zum Konverter, und ein geladener Wert geht unverändert zurück. Begründung: Ein
kopierter `v2`-Geheimtext entschlüsselt am falschen Ort nicht.

### 3. Schlüsselring: ein aktueller Schlüssel, abgelöste nur zum Entschlüsseln

Je Zweck gibt es den aktuellen Schlüssel und abgelöste Schlüssel in
`OPAA_CREDENTIALS_ENCRYPTION_PREVIOUS_KEYS` bzw. `OPAA_SETTINGS_ENCRYPTION_PREVIOUS_KEYS`, durch
Kommas getrennt. Verschlüsselt wird nur mit dem aktuellen; `v2` wählt den Schlüssel über `kid`, `v1`
probiert alle. Fehlt der Schlüssel eines Werts, nennt der Fehler beide Variablen. Alle Schlüssel
gehören zum Konfigurationsabdruck (ADR-0053, Entscheidung 4): Ein Wechsel ist ein Neustart aller
Instanzen, kein Rolling Update. Ein veröffentlichter Schlüssel ist als abgelöster erlaubt, damit der
Betrieb von ihm wegwechseln kann.

### 4. Neuverschlüsselung als geplanter Lauf

`CiphertextRewrap` verschlüsselt alle zehn Minuten auf einer Instanz (ShedLock) jeden Wert einer
registrierten Spalte neu, der nicht mit dem Präfix des aktuellen Schlüssels beginnt: seitenweise mit
`FOR UPDATE SKIP LOCKED`, geschrieben nur, wo die Spalte noch den gelesenen Wert hält, höchstens
5.000 neu verschlüsselte Werte je Lauf. Unlesbare Werte bleiben stehen, zählen nicht gegen dieses
Budget und werden je Instanz einmal mit Tabelle, Spalte und Zeile gemeldet, nie mit dem Wert. Das
Log meldet den Abschluss, sobald kein lesbarer Wert mehr unter altem Schlüssel oder Format steht,
und nennt die Zahl der unlesbaren; danach entfernt der Betrieb die abgelösten Schlüssel. Begründung: Ein Lauf statt eines Kommandos braucht kein Wartungsfenster, greift nach einer
Rücksicherung von selbst wieder und nutzt die vorhandene Koordination mehrerer Instanzen.

### 5. `v2` erst, wenn jede Instanz es lesen kann

Solange eine lebende Instanz eine andere Version fährt (`CipherFormatGate`), schreibt OPAA `enc:v1`
mit dem aktuellen Schlüssel, und die Neuverschlüsselung behält das Format jedes Werts: Ein `enc:v1`-Wert
wandert unter den aktuellen Schlüssel und bleibt `enc:v1`, ein `enc:v2`-Wert bleibt `enc:v2`. Ohne abgelösten Schlüssel liest und sperrt der Lauf dann keinen `enc:v1`-Wert, weil er ohnehin
unter dem aktuellen oder unter keinem eingetragenen Schlüssel steht.
Begründung: ADR-0053, Entscheidung 13, angewandt auf ein Datenformat; ein neuer Wert entsteht erst,
wenn die Vorgängerversion ihn lesen kann, und ein Schlüsselwechsel kommt trotzdem zum Abschluss.

Der Schalter `OPAA_ENCRYPTION_KEYED_FORMAT` (Vorgabe `true`) hält dasselbe Tor auf Wunsch geschlossen:
Mit `false` schreibt OPAA weiter `enc:v1` und stellt den Bestand nicht auf `v2` um. Sind dabei
abgelöste Schlüssel gesetzt, warnt das Log einmal je Instanz, dass der Wechsel im ungebundenen Format
läuft. Ein späteres Umschalten von `true` auf `false` öffnet den Rückweg nicht wieder, weil
`v2`-Werte `v2` bleiben. Begründung: Eine
Version vor dieser liest `enc:v2` nicht. Wer sich den Rückweg auf sie ohne Rücksicherung offenhalten
will, betreibt die neue Version zunächst mit `false` und schaltet erst um, wenn der Rückweg nicht
mehr gebraucht wird. Ein Aufschub nach Zeit wurde verworfen, weil er einen gespeicherten Zeitpunkt
bräuchte und neue Werte trotzdem in `v2` entstünden.

### 6. Nach der Umstellung nur noch `v2`

Jeder Lauf der Neuverschlüsselung bei offenem Formattor (Entscheidung 5) zählt vor seinem Durchgang
je Zweck die Werte in `enc:v1:` und, bei Zugangsdaten, den Klartext, lesbar oder nicht. Klartext
verschlüsselt er mit, außer in Spalten, deren Leser ihn heute schon als fehlend behandeln
(`EncryptedColumn#refusingCleartext`, die Token verbundener Zugänge); dort zählt er nicht. Zählen
zwei Läufe im Abstand von mindestens fünf Minuten und zwei Heartbeat-Intervallen nichts und keiner
dazwischen etwas, hält
`cipher_legacy_retirements` den Zweck als umgestellt fest (`LegacyCipherRetirement`). Ab dann lehnen
`CredentialsEncryptor` und `SettingsEncryptor` beim Entschlüsseln `enc:v1:` und Klartext ab wie
einen unlesbaren Wert, das Log nennt Tabelle, Spalte und Zeile, nie den Wert, und die
Neuverschlüsselung wählt solche Werte nicht mehr aus. Neue Werte entstehen immer in `v2`, auch bei
geschlossenem Tor. Eine Instanz fragt den Eintrag höchstens einmal je Heartbeat-Intervall. Der
Abschluss verfällt, sobald eine Instanz einer anderen Version nach ihm einen Heartbeat geschrieben
hat, etwa bei einem Rolling Update oder Rollback, weil sie wieder `enc:v1` schreiben kann; die
nächste Messung löscht ihn und beginnt neu. Mit `OPAA_ENCRYPTION_KEYED_FORMAT=false` lehnt keine
Instanz etwas ab, und ihr Start leert die Tabelle, weil sie wieder `enc:v1` schreibt.
Begründung: Erst der Abschluss nimmt dem Kopieren eines `v1`-Werts und dem Einsetzen von Klartext
die Wirkung. Der Abstand der Messungen ist länger als die Zeit, die eine Instanz die Antwort des
Formattors behält, und als eine Transaktion, die vor der Messung einen `v1`-Wert schrieb; ein solcher
Wert verschiebt den Abschluss, statt danach abgelehnt zu werden. Das Verschlüsseln von Klartext macht
nichts verwendbar, was der Leser nicht schon verwendet.

## Konsequenzen

**Einfacher:** Ein Schlüsselwechsel kommt ohne Neueintragen aus; nach dem Update stellt OPAA den
Bestand selbst auf `v2` um; ein kopierter `v2`-Geheimtext ist wirkungslos.

**Schwieriger:** Jede neue verschlüsselte Spalte braucht ihre `EncryptedColumn` und eine
`EncryptedColumns`-Bean; ein Test gleicht die registrierten Spalten mit jeder Spalte
`*_ciphertext` des Schemas ab. Kopien zwischen Zeilen müssen umschlüsseln.

**Restrisiken:** Ein Entity, das einen Wert vor der Neuverschlüsselung geladen hat und danach ohne
Versionsprüfung speichert, schreibt den älteren, weiter lesbaren Geheimtext zurück; der nächste Lauf
stellt ihn erneut um. Wer einen Geheimtext als Vergleichswert hält, etwa der Abdruck des
Embedding-Endpunkts, sieht nach der Umstellung einmal eine Änderung und baut seinen Client neu;
trifft das eine laufende Änderung des Embedding-Modells, scheitert sie einmal mit einem Konflikt und
lässt sich wiederholen.

Die Bindung schützt nur `v2`. Bis zum Abschluss der Umstellung (Entscheidung 6) kann, wer in die
Datenbank schreibt, einen `v1`-Geheimtext in eine andere Zeile kopieren oder einen Klartextwert
einsetzen, und OPAA verwendet ihn. Ein `v1`-Wert, den kein Schlüssel liest, hält den Abschluss auf,
bis er neu eingetragen ist. Nach dem Abschluss genügt das Schreiben in die Datenbank nicht mehr: Wer
zusätzlich den Eintrag in `cipher_legacy_retirements` löscht, öffnet die Altformate für jede danach
gestartete Instanz und, sobald zwei Läufe wieder nichts finden, nur bis zum erneuten Abschluss.
Während eines Updates und bis zum erneuten Abschluss danach gilt dasselbe wie vor der Umstellung.

Nach der Umstellung auf `v2` liest eine ältere Version die gespeicherten Geheimnisse nicht mehr. Der
Weg zurück hinter diese Version führt dann nur über eine Rücksicherung von vor dem Update, oder er
bleibt offen, solange `OPAA_ENCRYPTION_KEYED_FORMAT=false` gilt (Entscheidung 5).

## Verworfene Alternativen

- Externes KMS oder Envelope Encryption — eigene Infrastruktur für jede Behörde; außerhalb des Umfangs.
- Kommando zur Neuverschlüsselung beim Start — verzögert den Start, braucht ein Wartungsfenster und
  koordiniert mehrere Instanzen nicht.
- Fortlaufende Schlüsselnummer statt abgeleiteter Kennung — braucht ein Register und bricht, wenn
  der Betrieb die Reihenfolge der Variablen ändert.
- Bindung nur an Tabelle und Spalte — schützt nicht gegen das Kopieren zwischen Zeilen derselben
  Spalte, den häufigeren Fall.
