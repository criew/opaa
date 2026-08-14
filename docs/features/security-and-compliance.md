# Sicherheit, Nachweis & Prüfbarkeit

> **Status: Entwurf — die Leitplanken stehen, der Schnitt des Protokolls ist offen.**
>
> **Phasenlage:** Phase 1. Das revisionssichere Protokoll, die Vollständigkeit nach DSGVO, sichere
> Voreinstellungen und die Mitbestimmungsfähigkeit gehören zum Fundament — ohne sie ergibt ein Start in
> einer Behörde keinen Sinn. Der geordnete Entwicklungsprozess mit Stückliste und signierten Builds läuft
> parallel und begleitend; die unabhängige Prüfung als Nachweisstufe ist eine Reifegradfrage, keine
> Funktion.

> **Abgrenzung:** Das Rechtemodell steht in
> [Spaces, Assets & Zugangskontrolle](./spaces-and-assets.md), Anmeldung, Kontenlebenszyklus und
> Systemverwaltung in [Identität, Rechte & Mandanten](./access-control.md), die aggregierte Auswertung in
> [Monitoring, Kosten & Governance](./monitoring-and-governance.md). Dieses Dokument beschreibt, wie
> belegt wird, dass all das gewirkt hat — und wo die Grenzen dieses Belegens liegen.

## Motivation

In der Verwaltung genügt es nicht, das Richtige zu tun. Es muss Jahre später **belegbar** sein, und zwar
gegenüber jemandem, der weder beim Vorgang dabei war noch dem Haus wohlgesonnen sein muss: der internen
Revision, dem Rechnungshof, der Aufsichtsbehörde, der Datenschutzaufsicht, einer Betroffenen mit einem
Auskunftsersuchen.

Daraus folgen drei Anforderungen, die einander gegenläufig sind und deshalb ausdrücklich austariert
werden müssen:

1. **Es muss genug protokolliert werden**, um die Prüferfrage zu beantworten — auch die schwierige
   Negativfrage, worauf jemand gerade **keinen** Zugriff hatte.
2. **Es darf nicht so viel erhoben werden**, dass daraus ein Tätigkeits- und Leistungsprofil der
   Beschäftigten entsteht. Sonst scheitert die Einführung an der Mitbestimmung, und zwar zu Recht.
3. **Es muss löschbar sein**, ohne dass die Unveränderlichkeit des Protokolls Schaden nimmt.

Ein Produkt, das nur die erste Anforderung erfüllt, ist prüfbar und nicht einführbar. Eines, das nur die
zweite erfüllt, ist einführbar und nicht betreibbar. OPAA muss beides zugleich sein.

---

## Überblick

1. **Revisionssicheres Protokoll** — wer, wann, was; Verwaltungsaktionen und Agentenaktionen; der
   Zugriff auf Protokolldaten erzeugt selbst einen Eintrag; anbindbar an ein zentrales
   Sicherheitsmonitoring.
2. **Historisierung der Rechte** — die Rechtemenge zu einem beliebigen Stichtag ist rekonstruierbar,
   statt sie bei jeder Abfrage mitzuschreiben.
3. **Vollständigkeit nach DSGVO** — Löschung und Export, beides vollständig und beides ohne
   Sonderweg.
4. **Sichere Voreinstellungen und Produkthärtung** — der Auslieferungszustand ist der sichere Zustand.
5. **Geordneter Entwicklungsprozess** — Software-Stückliste, signierte und reproduzierbare Builds,
   unabhängige Prüfung als Nachweisstufe.
6. **C5-Fähigkeit statt Zertifizierung** — OPAA wird nie selbst zertifiziert; das Ziel ist, dass ein
   Betreiber die Prüfung mit OPAA im Prüfumfang besteht.
7. **Mitbestimmungsfähigkeit** — die Dienstvereinbarung wird zu einer Konfigurationsaufgabe statt zu
   einem Projektrisiko.

Die Punkte 1 bis 5 sind Bauaufgaben. Punkt 6 ist eine Dokumentationsaufgabe, die aus ihnen folgt.
Punkt 7 ist eine **Entwurfsentscheidung**, die begrenzt, was die Punkte 1 bis 5 tun dürfen — und die
deshalb im Zweifel gewinnt.

---

## Revisionssicheres Protokoll

### Der Schnitt ist noch nicht entschieden

Welche Ereignisse in welcher Tiefe protokolliert werden, was „revisionssicher" im Einzelnen bedeutet und
welcher Schnitt die erste umsetzbare Stufe ist, wird **nicht in dieser Spezifikation entschieden**. Die
Frage ist als eigene Arbeit erfasst und soll in einer Entscheidungsvorlage — möglichst als ADR —
beantwortet werden. Dieses Kapitel beschreibt die Leitplanken, innerhalb derer diese Entscheidung fallen
muss, und nicht ihr Ergebnis.

