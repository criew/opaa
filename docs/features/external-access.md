# Fremdzugänge: Zugangstokens und MCP-Server

> **Status: Entwurf.** Beschlossen am 18.09.2026 auf Grundlage der Recherche „OPAA als Wissensschicht
> für andere KI-Tools", überarbeitet am 18.09.2026 nach fünf Stakeholder-Bewertungen (Betrieb,
> Personalrat, Referatsleitung, Skeptiker, KI-Champion). Umgesetzt wird sie in Epic
> [#1715](https://github.com/criew/opaa/issues/1715); gebaut ist davon noch nichts. Der
> Entwurfshinweis entfällt, wenn der Maintainer die Spezifikation nach der Umsetzung abnimmt.

## Motivation

Beschäftigte arbeiten zunehmend in KI-Werkzeugen, die OPAA nicht kennt — einer Entwicklungsumgebung
mit Assistent, einem Automatisierungswerkzeug, einem eigenen Skript. Diese Werkzeuge haben Zugriff
auf das offene Netz, aber nicht auf das Wissen des Hauses. Wer sie trotzdem für eine fachliche Frage
benutzt, arbeitet entweder ohne die einschlägigen Unterlagen oder kopiert sie dorthin hinein.

Der Ausweg ist nicht, jedes dieser Werkzeuge nachzubauen, sondern **OPAA für sie erreichbar zu
machen**: Das fremde Werkzeug bleibt, wo es ist, und holt sich seine Belege aus dem Bestand des
Hauses — mit den Rechten der Person, die es benutzt, und nur aus den Beständen, die dafür
ausdrücklich freigegeben sind.

**Was sich dadurch ändert — und was nicht.** Das Ziel der Daten bleibt dasselbe fremde Werkzeug. Der
Kanal macht den Weg dorthin bequemer, belegter und an drei Stellen genehmigungspflichtig; er macht
ihn nicht enger. Aus „ein Dokument über die Zwischenablage, an jeder Freigabe vorbei" wird „die
freigegebene Bibliothek über `search` und `fetch`, mit Herkunftsangabe und mit einer Entscheidung
dahinter, die jemand getroffen hat und die zurückgenommen werden kann". Das ist der Gewinn:
**Entscheidbarkeit und Rückholbarkeit, nicht Dichtheit.** Wer den Kanal als Lösung eines
Abflussproblems verkauft, verspricht etwas, das er nicht halten kann — der unkontrollierte Weg über
die Zwischenablage bleibt daneben offen, und er bleibt es auch, solange Schalter oder Freigabe noch
fehlen.

Das ist eine Öffnung, und Öffnungen erzeugen Risiken. Eine Bibliothek, die über einen Fremdzugang
erreichbar ist, kann von einem Werkzeug gelesen werden, dessen Betreiber die Behörde nicht kennt.
Deshalb ist der Fremdzugang **kein Nebeneffekt der bestehenden Leserechte**, sondern eine eigene,
mehrfach eingeschränkte Entscheidung: Die Installation muss ihn einschalten, die Bibliothek muss
freigegeben sein, und die Person muss ihn für ein benanntes Werkzeug bewusst erteilen.

---

## Überblick

1. **Fremdzugänge sind ein eigener Kanal mit eigener Freigabe.** Wer eine Bibliothek in der
   Web-Oberfläche lesen darf, darf sie deshalb noch lange nicht über ein fremdes Werkzeug lesen.
2. **Drei Schalter müssen zugleich offen sein**: der Schalter der Installation, die Freigabe der
   Bibliothek und die Auswahl im Zugangstoken. Die effektive Sicht ist ihre Schnittmenge mit den
   Rechten der Person — bei **jedem Werkzeugaufruf** neu ausgewertet, nie eingefroren.
3. **Ein Zugangstoken kann nie mehr als seine Person.** Es ist ein Ausschnitt ihrer Rechte, kein
   eigener Rechteträger; verliert die Person ein Recht, verliert es das Token in derselben Sekunde.
4. **Fremdzugänge lesen und suchen — mehr nicht.** Kein Verwalten, kein Indizieren, kein Hochladen,
   kein Rechtevergeben. Der Rechteumfang ist fest und nicht wählbar.
5. **Dieselben Domain-Dienste wie die Web-Oberfläche.** Der Suchendpunkt und die MCP-Werkzeuge rufen
   den Dienst hinter `POST /api/v1/query` auf; es gibt keinen zweiten Weg an den Index. Die
   Rechteprüfung sitzt in der Suche, nicht davor.
6. **Die einzelne Abfrage wird nicht protokolliert.** Protokollpflichtig sind die
   Zugriffsänderungen — Token ausstellen, sperren, ablaufen, freigeben, schalten —, nicht das
   Verhalten. Die Zusage aus
   [security-and-compliance.md](./security-and-compliance.md#was-ausdrücklich-nicht-protokolliert-wird)
   bleibt unverändert; auch eine Nutzungszählung je Token gibt es nicht.
7. **Die Freigabe einer Bibliothek ist ein Reichweitenfeld.** Sie wird wie `visibility` und `listed`
   historisiert und ist **pflichtbefristet**; unter die Freigabe-Obergrenze konnektor-gespeister
   Bibliotheken fällt sie, sobald diese definiert ist (#797).
8. **OPAA wird dafür nicht öffentlich erreichbar.** Der ganze Kanal liegt zusätzlich hinter einer
   installationsweiten Netzbeschränkung, Vorgabe Hausnetz. Zielclients sind Werkzeuge, die auf dem
   Arbeitsplatz oder im Hausnetz laufen. Ein Betrieb als OAuth-Autorisierungsserver für fremde
   Cloud-Dienste ist ausdrücklich nicht Gegenstand dieser Stufe.
9. **Der Installationsschalter ist zugleich der Notaus.** Ein Ausschalten wirkt sofort für alle
   Tokens und beendet laufende Sitzungen, vernichtet die Tokens aber nicht — Wiedereinschalten stellt
   den vorherigen Zustand her.
10. **Der Kanal erklärt sich dem fremden Modell selbst.** Werkzeugbeschreibungen und der
    Einleitungstext des Servers werden aus der effektiven Sicht des Tokens erzeugt, damit das fremde
    Werkzeug von allein erkennt, welche Fragen hierher gehören.

---

## Abgrenzung: was dieser Kanal ist und was nicht

| | |
|---|---|
| **Ist** | Ein lesender Zugang für Werkzeuge, die eine Person auf ihrem Arbeitsplatz oder im Hausnetz betreibt: Entwicklungsassistenten, Automatisierungswerkzeuge, eigene Skripte |
| **Ist nicht** | Ein Zugang für fremd betriebene Verbraucherdienste. OPAA wird dafür nicht ins offene Netz gestellt, betreibt keinen Autorisierungsserver und kennt keine dynamische Client-Registrierung |
| **Ist nicht** | Eine zweite Assistenzoberfläche. Fremdzugänge liefern Fundstellen, keine erzeugten Antworten; die Generierung findet im fremden Werkzeug statt, mit dessen Modell |
| **Ist nicht** | Die Gegenrichtung. Dass OPAA selbst fremde MCP-Server als Werkzeuge einbindet, ist ein anderes Thema und bleibt bei den Agenten (siehe [agents-and-tools.md](./agents-and-tools.md#mcp-als-standardisierte-anbindung), Phase 2) |
| **Ist nicht** | Ein modellkompatibler Chat-Endpunkt. Ein OpenAI-förmiges `/v1/chat/completions` wird bewusst **nicht** gebaut: Es würde die Belege auf ein Textfeld reduzieren und die Modellvorgaben der Systemverwaltung umgehen |
| **Ist nicht** | Der Zugang für ein Fachverfahren. Diese Stufe kennt nur **persönliche** Tokens; ein Fachverfahren hinge damit am Konto einer Person und stünde still, sobald sie ausscheidet. Das Handbuch rät ausdrücklich davon ab, bis es Service-Accounts gibt |

---

## Der Schalter der Installation

Der Fremdzugang ist eine Einstellung der Systemverwaltung, installationsweit, **Standard aus**.

> **Gebaut (#1717).** Die Einstellungszeile, ihre Oberfläche unter *Administration →
> Fremdzugänge* und das Protokollereignis `EXTERNAL_ACCESS_SETTINGS_CHANGED` existieren. Die
> ausgelieferten Werte sind: Kanal **aus**, Ablauf-Obergrenze 90 Tage, Kontingent 60 Anfragen je
> Token und Stunde, Netzbereiche `10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`, `127.0.0.0/8`,
> `::1/128`, `fc00::/7` („Hausnetz"), Abflussalarm ab 600 Abrufen je Stunde und der Vorgabetext
> unten. Die Netzprüfung selbst liegt in einer Komponente mit `isAllowed(remoteAddress)`, die
> geschlossen versagt (leere Liste, fehlende Adresse und alles, was DNS auflösen könnte, werden
> abgewiesen); die Adresse stammt dabei aus der Trusted-Proxy-Auflösung, siehe „Welche Adresse
> geprüft wird" unten. **Noch nicht gebaut** sind die Auswertungen dieser Werte: die Durchsetzung an
> Suchweg und `/mcp` (#1720, #1721), das Kontingent und der Abflussalarm (#1720) sowie die
> Verwendung des Einleitungstextes im MCP-Handschlag (#1721).

```
Systemkonfiguration → Fremdzugänge
  [ ] Fremdzugänge erlauben                          (aus)
      zuletzt geändert am … durch …
      Ablauf-Obergrenze für Zugangstokens:  90 Tage
      Kontingent je Token:                  … Anfragen je Stunde
      Netzbereiche des Kanals:              Hausnetz (CIDR-Liste)
      Schwellenwert für den Abflussalarm:   … Abrufe je Stunde (installationsweit)
      Einleitungstext für fremde Werkzeuge: (Vorgabetext, änderbar)
```

**Solange er aus ist**, verhält sich die Installation, als gäbe es den Kanal nicht. Das Verhalten ist
entschieden und nicht mehr offen:

- **Ohne gültiges Token — `404`.** Ein Aufrufer, der nicht belegen kann, dass er zum Haus gehört,
  erfährt nicht einmal, dass es den Dienst gibt.
- **Mit gültigem Token — `503` mit einer benannten Ursache.** Der Betrieb muss „Kanal ist zu" von
  „falscher Pfad" und „Proxy kaputt" unterscheiden können, und er hat dafür keine Abfrageprotokolle,
  in denen er nachsehen könnte. Dieselbe Unterscheidung braucht die Person, die ihr Werkzeug gerade
  einrichtet und wissen muss, ob es an ihr liegt. Das Handbuchkapitel führt beide Fehlerbilder samt
  Ursache in der Störungssuche.

Vorhandene Tokens bleiben dabei **bestehen**. Das ist Absicht: Der Schalter ist der Notaus für den
Störungsfall, und ein Notaus, der die Einrichtung aller Beschäftigten vernichtet, wird im Zweifel
nicht benutzt.

**Der Notaus wirkt je Werkzeugaufruf, nicht je Sitzung.** Streamable HTTP kennt langlebige
Sitzungen; eine Schalterprüfung nur beim Verbindungsaufbau ließe offene Sitzungen den Notaus
überdauern und machte die Meldung „Kanal ist zu" falsch. Schalterzustand, Tokengültigkeit und
effektive Sicht werden deshalb bei **jedem** Werkzeug- und Endpunktaufruf geprüft; bestehende
Sitzungen enden beim Ausschalten. Das ist dieselbe Regel, die
[access-control.md](./access-control.md#sitzungen-netzbereiche-und-erzwungene-neuanmeldung) für
Sitzungen bereits kennt: Was mit einer beendeten Sitzung weiterläuft, trägt Rechte weiter, die es
nicht mehr gibt.

**Der Netzbereich gehört dem Kanal, nicht dem Token.** Die Einschränkung auf Netzbereiche gilt
installationsweit für den gesamten Fremdzugangskanal, Vorgabe **nur Hausnetz**. Der realistische Weg,
auf dem ein Token nach außen gerät, ist kein Angriff, sondern eine MCP-Konfigurationsdatei, die im
Klartext in einem Repository oder einem synchronisierten Profil landet; mit der Vorgabe wirkt es
dort nicht. Eine Einschränkung **je Token** wird bewusst nicht gebaut — siehe
[Abwägungen](#abwägungen-und-verworfene-alternativen).

**Welche Adresse geprüft wird.** Die Prüfung nimmt die Adresse aus der Trusted-Proxy-Auflösung
(`ClientIpResolver`, ADR-0033, Entscheidung 9): `X-Forwarded-For` zählt nur, wenn die Verbindung
selbst aus einem in `OPAA_RATE_LIMIT_TRUSTED_PROXY_CIDRS` eingetragenen Netz kommt; ohne
eingetragenen Proxy wird der Kopf ignoriert. Die Adresse der Verbindung selbst (`getRemoteAddr()`)
ist keine zulässige Quelle — hinter einem Reverse Proxy wäre sie entweder die Adresse des Proxys,
die selbst im Hausnetz liegt, oder der vom Aufrufer geschriebene linkeste Eintrag des Kopfes. Wer
OPAA hinter einem Proxy betreibt, **muss die Proxy-Netze dort eintragen**, sonst prüft die
Netzbeschränkung die Adresse des Proxys statt die des Arbeitsplatzes.

**Der Einleitungstext des Servers** (das `instructions`-Feld der MCP-Initialisierung) wird auf
derselben Seite gepflegt. Ein Vorgabetext ist gesetzt und für die meisten Häuser ausreichend:

```
Bei Fragen zu Verwaltungsvorgängen dieser Behörde zuerst „search" aufrufen.
Antworten mit Fundstellen belegen und die Herkunft angeben (Bibliothek,
Dokument, Fundstelle). Nichts erfinden, was die Treffer nicht hergeben.
```

**Protokollpflichtig ist das Schalten selbst** — in beide Richtungen — sowie jede Änderung der
Ablauf-Obergrenze, des Kontingents, der Netzbereiche und der Alarmschwelle. Eine Installation, in der
niemand nachweisen kann, seit wann der Kanal offen stand, hat den Kanal nicht im Griff. **Nicht**
protokollpflichtig ist die Änderung des Einleitungstextes: Sie ändert keine Reichweite, sondern die
Formulierung einer Aufforderung an ein fremdes Modell. Die geschlossene Liste aus
[security-and-compliance.md](./security-and-compliance.md#die-ereignisse-der-ersten-stufe) zählt
Systemeinstellungen einzeln auf, statt „alle Einstellungen" zu sagen; der Schalter samt Grenzwerten
ist dort als eigener Punkt **ergänzt** — neben der Freigabe-Obergrenze, die aus demselben Grund einen
eigenen hat —, der Einleitungstext ausdrücklich nicht.

**Das Einschalten ist ein Verwaltungsakt, kein Konfigurationsschritt.** Der Fremdzugang ist eine
technische Einrichtung, die zur Überwachung von Verhalten und Leistung geeignet sein kann — ob und in
welchem Umfang daraus ein Mitbestimmungstatbestand folgt, richtet sich nach dem einschlägigen
Personalvertretungsrecht und ist von der einführenden Stelle **rechtlich zu prüfen**. Handbuch und
Oberfläche sagen deshalb ausdrücklich: Die Beteiligung der Personalvertretung ist **vor** dem
Einschalten zu klären, und die Beschäftigten werden über den Kanal, die dabei erhobenen Angaben und
die **Freiwilligkeit** der Nutzung vorab unterrichtet. Aus der Nichtnutzung eines Fremdzugangs
entsteht kein Nachteil; jede Fachaufgabe bleibt über die Web-Oberfläche vollständig erledigbar.

**Nach einer Wiederherstellung aus einer Sicherung gilt nichts von alledem automatisch weiter.**
Schalter, Freigaben und Tokens sind Zeilen in der Datenbank; ein Restore von vorgestern setzt alle
drei zurück — einschließlich eines Notaus und gesperrter Tokens, und das Protokoll, das ihn belegen
würde, ist mit derselben Sicherung zurückgerollt. Die Oberfläche zeigt deshalb Schalterzustand und
Zeitpunkt der letzten Änderung prominent an, und das Handbuchkapitel trägt eine Prüfliste „nach
Wiederherstellung": Schalterzustand, freigegebene Bibliotheken und Tokenbestand gegen den Stand vor
dem Restore prüfen.

Ob Tokens auch bei ausgeschaltetem Kanal **angelegt** werden können, entscheidet die Umsetzung zu
Gunsten der Verständlichkeit: Die Oberfläche zeigt die Verwaltung dann mit einem deutlichen Hinweis,
dass der Kanal derzeit geschlossen ist (Annahme, siehe [Offene Fragen](#offene-fragen--zukünftige-erweiterungen)).

---

## Die Freigabe der Bibliothek

Die **Freigabe-Einheit ist die Wissensbibliothek**, nicht der Arbeitsraum. Der Arbeitsraum ist eine
Sicht auf Bibliotheken; Rechte hängen ohnehin an der Bibliothek, und eine Freigabe, die an einer
Sicht hängt, ließe sich durch eine zweite Sicht umgehen.

Jede Bibliothek trägt ein Merkmal **„darf über Fremdzugänge genutzt werden"**, Standard **aus**.
Gesetzt wird es von dem, der die Bibliothek verwaltet — mindestens Verwalter-Rolle an ihr — sowie von
der Systemverwaltung.

### Die Freigabe ist ein Reichweitenfeld und wird wie eines behandelt

Das Merkmal ist fachlich dasselbe wie `visibility` und `listed`: eine Stufe der Reichweite, an der
Bibliothek. Daraus folgen vier Festlegungen. Zwei davon brauchen keine neue Mechanik — die
Intervall-Historisierung samt Schreibpfadschutz und das Ablaufereignis befristeter Grants existieren
im Produkt bereits. Die anderen beiden hängen an Mechaniken, die heute **noch nicht gebaut** sind;
sie sind deshalb als Bedingung formuliert und nicht als Abnahmekriterium dieser Stufe:

1. **Historisiert, nicht nur protokolliert.** Das Merkmal wandert in dieselbe Intervall-Historisierung
   wie `visibility` und `listed` (siehe
   [security-and-compliance.md](./security-and-compliance.md#nachweisbarkeit-historisierung-von-rechten)),
   mit demselben Schreibpfadschutz: veränderbar nur aus dem Paket heraus, das die Historienzeile
   schreibt. Der Protokolleintrag bleibt zusätzlich. Der Grund ist eine Prüfsituation: Das Protokoll
   wird nach Frist monatsweise vollständig gelöscht — die Frage „war die Bibliothek ‚Vergabeakten
   2026' im Jahr 2026 aus dem Haus erreichbar?" wäre 2030 sonst mit „ich weiß es nicht" zu
   beantworten, bei einem Feld, das über Hausgrenzen entscheidet.
2. **Unter der Freigabe-Obergrenze — sobald es sie gibt.** Eine Bibliothek, die aus einem Konnektor
   gespeist wird, trägt die Obergrenze aus
   [access-control.md](./access-control.md#dokumentenfluss-konnektoren-gegen-benutzer-uploads) — die
   dort als „einzige technische Sicherung zwischen ‚Fachverfahrensdaten eingespeist' und
   ‚organisationsweit lesbar'" bezeichnet wird. Sie deckelt deshalb auch dieses Merkmal; andernfalls
   wäre der Fremdzugang der Weg an ihr vorbei. Wird die Obergrenze nachträglich gesenkt, wird eine
   bereits gesetzte Freigabe **ausgesetzt, nicht stillschweigend entzogen**: Sie steht auf der Liste
   des Bibliotheks-Eigentümers und wirkt nicht mehr, bis er sie anpasst. **Die Obergrenze ist heute
   weder definiert noch gebaut** — ihre genaue Wirkung entscheidet
   [#797](https://github.com/criew/opaa/issues/797). Das Merkmal fällt unter sie, **sobald #797 sie
   liefert**; diese Stufe nimmt die Entscheidung nicht vorweg und baut die Deckelung nicht mit. Wer
   #797 umsetzt, findet das Merkmal in der Aufzählung der gedeckelten Felder.
3. **Pflichtbefristet, höchstens ein Jahr.** Eine Freigabe ohne Ablauf ist eine Ratsche: Jede Anfrage
   „ich brauche X in meinem Werkzeug" erzeugt eine, und nichts erzeugt je eine Rücknahme. Nach
   spätestens einem Jahr **erlischt** sie; erneuern kann nur, wer den Bestand verantwortet. Der
   Ablauf erzeugt denselben Protokolleintrag wie jede andere ablaufende Befristung („Ablauf einer
   Befristung, sobald sie wirkt"), und die Wiedervorlage erreicht den Verantwortlichen rechtzeitig
   über denselben Mailweg wie die Tokenerinnerung. Das ist die einzige Maßnahme, die das
   Erfolgskriterium „der Anteil bleibt klein" durchsetzt, statt ihn zu erhoffen — und sie erzwingt
   einmal im Jahr das fachliche Gespräch, das den Kanal trägt.
4. **Keine Freigabe ohne fachliche Zuständigkeit — als künftige Sperrbedingung.**
   [spaces-and-assets.md](./spaces-and-assets.md#eigentümerschaft-und-verwaisung) friert die
   Reichweite von Assets im Zustand **„Nachfolge offen"** ein — „keine neuen Grants, keine Erhöhung
   der Freigabestufe" —, und dieses Merkmal ist eine Erhöhung der Reichweite. Sobald dieser Zustand
   im Produkt existiert (heute ist er Konzept, kein Feld), gehört das Merkmal in dieselbe Sperre:
   keine neue Freigabe, keine Erneuerung einer bestehenden. Wer den Zustand baut, findet diese
   Bedingung hier; diese Stufe baut sie nicht vor.

Die Änderung des Merkmals ist eine **Zugriffsänderung** und wird protokolliert, in beide Richtungen,
mit der handelnden Person, der betroffenen Bibliothek und dem Ablaufdatum der Freigabe.

```
Bibliothek „Baugenehmigungen 2024"
  Zugriff
    Leserechte …
    [ ] Über Fremdzugänge nutzbar        bis [ 17.09.2027 ]  (höchstens ein Jahr)
        Wenn gesetzt, können Personen mit Lesezugriff diese Bibliothek in einem
        eigenen Zugangstoken auswählen und aus fremden Werkzeugen darin suchen.
        Derzeit in 4 Zugangstokens enthalten.
```

### Was der Verantwortliche sieht

Der Bibliotheksverantwortliche sieht die **Anzahl** der Zugangstokens, die seine Bibliothek derzeit
enthalten — eine Zahl, keine Namen, keine Personen, keine Nutzung. Er hat damit den einen Anhalt, den
seine Entscheidung braucht: ob die Freigabe überhaupt gebraucht wird. Ihm eine Personenliste zu
zeigen, wäre genau der personenbezogene Auswertungspfad, den dieser Kanal an jeder anderen Stelle
ausschließt; ihm gar nichts zu zeigen, machte die jährliche Erneuerung zu einer Entscheidung ohne
Grundlage.

Wird die Freigabe zurückgenommen oder erlischt sie, verschwindet die Bibliothek aus der effektiven
Sicht **aller** Tokens, die sie ausgewählt hatten — sofort und ohne dass die Tokens angefasst werden
müssen. Die Auswahl im Token bleibt gespeichert und wird als **ausgesetzt angezeigt**, statt
stillschweigend wegzulassen; sie lebt aber **nicht von allein wieder auf**, wenn die Bibliothek
später erneut freigegeben wird. Ein Token, dessen Umfang ohne Zutun der Person wieder wächst, ist aus
derselben Richtung falsch wie eine Auswahl „alle, auch künftige" — nur langsamer. Wer die Bibliothek
wieder nutzen will, erteilt sie in einem neuen Token.

---

## Zugangstokens

Ein Zugangstoken ist ein persönliches Merkmal, das eine Person in ihren eigenen Einstellungen für ein
benanntes Werkzeug erzeugt. Es baut auf der Ausstellungs- und Widerrufsmechanik der lokalen Token
([ADR-0033](../decisions/0033-lokale-benutzerverwaltung.md)) auf — dieselbe Prüfkette, derselbe
Sofortwiderruf. Der Tokenwert selbst ist ein undurchsichtiges Zufallsmerkmal, serverseitig nur als
Hash gespeichert; widerrufen wird es über seine eigene Zeile, nicht über eine Sperrliste
selbsttragender Merkmale.

### Eigenschaften

| Eigenschaft | Regel |
|---|---|
| **Name / Zweck** | Pflicht. „Claude Code auf dem Dienstrechner", nicht „Token 3". Der Name ist das Einzige, woran die Person in einem Jahr noch erkennt, was sie widerrufen darf. Er steht in der Tokentabelle, **nicht** im Nachweisprotokoll |
| **Bibliotheken** | Konkrete Liste, Pflicht, mindestens eine. Auswählbar ist nur, was die Person selbst lesen darf **und** was freigegeben ist. **Keine Option „alle, auch künftige"**. Die Auswahl ist nach der Ausstellung **unveränderlich** — eine Änderung ist ein neues Token |
| **Ablauf** | Pflicht. Kein Token ohne Ablaufdatum. Die Obergrenze setzt die Systemverwaltung installationsweit; Vorgabe 90 Tage. Die Person wird 14 und 3 Tage vorher per Mail erinnert |
| **Rechteumfang** | Fest: lesen und suchen. Kein Auswahlmenü. Ein Token erreicht ausschließlich die Such-, Abruf- und Auflistungswege, nie einen Verwaltungsweg |
| **Netzbereich** | **Nicht je Token.** Die Netzbeschränkung gilt installationsweit für den ganzen Kanal (siehe [Schalter](#der-schalter-der-installation)) |
| **Anzeige (Person)** | Erstellt am, läuft ab am, zuletzt benutzt am — **nur das Datum**, und nur in der eigenen Sicht |
| **Anzeige (Systemverwaltung)** | Besitzer, Name, Bibliotheken, Ablauf, Zustand (gültig, abgelaufen, widerrufen, gesperrt). **Kein Nutzungsdatum, kein Personenfilter** |
| **Keine Zählung** | Es gibt keine Anzeige, wie oft ein Token benutzt wurde, und keine Statistik darüber. Eine Zählung je Person wäre ein Tätigkeitsprofil und damit genau das, was die Mitbestimmungsfähigkeit ausschließt |
| **Sichtbarkeit des Werts** | Genau einmal, unmittelbar nach dem Erzeugen. Danach nur noch ein Präfix zur Wiedererkennung. Kein Nachzeigen, kein Auslesen, auch nicht durch die Systemverwaltung |

### Beim Erzeugen wird gesagt, was der Kanal nicht zusagt

Die Zusage „was Sie fragen, wird nicht mitgeschrieben" gilt in OPAA. Sie gilt **nicht** in dem
Werkzeug, in das die Person ihre Frage eintippt: Frage, Treffer und abgerufene Inhalte laufen dort
durch, samt dessen Protokollierung, Telemetrie und Modellnutzung — über die OPAA nichts aussagen kann
und nichts aussagt. Wer das nicht weiß, verlässt sich für seinen tatsächlichen Arbeitsweg auf eine
Zusage, die dort endet. Die Oberfläche sagt es deshalb an der Stelle der Handlung, beim Erzeugen des
Tokens, in zwei Sätzen:

```
Ihre Fragen und die abgerufenen Inhalte verlassen mit diesem Token OPAA und
unterliegen der Protokollierung des fremden Werkzeugs. Die Zusage, dass OPAA
einzelne Abfragen nicht mitschreibt, gilt dort nicht.
Name, Bibliotheken und Ablauf dieses Tokens sieht auch die Systemverwaltung.
```

### Lebenszyklus

```
Person                              Systemverwaltung
  │                                   │
  ├─ erzeugen  ─────────────────────► Protokolleintrag
  │   Name, Bibliotheken, Ablauf
  │   Wert einmal sichtbar
  │
  ├─ eigene Tokens sehen              ├─ alle Tokens sehen
  │   Name, Ablauf, Bibliotheken,     │   Besitzer, Name, Ablauf,
  │   zuletzt benutzt                 │   Bibliotheken, Zustand
  │                                   │   (kein Nutzungsdatum)
  │                                   │
  ├─ eigenes Token widerrufen ──────► ├─ einzelnes Token sperren ──► Protokolleintrag
  │                                   ├─ alle Tokens einer Person sperren
  │                                   └─ Kanal abschalten (Notaus)
  │
  └─ Ablauf ────────────────────────► Protokolleintrag (Anlass: abgelaufen
      Erinnerung 14 und 3 Tage           bzw. Kontenlebenszyklus)
      vorher per Mail
```

Ein Token folgt dem Lebenszyklus seiner Person: Wird ihr Konto gesperrt, deaktiviert oder gelöscht,
wirken ihre Tokens nicht mehr. Ein Token, das die Deaktivierung überdauert, wäre der bequemste Weg,
den Kontenlebenszyklus zu umgehen — dieselbe Regel gilt in
[access-control.md](./access-control.md#offboarding) bereits für alles andere.

**Der Regelfall ist der Ablauf, nicht der Widerruf.** Bei einer Höchstlaufzeit von 90 Tagen
(Vorgabewert der Obergrenze) endet die überwiegende Mehrheit aller Tokens still. Ein Zugang, der ohne Eintrag endet, ist im Nachweis
dieselbe Lücke wie einer, der ohne Eintrag beginnt — deshalb erzeugt auch das Außerkrafttreten einen
Protokolleintrag, mit Anlass: abgelaufen oder Kontenlebenszyklus.

Die Systemverwaltung sieht die Liste aller Tokens und kann sperren, aber sie sieht **keinen
Tokenwert**, **keine Nutzungshäufigkeit** und **kein Nutzungsdatum**. Ihre Sicht ist eine
Bestandsliste für die Rechteprüfung und für Sperrentscheidungen — beides braucht kein Nutzungsdatum:
Gesperrt wird wegen eines Vorfalls oder eines Kontenlebenszyklus, nicht wegen Nichtbenutzung. Sie
kennt deshalb auch **keinen Filter nach Person**, sondern nur Filter über Zustand und Ablauf („läuft
in den nächsten 30 Tagen ab", „abgelaufen", „gesperrt"). Die Liste dient der Rechteprüfung und dem
Sperren; für arbeitsrechtliche, disziplinarische und leistungsbezogene Fragen steht sie nicht zur
Verfügung — es gilt dieselbe Zweckbindung wie für den übrigen Nachweisbestand.

### Was von einem Token übrig bleibt

- **„Zuletzt benutzt" wird beim Widerruf und beim Ablauf gelöscht.** Es ist eine Hilfe für die
  Entscheidung „darf ich das widerrufen?" und hat danach keinen Zweck mehr.
- **Abgelaufene und widerrufene Tokenzeilen werden nach einer benannten Frist gelöscht**, gekoppelt
  an die Protokollfrist der Installation. Sie dürfen nicht früher verschwinden als der
  Protokolleintrag, der sie belegt — sonst fehlt der Beleg, dass das Token je existiert hat —, und
  nicht später, sonst ist die Tokentabelle selbst das Nutzungsarchiv, das dieser Kanal an jeder
  anderen Stelle ausschließt.
- **Das Token-Präfix erscheint in keinem Anwendungs- oder Proxy-Log.** Es ist über Monate stabil und
  über die Tokentabelle einer Person zuzuordnen; in einem technischen Zugriffsprotokoll zusammen mit
  Zeitstempel und Netzadresse ergäbe es exakt die minutengenaue Abfragehistorie je Person, die das
  Nachweisprotokoll zu Recht ausschließt — nur ohne dessen Schutzregeln. Die Anwendung schreibt es
  deshalb nicht, auch nicht gekürzt, auch nicht auf Fehlerebene; die Betriebsdokumentation nennt es
  als Punkt, den die Proxy-Konfiguration der einführenden Stelle mit abdecken muss. Dass die Regel
  gilt, sichert eine Regressionsprüfung.

---

## Die effektive Sicht

```
        Rechte der Person            (was sie heute lesen darf)
                 ∩
        Freigabe der Bibliothek      (darf sie über Fremdzugänge genutzt werden,
                                      und ist die Freigabe noch gültig?)
                 ∩
        Auswahl im Token             (hat die Person sie diesem Werkzeug erteilt?)
                 ∩
        Schalter der Installation    (ist der Kanal überhaupt offen?)
        ────────────────────────────
        = was dieses Token sieht
```

Ausgewertet wird **zur Anfragezeit**, bei jedem einzelnen Werkzeugaufruf neu. Nichts davon wird in
das Token eingefroren; das Token trägt die Auswahl als Referenz, nicht als Kopie der Rechtelage.
Damit gilt die Regel ohne Ausnahme: **Ein Token kann nie mehr als seine Person, und nie mehr, als
beide Freigaben zulassen.**

Eine Bibliothek, die aus einem dieser vier Gründe wegfällt, verschwindet aus den Treffern — sie
erzeugt keinen Fehler und keinen Hinweis auf ihre Existenz. Das ist dieselbe Regel wie in der
Web-Oberfläche: Ein fremder Chunk wird nicht geladen, und seine Abwesenheit wird nicht erklärt.

---

## Was ein Fremdzugang erreicht

Drei Zugriffe, in dieser Reihenfolge gedacht: erst wissen, was da ist, dann suchen, dann das
Gefundene ganz lesen.

| Zweck | Verhalten |
|---|---|
| **Bibliotheken auflisten** | Gibt die effektive Sicht des Tokens zurück: Kennung, Name, Beschreibung. Das ist die Antwort auf „worin kann ich hier suchen?" und zugleich die einzige Stelle, an der ein fremdes Werkzeug den Umfang seines Zugangs erfährt |
| **Suchen** | Nimmt eine Frage entgegen und gibt **Treffer** zurück — Fundstellen mit Auszug, Herkunft, Metadaten und Relevanz. **Keine erzeugte Antwort**, kein Modellaufruf für eine Generierung. Optional auf einzelne der erteilten Bibliotheken eingegrenzt |
| **Abrufen** | Holt zu einer Trefferkennung den Abschnitt samt angrenzendem Kontext — auf Wunsch das ganze Dokument, beschränkt auf das, was die effektive Sicht enthält |

**Der Abruf liefert standardmäßig den Abschnitt, nicht das Dokument.** Die Vorgabe ist die Fundstelle
mit ihrem angrenzenden Kontext; das vollständige Dokument gibt es über einen ausdrücklichen Parameter
und unter einem serverseitigen Größendeckel. Das hat zwei Gründe, die in dieselbe Richtung zeigen:
Ein Assistenzwerkzeug arbeitet mit einem begrenzten Kontextfenster, und der Unterschied zwischen
Abschnitt und Dokument ist dort ein Vielfaches — ein Vorgabewert „ganzes Dokument" macht den
brauchbaren Fall zum Sonderfall. Und wer den Bestand abziehen will, muss es ausdrücklich verlangen,
statt es nebenbei zu bekommen.

Der Suchweg ist derselbe wie der der Web-Oberfläche: Teilfragen, Vektor- und Volltextsuche, Fusion,
Reranking, Rechtefilter in der Suche. Was dort nicht gefunden wird, wird hier auch nicht gefunden;
was hier gefunden wird, hätte die Person auch dort gefunden. Ein zweiter Rankingpfad wäre eine
zweite Qualitätswahrheit, die niemand pflegt.

Dieser Suchweg wird zugleich als **regulärer REST-Endpunkt** angeboten (`POST /api/v1/search` samt
Dokumentabruf). Er ist nicht auf Tokens beschränkt — auch eine angemeldete Person erreicht ihn — und
schließt zugleich eine Lücke, die
[user-frontends.md](./user-frontends.md#was-die-schnittstelle-anbietet) heute ausdrücklich als
fehlend führt: „ein eigener Such-Endpunkt neben der Abfrage" und „das Abrufen eines einzelnen
Dokuments". **Er hängt nicht am Fremdzugangsschalter:** Der Schalter ist der Notaus des
Fremdzugangskanals, nicht der des Suchendpunkts für angemeldete Personen. Für Token-Aufrufe gilt er
selbstverständlich.

---

## Der MCP-Server

Das Model Context Protocol ist der Standard, den die einschlägigen Werkzeuge bereits sprechen. OPAA
baut deshalb **keine eigene Erweiterung je Werkzeug**, sondern einen MCP-Server, den alle
gleichermaßen benutzen: Claude Code, Cursor, OpenCode, VS Code mit Copilot, Automatisierungswerkzeuge
und jedes eigene Skript mit einer MCP-Bibliothek.

**Transport ist Streamable HTTP** (Spezifikationsstand 2026-07-28), nicht stdio. Ein stdio-Server
müsste auf jedem Arbeitsplatz installiert werden und hätte dort ein eigenes Zugangsmerkmal zu
verwahren; ein HTTP-Server liegt beim Backend, wird einmal betrieben und einmal aktualisiert.

**Authentifizierung ist das Zugangstoken**, als Bearer-Merkmal. OAuth 2.1 mit
Autorisierungsserver und dynamischer Client-Registrierung — das, was die Spezifikation für fremd
betriebene Verbraucherdienste vorsieht — ist bewusst **zurückgestellt**: Es setzt voraus, dass OPAA
aus dem offenen Netz erreichbar ist, und genau das ist nicht die Betriebsform, die dieses Produkt
anstrebt. Der Weg dorthin bleibt offen, ist aber eine eigene Entscheidung mit eigener Begründung.

**Der Server ist eine dünne Schicht.** Er übersetzt Werkzeugaufrufe in Aufrufe derselben
Domain-Dienste, die die Web-Oberfläche benutzt. Er hält keinen eigenen Index, keine eigene
Rechtelogik und keinen eigenen Zugriff auf den Vektorspeicher. Diese Invariante ist die technische
Kernaussage dieser Spezifikation: **Es gibt genau einen Weg zu den Daten, und er prüft Rechte.**

### Die Werkzeuge

| Werkzeug | Zweck |
|---|---|
| `search` | Frage → Trefferliste mit Kennungen, Titeln, Auszügen, Herkunft |
| `fetch` | Trefferkennung → Abschnitt mit Kontext, auf Wunsch das ganze Dokument |
| `list_libraries` | effektive Sicht des Tokens |

`search` und `fetch` tragen bewusst diese Namen und diesen Zuschnitt: Sie sind die Signatur, die
gängige Assistenzwerkzeuge als Wissensquelle erkennen. Das kostet nichts — es ist derselbe Zuschnitt,
den der Kanal ohnehin braucht — und erspart jedem Haus, das später einen weiteren Client anschließt,
eine Sonderlocke. **Daraus folgt keine Zusage, dass OPAA an einen fremd betriebenen Dienst
angeschlossen wird**; dafür wäre die öffentliche Erreichbarkeit nötig, die dieses Dokument
ausschließt.

### Die Beschreibungen entstehen je Verbindung neu

Ein fremdes Modell entscheidet allein anhand der Werkzeugbeschreibung, ob eine Frage hierher gehört.
„Sucht in Wissensbibliotheken" beantwortet diese Frage nicht; „Sucht in den Beständen *Baugenehmigungen
2024* und *Vergaberecht* dieser Behörde" beantwortet sie. Die Beschreibungen von `search`, `fetch` und
`list_libraries` werden deshalb **zur Verbindungszeit aus Namen und Beschreibungen der Bibliotheken
der effektiven Sicht des Tokens erzeugt** — also je Person verschieden, nicht nur je Installation.

Das ist keine zusätzliche Auskunft: Die Namen stehen ohnehin in `list_libraries`, das dasselbe Token
jederzeit aufrufen kann. Es ist dieselbe Information, nur an der Stelle, an der das fremde Modell
sie tatsächlich liest. Ändert sich die effektive Sicht, gilt die neue Beschreibung mit der nächsten
Verbindung; der Rechtefilter in der Suche hängt davon nicht ab und wirkt bei jedem Aufruf.

Der **Einleitungstext** des Servers (`instructions` der Initialisierung) kommt aus der
Systemkonfiguration und ist für die ganze Installation gleich — er sagt dem fremden Modell, wann es
den Kanal überhaupt in Betracht ziehen soll und dass es seine Antworten mit Fundstellen zu belegen
hat.

### Protokollfassungen und ihr Wechsel

Der SSE-Transport der Vorgängerfassung ist bereits abgekündigt; die Fassung, die dieser Kanal spricht,
wird dasselbe Schicksal haben. Daraus folgen drei Festlegungen, die
[ADR-0035](../decisions/0035-fremdzugaenge-mcp-server-und-zugangstokens.md) ausformuliert: welche
Fassungen der Server aushandelt und ankündigt, wie er sich gegenüber einem Client mit älterer oder
neuerer Fassung verhält, und wie lange eine abgekündigte Fassung weiter bedient wird. Dazu gehört, dass die Pflege
der Fassungsfolge und der vier Client-Anleitungen im Handbuch eine benannte Zuständigkeit hat.

Das Störungsbild ist eigen: Weil es genau einen Server gibt, fallen bei einem Fassungsbruch **alle
Fremdzugänge des Hauses zugleich** aus, und die Meldung sieht die Person in ihrem Client („MCP server
failed") — eine Meldung, die OPAA nicht formuliert hat und nicht beeinflussen kann. Das
Handbuchkapitel führt dieses Bild in der Störungssuche, damit das Ticket beim Betrieb nicht als
Einzelfall untersucht wird.

### Einrichtung im Client

Die Einrichtung ist bewusst so knapp, dass eine Person sie ohne Betriebsunterstützung schafft: Adresse
des Servers, Zugangstoken, fertig. Das Handbuch führt die Schritte je Client aus — einschließlich
der Frage, wohin der Tokenwert **nicht** gehört: nicht in eine Datei, die in einem Repository landet,
und nicht in ein Profil, das über Geräte hinweg synchronisiert wird.

```
Adresse:  https://opaa.<behörde>.de/mcp
Merkmal:  Authorization: Bearer opaa_pat_…
```

---

## Kontingente und der Abflussalarm

Ein Skript stellt in einer Minute mehr Anfragen als ein Mensch an einem Tag. Der Fremdzugang bekommt
deshalb ein **Kontingent je Token** — nicht nur je Netzadresse, wie es die bestehenden Grenzen tun.
Der Grund steht schon in [user-frontends.md](./user-frontends.md#authentifizierung-und-zugang): Hinter
einem gemeinsamen Ausgangspunkt im Behördennetz teilen sich alle dieselbe Adresse; ein Kontingent je
Adresse trifft dann die Falschen.

**Das Kontingent ist eine Lastbremse, kein Schutz vor Massenabfluss.** Diese Ehrlichkeit gehört
hierher, weil die Gegenrechnung jeder anstellen kann: Ein konservativer Wert von 60 Anfragen je
Stunde ergibt über die Höchstlaufzeit eines einzigen Tokens — 90 Tage nach dem Vorgabewert der
Obergrenze — sechsstellig viele Abrufe. Was in einer
Nacht nicht geht, geht in neunzig Tagen. Der Kanal verhindert Massenabfluss nicht — er macht ihn
langsam, und er macht die Entscheidung, welcher Bestand überhaupt erreichbar ist, zu einer
zurechenbaren Handlung. Mehr ist es nicht, und mehr soll hier auch nicht behauptet werden.

Der Vorgabewert ist bewusst konservativ; die Systemverwaltung kann ihn anheben. Ein überschrittenes
Kontingent führt zu einer klaren Ablehnung, nicht zu einer langsamen Antwort. Ein Kontingent ist
**keine Nutzungsstatistik**: Es zählt in einem gleitenden Fenster und wird nicht historisiert, nicht
je Person ausgewertet und nicht angezeigt. Eine Kontingentablehnung wird **nicht je Token
festgehalten** — sie wäre dort ein Verhaltensdatum je Person.

**Der Abflussalarm ist ein Sicherheitsereignis — und liegt deshalb außerhalb des
Nachweisprotokolls.** Weil Verhinderung ausscheidet und Entdeckung nicht ausscheiden darf, zählt die
Installation die Abrufe des ganzen Kanals in einem Fenster **im Arbeitsspeicher** und meldet der
Systemverwaltung genau dann etwas, wenn eine Schwelle überschritten ist. Das ist derselbe Zuschnitt,
den [security-and-compliance.md](./security-and-compliance.md#was-ausdrücklich-nicht-protokolliert-wird)
für fehlgeschlagene Anmeldungen bereits gewählt hat: Der Alarm selbst ist ein Sicherheitsereignis und
gehört in das Sicherheitsmonitoring, nicht in die geschlossene Ereignisliste; in das Nachweisprotokoll
gelangt allein die **daraus folgende Sperre** des Tokens als Zustandsänderung. Was dabei entsteht und
was nicht:

- **Eine Meldung an die Systemverwaltung bei Überschreitung**, mit dem Zeitfenster und der
  Token-Kennung — dazu ein Eintrag im technischen Anwendungslog mit kurzer Frist und ohne
  Auswertungsoberfläche, der zusätzlich Schwelle und gemessenen Wert trägt. Kein Verlauf, keine
  Zeitreihe, keine Kurve, kein Bericht — die Zählung selbst lebt im Speicher und ist nach dem
  Fenster weg. Eine Beruhigungsfrist verhindert, dass ein anhaltender Vorgang in eine Ereignisreihe
  zerfällt, die faktisch ein Verlauf wäre.
- **Die Meldung wird höchstens 14 Tage aufbewahrt**, gelesen oder nicht, und dann gelöscht. Eine
  Meldung, die niemand lesen kann, ist keine; eine Meldung, die stehen bleibt, ist nach
  Zeitpunkt sortiert genau der Verlauf je Token, den dieses Kapitel ausschließt. Die Frist ist
  deshalb eine Konstante und keine Einstellung — sie ist kein Betriebsparameter, sondern die
  Grenze, die den Alarm mit „kein Verlauf" vereinbar macht. Aus demselben Grund steht der
  **gemessene Wert nicht in der Meldung**: Die Zahl wäre es, die eine Folge aufbewahrter Meldungen
  zu einer Nutzungskurve machte.
- **Die Token-Kennung führt zur Person, und das gehört gesagt.** Wer die Meldung erhält, kann sie in
  der Tokentabelle nachschlagen; ein Alarm, nach dem niemand handeln kann, ist keiner. Die Meldung
  steht deshalb unter derselben Zweckbindung wie der übrige Nachweisbestand: Vorfall und Sperre, nicht
  arbeitsrechtliche, disziplinarische oder leistungsbezogene Fragen. Sie wird nicht zu einer Auswertung
  je Person zusammengeführt, und sie wird nicht dauerhaft aufbewahrt, sondern höchstens 14 Tage.
- **Kein zweiter Zweck.** Der Alarm dient dem Vorfall, nicht dem Einstieg in eine Nutzungsbeobachtung.
  Er löst aus, wenn ein Mehrfaches des üblichen Kanalaufkommens erreicht ist, nicht bei fleißiger
  Arbeit.

Ohne ihn lautet die Antwort auf die Frage einer Aufsichtsbehörde „welche Daten sind abgeflossen?"
im Ernstfall: „Wir wissen, dass ein Token an einem Tag benutzt wurde." Das ist als Meldung nicht
ausreichend. Mit ihm lautet sie: „Am 14.11. um 02:40 überschritt der Kanal die Schwelle; der Zugang
wurde um 08:05 gesperrt." Das ist wenig, aber es ist ein Anfang, und es kostet keine Zeile im
Nachweisprotokoll.

---

## Protokollierung und Mitbestimmung

Protokollpflichtig ist genau das, was die Reichweite eines Zugriffs ändert. Die **geschlossene Liste**
aus [security-and-compliance.md](./security-and-compliance.md#die-ereignisse-der-ersten-stufe) wird
dafür an drei Stellen **erweitert** — die Liste zählt einzeln auf, und was dort nicht steht, wird
nicht geschrieben; eine Behauptung, man fülle nur vorhandene Punkte aus, wäre an dieser Stelle
unehrlich. Die drei Ergänzungen sind im selben Zug dort eingetragen:

- die **Fremdzugangsfreigabe einer Wissensbibliothek** — sie steht neben `visibility` und `listed`,
  weil sie dieselbe Art Reichweitenfeld ist;
- das **Außerkrafttreten eines Zugangstokens** — es steht neben Ausstellung und Widerruf, die der
  Punkt zu den API-Tokens bereits nennt;
- die **Kanaleinstellungen des Fremdzugangs** — sie stehen neben der Freigabe-Obergrenze, die
  ebenfalls einen eigenen Punkt hat, weil beide über die Reichweite eines Bestands entscheiden.

| Ereignis | Eintrag |
|---|---|
| Zugangstoken ausgestellt | Person (als Pseudonym), **Token-Kennung** (nicht der Name), Bibliotheksauswahl, Ablauf |
| Zugangstoken widerrufen oder gesperrt | dazu, ob durch die Person selbst oder durch die Systemverwaltung |
| Zugangstoken außer Kraft getreten | Anlass: abgelaufen oder Kontenlebenszyklus (Konto gesperrt, deaktiviert, gelöscht) |
| Bibliotheksfreigabe gesetzt oder zurückgenommen | handelnde Person, Bibliothek, Richtung, Ablaufdatum der Freigabe |
| Bibliotheksfreigabe erloschen oder ausgesetzt | Bibliothek, Anlass: Fristablauf — oder gesenkte Freigabe-Obergrenze, sobald #797 sie liefert |
| Schalter oder Grenzwerte des Kanals geändert | handelnde Person, Richtung bzw. Vorher/Nachher |

**Der Abflussalarm steht bewusst nicht in dieser Tabelle.** Er ist ein Sicherheitsereignis und gehört
in das Sicherheitsmonitoring, nicht in das Nachweisprotokoll — dieselbe Zuordnung, die
`security-and-compliance.md` für fehlgeschlagene Anmeldungen und abgewiesene Verbindungsversuche
bereits trifft. In das Nachweisprotokoll gelangt allein die **daraus folgende Sperre** des Tokens, und
die steht oben.

**Kein Freitext im Protokoll.** Der Tokenname ist Pflicht und von der Person frei formuliert; in
solche Felder geraten Vorgangsnummern, Projektkürzel, Gerätenamen und gelegentlich Personennamen.
`security-and-compliance.md` hat für lokale Konten bereits entschieden, dass in diesen Ereignissen
weder Adressen noch Namen noch Freitext stehen — hier gilt dasselbe. Das Protokoll trägt die
Token-Kennung; der Name steht in der Tokentabelle, wo ihn die Person und die Systemverwaltung sehen.
Für den Nachweis „welcher Zugang entstand wann, mit welchem Umfang" genügt die Kennung vollständig.

**Nicht protokolliert wird die einzelne Abfrage** — weder die Frage noch die Suchbegriffe, der
angewandte Suchbereich, die Trefferzahl oder die abgerufenen Dokumente. Das ist Verhalten, nicht
Zugriffsänderung, und in der Menge ergibt es das Tätigkeitsprofil, das die Mitbestimmungsfähigkeit
ausschließt. Für den Fremdzugang gilt hier nichts anderes als für die Web-Oberfläche; ein Kanal, in
dem plötzlich doch mitgeschrieben würde, wäre der Einstieg, den die Zusage ausschließt.

**Auch die Ausstellungsereignisse ergeben in der Menge ein Bild.** Über drei Jahre zeigt die Folge
der Einträge je Pseudonym, welche Fachbestände eine Person wann für welches Werkzeug brauchte und
wann sie damit aufhörte. Das ist als Zugriffsänderung richtig protokolliert — es ist dieselbe Art
Eintrag wie eine Rechtevergabe — und es unterliegt genau deshalb denselben Regeln wie der übrige
Protokollbestand: Vier-Augen-Prinzip beim Zugriff, Zweckausschluss für arbeitsrechtliche Fragen,
dieselbe Frist, dieselbe Pseudonymisierung.

Die Prüfbarkeit hängt nicht an den Abfragen: Die Frage „wer konnte wann worauf zugreifen?"
beantwortet die **Rechtehistorie** — die Grants, die Gruppenmitgliedschaften, die Reichweitenfelder
und, seit dieser Stufe, die Fremdzugangsfreigabe — zusammen mit den Ereignissen oben. Sie halten
fest, wann ein Fremdzugang entstand, welchen Umfang er hatte und wann er endete. Was **über ihn
tatsächlich gelesen** wurde, hält OPAA nicht fest; siehe
[Abwägungen](#abwägungen-und-verworfene-alternativen).

---

## Leitplanken der Stakeholder-Perspektiven

Aus dem Recherchebericht übernommen und nach den Bewertungen vom 18.09.2026 nachgezogen; jede
Leitplanke ist an genau eine Festlegung oben gebunden.

| Perspektive | Leitplanke | Wo sie eingelöst wird |
|---|---|---|
| **Personalrat** | Keine Zählung, keine Abfrageprotokolle, kein Nutzungsdatum und kein Personenfilter in fremder Sicht, benannte Löschfristen, Aufklärung an der Stelle der Handlung, Beteiligung vor dem Einschalten | [Protokollierung](#protokollierung-und-mitbestimmung), [Was von einem Token übrig bleibt](#was-von-einem-token-übrig-bleibt), [Schalter](#der-schalter-der-installation) |
| **Betriebsverantwortlicher** | Standard aus, ein Notaus, der je Aufruf wirkt, Freigabe als historisiertes Reichweitenfeld, ein Alarm statt eines leeren Schutzversprechens | [Freigabe](#die-freigabe-ist-ein-reichweitenfeld-und-wird-wie-eines-behandelt), [Schalter](#der-schalter-der-installation), [Kontingente](#kontingente-und-der-abflussalarm) |
| **Referatsleitung** | Die Entscheidung, ob ein Bestand das Haus verlassen darf, trifft, wer den Bestand verantwortet — und er sieht, wie viele Zugänge sie in Anspruch nehmen, und trifft sie jedes Jahr neu | [Freigabe der Bibliothek](#die-freigabe-der-bibliothek) |
| **Skeptiker** | Kein Dauerschlüssel — weder beim Token (Pflichtablauf, unveränderliche Auswahl) noch bei der Freigabe (Pflichtbefristung, kein Wiederaufleben) | [Zugangstokens](#zugangstokens), [Freigabe](#die-freigabe-der-bibliothek) |
| **KI-Champion** | Einrichtung in wenigen Minuten, ohne Betriebsticket, mit einer Anleitung je verbreitetem Client — und ehrlich darüber, dass zwei Freigaben davor liegen | [Einrichtung im Client](#einrichtung-im-client), [Erfolgs-Metriken](#erfolgs-metriken) |
| **Sachbearbeiter** | Verständliche Begriffe, eine Liste, in der erkennbar ist, was man widerrufen darf, und ein klarer Satz dazu, was der Kanal nicht zusagt | Tokenname ist Pflicht, ausgesetzte Auswahl wird angezeigt, [Hinweis beim Erzeugen](#beim-erzeugen-wird-gesagt-was-der-kanal-nicht-zusagt) |

---

## Abwägungen und verworfene Alternativen

**Kein Nachweis der tatsächlich abgerufenen Fundstellen.** Die Referatsleitung hat dafür den härtesten
Fall: Ein Bescheid beruht auf drei Fundstellen, die ein Assistenzwerkzeug fehlerhaft zusammengefasst
hat, und landet vor Gericht. Wer erklären muss, worauf der Bescheid beruht, hätte gern eine Liste der
geladenen Fundstellen. Sie wird bewusst **nicht** geführt: Ein Protokoll der abgerufenen Dokumente je
Person ist genau das Tätigkeitsprofil, dessen Nichtexistenz die Mitbestimmungsfähigkeit dieses
Produkts trägt — und es entstünde ausgerechnet dort zuerst, wo der Kanal am ehesten benutzt wird. Der
Zielkonflikt bleibt bestehen und wird hier zugunsten der Nichtprotokollierung entschieden, mit zwei
Erwägungen: Für die Web-Oberfläche gilt dieselbe Zusage, ohne dass die Nachweispflicht daran
gescheitert wäre; und die zweite Verarbeitungsstufe, um die es eigentlich geht, ist das fremde
Werkzeug — **es führt sein eigenes Protokoll**, und wer den Weg eines Bescheids rekonstruieren muss,
führt ihn dort, nicht hier. Was OPAA belegt, ist die Rechtelage: wer wann worauf zugreifen durfte.

**Kein Wiederaufleben, keine nachträgliche Rückholung.** Eine zurückgenommene Freigabe sperrt künftige
Zugriffe. Was bereits in ein fremdes Werkzeug geholt wurde — Chatverlauf, Kontextfenster,
Zwischenspeicher eines Anbieters —, bleibt dort. „Freigeben" ist deshalb faktisch unumkehrbar, sobald
einmal abgerufen wurde, auch wenn die Oberfläche später „aus" zeigt. Das ist kein Mangel des Kanals,
sondern seine Natur, und es ist der Grund für die Pflichtbefristung: Die teure Entscheidung soll
bewusst und wiederholt getroffen werden.

**Keine personenbezogene Freigabe.** „Frau X darf das über ihr Werkzeug nutzen, der Praktikant nicht"
gibt es nicht. Wer die Bibliothek lesen darf und sie freigegeben ist, kann sie in ein eigenes Token
aufnehmen. Eine zweite, personenbezogene Rechteebene neben den Leserechten wäre eine zweite
Rechtewahrheit — genau das, was dieser Kanal an jeder anderen Stelle vermeidet. Wer die Auswahl
enger ziehen will, zieht die Leserechte enger.

**Das Freigaberecht bleibt bei der Verwalter-Rolle plus Systemverwaltung.** Der Vorschlag, die
Entscheidung an die Referatsleitung als zusätzliche Genehmigungsstufe zu binden, ist abgelehnt: Wer
die Verwalter-Rolle an einer Bibliothek trägt, ist im Rechtemodell dieses Produkts der fachlich
Verantwortliche; eine zweite Stufe daneben verschöbe die Verantwortung, statt sie zu klären. Die
Pflichtbefristung, die Historisierung und die Anzeige der Tokenzahl sind die Antwort auf das
dahinterliegende Anliegen — dass die Entscheidung sichtbar bleibt und wiederkehrt.

**Kein „Freigabe beantragen"-Fluss.** Wer eine Bibliothek freigegeben haben möchte, spricht den
Verantwortlichen an. Ein Antragsweg mit Benachrichtigung und Anfrageliste wäre ein eigenes
Workflow-System; die Oberfläche sagt stattdessen im Leerzustand der Bibliotheksauswahl, **wen** man
anzusprechen hat.

**Keine Netzbeschränkung je Token.** Eine CIDR je Token ist für einen Arbeitsplatzclient hinter
wechselnden Adressen unbrauchbar, und sie wäre zugleich ein Anwesenheitsmerkmal: Gesetzt, scheitert
jeder Aufruf aus der Heimarbeit, und die Abweisung entstünde als Ereignis irgendwo im Betrieb —
`security-and-compliance.md` hat die Unterscheidbarkeit von Dienststelle und Heimarbeit ausdrücklich
als solches eingestuft. Die Einschränkung gehört deshalb an den Kanal, nicht an die Person. Für
Fachverfahren mit fester Adresse kommt sie mit den Service-Accounts wieder.

**Kein Nutzungsdatum in der Verwaltungssicht.** „Zuletzt benutzt" ist in der Selbstsicht eine Hilfe
und in einer nach Person filterbaren Tabelle ein Auswertungspfad: Eine Führungskraft bittet um „eine
Übersicht, wer die KI-Anbindung überhaupt nutzt", und nach einem halben Jahr monatlicher Abschriften
existiert eine Aktivitätsreihe je Beschäftigtem — ohne dass das Produkt dafür irgendetwas speichern
musste. Die Sperrentscheidung braucht den Wert nicht.

**Keine automatische Sperre ungenutzter Tokens.** Sie wäre die naheliegende Aufräumhilfe, setzt aber
voraus, dass die Nichtbenutzung je Token dauerhaft beobachtet wird. Der Pflichtablauf leistet
dasselbe ohne Beobachtung: Ein ungenutztes Token verschwindet spätestens nach 90 Tagen von allein.

---

## Integrationspunkte

- **[access-control.md](./access-control.md#api-tokens-und-service-accounts)** — Rechtemodell, an das
  der Fremdzugang gebunden ist, der Kontenlebenszyklus, dem die Tokens folgen, die Freigabe-Obergrenze
  konnektor-gespeister Bibliotheken und die Netzbereichseinschränkung
- **[user-frontends.md](./user-frontends.md#rest-api)** — die REST-API, deren Such- und
  Abrufendpunkt dieser Kanal mitbringt, und die kanalübergreifenden Eigenschaften
- **[security-and-compliance.md](./security-and-compliance.md)** — geschlossene Ereignisliste, die
  Historisierung der Reichweitenfelder und die Nichtprotokollierung von Abfragen
- **[spaces-and-assets.md](./spaces-and-assets.md#eigentümerschaft-und-verwaisung)** — „Nachfolge
  offen" und die eingefrorene Reichweite, die eine Freigabe ausschließt
- **[agents-and-tools.md](./agents-and-tools.md#mcp-als-standardisierte-anbindung)** — die
  **Gegenrichtung**: OPAA als MCP-*Client*, der fremde Werkzeuge einbindet. Bleibt Phase 2 und ist
  hier nicht gemeint
- **[hybrid-retrieval.md](./hybrid-retrieval.md)** und
  **[retrieval-algorithm.md](./retrieval-algorithm.md)** — der Suchweg, den die Werkzeuge benutzen,
  unverändert
- **[ADR-0035](../decisions/0035-fremdzugaenge-mcp-server-und-zugangstokens.md)** — Transport,
  Authentifizierungsverfahren, Freigabemodell, der eine Zugriffsweg und der Umgang mit dem
  Fassungswechsel der MCP-Spezifikation
- **[ADR-0033](../decisions/0033-lokale-benutzerverwaltung.md)** — Ausstellung, Widerruf und die
  Mailwege, auf denen die Erinnerungen laufen
- **[ADR-0005](../decisions/0005-authentication-strategy.md)** — Betriebsmodi der Authentifizierung;
  der Fremdzugang ist ein zusätzlicher Prüfweg, kein zusätzlicher Betriebsmodus

---

## Offene Fragen / Zukünftige Erweiterungen

- **Tokens anlegen bei geschlossenem Kanal** — erlaubt mit Hinweis (Annahme) oder gesperrt? Die
  Antwort hängt daran, ob eine Behörde den Kanal typischerweise vor oder nach der Einrichtung öffnet.
- **Der Vorgabewert des Kontingents** ist geraten, solange er nicht einmal gemessen wurde: Wie oft ein
  Assistenzwerkzeug je Arbeitsschritt `search` und `fetch` aufruft, weiß man erst im Betrieb — und
  weil nicht gezählt wird, auch dann nicht von allein. Eine einmalige Messung in einer
  Pilotinstallation, bevor der Wert festgeschrieben wird.
- **OAuth 2.1 mit Autorisierungsserver und dynamischer Client-Registrierung** — zurückgestellt, nicht
  verworfen. Er wird gebraucht, sobald ein fremd betriebener Dienst angeschlossen werden soll; das
  ist dann zugleich eine Entscheidung über die öffentliche Erreichbarkeit der Installation.
- **Service-Accounts als eigene Identität** (ohne interaktive Anmeldung), wie sie
  [access-control.md](./access-control.md#api-tokens-und-service-accounts) beschreibt. Diese Stufe
  kennt nur persönliche Tokens; ein Fachverfahren hinge damit am Konto einer Person, was genau der
  Prüfungsbefund ist, den Service-Accounts auflösen sollen. Bis dahin rät das Handbuch davon ab.
- **Schreibende Werkzeuge** — ein Fremdzugang, der indizieren oder hochladen darf, ist betrieblich
  etwas anderes als einer, der nur fragt. Bewusst außerhalb.
- **Ein Werkzeug, das Antworten erzeugt** (`ask` statt `search`), würde den Modellverbrauch der
  Installation an ein fremdes Werkzeug hängen. Erst sinnvoll, wenn Bedarf belegt ist.
- **Ausleitung an ein zentrales Sicherheitsmonitoring** — folgt der allgemeinen SIEM-Anbindung, nicht
  diesem Kanal. Ausdrücklich festgehalten: Eine Ausleitung der Ereignisse **dieses** Kanals ist damit
  nicht mitentschieden. Sie bedarf einer eigenen Vereinbarung, sonst wird an dieser Stelle später
  ausgehöhlt, was hier zugesagt ist. Das gilt auch für den Abflussalarm: Er ist ein
  Sicherheitsereignis und gehört seiner Art nach dorthin, wird bis zur SIEM-Anbindung aber nur als
  Meldung an die Systemverwaltung und als Eintrag im technischen Anwendungslog zugestellt.

---

## Erfolgs-Metriken

- **Ab freigeschaltetem Kanal und freigegebener Bibliothek** richtet eine Person einen Client ohne
  Betriebsunterstützung ein und erhält den ersten belegten Treffer in unter zehn Minuten. Das ist
  eine **Abnahmebeobachtung**, keine im Produkt erhobene Messung — eine gemessene Einrichtungsdauer
  je Person wäre eine Nutzungsstatistik. Die beiden Freigaben davor sind ausdrücklich nicht in dieser
  Zeit enthalten; sie sind organisatorische Entscheidungen, und das Handbuch sagt, an wen man sich
  dafür wendet.
- Anteil der freigegebenen Bibliotheken an allen Bibliotheken bleibt klein und bewusst gewählt — eine
  Installation, in der alles freigegeben ist, hat die Freigabe nicht verstanden. Durchgesetzt wird
  das nicht durch Hoffnung, sondern durch die Pflichtbefristung: Jede Freigabe läuft aus und muss von
  dem erneuert werden, der den Bestand verantwortet.
- Kein Vorfall, in dem ein Token mehr gesehen hat als seine Person zum Anfragezeitpunkt durfte. Diese
  Größe wird nicht beobachtet, sondern **getestet**: Sie hängt an den Prüfungen, die jeden der vier
  Faktoren einzeln entziehen, nicht an Betriebsdaten, die es bewusst nicht gibt.
