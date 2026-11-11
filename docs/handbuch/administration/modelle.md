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

Werkzeuge nutzt nur eine Frage an einen [Agenten](agenten.md); der gewöhnliche Chat braucht die
Angabe nicht. Was vor dem Einschalten der Agenten zu prüfen ist, steht unter
[Agenten betreiben](../betrieb/agenten-betreiben.md#nach-dem-update).

1. Im Zeilenmenü „Werkzeugfähigkeit festlegen“.
2. „Ungeprüft“, „Fähig“ oder „Nicht fähig“ wählen.
3. Bei „Fähig“ und „Nicht fähig“ die Grundlage wählen: „Gemessen“ verlangt die Kennung des
   Messlaufs, „Manuell gesetzt“ nicht. Speichern.

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
Messergebnis oder eine Behauptung ist. Eine gemessene Angabe entsteht mit
[Werkzeuge prüfen](#werkzeuge-prüfen) oder mit der Messung unter
[Modell auf Werkzeugaufrufe messen](../betrieb/modell-endpunkte.md#modell-auf-werkzeugaufrufe-messen):
Ergebnis und Kennung des Messlaufs tragen Sie im Dialog mit der Grundlage „Gemessen“ ein. Ersetzt
eine Änderung eine gemessene Angabe, warnt der Dialog vorher, und die Schaltfläche heißt „Ersetzen“.
Eine Person oder Gruppe steht nicht am
Modell. Wer die Angabe geändert hat, steht im
[Nachweisprotokoll](nachweisprotokoll-lesen.md) als Änderung des Chat-Modells, mit dem Wert vorher
und nachher.

**Ändern sich Basis-Adresse oder Modell-Kennung, ist die Angabe wieder „Ungeprüft“**, samt
Grundlage und Datum. Das Formular weist vor dem Speichern darauf hin. Nach einem Umzug des
Modellservers oder einem Update der Laufzeit mit neuer Adresse muss also erneut geprüft werden. Ein
Update an derselben Adresse setzt nichts zurück; dann die Angabe selbst auf „Ungeprüft“ stellen und
neu prüfen. Hat jemand anderes das Modell geändert, während der Dialog offen war, lehnt OPAA das
Speichern ab; die Liste neu laden und die Angabe für die neue Kombination erneut prüfen.

Beim Update auf die Version, die die Werkzeugfähigkeit einführt, kann bei mehreren Instanzen eine
Instanz der alten Version die Adresse ändern, ohne die Angabe zurückzusetzen. Basis-Adresse und Modell-Kennung deshalb erst ändern, wenn alle
Instanzen die neue Version fahren, oder die Angabe danach selbst auf „Ungeprüft“ stellen.

Ist das aktive Modell beim Start „Ungeprüft“, schreibt jede Instanz eine Warnung ins Log; der Start
läuft weiter.

## Werkzeuge prüfen

Ist der Schalter „Agenten“ eingeschaltet, misst OPAA die Werkzeugfähigkeit eines Chat-Modells selbst,
ohne Quellstand und ohne Kommandozeile. Ist er aus, fehlt der Menüpunkt.

Für Agenten braucht OPAA ein Modell mindestens der Klasse Gemma 4 31B. Kleinere Modelle rufen
Werkzeuge oft unzuverlässig auf; wer eines einsetzen will, prüft es hier vorher.

1. Im Zeilenmenü „Werkzeuge prüfen“.
2. Umfang wählen: „Schnell“ (jeder Fall einmal) für einen ersten Eindruck, „Gründlich“ (jeder Fall
   dreimal) für eine Angabe, die Sie festhalten wollen. „Prüfung starten“.
3. Nach einigen Minuten zeigt der Dialog je Prüffrage das Ergebnis und ob die Mindestanforderung
   erfüllt ist. „Ergebnis übernehmen“ öffnet „Werkzeugfähigkeit festlegen“ mit „Fähig“ oder
   „Nicht fähig“, Grundlage „Gemessen“ und der Kennung des Messlaufs; Speichern hält es fest.

**Gut zu wissen:**

- Die Prüfung stellt dem Modell erfundene Fragen mit dem Suchwerkzeug der Agenten; Bibliotheken und
  Chats berührt sie nicht. Sie läuft auf dem Server weiter, wenn Sie den Dialog schließen, und es
  läuft höchstens eine zur selben Zeit. Ein Neustart des Servers bricht sie ab; bis OPAA das
  merkt, erscheint sie bis zu 15 Minuten als laufend, und so lange lässt sich keine neue starten.
- Schaltet jemand die Agenten ab, bricht eine laufende Prüfung nach dem aktuellen Fall ab. Scheitern
  Aufrufe am Endpunkt, etwa an einer Drosselung, endet sie als gescheitert statt mit einem Ergebnis.
- „Bericht herunterladen“ liefert den vollständigen Bericht mit jedem Lauf im Wortlaut, wie die
  Messung unter [Modell auf Werkzeugaufrufe messen](../betrieb/modell-endpunkte.md#modell-auf-werkzeugaufrufe-messen).
- Ein Ergebnis für eine frühere Basis-Adresse oder Modell-Kennung lässt sich nicht übernehmen.

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

Je ein Reiter mit Auswahlkästchen zum Einschalten, Basis-Adresse, Modell-Kennung und Schlüssel, bei der Spracheingabe auch
die Sprache und die „Begriffsliste“: Fachbegriffe und Abkürzungen des Hauses, etwa
„Einspruchsfrist, Grunderwerbsteuer, AO, BZSt“, die das Modell damit zuverlässiger erkennt. Jede
Person ergänzt in ihren Einstellungen eine eigene Liste; sie geht nach der des Hauses an das Modell.
Grenzen und welche Dienste die Liste auswerten, stehen unter
[Begriffslisten](../betrieb/modell-endpunkte.md#begriffslisten). „Verbindung testen“ prüft, ohne zu
speichern. Fehlt der Betriebsschalter für die Spracheingabe, lässt sie sich nur vorbereiten. Die
Spracheingabe verarbeitet Stimmen; sie gehört vor dem Einschalten mit der Personalvertretung
geklärt ([Einführung vorbereiten](einfuehrung.md)).

## Live-Transkript einrichten

Mit Live-Transkript sieht die Person den Text schon beim Sprechen. Es kommt zum Diktat hinzu und
ersetzt es nicht: Das Diktat bleibt immer verfügbar, und fällt der Live-Dienst aus, wird die ganze
Aufnahme wie beim Diktat verarbeitet. Im Chat gibt es keinen Umschalter; das Mikrofon nutzt Live von
selbst, wenn es verfügbar ist. Reiter „Spracheingabe“, Abschnitt „Live-Transkript (optional)“:

1. „Live-Transkript einschalten“ anschalten.
2. Den „Live-Dienst“ wählen: „OpenAI“, „vLLM (eigener Server)“ oder „Mistral“. „Live-Adresse“ und
   „Live-Modell“ werden dabei vorbelegt; beim eigenen Server den Host anpassen. Sind Basis-Adresse
   und Modell-Kennung des Diktats noch leer, belegt die Wahl auch sie vor, siehe
   [Nur ein Live-Dienst vorhanden?](../betrieb/modell-endpunkte.md#nur-ein-live-dienst-vorhanden).
3. Den „Live-Schlüssel“ eintragen, wenn der Dienst einen anderen Schlüssel als das
   Transkriptionsmodell braucht. Ohne ihn geht der Schlüssel des Transkriptionsmodells mit, aber
   nur an die gleiche Adresse bis zum Port (Schema, Host, Port). OpenAI und Mistral verlangen
   einen Schlüssel: Liegt das Transkriptionsmodell unter einer anderen Adresse, etwa auf einem
   eigenen speaches, lehnt „Speichern“ ohne Live-Schlüssel ab. Das Auge neben dem Feld zeigt den eingetippten Schlüssel zur Kontrolle.
4. „Gleichzeitige Live-Sitzungen“ für die ganze Installation festlegen. Wer darüber diktiert,
   bekommt den Text wie ohne Live nach dem Ende der Aufnahme.
5. „Live-Dienst testen“ öffnet eine Sitzung ohne Audio; dann „Speichern“.

Den Haken entfernen und „Speichern“ entfernt den Live-Dienst samt Schlüssel. Fällt der Live-Dienst aus,
diktieren alle weiter, nur ohne Live-Text. Ein externer Dienst erhält die Aufnahme laufend während
des Sprechens; das gehört wie die Spracheingabe selbst vorher mit Datenschutz und
Personalvertretung geklärt. Was ein eigener Server leisten muss, steht unter
[Live-Transkript im Betrieb](../betrieb/modell-endpunkte.md#live-transkript).

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
