# Vorlage anlegen

**Ziel:** Eine Word-Vorlage mit Platzhaltern, nach der ein Agent fertige Dokumente erstellt.
{: .kopf }

**Wer:** Konto mit dem Anlegerecht „Vorlagen anlegen“, solange die Systemverwaltung Vorlagen eingeschaltet hat
{: .kopf }

1. In Word das Dokument wie gewohnt gestalten und an den variablen Stellen Platzhalter schreiben
   (siehe unten). Als `.docx` speichern, ohne Makros.
2. Im [Katalog](katalog.md) „Neuer Katalogeintrag“, dann „Vorlagen“ und „Weiter“.
3. **Stammdaten:** Name, Beschreibung und „Word-Datei auswählen“.
4. **Eigentümer:** „Mein Konto“ oder „Eine Gruppe“; für eine Hausvorlage ist die Gruppe die
   haltbarere Wahl. Darunter die „Verantwortliche Stelle“: eine Gruppe oder eine
   Organisationseinheit bzw. Funktion als Text, keine Einzelperson. Vorbelegt ist die gewählte
   Gruppe, sonst Ihre erste.
5. **Rechte:** auf Wunsch Rollen vormerken, dann „Vorlage anlegen“.

OPAA prüft die Datei beim Hochladen und zeigt im Reiter „Platzhalter“, welche Werte ein Agent
liefern muss. Abgelehnt werden Dateien, die kein Word-Dokument sind, größer als 10 MB sind, einen Block nicht
schließen oder aktive Inhalte tragen: Makros, ActiveX-Elemente, eingebettete Objekte, eine
verknüpfte Dokumentvorlage oder Felder wie DDE und INCLUDEPICTURE, die Inhalte nachladen. Links
bleiben erlaubt.

## Platzhalter schreiben

| Was | Schreibweise | Beispiel |
|---|---|---|
| Feld | `{{ name }}` | `Az.: {{ aktenzeichen }}` |
| Liste | `{% for e in name %}` … `{% endfor %}` | `{% for m in massnahmen %}{{ m.titel }} bis {{ m.frist }}{% endfor %}` |
| Bedingung | `{% if name %}` … `{% endif %}` | `{% if vertraulich %}VS – NUR FÜR DEN DIENSTGEBRAUCH{% endif %}` |

Eine Liste wiederholt ihren Teil für jeden Eintrag; soll sie eine Tabellenzeile wiederholen, steht
`{%tr for … %}` in der ersten und `{%tr endfor %}` in einer eigenen Zeile darunter. Ein `-` am Rand eines Tags (`{%- if … -%}`) entfernt Leerraum wie gewohnt. Namen bestehen
aus Buchstaben, Ziffern und Unterstrich. Ein Kommentar `{# … #}` erscheint nicht im Ergebnis.

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
- Fehlt die verantwortliche Stelle länger als die Frist der Systemverwaltung, wird die Vorlage
  gesperrt und von keinem Agenten mehr angeboten; ändern lässt sich die Stelle im Reiter „Freigaben“.
- Im Nachweisprotokoll stehen Anlegen, Ändern und Löschen, nie der Inhalt der Datei.
