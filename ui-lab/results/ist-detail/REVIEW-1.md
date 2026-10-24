# Ist (Variante F) Detailseite – Konsistenz-Review 1 (24.10.2026)

**Grundlage**
- Aufnahme: Lauf 1 der Ist-Matrix (Commit bcc0c7b), rund 2 300 Einzelbilder und 72 lesbare Querbögen (3 Arten je Zeile bei 1280 px, 6 je Zeile bei 320 px). Bewertet haben 6 Prüfer: 5 Prüfer mit je 2 Arten und 1 Querschnittsprüfer, der zugleich Webverzeichnis und die Sonderfälle Leser und privat übernahm.
- Kategorie F (Mock und Aufnahme) ist in 75bcd5c behoben. Die Matrix unter `ui-lab/results/ist-detail/` ist danach neu aufgenommen; die Dateinamen gelten unverändert.
- Belege nennen den Bildnamen ohne Endung. `grids/…` sind die Querbögen in `scratchpad/review/grids`; sie gehören nicht zum Branch und entstehen mit `contact_sheets.py` neu.

## A · Doppelungen

1. **„Letzter Lauf“ im Kopf wiederholt die erste Laufzeile.** Zustand, Zahlen und Zeit stehen in beiden Stellen und weichen voneinander ab (Kopf „3 verarbeitet (3 übersprungen) · 04:07“, Zeile „3 verarbeitet, 3 übersprungen · 04:00“). Beleg: `quelle__smb__small__light__1280`, `quelle__web__small__light__1280`; alle Konnektoren (6/6 Prüfer). → Im Kopf nur ein Zustandswort mit Datum, die Zahlen nur in der Laufzeile.
2. **Laufende Indizierung dreifach.** Gleichzeitig zu sehen: der deaktivierte Knopf „Indizierung läuft …“, der Balken „831 von 1340“ über den Reitern und die Laufzeile „Läuft · 26 verarbeitet“. Die Zahlen widersprechen sich. Beleg: `dokumente__s3__slow__light__1280`, `laeufe__nextcloud__slow__light__1280__full`; alle Konnektoren. → Fortschritt an genau einer Stelle, aus einer Zählung.
3. **Teilfehler doppelt.** Die Kante über den Reitern („… konnte die Ordner … nicht auflisten“) und die Laufzeile („2 Bereiche nicht lesbar“) melden denselben Teilfehler. Beleg: `laeufe__confluence__partial__light__1280__full`; Arten mit Auswahl. → Nicht lesbare Bereiche an der Zeile Umfang markieren.
4. **Laufdetail wiederholt Status und Zahlen der Zeile** („Indizierung abgeschlossen: … verarbeitet“). Beleg: `laeufe__smb__partial__light__1280__full`; alle. → Detail nur mit dem, was die Zeile nicht zeigt.
5. **Ereigniszeile nennt den Grund zweimal** („Format nicht unterstützt · Dateiformat wird nicht unterstützt (application/x-msaccess)“). Beleg: `laeufe__smb__partial__light__1280__full`; alle. → Ein Label je Grund, Rohwerte nur im Tooltip.
6. **Füllgrad der Metadaten doppelt.** Er steht als „Metadaten-Pflege“ im Reiter Dokumente und als „Extraktionsgüte“ im Reiter Metadaten, jeweils in anderer Form. Beleg: `dokumente__filesystem__small__light__1280__full` und `metadaten__filesystem__small__light__1280__full`; alle. → Nur im Reiter Metadaten; im Reiter Dokumente höchstens der Filter „ohne Wert“.
7. **„METADATENFELDER“ und darunter „Metadatenfelder“.** Beleg: `metadaten__*__small__light__1280__full`; alle. → Eine Überschrift.
8. **Nur-Lesen dreifach.** Chip „Leser“, Info-Banner „Sie haben … nur Leserechte“ und der Satz „Verbindung, Zeitplan und Läufe sehen nur Verwaltende“. Beleg: `quelle__confluence-leser__small__light__1280` (extra); Leser. → Chip genügt, fehlende Zeilen ohne Erklärung weglassen.
9. **Kennzahlen „in n Spaces“ und Eigentümer doppeln die Reiter Zuordnungen und Freigaben.** Die Rolle steht dazu als Chip und noch einmal als Kennzahl. Beleg: `freigaben__gdrive__small__light__1280__full`; alle. → Als Sprungverweis zum Reiter fassen (siehe Richtungen).
10. **Kontingentfehler dreimal wörtlich im Upload-Banner.** Beleg: `upload__upload__failed__light__1280`; Upload. → Grund einmal nennen, darunter die Dateien als Liste.

