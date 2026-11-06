# Suche und Indexierung überwachen

**Ziel:** Sie wissen, warum jemand ein Dokument nicht findet.
{: .kopf }

**Wer:** Systemverwaltung, „Administration → Suche & Indexierung“
{: .kopf }

Die Seite zeigt und diagnostiziert, stellt aber nichts ein.

| Reiter | Zeigt |
|---|---|
| „Überblick“ | Modellrollen (Chat, Einbettung, Reranking, Transkription, Texterkennung) mit Endpunkt und Erreichbarkeit, Suchpfade Vektor und Volltext |
| „Indexstatus“ | je Bibliothek Dokumente, Chunks, letzter Lauf, Rückstand, „Nachzug ausstehend“, Stand der Kernfelder; private Bibliotheken nur als eine Zeile ohne Namen |
| „Diagnose“ | eine Testfrage durch die echte Suche, Stufe für Stufe |

## Fehlendes Dokument finden

1. Reiter „Diagnose“, „Testfrage“ eintragen.
2. Unter „Sicht als“ den Rechtekontext wählen: „Eigener Rechtekontext“, ein Rechteprofil (eine
   Gruppe mit ihren lesbaren Bibliotheken) oder, mit Befugnis und Begründung, „Rechtekontext einer
   Person“.
3. Bei Bedarf unter „Dokument verfolgen (optional)“ das gesuchte Dokument nennen.
4. Lauf starten. Je Stufe stehen Eingang, Ausgang und das Urteil zu jedem Kandidaten; für das
   verfolgte Dokument: nie gefunden oder in welcher Stufe verworfen.

Nie gefunden heißt: Suchbereich, Index und Chunks prüfen. Verworfen heißt: ein Rangproblem.
„Chunk anzeigen“ öffnet den Text nur mit eigenem Leserecht auf die Bibliothek; die Systemrolle
allein öffnet keinen Inhalt.

**Schutz:** Ein Rechteprofil in einem Space verlangt mindestens so viele aktive Konten der Gruppe,
wie die Mindestgruppengröße vorgibt, und steht im Protokoll. „Sicht als“ ist befristet, braucht vor
jedem Lauf eine Begründung, und jede Bibliothek ist ab Werk für diese Sicht gesperrt, bis ihre
Verwaltenden sie lösen. Die betroffene Person sieht die Einträge zu sich.

## Bestand nachziehen

Im Indexstatus startet „Kernfelder nachrüsten“ den **Bestandslauf** je Bibliothek: Er ermittelt
Titel, Dokumentart und Datum für ältere oder nach einem Update veraltete Dokumente neu, ohne neu
einzubetten. Danach bettet der Kontextpräfix-Nachlauf Dokumente mit geändertem Präfix neu ein.
Beide lassen sich anhalten und fortsetzen.

Nach einem Update, das das Zerlegen eines Formats ändert, oder nach einer geänderten Chunk-Größe
zeigt der Indexstatus „Nachzug ausstehend“. Den **Pipeline-Nachzug** stößt die Systemverwaltung
über die Verwaltungsschnittstelle an; eine Oberfläche dafür gibt es nicht.
`GET /api/v1/admin/indexing/pipeline-versions` nennt je Pipeline Kennung und aktuelle Version und je
Bibliothek den Rückstand. `POST /api/v1/admin/indexing/pipeline-reindex` mit dieser Kennung als
`pipelineId` und der Version als `belowVersion` zieht ein Paket nach; wiederholen, bis die Antwort
`done` meldet. Nach einer geänderten Chunk-Größe gilt das für jede Pipeline außer `tika-fallback`
und `email`. Hochgeladene Dokumente und das
Dateisystem werden sofort neu verarbeitet, alle anderen Quellen im nächsten Lauf. Alte Chunks
verschwinden erst, wenn die neuen da sind. Den Wechsel des Embedding-Modells steuern Sie unter
[Modelle verwalten](modelle.md).

Gescannte PDF-Dokumente kommen nach dem Einschalten der Texterkennung nach und nach in den Index:
Quellen mit ihrem nächsten Lauf, hochgeladene Scans über ein Nachholen alle fünf Minuten. Wartende
und gesperrte Scans zeigt jede Bibliothek über ihrer Dokumentliste. Der Nachzug der Pipeline
`pdf-ocr` liest den gespeicherten Erkennungsstand und ruft den Dienst nicht.

**Gut zu wissen:**

- Die Diagnose ist kein Nachweis über vergangene Zugriffe; dafür gibt es die
  [Stichtagsauskunft](stichtagsauskunft.md).
- Ein Zugangsschlüssel erscheint auf der Seite nie, auch nicht gekürzt.
- Wie die Suche arbeitet: [Wie die Suche arbeitet](../referenz/wie-die-suche-arbeitet.md).
