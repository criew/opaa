# Juror „einheitlichkeit“ – Detailansicht Wissensbibliothek

Blickwinkel: gleiche Muster über alle 15 Fälle (11 Arten, privat, 3 Leser), Rollen und Reiter. Geprüft habe ich Kanten, Raster, Begriffe, Aktionen und Statusdarstellung, dazu die Passung zu den Gestaltungsleitlinien und zum Reiter Quelle aus F (Zeilen mit „Ändern“).

Stichprobe (nur hell): Confluence, SharePoint, Dateisystem, Web, Upload, Confluence-Leser, privat; Szenarien small, scale, failed, partial, slow; 1280 und 320; alle Reiter. Kopf im Querbogen aller 12 Arten in `failed`. Dazu `tabsTop` aus `metrics*.jsonl`, über alle Arten je Szenario ausgewertet. Arbeitsbögen liegen in `juroren-detail/einheitlichkeit/` (`kopf_failed_{A,B,C}.jpg`, `quelle_*.jpg`, `p_*.jpg` usw.).

Hinweis zum Ist: 9 helle Ist-Bilder sind weiß, darunter `dokumente__confluence__small__light__1280` und `dokumente__rss__small__light__320`. Ich habe Nachbarbilder genommen.

## Punkte

| Variante | Punkte |
|---|---|
| Ist (F) | **30** |
| A | **86** |
| B | **63** |
| C | **60** |

### Ist – 30
Der Kopf ändert seine Höhe je Art, Szenario und Rolle:
- Die Kennzahlzeile mit Icons bricht auf zwei Zeilen um, wenn privat oder „Letzter Lauf …“ dazukommt.
- Der Bestands-Alert, der Leser-Alert („nur Leserechte“) und der Teilfehler-Hinweis mit Randbalken schieben die Reiter nach unten. Belege: `dokumente__confluence__failed`, `quelle__sharepoint__partial`, `quelle__confluence-leser__small`, `dokumente__privat__small` (alle `__light__1280`).

Die Reiter folgen drei Mustern:
- Quelle: Zeilenraster mit „Ändern“ auf einer schmalen Kante (≈ 585 px).
- Metadaten und Freigaben: Mono-Versal-Überschriften, Erklärsätze und eigene Knopfformen („Eigentum übergeben“ als Kastenknopf, „Bearbeiten/Löschen“ inline).
- Zuordnungen: Kastenknopf „In Space verwenden“ links neben einem Erklärsatz.

Auch der Zustand ist uneinheitlich. Die Verbindung steht auf „nicht geprüft“, obwohl der Lauf das Gegenteil weiß, und Dokumentstatus erscheinen als Umriss-Chips („fehlgeschlagen“) statt als Punkt mit Wort (G 5.5).

### A – 86
A ist am konsequentesten:
- **Kopfhöhe:** `tabsTop` liegt bei 1280 in jedem Bild bei 212 px. Bei 320 sind es 331 px, nur der 120-Zeichen-Titel in `scale` braucht 350 px. Gemessen über alle Arten, Szenarien, Reiter und Rollen.
- **Eine Kante:** Kopf, Reiterleiste und alle Reiter haben dieselbe Spalte (720 px). „Ändern“, „Freigeben“, „In Space verwenden“, „Feld anlegen“, ⋯ und Schalter stehen auf derselben rechten Kante. Belege: `quelle_web_small`, `meta_fs_small`, `frei_sp_scale`, `zuord_fs_scale`.
- **Ein Abschnittsmuster:** `DetailSection` (15/600, Textknopf rechts) in allen Reitern, ein Suchbaustein (Lupe, „… enthält …“) für Dokumente, Freigaben und Spaces.
- **Ein Zustandswort mit einer Herkunft:** Arten ohne Anmeldung sagen überall „Nicht erreichbar“, im Kopf, in der Zeile Verbindung und in der Laufzeile. Belege: `kopf_failed_A` (Dateisystem, Web, RSS), `p_quelle_fs_failed`.
- **Statusdarstellung nach G 5.5:** Der Punkt steht nur bei nicht bereiten Dokumenten („● Fehlgeschlagen“). „Indiziert“ steht ohne Punkt.

