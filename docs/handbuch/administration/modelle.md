# Modelle verwalten

**Ziel:** Chat, Suche, Spracheingabe und Texterkennung nutzen die Modelle, die das Haus gewählt hat.
{: .kopf }

**Wer:** Systemverwaltung, „Administration → Modelle“
{: .kopf }

OPAA bringt kein Sprachmodell mit, sondern ruft Modelle an Endpunkten auf, die das Haus festlegt.
Welche Adressen erlaubt sind und wie ein Modellserver betrieben wird, regelt der Betrieb
([Modelle](../betrieb/modell-endpunkte.md)).

| Reiter | Rolle | Wechsel |
|---|---|---|
| „Chat-Modelle“ | Antwort, Teilfragen, Chat-Titel, Metadaten | jederzeit, ohne Neustart |
| „Einbettung“ | Vektoren für die Suche | ein anderes Modell bettet den Bestand neu ein |
| „Reranking“ | ordnet Treffer neu, optional | jederzeit |
| „Spracheingabe“ | wandelt Diktat in Text, optional | jederzeit |
| „Texterkennung“ | liest gescannte PDFs (OCR), optional | jederzeit |

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

## Werkzeugfähigkeit festhalten

Manche Anfragen lassen das Modell Werkzeuge aufrufen, etwa die eigene Suche mehrmals hintereinander.
Ob ein Modell das zuverlässig kann, hängt nicht nur am Modell, sondern auch an Quantisierung,
Laufzeit und deren Einstellungen: Eine Laufzeit braucht einen eigenen Schalter dafür, eine andere
verliert Werkzeugaufrufe bei der Ausgabe im Fluss, und eine zu kleine Kontextlänge schneidet die
Werkzeugbeschreibungen stillschweigend ab. Die Angabe gilt deshalb für diesen Endpunkt und diese
Laufzeit.

1. Im Zeilenmenü „Werkzeugfähigkeit festlegen“.
2. „Ungeprüft“, „Fähig“ oder „Nicht fähig“ wählen und speichern.

| Wert | Anfrage mit Werkzeugen |
|---|---|
| „Ungeprüft“ (Vorgabe jedes Modells) | wird abgelehnt |
| „Fähig“ | läuft |
| „Nicht fähig“ | wird abgelehnt |

Abgelehnt heißt: Die Person bekommt eine Meldung, dass das aktive Modell dafür nicht geprüft oder
nicht fähig ist und die Systemverwaltung das ändern kann. OPAA beantwortet eine solche Anfrage nie
stillschweigend ohne Werkzeuge. Der gewöhnliche Chat funktioniert mit jedem Wert.

Die Spalte „Werkzeuge“ zeigt neben dem Wert die **Grundlage** der Angabe und ihr Datum: „manuell
gesetzt“ oder „gemessen“ mit dem Verweis auf den Messlauf. So ist erkennbar, ob „Fähig“ ein
Messergebnis oder eine Behauptung ist. In der Oberfläche wird die Angabe manuell gesetzt; eine
gemessene Angabe samt Messlauf trägt die Verwaltungsschnittstelle ein. Eine Person oder Gruppe
steht nicht am Modell. Wer die Angabe geändert hat, steht im
[Nachweisprotokoll](nachweisprotokoll-lesen.md) als Änderung des Chat-Modells, mit dem Wert vorher
und nachher.

**Ändern sich Basis-Adresse oder Modell-Kennung, ist die Angabe wieder „Ungeprüft“**, samt
Grundlage und Datum. Das Formular weist vor dem Speichern darauf hin. Nach einem Umzug des
Modellservers oder einem Update der Laufzeit mit neuer Adresse muss also erneut geprüft werden. Ein
Update an derselben Adresse setzt nichts zurück; dann die Angabe selbst auf „Ungeprüft“ stellen und
neu prüfen.

Ist das aktive Modell beim Start „Ungeprüft“, schreibt jede Instanz eine Warnung ins Log; der Start
läuft weiter.

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
die Sprache und die „Begriffsliste“: Fachbegriffe und Abkürzungen des Hauses, etwa
„Einspruchsfrist, Grunderwerbsteuer, AO, BZSt“, die das Modell damit zuverlässiger erkennt. Jede
Person ergänzt in ihren Einstellungen eine eigene Liste; sie geht nach der des Hauses an das Modell.
Grenzen und welche Dienste die Liste auswerten, stehen unter
[Begriffslisten](../betrieb/modell-endpunkte.md#begriffslisten). „Verbindung testen“ prüft, ohne zu
speichern. Fehlt der Betriebsschalter für die Spracheingabe, lässt sie sich nur vorbereiten. Die
Spracheingabe verarbeitet Stimmen; sie gehört vor dem Einschalten mit der Personalvertretung
geklärt ([Einführung vorbereiten](einfuehrung.md)).

## Texterkennung einrichten

Die Texterkennung liest gescannte PDFs ohne Textebene, damit die Suche sie findet. Reiter
„Texterkennung“:

1. Unter „Anbindung“ wählen: „docling-serve“ für den mitgelieferten OCR-Dienst oder ein eigenes
   docling-serve, „Vision-Modell (OpenAI-kompatibel)“, wenn im Haus schon ein Modellserver mit
   Bildeingang läuft.
2. „Basis-Adresse“ eintragen, beim Vision-Modell auch die „Modell-Kennung“, und einen
   „Zugangsschlüssel“, falls der Dienst einen verlangt.
3. „Verbindung testen“ liest eine mitgelieferte Testseite in drei Stufen: „Adresse“ (der Dienst
   ist erreichbar), „Anmeldung“ (der Schlüssel wird angenommen) und „Erkennung“ (die Testseite
   wird gelesen; scheitert auch, wenn der Dienst dabei nicht rechtzeitig antwortet). Die Meldung
   steht an der Stufe, die scheitert.
4. „Texterkennung einschalten“ und „Speichern“. Bestehende Scans werden danach automatisch
   nachverarbeitet.

Hat der Betrieb die Texterkennung nicht freigegeben (`OPAA_OCR_ENABLED`), zeigt der Reiter nur
diesen Hinweis; gescannte PDFs werden dann weiter abgewiesen. Ein Vision-Modell liefert keine Lage
des Textes: Fundstellen zeigt OPAA nur auf ihrer Seite, nicht als Rahmen. Seiten gehen ganz an den
Dienst; ein Dienst außerhalb des Hauses gehört vorher mit Datenschutz und Personalvertretung
geklärt ([Texterkennung im Betrieb](../betrieb/modell-endpunkte.md#texterkennung-einschalten)).

**Gut zu wissen:**

- Ein gespeicherter Schlüssel geht nur an denselben Endpunkt. Bei einer anderen Adresse ist er neu
  einzutragen oder zu entfernen, und er erscheint nie wieder.
- Ob ein Modell erzeugten Text markiert (Art. 50 KI-Verordnung), hängt am Anbieter; ein
  Modellwechsel gehört deshalb in diese Prüfung.
- Den Zustand aller Rollen zeigt auch [Suche und Indexierung](suche-und-indexierung.md).
