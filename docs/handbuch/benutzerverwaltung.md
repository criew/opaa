# Benutzerverwaltung

> **Entwurf.** Dieses Kapitel beschreibt die Verwaltung **lokaler Konten** — derjenigen Konten, die
> OPAA selbst führt. Konten, die über einen Identitätsanbieter entstehen, werden dort verwaltet, wo
> sie herkommen; in der Kontenliste stehen sie mit ihrer Herkunft und ihrer Rolle, aber ohne
> Zustand, Ablauf und Aktivität — was sich an ihnen ändern lässt, sagen die Abschnitte 6 und 7.
> Einrichtung des Anmeldewegs, Erststart, Umgebungsvariablen und E-Mail-Versand stehen im Kapitel
> [Deployment](deployment.md); hier geht es um die täglichen Abläufe.

## 1. Wann diese Verwaltung gebraucht wird

Die lokale Benutzerverwaltung ist **abschaltbar und im Auslieferungszustand aus**. Zwei Lagen
brauchen sie:

- **Ein Haus ohne Identitätsanbieter** — oder eines, dessen Anbindung noch nicht steht. Dann ist sie
  der einzige Anmeldeweg.
- **Jede Installation für ihre Systemverwaltung.** Das Konto, mit dem die Verwaltung beim Erststart
  hereinkommt, ist immer ein lokales Konto. Es bleibt anmeldefähig, auch wenn die Verwaltung
  regulärer lokaler Konten aus ist.

Läuft ein Identitätsanbieter, empfiehlt das Kapitel [Deployment](deployment.md) die Verwaltung im
Regelbetrieb **aus** zu lassen: Lokale Konten laufen an den Ein- und Austrittsprozessen des Hauses
vorbei, und jedes von ihnen ist ein Zugang, den niemand automatisch entzieht. Die Gegenmittel dazu
stehen in Abschnitt 6.

Einschalten, Abschalten und die Regeln darunter liegen unter **Administration → Benutzer →
Einstellungen** in der Karte „Lokale Anmeldung"; die Konten selbst stehen im Bereich **Konten**
daneben. Jede dieser Änderungen steht im Nachweisprotokoll.

```mermaid
flowchart LR
    A[Verwaltung legt Konto an] -->|Einladung| B[Konto eingeladen]
    A -->|Anfangspasswort| C[Konto aktiv, Wechsel ausstehend]
    B -->|Person setzt Passwort| D[Konto aktiv]
    C -->|Person wechselt Passwort| D
    D -->|Verwaltung sperrt, Fehlversuche, Inaktivität| E[Konto gesperrt]
    E -->|Verwaltung entsperrt| D
    D -->|Ablaufdatum erreicht| F[Konto abgelaufen]
    F -->|Verwaltung verlängert| D
```

## 2. Ein Konto anlegen

„Konto anlegen" fragt fünf Dinge ab: **E-Mail-Adresse** (sie ist die Anmeldekennung), **Anzeigename**,
**Rolle**, **Ablaufdatum** und **Anlagegrund**. Die letzten beiden sind keine Formalität — siehe
Abschnitt 3.

Für den Zugang gibt es zwei Wege, die das Formular zur Wahl stellt:

| Weg | Was passiert | Wann passend |
|---|---|---|
| **Einladung per E-Mail** (Vorgabe) | Das Konto entsteht im Zustand „eingeladen", ohne Passwort. Die Person erhält einen Einmal-Link und legt ihr Passwort selbst fest. Die Verwaltung kennt es nie. | Der Regelfall, sobald der E-Mail-Versand steht. |
| **Anfangspasswort jetzt erzeugen** | OPAA erzeugt ein Passwort, zeigt es **einmalig** an und verlangt von der Person bei der ersten Anmeldung einen Wechsel. | Wenn kein Postfach erreichbar ist oder die Übergabe mündlich erfolgen soll. |

