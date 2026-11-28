# Belege prüfen

**Ziel:** Sie sehen, worauf sich eine Antwort stützt, und haben die Stelle im Original vor sich.
{: .kopf }

1. Unter der Antwort den Belegstand wählen, etwa „Belegt · 3 Stellen in 2 Dokumenten“, oder eine
   Fußnote im Text anklicken.
2. Das Fenster „Belege dieser Antwort“ zeigt die zitierten Dokumente mit Fundort.
3. „Dokument öffnen“ zeigt das Dokument bildschirmfüllend im Lesemodus, springt an die zitierte
   Stelle und hebt sie hervor. „Herunterladen“ speichert das Original.
4. „Zurück zur Antwort“ oben links, das ✕ oder Escape führt zurück in den Chat.

**Der Belegstand** sagt, worauf sich die Antwort stützt, nicht, ob sie stimmt:

| Stufe | Bedeutung |
|---|---|
| „Belegt“ | jede Aussage durch bestätigte Belege gedeckt |
| „Teilweise belegt“ | ein Teil ohne Fundstelle oder ein Beleg nicht bestätigt; der Grund steht daneben |
| „Nicht belegt“ | kein bestätigter Beleg |

opaa prüft jeden Beleg selbst: Hat die Antwort das Dokument wirklich gesehen, und passen Beträge,
Daten und Paragrafen? Was durchfällt, heißt **„Beleg nicht bestätigt“** und trägt ein Warnsymbol.
Sagt eine Antwort, etwas stehe nicht in den Dokumenten, sucht opaa die Begriffe der Frage noch
einmal in Ihrem Suchbereich; findet es dort neue Stellen, wird die Antwort einmal mit ihnen neu
geschrieben ([Die Antwort](../referenz/wie-die-suche-arbeitet.md#die-antwort)).

Die Antwort eines Agenten trägt zusätzlich die Kennzeichnung „Entwurf“ und einen Stempel mit
Agent, Fassung, Bibliotheken, Vorlagen und Modell; wie Sie ihn lesen, steht unter
[Agentenergebnis prüfen](agentenergebnis-pruefen.md). Der Belegstand gilt für sie genauso; jede
Frage, mit der der Agent gesucht hat, zählt dabei als ein Teil: „Belegt“ heißt, zu jeder dieser
Fragen zitiert die Antwort ein Dokument, das eine Suche danach gefunden hat. Schreibt der Agent
keinen Text, steht statt der Antwort ein Hinweis, die Frage erneut zu stellen.

**Im Lesemodus** steht links die Karte „Fundstelle“ mit Fundort, Zitat und „Zur Fundstelle“, rechts
das Dokument auf hellem Papier, auch im dunklen Farbschema. Die Stelle trägt eine hellblaue Fläche,
eine blaue Kante links und einmal das Etikett „Fundstelle“. Auf dem Smartphone wird die Karte zu
einer Leiste über dem Dokument; ein Tipp auf den Fundort klappt das Zitat auf.

PDFs öffnen auf der Seite der Fundstelle. Word-Dateien (`.docx`), CSV-Tabellen und Mails (`.eml`)
erscheinen als Original: Word mit seinem Layout, CSV als Tabelle mit markierter Zeile, eine Mail mit
Betreff, Absender, Empfängern, Datum und Text. Andere Office-Dateien, sehr große Dateien und Mails
ohne Textteil öffnen als Textfassung an der Passage; ein kurzer Hinweis sagt dann, warum.
Confluence-Seiten öffnen an der Quelle.

**Die Gliederung** steht links unter der Karte, wenn das Dokument Überschriften hat: bei PDFs die
Lesezeichen, bei Word-Dateien die Absätze in einer Überschriften-Formatvorlage, bei Markdown die
Überschriften, in der Textfassung (etwa ODT oder HTML) die beim Einlesen erkannten Abschnitte. Ein
Klick springt zur Überschrift; hervorgehoben ist der Abschnitt, den Sie gerade lesen. Tiefere Ebenen
klappen Sie mit dem Pfeil davor auf. Das Symbol über dem Dokument blendet die Gliederung aus und
wieder ein, auf dem Smartphone steht sie in der aufklappbaren Leiste. Ein Dokument ohne Überschriften
hat keine Gliederung.

Bei einem gescannten PDF stammt der Text aus der Texterkennung; Beleg und Ansicht tragen dann den
Hinweis „Maschinell erkannt, maßgeblich ist das Original“.
„Dokument öffnen“ zeigt den unveränderten Scan und zieht einen Rahmen um die zitierte Stelle,
daneben steht unter „Erkannter Text“, was gelesen wurde. Kennt die Texterkennung die Lage der Stelle
nicht, öffnet der Scan nur auf ihrer Seite.

**Gut zu wissen:**

- Ein Beleg öffnet nur Dokumente, die Sie lesen dürfen.
- Links in einem Word-Original öffnen nur Webadressen (`http`, `https`), in einem neuen Tab.
- „Stand vom …“ heißt: Die Bibliothek wird gerade nicht aktualisiert. „Das Original wurde seit der
  Antwort geändert“ heißt: Die Datei ist nicht mehr die zitierte Fassung.
- Ein Lesefehler der Texterkennung, etwa ein falsch gelesener Betrag, kann einen Beleg „nicht
  bestätigt“ machen. Vergleichen Sie dann den erkannten Text mit dem Rahmen im Scan.