Abzüge:
- Bei 320 bleibt unter kurzen Titeln eine leere Lücke (drei reservierte Zeilen, `p_quelle_fs_failed`). Der Stift wandert bei 320 von hinter den Titel in die Aktionszeile.
- Die Zusatzangabe des Zustands ist nicht ganz gleich gebaut: „Kontingent voll“ ohne Datum, „Nicht erreichbar am …“ in der Zeile Verbindung, „· 23.10.2026“ im Kopf.

### B – 63
Das Band hat über alle Arten dieselbe Form, ohne Tönung, und ordnet den Kopf sichtbar (`kopf_failed_B`). Die Einheitlichkeit bricht aber an mehreren Stellen:
- **Reiterfolge je Rolle und Art verschieden:** 6 Reiter bei Verwaltenden, 5 bei Lesern (ohne Verlauf), 4 bei Upload. Die Reiterleiste ist damit nicht mehr „gleich je Quellart“.
- **Begriffe:** Dateisystem meldet „Anmeldung abgelehnt“, obwohl es keine Anmeldung gibt. Web und RSS melden im selben Fall „Fehlgeschlagen“ (`kopf_failed_B`, `p_quelle_fs_failed`). Drei Arten ohne Anmeldung zeigen also zwei verschiedene Wörter, und eines davon ist falsch.
- **Signalfarben:** „Kontingent voll“ steht mit orangem Punkt, die übrigen Ernstfälle mit rotem.
- **Kopfhöhe:** 249 px, in `scale` 255 px. Bei 320 px fünf Werte: 296, 317, 328, 340 und 357 px.
- **Upload-Band** mit anderen Feldnamen („Speicher · Neuestes“, Dateiname als Wert).
- **Aktionen nicht nach einem Muster:** Im Reiter Metadaten öffnet das Feld ein ⋯, während Quelle „Ändern“ zeigt. Der Füllgrad steht nicht im Raster von Quelle (`meta_fs_small`).

Der Umfang steht zweimal, im Band und im Reiter Quelle.

### C – 60
C trägt das Zeilenraster von Quelle am weitesten in die anderen Reiter:
- Freigaben mit den Zeilen Eigentümer · Suchdiagnose · Herleitung und „Übergeben / Sperren / Anzeigen“.
- Metadaten mit den Zeilen Kontextpräfix und Extraktion samt „Ändern“.
- Zuordnungen mit „Lösen“ als Textaktion.

Belege: `meta_fs_small`, `frei_sp_scale`, `zuord_fs_scale`. Das passt am besten zum Vorbild „Zeile mit Ändern“.

Das Gerüst selbst ist aber das uneinheitlichste:
- **Steckbrief je Reiter verschieden:** Der Block Quelle fehlt im Reiter Quelle.
- **320 anders als 1280:**
  - Der Chip „Wissen“ entfällt.
  - Die Hauptaktion wird zum reinen Icon.
  - `tabsTop` schwankt zwischen 234 und 322 px, im Abschluss bis 412 px (über 360).
- **Zustandswort widerspricht der Ursache (D3):** Dateisystem, Web und RSS zeigen im Steckbrief „Fehlgeschlagen“. Verbindung und Läufe sagen „nicht erreichbar“ bzw. „Nicht erreichbar“, also auch noch in zwei Schreibungen (`kopf_failed_C`, `p_quelle_fs_failed`).
- **Zwei rechte Kanten:** Inhalt gegen Kopfaktionen und Steckbrief.
- **Kleine Bausteine uneinheitlich:**
  - Suchfelder mit Lupe (Dokumente) und ohne Lupe (Freigaben, Zuordnungen).
  - ⋮ für Ordner, ⋯ für den Kopf.
  - Im Steckbrief steht jede Quellzeile in Linkfarbe.

Die Seitenspalte ist ein Muster, das es sonst in der Anwendung nicht gibt.

## Stärken und Schwächen

**Ist**
- \+ Reiter Quelle: Zeilenraster Verbindung · Umfang · Zeitplan mit „Ändern“ (das Vorbild)
- \+ Chipfolge Art · Quellart · Rolle in allen Arten gleich
- \+ Hauptaktion immer an derselben Stelle oben rechts
- − Reiterhöhe springt mit Alerts, Hinweisen und umbrechender Kennzahlzeile
- − drei Überschriften- und Knopfstile über die Reiter (Mono-Versalien, Kastenknöpfe, Textlinks)
- − Status als Umriss-Chip; „nicht geprüft“ widerspricht dem Lauf

