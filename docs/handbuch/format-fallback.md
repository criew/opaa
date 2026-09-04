# Format: Auffang-Pipeline (TXT, DOC, Feed-Text)

> **Entwurf.** Pipeline `tika-fallback`, Version 1. Der gemeinsame Rahmen aller
> Format-Pipelines steht im Kapitel [Indexierung](indexierung.md), Abschnitt 5.

## 1. Wann sie läuft

Die Auffang-Pipeline beansprucht kein Format. Sie bekommt alles, wofür keine spezialisierte
Pipeline registriert ist:

| Fall | Zulassung |
|---|---|
| `.txt` | text-tolerant: Inhalt muss Text sein und die Datei muss `.txt` heißen |
| `.doc` (Word-Binärformat) | strikt: `application/msword`. Die Word-Pipeline kann das alte Format nicht öffnen. |
| Feed-Einträge | kein Format: Der [Feed-Konnektor](konnektor-rss-feed.md) übergibt bereits extrahierten Text, der nie eine Datei war |
| Dateien, deren Format nicht lesbar war | Rückfall mit Vermerk am Chunk, dass die Formaterkennung gescheitert ist |

## 2. Was gelesen wird

Apache Tika über den Spring-AI-Leser. Ein eigener Inhaltshandler schreibt an jeder Seitengrenze
einen Seitenmarker in den Text, damit die Seitenzahl die Extraktion überlebt.

## 3. Struktur und Chunks

Diese Pipeline kennt keine Formatstruktur. Der Text wird in **Token-Fenster** geschnitten:

| Parameter | Standard | Schlüssel |
|---|---|---|
| Chunk-Größe | 1000 Tokens | `opaa.indexing.chunk-size` |
| Überlappung | 100 Tokens | `opaa.indexing.chunk-overlap` |
| Mindestgröße | 350 Zeichen | fest |
| Höchstzahl Chunks je Dokument | 10.000 | fest |

Die Überlappung hängt jedem Chunk ab dem zweiten die letzten 100 Tokens seines Vorgängers
voran, damit ein Satz an der Schnittkante in beiden Chunks vollständig steht. Sie ist die einzige
Pipeline mit Überlappung; die strukturbewussten Pipelines schneiden an Abschnittsgrenzen und
brauchen sie nicht.

Die beiden Schlüssel gelten **nur** hier und für den Rückfall in der [Mail-Pipeline](format-mail.md).
Alle anderen Pipelines haben feste, projektseitig gesetzte Größen.

## 4. Metadaten am Chunk

Die Ortsangabe wird nachträglich aus dem flachen Text rekonstruiert:

| Erkennbar | Beispiel |
|---|---|
| Seitenmarker | `S. 3`, bei Chunks über mehrere Seiten `S. 3–5` |
| Überschriftenzeilen in `#`-Schreibweise | `Abschn. Fristen › Verlängerung` (die zwei tiefsten Ebenen) |
| beides | `S. 3 · Abschn. Fristen › Verlängerung` |
| nichts | keine Ortsangabe |

Die Ortsangabe beschreibt den Beginn des Chunks vor der Überlappung. Die Seitenmarker werden
vor dem Speichern und Embedden wieder entfernt.

**Dokumenteigenschaften:** keine. Für `.txt` und `.doc` stehen dem Metadatenschema nur
Dateiname und Struktur zur Verfügung.

## 5. Scans und Fehler

| Befund | Ergebnis |
|---|---|
| Kein Text, Datei als PDF erkannt | abgewiesen, „kein extrahierbarer Text" |
| Kein Text, anderes Format | fehlgeschlagen, „kein Inhalt" |
| Text vorhanden, aber Zuschnitt ergibt keinen Chunk (etwa nur Rauschen unter der Mindestgröße) | abgewiesen, „kein extrahierbarer Text" |
| Datei beschädigt | Fehler wird als Ausnahme gemeldet |

Die Scan-Erkennung ist nur für PDF verdrahtet. Bilder sind ohnehin nicht zugelassen.

## 6. Nicht verarbeitet

- Texterkennung (OCR)
- Strukturerkennung für `.doc`; ein strukturbewusster Zuschnitt für das alte Word-Format ist
  bewusst nicht gebaut
