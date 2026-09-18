# Fremdzugänge: Zugangstokens und MCP-Server

> **Status: Entwurf.** Beschlossen am 18.09.2026 auf Grundlage der Recherche „OPAA als Wissensschicht
> für andere KI-Tools". Umgesetzt wird sie in Epic
> [#1715](https://github.com/criew/opaa/issues/1715); gebaut ist davon noch nichts. Der
> Entwurfshinweis entfällt, wenn der Maintainer die Spezifikation nach der Umsetzung abnimmt.

## Motivation

Beschäftigte arbeiten zunehmend in KI-Werkzeugen, die OPAA nicht kennt — einer Entwicklungsumgebung
mit Assistent, einem Automatisierungswerkzeug, einem eigenen Skript. Diese Werkzeuge haben Zugriff
auf das offene Netz, aber nicht auf das Wissen des Hauses. Wer sie trotzdem für eine fachliche Frage
benutzt, arbeitet entweder ohne die einschlägigen Unterlagen oder kopiert sie dorthin hinein. Das
erste ist fachlich schlecht, das zweite ist der Schatten-KI-Pfad, den OPAA gerade schließen soll.

Der Ausweg ist nicht, jedes dieser Werkzeuge nachzubauen, sondern **OPAA für sie erreichbar zu
machen**: Das fremde Werkzeug bleibt, wo es ist, und holt sich seine Belege aus dem Bestand des
Hauses — mit den Rechten der Person, die es benutzt, und nur aus den Beständen, die dafür
ausdrücklich freigegeben sind.

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
   Rechten der Person — zur Anfragezeit ausgewertet, nie eingefroren.
3. **Ein Zugangstoken kann nie mehr als seine Person.** Es ist ein Ausschnitt ihrer Rechte, kein
   eigener Rechteträger; verliert die Person ein Recht, verliert es das Token in derselben Sekunde.
4. **Fremdzugänge lesen und suchen — mehr nicht.** Kein Verwalten, kein Indizieren, kein Hochladen,
   kein Rechtevergeben. Der Rechteumfang ist fest und nicht wählbar.
5. **Dieselben Domain-Dienste wie die Web-Oberfläche.** Der Suchendpunkt und die MCP-Werkzeuge rufen
   den Dienst hinter `POST /api/v1/query` auf; es gibt keinen zweiten Weg an den Index. Die
   Rechteprüfung sitzt in der Suche, nicht davor.
6. **Die einzelne Abfrage wird nicht protokolliert.** Protokollpflichtig sind die
   Zugriffsänderungen — Token ausstellen, sperren, freigeben, schalten —, nicht das Verhalten. Die
   Zusage aus [security-and-compliance.md](./security-and-compliance.md#was-ausdrücklich-nicht-protokolliert-wird)
   bleibt unverändert; auch eine Nutzungszählung je Token gibt es nicht.
7. **OPAA wird dafür nicht öffentlich erreichbar.** Zielclients sind Werkzeuge, die auf dem
   Arbeitsplatz oder im Hausnetz laufen. Ein Betrieb als OAuth-Autorisierungsserver für fremde
   Cloud-Dienste ist ausdrücklich nicht Gegenstand dieser Stufe.
8. **Der Installationsschalter ist zugleich der Notaus.** Ein Ausschalten wirkt sofort für alle
   Tokens, vernichtet sie aber nicht — Wiedereinschalten stellt den vorherigen Zustand her.

---

## Abgrenzung: was dieser Kanal ist und was nicht

| | |
|---|---|
| **Ist** | Ein lesender Zugang für Werkzeuge, die eine Person auf ihrem Arbeitsplatz oder im Hausnetz betreibt: Entwicklungsassistenten, Automatisierungswerkzeuge, eigene Skripte, ein Fachverfahren im selben Netz |
| **Ist nicht** | Ein Zugang für fremd betriebene Verbraucherdienste. OPAA wird dafür nicht ins offene Netz gestellt, betreibt keinen Autorisierungsserver und kennt keine dynamische Client-Registrierung |
| **Ist nicht** | Eine zweite Assistenzoberfläche. Fremdzugänge liefern Fundstellen, keine erzeugten Antworten; die Generierung findet im fremden Werkzeug statt, mit dessen Modell |
| **Ist nicht** | Die Gegenrichtung. Dass OPAA selbst fremde MCP-Server als Werkzeuge einbindet, ist ein anderes Thema und bleibt bei den Agenten (siehe [agents-and-tools.md](./agents-and-tools.md#mcp-als-standardisierte-anbindung), Phase 2) |
| **Ist nicht** | Ein modellkompatibler Chat-Endpunkt. Ein OpenAI-förmiges `/v1/chat/completions` wird bewusst **nicht** gebaut: Es würde die Belege auf ein Textfeld reduzieren und die Modellvorgaben der Systemverwaltung umgehen |

---

## Der Schalter der Installation

Der Fremdzugang ist eine Einstellung der Systemverwaltung, installationsweit, **Standard aus**.

```
Systemkonfiguration → Fremdzugänge
  [ ] Fremdzugänge erlauben                          (aus)
      Ablauf-Obergrenze für Zugangstokens:  90 Tage
      Kontingent je Token:                  … Anfragen je Stunde
```

**Solange er aus ist**, verhält sich die Installation, als gäbe es den Kanal nicht: Der MCP-Endpunkt
antwortet nicht mit einer fachlichen Fehlermeldung, sondern gar nicht erst wie ein vorhandener Dienst
(404 bzw. 503 — die genaue Wahl trifft die Umsetzung, siehe [Offene Fragen](#offene-fragen--zukünftige-erweiterungen)),
und jedes Zugangstoken wird zurückgewiesen. Vorhandene Tokens bleiben dabei **bestehen**. Das ist
Absicht: Der Schalter ist der Notaus für den Störungsfall, und ein Notaus, der die Einrichtung aller
Beschäftigten vernichtet, wird im Zweifel nicht benutzt.

**Das Schalten selbst ist protokollpflichtig** — in beide Richtungen. Eine Installation, in der
niemand nachweisen kann, seit wann der Kanal offen stand, hat den Kanal nicht im Griff.

Ob Tokens auch bei ausgeschaltetem Kanal **angelegt** werden können, entscheidet die Umsetzung zu
Gunsten der Verständlichkeit: Die Oberfläche zeigt die Verwaltung dann mit einem deutlichen Hinweis,
dass der Kanal derzeit geschlossen ist (Annahme, siehe [Offene Fragen](#offene-fragen--zukünftige-erweiterungen)).

---

## Die Freigabe der Bibliothek

Die **Freigabe-Einheit ist die Wissensbibliothek**, nicht der Arbeitsraum. Der Arbeitsraum ist eine
Sicht auf Bibliotheken; Rechte hängen ohnehin an der Bibliothek, und eine Freigabe, die an einer
Sicht hängt, ließe sich durch eine zweite Sicht umgehen.

Jede Bibliothek trägt ein Merkmal **„darf über Fremdzugänge genutzt werden"**, Standard **aus**.
Gesetzt wird es von dem, der die Bibliothek verwaltet — derselbe Personenkreis, der auch Leserechte
an ihr vergibt. Das ist kein Zufall: Die Freigabe ist eine Erweiterung der Reichweite und damit
fachlich dieselbe Art von Entscheidung.

Die Änderung dieses Merkmals ist eine **Zugriffsänderung** und wird protokolliert, in beide
Richtungen, mit der handelnden Person und der betroffenen Bibliothek.

```
Bibliothek „Baugenehmigungen 2024"
  Zugriff
    Leserechte …
    [ ] Über Fremdzugänge nutzbar
        Wenn gesetzt, können Personen mit Lesezugriff diese Bibliothek in einem
        eigenen Zugangstoken auswählen und aus fremden Werkzeugen darin suchen.
```

Wird die Freigabe zurückgenommen, verschwindet die Bibliothek aus der effektiven Sicht **aller**
Tokens, die sie ausgewählt hatten — sofort und ohne dass die Tokens angefasst werden müssen. Die
Auswahl im Token bleibt gespeichert; sie wirkt wieder, wenn die Bibliothek erneut freigegeben wird.
Die Oberfläche zeigt eine so ausgesetzte Auswahl als solche an, statt sie stillschweigend
wegzulassen.

---

## Zugangstokens

Ein Zugangstoken ist ein persönliches Merkmal, das eine Person in ihren eigenen Einstellungen für ein
benanntes Werkzeug erzeugt. Es baut auf der Ausstellungs-, Widerrufs- und Sperrlisten-Mechanik der
lokalen Token ([ADR-0033](../decisions/0033-lokale-benutzerverwaltung.md)) auf — dieselbe Prüfkette,
dieselbe `jti`-Sperrliste, derselbe Sofortwiderruf.

### Eigenschaften

| Eigenschaft | Regel |
|---|---|
| **Name / Zweck** | Pflicht. „Claude Code auf dem Dienstrechner", nicht „Token 3". Der Name ist das Einzige, woran die Person in einem Jahr noch erkennt, was sie widerrufen darf |
| **Bibliotheken** | Konkrete Liste, Pflicht, mindestens eine. Auswählbar ist nur, was die Person selbst lesen darf **und** was freigegeben ist. **Keine Option „alle, auch künftige"** — ein Token, dessen Umfang später von allein wächst, ist nicht mehr das, was die Person erteilt hat |
| **Ablauf** | Pflicht. Kein Token ohne Ablaufdatum. Die Obergrenze setzt die Systemverwaltung installationsweit; Vorgabe 90 Tage |
| **Rechteumfang** | Fest: lesen und suchen. Kein Auswahlmenü. Ein Token erreicht ausschließlich die Such-, Abruf- und Auflistungswege, nie einen Verwaltungsweg |
| **Netzbereich** | Optional, als Einschränkung auf ein CIDR — sinnvoll für ein Fachverfahren mit fester Adresse, unbrauchbar für einen Arbeitsplatzclient hinter wechselnden Adressen (Annahme; wird nur gebaut, wenn es die Umsetzung nicht verteuert) |
| **Anzeige** | Erstellt am, läuft ab am, zuletzt benutzt am — **nur das Datum** |
| **Keine Zählung** | Es gibt keine Anzeige, wie oft ein Token benutzt wurde, und keine Statistik darüber. Eine Zählung je Person wäre ein Tätigkeitsprofil und damit genau das, was die Mitbestimmungsfähigkeit ausschließt |
| **Sichtbarkeit des Werts** | Genau einmal, unmittelbar nach dem Erzeugen. Danach nur noch ein Präfix zur Wiedererkennung. Kein Nachzeigen, kein Auslesen, auch nicht durch die Systemverwaltung |

### Lebenszyklus

```
Person                              Systemverwaltung
  │                                   │
  ├─ erzeugen  ─────────────────────► Protokolleintrag
  │   Name, Bibliotheken, Ablauf
  │   Wert einmal sichtbar
  │
  ├─ eigene Tokens sehen              ├─ alle Tokens sehen
  │   Name, Ablauf, Bibliotheken,     │   zusätzlich: wem sie gehören
  │   zuletzt benutzt                 │
  │                                   │
  ├─ eigenes Token widerrufen ──────► ├─ einzelnes Token sperren ──► Protokolleintrag
  │                                   ├─ alle Tokens einer Person sperren
  │                                   └─ Kanal abschalten (Notaus)
  │
  └─ Ablauf                           (wirkt ohne Zutun)
```

Ein Token folgt dem Lebenszyklus seiner Person: Wird ihr Konto gesperrt, deaktiviert oder gelöscht,
wirken ihre Tokens nicht mehr. Ein Token, das die Deaktivierung überdauert, wäre der bequemste Weg,
den Kontenlebenszyklus zu umgehen — dieselbe Regel gilt in
[access-control.md](./access-control.md#offboarding) bereits für alles andere.

Die Systemverwaltung sieht die Liste aller Tokens und kann sperren, aber sie sieht **keinen
Tokenwert** und **keine Nutzungshäufigkeit**. Ihre Sicht ist eine Bestandsliste für die
Rechteprüfung, kein Auswertungswerkzeug.

---

## Die effektive Sicht

```
        Rechte der Person            (was sie heute lesen darf)
                 ∩
        Freigabe der Bibliothek      (darf sie über Fremdzugänge genutzt werden?)
                 ∩
        Auswahl im Token             (hat die Person sie diesem Werkzeug erteilt?)
                 ∩
        Schalter der Installation    (ist der Kanal überhaupt offen?)
        ────────────────────────────
        = was dieses Token sieht
```

Ausgewertet wird **zur Anfragezeit**, bei jeder Anfrage neu. Nichts davon wird in das Token
eingefroren; das Token trägt die Auswahl als Referenz, nicht als Kopie der Rechtelage. Damit gilt die
Regel ohne Ausnahme: **Ein Token kann nie mehr als seine Person, und nie mehr, als beide Freigaben
zulassen.**

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
| **Abrufen** | Holt zu einer Trefferkennung den vollständigen Inhalt des Dokuments oder Abschnitts, damit das fremde Werkzeug im Kontext weiterarbeiten kann — beschränkt auf das, was die effektive Sicht enthält |

Der Suchweg ist derselbe wie der der Web-Oberfläche: Teilfragen, Vektor- und Volltextsuche, Fusion,
Reranking, Rechtefilter in der Suche. Was dort nicht gefunden wird, wird hier auch nicht gefunden;
was hier gefunden wird, hätte die Person auch dort gefunden. Ein zweiter Rankingpfad wäre eine
zweite Qualitätswahrheit, die niemand pflegt.

Dieser Suchweg wird zugleich als **regulärer REST-Endpunkt** angeboten (`POST /api/v1/search` samt
Dokumentabruf). Er ist nicht auf Tokens beschränkt — auch eine angemeldete Person erreicht ihn — und
schließt zugleich eine Lücke, die
[user-frontends.md](./user-frontends.md#was-die-schnittstelle-anbietet) heute ausdrücklich als
fehlend führt: „ein eigener Such-Endpunkt neben der Abfrage" und „das Abrufen eines einzelnen
Dokuments".

---

## Der MCP-Server

Das Model Context Protocol ist der Standard, den die einschlägigen Werkzeuge bereits sprechen. OPAA
baut deshalb **keine eigene Erweiterung je Werkzeug**, sondern einen MCP-Server, den alle
gleichermaßen benutzen: Claude Code, Cursor, OpenCode, VS Code mit Copilot, Automatisierungswerkzeuge
wie n8n und jedes eigene Skript mit einer MCP-Bibliothek.

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
| `fetch` | Trefferkennung → vollständiger Inhalt |
| `list_libraries` | effektive Sicht des Tokens |

`search` und `fetch` tragen bewusst diese Namen und diesen Zuschnitt: Sie sind die Signatur, die
gängige Assistenzwerkzeuge als Wissensquelle erkennen. Das kostet nichts — es ist derselbe Zuschnitt,
den der Kanal ohnehin braucht — und erspart jedem Haus, das später einen weiteren Client anschließt,
eine Sonderlocke. **Daraus folgt keine Zusage, dass OPAA an einen fremd betriebenen Dienst
angeschlossen wird**; dafür wäre die öffentliche Erreichbarkeit nötig, die dieses Dokument
ausschließt.

### Einrichtung im Client

Die Einrichtung ist bewusst so knapp, dass eine Person sie ohne Betriebsunterstützung schafft: Adresse
des Servers, Zugangstoken, fertig. Das Handbuch führt die Schritte je Client aus.

```
Adresse:  https://opaa.<behörde>.de/mcp
Merkmal:  Authorization: Bearer opaa_pat_…
```

---

## Kontingente

Ein Skript stellt in einer Minute mehr Anfragen als ein Mensch an einem Tag. Der Fremdzugang bekommt
deshalb ein **Kontingent je Token** — nicht nur je Netzadresse, wie es die bestehenden Grenzen tun.
Der Grund steht schon in [user-frontends.md](./user-frontends.md#authentifizierung-und-zugang): Hinter
einem gemeinsamen Ausgangspunkt im Behördennetz teilen sich alle dieselbe Adresse; ein Kontingent je
Adresse trifft dann die Falschen.

Zwei Zwecke fallen zusammen: Betriebsschutz (ein Werkzeug bremst die Installation nicht aus) und
Schutz vor **Massenabfluss** (der ganze Bestand lässt sich nicht in einer Nacht abziehen). Der
Vorgabewert ist bewusst konservativ; die Systemverwaltung kann ihn anheben. Ein überschrittenes
Kontingent führt zu einer klaren Ablehnung, nicht zu einer langsamen Antwort.

Ein Kontingent ist **keine Nutzungsstatistik**: Es zählt in einem gleitenden Fenster und wird nicht
historisiert, nicht je Person ausgewertet und nicht angezeigt.

---

## Protokollierung und Mitbestimmung

Protokollpflichtig ist genau das, was die Reichweite eines Zugriffs ändert. Alle vier Ereignisse
passen in die **geschlossene Liste** aus
[security-and-compliance.md](./security-and-compliance.md#die-ereignisse-der-ersten-stufe) — die Liste
wird dadurch nicht erweitert, sondern an der Stelle „Ausstellung und Widerruf von API-Tokens"
ausgefüllt:

| Ereignis | Eintrag |
|---|---|
| Zugangstoken ausgestellt | Person (als Pseudonym), Tokenname, Bibliotheksauswahl, Ablauf |
| Zugangstoken widerrufen oder gesperrt | dazu, ob durch die Person selbst oder durch die Systemverwaltung |
| Bibliotheksfreigabe gesetzt oder zurückgenommen | handelnde Person, Bibliothek, Richtung |
| Schalter der Installation ein- oder ausgeschaltet | handelnde Person, Richtung |

**Nicht protokolliert wird die einzelne Abfrage** — weder die Frage noch die Suchbegriffe, der
angewandte Suchbereich, die Trefferzahl oder die abgerufenen Dokumente. Das ist Verhalten, nicht
Zugriffsänderung, und in der Menge ergibt es das Tätigkeitsprofil, das die Mitbestimmungsfähigkeit
ausschließt. Für den Fremdzugang gilt hier nichts anderes als für die Web-Oberfläche; ein Kanal, in
dem plötzlich doch mitgeschrieben würde, wäre der Einstieg, den die Zusage ausschließt.

Die Prüfbarkeit hängt nicht daran: Die Frage „wer konnte wann worauf zugreifen?" beantwortet die
Rechtehistorie zusammen mit den vier Ereignissen oben — sie halten exakt fest, wann ein Fremdzugang
entstand, welchen Umfang er hatte und wann er endete.

---

## Leitplanken der Stakeholder-Perspektiven

Aus dem Recherchebericht übernommen; jede Leitplanke ist an genau eine Festlegung oben gebunden.

| Perspektive | Leitplanke | Wo sie eingelöst wird |
|---|---|---|
| **Personalrat** | Keine Zählung, keine Nutzungsstatistik, keine Abfrageprotokolle je Person | [Protokollierung](#protokollierung-und-mitbestimmung), Token-Eigenschaft „Keine Zählung" |
| **Betriebsverantwortlicher** | Standard aus, ein Notaus, der sofort und vollständig wirkt, und ein Kontingent gegen Massenabfluss | [Schalter](#der-schalter-der-installation), [Kontingente](#kontingente) |
| **Referatsleitung** | Die Entscheidung, ob ein Bestand das Haus verlassen darf, trifft, wer den Bestand verantwortet — nicht, wer ihn liest | [Freigabe der Bibliothek](#die-freigabe-der-bibliothek) |
| **Skeptiker** | Kein Dauerschlüssel: Ablauf ist Pflicht, Obergrenze systemweit, kein „alle Bibliotheken, auch künftige" | [Zugangstokens](#zugangstokens) |
| **KI-Champion** | Einrichtung in wenigen Minuten, ohne Betriebsticket, mit einer Anleitung je verbreitetem Client | [Einrichtung im Client](#einrichtung-im-client), Handbuchkapitel |
| **Sachbearbeiter** | Verständliche Begriffe und eine Liste, in der erkennbar ist, was man widerrufen darf | Tokenname ist Pflicht, ausgesetzte Auswahl wird angezeigt |

---

## Integrationspunkte

- **[access-control.md](./access-control.md#api-tokens-und-service-accounts)** — Rechtemodell, an das
  der Fremdzugang gebunden ist, und der Kontenlebenszyklus, dem die Tokens folgen
- **[user-frontends.md](./user-frontends.md#rest-api)** — die REST-API, deren Such- und
  Abrufendpunkt dieser Kanal mitbringt, und die kanalübergreifenden Eigenschaften
- **[security-and-compliance.md](./security-and-compliance.md)** — geschlossene Ereignisliste und die
  Nichtprotokollierung von Abfragen
- **[agents-and-tools.md](./agents-and-tools.md#mcp-als-standardisierte-anbindung)** — die
  **Gegenrichtung**: OPAA als MCP-*Client*, der fremde Werkzeuge einbindet. Bleibt Phase 2 und ist
  hier nicht gemeint
- **[hybrid-retrieval.md](./hybrid-retrieval.md)** und
  **[retrieval-algorithm.md](./retrieval-algorithm.md)** — der Suchweg, den die Werkzeuge benutzen,
  unverändert
- **[ADR-0033](../decisions/0033-lokale-benutzerverwaltung.md)** — Ausstellung, Widerruf und
  `jti`-Sperrliste, auf denen die Tokens aufsetzen
- **[ADR-0005](../decisions/0005-authentication-strategy.md)** — Betriebsmodi der Authentifizierung;
  der Fremdzugang ist ein zusätzlicher Prüfweg, kein zusätzlicher Betriebsmodus

---

## Offene Fragen / Zukünftige Erweiterungen

- **404 oder 503 bei ausgeschaltetem Kanal?** 404 verrät nicht, dass es den Dienst gibt; 503 ist für
  den Betrieb ehrlicher. Die Umsetzung entscheidet und hält es im Handbuch fest.
- **Tokens anlegen bei geschlossenem Kanal** — erlaubt mit Hinweis (Annahme) oder gesperrt? Die
  Antwort hängt daran, ob eine Behörde den Kanal typischerweise vor oder nach der Einrichtung öffnet.
- **OAuth 2.1 mit Autorisierungsserver und dynamischer Client-Registrierung** — zurückgestellt, nicht
  verworfen. Er wird gebraucht, sobald ein fremd betriebener Dienst angeschlossen werden soll; das
  ist dann zugleich eine Entscheidung über die öffentliche Erreichbarkeit der Installation.
- **Service-Accounts als eigene Identität** (ohne interaktive Anmeldung), wie sie
  [access-control.md](./access-control.md#api-tokens-und-service-accounts) beschreibt. Diese Stufe
  kennt nur persönliche Tokens; ein Fachverfahren hängt damit am Konto einer Person, was genau der
  Prüfungsbefund ist, den Service-Accounts auflösen sollen.
- **Schreibende Werkzeuge** — ein Fremdzugang, der indizieren oder hochladen darf, ist betrieblich
  etwas anderes als einer, der nur fragt. Bewusst außerhalb.
- **Ein Werkzeug, das Antworten erzeugt** (`ask` statt `search`), würde den Modellverbrauch der
  Installation an ein fremdes Werkzeug hängen. Erst sinnvoll, wenn Bedarf belegt ist.
- **Ausleitung der vier Ereignisse an ein zentrales Sicherheitsmonitoring** — folgt der allgemeinen
  SIEM-Anbindung, nicht diesem Kanal.

---

## Erfolgs-Metriken

- Eine Person richtet einen Client ohne Betriebsunterstützung ein und erhält den ersten belegten
  Treffer in unter zehn Minuten.
- Anteil der freigegebenen Bibliotheken an allen Bibliotheken bleibt klein und bewusst gewählt — eine
  Installation, in der alles freigegeben ist, hat die Freigabe nicht verstanden.
- Kein Vorfall, in dem ein Token mehr gesehen hat als seine Person zum Anfragezeitpunkt durfte.
