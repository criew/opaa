# Katalog

OPAA bietet mehrere Arten von **Arbeitsmitteln** an: [Wissensbibliotheken](wissensbibliotheken/README.md),
aus denen der Assistent Fragen beantwortet, und [Prompt-Bibliotheken](prompt-bibliotheken.md) mit
wiederkehrenden Formulierungshilfen; weitere Arten folgen. Alle Arbeitsmittel werden auf dieselbe
Weise gefunden, angelegt, geteilt und einem Space zur Verfügung gestellt. Der **Katalog** ist der
Ort dafür. Dieses Kapitel beschreibt, was für alle Arten gilt; was eine Art besonders macht, steht in
ihrem Kapitel. Wer ein Arbeitsmittel sehen, pflegen und weitergeben darf, regelt das Kapitel
[Berechtigungen](../konten-und-rechte/berechtigungen.md).

## 1. Was im Katalog steht

Der Katalog ist ein Punkt der Hauptnavigation und zeigt **genau die Arbeitsmittel, die die Person
lesen darf** — über eine eigene Rolle, eine Rolle über eine Gruppe oder eine Freigabe an „Alle
Konten". Was sie nicht lesen darf, erscheint nirgends, auch nicht über die Suche, und seine Adresse
antwortet „nicht gefunden" wie bei einem unbekannten Arbeitsmittel. Das gilt auch für die
Systemverwaltung: **Verwalten ist nicht Lesen.** Ein Eintrag enthält Name und Beschreibung, nie
Inhalte wie Dokumente oder Prompts.

| Element | Inhalt |
|---|---|
| Suche | über Name und Beschreibung, ohne Rücksicht auf Groß- und Kleinschreibung |
| Art | „Alle" oder eine Art, etwa „Wissen" oder „Prompts" |
| Favoriten | zeigt nur die eigenen Favoriten |
| Reihenfolge | fest: eigene Favoriten zuerst, dann nach Name |
| Neu | legt ein Arbeitsmittel an (Abschnitt 4); nur mit einem Anlegerecht |

Die Einträge kommen seitenweise; „Weitere laden" hängt die nächste Seite an.

## 2. Die Kachel

Jedes Arbeitsmittel steht als Kachel im Katalog. Sie nennt:

- die **Art** als Etikett, etwa „Wissen" oder „Prompts", und ein **Welt-Symbol**, wenn das
  Arbeitsmittel an „Alle Konten" freigegeben ist; beim Überfahren nennt es „Für alle Konten
  freigegeben",
- **Name** und **Beschreibung**,
- den **Umfang**, etwa die Zahl der Dokumente oder Prompts, und in wie vielen Spaces es bereitsteht,
- die **zuständige Stelle**, eine Person oder eine Gruppe,
- wann es zuletzt **aktualisiert** wurde, oder in Worten einen Zustand, der Aufmerksamkeit braucht,
  etwa „Nachfolge offen".

Was eine Art darüber hinaus zeigt, steht in ihrem Kapitel. Über den **Stern** wird die Kachel zum
Favoriten, über das Menü **„⋯"** lässt sich das Arbeitsmittel in einem Space verwenden (Abschnitt 6).
Ein Klick auf die Kachel öffnet die Detailansicht.

## 3. Favoriten

Mit dem Stern markiert eine Person ein Arbeitsmittel als Favorit; ein zweiter Druck entfernt die
Markierung. Favoriten stehen im Katalog oben und lassen sich allein anzeigen. Sie sind eine rein
persönliche Ordnung:

- **Nur die Person selbst sieht sie.** Weder die Systemverwaltung noch die Verantwortlichen eines
  Arbeitsmittels oder eines Space erfahren, wer was markiert hat; es gibt keine Zahl der Favoriten.
- **Kein Nachweis.** Setzen und Entfernen erscheinen in keinem Protokoll, Bericht oder Export.
- **Nur Lesbares.** Verliert die Person das Leserecht, verschwindet der Favorit; entfernen kann sie
  ihre Markierung trotzdem.
- **Mit dem Konto gelöscht.** Favoriten gehen mit dem Konto oder dem Arbeitsmittel.

## 4. Ein Arbeitsmittel anlegen

