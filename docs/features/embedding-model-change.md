# Wechsel des Embedding-Modells

> **Status: Zielbild, nicht gebaut.** Konzept zu Issue #3214. Gebaut ist die Neueinbettung selbst
> (Zielspalte, Lauf mit Besitz, Abbruch, Fortsetzen, Übernahme, Umschalten); neu sind der geführte
> Wechsel in der Oberfläche, die Vorschau, das Verwerfen und der Endpunktwechsel.
> Entscheidungen: [ADR-0061](../decisions/0061-wechsel-des-embedding-modells.md), ergänzt
> [ADR-0060](../decisions/0060-schlanke-konfiguration-und-einrichtungsassistent.md), Entscheidung 10.

## Motivation

Die Installation legt das Embedding-Modell fest, mit einer Probeeinbettung, die die Dimension der
Vektortabelle bestimmt. Häuser wechseln es später aus guten Gründen: ein Modell, das deutsche Texte
besser trifft, ein Anbieter, der ein Modell abkündigt, ein eigener Server statt eines Cloud-Dienstes.
Heute ist das ein Eingriff in die Datenbank bei angehaltenen Instanzen, ohne Probeeinbettung und
ohne Möglichkeit, den Zugangsschlüssel zu ändern. Dasselbe gilt für den bloßen Umzug auf einen
anderen Server mit demselben Modell, etwa bei einem Ausfall.

Ziel ist ein Wechsel, den die Systemverwaltung in der Oberfläche durchführt: mit Prüfung, mit einer
ehrlichen Ankündigung von Dauer und Folgen, mit Abbruch und Rückweg bis zum Umschalten.

---

## Überblick

1. **Zwei Vorgänge:** Modellwechsel (anderes Modell oder andere Dimension, mit Neueinbettung) und
   Endpunktwechsel (gleiches Modell, andere Adresse, anderer Schlüssel oder andere Eingabegrenze,
   ohne Neueinbettung).
2. **Probeeinbettung vor allem anderen**, wie in der Installation.
3. **Vorschau** mit Chunks, Tokens, Dauer, Speicherbedarf und der angekündigten Unterbrechung der
   Vektorsuche.
4. **Bestätigung** setzt das neue Modell und startet die Neueinbettung; Suche und Chat antworten
   bis zum Umschalten mit der Volltextsuche.
5. **Abbrechen** pausiert, **Verwerfen** kehrt vor dem Umschalten zum alten Modell zurück; nach dem
   Umschalten gibt es keinen Rückweg außer einem neuen Wechsel.
6. **Mehrinstanzbetrieb:** genau ein Wechsel, gestartet nur bei gleicher Version aller Instanzen;
   Lauf und Übernahme wie gebaut.
7. **Kein Datenbankeingriff mehr** im Handbuch.

---

## Begriffe

| Begriff | Bedeutung |
|---|---|
| **Modellwechsel** | Wechsel auf ein Modell, dessen Vektoren mit den gespeicherten nicht vergleichbar sind; verlangt die Neueinbettung des Bestands |
| **Endpunktwechsel** | Änderung von Adresse, Schlüssel oder Eingabegrenze bei gleichem Modell; die gespeicherten Vektoren bleiben gültig |
| **Vorgänger** | Das bisherige Modell samt Schlüssel, aufbewahrt von der Bestätigung bis zum Umschalten oder Verwerfen |
| **Referenzvektor** | Der Vektor des festen Probetexts, gespeichert mit dem Modell; Maßstab des Endpunktwechsels |
| **Umschalten** | Der Schritt am Ende der Neueinbettung, nach dem die neuen Vektoren gelten und die alten wegfallen |

---

## Ablauf des Modellwechsels

```
┌──────────────────────────────────────────────────────────────────────┐
│  Modelle › Embedding                                                 │
│  Aktiv: nomic-embed-text (768 Dimensionen) · https://ollama:11434    │
│  ──────────────────────────────────────────────────────────────────  │
│  Modell wechseln                                                     │
│  Basis-Adresse   [ https://ollama:11434/v1        ]                  │
│  Modell-Kennung  [ bge-m3                         ]                  │
│  Schlüssel       [ ••••••••  ]   Eingabegrenze [ 8192 ]              │
│                                          [Probeeinbettung]           │
│  ✓ Probeeinbettung erfolgreich: 1024 Dimensionen                     │
│                                                                      │
│  Vorschau                                                            │
│  Chunks              48.120 (davon privat: nicht ausgewiesen)        │
│  Tokens              etwa 19 Mio.                                    │
│  Dauer               etwa 7 Stunden (gemessen am neuen Endpunkt)     │
│  Speicher            etwa 0,4 GB zusätzlich (Tabelle heute 1,1 GB)   │
│                                                                      │
│  ⚠ Ab dem Start bis zum Abschluss ist die Vektorsuche ausgesetzt.    │
│    Suche und Chat finden nur über die Volltextsuche.                 │
│  ⚠ Nach dem Abschluss fallen die bisherigen Vektoren weg; zurück     │
│    geht es dann nur mit einem neuen Wechsel.                         │
│                                       [Abbrechen]  [Wechsel starten] │
└──────────────────────────────────────────────────────────────────────┘
```