Das erzeugte Anfangspasswort erscheint in einem Fenster, das sich nur über seine eigene Schaltfläche
schließt, und ist danach nicht wieder abrufbar. Wer es verliert, setzt das Konto zurück
(Abschnitt 5) — ein zweiter Abruf ist nicht vorgesehen.

Die Adresse muss unter den lokalen Konten eindeutig sein, ohne Rücksicht auf Groß- und
Kleinschreibung. Dass dieselbe Adresse zusätzlich bei einem Identitätsanbieter existiert, ist
zulässig: Das sind zwei Konten, und OPAA führt sie getrennt.

### Den Link ohne E-Mail-Versand übergeben

Ist kein Mailserver eingerichtet, fehlt die öffentliche Adresse der Installation oder scheitert der
Versand, zeigt OPAA den Einladungslink **an** — genau einmal, mit dem Hinweis, dass er nicht wieder
abrufbar ist. Die Verwaltung übergibt ihn dann auf einem anderen, nachvollziehbaren Weg.

Zwei Dinge sind dabei wichtig:

- **Das Protokoll unterscheidet die Fälle.** Zu jeder Einladung, jeder Rücksetzung und jeder Übergabe
  steht im Nachweisprotokoll, ob die Nachricht zugestellt wurde, ob der Versand gescheitert ist oder
  ob der Link angezeigt wurde. Eine Weitergabe außerhalb des Systems ist damit von einer Zustellung
  unterscheidbar.
- **Ohne öffentliche Adresse der Installation ist der Link ein Pfad.** Er beginnt dann mit `/` und
  braucht die Adresse der Installation davor. Das Fenster sagt das; wie die Adresse gesetzt wird,
  steht im Kapitel [Deployment](deployment.md).

Ein Link ist **einmal** einlösbar, und ein neuer macht einen älteren ungültig. Die Seite, auf die er
führt, entfernt ihn sofort aus der Adresszeile — ein Neuladen hilft deshalb nicht weiter, die Person
öffnet den Link aus der Nachricht erneut.

## 3. Anlagegrund und Ablaufdatum

Beide Felder sind das, was lokale Konten prüfbar hält.

**Der Anlagegrund ist ein Pflichtfeld** (höchstens 200 Zeichen) und **zweckgebunden**: Er nennt den
dienstlichen Anlass des Kontos und den Grund seiner Befristung. Nicht hineingehören Angaben zu
Gesundheit, Beschäftigungsverhältnis, Leistung, Disziplinarsachverhalten oder Dritten. Der Hilfetext
des Formulars sagt das, und zwar aus einem konkreten Grund: **Die betroffene Person liest den Grund
in ihren eigenen Einstellungen.** Er ist Teil ihrer Selbstauskunft. Im Nachweisprotokoll erscheint er
nie als Wert, sondern höchstens als Name eines geänderten Feldes.

Brauchbare Gründe sind kurz und sachlich: „Sachbearbeitung Meldewesen, Vertretung bis Jahresende",
„Externe Prüfbegleitung Jahresabschluss", „Hospitation Bauamt, drei Monate".

**Das Ablaufdatum ist vorbelegt** und vom Verwalter änderbar. Es lässt sich auch entfernen — dann
verlangt das Formular ein ausdrückliches Häkchen „Kein Ablaufdatum", und das Konto erscheint in der
Auflagenprüfung (Abschnitt 6), bis es befristet wird. Ein abgelaufenes Konto ist nicht gelöscht: Es
kann sich nicht anmelden, laufende Sitzungen enden, und die Liste hebt es hervor. Wer es weiter
braucht, trägt ein neues Datum ein.

## 4. Sperren und Entsperren

**Sperren ist der Regelweg, Löschen die Ausnahme.** Eine Sperre beendet alle Sitzungen des Kontos
sofort, nicht erst beim Ablauf des Zugangstokens, und die Person erfährt beim nächsten Aufruf, dass
und warum sie sich neu anmelden muss. Sie erhält zusätzlich eine Nachricht zum Zeitpunkt der
Handlung; ein optionaler Satz aus dem Sperrdialog wird darin zitiert.