**A**
- \+ Reiterleiste in jedem Bild auf 212 px (1280); eine Kante für alle Reiter und Aktionen
- \+ gemeinsame Bausteine (`DetailSection`, `ListSearch`, `RowMenu`) über alle Reiter
- \+ ein Zustandswort aus `libraryState.ts`, gleich in Kopf, Verbindung und Lauf („Nicht erreichbar“ für Arten ohne Anmeldung)
- − 320 px: leere reservierte Titelzeilen; der Stift wandert in die Aktionszeile
- − Zusatzangabe des Zustands nicht gleich gebaut (Kontingent ohne Datum, „am“ gegen „·“)
- − Entfernen über ⋯, Einstellen über „Ändern“: zwei Aktionsformen nebeneinander (begründet, aber sichtbar)

**B**
- \+ Statusband mit drei festen Feldern bei jeder Art, gleiche Form auch bei Upload
- \+ Kennzahlzeile und Kopf-Links wie in A
- \+ Laufliste mit Filter „Nur mit Fehlern (n)“ und Startknöpfen an einem Ort
- − Reiterfolge wechselt mit Rolle und Art (6, 5 oder 4 Reiter)
- − Zustandswort falsch und uneinheitlich bei Arten ohne Anmeldung; Kontingent mit anderer Signalfarbe
- − Kopfhöhe bei 320 px mit fünf Werten, Upload-Band mit anderen Feldnamen; Metadaten mit ⋯ statt „Ändern“

**C**
- \+ Zeilenraster mit Textaktion auf Metadaten, Freigaben und Zuordnungen ausgeweitet
- \+ Kopf bei 1280 überall 134 px, schmal und gleich
- \+ Textaktionen sichtbar und benannt („Entziehen“, „Lösen“, „Übergeben“)
- − Steckbrief je Reiter verschieden; bei 320 andere Chips und Icon-Hauptaktion
- − Zustandswort widerspricht Verbindung und Lauf; Groß- und Kleinschreibung uneinheitlich
- − zwei rechte Kanten, Suchfelder mit und ohne Lupe, ⋮ neben ⋯, ein Seitenspaltenmuster, das die Anwendung sonst nicht kennt

## Was in eine Mischung gehört

| Muster | Herkunft |
|---|---|
| Eine Spalte mit einer Kante für Kopf, Reiter und alle Aktionen; feste Kopfhöhe | A |
| `DetailSection`, `ListSearch` (Lupe, „… enthält …“) und `RowMenu` als einzige Bausteine aller Reiter | A |
| Zustandswort aus einer Funktion für Kopf, Verbindung und Lauf, einschließlich „Nicht erreichbar“ für Arten ohne Anmeldung | A |
| Punkt nur bei nicht bereiten Dokumenten, Statuswörter einheitlich geschrieben | A |
| Einzelwerte als Zeilen Titel · Inhalt · Textaktion, wie im Reiter Quelle: Eigentümer, Suchdiagnose, Herleitung, Kontextpräfix, Extraktion | C |
| Benannte Textaktionen statt Icons, wo die Zeile nur eine Aktion hat („Übergeben“, „Sperren“) | C |
| Filter „Nur mit Fehlern (n)“ über der Laufliste, Startknöpfe an der Überschrift „Läufe“ | B |
| Zusatzangabe im Zustand immer gleich gebaut: Wort · Datum oder Wort · Zahl, auch bei Upload | B (Feldlogik) auf A übertragen |

Nicht übernehmen: Band als zweite Fläche über den Reitern (B), eine Reiterfolge, die mit der Rolle wechselt (B), die Seitenspalte und andere Chips bei 320 (C).

## Empfehlung

**A als Grundgerüst.** A ist die einzige Variante, deren Kopf, Kante, Begriffe und Bausteine über alle 15 Fälle, alle Reiter und beide Breiten gleich bleiben. Aus C kommt das Zeilenraster für Einzelwerte in Metadaten und Freigaben dazu; damit gilt das „Ändern“-Muster aus Quelle seitenweit. Aus B kommt der Fehlerfilter der Laufliste.
