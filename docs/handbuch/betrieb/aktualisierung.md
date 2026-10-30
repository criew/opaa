# Aktualisierung

Dieses Kapitel beschreibt, wie eine Installation auf eine neue Version kommt und was ein Update mit
dem Index macht. Es gilt für jeden Betriebsweg und gehört zum Teil Betrieb des
[Handbuchs](../README.md); die Bausteine einer Installation beschreibt die
[Betriebsübersicht](README.md). Die Befehle stehen bei den Betriebswegen:
[Docker Compose](docker-compose.md#aktualisierung-auf-einen-neuen-stand),
[Kubernetes](kubernetes.md#aktualisierung-und-rückweg).

## Ablauf eines Updates

1. **Release-Notizen lesen** (unter *Releases* im GitHub-Repository), Abschnitt „Vorbereitung für
   Bestandsinstallationen“: Er nennt die Schritte, die vor dem Update zu erledigen sind, oder sagt
   „Keine Vorbereitung nötig.“ Bei übersprungenen Versionen gilt das für jede. Für Abstimmungen mit
   Datenschutz oder Personalvertretung dient das
   [Update-Paket](releases-und-nachweise.md#das-update-paket).
2. **Sichern** ([Sicherung und Wiederherstellung](sicherung-und-wiederherstellung.md)).
3. **Vorbereitung erledigen, Images tauschen**, Backend und Frontend auf dieselbe Version.
4. **Start:** Das Backend migriert das Schema, nur vorwärts. Mit mehreren Instanzen:
   [Update ohne Ausfall](mehrere-instanzen.md#update-ohne-ausfall).
5. **Prüfen:** `/actuator/info`, eine Frage im Chat, „Suche & Indexierung“.

Zurück auf eine ältere Version geht es über die Sicherung; mit mehreren Instanzen innerhalb des
Versionsfensters auch ohne Rücksicherung ([Update ohne Ausfall](mehrere-instanzen.md#update-ohne-ausfall)),
außer hinter die Umstellung der gespeicherten Geheimnisse
([Kein Rückweg nach der Umstellung der Geheimnisse](#kein-rückweg-nach-der-umstellung-der-geheimnisse)).

## Was ein Update mit dem Index macht

Ein Update **gefährdet den Index nicht** und verlangt **keine Neuindizierung**.

- Schemaänderungen löschen keine Dokument- oder Vektordaten. Ausnahme ist ein Neuaufsetzen, das die
  Release-Notizen ankündigen ([Docker Compose](docker-compose.md#neu-aufsetzen-statt-aktualisieren)).
- Ändert sich die Verarbeitung eines Formats, bleiben alte Chunks durchsuchbar;
  „Suche & Indexierung“ zeigt „Nachzug ausstehend“, und ein
  [Nachzug](../arbeitsmittel/wissensbibliotheken/README.md#72-pipeline-versionen-und-nachzug) bringt sie auf den neuen Stand.
- Ein Update wechselt das Embedding-Modell nie; das Modell steht in der Datenbank und wechselt nur
  in der Modellverwaltung ([Modell wechseln](modelle.md#modell-wechseln)).
- **Kein Rollback während eines Wechsels des Embedding-Modells.** Läuft eine Neueinbettung nach
  einem Modellwechsel, erst umschalten lassen oder den Wechsel verwerfen und danach auf das frühere
  Release zurückgehen. Eine ältere Version kennt die Generation des Modells nicht und könnte einen
  Wechsel unter gleichem Modellnamen beim Start für erledigt halten. Solange Instanzen verschiedener
  Versionen laufen, lehnt OPAA Wechsel, Verwerfen und Endpunktwechsel ab.

- **Die Distanzfunktion wird beim Start geprüft.** Passt `OPAA_PGVECTOR_DISTANCE_TYPE` nicht zum
  bestehenden Vektorindex, bricht der Start ab; Abhilfe unter
  [„Distanzfunktion ändern“](datenbank.md#distanzfunktion-ändern). Ein Update ändert den Wert nie.

Eine vollständige Neuindizierung brauchen nur: eine verworfene Datenbank und der Austausch einer
fehlerhaften Ollama-Fassung bei langen Eingaben ([Modelle](modelle.md#embedding)).

> **Falle bei einer Neuindizierung:** Eine Datei mit unveränderter Prüfsumme, deren Dokument als
> indiziert gilt, wird übersprungen. Wer nur die Vektoren verwirft, bekommt einen leeren Index. Für
> einen Neuaufbau einer Bibliothek gemeinsam zurücksetzen, dann regulär indizieren:
>
> ```sql
> DELETE FROM vector_store WHERE metadata->>'library_id' = '<library-id>';
> DELETE FROM documents WHERE library_id = '<library-id>';
> DELETE FROM rss_feed_state WHERE library_id = '<library-id>';  -- nur bei Feed-Bibliotheken
> ```

## Kein Rückweg nach der Umstellung der Geheimnisse

Seit der Version mit dem [Schlüsselwechsel](netz-und-sicherheit.md#schlüsselwechsel) speichert OPAA
Zugangsdaten und Modellschlüssel im Format `enc:v2`, sobald alle Instanzen die neue Version fahren,
und stellt den Bestand im Hintergrund darauf um. Eine Version davor liest dieses Format nicht: Nach
einem Rückweg sind die gespeicherten Geheimnisse für sie unlesbar, die Oberfläche zeigt sie als
„nicht gesetzt“, und Abrufe mit Zugangsdaten scheitern.

- **Zurück hinter diese Version nur mit der Rücksicherung** von vor dem Update.
- **Rückweg ohne Rücksicherung offenhalten:** die neue Version mit
  `OPAA_ENCRYPTION_KEYED_FORMAT=false` starten. OPAA schreibt dann weiter das bisherige Format und
  stellt nichts auf das neue Format um. Ist der Rückweg nicht mehr nötig, die Variable entfernen und
  alle Instanzen gemeinsam neu starten.
- **Nachträglich lässt er sich nicht öffnen:** Wer erst nach der Umstellung auf `false` schaltet,
  behält die schon umgestellten Werte im neuen Format; der Weg zurück führt dann weiter nur über die
  Rücksicherung.
- **Abschluss der Umstellung:** Findet der Hintergrundlauf zweimal im Abstand von mindestens fünf
  Minuten keinen Wert mehr im alten Format, lehnt OPAA alte Werte und Klartext danach ab (siehe
  [Zugangsdaten-Verschlüsselung](netz-und-sicherheit.md#zugangsdaten-verschlüsselung)). Ein Start
  mit `OPAA_ENCRYPTION_KEYED_FORMAT=false` hebt diesen Abschluss wieder auf, damit die in dieser Zeit
  geschriebenen Werte nach dem Zurückschalten lesbar bleiben und umgestellt werden; danach schließt
  der Hintergrundlauf die Umstellung von selbst erneut ab. Ebenso hebt jedes Update und jeder
  Rollback, bei dem eine Instanz einer anderen Version läuft, den Abschluss auf; nach dem Update
  schließt der Hintergrundlauf ihn erneut ab.

## Fristen nach einem Rückweg

Die Aufbewahrungsfristen der Chats und der anlassbezogenen Klärungen stehen in der Datenbank. Eine
ältere Version liest sie dort nicht und löscht nach ihren eigenen Vorgaben oder nach den dann
gesetzten Umgebungsvariablen. Vor einem Rückweg die Variablen wieder auf die gewünschten Werte
setzen ([Konfiguration](konfiguration.md#entfallene-und-veraltete-variablen)).

## Weiterführende Kapitel

- [Docker Compose](docker-compose.md#aktualisierung-auf-einen-neuen-stand) und
  [Kubernetes](kubernetes.md#aktualisierung-und-rückweg) — die Befehle, Vorbereitungsschritte und
  der Rückweg je Betriebsweg
- [Mehrere Backend-Instanzen](mehrere-instanzen.md#update-ohne-ausfall) — Update ohne Ausfall
- [Sicherung und Wiederherstellung](sicherung-und-wiederherstellung.md) — die Sicherung vor dem
  Update
- [Releases und Nachweise](releases-und-nachweise.md) — Versionen, Sicherheitsupdates, Update-Paket