Drei Dinge sperren ein Konto:

| Anlass | Wer hebt sie auf | Nachricht an die Person |
|---|---|---|
| **Die Verwaltung** | Die Verwaltung, über „Entsperren" | ja |
| **Fehlversuche** — mehrere falsche Passwörter in Folge | Sie endet nach kurzer Zeit von selbst; „Entsperren" hebt sie sofort auf; ein eingelöster Rücksetzlink ebenfalls | nein |
| **Inaktivität** — das Konto war lange nicht in Gebrauch | Die Verwaltung, über „Entsperren" | ja |

Die Fehlversuch-Sperre schickt **bewusst keine** Nachricht: Sie wäre sonst ein Belästigungskanal für
jeden, der eine Adresse kennt. Sie schneidet die Selbsthilfe auch nicht ab — „Passwort vergessen"
bleibt wirksam, und wer den Link aus seinem Postfach einlöst, ist damit wieder drin. Der Besitz des
Postfachs ist der Nachweis, den die Sperre verlangt.

Die **Anmeldeseite nennt den Grund einer abgelehnten Anmeldung nicht**. Ob eine Adresse unbekannt
ist, das Passwort falsch, das Konto gesperrt, abgelaufen oder noch nicht bestätigt — die Antwort ist
immer dieselbe. Das ist Absicht: Eine Antwort, die nach dem richtigen Passwort den Zustand nennt,
bestätigt einem Angreifer genau dieses Passwort. Die Person erfährt den Grund auf dem Weg, den
dieser Abschnitt beschreibt — per Nachricht und beim nächsten Aufruf einer laufenden Sitzung.

Das eigene Konto lässt sich hier nicht sperren und nicht löschen. Und das **letzte** anmeldefähige
Systemverwalterkonto lässt sich weder sperren noch befristen, in der Rolle herabsetzen oder löschen:
OPAA lehnt den Versuch mit einem Hinweis ab, statt die Installation auszusperren.

## 5. Passwort zurücksetzen oder erzeugen

Zwei Wege, dieselbe Wirkung auf laufende Sitzungen — beide beenden sie:

- **Rücksetz-Link per E-Mail.** Die Person erhält einen Einmal-Link und wählt ihr Passwort selbst.
  Mit demselben Rückfall auf die Anzeige des Links wie bei der Einladung (Abschnitt 2).
- **Passwort erzeugen.** OPAA zeigt ein neues Passwort einmalig an; die Person muss es bei der
  nächsten Anmeldung wechseln.

In beiden Fällen sagt OPAA der Person, **warum** ein Wechsel verlangt wird — „Ihr Passwort wurde von
der Systemverwaltung zurückgesetzt" steht an anderer Stelle als „Bitte legen Sie Ihr erstes Passwort
fest". Ein administratives Zurücksetzen **bestätigt keine E-Mail-Adresse**: Ein Konto, das seine
Adresse noch nicht bestätigt hat, wird dadurch nicht anmeldefähig.

## 6. Die Liste und die Auflagenprüfung

Die Kontenliste im Bereich **Konten** führt **alle Konten der Installation** — lokale und die der
Identitätsanbieter. Die **Herkunft** steht an jeder Zeile: „Lokal" mit einem Schlüssel, sonst der
Name des Anbieters. Der Filter „Herkunft" grenzt auf lokale Konten, auf alle Anbieter oder auf einen
einzelnen Anbieter ein.

