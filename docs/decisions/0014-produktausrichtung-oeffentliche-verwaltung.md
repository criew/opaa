# ADR-0014: Produktausrichtung auf die öffentliche Verwaltung

## Status

Akzeptiert · zuletzt geändert 2026-10-02 · ergänzt durch ADR-0030 (zu Entscheidung 11) und ADR-0039 (zu Entscheidung 13)

## Kontext

OPAA begann als generisches, selbst gehostetes Wissensmanagement mit dem Versprechen der
Austauschbarkeit: jede Komponente konfigurierbar, jeder Modellanbieter gleichwertig, Betrieb im Haus
oder in einer öffentlichen Cloud. Aus der Arbeit an konkreten Anwendungsfällen ergab sich ein anderes
Bild:

- **Der Adressat ist enger und anspruchsvoller.** Die Anforderungen, die OPAA von einem Chat über
  einer Vektorsuche unterscheiden, kommen aus der öffentlichen Verwaltung: Eine Auskunft muss belegbar
  sein, Steuerdaten dürfen nach § 30 AO das Haus nicht verlassen, ein Rollout braucht eine
  Dienstvereinbarung, ein Betreiber muss eine Prüfung nach BSI C5 bestehen. Diese Anforderungen
  bestimmen die Architektur, von der Rechteprüfung zur Abfragezeit bis zu den zulässigen Auswertungen.
- **Das Produkt ist mehr als Wissensmanagement.** Wiederkehrende Abläufe sollen von Agenten erledigt
  werden, wissensgeerdet und abgestuft vom Lesen bis zu schreibenden Aktionen mit Freigabe.
- **Der Engpass ist die Verteilung.** Ohne einen Weg, KI-Kompetenz von wenigen zu allen Beschäftigten
  zu bringen, entsteht Schatten-KI mit Amtsdaten in Verbraucherwerkzeugen.

Der Dokumentationsbestand behauptete an verschiedenen Stellen Verschiedenes; dieser ADR legt die
Ausrichtung fest und führt die daraus folgenden Produktgrenzen als Register (Entscheidungen 6–15).

## Entscheidung

### 1. Nordstern und Säulen

OPAA ist eine **souveräne, quelloffene KI-Plattform für die öffentliche Verwaltung**. Drei Säulen
tragen das Produkt, ohne dass Daten das Haus verlassen: **Wissen** (verstreutes Wissen wird befragbar
und nachweisbar), **Agenten** (wiederkehrende Aufgaben werden automatisiert, immer wissensgeerdet) und
**KI für Teams und Organisation** (geteilte Arbeitsräume, teilbare Agenten, Prompt-Bibliotheken,
zentrale Modellvorgaben).

### 2. Leitprinzipien

Im Zweifel entscheiden zwei Prinzipien: **Belegbarkeit** (jede Aussage ist an ihre Quelle gebunden;
die technische Ausformung ist Entscheidung 14) und **Verteilbarkeit** (KI-Können ist ein benanntes,
teilbares, versionierbares Asset, das über Freigabestufen von der Person bis in die Organisation
wandert). Die Leitfrage lautet nicht mehr „ist das konfigurierbar genug?“, sondern „hilft das der
Belegbarkeit oder der Verteilbarkeit?“.

### 3. Nutzerkreis

Primärer Nutzerkreis ist die interne Verwaltung: Sachbearbeitung, Fachreferate, IT. Ein Assistent für
Bürgerinnen und Bürger ist Ausblick, nicht Fundament (Entscheidung 9).

### 4. Umfang

Der Produktumfang wird in elf Themenbereichen geführt und in vier Phasen ausgeliefert, jede für sich
nutzbar; beides steht in [VISION.md](../VISION.md). Der Abstand zwischen Anspruch und Stand wird im
[Gesamtstand](../fortschritt/gesamtstand.md) offen geführt.

### 5. Was in der Dokumentation nicht vorkommt

In Dokumenten und Marketing-Texten keine Namen von Mitbewerbern zur Positionierung, keine Vergleiche
mit benannten Produkten, keine beteiligten Personen oder Partnerunternehmen, keine Referenzkunden,
keine Preise, keine Aufwands- oder Kostenschätzungen. Abgrenzungen werden mit dem Sachgrund begründet.
Die Ausnahme für Sachbelege steht in Entscheidung 10.

