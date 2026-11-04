# Modelle verwalten

> **Wer:** Systemverwaltung, „Administration → Modelle“.

OPAA bringt kein Sprachmodell mit, sondern ruft Modelle an Endpunkten auf, die das Haus festlegt.
Welche Adressen erlaubt sind und wie ein Modellserver betrieben wird, regelt der Betrieb
([Modelle](../betrieb/modell-endpunkte.md)).

| Reiter | Rolle | Wechsel |
|---|---|---|
| „Chat-Modelle“ | Antwort, Teilfragen, Chat-Titel, Metadaten | jederzeit, ohne Neustart |
| „Einbettung“ | Vektoren für die Suche | ein anderes Modell bettet den Bestand neu ein |
| „Reranking“ | ordnet Treffer neu, optional | jederzeit |
| „Spracheingabe“ | wandelt Diktat in Text, optional | jederzeit |

## Chat-Modell anlegen und aktivieren

1. „Neues Modell“, „Basis-Adresse“ eintragen oder eine Vorlage wählen (Ollama, OpenAI, Anthropic).
2. „Modelle abrufen“ und die Kennung wählen; eine Kennung lässt sich auch von Hand eintragen.
3. Anzeigename und maximale Antwortlänge prüfen. Die Temperatur leer lassen, wenn der Anbieter
   keine andere als seine eigene annimmt.
4. Der Verbindungstest läuft von selbst; „Verbindung testen“ wiederholt ihn.
5. Im Zeilenmenü aktivieren. Genau eines ist aktiv, und das wirkt sofort auf allen Instanzen.

**Ein Ersatzmodell vorab anlegen und testen**, nicht aktivieren: Fällt das aktive aus, schalten Sie
in Sekunden um. Ein Modell bei einem anderen Anbieter schickt Fragen und Textausschnitte an einen
anderen Empfänger; das klärt das Haus vorher mit Datenschutz und Personalvertretung.

## Embedding-Modell wechseln

Reiter „Einbettung“, „Modell wechseln oder Endpunkt ändern“:

1. Adresse, Modell-Kennung, Eingabegrenze und Schlüssel eintragen.
2. „Probeeinbettung“. Ist es dasselbe Modell an einem anderen Server, bietet die Seite
   „Endpunkt ändern“ an: ohne Neueinbettung, sofort wirksam.
3. Die **Vorschau** nennt Chunks, Dauer und zusätzlichen Speicher.
4. „Wechsel starten“. Bis zum Ende sucht der Chat nur im Volltext und zeigt „Eingeschränkte Suche“.
5. Ist alles neu eingebettet, schaltet OPAA um. Bis dahin kehrt „Wechsel verwerfen“ zurück; danach
   sind die alten Vektoren weg.

## Reranking und Spracheingabe

Je ein Reiter mit Schalter, Basis-Adresse, Modell-Kennung und Schlüssel, bei der Spracheingabe auch
die Sprache und die „Begriffsliste“: Fachbegriffe und Abkürzungen des Hauses, etwa „Einspruchsfrist,
Grunderwerbsteuer, AO, BZSt“, die das Modell damit zuverlässiger erkennt. Jede Person ergänzt in
ihren Einstellungen eine eigene Liste; sie geht nach der des Hauses an das Modell. „Verbindung testen“ prüft, ohne zu speichern. Fehlt der Betriebsschalter für die
Spracheingabe, lässt sie sich nur vorbereiten. Die Spracheingabe verarbeitet Stimmen; sie gehört vor
dem Einschalten mit der Personalvertretung geklärt ([Einführung vorbereiten](einfuehrung.md)).

**Gut zu wissen:**

- Ein gespeicherter Schlüssel geht nur an denselben Endpunkt. Bei einer anderen Adresse ist er neu
  einzutragen oder zu entfernen, und er erscheint nie wieder.
- Ob ein Modell erzeugten Text markiert (Art. 50 KI-Verordnung), hängt am Anbieter; ein
  Modellwechsel gehört deshalb in diese Prüfung.
- Den Zustand aller Rollen zeigt auch [Suche und Indexierung](suche-und-indexierung.md).