**„Neuer Katalogeintrag"** oben rechts im Katalog fragt zuerst „Was möchten Sie anlegen?" und
bietet jede Art als Kachel an — **nur die Arten, für die die Person ein Anlegerecht hat** ([Berechtigungen](../konten-und-rechte/berechtigungen.md),
Abschnitt 9). Hat sie keines, fehlt der Knopf. „Weiter" führt in den Assistenten der gewählten Art,
den ihr Kapitel beschreibt; „Abbrechen" führt zurück in den Katalog.

Jeder Assistent endet mit Eigentümer und ersten Freigaben an Personen und Gruppen. Eine Freigabe an
„Alle Konten" wird erst am fertigen Arbeitsmittel erteilt. Konnte eine vorgemerkte Freigabe nicht
erteilt werden, ist das Arbeitsmittel trotzdem angelegt, und ein Hinweis nennt die Empfänger.

## 5. Die Detailansicht

Die Detailansicht ist für jede Art gleich aufgebaut, in einer Spalte. Oben steht der **Kopf** mit
Art, Welt-Symbol, eigener Rolle, der Hauptaktion der Art (etwa „Jetzt indizieren"), Stern und Menü
„⋯", darunter Name und Beschreibung — wer verwaltet, ändert beide über den Stift. Ein langer Name
wird nicht gekürzt, sondern bricht um. Darunter steht eine Zeile mit den Kennzahlen: Umfang, die
Spaces (führt zum Reiter „Zuordnungen"), die zuständige Stelle (führt zum Reiter „Freigaben") und,
wo die Art einen hat, ihr **Zustand** als ein Wort, etwa „Aktuell · 23.10.2026"; das Wort führt zu
seiner Ursache. Auf einem schmalen Bildschirm steht nur der Zustand dort, die Kennzahlen öffnet
„⌄". Im Menü „⋯" stehen „In Space verwenden" und, für den Eigentümer, „Löschen".

Darunter liegen die **Reiter**. Die ersten zeigen den Inhalt der Art, etwa die Dokumente einer
Wissensbibliothek oder die Prompts einer Prompt-Bibliothek; jede Art beschreibt sie in ihrem
Kapitel. Zwei Reiter haben alle Arten:

| Reiter | Inhalt |
|---|---|
| Freigaben | Eigentümer, wer welche Rolle hat, und die Herleitung „Warum sehe ich das?" ([Berechtigungen](../konten-und-rechte/berechtigungen.md), Abschnitt 4) |
| Zuordnungen | „In Space verwenden" und die Spaces, in denen das Arbeitsmittel bereitsteht |

Jede Rolle sieht jeden Reiter; wer nur liest, sieht ihn schreibgeschützt. Einzelne Werte stehen als
Zeile mit Titel, Wert und Textaktion, etwa „Eigentümer · Erika Muster · Übergeben". Ein Dialog, der
nur etwas zeigt, etwa eine Liste oder eine Vorschau, schließt über „✕" oben rechts; ein Dialog mit
Eingaben schließt über „Abbrechen".

## 6. In einem Space verwenden

Ein Space enthält genau die Arbeitsmittel, die ihm zugeordnet sind
([Spaces](../spaces/README.md)). Zuordnen lässt sich direkt aus dem Katalog: Im
Menü „⋯" einer Kachel, im Menü der Detailansicht und im Reiter „Zuordnungen" steht **„In Space
verwenden"**. Es zeigt die Spaces, in denen die Person Kurator oder Administrator ist; ein Klick
ordnet zu. „Neuen Space damit anlegen" öffnet den Space-Assistenten mit dem Arbeitsmittel als
bereits gewähltem Inhalt. Solange die Nachfolge eines Arbeitsmittels offen ist, lässt es sich nicht
neu zuordnen: Dann sind alle Einträge ausgegraut und nicht wählbar, und über der Liste steht der
Grund.

Im Reiter „Zuordnungen" sehen alle Leseberechtigten, wo das Arbeitsmittel bereitsteht, aber nur so
weit, wie der Space selbst für sie sichtbar ist. Private Spaces, zu denen eine Person nicht gehört,
erscheinen nur als Zahl („+ 2 weitere Spaces, die Sie nicht sehen dürfen"). Wer verwaltet, sieht
jede Zuordnung mit Namen, wer sie wann angelegt hat und ob nicht alle Mitglieder des Space lesen
dürfen. Gelöst wird eine Zuordnung über „Aus Space lösen" im Menü ihrer Zeile — von der Verwaltung
des Arbeitsmittels und von jedem, der den Space kuratiert oder administriert.