### 6. Vektorspeicher: PostgreSQL mit pgvector, und sonst keiner

PostgreSQL mit pgvector ist der unterstützte Vektorspeicher und der einzige; weitere werden nicht
zugesagt, nicht geprüft und nicht dokumentiert. Ein Wechsel bleibt über die portable Schnittstelle des
Rahmenwerks technisch möglich, ist aber kein Angebot. Grund: Wer im Behördenrechenzentrum betreibt,
wählt die Datenbank in der Regel nicht selbst; jede zugesagte Variante kostet dauerhaft
Integrationstest, Betriebsleitfaden und Unterstützung. Grenzen von pgvector bei sehr großen Beständen
sind ein bekannter Punkt (ADR-0002) und ergäben eine neue Entscheidung. Siehe
[data-indexing-rag.md](../features/data-indexing-rag.md#der-vektorspeicher-postgresql-mit-pgvector-und-sonst-keiner).

### 7. Kein Cloud-Betriebsmodell, kein betreuter Dienst

Der Betrieb außerhalb des eigenen Hauses ist eine Möglichkeit, kein Angebot: OPAA läuft dort, wo eine
Container-Umgebung und PostgreSQL mit pgvector stehen; wo das ist, entscheidet die verantwortliche
Stelle. Anbieternamen und anbieterspezifische Anleitungen kommen nicht vor. Erprobung und Schulung
außerhalb des Hauses bleiben ein benannter Fall ohne echte Daten. Ein vom Projektteam betreuter Dienst
entfällt ersatzlos. Grund: Entscheidend ist der Verantwortliche der Verarbeitung, nicht der Ort; ein
betreuter Dienst machte das Projekt zum Betreiber mit Verfügbarkeits- und Haftungserwartung, die ein
quelloffenes Vorhaben nicht einlöst. Siehe
[deployment-infrastructure.md](../features/deployment-infrastructure.md).

### 8. Modellanbieter: lokal ist die Voreinstellung, ohne technische Sperre

Lokal betriebene Modelle bleiben die Voreinstellung für Chat und Einbettung; der Standardfall
startet ohne zusätzliche Konfiguration. Eine technische Sperre gegen Modellaufrufe außerhalb
festgelegter Netzbereiche wird nicht gebaut; die Zusicherung „keine Daten an ein Modell außerhalb des
Hauses“ ruht auf der Konfiguration, die nachgewiesen und deren Netzweg außerhalb von OPAA abgesichert
wird. Eine spätere Durchsetzung hängt an den zentralen Modellvorgaben (Themenbereich E, Phase 1).
Die Basis-Adresse der openai-kompatiblen Schnittstelle hat keine Voreinstellung, weder die
gemeinsame noch die abgeleiteten für Chat und Einbettung: Wer diesen Anbieter wählt, gibt sie an,
sonst bricht der Start mit einer Meldung ab, die die fehlende Variable benennt
(`OpenAiBaseUrlGuard`). Grund: Eine Sperre ohne die Modellvorgaben, an die sie gehört, wäre eine
zweite Wahrheit neben der Konfiguration; und ein im Haus betriebener „openai-kompatibler“ Server darf
nicht stillschweigend ein Ziel außerhalb des Hauses erben. Siehe
[llm-integration.md](../features/llm-integration.md#was-heute-gilt-und-was-nicht-gebaut).

### 9. Assistent für Bürgerinnen und Bürger bleibt Ausblick

Der Bürger-Scope (Assistent, öffentlich eingebettetes Widget) bleibt Ausblick der Phase 4. Es gibt
dafür keine eigene Notiz, keine Anforderungsliste und nichts wird vorgezogen, auch nicht die
Barrierefreiheit nach BITV (Phase 3). Grund: Der Ausblick steht in VISION.md und
[public-sector.md](../features/public-sector.md#ausblick-assistent-für-bürgerinnen-und-bürger); eine
dritte Fassung erzeugte Pflegeaufwand und Widerspruchsrisiko ohne Nutzen.

### 10. Anbieternamen als Sachbeleg sind zulässig

Unzulässig bleibt die Nennung zur Positionierung: ein Name, der OPAA einordnet, eine Marktlage
beschreibt oder einen Kaufgrund liefert. Zulässig ist die Nennung als nachprüfbarer Sachbeleg für
eine technische Behauptung über ein fremdes System, die den eigenen Entwurf trägt, ebenso als
Arbeitsanweisung an eine interne Rolle; beide bestehenden Stellen tragen einen Hinweis auf diese
Regel. Grund: Anonymisiert wäre ein Beleg nicht nachprüfbar, und ein entschiedener Punkt käme als
neue Idee wieder. Siehe
[MESSAGING.md](../market/MESSAGING.md#was-wir-nicht-sagen).

### 11. Speicher für Originaldokumente: das Dateisystem ist der Vertrag

OPAA liest Quelldokumente gegen konfigurierte Verzeichnisse; ein Netzlaufwerk (SMB/NFS) hängt der
Betrieb dort ein. Eine Abstraktion über mehrere Speicherarten gibt es für die dateibasierten Fälle
nicht. Objektbasierter Speicher ist seit ADR-0030 für die Originalablage der Uploads gebaut
(`opaa.upload.store: s3`), als Weg für den mandantenfähigen Rechenzentrumsbetrieb; der
Objektspeicher-Dienst ist im Compose-Stapel über ein eigenes Profil zuschaltbar, nicht Teil des
Einstiegs. Siehe
[deployment-infrastructure.md](../features/deployment-infrastructure.md#speicher-backends).

### 12. Chat-Kanäle: nur selbst betriebene Team-Chats

Im Zielbild bleiben ausschließlich selbst betriebene Team-Chats (Matrix-basierter Chat-Baustein,
Mattermost, Rocket.Chat; Phase 3). Kanäle über fremd betriebene Verbraucherdienste entfallen
ersatzlos; die REST-API bleibt der offene, aber nicht gepflegte Weg für jeden weiteren Kanal. Grund:
Ein Chat-Kanal ist ein Zugang, der die fragende Person verlässlich auf ein OPAA-Konto abbilden muss,
sonst umgeht er das Rechtemodell; bei einem Verbraucherdienst hängt die Identität an einer
Telefonnummer, und jede Frage ist eine Übermittlung nach außen. Gebaut ist heute kein Chat-Kanal.
Siehe [user-frontends.md](../features/user-frontends.md#anbindung-an-team-chats).

### 13. Umfang des revisionssicheren Protokolls

Die erste Stufe protokolliert alles, was Zugriff verändert oder Verwaltungshandeln ist: Rechte an
Assets, Spaces, Bibliotheken und Gruppen samt Mitgliedschaften, Rollenänderungen, Eigentumsübergänge,
den Verzeichnisabgleich je bewirkter Änderung, Systemeinstellungen und Systemrollen sowie jeden
Zugriff auf die Protokolldaten selbst. Nicht protokolliert werden Abfragen, Antwortinhalte und
Favoriten (ADR-0039 Entscheidung 7): Sie ergeben in der Menge ein Tätigkeits- oder Interessenprofil.
Der Sicherheitsgrad ist einfaches Anfügen ohne Prüfsummenverkettung (Eigentümertrennung in ADR-0015);
der Schutz stützt sich darauf, dass die Anwendung kein Änderungsrecht hat und der direkte
Datenbankzugang betrieblich beschränkt und außerhalb von OPAA nachgewiesen wird. Der Eintrag trägt
die handelnde Person, aber ein personenbezogener Auswertungspfad ist nicht gebaut, nicht nur
abgeschaltet: Keine Oberfläche und keine Schnittstelle filtert, gruppiert oder sortiert nach Person;
Abfragen beginnen an einem Objekt, einem Zeitraum oder einer Ereignisart. Aufbewahrung: Untergrenze
ein Jahr, Voreinstellung drei, Obergrenze zehn Jahre, mit automatischer Löschung. Siehe
[security-and-compliance.md](../features/security-and-compliance.md#revisionssicheres-protokoll).

### 14. Belegbarkeit heißt deterministische Belegvalidierung

Jeder Beleg einer Antwort wird gegen die für diese Antwort abgerufenen Fundstellen geprüft
(Dokument-Kennung, Abschnittsnummer, Dokumentbezeichnung); ein ungültiger Beleg wird gekennzeichnet,
nicht entfernt und nicht als gültig behandelt. Ein Zwangs- und Verweigerungsapparat darüber
(Verweigerung bei fehlendem Beleg, Schalter am Space, inhaltliche Deckungsprüfung) ist entschieden
nicht gebaut: Das Modell sagt selbst, wenn es nichts gefunden hat, fehlende Belege sind im
Belegfenster sichtbar, und der eigentliche Schaden, ein erfundener Beleg, ist durch die Validierung
ausgeschlossen. Siehe [data-indexing-rag.md](../features/data-indexing-rag.md#belegvalidierung).

### 15. Nachweis der Organisationsgrenze

Die Organisationsgrenze wird in drei Schichten gehalten: Anwendung, Datenbank und ein struktureller
Prüflauf gegen das tatsächliche Schema, der verlangt, dass jede Tabelle mit Organisationszugehörigkeit
ihre Verweise auf organisationsgebundene Objekte zusammengesetzt führt. Der Prüflauf schließt die
Fehlerklasse statt des Einzelfalls. Die beidseitige Absicherung auf Datenbankebene (#289) und die
Durchsetzung im Verwaltungspfad (#271) waren Voraussetzung für eine zweite Organisation und sind
gebaut. Siehe [spaces-and-assets.md](../features/spaces-and-assets.md#wie-die-grenze-gehalten-wird).

## Konsequenzen

**Einfacher:** Entscheidungen haben einen Maßstab; Annahmen wie „die Rechteprüfung gehört in die
Vektorsuche“ sind zulässig. Compliance-Anforderungen werden zu Produktmerkmalen (Protokoll,
Nachweisbarkeit, Mitbestimmungsfähigkeit).

**Schwieriger:** Ein Teil des früheren Versprechens trägt nicht mehr (Entscheidungen 6, 7, 12).
Anforderungen wie zentrale Modellvorgaben, SCIM-Lebenszyklus, Leichte Sprache und BITV bleiben offen
sichtbar.

**Restrisiken:** Ohne Prüfsummenverkettung fällt eine Manipulation durch direkten Datenbankzugang
nicht auf (Entscheidung 13). Ohne technische Sperre schützt allein die Konfiguration vor Modellaufrufen
nach außen (Entscheidung 8).

## Verworfene Alternativen

- Generisches Produkt mit austauschbaren Komponenten — für einen Adressaten, der weder Datenbank noch
  Cloud frei wählt, kostet die Wahlfreiheit nur Pflege.
- Zitierzwang mit Verweigerung, Schalter am Space und inhaltlicher Deckungsprüfung — zweiter, selbst
  fehlbarer Prüfweg ohne Nutzen über die deterministische Validierung hinaus.
- Technische Sperre für Modellaufrufe nach außen — ohne die verwalteten Modellvorgaben eine zweite
  Wahrheit neben der Konfiguration.

## Offen

- Prüfsummenverkettung des Protokolls als nächste Stufe (Entscheidung 13).
- Plugin-Architektur für Konnektoren im Verhältnis zu MCP; die Konnektoren sind seit ADR-0038 intern
  steckbar, extern geladene bleiben eine Option ohne Entscheidung.

## Änderungsverlauf

| Datum | Issue/PR | Änderung (eine Zeile) | Entscheidungen |
|---|---|---|---|
| 2026-08-14 | #348 | pgvector als einziger Vektorspeicher | 6 |
| 2026-08-14 | #350 | Cloud-Betrieb als Möglichkeit, betreuter Dienst gestrichen | 7 |
| 2026-08-14 | #353 | Lokale Modelle als Voreinstellung, keine Sperre, keine openai-Basis-Adresse | 8 |
| 2026-08-14 | #357 | Bürgerassistent bleibt Ausblick der Phase 4 | 9 |
| 2026-08-14 | #367 | Anbieternamen als Sachbeleg zulässig | 10 |
| 2026-08-14 | #351 | Dateisystem als Vertrag der Dokumentablage | 11 |
| 2026-08-14 | #352 | Nur selbst betriebene Team-Chats | 12 |
| 2026-08-14 | #355 | Schnitt des revisionssicheren Protokolls | 13 |
| 2026-08-14 | #354 | Zitierzwang in zwei Stufen, Schalter am Space | 14 |
| 2026-08-14 | #356 | Nachweis der Organisationsgrenze | 15 |
| 2026-08-21 | #386 | Verweigerungsapparat verworfen, nur deterministische Belegvalidierung | 14 |
| 2026-09-04 | ADR-0030 | Objektspeicher für die Originalablage der Uploads, Compose-Profil | 11 |
| 2026-10-02 | ADR-0039 | Favoriten werden nicht protokolliert | 13 |
