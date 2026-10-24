# Variante F – Befunde aus der Screenshot-Durchsicht (24.10.2026)

Grundlage: ~470 Einzelbilder (4 Prüfer, je 2 Konnektoren) + 26 Querbögen (gleicher Bildschirm, 8 Konnektoren nebeneinander).

## A · Doppelungen
1. **Umfang doppelt auf der Detailseite**: Kopfzeile „… · Details“ unter den Kennzahlen + Zeile „Umfang“ im Reiter Quelle; bei viel Daten wird der Kopf zur Textwand. → Kopfzeile streichen. (alle Konnektoren)
2. **Drei Bestätigungen je Abschnitt im Assistenten**: „Verbindung testen“ + Link „Übernehmen“ + „Weiter“; „Übernehmen“ heißt wie der Dialog-Primärknopf, tut aber anderes. → Abschnitts-„Übernehmen“ streichen; „Weiter“ übernimmt und testet; Abschnitt schließt beim Öffnen eines anderen.
3. **Fehlschlag dreifach**: getönter Warnbanner mit Icon + rote „Fehlgeschlagen“-Zeilen + Fehlermeldung; Banner sagt „geplante Läufe“ auch bei manuellem Lauf und „Zeitplan versucht es erneut“ bei Zeitplan „aus“ (S3). → Banner streichen, Grund in der ersten Laufzeile.
4. **Lauf-Zeile doppelt**: „17 fehlgeschlagen“ und „17 Ereignisse, davon 17 nicht lesbar“. → eine Angabe.
5. **Fehlerzusammenfassung wiederholt Feldfehler wörtlich**. → Zusammenfassung nennt Abschnitt + Feldname als Sprunglink, Feld nennt den Fehler kurz.
6. **Laden + Leer gleichzeitig**: „Ordner werden geladen …“ neben „Keine Ordner ausgewählt“ + aktivem „Auswählen …“. → beim Laden nur Ladezeile.
7. **Ladefehler-Satz + Link sagen dasselbe** („Bitte später erneut versuchen“ + „Erneut laden“; S3 „Geben Sie Bucket und Präfix von Hand an“ + Knopf „Von Hand eintragen“). → Satz streichen.
8. **Seitentitel „Prüfen“ wiederholt Reiter „5 · Prüfen“**, Schritt 2 heißt dagegen „Quelle einrichten“. → ein Titel für den ganzen Assistenten.
9. **Tri-State + Wort „teilweise“**; „Nicht wählbar: Komma im Namen“ in jeder dritten Zeile. → nur Tri-State; Grund einmal bzw. als Tooltip.
10. **Titel wiederholt Quellart-Chip** („Satzungen (Dateisystem)“ + Chip „Dateisystem“).

## B · Inkonsistenzen
11. **„Ändern“ in zwei Formen**: Textlink rechts (Verbindung, Zeitplan) und Rahmenknopf mitten im Umfang; verschiedene rechte Kanten. Dialog-Öffner heißt je Szenario „Ändern“ / „Auswählen …“ / „Unterordner auswählen …“ / „Präfixe auswählen …“. → ein Linkstil an einer rechten Kante; Dialog-Öffner einheitlich „<Objekte> auswählen …“.
12. **Handeingabe dreimal anders benannt** („Von Hand eintragen …“, „Ordnerpfad von Hand eintragen“, „Ordner-ID von Hand eintragen“, „Bucket/Präfix …“). → ein Begriff, ein Stil.
13. **„Alle / Nur ausgewählte“ nur bei Ordnerquellen** (Nextcloud, SMB, Dropbox), nicht bei Confluence, SharePoint, Drive, S3; Vorgabe mal „Alle Ordner“, mal „Noch nicht festgelegt“ (Pflichtfehler). → Radiopaar überall wo die Quelle „alles“ kann, Vorgabe „Alle“.
14. **Verbindungs-Zusammenfassung uneinheitlich**: mal „· verbunden“, mal ohne Status, mal „Zugang „…““, mal nackter Host, im Detail Drive „Schlüssel gesetzt“; Fehlstatus nur als grauer Fließtext ohne Stufe, bei 320 px abgeschnitten. → immer „<Ziel> · <Status-Wort>“, Status in Signalfarbe als eigenes Element, Fehlerstufe (Adresse · Anmeldung · Rechte).
15. **Umfang in vier Darstellungen**: Checkboxen / Liste mit „und n weitere“ / Komma-Absatz (Prüfen, bricht mitten im Wort) / Kommazeile (Detail). Pfade in drei Schreibweisen (`Dokumente (Rathaus)`, `Rathaus · Dokumente › …`, `… › Ordner / …`). → überall Liste, 5 Einträge + „und n weitere“, ein Pfadtrenner; Kürzung in der Pfadmitte (bei 320 px sind sonst alle Zeilen gleich).
16. **Zwei Commit-Muster**: Assistent „Abbrechen“ links, Primär rechts; Bearbeiten „Speichern“ links + „Abbrechen“-Link daneben, zusätzlich zweiter Primärknopf „Jetzt indizieren“ sichtbar. „Übernehmen“ vs „Speichern“. → eine Reihenfolge, ein Primärknopf je Fläche.
17. **Inhaltsbreiten springen**: Assistent/Prüfen 744 px, Fußleiste 824 px, Bearbeiten/Detail 1240 px; Feldbreiten 264/520/360 px gemischt. → eine Inhaltsbreite, Feldbreiten nach Inhaltsart.
18. **Einrückung**: Hilfe-, Fehlerzeilen, „Übernehmen“, „Erneut laden“, „Abbrechen“ 10–18 px neben der Label-Kante; „Erneut laden“ nur im Bearbeiten getönt. → Textlinks ohne Innenabstand auf die Inhaltskante.
19. **Überschriftenstufen**: offener Abschnitt leichter als zugeklappte; „LÄUFE“ als Versal-Overline; zwei Trennlinien direkt unter den Reitern. → eine Abschnittsstufe, eine Linie.
20. **Fehlerformulierungen**: „Verzeichnispfad ist erforderlich“ / „Bitte die Adresse der Freigabe eingeben“ / „Adresse fehlt“; Label „Verzeichnis“. → „<Label> fehlt“.
21. **Muster bei S3** fehlen in Prüfen und Detail; Umschalter heißt „Ein- und Ausschlussmuster“ vs „Muster ausblenden“; Dateisystem-Muster als offenes Textfeld ohne Höhengrenze (40 Muster ≈ 1000 px).
22. **Leere Werte** „keine Angabe“ vs „keine“.