1. **Eingabe.** Basis-Adresse, Modell-Kennung, Schlüssel und Eingabegrenze, mit denselben Regeln
   wie in der Installation: Adressprüfung der Modelle, keine Zugangsdaten in der Adresse, Grenze der
   Dimension des Vektorindex. Ein leerer Schlüssel übernimmt den bisherigen nicht; „Schlüssel
   beibehalten" ist eine eigene Wahl, damit ein anderer Anbieter nicht versehentlich den alten
   Schlüssel erhält.
2. **Probeeinbettung.** Der feste Probetext ergibt Erfolg und Dimension. Gleicht das neue Modell
   dem aktiven (gleiche Kennung, gleiche Dimension, Probevektor nahe am Referenzvektor), bietet die
   Oberfläche statt des Modellwechsels den Endpunktwechsel an.
3. **Vorschau** (nächster Abschnitt).
4. **Bestätigung.** „Wechsel starten" öffnet einen Dialog mit den beiden Warnungen und verlangt
   eine ausdrückliche Bestätigung. Der Server bettet die Probe erneut ein; weicht die Dimension ab,
   lehnt er ab. Dann wird in einer Transaktion das neue Modell gesetzt, der Vorgänger bewahrt, die
   leere Zielspalte angelegt und der Lauf gestartet.
5. **Neueinbettung** wie gebaut: Dokument für Dokument, mit Fortschritt im Reiter „Indexstatus"
   der Seite „Suche & Indexierung", Abbruch, Fortsetzen und Übernahme.
6. **Umschalten.** Haben alle Chunks einen neuen Vektor, baut der Lauf den Vektorindex der
   Zielspalte und tauscht die Spalten. Der Vorgänger wird gelöscht, die Vektorsuche läuft wieder.

Es gibt keinen Entwurf auf dem Server: Eingaben und Probeergebnis leben in der Oberfläche, bis die
Bestätigung sie in einem Aufruf schickt.

---

## Vorschau

| Angabe | Herleitung |
|---|---|
| Chunks | Zahl der Chunks des Bestands wie im Fortschritt der Neueinbettung; private Bibliotheken nur, wenn genug Personen solche führen (Mindestgruppengröße) |
| Tokens | Textlänge der Chunks geteilt durch eine feste Zeichenzahl je Token, ausgewiesen als „etwa" |
| Dauer | Chunks geteilt durch den Durchsatz des neuen Endpunkts aus einer Durchsatzprobe; ohne Probe aus `opaa.indexing.embedding-rate-estimate`, dann so gekennzeichnet |
| Speicher | Chunks mal Größe eines Vektors der neuen Dimension, dazu der Vektorindex; daneben die heutige Größe der Vektortabelle |
| Unterbrechung | Vektorsuche ausgesetzt von der Bestätigung bis zum Umschalten, mit der geschätzten Dauer |

- Die **Durchsatzprobe** schickt einen kleinen Stapel künstlicher Texte in der Zielgröße der Chunks
  an den neuen Endpunkt. Vor der Bestätigung geht kein Inhalt des Bestands an den neuen Empfänger.
- **Kein Preis.** OPAA kennt die Tarife der Anbieter nicht; die Tokenzahl lässt sich mit dem
  eigenen Tarif rechnen.
- **Speicher nach dem Umschalten.** Die alte Spalte entfällt, ihren Platz gibt PostgreSQL aber erst
  frei, wenn die Zeilen neu geschrieben werden. Die Vorschau nennt das; freien Plattenplatz kann
  OPAA nicht sehen.

---

## Während des Wechsels

