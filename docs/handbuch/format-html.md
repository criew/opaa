# Format: HTML

> **Entwurf.** Pipeline `html`, Version 2. Der gemeinsame Rahmen aller Format-Pipelines steht im
> Kapitel [Indexierung](indexierung.md), Abschnitt 5.

## 1. Wann sie läuft

| Inhalt | Prüfung |
|---|---|
| `.html`-Datei | strikt: Inhalt wird als HTML oder XHTML erkannt |
| Feed-Detailseite | keine Formaterkennung: Der [Feed-Konnektor](konnektor-rss-feed.md) reduziert die Seite auf ihre Inhaltsbereiche (nach den Regeln in Abschnitt 3) und übergibt deren HTML direkt an diese Pipeline |

Eine Textdatei mit HTML-artigem Inhalt, die `.md`, `.txt`, `.csv` oder `.eml` heißt, landet
**nicht** hier: Die Textzulassung dieser Endungen hat Vorrang, weil HTML in der
Typhierarchie eine Spezialisierung von Text ist.

## 2. Was gelesen wird

Die Datei wird mit Jsoup geparst; der Zeichensatz wird aus BOM oder `<meta charset>` erkannt,
sonst UTF-8. Bei einer Feed-Detailseite hat der Konnektor die Kodierung bereits aufgelöst.

Die Struktur (Blöcke, Überschriften, Tabellen, Listen) liest derselbe XHTML-Ereignisleser, den
auch die [Confluence-Pipeline](format-confluence.md) verwendet; dort kommen nur die Makroregeln
hinzu.

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
Abschnitte korrekt erkannt werden. h4 bis h6 bleiben im Abschnitt. Absätze, Zitate und ähnliche
Blöcke sind die Trennstellen für das Zusammenlegen und Teilen. Zielgröße rund 4.000 Zeichen,
Obergrenze 20.000, keine Überlappung. Text vor der ersten Überschrift wird ein eigener Chunk
ohne Pfad.

- **Tabellen** werden eine Zeile je Tabellenzeile, Zellen mit „ | " getrennt; eine Tabelle in
  einer Zelle wird in diese Zelle geglättet.
- **Listen** werden eine Zeile je Eintrag; die Verschachtelung trägt der Marker (•, ◦, ▪ nach
  Tiefe, bei nummerierten Listen „2.1.").
- **Vorformatierter Text** (`pre`) behält seine Zeilenumbrüche.
- Geschützte Leerzeichen zählen als Leerraum; ein Absatz nur aus Leerraum entfällt.

Wortgrenzen werden am Quelltext geprüft: `<b>Personal</b>ausweis` bleibt „Personalausweis".

## 4. Metadaten am Chunk

| Feld | Inhalt | Beispiel |
|---|---|---|
| Ortsangabe (`location`) | Abschnittspfad | `Abschn. Leistungen › Personalausweis` |

Der Abschnittspfad steht zusätzlich als erste Zeile im Chunk-Text.

**Dokumenteigenschaften:** `<title>`, die erste `<h1>` und bei Dateien die erste Textzeile des
Inhalts als Titelzeile. Bei einer Feed-Detailseite wird keine Titelzeile gelesen: Eine Meldung
benennt andere Dokumente als sich selbst und würde sonst deren Dokumentart erben.

## 5. Fehler

| Befund | Ergebnis |
|---|---|
| Kein `body` | fehlgeschlagen, „kein Inhalt" |
| Seite besteht nur aus Boilerplate | abgewiesen, „kein extrahierbarer Text" |
| Datei nicht lesbar | Fehler wird als Ausnahme gemeldet, das Dokument ist „fehlgeschlagen" |

## 6. Grenzwerte

Keine eigenen Konfigurationsschlüssel und kein Bytedeckel im Parser. Am
Webverzeichnis-Konnektor greift dessen Dateigrößengrenze, am Feed-Konnektor dessen
Seitengrößengrenze.

## 7. Nicht verarbeitet

- Kopfzeilenwiederholung in Tabellen wie bei [Tabellen](format-tabular.md); eine Tabelle wird
  zeilenweise übernommen
- Linkziele, Alternativtexte, eingebettete Frames
- Strukturierte Daten (JSON-LD, Microdata) und `<meta>`-Angaben außer dem Titel
