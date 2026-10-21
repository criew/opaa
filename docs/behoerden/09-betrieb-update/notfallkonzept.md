---
stand: 2026-10-22
gilt-fuer: main
erzeugung: redaktionell
rechtsstand: 2026-10-13
pruefvermerk: nicht rechtlich geprüft
naechste-durchsicht: 2027-04-15
---

# Notfallkonzept für OPAA

> Nicht rechtlich geprüft. Kann als Vorlage dienen; die Verantwortung bleibt bei der Behörde.

## Hinweise zur Verwendung

Diese Vorlage ist das OPAA-spezifische Kapitel im Notfallkonzept einer Behörde, die ihr
Notfallmanagement nach
[BSI-Standard 200-4](https://www.bsi.bund.de/DE/Themen/Unternehmen-und-Organisationen/Standards-und-Zertifizierung/IT-Grundschutz/BSI-Standards/bsi-standards_node.html)
und dem Baustein DER.4 aufbaut. Sie beschreibt, was bei OPAA ausfallen kann, was dann gilt und wie
der Wiederanlauf geht. Die Befehle stehen im Handbuch und werden hier nur verwiesen; sie ändern sich
mit dem Code.

Angaben in eckigen Klammern **[…]** ersetzt die Behörde. Wiederherstellungszeit und
Wiederherstellungspunkt sind **Festlegungen der Behörde**, keine Zusagen des Projekts. Ob sie
erreichbar sind, zeigt erst die [Wiederherstellungsprobe](wiederherstellungsprobe.md).

## Annahmen

Dieses Kapitel gilt nur, solange die folgenden Annahmen zutreffen. Trifft eine nicht zu, ist das
Kapitel anzupassen.

| Nr. | Annahme | Folge, wenn sie nicht zutrifft |
|---|---|---|
| A1 | OPAA läuft mit **[einer / zwei oder mehr] Backend-Instanzen** gegen eine Datenbank; mit einer gibt es keine Hochverfügbarkeit des Backends ([Mehrere Backend-Instanzen](../../handbuch/mehrere-instanzen.md)) | Mit mehreren Instanzen antworten bei S1 die übrigen weiter; die Datenbank bleibt der einzige gemeinsame Baustein. S7 lässt sich ohne Sicherung nur zurücknehmen, wenn die neue Version die vorige in ihrem Versionsfenster zulässt |
| A2 | Die **Datenbank ist die einzige Wahrheit.** Vektorindex, Volltextindex, Rechte, Chats und Nachweisprotokoll liegen in ihr und werden mit ihrer Sicherung gesichert; der Index braucht keine eigene Sicherung | — |
| A3 | Die **Originale hochgeladener Dokumente** liegen nur in der Originalablage und sind ohne deren Sicherung verloren. Inhalte angebundener Quellen lassen sich dort erneut beziehen | Ohne Sicherung der Originalablage ist jeder Upload seit Inbetriebnahme gefährdet |
| A4 | Gesichert wird nach dem Handbuch: **erst die Datenbank, dann die Originale**; wiederhergestellt umgekehrt ([Sicherung und Wiederherstellung](../../handbuch/betrieb.md#sicherung-und-wiederherstellung)) | Datenbankzeilen ohne Original, „Original öffnen“ scheitert |
| A5 | Die beiden **Verschlüsselungsschlüssel** sind getrennt von der Datenbank gesichert und im Notfall greifbar ([Schlüssel und Geheimnisse](../../handbuch/betrieb.md#schlüssel-und-geheimnisse)) | Gespeicherte Zugangsdaten zu Quellen und Modellen sind unlesbar und neu einzutragen; die übrigen Daten bleiben |
| A6 | Wiederhergestellt wird mit **derselben OPAA-Version**, mit der gesichert wurde; Schemaänderungen laufen nur vorwärts ([Aktualisierung](../../handbuch/betrieb.md#aktualisierung)) | Eine ältere Version startet auf einem neueren Schema nicht verlässlich |
| A7 | Das **Embedding-Modell** auf dem Ziel ist dasselbe wie bei der Sicherung, in derselben Dimension ([Modell wechseln](../../handbuch/modelle.md#modell-wechseln)) | Die Vektorsuche ist ausgesetzt, bis der Bestand neu eingebettet ist; bis dahin nur Volltextsuche |
| A8 | Die **Images und der Helm-Chart** der eingesetzten Version sind abrufbar, aus der Registry des Projekts oder aus einem eigenen Spiegel | Ohne Netz und ohne Spiegel ist kein Neuaufbau möglich |
| A9 | Identitätsanbieter, Modelldienste, Mailserver und Quellen haben **eigene Notfallpläne**; dieses Kapitel beschreibt nur ihre Wirkung auf OPAA | — |
| A10 | Die **Konfiguration** (Werte-Datei bzw. `.env.docker`) liegt versioniert außerhalb der Installation | Endpunkte und Bucket der Originalablage sind nach einem Totalausfall nicht mehr ableitbar |
| A11 | [weitere Annahmen des Hauses, etwa Sicherungsintervall, Aufbewahrung, Standort der Sicherungen] | |

## Wiederherstellungszeit und Wiederherstellungspunkt

Die Behörde legt die Werte fest und belegt sie mit der letzten Wiederherstellungsprobe.

| Kennzahl | Festlegung | Herleitung | Belegt durch Probe vom |
|---|---|---|---|
| Wiederherstellungspunkt (RPO) Datenbank | [z. B. 24 Stunden] | mindestens der Abstand zweier Sicherungen, zuzüglich der Dauer einer Sicherung | [Datum] |
| Wiederherstellungspunkt (RPO) Originalablage | [gleich oder kürzer als die Datenbank] | Der Stand der Originale darf nicht älter sein als der der Datenbank (A4) | [Datum] |
| Wiederherstellungszeit (RTO) bis „arbeitsfähig“ | [z. B. 4 Stunden] | gemessene Dauer der Probe, hochgerechnet auf den Bestand, zuzüglich Beschaffung von Hardware und Personal | [Datum] |
| Maximal tolerierbare Ausfallzeit | [aus der Business-Impact-Analyse] | Die Wiederherstellungszeit muss darunter liegen | — |

Die Beispielwerte entsprechen den Zielwerten, auf die das Produkt ausgelegt ist
([Notfallwiederherstellung](../../features/deployment-infrastructure.md#notfallwiederherstellung)).
„Arbeitsfähig“ heißt: Anmeldung, Spaces und belegte Antworten mit Sprung ins Original funktionieren.

**Kürzerer Wiederherstellungspunkt.** Ein Wiederherstellungspunkt unter dem Sicherungsintervall
verlangt eine fortlaufende Sicherung der Datenbank (Archivierung des Transaktionsprotokolls) und eine
Originalablage mit Versionierung oder Replikation. Beides ist Sache des Datenbank- und
Speicherbetriebs; OPAA setzt nur voraus, dass beim Zurückspielen der Stand der Originale nicht älter
ist als der der Datenbank.

## Ausfallszenarien

| Nr. | Szenario | Wirkung | Wiederanlauf | Datenverlust |
|---|---|---|---|---|
| S1 | Backend fällt aus oder hängt | Mit einer Instanz steht der Dienst; die Oberfläche lädt, Anfragen scheitern. Mit mehreren antworten die übrigen Instanzen | Neustart; unter Kubernetes über die Lebendigkeitsprobe von selbst ([Bereitschaft und Lebendigkeit](../../handbuch/betrieb.md#bereitschaft-und-lebendigkeit)). Läufe der ausgefallenen Instanz gibt eine andere oder der Neustart auf, sie lassen sich wiederholen | keiner |
| S2 | Datenbank nicht erreichbar, Daten unversehrt | Dienst steht, die Bereitschaftsprobe meldet `DOWN` | Datenbank wiederherstellen, Backend arbeitet danach weiter | keiner |
| S3 | Datenbank verloren oder beschädigt | Dienst steht | Wiederherstellung aus der Sicherung mit Nacharbeit, siehe [Wiederherstellung](#wiederherstellung) | alles seit der letzten Sicherung: Uploads, Chats, Rechteänderungen, Protokolleinträge |
| S4 | Originalablage verloren | Suche und Antworten laufen weiter, „Original öffnen“ und neue Uploads scheitern | Originale aus der Sicherung zurückspielen; ist sie älter als die Datenbank, fehlen die Originale der Zwischenzeit und sind erneut hochzuladen | Originale seit der letzten Sicherung der Ablage |
| S5 | Installation vollständig verloren (Host oder Cluster) | Dienst steht | Neuaufbau nach [Wiederherstellung auf einem neuen Cluster](../../handbuch/kubernetes.md#sicherung-und-wiederherstellung) bzw. [Docker Compose](../../handbuch/docker-compose.md#sicherung-und-wiederherstellung) | wie S3 und S4 |
| S6 | Verschlüsselungsschlüssel verloren | Gespeicherte Zugangsdaten unlesbar; Läufe von Quellen mit Anmeldung scheitern | Zugangsdaten an Bibliotheken, Zugängen und Modellen neu eintragen ([Zugangsdaten-Verschlüsselung](../../handbuch/betrieb.md#zugangsdaten-verschlüsselung)) | nur die Zugangsdaten |
| S7 | Update fehlgeschlagen | Dienst steht oder läuft fehlerhaft | Rückweg über die Sicherung von vor dem Update; ohne sie nur, wenn die neue Version die vorige in ihrem Versionsfenster zulässt ([Aktualisierung und Rückweg](../../handbuch/kubernetes.md#aktualisierung-und-rückweg), [Update ohne Ausfall](../../handbuch/mehrere-instanzen.md#update-ohne-ausfall)) | über die Sicherung alles seit dieser Sicherung, über das Versionsfenster keiner |
| S8 | Identitätsanbieter fällt aus | Anmeldung über ihn unmöglich; lokale Konten melden sich weiter an | Ausfall beim Identitätsanbieter beheben; kommt niemand mehr herein: [Notfallprozedur](../../handbuch/authentifizierung.md#notfallprozedur-wieder-hereinkommen) | keiner |
| S9 | Modellanbieter fällt aus | siehe [Ausfall des Modellanbieters](#ausfall-des-modellanbieters) | | keiner |
| S10 | Verdacht auf Kompromittierung | nach Vorfallbehandlung des Hauses | Neuaufbau aus den Images der Version und einer Sicherung von vor dem Vorfall; alle Geheimnisse wechseln, gespeicherte Zugangsdaten danach neu eintragen (S6); Meldepflichten prüfen ([NIS2](../06-informationssicherheit/nis2-bundesverwaltung.md)) | je nach Sicherung |
| S11 | [weitere Szenarien des Hauses] | | | |

## Ausfall des Modellanbieters

OPAA nutzt bis zu vier Modellrollen ([Vier Modellrollen](../../handbuch/modelle.md#vier-modellrollen)).
Ihr Ausfall wirkt unterschiedlich; Anmeldung, Verwaltung und der Zugriff auf Dokumente arbeiten in
jedem Fall weiter, weil kein Modell die Bereitschaft des Backends bestimmt
([Bereitschaft und Lebendigkeit](../../handbuch/betrieb.md#bereitschaft-und-lebendigkeit)).

| Rolle | Wirkung des Ausfalls | Vorsorge | Wiederanlauf |
|---|---|---|---|
| Chat | Fragen im Chat scheitern | ein **Ersatzmodell** vorab in der Modellverwaltung anlegen und testen, nicht aktivieren ([Chat-Modelle in der Oberfläche anlegen](../../handbuch/modelle.md#chat-modelle-in-der-oberfläche-anlegen)) | Ersatzmodell aktivieren; das wirkt ohne Neustart. Nach dem Ausfall zurückschalten |
| Einbettung | Fragen scheitern, weil die Suche die Frage nicht einbetten kann; neue Dokumente enden als fehlgeschlagen | einen **Ersatz-Endpunkt mit demselben Modell** vorhalten; ein Wechsel nur des Endpunkts gilt nicht als Modellwechsel | Endpunkt umstellen und Backend neu starten; Dokumente, die in der Zwischenzeit fehlgeschlagen sind, erneut verarbeiten lassen, bei Quellen mit dem nächsten Lauf. Ein **anderes** Modell verlangt eine Neueinbettung des Bestands, bis dahin nur Volltextsuche ([Modell wechseln](../../handbuch/modelle.md#modell-wechseln)) |
| Reranking | Suche läuft ohne Reranking weiter, die Reihenfolge der Treffer ist schlechter | keine | von selbst, sobald der Endpunkt antwortet |
| Transkription | Spracheingabe nicht verfügbar | keine | von selbst |

**Ein Ersatzmodell bei einem anderen Anbieter ist ein neuer Datenfluss.** Fragen und
Textausschnitte gehen dann an einen anderen Empfänger. Bevor ein solches Ersatzmodell aktiviert
werden darf, ist es in den Datenschutzunterlagen (Verzeichnis von Verarbeitungstätigkeiten,
gegebenenfalls Auftragsverarbeitung und Datenschutz-Folgenabschätzung) und in der
Dienstvereinbarung abgebildet. Die Datenflüsse der eingesetzten Version stehen in
{{faktenblatt:datenfluesse}}. Ohne diese Vorarbeit ist die Vorsorge für den Chat: Ausfall abwarten.

**Erkennen.** Den Zustand der Modelle zeigen die Seite „Suche & Indexierung“, die Gesundheitsgruppen
des Backends und unter Kubernetes der Alarm für eine hohe Fehlerquote
([Fehlerbilder](../../handbuch/modelle.md#fehlerbilder), [Alarme](../../handbuch/kubernetes.md#alarme)).
Die Gesundheitsgruppe des Chat-Modells ruft das Modell nicht auf; ein Ausfall des Chat-Modells
zeigt sich an scheiternden Fragen und an der Fehlerquote.

## Wiederherstellung

Datenbank, Vektorindex und Originalablage passen nur zusammen, wenn sie aus zusammengehörigen
Sicherungen stammen. Für OPAA gilt:

1. **Vektorindex und Volltextindex** liegen in der Datenbank und kommen mit ihr zurück. Sie passen
   immer zum Dokumentbestand derselben Sicherung (A2), solange das Embedding-Modell gleich bleibt
   (A7).
2. **Originale vor der Datenbank zurückspielen**, und zwar einen Stand, der nicht älter ist als die
   Datenbanksicherung. Ein jüngerer Stand ist unschädlich: Originale ohne Datenbankzeile sind
   verwaist und lassen sich gezielt aufräumen
   ([Verwaiste Originale aufräumen](../../handbuch/originalablage.md#verwaiste-originale-aufräumen)).
   Ein älterer Stand hinterlässt Datenbankzeilen ohne Original.
3. **Datenbank zurückspielen**, mit denselben Voraussetzungen wie bei einer Neuinstallation
   ([Voraussetzungen einer eigenen PostgreSQL](../../handbuch/datenbank.md#voraussetzungen-einer-eigenen-postgresql)).
4. **Signaturgeheimnis ersetzen, bevor das Backend startet**, und die übrige Nacharbeit erledigen:
   Sperren und Rücksetzungen der Zwischenzeit erneut vornehmen, bei eingeschaltetem Zugriff externer KI-Werkzeuge dessen Prüfliste
   abarbeiten ([Nacharbeit nach einer Rücksicherung der Datenbank](../../handbuch/authentifizierung.md#nacharbeit-nach-einer-rücksicherung-der-datenbank)).
   Die Verschlüsselungsschlüssel bleiben dieselben (A5).
5. **Backend starten und prüfen** nach den Prüfkriterien der
   [Wiederherstellungsprobe](wiederherstellungsprobe.md#prüfkriterien).
6. **Manuelle Metadaten abgleichen**, nur wenn das Nachweisprotokoll jünger ist als die
   Datenbanksicherung ([Manuelle Metadatenwerte nach einem Restore abgleichen](../../features/deployment-infrastructure.md#manuelle-metadatenwerte-nach-einem-restore-abgleichen)).
7. **Nutzende informieren:** Alle lokalen Sitzungen sind beendet, Arbeit seit dem
   Wiederherstellungspunkt ist verloren.

Die Befehle stehen je Betriebsweg im Handbuch:
[Docker Compose](../../handbuch/docker-compose.md#sicherung-und-wiederherstellung) und
[Kubernetes](../../handbuch/kubernetes.md#sicherung-und-wiederherstellung).

## Zuständigkeiten und Erreichbarkeit

| Rolle | Name, Stelle | Erreichbar | Vertretung |
|---|---|---|---|
| Notfallverantwortliche Stelle für OPAA | [ ] | [ ] | [ ] |
| Betrieb der Plattform (Compose-Host oder Cluster) | [ ] | [ ] | [ ] |
| Datenbankbetrieb | [ ] | [ ] | [ ] |
| Speicherbetrieb (Originalablage) | [ ] | [ ] | [ ] |
| Verwahrung der Geheimnisse (Verschlüsselungsschlüssel, Datenbankkonto) | [ ] | [ ] | [ ] |
| Systemverwaltung in OPAA | [ ] | [ ] | [ ] |
| Modelldienste | [ ] | [ ] | [ ] |
| Informationssicherheitsbeauftragte Person | [ ] | [ ] | [ ] |
| Datenschutzbeauftragte Person (bei Datenverlust oder neuem Modellanbieter) | [ ] | [ ] | [ ] |

## Fortschreibung

Das Kapitel wird überprüft nach jeder Wiederherstellungsprobe, nach jedem Update, dessen
Release-Notizen Vorbereitungsschritte nennen, und nach jeder Änderung am Aufbau der Installation
(neuer Betriebsweg, neue Originalablage, neuer Modellanbieter).
