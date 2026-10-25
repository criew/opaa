# Chatliste eines Space: finden, ordnen, wegräumen

> **Status: umgesetzt (Epic #1762, Phasen 2–4, 09/2026); Phase 5 nur bei gemeldetem Bedarf.**
> Die Maintainer-Entscheidungen stehen im Abschnitt [Entscheidungen](#entscheidungen); den gebauten
> Stand der Chatsuche beschreibt das Handbuch ([Suche](../handbuch/spaces/suche.md)).

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

1. **Seitenleiste für den schnellen Griff.** Eine Gruppe „Angeheftet" ganz oben, darunter „Zuletzt
   verwendet" mit Nachladen; ein Suchknopf neben „Neu" führt in die Chatsuche.
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
Such-Endpunkt ohne Generierung, siehe [Handbuch Suche](../handbuch/spaces/suche.md)) findet Dokumente.
Die Oberfläche benennt beides verschieden — ein Feld „Suchen" ohne Zusatz in der Chatliste wäre
missverständlich, weil der ganze Assistent „sucht".

---

## Die Seitenleiste

```
┌──────────────────────┐
│ SPACE ▾ Widerspruch  │
├──────────────────────┤
│ CHATS      + Neu  🔍 │
│ ANGEHEFTET        ▾  │
│ ▪ Fristen Übersicht  │
│ ▪ Az. 12/4-2026      │
│ ZULETZT VERWENDET    │
│ · Erlass vom März    │
│ · Rückfrage Kämmerei │
│ · …                  │
│ 15 weitere ⌄         │
└──────────────────────┘
```

### Suchknopf statt Filterfeld

Ein Titelfilter beantwortet nur die halbe Frage — wer einen Chat sucht, erinnert sich oft an ein Wort
aus dem Gespräch, nicht an dessen Titel. Statt eines Filterfelds steht deshalb ein Suchknopf neben
„Neu" (#1918, Epic #1441):

- Er öffnet die [Chatsuche](#chatsuche) auf der Seite „Chats" mit leerem Suchbegriff. Leer listet sie
  alle Chats, Archiv eingeschlossen; ab drei Zeichen treten die Treffer an deren Stelle.
- Der Begriff geht als Navigationszustand mit, nie in der Adresse; die Seite übernimmt ihn und
  verwirft ihn sofort, nach einem Neuladen ist das Feld leer.
- Unter der Liste stehen keine Verweise mehr: „Alle Chats" und „In Inhalten suchen" führten ohnehin
  auf dieselbe Seite wie der Suchknopf.

### Abschnitte

| Abschnitt | Enthält |
|---|---|
| **Angeheftet** | alle angehefteten Chats, zuletzt angeheftet oben; immer offen |
| **Zuletzt verwendet** | alle übrigen Chats nach letzter Aktivität, die jüngsten 15 sichtbar |

- „15 weitere" blendet die nächste Seite ein — die letzte nennt ihre tatsächliche Zahl;
  „Weniger" klappt auf die ersten 15 zurück. Ein Spacewechsel beginnt wieder bei 15. Beide Knöpfe
  stehen einzeilig in einer Zeile (Pfeil statt „anzeigen"); vorgelesen werden sie vollständig als
  „15 weitere Chats anzeigen" und „Weniger anzeigen".
- Maßgeblich ist die **letzte Aktivität** des Chats; die jüngste steht oben. Tagesgruppen (Heute,
  Gestern, Letzte 7 Tage …) gibt es seit #1918 nicht mehr: Sie zerlegten eine kurze Liste in viele
  Überschriften, ohne beim Wiederfinden zu helfen.
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

### Umbenennen

- Über das Kontextmenü eines Eintrags, und seit #1919 zusätzlich **direkt in der Kopfzeile** des
  offenen Chats: Klick auf den Titel öffnet das Feld, Enter speichert, Escape verwirft. Beide Wege
  nutzen denselben Endpunkt; die Chatliste zieht sofort nach.
- In einem **archivierten Space** ist der Titel schreibgeschützt — anders als das Anheften ist
  Umbenennen eine Änderung am Chat.

---

## Die Seite „Chats" je Space

Erreichbar über den Suchknopf neben „Neu" in der Chatliste. Sie ist die Verwaltungsfläche der eigenen
Chats eines Space, keine zweite Chatoberfläche: Ein Klick auf einen Eintrag öffnet den Chat.

```
┌─────────────────────────────────────────────────────────────┐
│ Chats in „Widerspruchsstelle"                               │
│ ┌─────────────────────────────────────────────────────────┐ │
│ │ ⌕ Titel, Fragen und Antworten durchsuchen …             │ │
│ └─────────────────────────────────────────────────────────┘ │
│  [ Aktiv (42) ]  [ Archiv (118) ]                           │
│ ☑ Alle auf dieser Seite …  2 ausgewählt  Archivieren Löschen│
│  TITEL                              LETZTE AKTIVITÄT        │
│ ☐ Fristen Übersicht                 18.09.2026, 09:12    ⋮  │
│   · angeheftet                                              │
│ ☑ Erlass vom März                   17.09.2026, 16:40    ⋮  │
│ ☑ Rückfrage Kämmerei                12.09.2026, 11:03    ⋮  │
│                          42 Chats · Seite 1 von 2   ‹  ›    │
└─────────────────────────────────────────────────────────────┘
```

- **Tabelle wie in der Administration** („Benutzer", „Gruppen"): dieselben Bausteine
  (`shared/ui/list/`) für Tabellenstil, Seitenumschalter, Lade- und Leerzustand. Unterhalb der
  Tablet-Breite wird die Tabelle zur Liste (Leitfaden 5.3).
- **Reiter „Aktiv"** zeigt dieselben Chats wie die Seitenleiste, in derselben Ordnung (angeheftet, dann
  nach letzter Aktivität), mit Titel, Anheftung als zweiter Zeile und letzter Aktivität.
- **Reiter „Archiv"** zeigt die archivierten Chats, jüngst archivierte zuerst, mit „Archiviert am".
- **Blättern** in beiden Reitern mit 25 Chats je Seite und demselben Seitenumschalter wie in der
  Administration („42 Chats · Seite 1 von 2"). Die aktiven Chats sind für die Seitenleiste ohnehin
  vollständig geladen und werden im Browser geblättert; das Archiv wächst unbegrenzt und wird
  seitenweise vom Server geladen.
- **Zeilenmenü** (⋮) für die Einzelaktionen: *Anheften*/*Lösen* und *Archivieren* (Reiter Aktiv),
  *Zurückholen* (Reiter Archiv), *Löschen* unter einer Trennlinie (beide Reiter).
- **Mehrfachauswahl** mit Auswahlkästchen und „Alle auf dieser Seite auswählen". Die Sammelaktionen
  *Archivieren* bzw. *Zurückholen* und *Löschen* erscheinen erst, wenn etwas ausgewählt ist; die Leiste
  färbt sich dann leicht ein. Löschen fragt nach, die Rückfrage nennt die Zahl der betroffenen Chats
  (beim Einzellöschen den Titel). Vollständig per Tastatur bedienbar.
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

- **Archivieren ist kein Löschen** und **kein Aufbewahren über eine Frist hinaus.** Ohne
  eingeschaltete Bereinigung hat das Archiv keine eigene Lebensdauer. Mit ihr ist das Archiv
  bewusst die Vorstufe der Löschung: Ein Chat wird erst nach der Archivfrist archiviert und erst nach
  der Löschfrist im Archiv gelöscht; das Löschdatum steht am archivierten Chat. Die Regel, dass das
  Archiv kein zweiter, unüberblickter Bestand mit eigener Lebensdauer sein darf, bleibt damit
  gewahrt: Die Lebensdauer gilt nur im eingeschalteten Space, für alle nicht angehefteten Chats
  gleich, und ist am Chat ablesbar.
- Eine allgemeine Aufbewahrungsfrist für Chats ist nicht gebaut (der vorgesehene Umfang wurde mit #216
  als nicht geplant geschlossen, siehe [security-and-compliance.md](./security-and-compliance.md#aufbewahrung)).
  Gebaut ist die **automatische Bereinigung je Space** (#1923, nächster Abschnitt): Ist sie im Space
  eingeschaltet, trägt jeder archivierte Chat das Datum, an dem sie ihn löscht — in der Archivliste
  und in der Kopfzeile des Chats.
- Gelöscht wird ein archivierter Chat wie jeder andere durch seinen Autor, oder durch die automatische
  Bereinigung des Space auf demselben Löschweg.

### Automatisches Archivieren und Löschen je Space

Entschieden vom Maintainer am 02.10.2026 (#1923), nach Bewertung und Nachprüfung durch die
Stakeholder-Rolle Personalrat. Ursprünglich galt „ein Archivieren nach Inaktivität wird nicht
gebaut"; ohne Einschalten bleibt es dabei, das Sammelarchivieren auf der Seite „Chats" bleibt der
bewusste Weg, von Hand aufzuräumen.

- **Ein Schalter je Space** „Inaktive Chats automatisch archivieren und löschen", ab Werk aus, wählbar
  im Assistenten beim Anlegen und in den Space-Einstellungen (Reiter „Stammdaten"). Umlegen darf ihn,
  wer die Stammdaten ändern darf (Space-`ADMIN`, Systemverwaltung). **Im persönlichen Standard-Space
  nur die Person selbst** (Eigentümer), auch nicht die Systemverwaltung (403). Damit das nicht über
  einen Eigentümerwechsel zu umgehen ist, wird ein Standard-Space nie übertragen (400, weder über
  die Space-Übertragung noch über die Nachfolge; im Frontend fehlt dort „Zum Eigentümer machen").
- **Sichtbar für jedes Mitglied dort, wo die Chats sind:** Ist die Bereinigung an, nennt die Seite
  „Chats" die Fristen in einem knappen Hinweis („In diesem Space werden inaktive Chats nach … Tagen
  archiviert und nach weiteren … Tagen im Archiv gelöscht. Angeheftete Chats bleiben."). Die Fristen
  kommen mit der Space-Liste (`SpaceListResponse.chatAutoCleanup`). Der Einstieg in die
  Space-Einstellungen bleibt Administratoren und Kuratoren vorbehalten.
- **Feste Fristen**, systemweit, nur vom Betrieb einstellbar: archivieren nach 90 Tagen ohne
  Aktivität, archivierte Chats löschen nach 365 Tagen im Archiv. Die Konfiguration hat eine nicht
  unterschreitbare **Untergrenze** von 90 bzw. 30 Tagen; ein kleinerer Wert bricht den Start ab.
- **Aktivität** ist ausschließlich der vorhandene Zeitstempel der letzten Chat-Aktivität
  (`chats.updated_at`), dazu der Zeitpunkt des Zurückholens aus dem Archiv (unten). Weitere
  Aktivitätsdaten je Person werden nicht erhoben.
- **Keine Rückwirkung:** Der Zeitpunkt des Einschaltens wird am Space gespeichert; beide Fristen
  beginnen frühestens dort. Ein Archivierdatum aus der Zeit davor löst keine sofortige Löschung aus.
- **Angeheftete Chats** sind ausgenommen. Anheften, eine eigene neue Frage oder das Zurückholen aus
  dem Archiv holt einen archivierten Chat zurück und lässt die Archivfrist neu beginnen. Für das
  Zurückholen speichert die persönliche Archiv-Markierung den Zeitpunkt (`unarchived_at`), nur in
  Spaces mit eingeschalteter Bereinigung; der Lauf zählt ab `max(letzte Aktivität, zurückgeholt am,
  eingeschaltet am)` und vergisst den Zeitpunkt, sobald er eine Archivfrist alt ist. Eine eigene Benachrichtigung gibt es nicht; der
  archivierte Chat zeigt sein Löschdatum.
- **Gelöscht wird über den bestehenden Löschweg** des Chats, mit Verlauf, Notiz und persönlichen
  Merkmalen. Es gibt keine Protokollereignisart für das Löschen eines Chats, und es kommt keine dazu.
- **Protokoll:** nur das Umlegen des Schalters (Ereignis `SPACE_CHANGED` mit `chatAutoCleanup` vor
  und nach der Änderung, handelnde Person, Zeitpunkt; beim Anlegen im Ereignis `SPACE_CREATED`). Der
  tägliche Lauf (03:50 Uhr, auf einer Instanz unter der Sperre nach ADR-0053) schreibt nur Gesamtzahlen ins
  Anwendungslog, keinen Chat, keinen Space, keine Person.

**Verhältnis zur persönlichen Ablage.** Der Automatismus schreibt dieselbe persönliche
Archiv-Markierung, die die Person selbst setzen würde — die Zeile des **Autors** zu seinem Chat. Das
Archivmodell ändert sich dadurch nicht: Solange es nur private Chats gibt, ist der Autor die einzige
Person, die den Chat sieht. Kommt das Teilen von Chats, ist zu entscheiden, ob der Automatismus auch
die Zeilen der Personen schreibt, mit denen ein Chat geteilt ist, und wessen Archivzeitpunkt die
Löschfrist bestimmt.

---

## Löschung der Chats gesperrter Konten

Entschieden vom Maintainer am 23.10.2026 (#2997), nach dem Stakeholder-Review mit Personalrat und
Betrieb (Kommentare am Issue). Für das Ausscheiden einer Person ist die Sperre der Regelweg, nicht
die Löschung; ein vorhandener Chat verhindert die Löschung des Kontos sogar. Ohne diese Regel blieben
die Chats einer ausgeschiedenen Person unbegrenzt liegen, denn die Bereinigung je Space ist ab Werk
aus und im persönlichen Standard-Space legt sie nur die Person selbst um. Chats sind der größte und
aussagekräftigste Teil der Daten über eine Person.

### Regel

- **Auslöser ist allein die Sperre durch die Verwaltung** (`local_credentials.locked_reason =
  ADMIN`). Die Sperre wegen Inaktivität ist eine Abwesenheit (Elternzeit, Krankheit, Abordnung), die
  Fehlversuch-Sperre ein Angriff Dritter; Ablaufdatum, abgeschaltete lokale Konten, abgeschaltete
  oder gelöschte Anbieter und der Verzeichnisabgleich sind Sammel- oder Betriebszustände. Keiner
  davon startet die Frist. Die Positivliste ist abschließend, je ausgeschlossenem Anlass belegt ein
  Test, dass die Chats bleiben.
- **Umwidmen einer anderen Sperre:** Ein wegen Inaktivität oder Fehlversuchen gesperrtes Konto lässt
  sich durch die Verwaltung sperren, ohne Entsperren dazwischen und ohne Freischaltungs-Mail. Das ist
  der Regelfall für Ausgeschiedene, deren Konto schon die Inaktivitätssperre trifft. Protokolliert
  wie jede Sperre (`LOCAL_USER_LOCKED`); die Frist beginnt mit der Umwidmung.
- **Konten eines Identitätsanbieters** lassen sich in OPAA nicht sperren; die Frist greift für sie
  nicht. Maßgeblich ist nur die Sperre in OPAA, eine Deaktivierung beim Anbieter zählt nicht
  (Entscheidung 5). Eine Sperre von Anbieterkonten in OPAA ist ein eigenes Thema (#3025).
- **Frist** `opaa.chat.locked-account-cleanup.delete-after-days`, ab Werk **365 Tage**, Untergrenze
  **90 Tage**: Eine irrtümliche Sperre muss über Urlaub und Vertretung hinweg auffallen können. Ein
  kleinerer Wert bricht den Start ab. Die wirksame Frist steht beim Start im Anwendungslog.
- **Abschaltbar** mit `opaa.chat.locked-account-cleanup.enabled` (ab Werk an). Abgeschaltet löscht
  nichts; schon der Start mit abgeschalteter Löschung vergisst den Zählbeginn, nach dem
  Wiedereinschalten beginnt jede Frist neu.
- **Fristbeginn** ist der Beginn der aktuellen, ununterbrochenen Sperre durch die Verwaltung
  (`locked_at`), **frühestens die Einführung** bzw. das letzte Einschalten
  (`chat_locked_account_cleanup_state.counting_since`, einmalig gesetzt mit der Datenbankuhr). Ein
  Entsperren beendet die Frist, eine erneute Sperre beginnt von vorn. Keine Rückwirkung: Ein Konto,
  das bei der Einführung seit 400 Tagen gesperrt ist, behält seine Chats bis 365 Tage nach der
  Einführung.
- **Gelöscht werden alle Chats, deren Autor das Konto ist**, in allen Spaces einschließlich des
  persönlichen Standard-Space, angeheftete eingeschlossen (Entscheidung 3), auf demselben Weg wie
  durch die Person: mit Verlauf, Gesprächsnotiz und persönlichen Merkmalen. Konto und Standard-Space
  bleiben. Unabhängig vom Schalter der Bereinigung im Space.
- **Geteilte Chats bleiben**, bis die Bereinigung ihres Space oder eine Löschung von Hand sie
  erfasst. Der Lauf nimmt nur Chats mit Status `PRIVATE`; solange Chats nicht geteilt werden können,
  ist das jeder Chat.
- **Aussetzen bei anlassbezogener Klärung** (Entscheidung 4): Solange das Konto in einem offenen
  Antrag oder einer noch nutzbaren Freigabe steht, gleich in welcher Rolle, löscht der Lauf nicht;
  dieselbe Regel wie bei der Kontolöschung. Nach dem Ende holt der nächste Lauf nach, die Aussetzung
  verlängert nichts.
- **Anhalten je Konto** durch die Systemverwaltung, etwa für ein Disziplinarverfahren oder einen
  Rechtsstreit außerhalb von OPAA: „Löschung anhalten“ und „Löschung fortsetzen“ in der
  Einzelansicht des Kontos, ohne Begründung (sie wäre selbst ein sensibles Datum über die Person),
  beide protokolliert. Ist die Frist beim Fortsetzen abgelaufen, löscht der nächste Lauf.

### Lauf

Täglich um **02:30 Uhr** Serverzeit, auf einer Instanz unter der ShedLock-Sperre (ADR-0053), nicht
gleichzeitig mit der Bereinigung je Space um 03:50 Uhr. Zeitpunkt „jetzt“ ist die Datenbankuhr.

- **Obergrenze je Lauf:** höchstens 5.000 Chats, in Stapeln zu je 200 in eigener Transaktion. Bei
  der Einführung starten alle schon gesperrten Konten mit demselben Fristbeginn; die Welle verteilt
  sich so auf mehrere Nächte, und der Lauf bleibt weit unter der Sperrdauer von zwei Stunden.
- **Erneute Prüfung unter Sperre:** Jeder Stapel prüft das Konto unter einer Lesesperre auf seine
  Zugangsdaten erneut; ein Entsperren oder Anhalten wartet auf den Stapel oder wird von ihm gesehen.
  Der Lauf ist zustandsbasiert: Bricht er ab, setzt die nächste Nacht fort.
- **Wiederherstellung aus einer Sicherung:** Tauchen gelöschte Chats wieder auf, löscht der nächste
  Lauf sie erneut und schreibt ein neues Ereignis. Wer Chats einer zu Unrecht gesperrten Person
  zurückholen will, spielt die Sicherung ein und entsperrt das Konto oder hält die Löschung an,
  **bevor der nächste Lauf um 02:30 Uhr** beginnt: Eine vollständig eingespielte Datenbank bringt
  auch Sperre, Anhalten und Fristbeginn auf den Stand der Sicherung zurück, ein vorher gesetzter
  Halt ginge verloren. Alternativ startet die eingespielte Installation mit abgeschalteter
  Löschung; dann beginnen alle Fristen neu.

### Protokoll und Anwendungslog

- Ein Ereignis `LOCKED_ACCOUNT_CHATS_DELETED` **je Konto und Lauf**, der für das Konto etwas
  löscht, geschrieben in der Transaktion des ersten Stapels. Betroffene Person ist das Konto; es
  trägt den **Fristbeginn** und den **Sperrgrund**, **keine Zahl**, keinen Titel, keinen Inhalt,
  keinen Space. Die Zahl gelöschter Chats je Person wäre ein Nutzungsmaß, das nach der Löschung als
  einzige Spur bliebe. Verteilt sich ein Konto auf mehrere Nächte, entsteht je Nacht ein Ereignis.
- `LOCKED_ACCOUNT_CHAT_CLEANUP_HELD` und `…_RESUMED` für Anhalten und Fortsetzen, ohne Begründung;
  ein wiederholtes Anhalten schreibt nichts.
- Aufbewahrung: allgemeine Protokollfrist (Voreinstellung 36 Monate, wie die Rechtehistorie).
  Wird das Konto später gelöscht, bleibt das Ereignis mit dem Pseudonym ohne Zuordnung stehen.
- Im **Auszug für die Personalvertretung** mit Zweck, Zugriff und Frist
  ([security-and-compliance.md](./security-and-compliance.md#der-auszug-für-die-personalvertretung)).
- Anwendungslog: nur Gesamtzahlen (Chats, Konten); ein gescheitertes Konto oder ein Lauf, der die
  fälligen Konten nicht lesen kann, als **ERROR**.

### Sichtbarkeit des Löschdatums

- „Frühestens am …“ steht **nur in der Einzelansicht** des Kontos in der Verwaltung (Dialog
  „Bearbeiten“), für jedes durch die Verwaltung gesperrte Konto, **unabhängig davon, ob und wie
  viele Chats es hat**. Eine laufende Klärung ändert die Anzeige nicht. Ein Anhalten zeigt die
  Ansicht, weil es ein Akt der Verwaltung selbst ist.
- **Keine Spalte, kein Filter, keine Sortierung, kein Export** in der Kontenliste: Eine Liste „wird
  bald gelöscht“ wäre eine Liste der Ausgeschiedenen samt Zeitpunkt.
- **Nachricht bei der Sperre:** Die Mail der Sperre durch die Verwaltung nennt das früheste
  Löschdatum. Den Hinweis auf die Selbstauskunft trägt sie nicht: Nach der Sperre kann sich die
  Person nicht mehr anmelden, die Selbstauskunft ist für sie nicht mehr erreichbar. Die Gelegenheit
  zur Selbstauskunft vor einem planbaren Ausscheiden ist deshalb eine Aufgabe der Dienststelle
  (Handbuch, Dienstvereinbarung #2566). Die Mail der Inaktivitätssperre nennt kein Datum.

### Wirkung auf Behördenunterlagen

- [x] Neue oder geänderte personenbezogene Daten oder Protokollereignisse — Datum
  `chat-cleanup-holds` (angehaltene Löschung je Konto), Ereignisse `LOCKED_ACCOUNT_CHATS_DELETED`,
  `LOCKED_ACCOUNT_CHAT_CLEANUP_HELD`, `LOCKED_ACCOUNT_CHAT_CLEANUP_RESUMED`
- [x] Neue Sichtbarkeit: Daten werden für weitere Rollen oder Personen sichtbar — die
  Systemverwaltung sieht in der Einzelansicht das Löschdatum und ob die Löschung angehalten ist
- [ ] Neue Auswertung oder neuer Export, auch von bereits gespeicherten Daten
- [x] Geänderte Aufbewahrungs- oder Löschfrist — Chats: 365 Tage nach der Sperre durch die
  Verwaltung statt unbegrenzt
- [ ] Neuer externer Datenfluss (neuer Dienst, neues Ziel ausgehender Verbindungen)
- [x] Geänderte Voreinstellung, geändertes Standardmodell oder geänderter System-Prompt — ab Werk an
  mit Datenwirkung
- [ ] Geänderte KI-Einstufung (Zweck oder Einsatzbereich im Sinne der KI-Verordnung)
- [ ] Keine Wirkung auf Daten, Sichtbarkeit, Auswertung, Datenflüsse oder Voreinstellungen

**Voraussichtliche Stufe:** Zustimmung (Z1 neue Ereignisart, Z7 ab Werk an mit Datenwirkung); das
Stakeholder-Review mit Personalrat und Betrieb ist erledigt (Kommentare an #2997 vom 23.10.2026),
die Muss-Auflagen beider Reviews sind umgesetzt.

**Betroffene Vorlagen in `docs/behoerden/`:** `04-datenschutz/` (Löschkonzept), die Faktenblätter
Fristen, Voreinstellungen und Protokollereignisse aus dem Fakten-Manifest und die
Muster-Dienstvereinbarung (#2566) mit den Bedingungen des Personalrats: Frist mit Untergrenze und
Anknüpfung an die Sperre durch die Verwaltung, Verlängerung nur mit Zustimmung; Pflicht der
Dienststelle, Konten Ausgeschiedener zu sperren; keine Sperre wegen bloßer Abwesenheit; Gelegenheit
zur Selbstauskunft vor der Sperre; Verwertungsverbot für das Löschereignis.

**Nicht umgesetzt (Soll-Auflagen):** eine Vorschau „in den nächsten 30 Tagen fällig“ für den
Betrieb, ein Startlog-Hinweis auf eine verkürzte Frist und die Zahl ausgesetzter Löschungen im
Jahresbericht an die Personalvertretung (#3026).

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
  persönliche Zugangstokens und nicht über den MCP-Server für [externe KI-Werkzeuge](./external-access.md).

### Bedienung

- Suchfeld auf der Seite „Chats"; der Suchknopf der Chatliste öffnet sie mit leerem Feld und setzt
  den Cursor hinein.
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

## Export

Ein einzelner Chat lässt sich zur Ablage oder Weitergabe als Datei sichern (#1925). Das Aktionsmenü
eines Chats in der Seitenleiste und auf der Seite „Chats" bietet dafür „Exportieren als Markdown" und
„Exportieren als PDF".

- **Inhalt beider Formate:** der Titel als Überschrift, Beginn des Chats und Exportzeitpunkt, dann
  die [Gesprächsnotiz](./conversation-memory.md) als eigener Abschnitt „Gesprächsnotiz", sobald sie
  einen Punkt hat (wie in der Oberfläche am Kopf des Chats; im Export auch dann, wenn die Oberfläche
  sie in den ersten beiden Runden noch nicht zeigt), dann jede Frage und Antwort in ihrer
  Reihenfolge mit Datum und Uhrzeit. Unter jeder Antwort stehen ihre
  Quellenangaben: Jede Zitiermarke wird zur Fußnote, gezählt je Antwort in der Reihenfolge des ersten
  Auftretens, die Liste nennt Dokument und Fundort; zitierte Quellen ohne Marke im Text folgen ohne
  Nummer. Geprüfte, nicht
  zitierte Quellen gehören nicht dazu. Am Ende steht der Vermerk aus Hinweis zur KI-Nutzung und
  Produktname aus dem Branding, wie beim Kopieren einer Antwort.
- **Markdown entsteht im Browser:** Die Oberfläche lädt den Chat dafür frisch
  (`GET /api/v1/chats/{chatId}`), damit die Datei den gespeicherten Stand enthält, und behält die
  Formatierung der Antworten; die Fußnoten sind Markdown-Fußnoten mit der Nummer der Antwort davor (`[^2-1]`), weil
  Fußnotenbezeichner in einem Dokument eindeutig sein müssen.
- **PDF entsteht im Server** (`GET /api/v1/chats/{chatId}/export/pdf`, mit PDFBox, Standardschriften
  ohne Einbettung), Fußnoten als `[1]`. Die Markdown-Zeichen der Antworten werden zu lesbarem Text.
  Die Standardschriften kennen nur den westeuropäischen Zeichensatz: Format- und Richtungszeichen
  entfallen, Sonderleerzeichen, Bindestrich- und Minusvarianten, Pfeile und Vergleichszeichen erhalten
  einen einfachen Ersatz (`->`, `<=` …), alles Übrige erscheint als „?". Die Rechenzeit bleibt
  linear in der Textlänge. Hervorhebungen und Links werden bis 300 Zeichen aufgelöst, längere
  behalten ihre Markdown-Zeichen. Die Zeitangaben stehen in der Zeitzone der
  Installation. Solange das PDF entsteht, zeigt die Oberfläche „Export wird erstellt …"; ein zweiter
  Klick auf denselben Export desselben Chats startet keinen weiteren.
- **Wer exportieren darf:** wer den Chat lesen darf (`GET /api/v1/chats/{chatId}`), also nur seine
  Autorin, auch nach dem Austritt aus dem Space. Ein fremder Chat antwortet `404` wie ein
  unbekannter. Der Export wird nicht protokolliert und nicht gespeichert.
- **Außerhalb des Umfangs:** Export mehrerer Chats oder ganzer Spaces, Import.

### Wirkung auf Behördenunterlagen

- [ ] Neue oder geänderte personenbezogene Daten oder Protokollereignisse
- [ ] Neue Sichtbarkeit: Daten werden für weitere Rollen oder Personen sichtbar
- [x] Neue Auswertung oder neuer Export, auch von bereits gespeicherten Daten — der Export eines
  eigenen Chats als Markdown- oder PDF-Datei, nur durch die Person selbst (Datum `chats`)
- [ ] Geänderte Aufbewahrungs- oder Löschfrist
- [ ] Neuer externer Datenfluss (neuer Dienst, neues Ziel ausgehender Verbindungen)
- [ ] Geänderte Voreinstellung, geändertes Standardmodell oder geänderter System-Prompt
- [ ] Geänderte KI-Einstufung (Zweck oder Einsatzbereich im Sinne der KI-Verordnung)
- [ ] Keine Wirkung auf Daten, Sichtbarkeit, Auswertung, Datenflüsse oder Voreinstellungen

**Voraussichtliche Stufe:** Zustimmung (Z3, neuer Export; er umfasst nur eigene Daten der Person,
niemand sieht dadurch mehr über andere, Z2 also nicht). Der Export wird weder protokolliert noch
gespeichert, Z1 entfällt.

**Betroffene Vorlagen in `docs/behoerden/`:** keine über das Fakten-Manifest hinaus; die
Faktenblätter übernehmen den Eintrag aus dem Datum `chats`.

---

## Persönliche Ordnungsmerkmale im Datenmodell

Anheften und Archivieren werden **nicht** als Spalten am Chat gespeichert, sondern als eigene Zeile je
Person und Chat:

| Merkmal | Inhalt | Zweck |
|---|---|---|
| angeheftet | Zeitpunkt des Anheftens oder leer | Anzeige und Reihenfolge der Gruppe „Angeheftet" |
| archiviert | Zeitpunkt des Archivierens oder leer | Trennung Aktiv/Archiv, Anzeige „archiviert am" |
| zurückgeholt | Zeitpunkt des Zurückholens aus dem Archiv oder leer; nur in Spaces mit automatischer Bereinigung, höchstens eine Archivfrist lang | Neubeginn der Archivfrist nach dem Zurückholen |

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
  Reverse-Proxys. Ebenso trägt die Seite „Chats" den eingegebenen Begriff nicht in ihrer Adresse, sonst
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
| Aufbewahrungsanzeige im Archiv | Ursprünglich **entfallen**, weil es keine Chat-Aufbewahrung gab; Anzeige „archiviert am". Seit #1923 zeigt ein archivierter Chat in einem Space mit eingeschalteter Bereinigung zusätzlich sein Löschdatum | Chat-Aufbewahrung als Voraussetzung ins Epic holen |

Im Konzept festgelegt, ohne gesonderte Maintainer-Frage: Suche nur im aktiven Space; Suchbegriffe nicht
protokolliert; Archiv in der Suche einbezogen; persönliche Merkmale als eigene Zeilen je Person und
Chat; kein automatisches Archivieren (seit #1923 je Space einschaltbar, siehe oben); keine Obergrenze beim Anheften; Merkmale auch im archivierten Space
setzbar; Chatsuche nicht für externe KI-Werkzeuge erreichbar; durchsucht werden Titel, Fragen und Antworten.

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
- **[external-access.md](./external-access.md)** — die Chatsuche ist kein Zugriffsweg für externe KI-Werkzeuge.
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
- **Automatische Bereinigung geteilter Chats** — mit dem Teilen von Chats (siehe „Automatisches
  Archivieren und Löschen je Space").

---

## Erfolgs-Metriken

Ohne Personenbezug, nach denselben Bedingungen wie in
[Nutzungstransparenz](./spaces-and-assets.md#nutzungstransparenz):

- **Keine Rückmeldung „ich finde meinen Chat nicht wieder"** im Betrieb der Pilotbehörden — die
  eigentliche Probe, weil es bewusst keine feinere Messung gibt.
- **Gemeldeter Bedarf an Ordnern oder Schlagworten** — die Schwelle für Phase 5.
