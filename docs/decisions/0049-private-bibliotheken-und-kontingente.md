# ADR-0049: Private Bibliotheken und Kontingente

## Status

Akzeptiert · zuletzt geändert 2026-10-25 · ergänzt [ADR-0041](0041-verbindungen-als-eigenes-modul.md)
(Entscheidungen 4, 6 und 9), [ADR-0036](0036-berechtigungsmodell-gruppen-und-faehigkeiten.md) und
[ADR-0045](0045-gemeinsamer-datei-abgleich.md) (Entscheidung 9)

## Kontext

Eine private Bibliothek gehört einer Person und hängt an ihrem verbundenen Konto (ADR-0041,
Entscheidung 6). Sie darf nirgends als Einzelwert der Verwaltung erscheinen, ihre Inhalte dürfen
in keinem Protokoll und keinem Betriebslog stehen, und sie muss nach der Deaktivierung ihrer
Besitzerin innerhalb der Löschfrist vollständig und nachweisbar verschwinden, auch wenn gerade ein
Lauf schreibt. Weil eine Person beliebig viele private Bibliotheken anlegen kann, braucht die
Installation neben dem Kontingent je Bibliothek (#119) ein Kontingent je Person; am erschöpften
Kontingent soll der Lauf weiterlaufen und das System sich selbst erholen (Maintainer, 05.10.2026).

## Entscheidung

### 1. Anlegen und Betreiben

Anlegen nur über `library.PrivateLibraryCreation`: ausdrückliche Wahl „privat“, danach
unveränderlich; verlangt sind ein verbundenes Konto der Person auf dem Zugang, dessen Freigabe und
das Ziel des Kontos (Ursprung und Bindung müssen `SecretTarget#key` ergeben, sonst `400`); ein
eigenes Geheimnis nimmt sie nie an. Passt das Ziel nicht zum Konto, verweigert der Speicher mit
`TARGET_OUTSIDE_PROFILE`. Für alle außer der Besitzerin existiert sie nicht (`404`,
`PrivateLibraryInvisibilityIntegrationTest`); „Sicht als“ erreicht sie nie, die Diagnosesperre ist
die zweite Schranke. Verwaltungssichten zeigen keine private Bibliothek einzeln: Zahlen am Zugang,
Indexstatus und Pipeline-Stand (je eine Summenzeile), chunk-arme Dokumente, Speicherbereiche der
Bereinigung; der Bestandslauf der Pipeline bezieht sie als Systemprozess ein, weist sie aber in keiner
Zahl aus. Die ungefilterten Finder ruft nur ein gelisteter Systemprozess
(`privateLibrariesAreNotEnumeratedOutsideListedClasses`). **Eine Zählbasis:** Jede Zahl über private
Bibliotheken ruht auf ihren Besitzerinnen, nicht auf den Bibliotheken — unter der
Mindestgruppengröße N nur „weniger als N“ (`permission.PersonThreshold`, dieselbe Schwelle wie
`PersonNumbers`); eine Teilzahl (`rejectedPrivateLibraries`, `scheduledErasureCount`) nur, wenn sie
und ihr Rest je mindestens N Besitzerinnen haben (`PersonThreshold#disclosesPart`). Private Ablagen
zählen nie in `knownLibraryCount`. `indexing_jobs.failure_category` hält, warum ein Lauf scheiterte,
ohne Inhaltsbezug.

**Ein Lauf bleibt an die Verbindung gebunden, mit der er begann.** Wird das Konto währenddessen neu
verbunden (`SourceConnectionResolver#connectionGeneration`), endet der Lauf an seiner nächsten Frage
nach dem Geheimnis (`ConnectionReconnectedException`, ein `EndsRun`), bevor das neue Geheimnis ihn
erreicht; eine `404` unter einem anderen Konto entfernt so nichts.

### 2. Löschung

Eine Löschung für beide Wege, `library.PrivateLibraryErasure#erase`: Sofortlöschung durch die
Besitzerin (`DELETE /api/v1/libraries/{id}`) und der Löschlauf. Zuerst wird
`knowledge_libraries.erasure_requested_at` mit Anlass in eigener Transaktion gesetzt; danach startet
kein Lauf mehr, ein laufender endet an seiner nächsten Frage nach dem Geheimnis
(`LibraryErasureRequestedException`, ein `EndsRun`; jeder entfernte Konnektor fragt vor jeder
Anfrage, `RunSecretContract`). Solange ein Lauf `RUNNING` ist, antwortet die Löschung `202 PENDING`.
Danach in **einer** Transaktion unter Zeilensperre: Abschnitte in beiden Speichern (der Schreibweg
der Abschnitte hält `FOR KEY SHARE` und verweigert eine vorgemerkte Bibliothek), Verweise außerhalb
des Bestands über `knowledge.ErasedLibraryReferences` (Chat-Quellen werden zu „Quelle entfernt“,
Raumzuordnungen entfallen), Dokumente, Läufe, Benachrichtigungen, die Schale (`registerDeleted`), der
Nachweis, die Zeile; Ordner, Metadaten, Abgleichstand, Präsenz und Geheimnisse folgen per `CASCADE`.
Originale werden vorher außerhalb der Transaktion entfernt. Am Ende zählt sie jede Ablage nach;
bleibt etwas übrig, rollt sie zurück, der Marker bleibt, die Fortsetzung beginnt von vorn.
**Nachweis** `PRIVATE_LIBRARY_ERASED` im Revisionsprotokoll: Anlass (`OWNER_REQUEST`,
`DELETION_PERIOD_EXPIRED`), Zeitpunkt der Vormerkung, Handelnde, `erasedCompletely` und je
Inhaltsart nur `NONE` oder `PRESENT` (Maintainer, #2270); die geschwärzten Chat-Belege nennt er
nicht (`ErasedLibraryReferences#inProof`). Die Historientabellen der Rechte, Verbindungs- und
Diagnoseprotokoll behalten die Kennung, nie einen Namen. **Löschlauf** `PrivateLibraryDeletionSchedule`:
täglich um 04:45 nach dem Abgleich der Verbindungen, Fortsetzung vorgemerkter Löschungen alle fünf
Minuten. Die Frist hängt an der aktuellen ausdrücklichen Deaktivierung
(`ConnectionLifecycle#deletionPeriodStartedBefore` über `AccountUsability.Snapshot#deactivationsOf`),
beginnend beim späteren Zeitpunkt aus festgehaltenem Beginn und aktueller Sperre; ruhend löscht nie.
Eine getrennte Verbindung ohne private Bibliothek wird mit der Löschung entfernt
(`LIBRARY_DELETED`). Der Sperrgrund `OWNER_DEACTIVATED` trägt `contentDeletedOn`
(Port `connection.profile.DeactivationStarts`).

### 3. Lösen statt Veto

Lehnt der Konnektor eine Zugangsänderung für eine private Bibliothek ab oder lässt der Zugang
danach keine Personen mehr zu, wird sie gelöst und ruht mit `ACCESS_REMOVED`, bis die Besitzerin sie
einem anderen Zugang zuordnet; die Vorschau zählt solche Ablehnungen ohne Bibliothek und Grund, die
Besitzerin erhält `PRIVATE_LIBRARY_RELEASED` (ADR-0019). Verweigert der Speicher ihr Geheimnis
(deaktiviertes Konto), fragt die Änderung ihren Konnektor nicht und löst sie nicht allein deshalb.
Eine Constraint-Trigger-Funktion auf `connection_profiles` hält die Bedingung des Triggers auf
`library_connections` auch nach einer Änderung der Besitzart.

### 4. Speicherkontingent je Person

An der einen Aufnahmestelle liefert `LibraryStorageQuotaService#verdictFor` `QuotaVerdict`
(`WITHIN`, `LIBRARY_EXHAUSTED`, `PERSON_EXHAUSTED`); das Kontingent je Bibliothek prüft zuerst, für
eine Nur-Besitzerin-Bibliothek zählt danach die Summe über alle privaten Bibliotheken derselben
Besitzerin (`PersonalStorageQuota#usageOf`). Grenze: ein hausweiter Wert in
`private_storage_quota_settings` (`0` = unbegrenzt), ohne Zeile die Werkseinstellung
`PersonalStorageQuota#DEFAULT_QUOTA_BYTES` (10 GiB, ADR-0060 Entscheidung 1); setzen darf nur die Systemverwaltung
(`PRIVATE_STORAGE_QUOTA_CHANGED`). Prüfung und Speichern laufen unter
`LibraryStorageQuotaService#holdIntake`, einer Advisory-Sperre je Besitzerin in der Transaktion, die
prüft und schreibt (ADR-0053 Entscheidung 10); gezählt
wird, was gespeichert ist. Kein Verwaltungspfad zum Verbrauch einer Person: `usageOf` rufen nur
`LibraryStorageQuotaService` und der Selbstauskunfts-Controller (`personalUsageIsReadOnlyByItsOwner`,
`PERSONAL_USAGE_READERS`); die Übersicht der Verwaltung rechnet je Zugang in SQL über Personen und
maskiert nach `PersonThreshold#disclosesPart`.

### 5. Element überspringen, Marken halten

An beiden Kontingenten wird das Element übersprungen und der Lauf läuft weiter (Maintainer,
05.10.2026 für die Person; Koordinator, #2295 für die Bibliothek): `DocumentIngestService` liefert
`PERSONAL_QUOTA_EXCEEDED` bzw. `QUOTA_EXCEEDED`, nichts wird gespeichert, eine gespeicherte Fassung
bleibt, das Element bleibt präsent, der Lauf erreicht den Abgleich und endet als abgeschlossen und
unvollständig mit `QUOTA_EXHAUSTED` (Laufmeldung „Speicherkontingent erschöpft, Dateien
übersprungen“). Gezählt wird an einer Stelle (`IndexingRunProgress#recordOutcome`,
`AttachmentProgressSink#quotaRejections`). Eine Ablehnung hält jede Fortschrittsmarke am Element:
Das Dokument mit abgelehntem Anhang wird ohne Prüfsumme und Merkmal gespeichert, `FileSync` hält
Cursor und hebt das Ordnergedächtnis auf (ADR-0045, Entscheidung 9), Confluence `INCREMENTAL` hält
den Anker, RSS speichert den Feed-Zustand nicht — nie den Abschluss einer Runde und nie die
Entfernungen. Erholung: Löschungen in der Quelle schaffen Platz, sobald der Abgleich sie übernimmt.
Den Text an die Besitzerin erzeugt nur `DocumentIngestOutcomes`, `QuotaMessages#of` nur `IndexingRun`
und `AttachmentIndexer`. Für Dateiablagen läuft vor dem Download eine beratende Vorprüfung mit der
Listengröße (`DocumentIngestService#rejectionBeforeDownload`). Ein bestehendes Dokument wird an
seinem gespeicherten Fußabdruck gemessen (`#storedFootprint`: eigene Zeile plus Anhänge unter dem
Elternpfad), sonst zählten die Anhänge eines wiedergelesenen Mail-Archivs doppelt; der Aufschlag der
Transferkodierung bleibt, weil weder Kodierung je Anhang noch Rohgröße gespeichert sind.

### 6. Betriebslogs nennen keine Inhalte privater Bibliotheken

Wie eine Logzeile Pfade, Adressen, Datei-, Anhangs- und Tabellenblattnamen nennt, entscheidet
`sourceaccess.LoggedName`; die eine Stelle ist `KnowledgeLibrary#loggedNames`: bei einer privaten
Bibliothek nur `<private library <id>>`, sonst unverändert. Lauf, Aufnahme, Formate, Anhänge,
Quellzugriff und Originalabruf folgen ihr; weitergereichte Exceptions werden auf Typ und Stack ohne
Meldung reduziert, die Modell-Metadatenextraktion folgt der Regel. Nextcloud ist der erste
Konnektor, der sein persönliches Geheimnis für Personen anbietet; mit ihm entstand in Produktion die
erste private Bibliothek, belegt in `NextcloudPrivateLibraryLogLeakIntegrationTest` mit allen
Anwendungsloggern auf DEBUG.

## Konsequenzen

**Einfacher:** Eine Löschung, ein Zählmaßstab (Besitzerinnen), ein Ort für jede Kontingentfrage.

**Schwieriger:** Jede neue Verwaltungszahl muss gegen die Zählbasis geprüft werden; die
Nachzählung der Löschung ist exakt und rollt bei jedem Rest zurück.

**Restrisiken:** Die Antworttexte in Chats der Besitzerin bleiben stehen, nur Dateiname und Link
ihrer Quellen entfallen; Sicherungen enthalten eine vor der Löschung gesicherte Bibliothek weiter.
Bibliotheken Dritter (Tika, PDFBox, POI, JDK-HTTP-Client) und Fehlermeldungen der Datenbank
(`Failing row contains …`) loggen unterhalb von OPAA und sind nicht geprüft; `GlobalExceptionHandler`
kennt die Bibliothek nicht. Solange etwas am Kontingent fehlt, liest jeder Lauf dasselbe Fenster erneut und endet
mit `QUOTA_EXHAUSTED`.

## Verworfene Alternativen

- Lauf am erschöpften Personenkontingent beenden (`PersonalQuotaExhaustedException`) — kein
  Abgleich, keine Erholung; Element überspringen wie beim Bibliothekskontingent (Maintainer, #2276).
- Exakte Zähler im Löschnachweis — ließen auf Umfang der Nutzung schließen (Maintainer, #2270).
- Veto der privaten Bibliothek gegen eine Zugangsänderung — eine Person blockierte die Verwaltung;
  stattdessen Lösen mit Benachrichtigung.
- Fester Faktor für die Transferkodierung — zählte bei 7bit und quoted-printable zu wenig.
- Eigene Laufkategorie je Kontingent — eine genügt, das Element im Protokoll nennt das Kontingent.

## Offen

- Gespeicherte Rohgröße je Dokument, um den Kodierungsaufschlag beim Wiederlesen herauszurechnen.
- Marken am Kontingent je Bibliothek für Anhänge, RSS und Confluence `INCREMENTAL` sind erledigt;
  offen bleibt die Laufkategorie einer Ablehnung am Kontingent je Bibliothek außerhalb von
  Dateiablagen (#2295).
