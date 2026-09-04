# Format: OpenDocument Präsentation (ODP)

> **Entwurf.** Pipeline `odp`, Version 2. Der gemeinsame Rahmen aller Format-Pipelines steht im
> Kapitel [Indexierung](indexierung.md), Abschnitt 5. Das Verhalten entspricht weitgehend dem von
> [PowerPoint](format-pptx.md); hier stehen die Unterschiede.

## 1. Zulassung

| Endung | Prüfung |
|---|---|
| `.odp` | strikt: OpenDocument-Präsentation |

Flat-XML (`.fodp`) ist nicht zugelassen.

## 2. Was gelesen wird

Derselbe gehärtete XML-Leser wie bei [ODT](format-odt.md): der Inhalt für die Folien, die
Formatvorlagen für die Masterfolien.

## 3. Struktur und Chunks

**Eine Folie ist ein Chunk**, Aufbau wie bei PowerPoint:

1. Titel aus dem Rahmen mit der Präsentationsklasse „title" als erste Zeile; alle anderen
   Rahmen, auch Untertitel, sind Fließtext
2. Text aller Rahmen in Reihenfolge, Tabellen zeilenweise mit ` | `
3. Notizen als abschließender Absatz „Notizen: …"; Kopf-, Fuß-, Datums- und
   Seitenzahl-Platzhalter werden ausgelassen

**Masterfolien.** Anders als bei PowerPoint wird der Text der Masterfolien gelesen: alle
Absätze aller Masterseiten, dedupliziert, als **ein** führender Chunk mit der Ortsangabe
„Masterfolie". Ein Vortragstitel oder eine Organisationsangabe, die auf jeder Folie im Master
steht, wird so einmal gefunden.

Ein Chunk ist auf 20.000 Zeichen begrenzt; keine Zielgröße, keine Zusammenlegung, keine
Überlappung.

## 4. Metadaten am Chunk

| Feld | Inhalt | Beispiel |
|---|---|---|
| Ortsangabe (`location`) | Foliennummer und Titel | `Folie 3: Zuständigkeiten` |
| Ortsangabe des Master-Chunks | fest | `Masterfolie` |

**Dokumenteigenschaften:** Titel und Daten aus `meta.xml`. Keine erste Überschrift, weil Folien
keine Überschriftenhierarchie haben.

## 5. Fehler

| Befund | Ergebnis |
|---|---|
| Beschädigtes ZIP, fehlende Inhaltsdatei, Grenzwert gerissen | fehlgeschlagen, „kein Inhalt" |
| Keine Folie oder keine Folie mit Text | abgewiesen, „kein extrahierbarer Text" |
| Formatvorlagen-Datei defekt | nur der Master-Chunk entfällt |

Der Masterfolien-Text rettet eine Präsentation ohne Folientext nicht vor der Einstufung als
textlos.

## 6. Grenzwerte

Wie bei [ODT](format-odt.md) unter `opaa.indexing.odf.*`, mit `max-odp-slides` (Standard 5.000)
an Stelle der Absatzgrenze.

## 7. Nicht verarbeitet

- Verschachtelte Tabellen in Tabellenzellen gehen verloren
- Platzhalterklassen an Rahmen der Folie selbst (außerhalb der Notizen) werden nicht
  ausgewertet
- Animationen, Kommentare, Alternativtexte von Bildern
