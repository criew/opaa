# Juror „text-ruhe“ – Detailansicht Wissensbibliothek

Blickwinkel: UX-Writing (Wortwahl, Länge, Doppelungen, Hilfetexte), visuelle Ruhe, Hierarchie, kein KI-Look, eine Botschaft je Zustand. Nur helle Bilder. Stichprobe: Confluence, SharePoint, Dateisystem, S3, Upload, Leser (Confluence), privat; `small`, `scale`, `failed`, `partial`, `slow`, dazu C `abschluss`; je 1280 und 320; Reiter Dokumente, Quelle/Läufe bzw. Verlauf, Metadaten, Freigaben, Zuordnungen. Pfadkürzel: `Ist/` = `wt-detail-ist/ui-lab/results/ist-detail/`, `A/`, `B/`, `C/` = `wt-detail-<x>/ui-lab/results/<X>/`. Eigene Vergleichsbögen: `juroren-detail/text-ruhe/cmp_*.jpg`, `top_*.jpg`.

## Punkte

| | Ist | A | B | C |
|---|---|---|---|---|
| Text und Ruhe | **28** | **82** | **63** | **71** |

### Ist – 28
Jeder Reiter beginnt mit Erklärtext: Der Hinweis „Dieser Bestand stammt aus der Quelle …“ steht als getönter Alert, dazu kommen die Überschrift in Mono-Versalien „METADATEN-PFLEGE“ mit zweizeiligem Erklärsatz und drei gleichlautende Zeilen „12 Dokumente ohne Wert (100 %)“. Die Kennzahlzeile ist ein Satz statt Kennzahlen („Letzter Lauf: Fehlgeschlagen · Dokumente: 0 verarbeitet · 23.10.2026, 06:07“), Icons stehen vor jeder Angabe, und „Aktualisiert am“ widerspricht dem Fehler. Im Fall `partial` liegt ein zweizeiliger Erklärbalken zwischen Kopf und Reitern, und „nicht geprüft“ steht neben einem Lauf, der es besser weiß. Leser sehen zwei Alerts übereinander. Bei 320 px wird daraus eine lange Textwand.
Belege: `Ist/dokumente__confluence__failed__light__1280`, `Ist/quelle__sharepoint__partial__light__1280`, `Ist/dokumente__confluence-leser__small__light__1280`, `cmp_dokumente__privat__small__light__320`.

### A – 82
Die ruhigste Variante: eine Spalte, eine Kennzahlzeile, das Zustandswort als letztes Glied und nirgends ein Erklärsatz (`uiWords` Metadaten 46 gegen 363 im Ist). Farbe tragen nur Probleme: „Indiziert“ bleibt grau, „● Fehlgeschlagen“ ist rot, und dadurch hat jeder Zustand genau eine Botschaft. Die Wortwahl ist am saubersten getrennt: „Nicht erreichbar“ ist der Zustand der Quelle, „Nicht abrufbar“ die Ereignisgruppe, „Sonstige Fehler“ steht zuletzt. Die Laufzeile „214 Dokumente · 17 fehlgeschlagen“ wiederholt nichts. Schwach ist die Hierarchie. Der Titel ist kaum größer als der Fließtext, und bei 320 px schrumpft er auf 14 px mit rund 40 px reservierter Leere darunter. Im Normalfall ist „Aktuell“ in der grauen Kennzahlzeile kaum vom Rest zu unterscheiden.
Belege: `A/dokumente__confluence__failed__light__1280`, `A/quelle__sharepoint__partial__light__1280`, `A/laeufe__confluence__scale__light__1280__full`, `A/metadaten__sharepoint__small__light__1280`, `cmp_dokumente__upload__small__light__320`.

