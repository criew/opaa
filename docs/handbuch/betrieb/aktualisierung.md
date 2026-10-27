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
Versionsfensters auch ohne Rücksicherung ([Update ohne Ausfall](mehrere-instanzen.md#update-ohne-ausfall)).

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
