# Format: Markdown

> **Entwurf.** Pipeline `markdown`, Version 1. Der gemeinsame Rahmen aller Format-Pipelines
> steht im Kapitel [Indexierung](indexierung.md), Abschnitt 5.

## 1. Zulassung

| Endung | Prüfung |
|---|---|
| `.md` | text-tolerant: Inhalt muss Text sein **und** die Datei muss `.md` heißen |

Die Textzulassung hat Vorrang vor der HTML-Erkennung: Eine Markdown-Datei, die mit einem
`<div>` beginnt, bleibt Markdown.

## 2. Was gelesen wird

Keine Bibliothek. Die Pipeline liest die Datei als UTF-8 und verarbeitet sie zeilenweise mit
einer eigenen, kleinen Zustandsmaschine.

## 3. Struktur und Chunks

- **Überschriften** in der `#`-Schreibweise (ATX) werden bis Ebene 6 erkannt und bis Ebene 3
  geschnitten. Die Unterstreichungs-Schreibweise (Setext, `===` oder `---` unter einer Zeile)
  wird nicht erkannt.
- **Codeblöcke** in Zäunen (```` ``` ```` oder `~~~`) werden nicht geschnitten; ein `#` in einem
  Codeblock ist keine Überschrift. Der Block bleibt ein zusammenhängender Absatz.
- Eine Leerzeile schließt einen Absatz.
- **Frontmatter** (YAML-Block zwischen `---`-Zeilen am Dateianfang) wird aus dem Text entfernt.
  Die einfachen Schlüssel-Wert-Paare darin werden als Dokumenteigenschaften weitergereicht;
  verschachtelte Strukturen werden übersprungen. Ein nicht geschlossener Block bleibt Inhalt,
  damit nicht der Rest des Dokuments verloren geht.

Zielgröße rund 4.000 Zeichen, Obergrenze 20.000, keine Überlappung, Überschriftenzeile am
Anfang jedes Chunks.

## 4. Metadaten am Chunk

| Feld | Inhalt | Beispiel |
|---|---|---|
| Ortsangabe (`location`) | Abschnittspfad | `Abschn. Fristen › Verlängerung` |

**Dokumenteigenschaften:** alle Frontmatter-Skalare (Schlüssel kleingeschrieben) und die erste
Überschrift der Ebene 1. Über diesen Weg erreichen Angaben wie `titel`, `dokumentart` oder
`stand_datum` das Metadatenschema.

## 5. Fehler

| Befund | Ergebnis |
|---|---|
| Nach Entfernen des Frontmatters leer | fehlgeschlagen, „kein Inhalt" |
| Zuschnitt ergibt nichts | abgewiesen, „kein extrahierbarer Text" |
| Datei nicht lesbar | Fehler wird als Ausnahme gemeldet |

## 6. Grenzwerte

Keine eigenen Konfigurationsschlüssel; die Datei wird vollständig in den Speicher gelesen.

## 7. Nicht verarbeitet

- Setext-Überschriften
- Markdown-Tabellen als Struktur (bleiben Fließtext)
- Linkziele, Fußnoten, Listenschachtelung als Struktur, eingebettetes HTML

Hinweis für Entwickelnde: Der Evaluierungskorpus der Suchqualität besteht aus Markdown. Jede
Änderung an dieser Pipeline verschiebt die Messbasis und zieht die Baselines nach.
