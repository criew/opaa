# Suche und Indexierung überwachen

> **Entwurf** — gegen den Code geprüft, vom Maintainer noch nicht abgenommen.

> **Wer darf das:** die Systemverwaltung, unter „Administration → Suche & Indexierung“. Die Diagnose
> im Namen einer Person braucht zusätzlich die Befugnis „Sicht als“: einzeln erteilt, höchstens
> zwölf Monate gültig, nie aus einer Rolle.

Die Seite zeigt und diagnostiziert, stellt aber nichts ein. Sie beantwortet die häufigste Frage im
Betrieb: Warum findet jemand ein Dokument nicht?

| Reiter | Zeigt |
|---|---|
| „Überblick“ | Modellrollen mit Endpunkt und Erreichbarkeit, Suchpfade Vektor und Volltext |
| „Indexstatus“ | je Bibliothek Dokumente, Chunks, letzter Lauf, Rückstand, „Nachzug ausstehend“, Stand der Kernfelder; private Bibliotheken nur als eine Zeile ohne Namen |
| „Diagnose“ | eine Testfrage durch die echte Suche, Stufe für Stufe |

## Ein fehlendes Dokument finden

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

## Den Bestand nachziehen

Im Indexstatus startet „Kernfelder nachrüsten“ den **Bestandslauf** je Bibliothek: Er ermittelt
Titel, Dokumentart und Datum für ältere oder nach einem Update veraltete Dokumente neu, ohne neu
einzubetten. Danach bettet der Kontextpräfix-Nachlauf Dokumente mit geändertem Präfix neu ein.
Beide lassen sich anhalten und fortsetzen. Den Wechsel des Embedding-Modells steuern Sie unter
[Modelle verwalten](modelle.md).

**Gut zu wissen:**

- Die Diagnose ist kein Nachweis über vergangene Zugriffe; dafür gibt es die
  [Stichtagsauskunft](stichtagsauskunft.md).
- Ein Zugangsschlüssel erscheint auf der Seite nie, auch nicht gekürzt.
- Wie die Suche arbeitet, Stufe für Stufe: [Suche](../spaces/suche.md).