## B · Inkonsistenzen

11. **Teilfehler-Kante nur bei 7 von 10 Konnektoren.** Dateisystem, Web und RSS zeigen keine, obwohl ihr Lauf „2 Bereiche nicht lesbar“ meldet. Dazu kommen sechs Wortlaute: Lauf/Abgleich/Vollabgleich, lesen/auflisten/erreichen, Spaces/Ordner/Geltungsbereiche/Bereiche. Beleg: `grids/dokumente__partial__light__1280`. → Ein Muster, ein Begriff, eine Stelle.
12. **Reiter Quelle: Zeile Umfang fehlt bei Web und RSS.** Beleg: `grids/quelle__small__light__1280`. → Gleiches Raster, für Web z. B. „Ganzes Verzeichnis“.
13. **Betriebsart (Inkrementell/Vollabgleich) nur bei Confluence, SharePoint, Drive, Dropbox.** Dropbox zeigt sie im Lauf, aber nicht im Zeitplan. Beleg: `quelle__dropbox__small__light__1280`. → Überall oder nirgends, mit Zeitplan und Lauf gleich.
14. **Verbindungszeile je Art anders aufgebaut.** Beispiele: Pfad, Host, „Zugang „…““, „Schlüsseldatei gesetzt“, „Zugang „Dropbox Rheinfurt“ · eigene Ablage“; ein hängender Mittelpunkt vor „nicht geprüft“. Beleg: `grids/quelle__small__light__1280`. → „Zugang oder Adresse · Zustand“.
15. **RSS zählt Feed-Einträge nur im Kopf und im Fortschritt, die Laufzeile zählt Dokumente.** Der Kopfsatz bricht um und geht rechnerisch nicht auf. Beleg: `dokumente__rss__small__light__1280`, `quelle__rss__scale__light__1280__full`. → Eine Zählform je Art an allen Stellen.
16. **Kopfhöhe und Lage der Reiterleiste springen je Art und Szenario** (y ≈ 258, 288 oder 327). Ursache sind die Umbrüche der Kennzahlzeile, der Fortschritt und die Kante. Beleg: `dokumente__upload__slow__light__1280` und `dokumente__smb__slow__light__1280`. → Feste Kopfstruktur.
17. **Inhaltsbreite springt.** Im Reiter Quelle sind es 720 px (rechte Kante bei ca. 824 px), in den anderen Reitern 1136 px. Beleg: `quelle__s3__small__light__1280` und `zuordnungen__s3__scale__light__1280__full`; alle (P46). → Eine Inhaltsbreite.
18. **Zwei Überschriftenformen.** Mono-Versalien („METADATEN-PFLEGE“, „EIGENTÜMER“) stehen neben fetter Satzschrift („Verbindung“, „Läufe“). Beleg: `freigaben__web__small__light__1280`. → Ein Stil.
19. **Status in drei Formen.** Kennzahl (Icon + Satz), Laufzeile (Punkt + Wort), Dokument (umrandeter Chip). Upload hat gar keinen Gesamtstatus, nur 13 Chips „fehlgeschlagen“. Beleg: `dokumente__upload__partial__light__1280__full` und `dokumente__smb__partial__light__1280__full`. → Eine Statusform (P40).
20. **Hinzufügen-Aktion je Reiter anders.** „In Space verwenden“ ist ein Knopf oben, „+ Freigeben“ ein Link am Listenende, „Feld anlegen“ ein Link oben rechts, „Anzeigen“ sind Umriss-Knöpfe. Beleg: `zuordnungen__filesystem__small__light__1280`, `freigaben__gdrive__small__light__1280__full`. → Eine Form und eine Position.
21. **Dokumentzeile 2- oder 3-zeilig.** Web, RSS und Confluence bekommen eine Herkunftszeile, bei Confluence bis zu 4 Textzeilen. Beleg: `dokumente__confluence__small__light__1280__full`. → Herkunft ins ⓘ (P6).
22. **Zahlen- und Datumsformate uneinheitlich.** Beispiele: „1.250“ neben „1250“, „23.10.2026, 04:07“, „25.10.26, 06:00“ und „10.9.2026“. Beleg: `dokumente__sharepoint__scale__light__1280`. → Ein Format.
23. **Speicher-Kennzahl in zwei Formen** (Upload „Speicherkontingent belegt“, privat „in Ihren privaten Bibliotheken belegt“). Beleg: `quelle__privat__small__light__1280` (extra).
24. **Freigaben: Rollenauswahl springt** bei „bis 31.12.2026“ gegenüber „unbefristet“, und das Label „Rolle“ steht in jeder Zeile. Beleg: `freigaben__sharepoint__scale__light__1280__full`. → Feste Spalten, Spaltenkopf einmal.
25. **Pfadformat im Umfang je Art und Ansicht verschieden** („protokolle / 2025“ neben „protokolle/2025/“, „Dokumente (Rathaus)“ neben „Rathaus · Dokumente / …“). Beleg: `quelle__s3__failed__light__1280`, `dokumente__s3__partial__light__1280`.

