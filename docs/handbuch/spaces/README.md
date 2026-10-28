# Spaces

Ein **Space** ist der Ort, an dem in OPAA gearbeitet wird. Er bündelt die Chats einer Gruppe von
Menschen und die Arbeitsmittel, mit denen sie arbeiten: die Wissensbibliotheken, in denen ihre
Fragen suchen, und die Prompt-Bibliotheken, deren Prompts der Chat anbietet. Wer Mitglied ist und
mit welcher Rolle, legt der Space selbst fest. Jede Person hat zudem einen persönlichen
**Standard-Space**, der nur ihr gehört.

Was im [Katalog](../arbeitsmittel/katalog.md) steht, wird erst nutzbar, wenn es einem Space zugeordnet ist. Wie eine
Frage im Chat beantwortet wird, beschreibt das Kapitel [Suche](suche.md); die Rechte dahinter das
Kapitel [Berechtigungen](../konten-und-rechte/berechtigungen.md).

## 1. Was ein Space enthält

**Ein Space enthält genau, was ihm zugeordnet ist** — für jede Art von Arbeitsmittel. Die Chats des Space
suchen nur in den zugeordneten Wissensbibliotheken, auch über `@`-Bezüge, und bieten nur die
Prompts der zugeordneten Prompt-Bibliotheken an. Was eine Person lesen darf, aber dem Space nicht
zugeordnet ist, steht dort nicht zur Verfügung. Ein Space ohne zugeordnetes Wissen durchsucht nichts;
das gilt auch für den persönlichen Standard-Space, der zunächst leer ist. Der Chat und die Seite des
Space sagen das mit „Diesem Space ist kein Wissen zugeordnet."; Kuratoren und Administratoren
führt „Wissen zuordnen" direkt in den Reiter „Inhalte" der Einstellungen. Die Durchsetzung liegt im
Backend, nicht nur in der Oberfläche: Einen `@`-Bezug auf eine nicht zugeordnete Bibliothek und
einen Prompt aus einer nicht zugeordneten Prompt-Bibliothek lehnt es ab.

Die Mitgliederliste eines Space sehen **nur** seine Administratoren, sein Eigentümer und die
Systemverwaltung. Mitglieder und Kuratoren sehen ausschließlich, wie viele Mitglieder je Rolle es
gibt. Ein Space ist nur für seine Mitglieder auffindbar; ein Verzeichnis der Spaces gibt es
nicht, und eine Einstellung zur Sichtbarkeit eines Space deshalb auch nicht.

## 2. Mitglieder und Rollen

| Rolle | Darf |
|---|---|
| **Mitglied** | Den Space betreten, eigene Chats anlegen und führen, die bereitgestellten Arbeitsmittel sehen — gefiltert auf den eigenen Zugriff |
| **Kurator** | Zusätzlich Arbeitsmittel bereitstellen und lösen, Inhalte ordnen |
| **Administrator** | Zusätzlich Mitglieder und Rollen verwalten, Einstellungen setzen, geteilte Inhalte zurückziehen |

Daneben steht der **Eigentümer** als eigenes Merkmal: Er allein — und die Systemverwaltung — darf
den Space löschen. **Ein Space gehört immer einer natürlichen Person.**

**Auch eine Gruppe wird Mitglied eines Space, mit Rolle.** Ihre Mitglieder tragen diese Rolle ohne
eigene Zeile und verlieren sie im Moment, in dem sie die Gruppe verlassen. Hält jemand mehrere Wege
in denselben Space — direkt und über zwei Gruppen —, gilt die stärkste Rolle.

Drei Regeln halten einen Space handlungsfähig:

- **Er verliert nie sein letztes handlungsfähiges Administrator-Mitglied durch eine
  Verwaltungshandlung.** Entfernen oder Herabstufen wird abgelehnt und nennt den Grund. **Eine
  Gruppe zählt dabei als Administrator, solange sie handlungsfähig ist.**
- **Den Eigentümerwechsel darf jedes handlungsfähige Administrator-Mitglied vollziehen**, das eine
  natürliche Person ist — an sich selbst oder an ein anderes solches Mitglied.
- **Ein archivierter Space nimmt keine neuen Mitglieder auf.**