Zwei Anforderungen sind dabei gegeneinander abzuwägen: die Prüfbarkeit gegen die Festlegung, keinen
personenbezogenen Auswertungspfad zu bauen. Beide sind verbindlich, und die Vorlage muss zeigen, wie sie
zusammengehen.

### Der Protokollsatz

Der heutige Arbeitsstand des Standardsatzes:

```json
{
  "timestamp": "2026-02-16T14:30:15Z",
  "user_id": "user-123",
  "organization_id": "org-1",
  "action": "search",
  "space_id": "space-veranlagung",
  "libraries_searched": ["lib-rechtsquellen"],
  "results_count": 5,
  "documents_accessed": ["doc-1", "doc-2"],
  "result": "success"
}
```

**Die Netzadresse ist nicht Teil des Standardsatzes.** Sie unterscheidet Dienststelle von Heimarbeit und
ist damit ein Anwesenheitsmerkmal. Sie kann für Sicherheitszwecke ausdrücklich eingeschaltet werden; dann
ist die Einschaltung zu begründen, und das Feld bleibt aus Berichten und Exporten ausgeschlossen. Ob eine
C5-Prüfung das Feld zwingend verlangt, ist offen; sollte das so sein, ist es schriftlich zu begründen.

### Besonders protokollpflichtige Handlungen

Handlungen, an denen sich Rechte, Reichweiten oder die Beobachtbarkeit ändern:

- Rechtevergabe und -entzug an Assets, einschließlich Mitfreigaben aus der Freigabekette
- **Ablegen** eines Chats oder Artefakts im Space und **Zurückziehen** durch Ersteller oder Space-Admin
- Aufnahme und Entfernen von Space-Mitgliedern; die Aufnahme **externer** Personen in einen Space mit
  abgelegten Inhalten zusätzlich mit ausdrücklicher Bestätigung
- Bereitstellung einer Bibliothek in einem Space, dessen Mitglieder nicht sämtlich Lesezugriff haben
- Änderung der Freigabestufe oder Auffindbarkeit eines Assets
- Übernahme von Assets ohne Zuständigkeit und Eigentümerwechsel
- Änderungen an Modell-Policies
- **Änderungen an Governance-Einstellungen** — Aufbewahrungsfristen, Aggregation, Statistik,
  Protokollkonfiguration. Ohne diesen Punkt bleibt eine spätere Abweichung von der Dienstvereinbarung
  unbemerkt; die Änderung wird zusätzlich angezeigt
- Jede bewirkte Rechteänderung aus einem Verzeichnissynchronisationslauf — je Änderung, nicht je Lauf
- Deaktivierung eines Kontos, erzwungene Neuanmeldung, Ausstellung und Widerruf von API-Tokens

### Verwaltungsaktionen und Agentenaktionen

Zwei Kategorien, die über die gewöhnliche Nutzerhandlung hinausgehen und deshalb eigens genannt werden:

- **Verwaltungsaktionen.** Alles, was die Systemverwaltung tut, ist protokollpflichtig — gerade weil
  System-Admins nicht automatisch leseberechtigt sind, muss jeder Übernahme- und Verwaltungsakt sichtbar
  sein. Ein Admin, der ein Asset einer neuen Zuständigkeit zuweist, hinterlässt eine Spur, die er selbst
  nicht entfernen kann.
- **Agentenaktionen.** Ein Agent handelt **immer mit den Rechten der aufrufenden Person**. Der
  Protokolleintrag hält deshalb beides fest: die aufrufende Person und den ausführenden Agenten in seiner
  konkreten Version. Bei schreibenden Aktionen kommt der Freigabeschritt hinzu — wer freigegeben hat,
  wann, und was genau freigegeben wurde. Ein automatisierter Vorgang ohne benennbaren Menschen dahinter
  ist in der Verwaltung nicht zurechenbar und damit nicht zulässig.

### Der Zugriff auf Protokolldaten erzeugt selbst einen Eintrag

Wer Protokolldaten liest, exportiert oder auswertet, erzeugt damit einen eigenen Protokolleintrag — mit
Person, Zeitpunkt, Anlass und Umfang der Abfrage. Der Eintrag ist für die auswertende Stelle nicht
unterdrückbar.

**Protokollierter Zugriff ist aber kein begrenzter Zugriff; beides ist nötig.** Deshalb zusätzlich:
benannter Personenkreis, dokumentierter Anlass und die technisch durchgesetzte Trennung der
Auswertungswege für Revision und Dienststellenleitung.

### Aufbewahrung