### B – 63
Das Band selbst ist ohne Tönung gebaut und wirkt nicht nach KI-Look. Es fügt dem Kopf aber drei Labels und bis zu drei weitere Links hinzu („Verbindung ›“, „Quelle ›“, „Zeitplan ›“); zusammen mit „in 3 Spaces ›“ und „Erika Muster ›“ stehen fünf blaue Pfeil-Links über den Reitern. Der Umfang steht doppelt, im Band und im Reiter Quelle. Es gibt einen echten Wortfehler: Das Dateisystem meldet „Anmeldung abgelehnt“, obwohl es dort keine Anmeldung gibt. Weitere Brüche in der Sprache sind „Hand“ als Auslöser, die nichtssagende Gruppe „Fehler · 13“, „Hinweise (462)“ gegenüber „· n“ an anderen Stellen und „Neuestes“ als Feldname ohne Substantiv. Unruhig wirken die zwei rechten Kanten (Band und Reiter enden bei 984 px, die Liste bei 1240 px) und bei 320 px die zweizeilige Reiterleiste mit verwaister „Zuordnungen“ sowie drei Textlinks untereinander im Upload. Positiv: Der Verlauf hat Platz und bleibt als Tabelle sachlich.
Belege: `B/dokumente__confluence__failed__light__1280`, `cmp_quelle__filesystem__failed__light__320`, `B/laeufe__confluence__scale__light__1280__full`, `B/dokumente__upload__failed__light__1280`, `cmp_dokumente__upload__small__light__320`.

### C – 71
C hat die klarste Hierarchie. Der Titel ist groß, die Beschreibung steht daneben, und der Kopf ist kurz. Bei 320 px fasst eine einzige zugeklappte Zeile „Steckbrief ● Fehlgeschlagen“ den Zustand zusammen, das ist sehr ruhig. Der Abschluss mit ✓/○ und „Ausblenden“ bleibt zurückhaltend und erscheint nur nach dem Anlegen. Dafür ist der Steckbrief fast vollständig blau: Jede Zeile ist ein Link, und „Erika Muster · zuständig“ benennt dieselbe Person anders als der Chip „Eigentümer“. In den Freigaben steht „Entziehen“ in jeder Zeile, dazu kommt die Doppelung „13 Mitglieder · Mitglieder anzeigen“. Mehrere Formulierungen holpern: „Läuft › · 62 %“, „mind. 4 fehlgeschlagen“, „Hochladen“ als Überschrift für den Speicher, die Gruppe „Datei ist größer als 200 MB und wurde übersprungen · 10“ als ganzer Satz, „manuell“ klein gegen „Zeitplan“ groß. Hinzu kommt eine zweizeilige Laufzeile bei 1280 px. Grüne Punkte an jedem indizierten Dokument verteilen Signalfarbe auch dort, wo nichts zu tun ist.
Belege: `C/dokumente__confluence__failed__light__1280`, `C/abschluss__confluence__small__light__1280`, `C/freigaben__confluence__scale__light__1280`, `C/quelle__sharepoint__partial__light__1280`, `top_dokumente__s3__slow__light__1280`, `C/dokumente__upload__failed__light__1280`.

## Stärken und Schwächen

**Ist**
- \+ Der Reiter Quelle hat bereits das ruhige Zeilenraster Verbindung · Umfang · Zeitplan · Ändern.
- \+ Die Laufzeile nennt Zustand und Zahlen.
- \+ Die Chips stehen in fester Reihenfolge.
- − Dauerhinweise und Erklärsätze stehen auf jedem Reiter, Leser sehen zwei Alerts.
- − Die Kennzahlzeile ist ein Satz mit Icons, der Zustand steht doppelt und widersprüchlich („Aktualisiert am“ neben einem Fehler).
- − Mono-Versalien-Überschriften und wiederholte Zeilen „… ohne Wert (100 %)“.

**A**
- \+ Jeder Zustand hat ein Wort, und nur Probleme tragen Farbe.
- \+ Die wenigsten Wörter je Reiter, kein Erklärsatz, Entfernen steckt im ⋯ statt als wiederholtes Textwort.
- \+ Die Fachbegriffe sind sauber getrennt (Nicht erreichbar/Nicht abrufbar, Sonstige Fehler zuletzt, Ergebnis „n · m fehlgeschlagen“).
- − Der Titel ist bei 320 px kleiner als Knopftexte, darunter bleibt reservierte Leere.
- − Das Zustandswort geht im Normalfall in der grauen Kennzahlzeile unter („Aktuell“ ohne Punkt).
- − „Live-Abgleich“ weicht vom Begriff „Live-Aktualisierung“ der Richtung und der anderen Varianten ab.

