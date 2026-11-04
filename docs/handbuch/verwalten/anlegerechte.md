# Anlegerechte vergeben

> **Wer:** Systemverwaltung, „Administration → Anlegerechte“.

Ein Anlegerecht entscheidet, wer etwas **anlegen** darf. Es öffnet nie einen Inhalt. Vergeben wird
es an eine Person, eine Gruppe oder an „Alle Konten“.

| Anlegerecht | Ab Werk |
|---|---|
| Spaces anlegen | Alle Konten |
| Bibliotheken für Uploads anlegen | Alle Konten |
| Konnektorbibliotheken anlegen | je Quellart und je Zugang: Dateisystem, Webverzeichnis, RSS-Feed, Confluence und S3 für Alle Konten; jede weitere Quellart und jeder neue Zugang nur die Systemverwaltung |
| Interne Gruppen anlegen | niemand außer der Systemverwaltung |
| Prompt-Bibliotheken anlegen | Alle Konten |

1. In der Zeile des Rechts „Ändern“.
2. Bei Konnektorbibliotheken zuerst die Quellart oder den Zugang wählen.
3. Antwort wählen: „Alle Konten“, „Nur bestimmte Gruppen und Personen“ oder „Nur die
   Systemverwaltung“. Darunter steht, was danach gilt.
4. „Speichern“. Den Entzug von „Alle Konten“ bestätigen Sie eigens.

**Einschränken** heißt: „Alle Konten“ entziehen und einer benannten Gruppe geben. OPAA erteilt
dabei zuerst und entzieht danach, sodass zu keinem Zeitpunkt weniger Konten anlegen dürfen als
vorher und nachher. Ein Entzug wirkt sofort; bestehende Spaces und Bibliotheken bleiben.

**Konnektorbibliotheken** sind der erste Kandidat für eine Einschränkung: Sie erreichen Server und
hinterlegte Zugangsdaten. Die Freigabe eines Zugangs öffnet keinen zweiten derselben Quellart, und
die Freigabe einer Quellart keinen ihrer Zugänge. Gebraucht wird sie für alles, was ein neues Ziel
öffnet: anlegen, einem anderen Zugang zuordnen, vom Zugang lösen, die Adresse auf einen anderen
Server stellen. Läufe stoppt sie nicht; das tut nur die Sperre ([Zugänge einrichten](zugaenge.md)).

**Freigabe-Obergrenze.** Eine neue Konnektorbibliothek darf ab Werk an „Alle Konten“ freigegeben
werden. Die Systemverwaltung kann das je Bibliothek im Reiter „Freigaben“ unter „Freigabe an alle
Konten erlaubt“ entziehen; eine bestehende Freigabe an alle endet dann sofort, Rechte an Personen
und Gruppen bleiben. Wer das nicht nach jeder Neuanlage prüfen will, schränkt das Anlegerecht ein.

**Gut zu wissen:**

- Wem ein Anlegerecht fehlt, dem zeigt der Katalog die Art unter „Neuer Katalogeintrag“ gar nicht.
- Vergabe und Entzug stehen im Nachweisprotokoll und mit Zeitraum in der Rechtehistorie.
- Den Stand ab Werk vor der Einführung bewusst bestätigen oder ändern
  ([Einführung vorbereiten](einfuehrung.md)).