**Der persönliche Standard-Space** entsteht bei der ersten Anmeldung automatisch. Er ist immer
privat: Außer der Person selbst wird niemand Mitglied, auch keine Gruppe. Wer mit anderen arbeiten
will, legt einen Space an. Der Standard-Space lässt sich nicht übertragen, archivieren oder löschen;
er braucht keine Nachfolge und wird mit dem Konto gelöscht.

## 3. Arbeitsmittel zuordnen

**Ein Arbeitsmittel in einem Space bereitzustellen ist eine Handlung im Space.** Sie setzt die
Kuratorenrolle **im Space** und mindestens die Leserrolle **am Arbeitsmittel** voraus: Wer etwas
bereitstellt, muss es selbst lesen dürfen. Die Bereitstellung **verschafft niemandem ein
Leserecht** — sie legt fest, worin die Chats dieses Space suchen. Sind im Space
Mitglieder ohne Leserecht an diesem Arbeitsmittel, wird seine Eigentümerin darüber
benachrichtigt; eine Zustimmung braucht es nicht, die Bereitstellung wirkt sofort. Das gilt für jede
Art von Arbeitsmittel. Entstehen beim Anlegen eines Space mehrere solche Bereitstellungen, erhält jede
Eigentümerin **eine** Benachrichtigung, die alle ihre Arbeitsmittel nennt.

**Was eine Person nicht lesen darf, sieht sie auch in der Zuordnungsliste nicht** — in keiner
Rolle, auch nicht als Kurator, Administrator oder Systemverwaltung. Die Liste nennt weder Namen
noch Anzahl solcher Bereitstellungen. Sind nicht alle Zuordnungen lesbar, steht im Reiter
„Inhalte" der Einstellungen der Hinweis „Nicht alle zugeordneten Inhalte sind für Sie
lesbar."; ist nur Nicht-Lesbares zugeordnet, steht allein dieser Hinweis da. Lösen kann eine
Bereitstellung im Space nur, wer das Arbeitsmittel lesen darf; jeder andere Versuch wird wie bei
einem unbekannten mit „nicht gefunden" beantwortet. Eine Bereitstellung, die man nicht lesen
darf, löst die Verwaltung des Arbeitsmittels von dessen Seite aus, Reiter „Zuordnungen".

Zugeordnet wird im Reiter „Inhalte" der Einstellungen (Abschnitt 4) oder direkt aus dem
[Katalog](../arbeitsmittel/katalog.md) über „In Space verwenden".