| Bereich | Verhalten |
|---|---|
| Suche und Chat | Vektorsuche ausgesetzt, Volltextsuche allein; Chat zeigt „Eingeschränkte Suche", das Erklärprotokoll die Stufe als nicht verfügbar |
| Reranking | unverändert; es arbeitet auf dem Text der Kandidaten |
| Indexierung, Uploads | laufen weiter; neue und geänderte Chunks bekommen nur den Vektor des neuen Modells |
| Nachläufe (Kontextpräfix, Nachzug, Metadatenschema) | laufen weiter und schreiben in die Zielspalte |
| Modelle › Embedding | zeigt aktives Modell, Vorgänger und Fortschritt; Endpunktwechsel und ein zweiter Modellwechsel sind gesperrt |
| Statusseite | „Index veraltet" mit beiden Modellen, wie gebaut |

Ein doppeltes Einbetten neuer Dokumente mit beiden Modellen gibt es nicht.

---

## Abbrechen, Verwerfen, Rückweg

| Aktion | Wann | Wirkung |
|---|---|---|
| Abbrechen | Lauf läuft | Lauf hält nach dem aktuellen Dokument an; das Eingebettete bleibt; Vektorsuche bleibt ausgesetzt |
| Fortsetzen | Lauf angehalten oder fehlgeschlagen | macht bei den Chunks ohne neuen Vektor weiter |
| Verwerfen | vor dem Umschalten | Vorgänger wird wieder aktiv, Zielspalte entfällt, ein laufender Lauf endet; Vektorsuche sofort wieder mit dem alten Modell |
| Zurück nach dem Umschalten | — | nur als neuer Modellwechsel mit vollständiger Neueinbettung |

Chunks, die seit der Bestätigung neu oder mit geändertem Text geschrieben wurden, haben nach dem
Verwerfen keinen Vektor; sie findet nur die Volltextsuche, bis ihr Dokument neu indiziert wird. Der
Dialog des Verwerfens nennt ihre Zahl.

---

## Endpunktwechsel

Ein Endpunktwechsel ändert Basis-Adresse, Schlüssel oder Eingabegrenze, ohne die gespeicherten
Vektoren anzufassen. Er gelingt nur, wenn

- die Modell-Kennung gleich bleibt,
- die Probeeinbettung dieselbe Dimension liefert und
- ihr Vektor dem Referenzvektor nahezu gleicht (Kosinus-Ähnlichkeit mindestens 0,99, fest im Code).

Der Referenzvektor entsteht mit der Probeeinbettung der Installation, eines Modellwechsels und
jedes Endpunktwechsels. Fehlt er, bettet die Prüfung den Probetext mit dem bisherigen Endpunkt ein;
ist dieser nicht erreichbar, lehnt sie ab und nennt den Modellwechsel als Weg. Ein abgelehnter
Endpunktwechsel nennt die gemessene Ähnlichkeit. Gespeichert wird in einer Transaktion; die
Instanzen bauen ihren Modell-Client über den Invalidierungsbus neu. Während eines Modellwechsels ist
der Endpunktwechsel gesperrt.

Typischer Anlass ist der Ausfall: Ein Ersatzserver mit demselben Modell wird eingetragen, ohne
Neustart und ohne Neueinbettung.

---

## Mehrinstanzbetrieb

- **Genau ein Wechsel.** Bestätigung und Verwerfen laufen in einer Transaktion unter dem Schemalock
  der Vektortabelle; ein zweiter Wechsel wird mit `409` abgelehnt.
- **Gleiche Version.** Die Bestätigung verlangt, dass alle lebenden Instanzen dieselbe Version
  melden; sonst `409` mit dem Hinweis, das Rolling Update abzuschließen.
- **Lauf, Besitz, Übernahme** wie gebaut: Der Lauf gehört der bestätigenden Instanz, jede Instanz
  kann abbrechen, eine lebende übernimmt den Lauf einer toten.
- **Spaltenstand und Modell-Client** erfahren die Instanzen über den Invalidierungsbus. Ein
  Schreiber bettet nur mit dem Modell ein, das sein Spaltenstand für die gewählte Spalte nennt;
  passt sein Client nicht dazu, liest er beides neu, bevor er schreibt.

---

## Revisionsprotokoll

| Ereignis | Anlass | Inhalt |
|---|---|---|
| `EMBEDDING_MODEL_CHANGED` | Bestätigung eines Modellwechsels | Modell, Adresse ohne Zugangsdaten, Dimension und Eingabegrenze vorher und nachher; Schlüssel nur als „gesetzt", „geändert", „beibehalten" oder „entfernt"; Chunks der Vorschau |
| `EMBEDDING_MODEL_CHANGE_DISCARDED` | Verwerfen | zurückgesetztes und verworfenes Modell; Zahl der Chunks ohne Vektor |
| `EMBEDDING_MODEL_ENDPOINT_CHANGED` | Endpunktwechsel | Adresse und Eingabegrenze vorher und nachher, Schlüsselstatus, gemessene Ähnlichkeit |
| `EMBEDDING_REINDEX_STARTED`, `…_CANCELLED`, `…_COMPLETED` | Lauf, Fortsetzen, Abbruch, Umschalten | wie gebaut |

