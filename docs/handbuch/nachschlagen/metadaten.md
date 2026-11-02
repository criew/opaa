# Metadaten

Metadaten sind **keine Suchbegriffe, sondern Bedingungen an das Ergebnis**: „nur
Dienstanweisungen“, „Stand nach 2024“. Pflegen: [Metadaten pflegen](../pflegen/metadaten-pflegen.md).

Die Regel hinter allem: **lieber leer als geraten.** Ein geratener Wert macht ein Dokument unter
einem Filter unsichtbar, ein leeres Feld ist sichtbar und behebbar. Darum gibt es keine
Vorgabewerte, und ein Dokument ohne Wert schließt kein Filter aus; im Beleg heißt es „ohne Angabe“.

## Felder

| Feld | Art | Filterbar |
|---|---|---|
| Titel | Kernfeld, Text | nein |
| Dokumentart | Kernfeld, Wert aus dem Vokabular | ja |
| Datum/Stand | Kernfeld, Tag, Monat oder Jahr | ja, als Zeitfenster |
| Absender | nur E-Mail | ja, genau eine Adresse |
| An, Betreff | nur E-Mail | nein |
| eigene Felder | bis zu fünf je Bibliothek: Auswahl, Datum oder Kennung | ja, nur in ihrer Bibliothek |

„Fassung 2024“ wird als Jahr gespeichert und passt in jedes Fenster, das 2024 berührt: im Zweifel zu
weit, nie zu eng. Das Änderungsdatum einer Datei zählt nicht als Stand.

**Vokabular der Dokumentart:** Satzung/Ordnung, Dienstanweisung, Vermerk, Protokoll,
Bescheid-Vorlage, Formular, Gebührenverzeichnis, Präsentation, Sonstiges. Ein Wert außerhalb dieser
Liste lässt sich nicht speichern.

## Woher ein Wert kommt

| Herkunft | Anzeige |
|---|---|
| feste Regeln aus Dateiname, Dokumenteigenschaften, Frontmatter oder erster Überschrift | „automatisch ermittelt“ |
| Sprachmodell, nur wenn die Bibliothek es eingeschaltet hat | „abgeleitet“, mit Konfidenz |
| eine Person | „manuell“ |

- Ein manueller Wert wird von keiner Automatik überschrieben.
- „Kein Wert ermittelbar“ setzt nur eine Person; es zählt nicht mehr als offen.
- **Modellgestützte Ermittlung** und **freie Schlagworte** sind je Bibliothek ab Werk aus. Ist das
  Chat-Modell extern betrieben, verlässt damit der Inhalt jedes aufgenommenen Dokuments das Haus;
  OPAA fragt vorher. Übernommen wird nur ein Wert aus der Liste mit Konfidenz ab 0,80. Auf einem
  Bestand, für den das Vokabular nicht passt, rät das Modell oft falsch: dort ausgeschaltet lassen.
- Freie Schlagworte (bis zu fünf je Dokument) helfen nur beim Finden; sie filtern nie.

## Wo Metadaten wirken

| Stelle | Wirkung |
|---|---|
| Filter neben den Suchbereichs-Chips | schränkt Vektor- und Volltextsuche vor dem Ranking ein. Ein Kernfeld wird nur angeboten, wenn es gut genug gefüllt ist (Dokumentart 90 %, Datum 75 %) |
| Kontextpräfix | Titel immer, andere Felder je Bibliothek zuschaltbar: steht beim Einbetten vor jedem Abschnitt, der gespeicherte Text bleibt unverändert |
| externe KI-Werkzeuge | bekommen zu jeder Fundstelle alle gesetzten Felder |

Ändert sich, was im Kontextpräfix steht, nennt OPAA vor dem Speichern, wie viel neu einzubetten ist.
Neu eingebettet wird erst, wenn die Systemverwaltung den Nachlauf startet
([Suche und Indexierung überwachen](../verwalten/suche-und-indexierung.md)).

Werte sehen ab der Rolle *Leser*, setzen ab *Bearbeiter*, Felder und Schalter ab *Verwalter* an der
Bibliothek. Die Systemverwaltung sieht Werte nur mit einer eigenen Rolle an der Bibliothek. Eine
Bibliothek, die jemand nicht lesen darf, fehlt in allen Zahlen.
