# Variante A „Alles an seinen Ort“ – Bericht (Runde 3, 25.10.2026)

Branch `feature/3084_ui-lab-detail-var-a`, Basis `feature/3084_ui-lab-detail-base`. Matrix: alle 11 Arten × `small scale failed empty partial slow` × hell/dunkel × 1280/320, dazu `privat`, `upload-leser`, `confluence-leser`, `nextcloud-leser` in `small scale failed`. Bilder `<bereich>__<art>__<szenario>__<schema>__<breite>[__full].jpg` (JPEG q70; `__full` nur hell, halbe Breite). Kennzahlen stehen in `metrics.jsonl` und `metrics-extra.jsonl`. Die Befunde aus `REVIEW-1.md` sind unten einzeln abgehakt.

## Konzept

- **Eine Spalte, 720 px:** Kopf, Reiterleiste und alle Reiter haben die Breite von Assistent und Bearbeiten (`CONTENT_WIDTH`).
- **Ein Kopf für alle Arten, feste Höhe:**
  - Chips (Art · Quellart in Kurzform · Rolle) · Titel (1280 px eine Zeile, 320 px zwei, Rest im Tooltip) · Beschreibung (eine Zeile) · Kennzahlzeile.
  - Hauptaktion rechts oben: „Jetzt indizieren“, bei Upload „Hochladen“ an derselben Stelle.
  - Die Reiterleiste liegt in jedem Bild der Matrix auf derselben Höhe.
- **Ein Zustandswort** als letztes Element der Kennzahlzeile, bei 320 px als erste Zeile.
  - Form: Punkt + Wort + Datum, Prozent oder Zahl.
  - Das Wort ist ein Link zur Ursache; Leser bekommen es ohne Link.
  - Abgeleitet an einer Stelle (`libraryState.ts`); Laufzeile und Kopf nutzen dieselbe Funktion `runWord`.
- **Zwischen Kopf und Reitern steht nichts.**
  - Abgelehnte Anmeldung oder „Nicht erreichbar“ stehen an der Zeile Verbindung, nicht lesbare Bereiche am Listeneintrag im Umfang.
  - Gescheiterte geplante Läufe stehen an der Zeile Zeitplan, nur wenn die Verbindung nicht schon die Ursache nennt.
- **Ein Abschnittsmuster** (`DetailSection`, 15/600): Hinzufügen-Aktion als Textknopf rechts auf der Kante von „Ändern“, Entfernen immer im ⋯ einer Zeile (`RowMenu`), ein Suchfeld-Baustein ab 8 Einträgen (`ListSearch`).
- **Läufe** im Reiter Quelle als Tabelle (Zustand · Beginn · Dauer · Auslöser · Ergebnis). Die Zeile ist ein Knopf in einer Liste.
  - Aufgeklappt: nicht lesbare Bereiche, Fehler nach Grund (die größte Gruppe zuerst, „Sonstige Fehler“ zuletzt, 5 Fundorte, „alle n anzeigen“).
  - Hinweise und technische Angaben sind zugeklappt.

## Status je Punkt

