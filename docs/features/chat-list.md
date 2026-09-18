# Chatliste eines Space: finden, ordnen, wegräumen

> **Status: umgesetzt (Epic #1762, Phasen 2–4, 09/2026); Phase 5 nur bei gemeldetem Bedarf.**
> Die Maintainer-Entscheidungen stehen im Abschnitt [Entscheidungen](#entscheidungen); den gebauten
> Stand der Chatsuche beschreibt das Handbuch ([Suche](../handbuch/suche.md)).

## Motivation

Ein Chat ist in OPAA ein persistentes Objekt im Space ([spaces-and-assets.md](./spaces-and-assets.md#chats)).
Wer täglich mit dem Assistenten arbeitet, hat nach einigen Wochen Dutzende Chats in einem Space, nach
einem Jahr Hunderte — und mit Agentenläufen und dem Teilen von Chats werden es mehr. Heute ist die
Chatliste in der Seitenleiste eine einzige, nach letzter Änderung sortierte Liste der eigenen Chats.
Ein früheres Gespräch findet man nur durch Blättern, und woran man sich erinnert, ist selten der Titel:
Titel werden automatisch erzeugt, erinnert wird ein Aktenzeichen, ein Begriff, eine Fundstelle.

Drei Bedürfnisse sind zu decken:

- **Finden** — ein Gespräch wiederfinden, von dem man nur noch einen Begriff weiß.
- **Ordnen** — die zwei, drei Dauergespräche griffbereit halten und den Rest zeitlich einordnen.
- **Wegräumen** — erledigte Gespräche aus dem Blick nehmen, ohne sie zu löschen.

Die Grenze ist ebenso wichtig wie der Umfang: Die Chatliste bleibt ein Arbeitsmittel **der Person**.
Nichts davon darf eine Auswertung über Personen ermöglichen, und nichts darf die Existenz eines Chats
verraten, den die Person nicht sehen darf.

---

## Überblick

1. **Seitenleiste für den schnellen Griff.** Titelfilter beim Tippen, Zeitgruppen, eine Gruppe
   „Angeheftet" ganz oben.
2. **Eine Seite „Chats" je Space zum Verwalten.** Reiter „Aktiv" und „Archiv", Mehrfachauswahl,
   Inhaltssuche mit Trefferauszug. Die Seitenleiste ist für Auszüge und Auswahlkästchen zu schmal.
3. **Archivieren ist eine persönliche Ablage.** Der Chat verschwindet aus der Liste, bleibt lesbar und
   fortsetzbar; wer selbst weiterschreibt, holt ihn damit automatisch zurück.
4. **Die Chatsuche durchsucht Titel, Fragen und Antworten** der für die Person sichtbaren Chats des
   aktiven Space, bezieht das Archiv ein und öffnet einen Treffer an der Trefferstelle.
5. **Anheften und Archivieren sind Merkmale der Person, nicht des Chats** — eigene Zeilen je Person und
   Chat, damit das spätere Teilen von Chats niemandem die Liste eines anderen umräumt.
6. **Keine Ordner, keine Schlagworte.** Der Space ist bereits der Projektordner.
7. **Nichts wird protokolliert oder ausgewertet** — weder Suchbegriffe noch Ordnungsmerkmale; kein
   Durchgriff für Space- oder System-Admins.

---

## Begriffe

Zwei Vorgänge heißen ähnlich und sind strikt zu trennen, in der Oberfläche wie in der Dokumentation:

| Begriff | Wirkt auf | Wer | Folge |
|---|---|---|---|
| **Chat-Archiv** (dieses Dokument) | einen Chat, **nur für die archivierende Person** | jede Person für ihre sichtbaren Chats | Chat verschwindet aus ihrer Liste, sonst ändert sich nichts |
| **Archivierter Space** ([spaces-and-assets.md](./spaces-and-assets.md#einen-space-stilllegen-archivieren-statt-löschen)) | den ganzen Space, für alle | Space-Eigentümer, System-Admin | Space nimmt keinen neuen Inhalt an, wird aus regulären Listen ausgeblendet |

Ebenso getrennt: Die **Chatsuche** findet eigene Gespräche; die **Wissenssuche** (Retrieval und der
Such-Endpunkt ohne Generierung, siehe [Handbuch Suche](../handbuch/suche.md)) findet Dokumente.
Die Oberfläche benennt beides verschieden — ein Feld „Suchen" ohne Zusatz in der Chatliste wäre
missverständlich, weil der ganze Assistent „sucht".

---

## Die Seitenleiste

```
┌──────────────────────┐
│ SPACE ▾ Widerspruch  │
├──────────────────────┤
│ CHATS          + Neu │
│ ┌──────────────────┐ │
│ │ Chats filtern …  │ │
│ └──────────────────┘ │
│ ANGEHEFTET        ▾  │
│ ▪ Fristen Übersicht  │
│ ▪ Az. 12/4-2026      │
│ HEUTE                │
│ · Erlass vom März    │
│ GESTERN              │
│ · Rückfrage Kämmerei │
│ LETZTE 7 TAGE        │
│ · …                  │
│ In Inhalten suchen → │
│ Alle Chats →         │
└──────────────────────┘
```

### Titelfilter

- Ein Feld über der Liste filtert beim Tippen sofort nach Titel — ohne Unterscheidung von Groß- und
  Kleinschreibung, über die Liste, die der Client ohnehin hält. Kein Serveraufruf.
- Der Filter durchsucht die **aktiven** Chats der Seitenleiste. Findet er nichts, sagt er das und bietet
  „In Inhalten suchen" an — die [Chatsuche](#chatsuche) auf der Seite „Chats", mit dem eingegebenen
  Begriff vorbelegt. Sie durchsucht auch Titel und Archiv; ein eigener Weg „Im Archiv suchen" ist
  deshalb nicht nötig. Der Begriff geht als Navigationszustand mit, nicht in der Adresse; die Seite
  übernimmt ihn und verwirft ihn sofort, nach einem Neuladen ist das Feld leer.
- Escape leert das Feld; das Feld ist beschriftet und für Screenreader angekündigt, die Zahl der
  gefilterten Einträge wird als Statusmeldung ausgegeben.

### Zeitgruppen

| Gruppe | Enthält |
|---|---|
| **Angeheftet** | alle angehefteten Chats, zuletzt angeheftet oben; einklappbar |
| **Heute** | letzte Aktivität am heutigen Kalendertag |
| **Gestern** | am Vortag |
| **Letzte 7 Tage** | davor, bis sieben Tage zurück |
| **Letzte 30 Tage** | davor, bis dreißig Tage zurück |
| **Älter** | alles Übrige |

- Maßgeblich ist die **letzte Aktivität** des Chats; innerhalb einer Gruppe steht die jüngste oben.
  Leere Gruppen erscheinen nicht. Die Tagesgrenzen folgen der Zeitzone des Browsers.
- Solange es nur private Chats gibt, ist die letzte Aktivität die eigene. Ob in einem **geteilten** Chat
  fremde Aktivität die eigene Liste umsortieren darf, ist eine Frage des Teilens
  ([agents-and-tools.md, Offene Fragen zur Oberfläche](./agents-and-tools.md#offene-fragen-zur-oberfläche))
  und wird dort entschieden; die Zeile der persönlichen Merkmale (siehe unten) kann dann eine
  „letzte eigene Aktivität" aufnehmen.

### Anheften

- Über das Kontextmenü eines Chats („Anheften" / „Lösen"), in der Seitenleiste und auf der Seite „Chats".
- Keine Obergrenze. Wer dreißig Chats anheftet, hat eine zweite Liste — das ist sein gutes Recht, und
  eine Grenze wäre eine Regel mehr, die man sich merken muss.
- Anheften ist auch in einem **archivierten Space** möglich: Es ist ein Merkmal der Person, keine
  Änderung am Chat, und fällt deshalb nicht unter die Sperre „keine Änderungen an einem Chat".

---

## Die Seite „Chats" je Space

Erreichbar über „Alle Chats" am Ende der Chatliste in der Seitenleiste und über „In Inhalten suchen"
(dauerhaft unter der Liste und im Hinweis eines leeren Filterergebnisses). Sie ist die Verwaltungsfläche der eigenen
Chats eines Space, keine zweite Chatoberfläche: Ein Klick auf einen Eintrag öffnet den Chat.

```
┌─────────────────────────────────────────────────────────────┐
│ Chats in „Widerspruchsstelle"                               │
│ ┌─────────────────────────────────────────────┐             │
│ │ In Chats suchen …                           │             │
│ └─────────────────────────────────────────────┘             │
│  [ Aktiv (42) ]  [ Archiv (118) ]                           │
│ ─────────────────────────────────────────────────────────── │
│ ☐  Fristen Übersicht      angeheftet   zuletzt heute        │
│ ☐  Erlass vom März                     zuletzt gestern      │
│ ☐  Rückfrage Kämmerei                  zuletzt 12.09.2026   │
│ ─────────────────────────────────────────────────────────── │
│ 2 ausgewählt:  [ Archivieren ]  [ Löschen ]                 │
└─────────────────────────────────────────────────────────────┘
```

- **Reiter „Aktiv"** zeigt dieselben Chats wie die Seitenleiste, in derselben Ordnung (angeheftet, dann
  nach letzter Aktivität), als Tabelle mit Titel, Anheftung und letzter Aktivität.
- **Reiter „Archiv"** zeigt die archivierten Chats, jüngst archivierte zuerst, mit „archiviert am".
  Das Archiv wächst unbegrenzt und wird deshalb seitenweise geladen.
- **Mehrfachauswahl** mit Auswahlkästchen, „Alle auf dieser Seite auswählen" und den Sammelaktionen
  *Archivieren* (Reiter Aktiv), *Zurückholen* (Reiter Archiv) und *Löschen* (beide Reiter, mit derselben
  Bestätigung wie beim Einzellöschen, die die Zahl der betroffenen Chats nennt). Vollständig per Tastatur
  bedienbar.
- **Zahlen an den Reitern** zählen ausschließlich die eigenen, für die Person sichtbaren Chats.
- Das **Suchfeld** der Seite ist die Chatsuche (siehe [Chatsuche](#chatsuche)) und steht über beiden
  Reitern. Solange ein Begriff eingegeben ist, ersetzt die Trefferliste die Reiteransicht; Leeren des
  Feldes stellt die Reiter wieder her.

---

## Archivieren

### Was Archivieren bedeutet

Archivieren ist **eine persönliche Ablage**, keine Zustandsänderung am Chat:

- Der Chat verschwindet aus der Seitenleiste und dem Reiter „Aktiv" und erscheint im Reiter „Archiv".
- Er bleibt **lesbar**: Ein Klick im Archiv öffnet ihn; der Kopf des Chats trägt dann den Hinweis
  „Archiviert" mit der Aktion „Zurückholen".
- Er bleibt **fortsetzbar**. Wer in einem archivierten Chat selbst eine Nachricht sendet, holt ihn damit
  automatisch zurück; eine kurze Meldung sagt das („Chat aus dem Archiv zurückgeholt"). Grund: Ein
  Gespräch, an dem man weiterarbeitet, ist nicht erledigt — und ein Chat, der archiviert bleibt, obwohl
  man gerade darin schreibt, wäre beim nächsten Öffnen der Liste wieder verschwunden. In einem
  **archivierten Space** ist kein Chat fortsetzbar, archiviert oder nicht — dort gilt die Sperre des
  Space.
- Nur die **eigene** Nachricht holt zurück. Aktivität anderer in einem später geteilten Chat verändert
  die Ablage einer Person nicht.
- Archivieren **löst die Anheftung**. Beides zugleich ergibt keinen Sinn; beim Zurückholen bleibt der
  Chat ungeheftet.
- Archivieren **ändert den Chat nicht**: weder Titel noch letzte Aktivität noch Sichtbarkeit noch
  Teilen-Status.
- Archivieren ist auch in einem **archivierten Space** möglich, aus demselben Grund wie das Anheften —
  dort ist es gerade das Mittel, die eigene Liste leerlaufen zu lassen. Den **Space** leert es nicht:
  Ein archivierter Chat liegt weiter im Space, hält ihn für seinen Autor sichtbar und hält die
  Löschsperre des Space ([#543](https://github.com/criew/opaa/issues/543)) aufrecht — dafür bleibt das
  Löschen.

### Verhältnis zu Aufbewahrung und Löschen

- **Archivieren ist kein Löschen** und **kein Aufbewahren über eine Frist hinaus.** Eine
  Aufbewahrungsfrist für Chats wirkt auf archivierte und aktive Chats gleich. Ein Archiv mit eigener
  Lebensdauer wäre ein zweiter Bestand, den niemand überblickt.
- Eine Aufbewahrungsfrist für Chats ist **heute nicht gebaut** (der vorgesehene Umfang wurde mit #216
  als nicht geplant geschlossen, siehe [security-and-compliance.md](./security-and-compliance.md#aufbewahrung)).
  Die Archivansicht zeigt deshalb nur „archiviert am" und **keine** Angabe, wann ein Chat entfällt.
  Kommt eine Chat-Aufbewahrung, zeigt die Archivansicht den Ablauf dort an, wo er ohnehin fällig wird —
  an jedem Chat, nicht nur im Archiv.
- Gelöscht wird ein archivierter Chat wie jeder andere: durch seinen Autor.

### Kein automatisches Archivieren

Ein Archivieren nach Inaktivität wird **nicht** gebaut. Eine Liste, die sich von selbst verändert, ist
erklärungsbedürftig, und die Zeitgruppen leisten das Wesentliche — Altes rutscht in „Älter", ohne zu
verschwinden. Das Sammelarchivieren auf der Seite „Chats" ist der bewusste Weg, aufzuräumen.

---

## Chatsuche

### Was durchsucht wird

| Bestandteil | Durchsucht | Begründung |
|---|---|---|
| Titel | ja | billig, und der Titel ist oft das Erste, was man eintippt |
| Fragen der Person | ja | hier steht das Aktenzeichen, der Begriff, an den man sich erinnert |
| Antworten | ja | hier steht die Fundstelle, der Paragraf, die Zahl |
| Gesprächsnotiz | nein | sie verdichtet die Fragen; sie zu durchsuchen, verdoppelt Treffer |
| Text der Fundstellen (Belegpassagen) | nein | sonst wird die Chatsuche zu einer Schattensuche über Dokumente, an der Rechtelage der Wissenssuche vorbei |
| Titel der zitierten Dokumente | nein, vorerst | siehe [Offene Fragen](#offene-fragen--zukünftige-erweiterungen) |

Es ist eine **Volltextsuche** mit der deutschen Sprachkonfiguration wie in der Wissenssuche (Stammformen,
Stoppwörter). Eine semantische Suche über Chats wird nicht gebaut: Für „wo stand das noch" genügt der
Begriff, und Einbettungen aller Chatnachrichten wären ein zweiter, großer Vektorbestand mit Inhalten
aus dem privaten Arbeitsbereich.

### Wessen Chats, welcher Space

- **Nur im aktiven Space.** Der Space ist die Arbeitseinheit; eine Suche über alle Spaces vermischt
  Kontexte, die das Grundmodell trennt. Die spaceübergreifende Suche bleibt eine spätere Erweiterung.
- **Nur Chats, die die Person sehen darf** — heute ausschließlich die eigenen, nach dem Teilen auch die in
  den Space geteilten. Der Rechtefilter steht **in der Abfrage**, nicht dahinter: Ein fremder Chat wird
  nicht geladen, nicht gezählt und nicht verworfen, er kommt gar nicht vor.
- **Das Archiv ist einbezogen.** Archivierte Treffer sind gekennzeichnet. Sonst würde Archivieren zum
  Risiko, etwas nicht wiederzufinden, und unterbliebe.
- **Kein Durchgriff.** Weder Space- noch System-Admins können fremde Chats durchsuchen; es gibt dafür
  keinen Parameter und keine Rolle. Die Suche ist nur in der angemeldeten Sitzung erreichbar, nicht über
  persönliche Zugangstokens und nicht über den MCP-Server der [Fremdzugänge](./external-access.md).

### Bedienung

- Suchfeld auf der Seite „Chats", vorbelegt, wenn man über „In Inhalten suchen" aus der Seitenleiste
  kommt.
- Treffer ab einer Mindestlänge des Begriffs, nach einer kurzen Tipp-Pause; Enter sucht sofort.
- **Ein Treffer je Chat**, mit Titel, Auszug der besten Stelle (Begriff hervorgehoben, nicht allein durch
  Farbe), Angabe „Frage" oder „Antwort", Datum der Nachricht und dem Kennzeichen „Archiviert".
  Sortiert nach Relevanz; seitenweise.
- **Ein Klick öffnet den Chat an der Trefferstelle**: Die Ansicht scrollt zur Nachricht, hebt sie hervor
  und setzt den Fokus dorthin (mit Rücksicht auf `prefers-reduced-motion`). Der Link bleibt gültig
  — auch nach einem Neuladen.
- Leere Zustände: „Kein Chat enthält ‚…'" mit dem Hinweis, dass nur eigene Chats dieses Space durchsucht
  werden.

---

## Persönliche Ordnungsmerkmale im Datenmodell

Anheften und Archivieren werden **nicht** als Spalten am Chat gespeichert, sondern als eigene Zeile je
Person und Chat:

| Merkmal | Inhalt | Zweck |
|---|---|---|
| angeheftet | Zeitpunkt des Anheftens oder leer | Anzeige und Reihenfolge der Gruppe „Angeheftet" |
| archiviert | Zeitpunkt des Archivierens oder leer | Trennung Aktiv/Archiv, Anzeige „archiviert am" |

- Bei privaten Chats fallen Person und Autor zusammen; das Modell trägt aber schon den geteilten Chat, in
  dem jede Person ihre eigene Ordnung hat.
- Die Zeile entfällt mit dem Chat und mit dem Konto.
- Das Setzen und Lösen eines Merkmals ist ein **eigener Vorgang**, keine Änderung am Chat: Es verändert
  die letzte Aktivität nicht und ist deshalb auch im archivierten Space erlaubt.
- Ein fremdes Merkmal ist nicht lesbar und nicht setzbar; der Versuch, einen nicht sichtbaren Chat
  anzuheften oder zu archivieren, wird wie ein nicht existierender Chat beantwortet.

---

## Datenschutz und Personalvertretung

- **Suchbegriffe der Chatsuche werden nicht protokolliert** — nicht im Nachweisprotokoll, nicht im
  Anwendungslog, nicht in Metriken. Sie sind Abfragen im Sinne von
  [security-and-compliance.md](./security-and-compliance.md#was-ausdrücklich-nicht-protokolliert-wird).
  Zulässig sind nur Betriebsmetriken ohne Begriff und ohne Person (Anzahl, Dauer).
- **Der Suchbegriff steht in keiner URL.** Der Such-Endpunkt nimmt ihn im Request-Body entgegen wie die
  Wissenssuche (`POST /api/v1/search`) — als Query-Parameter landete er im Zugriffslog des
  Reverse-Proxys. Ebenso trägt die Seite „Chats" den vorbelegten Begriff nicht in ihrer Adresse, sonst
  stünde er im Browserverlauf; und er wird nicht im Browser-Speicher abgelegt.
- **Ordnungsmerkmale** (angeheftet, archiviert) sind für niemanden sonst sichtbar, werden nicht
  protokolliert, nicht exportiert außer in der Selbstauskunft der Person und nicht ausgewertet. Es gibt
  keine Zahl „archivierte Chats je Person", auch nicht aggregiert.
- Die Zeitpunkte der Merkmale sind Verhaltensdaten im engeren Sinn (wann hat jemand aufgeräumt). Sie
  werden nur gespeichert, weil die eigene Anzeige sie braucht, sind über keine Oberfläche und keinen
  Endpunkt für Dritte abrufbar und werden in keine Auswertung übernommen.
- Die Chatsuche ändert nichts am Grundsatz „private Inhalte sind unbeobachtet": Sie ist ein Werkzeug der
  Person über ihren eigenen Bestand, kein Zugang zu fremdem.

---

## Stakeholder-Bewertung

| Perspektive | Einschätzung |
|---|---|
| **Sachbearbeitung** | Zeitgruppen und Anheften helfen sofort und kosten nichts. Der eigentliche Gewinn ist die Inhaltssuche mit Sprung an die Stelle — „der Chat mit dem Aktenzeichen 12/4" ist in Sekunden wieder da. Das Archiv entspricht der Gewohnheit der Ablage. |
| **Personalvertretung** | Kein neuer Auswertungspfad: Suchbegriffe ungespeichert, Ordnungsmerkmale nur für die Person, kein Admin-Durchgriff. Die beiden neuen Zeitpunkte je Person und Chat bleiben ausschließlich der Person selbst sichtbar. |
| **Skeptiker (Pflegeaufwand)** | Nichts davon verlangt Pflege: Zeitgruppen entstehen von selbst, Anheften ist ein Klick, Archivieren optional. Ordner und Schlagworte, die gepflegt werden müssten, werden bewusst nicht gebaut. |
| **Betrieb** | Ein Volltextindex über Chatnachrichten wächst mit dem Chatbestand; er entfällt mit dem Chat. Keine neue Infrastruktur, keine Einbettungen. |

---

## Entscheidungen

Entschieden vom Maintainer am 18.09.2026 (Epic #1762, Phase 1):

| Frage | Entscheidung | Verworfen |
|---|---|---|
| Ordner oder Schlagworte | **Keines.** Phase 5 nur bei gemeldetem Bedarf nach Einführung der Phasen 2–4 | Schlagworte im Datenmodell vorsehen; Ordner |
| Archivsemantik | **Persönliche Ablage**, lesbar und fortsetzbar; eigene neue Nachricht holt automatisch zurück, mit Hinweis | schreibgeschützt bis zum Zurückholen; fortsetzbar, bleibt archiviert |
| Ort für Verwalten und Inhaltssuche | **Seitenleiste** für Filter, Zeitgruppen, Anheften; **Seite „Chats" je Space** mit Reitern Aktiv/Archiv, Mehrfachauswahl und Inhaltssuche | alles in der Seitenleiste; Suchdialog plus Archiv in den persönlichen Einstellungen |
| Aufbewahrungsanzeige im Archiv | **Entfällt**, weil es keine Chat-Aufbewahrung gibt; Anzeige „archiviert am" | Chat-Aufbewahrung als Voraussetzung ins Epic holen |

Im Konzept festgelegt, ohne gesonderte Maintainer-Frage: Suche nur im aktiven Space; Suchbegriffe nicht
protokolliert; Archiv in der Suche einbezogen; persönliche Merkmale als eigene Zeilen je Person und
Chat; kein automatisches Archivieren; keine Obergrenze beim Anheften; Merkmale auch im archivierten Space
setzbar; Chatsuche nicht über Fremdzugänge erreichbar; durchsucht werden Titel, Fragen und Antworten.

### Mustervergleich

Verifiziert am 18.09.2026:

| Muster | Befund | Übernommen |
|---|---|---|
| Zeitgruppen | ChatGPT, Claude, Gemini, Open WebUI | ja |
| Anheften | ChatGPT (eigener Abschnitt, in den Verbrauchertarifen auf drei Chats begrenzt), Claude (Stern), Gemini (oben in „Zuletzt"), Open WebUI | ja, ohne Obergrenze |
| Archiv | ChatGPT (Archiv weiter durchsuchbar; eine neue Nachricht holt den Chat zurück), Open WebUI (eigene Übersicht, Suche mit Archivfilter), LibreChat | ja, mit derselben Rückholregel |
| Suche | ChatGPT (Dialog, Inhalt), Gemini (Titel und Auszug), Open WebUI (Inhalt, Filter), LibreChat (Volltext über eigenen Suchdienst, nicht semantisch); Claude durchsucht nur Titel | Inhalt, mit Auszug, ohne eigenen Suchdienst |
| Eigene Chat-Übersichtsseite | Claude („Chats"), Open WebUI (Archivübersicht) | ja, als Seite „Chats" je Space |
| Ordner, Schlagworte, Lesezeichen | Open WebUI (Ordner, Schlagworte), LibreChat (Lesezeichen mit Schlagworten); Gemini ohne Ordner | nein |

---

## Umsetzung in Phasen

| Phase | Inhalt | Abhängig von |
|---|---|---|
| 2 — Ordnung | Titelfilter, Zeitgruppen, Anheften in der Seitenleiste; Tabelle der persönlichen Merkmale | diesem Konzept |
| 3 — Archiv | Archivieren, Zurückholen, automatisches Zurückholen, **Seite „Chats"** mit Reitern und Mehrfachauswahl | Phase 2 |
| 4 — Chatsuche | Volltextindex, Such-Endpunkt je Space; Suchfeld der Seite „Chats" mit Auszug und Sprung an die Trefferstelle | Phase 3 (Archiv und Seite) |
| 5 — nur bei Bedarf | Schlagworte oder Ordner | gemeldetem Bedarf nach Phasen 2–4; kein Issue angelegt |

Handbuch und E2E-Abdeckung gehören in jede Phase, nicht gesammelt ans Ende.

---

## Integrationspunkte

- **[spaces-and-assets.md](./spaces-and-assets.md#chats)** — Chats als space-eigene Objekte, die
  Grundregel „zunächst privat" und das Space-Archiv. Das Teilen von Chats macht die persönlichen
  Merkmale erst wirksam; die Chatsuche erweitert sich dann auf geteilte Chats.
- **[agents-and-tools.md](./agents-and-tools.md#offene-fragen-zur-oberfläche)** — Ungelesen-Markierung
  und Sortierung bei fremder Aktivität bleiben dort; die Ungelesen-Markierung ist eine weitere Zeile
  derselben Art (je Person und Chat) und passt in das hier festgelegte Modell.
- **[security-and-compliance.md](./security-and-compliance.md#was-ausdrücklich-nicht-protokolliert-wird)** —
  Suchbegriffe sind Abfragen und werden nicht protokolliert.
- **[external-access.md](./external-access.md)** — die Chatsuche ist kein Fremdzugangsweg.
- **[conversation-memory.md](./conversation-memory.md)** — die Gesprächsnotiz folgt dem Chat, wird aber
  nicht durchsucht.
- **Benachrichtigungssystem (#1297)** — das Postfach verweist auf Chats; ein Verweis auf einen
  archivierten Chat öffnet ihn wie jeder andere.
- **[Zielbild der Weboberfläche](../design/redesign-prompt.md)** — Abschnitt 3 „Grundriss".

---

## Offene Fragen / Zukünftige Erweiterungen

- **Schlagworte oder Ordner (Phase 5)** — nur bei gemeldetem Bedarf, frühestens nach Einführung der
  Phasen 2–4. Wer im Space weiter untergliedern will, hat oft zwei Vorhaben in einem Space; zuerst ist
  zu prüfen, ob ein zweiter Space die bessere Antwort ist.
- **Suche nach Titeln zitierter Dokumente** („in welchem Chat hatte ich den Erlass?") — naheliegend, aber
  erst zu bauen, wenn geklärt ist, wie sie sich zur Rechtelage einer inzwischen entzogenen Bibliothek
  verhält.
- **Spaceübergreifende Chatsuche** („in welchem Space war das noch?") — eigenes Epic.
- **Filter „meine / mit mir geteilte"** und die Sortierung bei fremder Aktivität — mit dem Teilen von Chats.
- **Anzeige des Fristablaufs** — mit einer künftigen Chat-Aufbewahrung.

---

## Erfolgs-Metriken

Ohne Personenbezug, nach denselben Bedingungen wie in
[Nutzungstransparenz](./spaces-and-assets.md#nutzungstransparenz):

- **Keine Rückmeldung „ich finde meinen Chat nicht wieder"** im Betrieb der Pilotbehörden — die
  eigentliche Probe, weil es bewusst keine feinere Messung gibt.
- **Gemeldeter Bedarf an Ordnern oder Schlagworten** — die Schwelle für Phase 5.
