# Metadaten pflegen

> **Entwurf** — gegen den Code geprüft, vom Maintainer noch nicht abgenommen.

> **Wer darf das:** Werte setzen ab der Rolle *Bearbeiter* an der Bibliothek, eigene Felder anlegen
> ab *Verwalter*. Ab Werk hat beides, wer die Bibliothek angelegt hat.

Die Suche filtert danach zuverlässig, etwa „nur Dienstanweisungen“ oder „Stand nach 2024“.

Jedes Dokument trägt die Kernfelder **Titel**, **Dokumentart** und **Datum/Stand**. OPAA füllt sie
selbst aus Datei und Inhalt, nach der Regel **lieber leer als geraten**: Ein Dokument ohne Wert
schließt kein Filter aus.

## Fehlende Werte nachtragen

1. Reiter „Metadaten“, Abschnitt „Füllgrad“: Er nennt je Feld, wie viele Dokumente einen Wert tragen.
2. „Anzeigen“ öffnet im Reiter „Dokumente“ genau die Dokumente ohne Wert.
3. Einzeln: „Metadaten von … anzeigen“, dann „Bearbeiten“. Oder mehrere Zeilen ankreuzen und
   „Feld setzen“ wählen, für bis zu 1000 Dokumente auf einmal.

Lässt sich ein Wert nicht bestimmen, „Kein Wert ermittelbar“ wählen; das Feld zählt dann nicht mehr
als offen.

## Ein eigenes Feld anlegen

1. Reiter „Metadaten“, Abschnitt „Felder“, „Feld anlegen“.
2. „Feldschlüssel“, „Feldname“ und „Typ“ wählen: **Auswahl** (Werteliste), **Datum** oder
   **Kennung** (Muster wie `^RF-[A-Z]+-[0-9]+$`).
3. „Wirkt im Filter“ und/oder „Wirkt im Kontextpräfix“ ankreuzen; eins von beiden ist Pflicht. Im
   Kontextpräfix steht der Wert vor jedem Textabschnitt, sodass die Suche ihn auch ohne Filter findet.

Höchstens fünf eigene Felder je Bibliothek. Einen benutzten Listenwert entfernen Sie, indem Sie ihn
auf einen anderen Wert oder „leer“ abbilden. Ein Feld löschen entfernt seine Werte an allen
Dokumenten. In beiden Fällen nennt OPAA vorher, wie viele Dokumente das betrifft.

**Gut zu wissen:**

- Ein von Hand gesetzter Wert hat Vorrang und übersteht eine Neuaufnahme des Dokuments.
- Jede Änderung steht mit Alt- und Neuwert im Nachweisprotokoll.
- Die Systemverwaltung sieht die Werte nur mit einer eigenen Rolle an der Bibliothek.