## C · Kaputtes und Irreführendes

26. **Kopf grün „Abgeschlossen“, Laufzeile „Teilweise fehlgeschlagen“, Detail „Indizierung abgeschlossen“.** Das gilt auch für einen unvollständigen Lauf mit „4 Bereiche nicht lesbar“. Beleg: `quelle__nextcloud__scale__light__1280__full`, `laeufe__web__partial__light__1280__full`; alle (6/6). → Ein Zustand aus einer Quelle.
27. **Abgelehnte Anmeldung nur im aufgeklappten Lauf.** Die Verbindung bleibt „· nicht geprüft“, über den Reitern steht nichts. Beleg: `quelle__*__failed__light__1280`, `laeufe__smb__failed__light__1280__full`; alle (P41). → Zustand an der Zeile Verbindung, mit „Ändern“.
28. **Laufdetail widerspricht der Zeile** („Nichts übersprungen oder fehlgeschlagen“ bei 3 übersprungenen und bei „Fehlgeschlagen“). Beleg: `laeufe__smb__small__light__1280__full`, `laeufe__s3__failed__light__1280`.
29. **Läufe unbegrenzt.** Ein Lauf zeigt 500 Ereignisse flach; die Seite wird 24 000 px hoch, bei 320 px bis 42 000 px. Beleg: `laeufe__smb__scale__light__1280__full`; alle (Rückmeldung 2). → Nach Grund gruppieren, mit Anzahl und „alle anzeigen“.
30. **Hinweise stehen in der Fehlerliste.** „In der Quelle entfernt“ und „Ratenbegrenzung … wiederholt“ stehen zwischen den Fehlern, die Zählung passt nicht („38 fehlgeschlagen“, 500 Zeilen). Beleg: `laeufe__s3__partial__light__1280__full`.
31. **Laufzeile ohne Raster.** Die zweite Zeile wechselt den Inhalt, die Folgezeile hat eine eigene Einrückung (116 statt 104 px), und die ganze Zeile ist als Überschrift ausgezeichnet. Beleg: `quelle__sharepoint__scale__light__1280__full`, `metrics.jsonl` (headings).
32. **Leerzustand irreführend.** Bei 0 Dokumenten stehen „vollständig gepflegt“, das Suchfeld, „20 je Seite“ und das Info-Banner. Beleg: `grids/dokumente__empty__light__1280`; alle (P38).
33. **Titel bei 320 px abgeschnitten**, ohne Treffer in `clippedText`. Die Reiterleiste hat keinen Scrollhinweis, der aktive Reiter ist unsichtbar, und der Kopf belegt 430–600 px. Beleg: `dokumente__web__scale__light__320`, `zuordnungen__sharepoint__scale__light__320`; alle (P25, P43).
34. **Freigaben skalieren nicht.** Bei 45 Freigaben gibt es keine Suche, jeder Eintrag hat 3 Textzeilen, „Freigeben“ steht ganz unten, und bei 320 px wird die Seite rund 9 000 px lang. Beleg: `freigaben__sharepoint__scale__light__1280__full`.
35. **Zuordnungen ohne Suche bei 34 Spaces**, mit einem Schmuck-Icon je Zeile. Beleg: `zuordnungen__s3__scale__light__1280__full` (P20, P3).
36. **Begriffskollision „Space“ bei Confluence.** Der Kopf sagt „in 3 Spaces“ (OPAA), der Umfang „2 Spaces“ (Confluence). Beleg: `quelle__confluence__failed__light__1280`.
37. **Upload: volles Kontingent im Kopf unsichtbar.** Der Kopf zeigt weiter „734 MB von 2 GB“, und die Zahl im Listenfuß weicht vom Kopf ab (938 gegenüber 1 250 auf der Ordnerebene). Dazu kommen 60 Ordner ungeblättert vor den Dateien. Beleg: `upload__upload__failed__light__1280__full`, `dokumente__upload__scale__light__1280__full`.
38. **Rechte verdreht.** Ein Leser sieht „Sperre setzen“ (Diagnosesperre), der Eigentümer der privaten Bibliothek nicht. Beleg: `freigaben__confluence-leser__small__light__1280` (extra). Der erste Teil ist ein Mock-Fehler (behoben), der zweite ist gewolltes Verhalten (privat bleibt gesperrt).
39. **Teilfehler zeigt Roh-IDs** (`b!rathausDokumente`, `0AMockDrive`). Der Grund ist der Code: `scopeHero.unlistedWarning` nutzt den Schlüssel, wenn der Name fehlt. Beleg: `dokumente__sharepoint__partial__light__1280`.
40. **Barrierefreiheit (axe, hell).** Gefunden wurden `heading-order` (36), `landmark-unique` (12), `aria-progressbar-name` (12, Fortschrittsbalken ohne Namen) und `label-content-name-mismatch` (8). Kontrast im dunklen Schema und `overflowX` sind überall 0.

