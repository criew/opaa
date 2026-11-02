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

Schritt für Schritt: [Mitglieder eines Space verwalten](../pflegen/space-mitglieder.md).

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
  natürliche Person ist — an sich selbst oder an ein anderes solches Mitglied. Das neue
  Mitglied braucht ein aktives Konto; ein gesperrtes oder noch nicht bestätigtes übernimmt die
  Verantwortung nicht.
- **Ein archivierter Space nimmt keine neuen Mitglieder auf** und wechselt den Eigentümer nicht
  mehr.

**Der persönliche Standard-Space** entsteht bei der ersten Anmeldung automatisch. Er ist immer
privat: Außer der Person selbst wird niemand Mitglied, auch keine Gruppe. Wer mit anderen arbeiten
will, legt einen Space an. Der Standard-Space lässt sich nicht übertragen, archivieren oder löschen;
er braucht keine Nachfolge und wird mit dem Konto gelöscht. Auch die Systemverwaltung ordnet ihm
nichts zu und liest seine Anweisung an das Modell nicht; eine bestehende Zuordnung lösen kann sie.

**Wer nicht Mitglied ist, erfährt nichts über den Space.** Seine Einstellungen, Mitglieder,
Inhalte, Chats, Chatsuche und Prompts antworten einer solchen Person wie bei einem Space, den es
nicht gibt. Ein Mitglied, dessen Rolle für eine Handlung nicht reicht, erfährt dagegen den Grund.

## 3. Arbeitsmittel zuordnen

Schritt für Schritt: [Inhalte eines Space zuordnen](../pflegen/space-inhalte.md).

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
zuordnen könnte. In beiden Ansichten stehen die Kacheln des Katalogs als Auswahl (siehe „Einen Space
anlegen"), in der Reihenfolge des Katalogs: Favoriten zuerst, dann nach Name. Darüber stehen dieselben Filter wie im Katalog:
Suche, Typ und Favoriten. Wer
weder Kurator noch Administrator noch Eigentümer des Space ist, sieht die zugeordneten Inhalte, die
er lesen darf, nur lesend.

## 4. Einstellungen eines Space

Schritt für Schritt: [Einen Space einstellen, archivieren oder löschen](../pflegen/space-einstellungen.md).

**In der Oberfläche liegt all das auf einer Seite je Space:** Das Zahnrad rechts neben dem Namen
eines Space in der Seitenleiste öffnet sie; im schmalen Fenster steht es neben der Space-Auswahl.
Das Zahnrad sieht jedes Mitglied; wer den Space nicht verwalten darf, liest dort nur.
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

**Löschen geht nur ohne Chats.** Enthält ein Space noch Chats, auch solche, die der Eigentümer nicht
sieht, lehnt OPAA das Löschen ab; Chats bleiben ihren Autoren erhalten. Der Weg hinaus ist
„Space archivieren": Der Space nimmt dann keine neuen Inhalte und Mitglieder mehr an, verschwindet aus
der Liste der Mitglieder ohne eigenen Chat darin und bleibt für Eigentümer, Systemverwaltung und die
eigenen Chats der Mitglieder lesbar. Zurückholen lässt sich ein archivierter Space nicht.

**Die Anweisung an das Modell setzt und ändert nur, wer im Space die Rolle Administrator hat** —
direkt, über eine Gruppe oder als Eigentümer. Kuratoren und Mitglieder lesen sie in den Stammdaten,
und die Systemverwaltung ändert sie ohne diese Rolle nicht; Name und Beschreibung ändert sie
weiterhin. Im eigenen Standard-Space ist die Person selbst Administrator und hinterlegt dort eine
persönliche Daueranweisung. Ein leeres Feld entfernt die Anweisung. Jede Änderung steht im
Revisionsprotokoll als Änderung des Space mit dem Feldnamen, der Text selbst nicht.

## 5. Einen Space anlegen

Schritt für Schritt: [Einen Space anlegen](../arbeiten/space-anlegen.md). Lässt sich ein Mitglied nicht
aufnehmen oder ein Arbeitsmittel nicht zuordnen, entsteht kein Space; die Aufnahme jedes Mitglieds
steht wie beim späteren Hinzufügen im Nachweisprotokoll.

## 6. Übersicht und Wechsel

Übersicht, Seitenleiste und Space-Menü: [Erste Schritte](../arbeiten/erste-schritte.md). Die zuletzt
genutzten Spaces merkt sich nur der Browser, als Kennungen, abgeglichen gegen die Spaces des
angemeldeten Kontos; ein Recht verschafft die Liste nicht.

## 7. Chats

Chatliste, Archiv, Chatsuche und Export aus Sicht der Person: [Chats verwalten](../arbeiten/chats.md). Hier
stehen die Regeln dahinter.

Das **Chat-Archiv** ist eine persönliche Ablage: Archivieren ändert den Chat nicht, und niemand
sonst sieht, ob eine Person einen Chat archiviert hat. Ein **archivierter Chat** ist etwas anderes
als ein **archivierter Space**: Der Space nimmt für alle keine neuen Inhalte mehr an.

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

## 8. Weiterführende Kapitel

- Was einem Space zugeordnet werden kann: [Katalog](../arbeitsmittel/katalog.md),
  [Wissensbibliotheken](../arbeitsmittel/wissensbibliotheken/README.md), [Prompt-Bibliotheken](../arbeitsmittel/prompt-bibliotheken.md)
- Wie eine Frage im Chat beantwortet wird: [Suche](suche.md)
- Rollen, Gruppen und Nachweis: [Berechtigungen](../konten-und-rechte/berechtigungen.md)
