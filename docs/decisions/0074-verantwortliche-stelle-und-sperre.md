# ADR-0074: Verantwortliche Stelle und Sperre von Agenten und Vorlagen

## Status

Akzeptiert · zuletzt geändert 2026-11-15 · ergänzt Entscheidung 6 von
[ADR-0036](0036-berechtigungsmodell-gruppen-und-faehigkeiten.md)

## Kontext

Agenten und Vorlagen ([ADR-0070](0070-werkzeugaufrufe-im-chat.md)) wirken für alle, die sie nutzen:
Ein Agent arbeitet mit eigener Anweisung, eigenen Werkzeugen und eigenen Grenzen, eine Vorlage
bestimmt, welches Dokument ein Lauf erzeugt. Wer in einer Verwaltung ein solches Arbeitsmittel
betreibt, muss sagen können, welche Stelle dafür einsteht. Der Eigentümer allein genügt dafür
nicht: Er ist eine Person, und scheidet sie aus, steht das Objekt nach ADR-0036, Entscheidung 6,
auf „Nachfolge offen“ — nutzbar, ohne Frist und ohne Folge. Für eine Bibliothek ist das richtig,
denn sie öffnet nur Wissen, das ohnehin freigegeben ist. Ein Agent ohne Zuständigen liefe dagegen
unbegrenzt weiter, mit einer Anweisung, die niemand mehr verantwortet.

Zugleich fehlte der Systemverwaltung ein Weg, ein einzelnes Objekt aus dem Betrieb zu nehmen, ohne
es zu löschen: Der Schalter „Agenten“ wirkt nur auf die ganze Installation, Löschen vernichtet
Anweisung, Fassungen und den Bezug des Protokolls. Beides muss auf jeder Instanz gleich gelten
([ADR-0053](0053-mehrinstanzbetrieb-aktiv-aktiv.md)).

## Entscheidung

### 1. Jeder Agent und jede Vorlage nennt eine verantwortliche Stelle

Beim Anlegen ist die Stelle Pflicht: genau eines von einer Gruppe der eigenen Organisation, die die
anlegende Person auswählen darf und die wirksam ist, oder einer Organisationseinheit bzw. Funktion
als Freitext (höchstens 200 Zeichen), nie eine Person allein. Geprüft wird beim Anlegen und Ändern
in `AssetResponsibilityService#validate`; geändert wird sie von Verwaltern des Objekts. Gespeichert
ist sie in `asset_responsibilities`, einer Zeile je Objekt an der Asset-Schale (ADR-0036,
Entscheidung 12). Der mitgelieferte Agent „Recherche“ nennt die Funktion „Systemverwaltung“.
Begründung: Eine Stelle überdauert die Person, die das Objekt angelegt hat, und das
Agentenverzeichnis (ADR-0036, Entscheidung 5) kann sie nennen, ohne Personen zu nennen.

### 2. „Stelle fehlt“ ist abgeleitet, gespeichert wird nur der Beginn der Frist

Die Stelle fehlt, solange keine genannt ist, die genannte Gruppe unbekannt, aufgelöst,
ausgeschaltet oder deaktiviert ist oder der Eigentümer auf „Nachfolge offen“ steht. Eine Funktion
fehlt nur im letzten Fall. Der Zustand wird bei jedem Lesen hergeleitet wie „Nachfolge offen“;
gespeichert wird nur, seit wann er besteht (`missing_since`). Das Benennen einer neuen Stelle
beendet die Frist. Begründung: Gruppen- und Kontozustand haben ihre eigene Quelle; eine
gespeicherte Kopie liefe ihr hinterher.

### 3. Nach der Frist wird gesperrt, nie gelöscht

Fehlt die Stelle länger als die Frist, sperrt OPAA das Objekt (Ursache `NO_RESPONSIBLE`). Die Frist
ist eine Verwaltungseinstellung in der Datenbank (`responsibility_lock_settings`, Seite „Ohne
Zuständigkeit“), 1 bis 365 Tage, Werkseinstellung 30 (`ResponsibilityLockPeriod`); ihre Änderung
ist ein Protokolleintrag (`RESPONSIBILITY_LOCK_PERIOD_CHANGED`). Die Seite des Objekts zeigt, wann
gesperrt wird. Eine Sperre bleibt, bis die Systemverwaltung sie aufhebt; fehlt die Stelle dann
noch, sperrt der nächste Lauf wieder. Begründung: Eine Sperre ist umkehrbar und lässt Anweisung,
Fassungen, Rechte und Protokollbezug stehen; die Frist lässt Zeit, nach einem Ausscheiden oder
Gruppenumbau eine Stelle zu benennen.

### 4. Die Systemverwaltung sperrt und entsperrt einzelne Objekte

Nur die Systemverwaltung sperrt von Hand (Ursache `MANUAL`), mit Pflichtgrund von höchstens 500
Zeichen; erneutes Sperren ersetzt den Grund. „Entsperren“ hebt jede Sperre auf, gleich welcher
Ursache. Den Grund sehen nur die Systemverwaltung und Verwalter des Objekts, Lesende nur, seit wann
gesperrt ist; das Agentenverzeichnis zeigt den Sperrzustand ohne Grund. Begründung: Ein Notaus für
ein Objekt darf nicht davon abhängen, ob seine Stelle fehlt.