## C · Kaputt / irreführend
23. **320 px Detailkopf läuft über**: „Vollabgleich starten“, Stern, „…“ abgeschnitten; lange Titel hart abgeschnitten; Reiter „Freigaben“, „Zuordnungen“ unsichtbar ohne Scrollhinweis; hängende Trenner in der Kennzahlenzeile. → Aktionen ins „…“-Menü, Titel umbrechen, Reiterleiste scrollbar mit Randschatten.
24. **Lauf „Abgeschlossen“ (grün) trotz 12/17 Fehlern** → Status „Teilweise fehlgeschlagen“ (Prüfliste 40).
25. **Bearbeiten-Auswahldialog**: „1 ausgewählt“, aber nichts angehakt, gewählter Eintrag fehlt in der Liste; Dialog fest 700 px hoch, fast leer. → gespeicherte Auswahl oben angehakt, Höhe nach Inhalt.
26. **„Alle 20 auswählen“ bei 30 sichtbaren Zeilen** und unvollständiger Liste → Zahl korrekt bzw. „Alle geladenen auswählen“.
27. **Leer/Fehler nennen falsche Ebene** („In „Sites“ liegen keine Dokumentbibliotheken“); Suchfeld und „Auswahl ansehen“ bei 0 Einträgen sichtbar.
28. **Tooltip bei ungekürzten Namen** verdeckt die Folgezeile; Dialog-Fußleiste bei 320 px nicht rechtsbündig.

## D · Hilfetexte, die noch weg müssen
- „Die Quellart „X“ ist nur über einen Zugang nutzbar; eine eigene Adresse ist nicht möglich.“ (SharePoint, Dropbox)
- „Zugänge legt die Systemverwaltung an und gibt sie frei.“
- „„Jetzt indizieren“ liest Änderungen, „Vollabgleich starten“ … neu.“ (jede Detailseite; ggf. Tooltip am Knopf)
- „Microsoft Graph hat die Auflistung nach 10 Seiten beendet …“ → „Liste unvollständig.“
- Zugangskachel „Vorgaben: edition: INTERN“ (Rohdaten)
- Einzelne Zugangskachel, wenn es nur einen Zugang gibt (SharePoint, Dropbox) → als Zeile, nicht als Auswahl
- Grenzfall behalten: „In Atlassian unter Konto › Sicherheit › API-Token“, „In Nextcloud unter Einstellungen › Sicherheit …“ (sagen, wo man den Wert findet) – aber nicht im Fehlerfall ausblenden.

## E · KI-Look-Reste (Prüfliste 1, 4)
- Detailkopf: Verlauf oben links, getönter Chip „Wissen“ mit Icon, Akzent-Chip „Eigentümer“ → neutrale Text-Chips.
- Ausgewählte Zugangskachel getönt (Guideline §5.11 verlangt nur Akzentrahmen + Haken).
- Getönter Warnbanner (s. 3).

## F · Mock-/Aufnahmefehler (keine UI-Befunde, aber vor Videos beheben)
- Zeitplan „täglich 06:00“ vs „Nächster Lauf 04:00“ in den Fixtures.
- S3: „slow“ zeigt „Test fehlgeschlagen“ + geladene Liste, „empty“ zeigt „Test fehlgeschlagen“.
- slow hell/dunkel bzw. 320/1280 zeigen verschiedene Zustände; filesystem wizard empty/scale/partial byteidentisch.
- `wizard-select__*__1280__full` zeigt nur das Overlay; `320__full` auf 900 px gekappt.
- Space-Namen „Space 001“ (Detail) vs „Personal und Organisation 001“ (Dialog).