Ein **lokales Konto** zeigt Zustand, Rolle, Ablauf, Anlagedatum, Anlagegrund und eine
**Aktivitätsklasse**. Die Klasse ist grob — „nie", „länger nicht genutzt", „aktiv" — und bewusst so:
Ein exakter Zeitstempel der letzten Nutzung wäre der Rohstoff für eine Anwesenheitsauswertung. Nach
Aktivität lässt sich deshalb auch **nicht sortieren**, und es gibt **keinen Export** der Liste. Das
ist eine dauerhafte Eigenschaft dieser Ansicht.

Sortieren lässt sich über die Spaltenköpfe Name, E-Mail, Herkunft, Rolle, Zustand, Ablauf und
Angelegt. Drei davon ordnen keine Wörter, sondern Kategorien, und tun das nach einer festen
Reihenfolge statt alphabetisch:

| Spalte | Reihenfolge aufsteigend |
|---|---|
| Herkunft | lokale Konten, dann die Anbieter nach Namen, zuletzt Konten ohne Anbieterzeile |
| Rolle | Nutzer, Revision, Systemverwaltung |
| Zustand | gesperrt, abgelaufen, eingeladen, aktiv — Anbieterkonten zuletzt, sie tragen keinen |

Der Zustand sortiert damit das nach oben, was eine Entscheidung braucht.

Ein **Konto eines Identitätsanbieters** zeigt Herkunft und Rolle, aber weder Zustand noch Ablauf
noch Aktivität: Sein Lebenszyklus liegt beim Anbieter, und die Prüfpflicht dieses Kapitels gilt ihm
nicht. Steht in seiner Zustandsspalte „Anbieter deaktiviert", kann sich niemand mehr über diesen
Anbieter anmelden; „Anmeldung nicht möglich" heißt, dass zu seinem Issuer gar keine Anbieterzeile
mehr existiert — das Konto bleibt, der Weg hinein ist zu. In der Spalte Herkunft steht dann „Kein
Anbieter", und der Tooltip nennt den Issuer. Sein Zeilenmenü bietet „Rolle ändern"
(Abschnitt 7) und den Weg zur Anbieterverwaltung.

Drei Filter bedienen die Prüfpflicht — sie beschreiben lokale Konten und blenden Anbieterkonten
aus:

- **ohne Ablaufdatum** — die Konten, die keine Befristung tragen,
- **länger nicht genutzt** — Kandidaten für eine Sperre oder Löschung,
- **offene Einladungen** — Konten, deren Einladung niemand eingelöst hat.

Ein Hinweis über der Liste nennt die beiden Zahlen „Konten ohne Ablaufdatum" und „offene
Einladungen" und führt direkt in den passenden Filter. Er verschwindet, sobald beide null sind.

Dazu kommen zwei Automatiken, die die Prüfung am Laufen halten: Vor einem Ablauf erhalten die Person
und die Systemverwaltung eine Nachricht, und einmal im Quartal geht eine Wiedervorlage an die
Systemverwaltung — mit der Zahl der Konten ohne Ablaufdatum und einem Link auf die Liste, ohne Namen.
Systemverwalterkonten sind von keiner dieser Regeln ausgenommen.

## 7. Rollen

Die Rolle entscheidet, was ein Konto in der ganzen Anwendung darf; die Rechte an einzelnen Räumen und
Bibliotheken werden dort vergeben, nicht hier.

| Rolle | Bedeutung |
|---|---|
| **Nutzer** | Der Regelfall: fragen, eigene Inhalte verwalten, nutzen, was freigegeben ist |
| **Systemverwaltung** | Alles unter „Administration": Konten, Anbieter, Modelle, E-Mail, Suche, Erscheinungsbild |
| **Revision** | Lesender Zugriff auf das Nachweisprotokoll, ohne Verwaltungsrechte |

Die Rolle eines lokalen Kontos steht im Dialog „Bearbeiten". Die Rolle eines Anbieterkontos ändert
die Systemverwaltung über „Rolle ändern …" im Zeilenmenü — es sei denn, der Anbieter führt die
Rollen über seinen Rollen-Claim; dann ist der Eintrag deaktiviert und sagt das, weil ein hier
gesetzter Wert bei der nächsten Anmeldung der Person überschrieben würde. Für beide Kontotypen gilt
derselbe Schutz: Dem letzten anmeldefähigen Systemverwalter lässt sich die Rolle nicht entziehen.