| Nr | Status | Beleg |
|---|---|---|
| D1 Ein Kopf, Reiter auf gleicher Höhe | erfüllt | `tabsTop` in **allen** 1 800 Bildern (alle Arten, Szenarien, Reiter, Rollen): 1280 px 212, 320 px 292 (Budget ≤ 260 / ≤ 360) |
| D2 Ein Zustandsfeld | erfüllt (Wortliste ergänzt) | `dokumente__gdrive__failed__light__1280` „Anmeldung abgelehnt · 23.10.2026“, `dokumente__filesystem__failed__…` „Nicht erreichbar“, `dokumente__s3__slow__…` „Läuft · 62 %“, `dokumente__upload__slow__…` „Läuft · 17 von 26“ |
| D3 Zustand ist wahr | erfüllt | Kopf, erste Laufzeile und Detail mit demselben Wort und Datum; „fehlgeschlagen“ zählt `documentsFailed`, fehlende Fundorte stehen als „n weitere fehlgeschlagen, ohne gespeicherten Fundort“ (`laeufe__*__scale`) |
| D4 Ernstfall von jedem Reiter | erfüllt | `freigaben__smb__failed__light__1280`, `quelle__smb__failed__light__1280` (Verbindung „Anmeldung abgelehnt am …“ · Ändern) |
| D5 Kennzahlen ohne Doppelung | erfüllt | Spaces und Eigentümer als Links mit „›“; Listenfuß nur „Seite 1 von 63“ |
| D6 Rolle einmal | erfüllt | `*__confluence-leser__*`: Chip „Leser“, kein Alert, keine ausgegrauten Schalter, Zustandswort ohne Link |
| D7 Laufender Vorgang einmal | erfüllt | `laeufe__s3__slow__light__1280`: „Läuft · 62 %“ in Kopf und Zeile, „16 von 26“ als Ergebnis |
| D8 Nichts zwischen Kopf und Reitern | erfüllt | `quelle__nextcloud__partial__light__1280` (Markierung am Eintrag „/Projekte“) |
| D9 Zeilenraster | erfüllt | `quelle__web__small__…` „Ganzes Verzeichnis“; Confluence/S3 vierte Zeile „Live-Abgleich · aus · Einrichten“ |
| D10 Verbindung mit letztem Zustand | erfüllt | in jedem Szenario aus dem letzten beendeten Lauf („Anmeldung ok · zuletzt …“, „Erreichbar · …“) |
| D11 Betriebsart einheitlich | erfüllt | „Vollabgleich alle n Tage“ im Zeitplan und Auslöser „Vollabgleich“ am Lauf genau bei den vier Arten |
| D12 Laufzeile als Tabellenzeile | erfüllt | eine Zeile bei 1280 px, zwei bei 320 px |
| D13 Ereignisse gruppiert | erfüllt | aufgeklappter Lauf `scale` +745 px (Budget 900) |
| D14 Laufdetail ohne Wiederholung | erfüllt | kein Abbruchsatz, wenn das Wort die Ursache nennt |
| D15 Läufe als Liste von 10 | erfüllt | Upload ohne Läufe |
| D16 Eine Inhaltsbreite | erfüllt | 720 px; „Ändern“, Textknöpfe, ⋯ und Schalter auf derselben rechten Kante |
| D17 Ein Überschriftenstil | erfüllt | `DetailSection` überall, „Herleitung“ statt Frage |
| D18 Hinzufügen oben rechts | erfüllt | Freigeben, Feld anlegen, In Space verwenden |
| D19 Suche ab 8 | erfüllt | Freigaben, Spaces, Werte, Dokumente (Werkzeugleiste erst ab 8), Ordner, Umfang („alle n anzeigen“) |
| D20 Kein Dauerhinweis | erfüllt | Bestands-, Privat-, Diagnose- und Extraktionshinweis entfallen; Alerts nur noch an Upload-Ablehnungen |
| D21 Leerzustände | erfüllt | ein Satz ohne zweite Handlung |
| D22 Füllgrad an einem Ort | erfüllt | Reiter Metadaten › Füllgrad, alle filternden Felder |
| D23 Upload gleich gebaut | erfüllt | „Kontingent voll“, „Läuft · n von m“, „Teilweise fehlgeschlagen · 17 von 214“; Meldung „Nicht hochgeladen: …“ einmal |
| D24 Nichts verlieren | erfüllt mit Abstrichen | Liste unten |

**Vorschlag für die D2-Liste:** „Nicht erreichbar“ (Arten ohne Anmeldung) und für Upload „Hochgeladen“ und „Noch kein Inhalt“ (G 5.5).

**Kennzahlen (Runde 2 gegen Ist):**
- Wortbudget (`uiWords`, small, hell, 1280, Maximum über die Arten):

  | Reiter | A | Ist |
  |---|---|---|
  | Dokumente | 84 | 218 |
  | Quelle | 70 | 92 |
  | Läufe | 70 | 118 |
  | Metadaten | 41 | 363 |
  | Freigaben | 50 | 261 |
  | Zuordnungen | 34 | 52 |
  | Upload | 109 | 197 |

- Prüfwerte: Budgetverletzungen 0 · Kontrast 0 · `overflowX` 0.
- axe: nur noch `target-size` an 4 Bildern (`.MuiIconButton-colorInherit`, Kopfleiste bei 320 px; Ist 1). `label-content-name-mismatch`, `heading-order`, `landmark-unique` und `aria-progressbar-name` sind entfallen.
- Aufgeklappter Lauf `scale`: +745 px (Ist bis 24 492 px Seitenhöhe).

### D24 – Inventur Kapitel 8 und F