**Im Reiter „Inhalte" wirkt ein Häkchen sofort.** Anhaken ordnet zu und bestätigt das kurz; Abhaken
löst die Zuordnung, und die Meldung dazu bietet „Rückgängig" an. Ausgenommen ist ein Arbeitsmittel
mit offener Nachfolge: Es lässt sich lösen, aber bis zum Abschluss der Nachfolge nicht wieder
zuordnen; die Meldung sagt das, statt „Rückgängig" anzubieten. Schlägt die Änderung fehl, springt
das Häkchen zurück, und eine Fehlermeldung nennt den Grund. Beim Öffnen ist der Filter „Nur
zugeordnete" eingeschaltet; ausgeschaltet zeigt die Liste alles, was man lesen darf und deshalb
zuordnen könnte. In beiden Ansichten stehen die schlanken Kacheln der Auswahl (siehe „Einen Space
anlegen"), in der Reihenfolge des Katalogs: Favoriten zuerst, dann nach Name. Darüber stehen dieselben Filter wie im Katalog:
Suche, Typ und Favoriten. Wer
weder Kurator noch Administrator noch Eigentümer des Space ist, sieht die zugeordneten Inhalte, die
er lesen darf, nur lesend.

## 4. Einstellungen eines Space

**In der Oberfläche liegt all das auf einer Seite je Space:** dem Zahnrad „Einstellungen" am Fuß der
Seitenleiste. Das Zahnrad sieht jedes Mitglied; wer den Space nicht verwalten darf, liest dort nur.
Die Seite hat drei Reiter, und keine Überschrift im Reiter wiederholt seinen Namen:

| Reiter | Inhalt |
|---|---|
| **Stammdaten** | Name, Beschreibung, der Schalter der automatischen Chat-Bereinigung mit den Fristen der Installation (Abschnitt 7), die **Anweisung an das Modell** ([Suche](suche.md), Abschnitt 6) — und am Ende der abgesetzte **Gefahrenbereich** mit „Space archivieren" und „Space löschen" |
| **Mitglieder** | Die Mitgliederliste und das Aufnehmen von Personen und Gruppen. Die Liste beginnt mit dem Eigentümer, danach folgen Administratoren, Kuratoren und Mitglieder, je Rolle nach Name, Personen und Gruppen gemischt; eine lange Liste bekommt darüber ein Suchfeld. Jede Zeile trägt nur die Rolle — für Administratoren als Auswahl, beim Eigentümer als schreibgeschützte Auswahl „Eigentümer" mit Schloss statt Pfeil und dem Hinweis, dass der Eigentümer über „Zum Eigentümer machen" bei einem anderen Mitglied wechselt — und ein Menü „⋯" mit „Warum hat … Zugriff?", „Mitglieder der Gruppe anzeigen", „Zum Eigentümer machen" und „Aus Space entfernen", soweit der Eintrag für Zeile und eigene Rolle gilt. Darunter steht „Mitglied hinzufügen" mit einem Suchfeld für Personen und Gruppen, der Rolle und „Hinzufügen" in einer Zeile; ohne Auswahl nennt „Hinzufügen" die fehlende Auswahl am Suchfeld |
| **Inhalte** | Eine Kachelliste aller Arbeitsmittel — Wissensbibliotheken und [Prompt-Bibliotheken](../arbeitsmittel/prompt-bibliotheken.md), solange Prompt-Bibliotheken eingeschaltet sind. Ein Häkchen heißt „dem Space zugeordnet"; anhaken und abhaken dürfen Kuratoren, Administratoren und der Eigentümer des Space |

**Im Reiter „Mitglieder" meldet sich jede Änderung kurz.** Hinzufügen, Entfernen, Rollenwechsel und
Übertragung bestätigt je eine Meldung, die von selbst verschwindet („Gruppe Meldewesen
hinzugefügt", „Rolle von Thomas Klein: Kurator"). Die Liste aktualisiert sich dabei ohne neues
Laden. Ein Fehler bleibt stehen, bis er geschlossen wird.

**Die ersten beiden Reiter gehören den Administratoren.** Kuratoren und Mitglieder öffnen dieselbe
Seite und sehen die Stammdaten nur lesend. Im Reiter „Mitglieder" steht für sie keine Liste und
kein Name, nur die Größe des Space: wie viele Personen und Gruppen aufgenommen sind („10 Personen
und 2 Gruppen") und wie sich die Rollen darauf verteilen („Rollen: 2 Administratoren, 1 Kurator,
9 Mitglieder"). Eine Gruppe zählt dabei einmal, gleich wie viele Konten sie hat. Die Arbeit eines Kurators
liegt im Reiter „Inhalte".
Den Schalter der Chat-Bereinigung im eigenen Standard-Space legt nur dessen Eigentümer um, auch die
Systemverwaltung nicht. Deshalb lässt sich ein Standard-Space auch nicht übertragen. Sein Reiter
„Mitglieder" zeigt nur den Hinweis „Ihr Standard-Space ist privat." mit dem Verweis „Space
anlegen", der Systemverwaltung nur „Der Standard-Space ist privat." Den Gefahrenbereich sieht nur der Eigentümer, und nicht im eigenen Standard-Space: Der lässt sich weder
archivieren noch löschen.

**Die Anweisung an das Modell setzt und ändert nur, wer im Space die Rolle Administrator hat** —
direkt, über eine Gruppe oder als Eigentümer. Kuratoren und Mitglieder lesen sie in den Stammdaten,
und die Systemverwaltung ändert sie ohne diese Rolle nicht; Name und Beschreibung ändert sie
weiterhin. Im eigenen Standard-Space ist die Person selbst Administrator und hinterlegt dort eine
persönliche Daueranweisung. Ein leeres Feld entfernt die Anweisung. Jede Änderung steht im
Revisionsprotokoll als Änderung des Space mit dem Feldnamen, der Text selbst nicht.

## 5. Einen Space anlegen

„Neuer Space" führt durch vier Schritte: **Grunddaten**, **Mitglieder**, **Inhalte** und
**Zusammenfassung**. Nur der Name ist Pflicht; die Schritte „Mitglieder" und „Inhalte" lassen sich
mit „Weiter" überspringen.

Die **Grunddaten** sind Name, Beschreibung und der Schalter der automatischen Chat-Bereinigung. Der
Schalter nennt die Fristen, die der Betrieb festgelegt hat; was sie bewirken, steht in
Abschnitt 7. Jeder Space ist nur für seine Mitglieder sichtbar.

Im Schritt **Mitglieder** nimmt der Assistent Personen **und** Gruppen auf, über dasselbe Suchfeld
wie der Reiter „Mitglieder" der Einstellungen: Suche, Rolle und „Vormerken" in einer Zeile, mit
denselben Angaben zu Gruppengröße und denselben Regeln für geschützte Gruppen ([Berechtigungen](../konten-und-rechte/berechtigungen.md), Abschnitt 8). Eine
Gruppe gibt ihre Rolle an alle ihre Mitglieder weiter.

Die **Zusammenfassung** nennt Mitglieder und Inhalte je mit einem kleinen Symbol; Gruppen tragen
zusätzlich „Gruppe", Inhalte ihre Art im Text. Die Chat-Bereinigung erscheint nur, wenn der Schalter
an ist, als Satz mit beiden Fristen.

Im Schritt **Inhalte** stehen alle Arbeitsmittel als Kacheln, die die Person lesen darf — dieselbe Menge
wie im Katalog, in derselben Reihenfolge. Mehrere Kacheln lassen sich zugleich wählen. Darüber
steht dieselbe Filterzeile wie im Katalog: Suche über Name und Beschreibung, Art („Alle", „Wissen",
„Prompts") und „Favoriten". Über „Weitere laden" steht, wie viele von wie vielen angezeigt sind. Eine Wahl bleibt bestehen,
wenn Filter oder Suche ihre Kachel ausblenden; die Zeile unter den Kacheln nennt alles Gewählte.
Die Kacheln zeigen, was für die Wahl zählt: Art, Stern, Name, Beschreibung und Umfang, also die
Zahl der Dokumente oder Prompts. Dazu kommen „Privat" oder „Wird gelöscht", der Hinweis auf eine
gesperrte Quelle und ein Zustand, der Aufmerksamkeit braucht, etwa „Aktualisierung fehlgeschlagen"
oder „Nachfolge offen". Solange die Nachfolge offen ist, lässt sich das Arbeitsmittel nicht neu
zuordnen: Seine Kachel ist ausgegraut, lässt sich nicht wählen und nennt den Grund „Lässt sich
nicht neu zuordnen, solange die Nachfolge offen ist." Kommt der Assistent über „Neuen Space damit
anlegen" mit einem solchen Arbeitsmittel, übernimmt er es nicht als gewählt; ein Hinweis im Schritt
„Inhalte" nennt den Grund. Zuständige Stelle, „Aktualisiert am", die Zahl der Spaces und das Welt-Symbol stehen im
Katalog und auf der Detailseite. Links oben steht ein Häkchen, wenn die Kachel gewählt ist, sonst ein leerer
Kreis; der Rahmen bleibt gleich. Ein Klick auf die Kachel wählt sie oder hebt die Wahl auf; ein
Klick auf den Stern setzt nur den eigenen Favoriten und ändert die Wahl nicht.

„Space anlegen" legt den Space samt vorgemerkten Mitgliedern und allen Zuordnungen in einem Schritt
an. Lässt sich ein Mitglied nicht aufnehmen oder ein gewähltes Arbeitsmittel nicht zuordnen, entsteht kein
Space; der Assistent bleibt mit allen Eingaben offen und nennt den Grund. Die Aufnahme jedes
Mitglieds steht wie beim späteren Hinzufügen im Nachweisprotokoll. Ist kein Wissen gewählt, sagt die Zusammenfassung „Kein
Wissen zugeordnet — der Space durchsucht kein Wissen, bis Sie etwas zuordnen."

Dieselbe Kachelauswahl steht im Reiter „Inhalte" der Einstellungen. Dort ist das Häkchen die
Zuordnung selbst und wirkt sofort (Abschnitt 3); eine Zeile mit
allem Gewählten und einen Knopf „Zuordnen" gibt es dort nicht.

## 6. Übersicht und Wechsel

Die Übersicht führt alle Spaces auf, in denen Sie Mitglied sind. Ihr Kopf ist wie im
[Katalog](../arbeitsmittel/katalog.md) aufgebaut: die Überschrift „Spaces", daneben die Zahl der
Einträge, oben rechts „Neuer Space". Ein Suchfeld filtert über Name und Beschreibung. Die Spaces
stehen als Kacheln; eine Tabellenansicht gibt es nicht.

Eine Kachel zeigt Name, Beschreibung und die Anzahl der Chats. Ihre eigene Rolle und die Mitglieder
stehen in den Einstellungen des Space. Ein archivierter Space ist als solcher
gekennzeichnet und führt auf seine Übersicht statt in einen neuen Chat.

Nach der Anmeldung öffnet OPAA einen **leeren Chat im zuletzt genutzten Space** — nicht den letzten
Chat, und nicht mehr grundsätzlich den persönlichen Space. Ist der gemerkte Space nicht mehr
zugänglich, weil er archiviert wurde, die Mitgliedschaft entzogen ist oder der Rechner zuletzt von
jemand anderem benutzt wurde, führt der Einstieg in den persönlichen Space. Denselben Weg nimmt der
Eintrag **„Chat"** ganz links in der globalen Leiste; er bleibt hervorgehoben, solange Sie in einem
Space, in der Übersicht oder im Assistenten für einen neuen Space sind.

Gewechselt wird in der **Seitenleiste**. Auf dem Desktop steht oben der Bereich „Spaces" mit
**höchstens fünf Spaces** als Zeilen: ausgewählt werden die zuletzt genutzten, angezeigt der
persönliche Space zuerst, danach nach Name, damit eine angeklickte Zeile nicht springt. Der aktive
Space ist immer dabei und hervorgehoben; ein archivierter erscheint nur, wenn Sie ihn zuletzt
genutzt haben, mit dem Hinweis „archiviert". Die Zahl hinter „Spaces" nennt alle Ihre nicht
archivierten Spaces. Rechts im Kopf legt das Plus „Neuer Space" einen Space an, die Lupe öffnet die
Übersicht mit dem Cursor im Suchfeld; die Seitenleiste bleibt dabei stehen und markiert den zuletzt
aktiven Space. Wer in vielen Spaces Mitglied ist, findet dort den vollständigen Bestand — die
Zeilen sind der schnelle Weg zurück, nicht das Verzeichnis. Der Bereich lässt sich zuklappen; die
Zeile des aktiven Space bleibt dann sichtbar.

Auf schmalen Bildschirmen, wo sich die Seitenleiste als Überlagerung öffnet, ist für fünf Zeilen
kein Platz. Dort steht stattdessen das **Space-Menü** am Kopf der Seitenleiste mit denselben
höchstens fünf Spaces, den zuletzt genutzten zuoberst, und darunter „Alle Spaces anzeigen" (wie die
Lupe) und „Neuer Space". Es nennt unter jedem Namen die Mitglieder, wie die Reichweite eines
Arbeitsmittels formuliert: „2 Gruppen, 3 Personen", oder „nur Sie", wenn Ihr eigenes Konto das
einzige Mitglied ist — gleich, ob es der Standard-Space ist oder nicht. Eine Gruppe zählt als eine
Mitgliedschaft, wie viele Personen sie auch umfasst; ihre Größe wird dort nicht genannt.

Die Nutzungsreihenfolge merkt sich der Browser, nicht der Server: Sie gilt je Gerät und je Browser
und wandert nicht mit. Gemerkt werden nur Space-Kennungen, und die Liste wird bei jedem Öffnen gegen
die Spaces abgeglichen, die der Dienst dem angemeldeten Konto ausliefert. Melden sich zwei Personen
nacheinander am selben Rechner an, wirkt die Reihenfolge deshalb genau in den Spaces weiter, in
denen beide Mitglied sind — alles andere fällt heraus. Wer das nicht möchte, meldet sich in einem
privaten Fenster an oder löscht die Browserdaten; ein Recht verschafft die Liste ohnehin nicht.

## 7. Chats

Ein **Chat** ist eine gespeicherte Unterhaltung in einem Space. Im Eingabefeld öffnet `@` die Wahl
des Suchbereichs ([Suche](suche.md), Abschnitt 2) und `/` die Prompts des Space
([Prompt-Bibliotheken](../arbeitsmittel/prompt-bibliotheken.md), Abschnitt 6).

Das **Chat-Archiv** ist eine persönliche Ablage: Wer einen Chat archiviert, nimmt ihn nur aus der
eigenen Chatliste und findet ihn auf der Seite „Chats" des Space im Reiter „Archiv" wieder. Der Chat
selbst ändert sich dabei nicht — Titel, letzte Aktivität, Verlauf, Suchbereich und Filter bleiben,
und niemand sonst sieht, ob eine Person einen Chat archiviert hat. Ein archivierter Chat bleibt
lesbar und fortsetzbar; eine eigene neue Frage darin holt ihn automatisch zurück. Archivieren löst
eine Anheftung. Ein **archivierter Chat** ist etwas anderes als ein **archivierter Space**: Der
Space nimmt für alle keine neuen Inhalte mehr an, auch keine Frage in einem archivierten Chat; das
Archivieren eines Chats ist dort trotzdem möglich und ändert an der Sichtbarkeit des Space nichts.

**Automatisches Archivieren und Löschen.** Je Space gibt es den Schalter „Inaktive Chats nach …
Tagen archivieren und nach weiteren … Tagen löschen", ab Werk aus; er nennt die Fristen der
Installation. Gesetzt wird er beim Anlegen des Space im
Assistenten oder später in den Einstellungen des Space im Reiter „Stammdaten"; umlegen darf ihn,
wer die Stammdaten ändern darf. Im persönlichen Standard-Space legt ihn nur die Person selbst um,
auch die Systemverwaltung nicht; ein Standard-Space lässt sich dafür auch nicht übertragen. Ist er an, nennt die Seite „Chats" allen Mitgliedern die Fristen
in einem kurzen Hinweis. Für alle Chats des Space gilt dann:

- Ein Chat, dessen letzte Aktivität länger als die Archivfrist zurückliegt, wandert in das
  Chat-Archiv seines Autors. Aktivität ist allein der vorhandene Zeitstempel der letzten Änderung
  des Chats, also vor allem die letzte Frage, und der Zeitpunkt, zu dem die Person den Chat aus dem
  Archiv zurückgeholt hat; weitere Daten über die Person werden dafür weder erhoben noch
  gespeichert.
- Ein Chat, der länger als die Löschfrist im Archiv liegt, wird gelöscht — auf demselben Weg, auf
  dem sein Autor ihn löschen würde, mit Verlauf und Gesprächsnotiz. Das gilt auch für Chats, die
  die Person selbst archiviert hat.
- **Angeheftete Chats** sind ausgenommen. Anheften, eine neue Frage oder das Zurückholen aus dem
  Archiv holt einen archivierten Chat zurück und lässt die Archivfrist neu beginnen; Zurückholen
  genügt also, um einen Chat für eine weitere Archivfrist zu behalten. Dafür wird der Zeitpunkt
  des Zurückholens an der persönlichen Archiv-Markierung gespeichert, nur solange die Bereinigung
  an ist und höchstens eine Archivfrist lang. Wer einen Chat dauerhaft behalten will,
  heftet ihn an.
- **Keine Rückwirkung:** Beide Fristen beginnen frühestens mit dem Einschalten. Ein Chat, der
  schon lange ruht oder lange im Archiv liegt, verschwindet nicht am Tag danach. Ausschalten hält
  den Automatismus an; erneutes Einschalten beginnt die Fristen von vorn.
- Ein archivierter Chat zeigt im Reiter „Archiv" und in seiner Kopfzeile, an welchem Tag er
  gelöscht wird. Eine gesonderte Benachrichtigung gibt es nicht.
- Der Lauf findet einmal täglich nachts statt. Er schreibt nur Gesamtzahlen ins Anwendungslog, nie
  einen Chat, einen Space oder eine Person; im Protokoll steht allein das Umlegen des Schalters mit
  altem und neuem Wert, handelnder Person und Zeitpunkt.

Die Fristen sind systemweit fest und nur vom Betrieb einstellbar, mit einer Untergrenze, die keine
Konfiguration unterschreiten kann ([Suche](suche.md), Abschnitt 10.3).

**Chats gesperrter Konten.** Unabhängig von diesem Schalter löscht OPAA die Chats eines Kontos, das
die Verwaltung gesperrt hat, nach einer eigenen Frist ab der Sperre — in jedem Space, auch im
persönlichen, angeheftete eingeschlossen. Die Bereinigung je Space und ihre Fristen ändern daran
nichts. Was dabei gilt, steht im Kapitel [Benutzerverwaltung](../konten-und-rechte/benutzerverwaltung.md#was-mit-den-chats-eines-gesperrten-kontos-geschieht).

Die **Chatliste** in der Seitenleiste zeigt zuerst die angehefteten Chats, darunter „Zuletzt
verwendet" mit den 15 zuletzt genutzten. „15 weitere" blendet die nächsten ein, „Weniger" klappt
wieder auf die ersten 15 zurück. Beide Gruppen lassen sich zuklappen; die Zahl hinter dem Namen
nennt die ganze Gruppe, bei „Zuletzt verwendet" auch die noch nicht eingeblendeten Chats. Welche
Gruppen zugeklappt sind, merkt sich der Browser, wie die Breite der Seitenleiste. Im Kopf „Chats"
beginnt das Plus „Neuer Chat" einen Chat, in einem archivierten Space ist es gesperrt. Ein
Filterfeld gibt es nicht; die Lupe daneben öffnet die Chatsuche ([Suche](suche.md), Abschnitt 7.2),
die auch archivierte Chats findet. Jeder Eintrag hat ein
Menü mit Umbenennen, Anheften, Archivieren, den beiden Exporten als Markdown und PDF
([Suche](suche.md#7-belege-prüfen-und-fundstellen-bilden), Abschnitt „Belege prüfen und Fundstellen bilden", Absatz „Exportieren") und Löschen; der Titel des offenen Chats lässt sich
zusätzlich direkt in der Kopfzeile bearbeiten (Enter speichert, Escape verwirft).

Die **Seitenleiste** lässt sich auf dem Desktop am rechten Rand breiter oder schmaler ziehen; mit
dem Griff im Fokus geht das auch mit den Pfeiltasten, Pos1 und Ende. Ein Doppelklick auf den Griff
stellt die Standardbreite wieder her. Die gewählte Breite merkt sich der Browser, sie gilt also je
Gerät und nicht je Konto. Auf schmalen Bildschirmen öffnet sich die Seitenleiste als
Überlagerung in fester Breite.

Das **Eingabefeld** sendet mit dem Senden-Knopf oder mit Enter. Sein Platzhalter lautet
„Nachricht eingeben … @ für Quellen, / für Aktionen"; hat die Installation Prompt-Bibliotheken
abgeschaltet, fehlt „/ für Aktionen"
([Prompt-Bibliotheken](../arbeitsmittel/prompt-bibliotheken.md#9-abschalten)). Ist das Feld
schmal, auf kleinen Bildschirmen oder bei breit gezogener Seitenleiste, steht dort nur die Kurzform
„@ Quellen, / Aktionen". Der Platzhalter verschwindet
beim Tippen; Screenreader lesen den Hinweis weiterhin als Beschreibung des Felds vor. Solange der
Suchbereich des Space noch ermittelt wird, steht unter dem Feld „Suchbereich wird ermittelt …".

Während eine Antwort entsteht, bleibt das Eingabefeld bedienbar: Die nächste Frage lässt sich schon
schreiben, auch ein Prompt per `/` lässt sich einsetzen. Senden geht erst, wenn die Antwort da ist.
Bis dahin lassen sich Suchbereich und Filter nicht ändern, und `@` öffnet keine Auswahl; der
Platzhalter nennt deshalb nur „/ für Aktionen" (ohne Prompt-Bibliotheken nur „Nachricht
eingeben …"). Die laufende Antwort behält den
Suchbereich, mit dem die Frage gestellt wurde. Lehnt OPAA die laufende Frage ab, steht sie wieder
im Eingabefeld, vor dem schon geschriebenen Text.

Ein per `/` eingesetzter Prompt gehört zu seinem Space: Wechselt der Chat in einen anderen Space,
entfällt der Chip „`Prompt: <Titel>`", der Text bleibt stehen.

## 8. Weiterführende Kapitel

- Was einem Space zugeordnet werden kann: [Katalog](../arbeitsmittel/katalog.md),
  [Wissensbibliotheken](../arbeitsmittel/wissensbibliotheken/README.md), [Prompt-Bibliotheken](../arbeitsmittel/prompt-bibliotheken.md)
- Wie eine Frage im Chat beantwortet wird: [Suche](suche.md)
- Rollen, Gruppen und Nachweis: [Berechtigungen](../konten-und-rechte/berechtigungen.md)