### 5. Ein gesperrtes Objekt wird nicht genutzt, auf jeder Instanz ab dem nächsten Aufruf

Ein gesperrter Agent fehlt in der Auswahl des Chats, jeder Lauf wird mit `409` abgewiesen
(`AgentService#requireUsableForRun`); eine gesperrte Vorlage bietet kein Lauf mehr an
(`ReadableRunTemplates`). Das Objekt selbst bleibt verwaltbar. Die Sperre wird bei
jedem Aufruf aus der Tabelle gelesen und nie zwischengespeichert (`AssetResponsibilityService#isLocked`,
`#lockedAmong`). Begründung: Ein Primärschlüsselzugriff je Aufruf kostet weniger als ein
Cache am Invalidierungsbus und lässt kein Fenster, in dem eine Instanz die Sperre noch nicht kennt.

### 6. Ein Lauf für alle Instanzen, abgesichert durch ein bedingtes Update

`ResponsibilityLockScheduler` startet `ResponsibilityLockRun` stündlich zur zwanzigsten Minute unter
der ShedLock-Sperre `asset-responsibility-lock` (ADR-0053, Entscheidung 7). Der Lauf geht seitenweise
über alle Zeilen und entscheidet jedes Objekt in einer eigenen kurzen Transaktion: Frist beginnen,
beenden oder sperren. Beginn und Fälligkeit stammen aus der Datenbankuhr (`clock_timestamp()`,
`DatabaseClock`, ADR-0053, Entscheidung 9). Sperre und Protokolleintrag stehen hinter einem bedingten
`UPDATE` (`locked_at IS NULL`, Frist abgelaufen), sodass eine zweite Instanz oder ein wiederholter
Lauf nichts doppelt sperrt oder protokolliert. Begründung: Die ShedLock-Sperre spart doppelte Arbeit,
das bedingte Update hält die Zusage auch, wenn sie einmal nicht greift.

### 7. Protokoll: Ändern, Sperren, Entsperren, nicht das Nutzen

Eine geänderte Stelle (`ASSET_RESPONSIBILITY_CHANGED`), jede Sperre (`ASSET_LOCKED` mit Ursache und
Grund; die automatische mit dem Prozess `responsibility-lock` als Akteur) und jedes Entsperren
(`ASSET_UNLOCKED`) schreiben je einen Eintrag; die erste Stelle steht im Anlegeeintrag. Das Nutzen
eines Objekts schreibt keinen. Begründung: Nachweispflichtig ist, wer wann eine Zuständigkeit
geändert oder ein Objekt aus dem Betrieb genommen hat; ein Eintrag je Nutzung wäre eine
Verhaltensauswertung ([ADR-0068](0068-mitbestimmungsfaehigkeit.md), Entscheidung 2).

### 8. Ein Mechanismus in der Asset-Schale

Stelle, Frist und Sperre leben einmal in `io.opaa.asset` und gelten für Agenten und Vorlagen gleich;
die Typen rufen nur `establish`, `change`, `isLocked` und `lockedAmong`. Bibliotheken und
Prompt-Bibliotheken tragen keine Stelle. Begründung: Jede Mechanik der Schale gibt es genau einmal
(ADR-0036, Entscheidung 12); ein weiterer Typ mit eigener Wirkung bekommt sie, ohne sie zu kopieren.

## Konsequenzen

Einfacher: Jedes Agenten- und Vorlagenobjekt hat eine benannte Stelle, die Verzeichnis und
Mitbestimmung zeigen können. Kein Objekt bleibt unbegrenzt ohne Zuständigen in Betrieb, und eine
Sperre lässt sich ohne Datenverlust zurücknehmen.

Schwieriger: Anlegen verlangt eine Stelle. Jede Nutzung liest die Sperre aus der Datenbank. Die
Systemverwaltung muss gesperrte Objekte nach dem Benennen einer neuen Stelle selbst entsperren.

Restrisiken: Eine Funktion als Freitext wird nie als fehlend erkannt, solange der Eigentümer
handlungsfähig ist, auch wenn es die Einheit nicht mehr gibt. Weil der Lauf stündlich läuft, beginnt
die Frist bis zu einer Stunde später, und die Sperre folgt bis zu einer Stunde nach ihrem Ende.

## Verworfene Alternativen

- Löschen nach der Frist — vernichtet Anweisung, Fassungen und Protokollbezug und ist nicht umkehrbar.
- Keine Frist, nur ein Eintrag in der Liste „Ohne Zuständigkeit“ wie bei der Nachfolge — ein Agent ohne Zuständigen liefe unbegrenzt weiter.
- Sofortige Sperre beim Fehlen der Stelle — legte nach jedem Gruppenumbau oder Ausscheiden Agenten ohne Vorwarnung still.
- Eine Person als Stelle — scheidet aus und ist damit genau der Fall, den die Stelle abdeckt.
- Automatisches Entsperren, sobald wieder eine Stelle genannt ist — wer die Stelle nennt, höbe eine Sperre der Systemverwaltung damit selbst auf.
- Lauf je Instanz ohne gemeinsame Sperre und ohne bedingtes Update — sperrte und protokollierte doppelt, mit Fristen nach abweichenden Instanzuhren.
- Sperrzustand im Cache am Invalidierungsbus — ein Lesezugriff je Aufruf ist billiger als das Fenster bis zur Invalidierung.

## Offen

- Keine offenen Punkte.