- **Frist mit Ober- und Untergrenze**, konfigurierbar, mit automatischer Löschung nach Ablauf. Eine reine
  Untergrenze („mindestens ein Jahr") ist keine Regelung, sondern eine unbefristete Speicherung mit
  Mindestdauer.
- Die Protokollfrist muss **mindestens so lang** gewählt werden wie die Aufbewahrung der Inhalte, auf die
  sie sich bezieht. Sonst existiert ein Chatverlauf noch, aber es ist nicht mehr belegbar, wer ihn wann
  gelesen hat. Das Produkt warnt bei einer inkonsistenten Einstellung. Die konkrete Dauer folgt aus
  Fachrecht und Aktenordnung der einführenden Stelle.

### Unveränderlichkeit und Löschrecht

Ein nur anfügendes Protokoll und ein nachträgliches Schwärzen schließen einander aus. Der Widerspruch wird
zugunsten der Unveränderlichkeit aufgelöst:

**Der Personenbezug wird ab dem Schreibzeitpunkt pseudonymisiert.** Das Protokoll enthält eine Kennung,
die Zuordnung zur Person liegt in einer getrennt gehaltenen Tabelle. Beim Löschen eines Kontos entfällt
dieser Eintrag — das Protokoll bleibt unverändert und ist danach nicht mehr auf eine Person zurückführbar.
Es wird nichts nachträglich verändert und nichts überschrieben.

### Anbindung an ein zentrales Sicherheitsmonitoring

Behörden betreiben ihr Sicherheitsmonitoring in aller Regel zentral, oft für viele Fachverfahren
zusammen. OPAA liefert seine sicherheitsrelevanten Ereignisse deshalb in einem gängigen Format an ein
solches System aus — fehlgeschlagene Anmeldungen, abgewiesene Verbindungsversuche aus unzulässigen
Netzbereichen, Rechteänderungen, Verwaltungsaktionen, Zugriffe auf Protokolldaten.

**Der Export ist keine Umgehung.** Was hinausgeht, unterliegt denselben Zweck-, Zugriffs- und
Sparsamkeitsregeln wie das Protokoll selbst. Insbesondere führt der Weg über das Sicherheitsmonitoring
nicht an der Festlegung vorbei, dass es keinen personenbezogenen Auswertungspfad gibt: Was OPAA nicht
nach Person gruppiert, liefert es auch nicht so aus. Welche Ereignisklassen ausgeleitet werden, ist Teil
der dokumentierten Zweckbindung und damit Gegenstand der Dienstvereinbarung.

---

## Nachweisbarkeit: Historisierung von Rechten

Die Rechtemenge eines Nutzers ist eine **berechnete Größe** aus drei Quellen — direkte Grants,
Gruppengrants und organisationsweite Freigaben —, von denen sich eine, die Gruppenmitgliedschaft, per
Verzeichnissynchronisation ändert. Rechte, die aus mehreren Quellen zusammengerechnet werden, muss man
erklären können.

Die Prüferfrage lautet nicht „was hat Frau K. getan", sondern: *„Worauf hatte Frau K. am 3. März Zugriff,
und belegen Sie, dass die Bibliothek `Personalvorgänge` nicht dazugehörte."* Die **Negativfrage** ist die
schwierigere, und ein Ereignisprotokoll kann sie nicht beantworten, solange es Lücken haben kann.

Deshalb werden **alle drei Quellen historisiert**: Grants, Gruppenmitgliedschaften **und die
Reichweitenfelder am Asset** (`visibility`, `listed`). Zu jedem Zeitpunkt ist rekonstruierbar, wer welche
Rechte hatte, seit wann und aufgrund welchen Vorgangs.

Die dritte Quelle mitzunehmen ist nicht optional: Eine Bibliothek, die vom 1. bis zum 10. März
organisationsweit freigegeben war, verschaffte in dieser Zeit Zugriff, ohne dass je ein Grant existierte.
Wäre nur protokolliert statt historisiert, ruhte ein Drittel der Rekonstruktion auf genau der
lückenanfälligen Quelle, die dieses Kapitel verwirft — und die Antwort auf die Prüferfrage fiele falsch
aus, und zwar in die gefährliche Richtung. Es sind zwei Felder an wenigen hundert Objekten.

**Aufbewahrung und Löschschicksal der Historie** folgen derselben Logik wie das Protokoll: Sie unterliegt
einer Höchstdauer, und der Personenbezug ist ab dem Schreibzeitpunkt pseudonymisiert. Beim Löschen eines
Kontos entfällt die Zuordnung; die Historie selbst bleibt unverändert bestehen. Ohne diese Festlegung
entstünden zwei unvereinbare Aussagen — entweder wäre die Zusage „danach nicht mehr auf eine Person
zurückführbar" nicht haltbar, oder für ausgeschiedene Personen wäre nichts mehr belegbar, obwohl
Prüfungen gerade sie häufig betreffen.

**Regressionsprüfung gegen Filterfehler:** Enthält `libraries_searched` einer Abfrage eine Bibliothek, die
nach der Historie zu diesem Zeitpunkt für den Nutzer nicht lesbar war, ist das ein beweisbarer
Durchsetzungsfehler. Der Abgleich ist billig und wird als Prüfung geführt.

Das ist bewusst **anders gelöst als über eine Protokollzeile je Abfrage**: Die Rechtemenge bei jeder Suche
mitzuschreiben würde das Protokoll um eine erhebliche Menge personenbezogener Daten erweitern — genau das,
was die Datensparsamkeit vermeiden soll — und wäre trotzdem lückenanfällig. Die Historie liefert dieselbe
Aussage mit weniger Daten.

**Folge für die Berichte:** Einen Bericht „abgelehnte Zugriffe" kann es nicht geben. Weil der Filter Teil
der Vektorsuche ist, existiert kein abgelehnter Zugriff, den man protokollieren könnte — unberechtigte
Chunks werden nie geladen. Was es gibt, ist der Nachweis über die Rechtehistorie und über den bei jeder
Abfrage protokollierten **angewandten Suchbereich**.

---

## Vollständigkeit nach DSGVO: Löschung und Export

### Löschung eines Benutzerkontos

```
1. Zugang sofort deaktivieren — nie durch offene Eigentumsfragen aufgehalten
2. Assets in den Zustand "Nachfolge offen" versetzen: nutzbar, aber Reichweite eingefroren
3. Nutzer aus allen Spaces und Gruppen entfernen
4. Konto, Sitzungen und Tokens löschen
5. Pseudonymzuordnung entfernen — das Protokoll bleibt unverändert bestehen
```

Entwürfe des Nutzers folgen den Regeln des persönlichen Space. Abgelegte Chats und Artefakte in geteilten
Spaces sind Arbeitsergebnisse der Organisation und verschwinden nicht mit dem Konto ihres Erstellers,
werden aber nach Ablauf der Aufbewahrungsfrist gelöscht.

Dokumente werden über ihre Wissensbibliothek gelöscht. Für konnektor-indizierte Dokumente gilt weiterhin
der Ausschluss-Mechanismus, weil sie beim nächsten Lauf sonst erneut aufgenommen würden.

**Löschung heißt Löschung in allen abgeleiteten Beständen.** Ein gelöschtes Dokument verschwindet mit
seinen Chunks, Einbettungen und Zwischenständen; ein Rest im Index wäre der Unterschied zwischen einer
erfüllten und einer behaupteten Löschpflicht.

### Export und Auskunft

Drei Exporte, die auseinanderzuhalten sind, weil sie verschiedene Adressaten haben:

| Export | Für wen | Inhalt |
|---|---|---|
| **Selbstauskunft** | die betroffene Person selbst | alle zu ihr gespeicherten Daten, vollständig und maschinenlesbar — **nicht delegierbar**, für niemanden sonst auslösbar |
| **Auskunft über die Datenerhebung** | Datenschutzbeauftragte und Personalvertretung | welche personenbeziehbaren Felder erhoben werden, in welcher Granularität, zu welchem Zweck und wie lange sie liegen — vor dem Rollout einmal vollständig vorlegbar |
| **Bestandsexport** | die Organisation | Wissensbestände, Assets und Konfiguration in offenen Formaten, damit ein Wechsel des Betreibers oder des Produkts möglich bleibt |

Die Selbstauskunft ist der einzige Weg, auf dem personenbezogene Daten gebündelt herausgehen — und sie
geht ausschließlich an die betroffene Person, weder über eine Vertretungsfunktion noch durch einen Admin
„im Auftrag" noch in ein fremdes Postfach.

### Berichte

- **Zugangsbericht:** wer hat wann worauf zugegriffen — mit der Einschränkung, dass er nicht nach Person
  gruppiert oder sortiert werden kann
- **Rechteänderungen:** wer hat wem was freigegeben, mit Rechtestand zum Stichtag aus der Historie
- **Zugriff auf geschützte Bestände**
- **Testzugang für die Personalvertretung** vor dem Rollout, damit sie die Zusagen selbst nachvollziehen
  kann statt sie zu glauben

---

## Sichere Voreinstellungen und Produkthärtung

Der Auslieferungszustand ist der sichere Zustand. Wer OPAA aufsetzt, soll nichts abschalten müssen, um
sicher zu sein — er soll etwas einschalten müssen, um es nicht zu sein, und das begründen.

- **Keine Vorgabekennwörter, keine Vorgabekonten.** Der erste Verwaltungszugang entsteht bei der
  Einrichtung, nicht im Auslieferungszustand.
- **Verschlüsselung auf dem Transportweg durchgehend**, auch zwischen den Bestandteilen des Systems.
  Ruhende Daten liegen im verschlüsselten Speicher des Betreibers; Schlüsselverwaltung und -wechsel sind
  dokumentiert und liegen beim Betreiber.
- **Geschlossene Voreinstellung nach außen.** Ohne ausdrückliche Freigabe geht keine Anfrage an ein
  externes Modell und an keinen externen Dienst. Der Betrieb ohne Netzanbindung ist das vorgesehene
  Szenario, nicht die Ausnahme.
- **Getrennte Ausführung.** Was Dokumente verarbeitet oder Werkzeuge ausführt, läuft in einer eigenen,
  eingeschränkten Umgebung ohne Zugriff auf Netz und Datenbestand außerhalb des Auftrags.
- **Fehlermeldungen verraten nichts.** Nutzerseitige Meldungen nennen keine internen Pfade, Kennungen
  oder Bestandteile; die Einzelheiten gehen ins Protokoll.
- **Grenzen sind voreingestellt**, nicht optional — Größen von Uploads, Anzahl gleichzeitiger Anfragen,
  Verbrauchsgrenzen je Nutzer (siehe [Monitoring, Kosten & Governance](./monitoring-and-governance.md)).
- **Härtungs- und Konfigurationsleitfäden** sind Teil des Produkts, nicht Beratungsleistung: eine
  dokumentierte Referenzkonfiguration, eine Liste der sicherheitsrelevanten Einstellungen mit ihrer
  Voreinstellung und eine Prüfliste für die Inbetriebnahme.
- **Das Produkt prüft sich selbst.** Eine Betriebsansicht zeigt an, wo die laufende Konfiguration von der
  gehärteten Referenz abweicht. Eine Abweichung ist erlaubt; unbemerkt zu bleiben ist sie nicht.

---

## Geordneter Entwicklungsprozess: Stückliste und signierte Builds

Ein Betreiber kann für eine Software nur einstehen, wenn er weiß, woraus sie besteht und dass das
Ausgelieferte dem Geprüften entspricht.

- **Software-Stückliste.** Für jede Veröffentlichung wird maschinenlesbar mitgeliefert, aus welchen
  Bestandteilen und Versionen sie besteht. Das ist die Voraussetzung dafür, dass eine neu bekannt
  gewordene Schwachstelle im Haus binnen Stunden bewertet werden kann statt binnen Wochen.
- **Signierte Artefakte.** Veröffentlichte Stände und Container-Abbilder sind signiert und ihre Herkunft
  ist überprüfbar. Der Betreiber kann feststellen, dass er das ausführt, was veröffentlicht wurde.
- **Reproduzierbare Builds.** Derselbe Quellstand ergibt dasselbe Artefakt. Damit ist die Signatur mehr
  als eine Zusage: Sie ist unabhängig nachvollziehbar — und für einen quelloffenen Kern ist genau das das
  Argument, warum Offenheit hier Sicherheit erzeugt und nicht Angriffsfläche.
- **Geordneter Änderungsprozess.** Änderungen laufen über nachvollziehbare Vorgänge mit Überprüfung durch
  eine zweite Person, automatisierte Tests und ein festes Vorgehen bei Sicherheitskorrekturen. Für die
  Meldung von Schwachstellen gibt es einen benannten Weg und eine zugesagte Reaktion.
- **Unabhängige Prüfung als Nachweisstufe.** Eine externe Sicherheitsuntersuchung des Produkts und seines
  Quelltextes ist die Stufe, die aus einer Selbstauskunft einen Nachweis macht. Sie ist keine
  Produktfunktion, sondern ein Reifegrad; ihr Bericht gehört in das Nachweispaket. Wann und in welchem
  Umfang sie erfolgt, entscheidet das Projekt gesondert.

---

## C5-Fähigkeit statt Zertifizierung

**OPAA wird nie selbst „C5-zertifiziert".** Das ist keine Zurückhaltung, sondern eine Eigenschaft des
Kriterienkatalogs: Der C5-Katalog des BSI prüft den **Betrieb** eines Dienstes — Organisation, Personal,
Räumlichkeiten, Verfahren —, nicht ein Stück Software. Ein Softwarehersteller kann diese Prüfung schon
deshalb nicht bestehen, weil er den geprüften Gegenstand gar nicht betreibt. Wer mit einem
„C5-zertifizierten Produkt" wirbt, beschreibt etwas, das es nicht gibt.

Das Produktziel ist deshalb die **C5-Fähigkeit**: OPAA ist so gebaut und dokumentiert, dass ein Betreiber
die Prüfung **mit OPAA im Prüfumfang** besteht. Was dafür nötig ist, sind die vier Blöcke dieses
Dokuments — revisionssicheres Protokoll, Vollständigkeit nach DSGVO mit Löschung und Export, sichere
Voreinstellungen und Produkthärtung, geordneter Entwicklungsprozess mit Stückliste und signierten Builds.

### Welche Prüfbereiche das Produkt betreffen

| | Prüfbereiche |
|---|---|
| **Softwarerelevant** — OPAA muss liefern, was der Betreiber hier vorlegt | Identitäts- und Berechtigungsmanagement (IDM) · Regelbetrieb (OPS) · Produktsicherheit (PSS) · Kryptographie und Schlüsselmanagement (CRY) · Portabilität und Interoperabilität (PI) · Compliance (COM) · Beschaffung, Entwicklung und Änderung von Informationssystemen (DEV) · Kommunikationssicherheit (COS) · Umgang mit Sicherheitsvorfällen (SIM) · Kontinuität des Betriebs (BCM) |
| **Rein betreiberseitig** — OPAA kann dazu nichts beitragen | Physische Sicherheit (PS) · Personal (HR) · Organisation der Informationssicherheit (OIS) · Sicherheitsrichtlinien und Arbeitsanweisungen (SP) · Steuerung und Überwachung von Dienstleistern (SSO) · Umgang mit Ermittlungsanfragen staatlicher Stellen (INQ) · Verwaltung der Werte (AM) |

Diese Zuordnung ist eine Einschätzung aus Produktsicht und ersetzt keine Prüfungsplanung; maßgeblich ist
der Prüfumfang, den Betreiber und Prüfstelle vereinbaren.

### Zwei Wege — die Behörde wählt

Beide Wege werden **gleichwertig** unterstützt. Die Nachweise sind dieselben, nur der Adressat
unterscheidet sich.

**Selbst betreiben.** Das Haus betreibt OPAA im eigenen Rechenzentrum und bringt es in seine eigene
Prüfung ein. Dafür stellt das Projekt ein **Nachweispaket** bereit:

- **Verantwortungsmatrix** — welche Anforderung erfüllt das Produkt, welche der Betreiber, welche beide
  gemeinsam. Ohne sie beginnt jede Prüfung mit derselben Klärungsrunde.
- **Härtungs- und Konfigurationsleitfäden** mit Referenzkonfiguration und Prüfliste
- **Software-Stückliste** je Veröffentlichung
- **Signierte und reproduzierbare Builds** mit überprüfbarer Herkunft
- **Bericht der unabhängigen Prüfung**, sobald vorhanden

**Über einen bereits testierten Betreiber betreiben lassen.** Ein Betreiber, der ein C5-Testat bereits
hält — etwa ein Landes- oder Bundesrechenzentrum —, nimmt OPAA in seinen Prüfumfang auf. Die Behörde
bezieht dann das Testat und muss selbst nichts nachweisen. Für Häuser ohne eigenes testiertes
Rechenzentrum ist das der kürzere Weg; er setzt voraus, dass OPAA mandantenfähig betrieben werden kann,
und ist damit an die Mandantengrenze der Organisation gebunden.

Welcher Weg der richtige ist, hängt allein am vorhandenen Betrieb, nicht an einer Produktentscheidung.
OPAA verhält sich in beiden Fällen gleich.

---

## Mitbestimmungsfähigkeit

OPAA erzeugt Daten mit Personenbezug: abgelegte Chatverläufe in gemeinsamen Räumen, Nutzungsstatistiken
je Asset, Protokolldaten. In einer Behörde ist das mitbestimmungsrelevant — **ohne Dienstvereinbarung
beginnt in aller Regel kein Rollout**, und die Personalvertretung spricht genau diese Punkte an. Das ist
das am häufigsten unterschätzte Einführungshindernis: kein technisches, sondern eines, das ein Projekt vor
der ersten Zeile Nutzung anhalten kann.

Die ausführliche Begründung, die drei Datenquellen mit Personenbezug und die vollständige Liste der
Stellschrauben stehen in
[Spaces, Assets & Zugangskontrolle](./spaces-and-assets.md#mitbestimmung-und-personalvertretung). Hier
stehen die fünf Eigenschaften, die das Produkt dafür tragen muss.

### 1. Sichtbarkeit ist eine Handlung, keine Automatik

Chats und Artefakte entstehen als **Entwurf** und werden erst sichtbar, wenn die Person sie ablegt. Wer
teilt, tut es bewusst. Die dreimal gestellte Rückfrage zu einer Rechtsgrundlage, bei der jemand unsicher
ist, wird damit nicht zur dauerhaft sichtbaren Wissenslücke in Schriftform.

Der persönliche Space ist **verbindlich unbeobachtet** — auch gegenüber Systemverwaltung, Revision und
Dienststellenleitung — und **fachlich gleichwertig**: Dort steht dasselbe Wissen und derselbe Suchbereich
zur Verfügung wie im gemeinsamen Raum. Ohne diese Gleichwertigkeit wäre die Ausweichmöglichkeit nur
formal und der Zwang zum sichtbaren Raum faktisch.

Geschützt ist der **Inhalt**, nicht die Tatsache der Nutzung: Dass jemand arbeitet, wird protokolliert;
was er schreibt, nicht. Das gehört ausgesprochen, damit die Auskunft an die Beschäftigten stimmt.

### 2. Einen personenbezogenen Auswertungspfad gibt es nicht

> **Es gibt keine Schnittstelle und keine Oberfläche, die Nutzungs-, Chat- oder Herkunftsdaten nach Person
> filtert, gruppiert oder sortiert. Diese Funktion ist nicht abschaltbar vorhanden — sie existiert
> nicht.**

**Nicht abgeschaltet, sondern nicht gebaut.** Eine Funktion, die es nicht gibt, kann niemand einschalten.
Für eine Dienstvereinbarung ist das der Unterschied zwischen einer Zusage und einer Tatsache — und
zwischen „heute ist es aus" und „morgen ist es an, mit rückwirkend auswertbaren Daten von gestern".

Zwei Wege bleiben notwendigerweise offen, und beide sind kein Auswertungspfad:

- **Selbstauskunft** der betroffenen Person — ihr eigenes Auskunftsrecht, nicht delegierbar.
- **Anlassbezogene Klärung eines Sicherheitsvorfalls** — im **Vier-Augen-Prinzip unter Beteiligung der
  Personalvertretung**, mit dokumentiertem Anlass und eigenem Protokolleintrag über den Zugriff. Ein
  Produkt ohne jede Möglichkeit, einen Vorfall aufzuklären, wäre nicht betreibbar; eines, in dem diese
  Aufklärung der Normalweg ist, wäre nicht zustimmungsfähig.

Diese Ausnahme ist **inhaltlich begrenzt, nicht nur formal** — sie ist der einzige verbliebene Weg von
den Daten zu einer Person, und alles, was jemand wissen will, drückt künftig durch dieses eine Nadelöhr:

1. **Zweckausschluss.** Nicht verfügbar für arbeitsrechtliche, disziplinarische und leistungsbezogene
   Fragen — auch nicht bei Mischsachverhalten.
2. **Umfangsbegrenzung vorab.** Person, Zeitraum und Zweck werden vor der Freigabe festgelegt und
   begrenzen die Abfrage **technisch**. Sonst klärt man einen Vorfall vom Mai und liest dabei zwei Jahre.
3. **Unterrichtung der betroffenen Person** nach Abschluss, mit Anlass und Umfang — außer die Klärung
   richtet sich gegen einen Dritten.
4. **Jahresbericht an die Personalvertretung** über Zahl der Fälle und Anlässe in Kategorien, ohne Namen.

### 3. Keine Ranglisten

Kein Vergleich einzelner Beschäftigter, keine Bestenlisten, keine Aktivitätsbewertung — **auch nicht als
spielerisches Element**. Der spielerische Rahmen ändert nichts an der Datengrundlage; er macht die
Auswertung nur geselliger.

Statistiken sind **aggregiert** je Organisationseinheit. Unterhalb einer Mindestgruppengröße wird der Wert
**unterdrückt statt angezeigt** — nicht gerundet, nicht anonymisiert dargestellt, sondern nicht
ausgegeben. Das Produkt setzt eine Voreinstellung und erzwingt eine Untergrenze; die angemessene Zahl
folgt aus dem tatsächlichen Zuschnitt der Einheiten und gehört in die Dienstvereinbarung.

### 4. Aufbewahrung mit Ober- und Untergrenze

Für Chats, Artefakte, Entwürfe, Herkunftsdaten **und Protokolldaten**: konfigurierbare Frist mit einer
**Höchstdauer**, nicht nur einer Mindestdauer, und automatischer Löschung nach Ablauf.

Die **Netzadresse gehört nicht zum Standard-Protokollsatz**, weil sie Dienststelle und Heimarbeit
unterscheidbar macht und damit ein Anwesenheitsmerkmal ist. Wird sie für Sicherheitszwecke benötigt, wird
sie ausdrücklich eingeschaltet, begründet und aus Berichten und Exporten ausgeschlossen.

### 5. Getrennte Zugriffswege, technisch durchgesetzt

Revision und Dienststellenleitung haben **verschiedene** Auswertungswege, und die Trennung ist technisch
durchgesetzt statt organisatorisch zugesagt. Für **jede erhobene Kennzahl ist der Zweck dokumentiert** —
auch für die Protokolldaten und die Herkunftsverfolgung, nicht nur für die Kennzahlen des Cockpits. Ein
**Auszug für die Personalvertretung** ist exportierbar: welche personenbeziehbaren Daten erhoben werden,
in welcher Granularität, wozu und wie lange sie liegen.

### Die Wirkung

**Die Dienstvereinbarung wird damit zu einer Konfigurationsaufgabe statt zu einem Projektrisiko.** Die
Punkte, die die Personalvertretung ansprechen wird, sind im Produkt bereits entschieden; verhandelt werden
Fristen, Schwellen und Zuständigkeiten — nicht die Frage, ob eine Überwachungsfunktion existiert.

### Was das Produkt nicht regeln kann

- **Freiwilligkeit.** Ob die Nutzung verpflichtend wird und ob Beschäftigten ein Nachteil entsteht, die
  den Assistenten nicht oder nur für sich nutzen, entscheidet die Dienststelle.
- **Die Höhe der Mindestgruppengröße.** In einem Referat mit vier Beschäftigten ist auch ein Aggregatwert
  personenbeziehbar, sobald zwei im Urlaub sind.
- **Die rechtliche Bewertung.** Ob und in welchem Umfang der Mitbestimmungstatbestand greift, entscheidet
  die einführende Stelle; die Darstellung hier ist Produktsicht und keine Rechtsberatung.

---

## Integrationspunkte

- **Identität und Kontenlebenszyklus:** jede Rechte- und Kontenänderung erzeugt einen Protokolleintrag →
  [access-control.md](./access-control.md)
- **Rechtemodell und Mitbestimmung:** die Datenquellen mit Personenbezug und die vollständige Liste der
  Stellschrauben → [spaces-and-assets.md](./spaces-and-assets.md)
- **Monitoring und Governance:** die Grenze dieses Dokuments ist dort die verbindliche Vorgabe →
  [monitoring-and-governance.md](./monitoring-and-governance.md)
- **RAG-Engine:** der angewandte Suchbereich je Abfrage ist die Grundlage der Regressionsprüfung →
  [data-indexing-rag.md](./data-indexing-rag.md)
- **Modelle:** die Freigabe externer Modelle ist eine sicherheitsrelevante Einstellung →
  [llm-integration.md](./llm-integration.md)
- **Betrieb:** Verschlüsselung ruhender Daten, Schlüsselverwaltung, Sicherung und Wiederanlauf liegen
  beim Betreiber → [deployment-infrastructure.md](./deployment-infrastructure.md)

---

## Offene Fragen

- **Der Umfang des revisionssicheren Protokolls ist nicht entschieden** — welche Ereignisse, welche Tiefe,
  welche erste umsetzbare Stufe und was „revisionssicher" im Einzelnen bedeutet. Die Klärung ist als
  eigene Arbeit erfasst und soll als Entscheidungsvorlage erfolgen.
- Verlangt eine C5-Prüfung die Netzadresse im Protokollsatz zwingend? Falls ja, ist die Ausnahme
  schriftlich zu begründen.
- Wie wird die technische Trennung der Auswertungswege für Revision und Leitung konkret durchgesetzt —
  über getrennte Rollen, getrennte Endpunkte oder getrennte Bestände?
- Wie wird die vorab festgelegte Umfangsbegrenzung der anlassbezogenen Klärung technisch erzwungen?
- Ab wann und in welchem Umfang erfolgt die unabhängige Sicherheitsprüfung?
- Wie weit reicht der Bestandsexport für einen Betreiberwechsel — Rohbestände, Assets, Konfiguration,
  Protokolldaten?

---

## Erfolgs-Metriken

- **Prüfbarkeit:** Die Negativfrage („belegen Sie, dass X nicht zugänglich war") ist für einen beliebigen
  Stichtag ohne Nacharbeit beantwortbar.
- **Vollständigkeit:** Kein Zugriff auf Protokolldaten ohne eigenen Eintrag; nachgewiesen durch Prüfung
  gegen alle Auswertungswege.
- **Nachweis der Nichtexistenz:** Ein Test gegen sämtliche Auswertungsendpunkte belegt, dass keiner nach
  Person gruppiert oder sortiert.
- **Löschtreue:** Nach Ablauf der Höchstfrist existieren keine Sätze mehr; nach einer Kontolöschung ist
  kein Protokollsatz auf eine Person zurückführbar, und kein Satz wurde verändert.
- **Einführungsreife:** Das Nachweispaket ist vor der ersten Prüfung vollständig vorlegbar — gemessen
  daran, wie viele Rückfragen einer Prüfstelle es nicht schon beantwortet.

---

## Verwandte Dokumente

- [Identität, Rechte & Mandanten](./access-control.md) — Anmeldung, Kontenlebenszyklus, Systemverwaltung
- [Spaces, Assets & Zugangskontrolle](./spaces-and-assets.md) — Rechtemodell und Mitbestimmung im Detail
- [Monitoring, Kosten & Governance](./monitoring-and-governance.md) — was ausgewertet werden darf
- [Produktvision](../VISION.md) — Einordnung in die Themenbereiche und Phasen
