# Vorlage anlegen

**Ziel:** Eine Word-Vorlage mit Platzhaltern, nach der ein Agent fertige Dokumente erstellt.
{: .kopf }

**Wer:** Konto mit dem Anlegerecht „Vorlagen anlegen“, solange die Systemverwaltung Vorlagen eingeschaltet hat
{: .kopf }

1. In Word das Dokument wie gewohnt gestalten und an den variablen Stellen Platzhalter schreiben
   (siehe unten). Als `.docx` speichern, ohne Makros.
2. Im [Katalog](katalog.md) „Neuer Katalogeintrag“, dann „Vorlagen“ und „Weiter“.
3. **Stammdaten:** Name, Beschreibung und „Word-Datei auswählen“.
4. **Freigaben:** als Eigentümer „Mein Konto“ oder „Eine Gruppe“; für eine Hausvorlage ist die
   Gruppe die haltbarere Wahl. Auf Wunsch Rollen vormerken.
5. **Prüfen:** die Angaben durchsehen, einzelne über „Ändern“ korrigieren, dann „Anlegen“.

OPAA prüft die Datei beim Hochladen und zeigt im Reiter „Platzhalter“, welche Werte ein Agent
liefern muss. Abgelehnt werden Dateien, die kein Word-Dokument sind, größer als 10 MB sind, einen Block nicht
schließen oder aktive Inhalte tragen: Makros, ActiveX-Elemente, eingebettete Objekte, eine
verknüpfte Dokumentvorlage, eine Seriendruck-Datenquelle, Makros an Formularfeldern oder Felder,
die Inhalte nachladen. Erlaubt sind Links und einfache
Felder wie Seitenzahl, Datum, Verweis und Inhaltsverzeichnis, aber kein Feld innerhalb eines
anderen Feldes (etwa in einer WENN-Bedingung).

## Platzhalter schreiben

| Was | Schreibweise | Beispiel |
|---|---|---|
| Feld | `{{ name }}` | `Az.: {{ aktenzeichen }}` |
| Liste | `{% for e in name %}` … `{% endfor %}` | `{% for m in massnahmen %}{{ m.titel }} bis {{ m.frist }}{% endfor %}` |
| Bedingung | `{% if name %}` … `{% endif %}` | `{% if vertraulich %}VS – NUR FÜR DEN DIENSTGEBRAUCH{% endif %}` |

Erlaubt sind nur Felder, Listen und Bedingungen, dazu Vergleiche (`{% if betrag > 0 %}`), `+`
und `-`, `~` zum Verketten und die Filter `default`, `upper`, `lower`, `title`, `capitalize`, `trim`, `round`,
`int`, `float`, `abs`, `join`, `length`, `count`, `first` und `last` mit festen Werten als
Argument, etwa `{{ name|default('unbekannt') }}`. Funktionsaufrufe, `set`, `macro`, andere Filter
und Ähnliches lehnt OPAA beim Hochladen ab, damit jeder Wert als Text im Dokument landet.

Eine Liste wiederholt ihren Teil für jeden Eintrag; soll sie eine Tabellenzeile wiederholen, steht
`{%tr for … %}` in der ersten und `{%tr endfor %}` in einer eigenen Zeile darunter. Ein `-` am Rand eines Tags (`{%- if … -%}`) entfernt Leerraum wie gewohnt. Namen bestehen
aus Buchstaben, Ziffern und Unterstrich. Ein Kommentar `{# … #}` erscheint nicht im Ergebnis.

## So nutzt ein Agent die Vorlage

Ein Agent mit dem Werkzeug `fill_template` bekommt in jedem Lauf die Vorlagen angeboten, die die
fragende Person lesen darf, jeweils mit ihren Platzhaltern. Das Modell liefert nur die Werte, etwa
Aktenzeichen und Maßnahmen aus den gefundenen Belegen. OPAA füllt die Vorlage damit in der
[Sandbox](../betrieb/sandbox.md) und hängt das Ergebnis als Word-Datei und PDF an die Antwort; die
Schritte der Antwort zeigen „Vorlage“ mit Vorlage und Dateien. Liefert das Modell einen Wert
nicht, bleibt ein Feld leer (oder zeigt, was `default(…)` vorgibt), eine Liste hat keine Einträge,
und eine Bedingung gilt als nicht erfüllt. Rechnet die Vorlage mit einem fehlenden Wert, scheitert
das Befüllen.

Sind Vorlagen ausgeschaltet, darf die Person keine Vorlage lesen oder ist die Sandbox nicht
erreichbar, entsteht keine Datei: Der Agent antwortet im Chat und sagt das dazu.

**Rollen an der Vorlage:**

| Rolle | Darf |
|---|---|
| Leser | Vorlage sehen, herunterladen und von Agenten nutzen lassen |
| Bearbeiter | zusätzlich die Datei ersetzen |
| Verwalter | zusätzlich Name, Beschreibung und Rechte ändern |
| Eigentümer | zusätzlich die Vorlage löschen |

**Gut zu wissen:**

- OPAA führt nichts aus der Datei aus. Ausgefüllt wird sie erst, wenn ein Agent sie nutzt, in
  einer abgeschotteten Umgebung; Werte aus dem Chat landen dort immer als Text, nie als Befehl.
- Eine ersetzte Datei wird wie beim Anlegen geprüft, die Platzhalter werden neu gelesen.
- Im Nachweisprotokoll stehen Anlegen, Ändern und Löschen, nie der Inhalt der Datei.