## D · Hilfetexte, die weg müssen

41. Das Dauerbanner „Dieser Bestand stammt aus der Quelle; einzelne Dokumente lassen sich hier nicht löschen …“ steht auf jedem Konnektor-Reiter Dokumente, auch bei Lesern und bei 0 Dokumenten; bei 320 px hat es 5 Zeilen. (P13)
42. Wortbudget überschritten (`uiWords`, small): Metadaten 352–363, Freigaben 237–261, Dokumente 151–218. Erklärsätze: Metadaten-Pflege (2 Zeilen), Berechtigungen (3 Zeilen, endet „— auch die.“), „Ihr eigener Weg zur wirksamen Rolle. Ohne Vollmacht, ohne Protokoll.“, „Die Spaces, in denen dieses Objekt …“. Dazu der Teilfehler-Satz „Der Hinweis bleibt, bis …“.
43. Der Formatfehler beim Upload zählt alle erlaubten Endungen auf.

## E · KI-Look-Reste

44. **Der blaue Verlauf oben links ist in F weg; das bestätigen 6 von 6 Prüfern.** Übrig sind blau getönte Infoboxen (Dokumente, Metadaten, Rollenhinweis), eine beige Warnbox, getönte Chips „Filter/Kontextpräfix“, Schmuck-Icons vor jeder Kennzahl und jedem Space sowie der Fortschrittsbalken über die volle Breite in Akzentblau. (P1, P3, P4, P5)

## F · Mock- und Aufnahmefehler (behoben in 75bcd5c, Matrix neu aufgenommen)

45. Fixtures widersprachen sich:
    - Zeitplan 06:00, Läufe 04:00, Kopf 04:07
    - Läufe „per Zeitplan“ ohne Zeitplan (S3, privat)
    - abgelehnter Lauf mit 1 843 Anfragen und 734 MB
    - Detailsatz „1320 verarbeitet“ bei 214 Dokumenten
    - Ereignisorte als Dateipfade auch bei RSS, Web und Confluence
    - Freigabe „Admin · Eigentümer“ neben der Eigentümerin Erika Muster
    - Leser mit umschaltbarer Diagnosesperre
    - privat mit Umfang „keine“

    Offen bleiben: Extraktion aus, aber gezählte Modellaufrufe, und S3 als einzige Art mit Rolle „Verwalter“ (die Fixture bleibt, sie zeigt einen echten Fall).
46. Aufnahme:
    - Viewport-Bilder der Läufe zeigten den Lauf nicht aufgeklappt oder leer: Animation, und ein falsches Klickziel bis c44ae3f.
    - Der Skip-Link war sichtbar.
    - 13 Aufnahmen „tab not offered“ unter Last.
    - 4 Aufnahmen mit „HTTP 502“, weil der Mock-Worker noch nicht aktiv war.
    - `__full` endet bei 16 000 px (Werkzeuggrenze, bleibt).
    - `clippedText` misst den h1 nicht (Werkzeuglücke, bleibt; als Hinweis in der Prüfliste aufgenommen).
47. Im Szenario `slow` erscheint ein aufgeklappter Lauf im Viewport-Bild leer, im `__full` dagegen voll. Ein direkter Playwright-Screenshot zeigt ihn vollständig. Die Laufliste wird beim Polling nicht neu geladen (`indexingStore.ts`, `loadRunHistory` erst bei Laufende). Die Ursache liegt daher wohl in der Aufnahme (Viewport-Wechsel bei laufenden Animationen) und ist **ungeklärt**. Im Video `laeufe__*` ist zu prüfen, ob Nutzende ein Flackern sehen (P39).