Systemverwalterkonten sind **persönliche** Konten, je Person eines — keine Sammelkonten. Das
Notanker-Konto, das der Erststart anlegt, ist davon ausgenommen und kein Arbeitskonto; wofür es
gedacht ist und welche Auflagen dafür gelten, steht im Kapitel [Deployment](deployment.md).

## 8. Löschen oder sperren

**Löschen ist die Ausnahme.** Für das Ausscheiden einer Person ist die Sperre der vorgesehene Weg:
Sie entzieht den Zugang sofort und lässt nachvollziehbar, dass es dieses Konto gab.

Gelöscht werden kann ein lokales Konto nur, wenn es **nichts besitzt** — keine Dokumente und keinen
Raum außer seinem persönlichen. Sonst lehnt OPAA die Löschung ab und verweist auf die Sperre. In der
Praxis heißt das: Löschen ist der Weg für ein Konto, das nie benutzt wurde — eine falsch getippte
Adresse, eine Einladung an die falsche Person, ein Testkonto. Für alles andere gilt die Sperre.

Das Notanker-Konto der Systemverwaltung lässt sich **nicht löschen**. Sperren, Befristen und
Herabsetzen sind daran nicht grundsätzlich gesperrt — sie werden abgelehnt, solange es der letzte
anmeldefähige Systemverwalter ist. Von der Sperre nach Inaktivität ist es ausgenommen.

## 9. Selbstregistrierung

Selbstregistrierung ist **aus** und wirkt nur mit einer **nichtleeren Liste zulässiger
Adressdomänen**. Eine leere Liste bedeutet „niemand kann sich registrieren" — nicht „alle". Die
Verwaltung lässt sich ohne Domäne auch nicht einschalten und sagt, was fehlt.

Was ein selbstregistriertes Konto erhält: immer die Rolle „Nutzer", ein **Pflicht-Ablaufdatum** und
den festen Anlagegrund „Selbstregistrierung". Bis die Adresse über den Bestätigungslink bestätigt
ist, ist das Konto nicht anmeldefähig.

Drei Eigenschaften, die im Alltag auffallen:

- **Eine belegte Adresse ergibt kein zweites Konto**, und die Antwort ist dieselbe wie bei einer
  freien Adresse. An die belegte Adresse geht auch keine Hinweisnachricht — sie wäre selbst ein Weg,
  Adressen auszuprobieren.
- **Eine noch unbestätigte Registrierung kann es erneut versuchen** und erhält ihren
  Bestätigungslink neu. Name und Passwort der ersten Registrierung bleiben dabei erhalten.
- **Eine unbestätigte Registrierung ist keine Sackgasse, aber auch nicht reparierbar.** Ein
  administratives Zurücksetzen bestätigt die Adresse nicht. Ist die Selbstregistrierung inzwischen
  abgeschaltet, ist der Ausweg: das unbestätigte Konto **löschen** (es besitzt nichts, also geht das)
  und die Person regulär **einladen**.

Das Einschalten der Selbstregistrierung ist ein Punkt, der vor der Inbetriebnahme mit der
Personalvertretung zu klären ist; das Kapitel [Deployment](deployment.md) führt die Punkte auf.

## 10. Was Nutzende selbst können

Ein lokales Konto findet in seinen eigenen Einstellungen:

- **„Anlass des Kontos"** — den Anlagegrund, den die Systemverwaltung festgehalten hat.
- **„Passwort ändern"** — mit dem aktuellen Passwort. Die eigene Sitzung bleibt bestehen, alle
  übrigen Sitzungen des Kontos enden.