| Funktion | Ort in A |
|---|---|
| Confluence-Webhook, S3-Ereignisse | Reiter Quelle, Zeile „Live-Abgleich“ (Einrichten öffnet die bestehenden Abschnitte) |
| „letzte geplante Läufe fehlgeschlagen“ | Zeile Zeitplan |
| Vollabgleich starten | an „Läufe“, nach dem ersten Lauf |
| Status unbekannt / erneut abrufen | „Läuft · Stand unbekannt“, „Stand erneut abrufen“ an „Läufe“ |
| Neu verbinden, Zugang zuordnen/wechseln/lösen | Zeile Verbindung, unverändert aus F |
| Metadaten-Pflege | Füllgrad › Anzeigen; Filter „Metadaten“ in Dokumente |
| Upload: Dateien, Ordner, Neuer Ordner, Drag & Drop | Kopf „Hochladen“ (Menü), „Neuer Ordner“ rechts in der Werkzeugleiste, Ablegen auf der Liste |
| Diagnosesperre, Eigentum übergeben, Herleitung | Reiter Freigaben |
| Feld löschen | im Dialog „Ändern“ |
| Mitglieder einer Gruppe | „13 Mitglieder“ als Textknopf |
| Rollen-Legende | Tooltip an den Rolleneinträgen (nur per Maus) |
| „vergeben von …“ | Tooltip der Freigabezeile (nur per Maus) |
| Seitengröße 20/50/100 | **entfällt** |
| Herkunft eines Dokuments | Tooltip am Namen (kein Link mehr) |

## Review-Befunde Runde 1

„erledigt“ = umgesetzt und in der Matrix von Runde 2 zu sehen; „bewusst nicht“ mit Grund.

