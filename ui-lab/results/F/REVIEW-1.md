# Variante F – Review-Runde 1 (24.10.2026)

Grundlage: Review des Maintainers über die Orchestrierungs-Session (Querbögen der ersten vollen Matrix) und vier Nachträge. Nummern laufen durch; `REPORT.md` hakt sie ab.

## Nachträge mit Vorrang
- **N1 Eine Breite:** Reiterleiste, Zeilen und Fußleiste gleich breit, gleiche Kanten.
- **N2 Trennlinien:** Reiter-Unterkante · Verbindung · Linie · Umfang · Linie · Zeitplan · Linie · Knöpfe. Keine Doppellinien.
- **N3 Ein Raster:** Titel | Inhalt | „Ändern“ (Textlink, gemeinsame rechte Kante) für alle Zeilen; Umfang als Liste in der Mittelspalte, erste Zeile „n Spaces“, kein Unterlabel.
- **N4 „Ändern“ nie als Knopf.** Dialog-Quellen: „Ändern“ öffnet den Dialog direkt; im Detail speichert „Übernehmen“ sofort (Popup „Gespeichert“). Inline-Zeilen: aufklappen, unten „Abbrechen“ + „Speichern“ wie die Fußleiste. In „Nur ausgewählte“ ein Textlink „<Objekte> auswählen …“.
- **N5 Zugänge ohne Kacheln:** ein Zugang = Zeile „Zugang „…“ · verbunden“, Abschnitt zugeklappt; mehrere = Radioliste Name + Adresse (fg-2), „Eigene Adresse“ als letzte Zeile, Felder erst danach; ab 8 Suche. Mock mit 2–3 Zugängen.
- **N6 Werkzeuge** aus PR #3101 (`capture.mjs`, `record-flows.mjs`, `contact_sheets.py`, `opaa.mockSlowMs`); alte 320er-Kennzahlen gelten als unzuverlässig.

## A · Doppelungen und Kaputtes
1. Umfangszeile im Detailkopf streichen.
2. Abschnitts-Link „Übernehmen“ im Assistenten streichen.
3. Getönten Banner „letzte geplante Läufe fehlgeschlagen“ streichen; kein „geplant“/„Zeitplan versucht es erneut“, wenn manuell oder ohne Zeitplan.
4. Laufzeile: Fehlerzahl einmal; Status „Teilweise fehlgeschlagen“ statt grün „Abgeschlossen“.
5. Fehlerzusammenfassung: Abschnitt + Feldname als Link; Feldfehler einheitlich „<Label> fehlt“.
6. Beim Laden nur die Ladezeile; Ladefehler ohne Satz, der den Link wiederholt.
7. 320 px Detailkopf: Vollabgleich ins „…“-Menü, Titel umbrechen, Reiter scrollbar mit Randschatten, keine hängenden Trenner.
8. Bearbeiten-Dialog: gespeicherte Auswahl angehakt oben; Höhe nach Inhalt.
9. „Alle n auswählen“ mit richtiger Zahl; unvollständige Liste „Alle geladenen auswählen“.
10. Leer/Fehler nennen die richtige Ebene; Suche und „Auswahl ansehen“ bei 0 Einträgen ausblenden.

## B · Einheitlichkeit
11. „Ändern“ nur als Textlink an der rechten Kante; Dialog-Öffner überall „<Objekte> auswählen …“.
12. Handeingabe überall gleich benannt und gestaltet.
13. Radiopaar „Alle / Nur ausgewählte“ überall, wo die Quelle alles kann (auch Confluence, Drive, S3, SharePoint je Bibliothek); Vorgabe „Alle“.
14. Verbindungszeile „<Ziel> · <Status>“, Status als eigenes Element mit Signalfarbe und Stufe; bei 320 px eigene Zeile.
15. Umfang überall als Liste (5 + „und n weitere“), auch Prüfen und Detail; ein Pfadtrenner, Kürzung in der Mitte.
16. Eine Knopfreihenfolge, ein Primärknopf je Fläche.
17. Eine Inhaltsbreite für Assistent, Prüfen, Bearbeiten, Detail; Feldbreiten nach Inhaltsart.
18. Textlinks ohne Innenabstand, keine Tönung.
19. Eine Überschriftenstufe (auch „Läufe“), eine Linie unter den Reitern, ein Seitentitel für den Assistenten.
20. Nur Tri-State ohne „teilweise“; „Nicht wählbar“ einmal oder Tooltip; Tooltip nur bei gekürzten Namen.
21. S3-Muster in Prüfen und Detail; Umschalter gleich benennen; Dateisystem-Textarea begrenzen.
22. Leere Werte einheitlich „keine“.

## D · Hilfetexte / E · KI-Look
23. Streichen: „Die Quellart „X“ ist nur über einen Zugang nutzbar …“, „Zugänge legt die Systemverwaltung an …“, „„Jetzt indizieren“ liest Änderungen …“, Graph-Satz „nach 10 Seiten“ → „Liste unvollständig.“, „Vorgaben: edition: INTERN“.
24. Ein Zugang als Zeile, nicht als Kachel (siehe N5).
25. Fundort-Hilfen bleiben auch im Fehlerfall.
26. Detailkopf ohne Verlauf, Chips neutral; gewählte Kachel ohne Tönung; Titel ohne „(Quellart)“, wenn der Chip sie nennt.

## F · Mock- und Aufnahmefehler
27. Zeitplan 06:00 und nächster Lauf 04:00 angleichen.
28. S3 slow/empty ohne „Test fehlgeschlagen“.
29. slow in allen Schemata und Breiten derselbe Zustand.
30. Dateisystem: Szenarien sichtbar unterscheiden.
31. wizard-select `__full`: Dialog statt Overlay.
32. 320 `__full` nicht auf 900 px kappen.
33. Space-Namen in Detail und Dialog gleich.