Ein abgelehnter Endpunktwechsel oder eine fehlgeschlagene Probeeinbettung erzeugt kein Ereignis; es
ändert sich nichts.

---

## Schnittstelle

Im Thema `models`, nur für die Systemverwaltung:

| Operation | Zweck |
|---|---|
| Probe des Embedding-Wechsels | Probeeinbettung und Durchsatzprobe eines Entwurfs; liefert Dimension, Art des Vorgangs (Modell- oder Endpunktwechsel, mit Ähnlichkeit) und die Vorschau |
| Modellwechsel starten | Bestätigung mit den Angaben des Entwurfs |
| Modellwechsel verwerfen | vor dem Umschalten |
| Endpunkt ändern | Endpunktwechsel |

Fortschritt, Abbruch und Fortsetzen bleiben die bestehenden Operationen der Neueinbettung im Thema
`indexing`. Der Zugangsschlüssel kommt nur schreibend vor, wie in der Modellverwaltung.

---

## Was entfällt

- Der Datenbankeingriff für Modell- und Endpunktwechsel im Handbuch, samt dem Hinweis, der Schlüssel
  lasse sich so nicht ändern.
- „Nicht über die Oberfläche änderbar" in Installation, Modellverwaltung und Fakten-Manifest.

Der Startabgleich bleibt: Passt die Modellzeile beim Start nicht zum Index, etwa nach einer
Rücksicherung, setzt er die Vektorsuche wie gebaut aus und legt die Zielspalte an. Einen Vorgänger
gibt es dann nicht, Verwerfen also auch nicht.

---

## Integrationspunkte

- **Installation** ([installation-and-settings.md](./installation-and-settings.md)): gleiche Prüfung
  der Angaben, gleiche Probeeinbettung; der Referenzvektor entsteht dort zuerst.
- **Modellverwaltung** ([llm-integration.md](./llm-integration.md)): Reiter Embedding wird
  bearbeitbar, mit den beiden Vorgängen.
- **Mehrinstanzbetrieb** ([ADR-0053](../decisions/0053-mehrinstanzbetrieb-aktiv-aktiv.md),
  Entscheidung 8): Lauf, Besitz und Übernahme der Neueinbettung.
- **Suche** ([hybrid-retrieval.md](./hybrid-retrieval.md)): Aussetzung der Vektorsuche wie gebaut.
- **Kostenvorschau** ([metadata-schema.md](./metadata-schema.md)): gleiche Durchsatzmessung je
  Embedding-Modell.

---

## Wirkung auf Behördenunterlagen

- [x] Neue oder geänderte personenbezogene Daten oder Protokollereignisse — drei neue
  Revisionsereignisse, ohne Personenbezug über die handelnde Person hinaus
- [ ] Neue Sichtbarkeit: Daten werden für weitere Rollen oder Personen sichtbar
- [ ] Neue Auswertung oder neuer Export, auch von bereits gespeicherten Daten
- [ ] Geänderte Aufbewahrungs- oder Löschfrist
- [x] Neuer externer Datenfluss (neuer Dienst, neues Ziel ausgehender Verbindungen) — die
  Systemverwaltung kann den Empfänger der Textabschnitte und Suchanfragen ändern; vor der
  Bestätigung gehen nur Probetexte dorthin
- [x] Geänderte Voreinstellung, geändertes Standardmodell oder geänderter System-Prompt — das
  Embedding-Modell ist nach der Installation änderbar
- [ ] Geänderte KI-Einstufung (Zweck oder Einsatzbereich im Sinne der KI-Verordnung)
- [ ] Keine Wirkung auf Daten, Sichtbarkeit, Auswertung, Datenflüsse oder Voreinstellungen

**Voraussichtliche Stufe:** Anzeige

**Betroffene Vorlagen in `docs/behoerden/`:** Verzeichnis der Datenflüsse und Betriebsunterlagen
(Verzeichnis beim Umsetzen bestimmen)

---

## Offene Fragen / Zukünftige Erweiterungen

- Modelle mit mehr Dimensionen, als der Vektorindex aufnimmt (etwa über `halfvec` oder eine
  verkürzte Ausgabe des Modells), bleiben abgelehnt.
