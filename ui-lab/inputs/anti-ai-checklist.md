# Prüfliste „kein KI-Look" — jede Variante muss bestehen

Quelle: Vergleichsbilder des Maintainers (KI-Design vs. menschliches Design / UX-Writing).
Gilt zusätzlich zu docs/design/guidelines.md. Bei Konflikt gewinnt die Guideline.

## Visuell
1. **Ein Signal pro Element.** Kein Icon + Punkt + Rahmen + Tönung + Zusatztext im selben Badge/Chip.
2. **Tabs:** Text + Unterstrich, keine Pillen mit Icon, Zähler und „Neu"-Badge.
3. **Keine Deko-Icons** vor Überschriften, Kachel-Titeln oder Labels, wenn sie nichts unterscheiden.
4. **Keine getönten Flächen und Verläufe** als Schmuck; neutrale Fläche + 1-px-Rahmen (§1.1).
5. **Akzentfarbe nur für Handlung/Zustand**, nicht für Zahlen, Titel, Hervorhebung (§1.2).
6. **Listenzeile:** max. 2 Textzeilen, Primärinfo + 1 Sekundärinfo, Navigation per Chevron. Keine Avatare/IDs/Fortschrittsbalken ohne Zweck.
7. **Fehler am Feld:** roter Rahmen + kurze Zeile darunter. Kein Banner mit Icon, Titel, Fehlercode, Support-Link, Schließen-X für Validierung.
8. **Bestätigungsdialog:** Titel als Frage mit Objekt, ein Satz Konsequenz, genau zwei Knöpfe (Abbrechen / Verb). Kein Warn-Icon, kein „Nicht mehr fragen", kein „Später", keine Zusatz-Warnbox.
9. **Stepper:** kurze Schrittnamen (1 Wort), keine Unterzeilen, keine Restzeit-/Prozentangaben.
10. **Filter:** schlichte Chips, ausgewählt = gefüllt. Keine „Gespeicherte Filter"/„Alle zurücksetzen"-Leisten, wenn nicht nötig.
11. **Hierarchie über Größe/Gewicht** (§1.3), nicht über zusätzliche Kästen.

