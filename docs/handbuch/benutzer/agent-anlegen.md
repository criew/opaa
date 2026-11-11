# Agent anlegen

**Ziel:** Ein Agent, der Fragen Ihres Fachgebiets in mehreren Schritten mit Werkzeugen abarbeitet.
{: .kopf }

**Wer:** Anlegerecht „Agenten anlegen“; einrichten ab *Verwalter* des Agenten
{: .kopf }

Agenten gibt es nur, wenn die Systemverwaltung sie eingeschaltet hat. Das Anlegerecht hat ab Werk
niemand; ohne es fehlt die Kachel „Agenten“ unter „Neuer Katalogeintrag“.

1. Im [Katalog](katalog.md) „Neuer Katalogeintrag“, dann „Agenten“ und „Weiter“.
2. **Stammdaten:** Name und Zweck. Der Zweck steht später in der Auswahl im Chat unter dem Namen.
   **Freigaben:** als Eigentümer „Mein Konto“ oder „Eine Gruppe“, dazu die „Verantwortliche
   Stelle“, auf Wunsch Rollen vormerken.
   **Prüfen:** die Angaben durchsehen, einzelne über „Ändern“ korrigieren, dann „Anlegen“.
3. Der Agent startet leer: ohne Auftrag, mit allem Wissen, das die aufrufende Person lesen darf,
   und mit den Werkzeugen „Im Wissen suchen“ und „Dokument lesen“.
4. Auf seiner Seite jede Zeile mit „Ändern“ einrichten:
    - **Auftrag:** was der Agent tun soll. Er ergänzt die festen Regeln von OPAA, ersetzt sie nicht.
    - **Wissen und Werkzeuge:** Bibliotheken zur Eingrenzung, die Werkzeuge, die er nutzen darf,
      und die Vorlagen, nach denen er sein Ergebnis als Datei ablegt (siehe unten).
    - **Ablauf:** gleich loslegen oder erst den Plan bestätigen lassen; unter „Erweitert“ eigene
      Grenzen für Werkzeugaufrufe, Suchen, Laufzeit und Lesemenge.
5. Im Reiter „Freigaben“ Personen oder Gruppen berechtigen, im Reiter „Zuordnungen“ Spaces wählen.

## Was ein Agent liest

Ein Agent liest nie mehr als die Person, die ihn aufruft. Gewählte Bibliotheken grenzen ein, sie
erweitern nichts: Wer eine Bibliothek nicht lesen darf, erhält auch über den Agenten nichts daraus.
Eine Bibliothek, die ein anderer Verwalter zugeordnet hat und die Sie nicht lesen dürfen, zählt die
Seite nur, statt sie zu nennen; beim Speichern bleibt sie erhalten.

## Ergebnis als Datei nach Vorlage

Unter „Vorlagen“ wählen Sie eine oder mehrere [Vorlagen](vorlage-anlegen.md), die Sie lesen
dürfen; dieselbe Vorlage kann mehreren Agenten dienen. Die Zeile erscheint nur, wenn die
Systemverwaltung Vorlagen eingeschaltet hat. Der Agent füllt eine Vorlage aus, wenn er zusätzlich
das Werkzeug „Vorlage ausfüllen“ hat, und hängt das Ergebnis als Word-Datei und PDF an die
Antwort. Ohne zugeordnete Vorlage bietet ein Lauf das Werkzeug nicht an; ist keine zugeordnete
Vorlage nutzbar, etwa weil Vorlagen ausgeschaltet sind, antwortet der Agent im Chat und sagt das
dazu.

Ein Lauf nutzt nur die zugeordneten Vorlagen, die die aufrufende Person lesen darf. Wie bei den
Bibliotheken zählt die Seite eine Vorlage, die Sie nicht lesen dürfen, nur; beim Speichern bleibt
sie erhalten. Eine gelöschte Vorlage fällt aus der Zuordnung.

**Rollen am Agenten:**

| Rolle | Darf |
|---|---|
| Leser | den Agenten im Chat aufrufen und seine Einrichtung sehen |
| Verwalter | zusätzlich Name, Zweck, Auftrag, Wissen, Werkzeuge, Vorlagen, Ablauf und Rechte ändern |
| Eigentümer | zusätzlich den Agenten löschen und übergeben |

**Verantwortliche Stelle:** eine Gruppe aus der Liste oder eine Organisationseinheit bzw. Funktion
als Text, etwa „Referat Z3 – Organisation“, keine Einzelperson. Vorbelegt ist die Gruppe des
Eigentümers, sonst Ihre erste Gruppe. Ändern lässt sie sich im Reiter „Freigaben“. Fehlt die Stelle
länger als die Frist der Systemverwaltung, wird der Agent gesperrt.

**Gut zu wissen:**

- Den Agenten „Recherche“ legt OPAA beim Einschalten an. Er ist ein gewöhnlicher Agent: sichtbar
  für alle Konten und von seinem Eigentümer änderbar.
- Ein Werkzeug, das die Systemverwaltung abgeschaltet hat, bietet ein Lauf nicht an, auch wenn der
  Agent es nennt.
- Im Nachweisprotokoll stehen Anlegen, Ändern und Löschen, bei einer Änderung nur die Namen der
  geänderten Felder, nie der Auftrag selbst. Das Aufrufen steht dort nicht.
