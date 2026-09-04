# Format: HTML

> **Entwurf.** Pipeline `html`, Version 1. Der gemeinsame Rahmen aller Format-Pipelines steht im
> Kapitel [Indexierung](indexierung.md), Abschnitt 5.

## 1. Zulassung

| Endung | Prüfung |
|---|---|
| `.html` | strikt: Inhalt wird als HTML oder XHTML erkannt |

Eine Textdatei mit HTML-artigem Inhalt, die `.md`, `.txt`, `.csv` oder `.eml` heißt, landet
**nicht** hier: Die Textzulassung dieser Endungen hat Vorrang, weil HTML in der
Typhierarchie eine Spezialisierung von Text ist.

Wichtig für Feeds: Die Detailseiten des [Feed-Konnektors](konnektor-rss-feed.md) erreichen
diese Pipeline nie. Der Konnektor extrahiert den Hauptinhalt selbst und übergibt reinen Text an
die [Auffang-Pipeline](format-fallback.md). Diese Pipeline verarbeitet HTML-**Dateien**, etwa
aus einem Verzeichnis oder einem Upload.

## 2. Was gelesen wird

Die Datei wird mit Jsoup geparst; der Zeichensatz wird aus BOM oder `<meta charset>` erkannt,
sonst UTF-8.

## 3. Struktur und Chunks

**Boilerplate entfernen, zweistufig:**

1. Unbedingt, in der ganzen Seite: Navigation, Seitenleisten, Menüs, Brotkrumen,
   Cookie-Banner, Skripte und Stylesheets.
2. Nur außerhalb der Inhaltsbereiche: Seitenkopf und Seitenfuß (`header`, `footer` und die
   entsprechenden Rollen). Ein Artikel darf einen eigenen Kopf und Fuß haben.

**Inhaltsbereich wählen:** `main`, `article` oder `[role=main]`. Jeder Treffer wird
verarbeitet, etwa alle Teaser einer Übersichtsseite. Ein Treffer, der in einem anderen Treffer
steckt, wird verworfen, damit derselbe Inhalt nicht doppelt gespeichert wird. Gibt es keinen
Treffer, wird der ganze `body` genommen.

**Schneiden:** an den Überschriften h1 bis h3, über die DOM-Struktur, sodass verschachtelte
Abschnitte korrekt erkannt werden. h4 bis h6 bleiben im Abschnitt. Absätze, Listenpunkte,
Tabellenzeilen, Zitate und ähnliche Blöcke sind die Trennstellen für das Zusammenlegen und
Teilen. Zielgröße rund 4.000 Zeichen, Obergrenze 20.000, keine Überlappung. Text vor der ersten
Überschrift wird ein eigener Chunk ohne Pfad.

Wortgrenzen werden am Quelltext geprüft: `<b>Personal</b>ausweis` bleibt „Personalausweis".

## 4. Metadaten am Chunk

| Feld | Inhalt | Beispiel |
|---|---|---|
| Ortsangabe (`location`) | Abschnittspfad | `Abschn. Leistungen › Personalausweis` |

Der Abschnittspfad steht zusätzlich als erste Zeile im Chunk-Text.

**Dokumenteigenschaften:** `<title>` und die erste `<h1>`.

## 5. Fehler

| Befund | Ergebnis |
|---|---|
| Kein `body` | fehlgeschlagen, „kein Inhalt" |
| Seite besteht nur aus Boilerplate | abgewiesen, „kein extrahierbarer Text" |
| Datei nicht lesbar | Fehler wird als Ausnahme gemeldet, das Dokument ist „fehlgeschlagen" |

## 6. Grenzwerte

Keine eigenen Konfigurationsschlüssel und kein Bytedeckel im Parser. Am
Webverzeichnis-Konnektor greift dessen Dateigrößengrenze.

## 7. Nicht verarbeitet

- Tabellenstruktur: eine Tabelle ist nur eine Blockgrenze, es gibt keine Kopfzeilenwiederholung
  wie bei [Tabellen](format-tabular.md)
- Linkziele, Alternativtexte, eingebettete Frames
- Strukturierte Daten (JSON-LD, Microdata) und `<meta>`-Angaben außer dem Titel