| Nr | Stand |
|---|---|
| R1-1 | erledigt: Reiterleiste mit Pfeilen bei Überlauf, gewählter Reiter wird beim Laden und bei jeder Breitenänderung in den Blick gescrollt |
| R1-2 | erledigt: Titel mit fester Höhe (1280 px eine Zeile, 320 px zwei Zeilen, Rest im Tooltip); `tabsTop` über alle Szenarien gleich |
| R1-3 | erledigt: bei 320 px Zustand in Zeile 1, alle Kennzahlen in Zeile 2 (kürzt mit „…“, voller Text im Tooltip) |
| R1-4 | erledigt: neues Wort „Nicht erreichbar“ für Arten ohne Anmeldung (Kopf, Verbindung, Lauf); Vorschlag für die D2-Liste |
| R1-5 | erledigt: Leser bekommen das Zustandswort ohne Link, wenn die Ursache im Reiter Quelle liegt |
| R1-6 | erledigt: Eigentümerin steht nur im Abschnitt Eigentümer; Berechtigungen ohne Rolle „Eigentümer“ |
| R1-7 | erledigt: Zeitplan-Markierung nur bei aktivem Zeitplan und fehlgeschlagenem geplantem Lauf |
| R1-8 | erledigt: „Externer Zugang“ erscheint nur, wenn das Backend die Freigabe liefert |
| R1-9 | erledigt: Status in der Nebenzeile bei 320 px mit Punkt |
| R1-10 | erledigt: Freigabezeile bei 320 px über die volle Breite, Rolle und ⋯ darunter; Filter heißen „Status“, „Metadaten“ |
| R1-11 | teilweise: Fundorte kürzen in der Mitte, Dateiname bleibt, Präfix der Web-Adresse entfällt. Umfang (`PathLabel`) bewusst nicht: gemeinsamer Baustein mit dem Assistenten |
| R1-12 | erledigt: privat neu aufgenommen, Fundorte einzeilig |
| R1-13 | erledigt: Umfang markiert nur Bereiche mit Namen; ohne Namen (Dateisystem, Web, RSS) steht der Teilfehler im Lauf („Nicht lesbare Bereiche · n“) |
| R1-14 | erledigt: „alle n anzeigen“ mit Dialog und Suche |
| R1-15 | erledigt: Upload „Läuft · 17 von 26“, solange Dokumente verarbeitet werden, aus derselben Zählung wie die Statusspalte |
| R1-16 | erledigt: „Teilweise fehlgeschlagen · 17 von 214“ |
| R1-17 | erledigt: bei 320 px ohne ⓘ, Löschen im ⋯ |
| R1-18 | erledigt: „Vollabgleich starten“ erst nach dem ersten Lauf |
| R1-19 | erledigt: Leser lesen „im Kontextpräfix: …“ als Text |
| R1-20 | erledigt: „1.034 von 1.250 gefüllt (83 %)“, „alle 3 gefüllt“ |
| R1-21 | erledigt: Füllgrad führt alle filternden eigenen Felder (Mock liefert sie jetzt) |
| R1-22 | bewusst nicht: fachlich offen, ob private Bibliotheken in Spaces dürfen; der Mock erlaubt es, die UI folgt den Daten |
| R1-23 | erledigt: keine automatische Silbentrennung im Titel |
| R1-24 | erledigt: Textknöpfe mit sichtbarem Wort und visuell verstecktem Zusatz |
| R1-25 | teilweise: Zeitplan-Markierung entfällt, wenn die Verbindung die Ursache trägt; Laufdetail ohne den Abbruchsatz. Jede fehlgeschlagene Laufzeile behält ihr Wort (Verlauf) |
| R1-26 | erledigt: Markierung am Listeneintrag; Sammelzeile nur, wenn kein markierter Eintrag sichtbar ist |
| R1-27 | erledigt: Leersatz ohne zweite Handlung, Ablagefläche ohne Link; Hauptaktion bleibt im Kopf |
| R1-28 | erledigt: Upload-Meldung nennt bei vollem Kontingent nur „Nicht hochgeladen: …“ |
| R1-29 | erledigt: „vergeben von …“ als Tooltip der Zeile |
| R1-30 | erledigt: „13 Mitglieder“ ist selbst der Textknopf |
| R1-31 | erledigt: Fuß nur „Seite 1 von 63“, Zahl nur bei Filter |
| R1-32 | erledigt: eine Angabe „40 Werte“ (Textknopf) |
| R1-33 | erledigt: kein doppeltes „Zugang“ |
| R1-34 | erledigt: Entfernen überall im ⋯ auf der Kante (Freigaben, Zuordnungen, Dokumente, Ordner); Feld löschen im Dialog „Ändern“; Schalter auf der Kante |
| R1-35 | erledigt: „TT.MM.JJJJ“ und „TT.MM.JJJJ, HH:MM“ überall, auch bei 320 px und am Ablaufdatum |
| R1-36 | teilweise: Fundorte je Art in der Form der Quelle (Fixture); Umfang bewusst nicht (Assistent) |
| R1-37 | erledigt: „214 Dokumente · 17 fehlgeschlagen“, Leerwert „–“ |
| R1-38 | erledigt: Spaltenwerte und Statuswörter groß („Manuell“, „Indiziert“, „Erreichbar“) |
| R1-39 | erledigt: Verbindung liest den letzten beendeten Lauf, auch in `slow`. Dienstkonto bei Drive nur in `scale`: Fixture, offen |
| R1-40 | erledigt: Zustand in Verbindung, Umfang, Zeitplan immer als eigene zweite Zeile |
| R1-41 | erledigt: Stift immer direkt hinter dem (gekürzten) Titel |
| R1-42 | bewusst nicht: „Jetzt indizieren“ bleibt die eine Hauptaktion im Kopf (D1); an „Läufe“ nur „Vollabgleich starten“, sonst stünde dieselbe Aktion zweimal auf der Fläche (Kategorie A) |
| R1-43 | erledigt: Füllgrad im Raster der Quelle-Zeilen (128 px, 15/600) |
| R1-44 | erledigt: „Herleitung“ als Abschnitt mit Linie und Textknopf wie die übrigen |
| R1-45 | erledigt: Feld zweizeilig, Werte über „40 Werte“, ein „Ändern“ |
| R1-46 | erledigt: Kennzahl-Links mit „›“ in Akzentfarbe |
| R1-47 | erledigt: ein Suchfeld-Baustein (`ListSearch`) mit Lupe |
| R1-48 | erledigt: Kopfzeile „Name“ und Kästchenspalte für jede Rolle |
| R1-49 | bewusst: Upload-Wörter „Hochgeladen“ und „Noch kein Inhalt“ (G 5.5) als Vorschlag für D2/D23 |
| R1-50 | erledigt: „2 GB von 2 GB“ auch bei vollem Kontingent. S3-Speicher bewusst nicht: die API liefert ihn nicht |
| R1-51 | erledigt: Gruppe „Nicht lesbare Bereiche · n“ im Laufdetail |
| R1-52 | erledigt: Schalter bei 320 px je eine Zeile |
| R1-53 | erledigt: kompakte Seitenleiste, Nebenzeile bei 320 px nur Größe und Status |
| R1-54 | erledigt: Aktionen bei 320 px immer eigene Zeile |
| R1-55 | Vollabgleich SharePoint: Fixture jetzt täglich voll. „Spaces“ für Confluence bleibt (Herstellerbegriff, G 6). S3-Muster und RSS/Web ohne „Ändern“: Assistentenzusammenfassung bzw. nichts zu ändern, bewusst nicht. „Aktuell“ gegen „Abgeschlossen“: Zustand der Bibliothek gegen Zustand des Laufs, bewusst. Leerraum: Kopf 16 px enger |
| R1-56 | erledigt: Grund in einem Satz, ohne Endungsliste |
| R1-57 | erledigt: „Kennung nach Muster“, Muster nur im Dialog |
| R1-58 | erledigt: „Gesperrt für Suchdiagnosen anderer“ / „Nicht gesperrt“, kein Erklärsatz |
| R1-59 | erledigt: kein Dauerhinweis; Bestätigung beim Einschalten nennt den Anbieter, Modell und URL unter „Technische Angaben“; Schalter ohne Unterzeilen |
| R1-60 | erledigt: „Sonstige Fehler“ am Ende, Gruppen nach Größe |
| R1-61 | erledigt: „zugeordnet am 12.09.2026 von …“ |
| R1-62 | erledigt: Privat-Satz entfällt |
| R1-63 | erledigt: Suche und Filter erst ab 8 Dokumenten oder bei aktivem Filter |
| R1-64 | erledigt: ohne Platzhalter, nur Größe |
| R1-65 | erledigt: kein Randbalken, roter Punkt vor dem Grund |
| R1-66 | erledigt mit R1-59 |
| R1-67 | erledigt: Fixture mit einem Fehlerereignis je fehlgeschlagenem Dokument; im Code zählt die Zeile `documentsFailed`, das Detail nennt „n weitere fehlgeschlagen, ohne gespeicherten Fundort“, wo Ereignisse fehlen; Hinweise getrennt |
| R1-68 | erledigt (Werkzeug): `beforeShot` je Breite scrollt zur Laufliste |
| R1-69 | erledigt (Werkzeug): Fokus und Zeiger vor jeder Breite in Ruhe |
| R1-70 | erledigt: neu aufgenommen |
| R1-71 | erledigt: Flow wartet auf das Zustandsfeld, Zeiger oben rechts |
| R1-72 | erledigt: Indizierdatum = letzter erfolgreicher Lauf |
| R1-73 | erledigt: „16 von 26“ aus den Dokumenten der Bibliothek |
| R1-74 | erledigt: „wird verarbeitet“ nur in `slow` |
| R1-75 | erledigt: ein von zwei Bereichen nicht lesbar, Fundorte im lesbaren |
| R1-76 | erledigt: Fundorte und Namen je Art |
| R1-77 | erledigt: abgelehnte Läufe 3 s, manuelle Läufe zu krummen Zeiten |
| R1-78 | erledigt: Datum aus dem jüngsten Upload, Speicher aus den Dokumenten |
| R1-79 | erledigt: Füllgrad befüllt, eine lange Person, Zuordnungsdaten verschieden |
| R1-80 | erledigt: Dateisystem, Web, RSS scheitern ohne Anmeldekategorie |