**B**
- \+ Das Band kommt ohne Tönung und ohne Icons aus, bei allen Arten mit derselben Form.
- \+ Der Verlauf ist ein eigener ruhiger Ort für lange Laufhistorie, der Filter „Nur mit Fehlern (7)“ ist nützlich.
- \+ Bei 320 px liest sich das Band als Label-/Wert-Raster gut.
- − Der Kopf trägt fünf Pfeil-Links und zeigt den Umfang doppelt.
- − Falsches Zustandswort „Anmeldung abgelehnt“ beim Dateisystem, dazu uneinheitliche Wörter („Hand“, „Fehler · 13“, „(462)“, „Neuestes“).
- − Zwei rechte Kanten bei 1280 px, bei 320 px eine zweizeilige Reiterleiste mit Waise.

**C**
- \+ Die stärkste Hierarchie: großer Titel, kurzer Kopf, Reiterleiste weit oben.
- \+ Bei 320 px fasst eine einzige Steckbrief-Zeile mit Zustand alles zusammen.
- \+ Der Abschluss bleibt zurückhaltend (✓/○, „Ausblenden“, nur nach dem Anlegen), und die Zeilen in den Freigaben folgen dem Raster Label | Wert | Aktion.
- − Der Steckbrief ist eine Spalte voller Links, „zuständig“ und „Eigentümer“ stehen für dieselbe Person.
- − „Entziehen“ steht in jeder Zeile, „Mitglieder anzeigen“ doppelt, die Interpunktion holpert („Läuft › · 62 %“, „mind. 4“).
- − Grüne Punkte an jedem Dokument, die Laufzeile bricht bei 1280 px um, und Ereignisgruppen heißen ganze Sätze.

## Was in die Mischung gehört

- **Aus A (Grundgerüst):**
  - die Kennzahlzeile mit dem Zustandswort am Ende und Signalfarbe nur bei Problemen; „Indiziert“ bleibt grau
  - Vokabular und Schreibweise der Läufe: „Teilweise fehlgeschlagen“, „Abgeschlossen“, „Nicht erreichbar“ (Quelle) gegen „Nicht abrufbar“ (Gruppe), „Sonstige Fehler“ zuletzt, Ergebnis „n Dokumente · m fehlgeschlagen“
  - der Füllgrad als „2 von 3 gefüllt (67 %) · Anzeigen“
  - „13 Mitglieder“ als Link selbst, Entfernen im ⋯
  - keinerlei Erklärsätze
- **Aus C:**
  - die Hierarchie des Titels: größere Stufe, bei 320 px nicht unter Fließtextgröße und ohne reservierte Leere; das löst A's Schwäche
  - die einzeilige, zugeklappte Zustandszeile bei 320 px als Vorbild für Kürze
  - der Abschluss nach dem Anlegen (✓/○, „Ausblenden“) als einmaliger Block, in A im Reiter Dokumente oder unter der Kennzahlzeile nur direkt nach dem Anlegen
  - das Zeilenraster Label | Wert | Aktion für Eigentümer, Suchdiagnose und Herleitung in den Freigaben
  - die kompakte Laufzeile bei 320 px („4 s · Zeitplan · abgebrochen“)
- **Aus B:**
  - der Filter „Nur mit Fehlern (n)“ über der Laufliste, sobald sie lang wird (ohne eigenen Reiter)
  - die Feldbezeichnung „Nächster Lauf“ als klarer Kurztext in der Zeile Zeitplan
  - der Titel bei 1280 px auf zwei Zeilen statt einer Zeile mit Tooltip, wenn das Kopfbudget es erlaubt
- **Nicht übernehmen:**
  - das Statusband (Doppelung, Link-Häufung)
  - die Link-Spalte des Steckbriefs
  - das wiederholte „Entziehen“
  - „Hand“, „Neuestes“, „mind.“, „Läuft › ·“

## Empfehlung

**A als Grundgerüst.** A erfüllt die Wünsche des Maintainers am wörtlichsten: der Bereich über den Reitern ist für jede Quellart gleich, die Läufe bleiben knapp, es gibt keine Deko, und ein Zustand trägt ein Wort. A hat die geringste Textmenge und die einheitlichste Sprache. Nachzubessern ist die Titelhierarchie (aus C) und die Sichtbarkeit des Zustandsworts im Normalfall.