## Text (UX Writing)
12. **Labels 1–3 Wörter** („Geburtsdatum", nicht „Bitte geben Sie hier das vollständige …").
13. **Hilfetext nur, wenn er etwas Neues sagt**; nie Label oder Platzhalter wiederholen. Max. 1 Zeile.
14. **Platzhalter = Beispiel oder Format**, keine Anleitung.
15. **Fehler:** was ist falsch, ggf. was tun, in ≤ 1 Satz. Keine Entschuldigung („Oh nein!"), keine Schuldzuweisung („Die von Ihnen …"), kein Fehlercode für Nutzer.
16. **Status:** ein Wort („Aktiv"), nicht „Der Datensatz ist aktuell aktiv und erfolgreich verifiziert".
17. **Knöpfe = Verb** der Handlung („Löschen", „Verbinden"), nicht „Ja, …" oder „OK".
18. **Keine Redundanz:** „Sind Sie sicher?" + „Sind Sie sicher, dass …" + „nicht rückgängig" + „dauerhaft und unwiderruflich" → ein Satz.
19. **Einheitliche Begriffe** über alle Quelltypen (Glossar `docs/handbuch/glossar.md`, Guideline §6).

## Hinweis zu den Vorlagen
Bild 3 („Was gutes UX Writing ausmacht") zeigt bei Kennzahl-Kachel (Icon, Tönung, Akzentzahl)
und Fehlermeldung (getöntes Banner für Feldfehler) selbst Muster, die Bild 1 als KI-Look markiert.
Maßstab sind Bild 1/2 rechts und die Guidelines.

## Skalierung (Pflicht-Szenarien je Variante)
Jede Variante wird zusätzlich mit einem Stress-Fixture-Satz geprüft, nicht nur mit 1–2 Einträgen:
20. **Viele Einträge:** 17 SharePoint-Sites × 25 Dokumentbibliotheken, 40 Confluence-Spaces, 200 Ordner. Ab ~7 Einträgen Suche/Filter beim Tippen; Mehrfachauswahl bleibt sichtbar (Chips/Zähler „12 ausgewählt").
21. **Hierarchie:** Site → Bibliothek → Ordner ohne Kontextverlust (Breadcrumb/Baum), Auswahl über Ebenen hinweg nachvollziehbar.
22. **Lange Namen** (80+ Zeichen): Kürzen mit Tooltip, kein Layoutbruch.
23. **Tiefe Verschachtelung** (8 Ebenen), **0 Treffer**, **langsames/fehlgeschlagenes Laden** je Ebene: Leer-/Lade-/Fehlerzustand nach §5.7.
24. **Auswahl alle / keine** dort, wo Massenauswahl realistisch ist.

## Allgemeine Qualität (vom Maintainer bestätigt; zusammengeführt mit best-practices.md)
25. **Reflow:** 320 px und 200 %/400 % Zoom ohne horizontales Scrollen (WCAG 1.4.10).
26. **Tastatur:** komplette Strecke ohne Maus; Fokus kehrt nach Dialog zurück; nach „Weiter" Fokus auf Schrittüberschrift, `document.title` nennt den Schritt.
27. **Fokus nicht verdeckt** durch Sticky-Leiste oder Toast (WCAG 2.4.11).
28. **Absenden bleibt aktiv**; kein `disabled` wegen fehlender Eingaben; Doppelklick legt nichts doppelt an.
29. **Validierung beim Verlassen/Absenden**, nicht beim Tippen; Fehlerzusammenfassung oben mit Sprunglinks, Titel beginnt mit „Fehler: ".
30. **Zurück verliert nichts** (Wizard-Zurück und Browser-Zurück).
31. **Ungespeicherte Änderungen:** Nachfrage nur, wenn wirklich geändert.
32. **Prüfschritt** vor dem Anlegen mit „Ändern"-Links.
33. **Verbindungstest einheitlich:** automatisch bei Weiter + eigener Knopf; Ergebnis inline, nennt Stufe (Adresse · Anmeldung · Rechte); verfällt bei Änderung eines Verbindungsfelds.
34. **Geheimnisse** im Bearbeiten-Modus als „gesetzt" + „Ersetzen".
35. **Auswahlmuster nach Art der Menge** (statt fester Obergrenze):
    - *Geschlossene Menge von Arten* (Quellart, Asset-Art): Kacheln nach §5.11; wächst sie über ~12, gruppieren (z. B. „Dateiablagen · Wikis · Cloud"), nicht durchsuchen.
    - *Offene Menge eigener Objekte* (Katalog, Zuordnung, Token): Asset-Kacheln + `AssetFilterBar` + „Weitere laden" nach §5.11; Stresstest mit 300 Assets.
    - *Fremde Objekte aus einer Quelle* (Sites, Spaces, Buckets, Ordner): homogen, ohne unterscheidendes Icon, oft hierarchisch → Liste/Baum mit Suche, Suchfeld erst ab ~7 Einträgen; keine Kacheln.
36. **Tri-State** im Baum (`aria-checked="mixed"`).
37. **„Alle auswählen" nennt den Umfang**; Filter löscht nie die Auswahl.
38. **Drei Leerzustände** unterscheidbar: erstmals leer · 0 Treffer (mit „Filter zurücksetzen") · kein Zugriff.
39. **Kein Lade-Flackern** < 300 ms; Aktion > 1 s zeigt Fortschritt im auslösenden Knopf.
40. **Indizierungsstatus** überall gleich: Zustand + „n von m" + letzter Lauf + „Teilweise fehlgeschlagen".
41. **Fehler, die Handeln verlangen, nie nur im Toast**; Toast-Timer pausiert bei Hover/Fokus.
42. **Nur-Lesen-Zustand** überall gleich dargestellt.
43. **Deutsche Textlänge:** +40 % Pseudo-Lokalisierung und 35-Zeichen-Kompositum bei 320 px ohne Abschneiden.
44. **Automatik:** axe (inkl. `target-size` ≥ 24 px) ohne Befund, Kontrast ≥ 4,5:1, Screenshots hell/dunkel × 320/1280 px.

## Vorschläge zur Guideline-Anpassung (Entscheidung Maintainer)
- §5.9: fester 6-s-Timer → pausiert bei Hover/Fokus (WCAG 2.2.1); fehlgeschlagener Download nicht nur als Toast.
- §5.11: dritte Mengenart ergänzen — fremde Objekte aus einer Quelle (Sites, Spaces, Ordner) als Liste/Baum mit Suche; geschlossene Artenmengen ab ~12 gruppieren. Keine feste Kachel-Obergrenze.
- accessibility.md: Ziel von WCAG 2.1 auf 2.2 heben (EN 301 549 v4).
