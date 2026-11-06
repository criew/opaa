# UI-Lab #2640 · Ist-Stand

`main` (79719b6b) nur mit den neuen Mock-Szenarien, ohne Variante (`?variant=none`). Messlauf mit
`capture.mjs --flow ocr`: hell 1280 und 320 px über alle Szenarien, dunkel als Stichprobe `default`.

## Was der Ist-Stand zeigt

- **(a) Modellverwaltung:** Es gibt keinen Reiter „Texterkennung“; `/admin/models/text-recognition`
  leitet auf „Chat-Modelle“ um. Die Bereiche `test`, `blocked`, `vision` sind deshalb `not-found`
  (9 Zustände). Vorbild für alle Varianten sind die Reiter „Reranking“ und „Spracheingabe“.
- **(b) Beleg und Viewer:** Das Belegfenster zeigt Scan-Belege wie jeden anderen Beleg, ohne
  Kennzeichnung. „Dokument öffnen“ auf dem Scan springt zur Fundseite und meldet in Warnfarbe
  „Die Fundstelle wurde im Original nicht gefunden – angezeigt wird die Fundseite.“
  (`viewer__beleg__default__light__1280.png`): Der Scan hat keine Textebene, die Textsuche
  greift nicht. Genau dieser Fall ist der Anlass von #3605.

## Kennzahlen (compare.py)

| Kennzahl | Wert |
| --- | --- |
| Screenshots | 34 |
| axe-Knoten (hell) | 0 |
| Kontrastverstöße (hell und dunkel) | 0 |
| Überlauf bei 320 px | 0 |
| Budgetüberschreitungen | 0 |
| Fokus verdeckt | 3 (nur Shell: „Zum Inhalt springen“, „Menü öffnen“ bei 320 px) |