Ohne Anmeldung, wenn der Fluss eingeschaltet ist:

- **„Passwort vergessen"** — die Antwort ist immer dieselbe, ob es zu der Adresse ein Konto gibt oder
  nicht. Wer keine Nachricht erhält, hat entweder kein Konto unter dieser Adresse, oder das Konto ist
  von der Verwaltung gesperrt, abgelaufen oder noch eingeladen. In diesen Fällen hilft nur die
  Systemverwaltung.
- **„Konto registrieren"** — siehe Abschnitt 9.

Ein Konto eines Identitätsanbieters sieht keinen dieser Punkte; sein Passwort verwaltet der Anbieter.

## 11. Regeln und Fristen

Die Karte „Lokale Anmeldung" unter Administration → Benutzer → Einstellungen führt die Regeln, die für alle lokalen
Konten gelten. Sie wirken ab der nächsten Anwendung, nicht rückwirkend auf bestehende Passwörter.

| Einstellung | Bedeutung | Grenzen |
|---|---|---|
| Adress-Domänen der Selbstregistrierung | Zulässige Adressdomänen; Pflicht für die Selbstregistrierung | leer = keine Registrierung möglich |
| Mindestlänge des Passworts | Untergrenze der Passwortrichtlinie | 8 bis 64 Zeichen |
| Vorbelegtes Ablaufdatum (Tage) | Vorschlag beim Anlegen und Pflichtfrist selbstregistrierter Konten | mindestens 1 Tag |
| Sperre nach Inaktivität (Tage) | Frist, nach der ein ungenutztes Konto gesperrt wird | mindestens 30 Tage |
| Einladungslink gültig (Stunden) | Gültigkeit eines Einladungslinks | 1 bis 720 Stunden |
| Rücksetzlink gültig (Minuten) | Gültigkeit eines Rücksetz- und Bestätigungslinks | 1 bis 1440 Minuten |

Die **Passwortrichtlinie** selbst ist knapp und bewusst ohne Komplexitätsregeln: mindestens die
eingestellte Länge, höchstens 64 Zeichen, nicht gleich der eigenen E-Mail-Adresse und nicht auf der
mitgelieferten Liste besonders häufiger Passwörter. Einen erzwungenen regelmäßigen Wechsel gibt es
nicht. Die Eingabemasken zeigen die Regel an und bieten „Sicheres Passwort erzeugen".

> **Hinweis zur Länge:** Die Obergrenze ist zusätzlich durch die Zahl der Bytes begrenzt. Umlaute und
> Sonderzeichen zählen mehrfach; eine sehr lange Passphrase aus Sonderzeichen kann deshalb vor der
> Zeichengrenze abgewiesen werden. Die Eingabemaske sagt das im Feldfehler.

## 12. Was es hier nicht gibt

- **Keinen zweiten Faktor.** Lokale Konten melden sich mit Adresse und Passwort an. Für lokale
  Systemverwalterkonten lässt sich stattdessen der Zugang auf bestimmte Netze begrenzen; das Kapitel
  [Deployment](deployment.md) beschreibt, wie.
- **Keine Verwaltung des Lebenszyklus der Konten eines Identitätsanbieters.** Sperren, Befristen
  und Löschen erfolgen beim Anbieter; hier sind diese Konten mit Herkunft und Rolle sichtbar, und
  nur die Rolle lässt sich ändern.
- **Kein Export und kein Massenabruf der Kontenliste** (Abschnitt 6).
- **Keine Übersicht der eigenen Sitzungen.** Eine Person kann ihre übrigen Sitzungen über einen
  Passwortwechsel beenden, aber nicht einzeln einsehen oder abmelden.
- **Kein Zusammenführen eines lokalen Kontos mit einer Anbieteridentität.** Ein Weg, ein lokales
  Konto an einen Identitätsanbieter zu übergeben, ist vorgesehen, aber noch nicht gebaut (#1594).