## Nachcheck 1 – Runde 3

Alle offenen Punkte aus `NACHCHECK-1.md` (Teil a und b) umgesetzt; R1-22 bleibt offene Frage an den Maintainer.

| Punkt | Umsetzung | Beleg |
|---|---|---|
| R1-3, Regr. b7 | Kennzahlzeile bricht um statt zu kürzen: zwei Zeilen bei 320 px fest reserviert, jede Kennzahl ganz. Der Trenner „·“ der ersten Kennzahl einer Zeile liegt in einer weggeschnittenen Rinne, keine Zeile beginnt oder endet auf „·“. „in keinem Space“ ohne „›“ | `dokumente__filesystem__scale__light__320`, `dokumente__upload__small__light__320`, `quelle__confluence__empty__light__320` |
| R1-6 | `partial`: Verwalter-Zeile ist jetzt „Jonas Albrecht“, Eigentümerin nur im Abschnitt Eigentum | `freigaben__gdrive__partial__light__1280` |
| R1-11, R1-36 | Ein Pfadformat „a / b / c“: `displayPath` entfernt führende und schließende Schrägstriche immer. `PathLabel` kürzt nur den Kopf, das letzte Segment samt Trenner behält seine Breite. Fundorte nutzen dasselbe Format und Kürzungsverhalten; „›“ wird zu „ / “. Der Verbindungspfad (Dateisystem) und die Web-Adresse entfallen in jeder Fundortzeile | `laeufe__filesystem__partial__light__1280`, `quelle__s3__partial__light__1280`, `laeufe__nextcloud__scale__light__1280` |
| R1-13, R1-51, Regr. a4/b1 | Dateisystem und Web melden in `partial` einen Pfad (`Gebührensatzungen`, `archiv`). Er steht im Umfang („● Nicht lesbar: Gebührensatzungen“) und im Lauf unter „Nicht lesbare Bereiche · 1“. RSS meldet keinen Bereich mehr; ohne Namen stünde eine einzelne Zeile „Ein Bereich der Quelle nicht lesbar“, nie eine leere Gruppe | `laeufe__filesystem__partial__light__1280`, `laeufe__web__partial__light__1280`, `laeufe__rss__partial__light__1280` |
| R1-16, Regr. a1 | Upload: „Teilweise fehlgeschlagen · 17 fehlgeschlagen“ | `dokumente__upload__partial__light__1280` |
| Regr. b4 | Upload, während der Upload läuft: „Läuft · 214 von 216 · 17 fehlgeschlagen“ | `upload__upload__partial__light__1280` |
| R1-40 | Zusammenfassungen im Raster („täglich 06:00 · nächster Lauf …“) brechen teilweise um, der Trenner liegt in derselben Rinne wie im Kopf; nie ein „·“ am Zeilenende | `quelle__confluence__failed__light__320`, `quelle__dropbox__failed__light__320` |
| Regr. a2/b5 | Laufzeile bei 320 px: Zeile 1 Zustand + Datum, Zeile 2 das ganze Ergebnis, umbrechend statt gekürzt; abgelehnte Läufe ohne „–“-Zeile | `laeufe__confluence__scale__light__320`, `laeufe__web__failed__light__320` |
| Regr. a3/b3, R1-43 | Füllgrad: Labels brechen um, „Anzeigen“ auch bei eigenen Feldern. Der Filter „Metadaten“ in Dokumente bietet die eigenen Felder nach den Kernfeldern an | `metadaten__smb__scale__light__1280__full`, `dokumente__smb__scale__light__1280` |
| Regr. a5 | Ereignisgruppe `UNREACHABLE` heißt „Nicht abrufbar“; „Nicht erreichbar“ bleibt der Zustand der Quelle | `laeufe__filesystem__partial__light__1280` |
| Regr. a6 | Ein abgelehnter Lauf ohne Ereignisse und Messwerte ist nicht aufklappbar (kein Pfeil, kein Knopf). Aufklappbar bleiben Läufe mit Grund („Fehlgeschlagen“, Kontingent) | `laeufe__dropbox__failed__light__1280` |
| Regr. a8 | Stift bei 320 px an der ersten Titelzeile ausgerichtet | `dokumente__upload__small__light__320` |
| Regr. b6 | Reiterleiste holt den aktiven Reiter mittig ins Bild, der Pfeil überdeckt ihn nicht | `metadaten__upload__small__dark__320`, `laeufe__smb__failed__dark__320` |
| R1-46 | Kennzahl-Links („in 3 Spaces ›“, Eigentümer ›) im Kopf ganz in Linkfarbe, nicht nur das „›“ | `dokumente__dropbox__scale__light__1280` |
| R1-55 | S3-Umfang zeigt die Muster als Einträge („nur **/*.pdf“, „außer …“) | `quelle__s3__small__light__1280` |
| R1-58 | Diagnosesperre nur für Verwaltende oder wer sie umschalten darf; Leser sehen den Abschnitt nicht | `freigaben__confluence-leser__small__light__1280` |
| R1-72, Regr. b2 | Indizierdatum = Ende des letzten erfolgreichen Laufs: `failed` 20.10. (vor den Ablehnungen), S3/privat die krummen Handzeiten, Upload 24.10., 09:30 | `dokumente__gdrive__failed__light__1280`, `dokumente__s3__small__light__1280`, `dokumente__upload__small__light__1280__full` |
| R1-78 | Hochgeladene Dateien 412 KB / 86 KB / 2,3 MB; Upload `failed`: Kontingent 100 MB, 12 Scans zu ≈ 8 MB | `upload__upload__small__light__1280`, `dokumente__upload__failed__light__1280` |
| R1-79 | Google Drive: gleicher Umfang in allen Szenarien (Overlay `small` entfernt). S3: Berechtigung „Admin · Verwalter“ passt zum Rollenchip (angemeldetes Konto bekommt eine Zeile mit seiner Rolle, wenn es nicht Eigentümer ist). privat „2,4 GB von 10 GB“ bleibt: Der Wert ist die Summe über alle privaten Bibliotheken | `quelle__gdrive__small__light__1280`, `freigaben__s3__small__light__1280` |
| Randnotiz SharePoint | „Vollabgleich“ um 12:51 bleibt: SharePoint ist auf täglichen Vollabgleich gestellt, jeder Lauf ist einer | `quelle__sharepoint__failed__light__1280` |
| R1-22 | **offene Frage an den Maintainer:** Dürfen private Bibliotheken in Spaces verwendet werden? Die Variante zeigt den Reiter Zuordnungen bei privat unverändert | `zuordnungen__privat__scale__light__1280` |

## Schwächen

1. Status-Filter, Fehler- und Verarbeitungszahl im Upload-Kopf brauchen einen Parameter `status` an `GET /libraries/{id}/documents` (Spec und Backend); im Prototyp nur im Mock.
2. Rollen-Legende, „vergeben von“ und Dokumentherkunft nur im Tooltip; Seitengröße entfällt.
3. Titel bei 1280 px auf eine Zeile gekürzt (Tooltip) – zugunsten von D1; P43 („ohne Abschneiden“) gilt damit nur bei 320 px über zwei Zeilen.
4. Umfang nutzt weiter `PathLabel` des Assistenten (Pfadform je Art, R1-11/R1-36 dort offen).
5. `sourceBlock`-Hinweis und „Zugang entfernt“ stehen weiter als getönte Alerts im Reiter Quelle (in keinem Szenario sichtbar).
6. Messartefakt `clippedText` in `quelle`: der visuell versteckte Zusatz von „Ändern Verbindung“ (wie in F).
7. Werkzeug: Unter Last (Nebenläufigkeit 3) brechen einzelne Aufrufe nach 60 s ab; diese Bilder sind einzeln nachaufgenommen.

## Gebrochene Unit-Tests

`npx vitest run`: 279 Tests rot, davon **107 schon auf der Basis rot** (Abgleich in einem Worktree der Basis). Nur aufgelistet; erwartet, weil Texte, Rollen, Aufbau und der Parameter `status` sich geändert haben.

| Datei | Basis | A |
|---|---|---|
| `library/pages/LibraryDetailPage.test.tsx` | 19 | 123 |
| `library/pages/LibraryCreatePage.test.tsx` | 46 | 46 |
| `library/components/LibrarySourceSection.test.tsx` | 18 | 24 |
| `library/pages/LibraryDetailPage.privateLibrary.test.tsx` | 0 | 12 |
| `asset/components/AssetGrantsSection.test.tsx` | 0 | 12 |
| `library/stores/documentStore.test.ts` | 0 | 10 |
| `library/components/metadata/LibraryMetadataFieldsSection.test.tsx` | 0 | 9 |
| `library/components/LibraryDocumentList.test.tsx` | 0 | 8 |
| `library/pages/LibraryCreatePage.sourceConsent.test.tsx` | 5 | 5 |
| `library/components/LibrarySourceSection.sourceConnection.test.tsx` | 5 | 5 |
| `library/components/metadata/MetadataExtractionSettingsSection.test.tsx` | 0 | 4 |
| `library/components/ConnectionProfileSelect.test.tsx` | 4 | 4 |
| `prompt/pages/PromptLibraryFlow.test.tsx` | 0 | 3 |
| `library/utils/googleDriveSource.test.ts` | 2 | 2 |
| `library/components/sources/registry.test.ts` | 2 | 2 |
| `library/components/S3SourceForm.test.tsx` | 2 | 2 |
| `library/utils/sharePointSource.test.ts` | 1 | 1 |
| `library/utils/s3Source.test.ts` | 1 | 1 |
| `library/pages/ConnectionCallbackPage.test.tsx` | 1 | 1 |
| `library/components/GoogleDriveSourceForm.test.tsx` | 1 | 1 |
| `asset/components/AssetSpacesList.test.tsx` | 0 | 1 |
| `asset/components/AssetOwnerSection.test.tsx` | 0 | 1 |
| `asset/components/AssetDetailHeader.test.tsx` | 0 | 1 |
| `asset/components/AssetAccessDerivationSection.test.tsx` | 0 | 1 |

`pnpm run lint`, `tsc -b` und `vite build` (über `pnpm run build`) bestehen.

## `git diff --stat`

Code und Werkzeuge gegenüber der Basis (ohne Bilder):

```
 .../components/AssetAccessDerivationSection.tsx    |  60 +-
 .../asset/components/AssetDetailHeader.tsx         | 156 +++--
 .../asset/components/AssetGrantsSection.tsx        | 466 ++++++++-------
 .../asset/components/AssetHeadlineEditor.tsx       |  39 +-
 .../asset/components/AssetOwnerSection.tsx         |  27 +-
 .../features/asset/components/AssetSpacesList.tsx  | 203 ++++---
 .../asset/components/AssetSpacesSection.tsx        |  60 +-
 frontend/src/features/asset/mocks/assetHandlers.ts |   8 +-
 frontend/src/features/library/api/libraryApi.ts    |   4 +
 .../library/components/DiagnosticsLockControl.tsx  |  49 +-
 .../library/components/LibraryDetailTabBar.tsx     |  21 +-
 .../library/components/LibraryDocumentList.tsx     | 469 ++++++++-------
 .../components/LibraryDocumentUploadArea.tsx       | 159 +----
 .../library/components/LibraryDocumentsNotices.tsx |  63 +-
 .../library/components/LibraryDocumentsSection.tsx | 239 +++++---
 .../components/LibraryExternalAccessSection.tsx    | 106 ++--
 .../library/components/LibraryLiveUpdateRow.tsx    |  51 ++
 .../library/components/LibraryMetadataTab.tsx      |  31 +-
 .../features/library/components/LibraryRunList.tsx | 509 ++++++++++++++++
 .../library/components/LibrarySharingTab.tsx       |  50 +-
 .../library/components/LibrarySourceRows.tsx       |  28 +-
 .../library/components/LibrarySourceSection.tsx    | 420 +++++--------
 .../library/components/LibraryStateLink.tsx        |  76 +++
 .../library/components/LibraryUploadInputs.tsx     |  59 ++
 .../features/library/components/indexingLabels.ts  |   7 +
 .../features/library/components/libraryState.ts    | 217 +++++++
 .../metadata/CoreContextPrefixSwitch.tsx           |   1 +
 .../components/metadata/EditFieldDialog.tsx        |   8 +
 .../metadata/LibraryMetadataFieldsSection.tsx      | 350 +++++++----
 .../metadata/MetadataExtractionSettingsSection.tsx | 282 ++++++---
 .../src/features/library/components/scopeMarks.ts  |  53 ++
 .../library/components/setup/SourceSetupRows.tsx   |  50 +-
 .../library/components/setup/SummaryRow.tsx        | 141 ++++-
 .../features/library/hooks/useDocumentsUrlState.ts |  16 +
 .../library/mocks/detailScenarioFixtures.ts        |  87 ++-
 .../src/features/library/mocks/indexingHandlers.ts |  10 +-
 .../library/mocks/libraryDocumentHandlers.ts       |  17 +
 .../library/mocks/libraryMetadataHandlers.ts       |  25 +-
 .../library/mocks/libraryScenarioFixtures.ts       | 152 ++++-
 .../library/mocks/sourceLibraryFixtures.ts         |   2 +
 .../features/library/pages/LibraryDetailPage.tsx   | 663 ++++++++++-----------
 .../src/features/library/stores/documentStore.ts   |  14 +-
 frontend/src/shared/ui/DetailSection.tsx           | 105 ++++
 frontend/src/shared/ui/ListSearch.tsx              |  42 ++
 frontend/src/shared/ui/RowMenu.tsx                 |  57 ++
 ui-lab/harness/flows/library-detail.mjs            |  29 +-
 ui-lab/harness/lib/measure.mjs                     |  18 +-
 47 files changed, 3755 insertions(+), 1944 deletions(-)
```
