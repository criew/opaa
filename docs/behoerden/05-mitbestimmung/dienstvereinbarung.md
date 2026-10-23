---
stand: 2026-10-23
gilt-fuer: main
erzeugung: redaktionell
rechtsstand: 2026-10-23
pruefvermerk: nicht rechtlich geprüft
naechste-durchsicht: 2027-04-23
---

# Dienstvereinbarung

> Nicht rechtlich geprüft. Kann als Vorlage dienen; die Verantwortung bleibt bei der Behörde.

**Gilt für:** Behörden mit Personalrat. Bundesbehörden folgen dem Bundespersonalvertretungsgesetz,
Landes- und Kommunalbehörden dem Personalvertretungsgesetz ihres Landes. Betriebe mit Betriebsrat
sind nicht erfasst.

Eine Dienstvereinbarung ist ein Vertrag zwischen Dienststelle und Personalrat, der eine
mitbestimmungspflichtige Maßnahme für alle Beschäftigten regelt. Ein KI-Assistent, der Daten über
Beschäftigte speichert, ist in der Regel objektiv geeignet, Verhalten oder Leistung zu überwachen;
nach der Rechtsprechung genügt das für die Mitbestimmung, auch wenn niemand das vorhat.
Verantwortlich ist die Dienststelle; der Personalrat stimmt zu.

Der Mustertext unten folgt dem Aufbau bewährter IT-Rahmendienstvereinbarungen: Die Verbote stehen im
**Haupttext**, die Fakten in **Anlagen zu einer festen Version**, und ein **Stufenmodell** regelt,
welches Update nur angezeigt wird und welches eine neue Zustimmung braucht. So muss nicht jedes
Release neu verhandelt werden, und keine Anlage erweitert den Überwachungsumfang unbemerkt. Angaben
in eckigen Klammern sind Verhandlungssache; Schrägstriche trennen Optionen.

## Was OPAA liefert

- **Fakten für die Anlagen:** die Faktenblätter der eingesetzten Version, etwa
  {{faktenblatt:datenkatalog}}, {{faktenblatt:rollensicht}} und {{faktenblatt:datenfluesse}}, im
  Update-Paket am GitHub-Release der Version.
- **Stand der eigenen Installation:** die
  [Auskunft über die Datenerhebung](../../handbuch/betrieb/nachvollziehbarkeit.md#auskunft-über-die-datenerhebung-einer-installation)
  mit allen abweichenden Einstellungen und der Beschreibung des Protokolls (dem Auszug für die
  Personalvertretung).
- **Zu jedem Release** ein Delta mit Stufenvorschlag nach denselben Klassen wie in Nummer 15 des
  Mustertexts, dazu die Vorlage [Update-Vermerk](../09-betrieb-update/update-vermerk.md). Die Klassen
  stammen aus einer gemeinsamen Quelle, den
  [Stufenregeln](../../features/behoerdenunterlagen.md#die-stufenregeln). Das Delta im Paket
  vergleicht mit dem vorigen finalen Release, bei einem LTS-Release zusätzlich mit der vorigen
  LTS-Linie; für einen anderen Sprung erzeugt der Betrieb es selbst
  ([Das Update-Paket eines Release](../../handbuch/betrieb/nachvollziehbarkeit.md#das-update-paket-eines-release)).
- **Supportlinien:** Patch-Releases bringen nur Sicherheitskorrekturen und Behebungen kritischer
  Fehler, für ältere Linien über eigene Wartungsbranches
  ([Sicherheitsupdates](../../handbuch/betrieb/releases-und-nachweise.md#sicherheitsupdates)). Den
  Schutz gibt zusätzlich die Sichtung des Deltas vor dem Einspielen.
- Die [Informationsmappe für den Personalrat](informationsmappe-personalrat.md) erklärt all das in
  Alltagssprache.

## Aufgaben der Behörde

1. **Version festlegen,** die eingeführt wird, und deren Update-Paket herunterladen.
2. **Personalrat früh einbinden:** Informationsmappe übergeben, Testinstanz anbieten, schon vor einer
   Teststellung mit echten Beschäftigten.
3. **Mustertext anpassen:** Angaben in eckigen Klammern ersetzen, Optionen wählen, Nummern streichen,
   die die eingesetzte Version nicht betreffen, Landesrecht prüfen lassen.
4. **Anlagen zusammenstellen** und jede mit Version und Commit aus dem Kopf des Faktenblatts
   beschriften.
5. **Rollen benennen:** wer Systemverwaltung, Revision und Datenbankbetrieb wahrnimmt und wer bei
   einer anlassbezogenen Klärung freigibt.
6. **Abschließen** und bekannt machen; danach jedes Update nach Nummer 15 behandeln.

## Mustertext

---

### Dienstvereinbarung über den Einsatz von [Name der Installation] in [Behörde]

zwischen [Behörde], vertreten durch [Leitung], und dem Personalrat bei [Behörde], vertreten durch
[Vorsitz]

#### 1. Gegenstand und Geltungsbereich

(1) Diese Vereinbarung regelt Einführung, Nutzung und Änderung von [Name der Installation], einem
KI-Assistenten auf Grundlage von OPAA in der Version [X.Y.Z] (Anlage 1).

(2) Sie gilt für alle Beschäftigten von [Behörde], die [Name der Installation] nutzen oder deren Daten
darin verarbeitet werden, auch für Beschäftigte, die das System verwalten.

#### 2. Zweck

(1) [Name der Installation] beantwortet Fragen aus Dokumenten, die [Behörde] in Wissensbibliotheken
bereitstellt, und nennt die Fundstellen. [Weitere Zwecke.]

(2) Daten über Beschäftigte werden nur verarbeitet, soweit der Betrieb, die Sicherheit und der
Nachweis von Verwaltungshandeln es erfordern. Jede andere Nutzung ist ausgeschlossen.

#### 3. Freiwilligkeit

[Die Nutzung ist freiwillig. / Die Nutzung ist für folgende Aufgaben vorgesehen: …] Wer
[Name der Installation] nicht oder wenig nutzt, hat dadurch keinen Nachteil. Vorgaben, wie oft oder
wie viel jemand den Assistenten nutzt, gibt es nicht.

#### 4. Keine Leistungs- und Verhaltenskontrolle

(1) [Name der Installation] wird nicht zur Kontrolle von Leistung oder Verhalten der Beschäftigten
eingesetzt.

(2) Erkenntnisse, die entgegen dieser Vereinbarung gewonnen wurden, dürfen für keine personelle
Maßnahme verwendet werden, insbesondere nicht für Beurteilungen, Abmahnungen, disziplinarische oder
arbeitsrechtliche Schritte (Verwertungsverbot). Das gilt auch für Zufallsfunde aus einem zulässigen
Zugriff, soweit sie über dessen Zweck hinausgehen.

#### 5. Vereinbarte Auswertungen

(1) Personenbezogene Auswertungen sind nur diese:

1. Prüfung von Verwaltungshandlungen durch die Revision nach Objekt, Zeitraum, Ereignisart oder
   Vorgang,
2. die anlassbezogene Klärung nach Nummer 8,
3. die Kontenpflege nach Nummer 6,
4. Statistiken, die nur zusammengefasst und erst ab der Mindestgruppengröße erscheinen,
5. die Selbstauskunft der Person nach Nummer 11,
6. [weitere vereinbarte Auswertung mit Zweck].

(2) Anlage 2 beschreibt, was die eingesetzte Version technisch an Auswertungen erlaubt. Sie erweitert
die Liste in Absatz 1 nicht. Eine neue technische Möglichkeit in einem Update ist eine Änderung der
Klasse Z3 nach Nummer 15.

#### 6. Kontenpflege und Inaktivität

(1) Die Verwaltung sieht, welche lokalen Konten länger nicht genutzt wurden. Sie nutzt diese Anzeige
nur, um Konten zu befristen, zu sperren oder zu löschen, nicht für Rückschlüsse auf Anwesenheit oder
Arbeitsweise.

(2) Lokale Konten werden nach [Frist, nicht kürzer als die Untergrenze der Version] ohne Anmeldung
automatisch gesperrt. [Eine Abschaltung sieht die Version nicht vor; ein hoher Wert wirkt ähnlich.]
Die Person kann die Entsperrung ohne Begründung verlangen.

(3) Eine Sperre wegen Inaktivität ist keine Sperre durch die Verwaltung und startet keine Löschfrist.
Bloße Abwesenheit, etwa Elternzeit, Krankheit, Abordnung oder Freistellung, ist kein Grund für eine
Sperre durch die Verwaltung.

#### 7. Zugriffswege, benannte Personen, Vier-Augen-Prinzip

(1) Die Nummern 4 und 5 gelten für jeden Zugriffsweg: Oberfläche, Schnittstellen, Protokoll,
Datenbank, Sicherungen, Originalablage, Anwendungslog, Logs von Reverse-Proxy und Konnektoren,
Monitoring und Tracing, Anmeldedaten beim Identitätsanbieter einschließlich Anmeldezeiten und
erneuter Anmeldung, Logs beim Modellanbieter sowie Kopien in Test- und Entwicklungssystemen.

(2) Direkten Zugriff auf Datenbank, Sicherungen und Logs haben nur die in Anlage 4 benannten
Personen, und nur für Betrieb, Sicherung und Fehlerbehebung.

(3) Ein Zugriff, bei dem Inhalte oder Daten bestimmter Beschäftigter sichtbar werden können, geschieht
im Vier-Augen-Prinzip mit einer zweiten benannten Person, wird mit Anlass, Umfang und Beteiligten
dokumentiert und dem Personalrat [binnen … Arbeitstagen] mitgeteilt.

(4) Echte Daten werden in Test- und Entwicklungssysteme [nicht / nur anonymisiert] übernommen.

#### 8. Anlassbezogene Klärung

(1) Das Protokoll wird nach einer Person nur als anlassbezogene Klärung abgefragt, und nur zu diesen
Zwecken: [Sicherheitsvorfall; Verdacht auf unbefugten Zugriff; Untersuchung einer
Datenschutzverletzung; externe Prüfung oder Kontrolle]. Ausgeschlossen sind arbeitsrechtliche,
disziplinarische und leistungsbezogene Zwecke, auch bei gemischten Sachverhalten.

(2) Person, Zeitraum und Zweck werden vorab festgelegt; eine zweite Person der Revision gibt frei.

(3) Der Personalrat [wird vor der Freigabe unterrichtet / benennt ein Mitglied, das vor der Freigabe
gehört wird]. [Er kann binnen [zwei] Arbeitstagen widersprechen; der Widerspruch schiebt die
Freigabe auf, außer bei Gefahr im Verzug.]

(4) Die Ergebnisse werden nach Abschluss der Klärung [binnen … Wochen] gelöscht, soweit sie nicht für
ein Verfahren nach Absatz 1 gebraucht werden.

(5) Die betroffene Person wird nach Abschluss mit Anlass und Umfang unterrichtet. Die Unterrichtung
wird nur zurückgestellt, solange sie den Zweck der Klärung gefährden würde, höchstens [drei Monate];
Grund und Dauer werden dokumentiert und dem Personalrat mitgeteilt.

#### 9. Diagnose im Rechtekontext einer Person

„Sicht als“ zeigt der Systemverwaltung, was eine bestimmte Person sehen würde. Sie dient nur der
Behebung von Störungen, geschieht [im Vier-Augen-Prinzip / mit Einwilligung der Person], wird mit
Anlass dokumentiert, und die Person wird [vorher / danach] unterrichtet.

#### 10. Negativliste

Ausgeschlossen sind insbesondere:

1. Ranglisten, Vergleiche oder Bewertungen einzelner Beschäftigter, auch als spielerisches Element
   und auch nach der Autorschaft geteilter Inhalte,
2. Auswertungen je Person über Nutzungshäufigkeit, Zeiten, Dauer oder Inhalte von Fragen und Chats,
   außer der Kontenpflege und der Sperre wegen Inaktivität nach Nummer 6,
3. Profile aus den Inhalten von Fragen, auch mit Hilfe einer KI,
4. Auswertungen von Anmelde- und Abmeldezeiten, außer der Kontenpflege und der Sperre wegen
   Inaktivität nach Nummer 6,
5. jede Einsicht in die Chats Dritter,
6. Rückschlüsse auf Anwesenheit oder Arbeitsort, etwa über Netzadressen,
7. Statistiken unterhalb der Mindestgruppengröße; sie beträgt [Wert, nicht kleiner als die
   Voreinstellung der Version, {{faktenblatt:voreinstellungen#opaa-permission-minimum-group-size}}],
8. die Verknüpfung mit anderen Systemen über Beschäftigte, etwa Zeiterfassung oder
   Personalverwaltung,
9. Entscheidungen über Beschäftigte allein auf Grundlage einer Antwort des Assistenten,
10. die Nutzung geteilter Inhalte für Beurteilungen,
11. das Training von Modellen mit Fragen und Chats, auch beim Modellanbieter,
12. die Weitergabe von Protokoll- oder Nutzungsdaten an Vorgesetzte außerhalb einer anlassbezogenen
    Klärung.

#### 11. Selbstauskunft

Diese Nummer gilt für die Selbstauskunft, mit der jede Person die zu ihr gespeicherten Daten selbst
herunterlädt, im Umfang der Kategorien, die die eingesetzte Version ausgibt. Die Übersicht der
Auskunft nennt die fehlenden.

(1) Niemand darf verlangen, dass Beschäftigte ihre Selbstauskunft vorlegen oder weitergeben. Eine
dennoch vorgelegte Auskunft wird nicht verwertet.

(2) Wer wann eine Selbstauskunft abruft, wird nicht ausgewertet. Die Protokolleinträge dazu dienen
nur dem Nachweis, dass das Auskunftsrecht erfüllt wurde, und der Erkennung missbräuchlicher Abrufe.
Sie stehen mit Zweck, Zugriff und Frist im Auszug für die Personalvertretung.

(3) Niemand exportiert die Selbstauskunft für eine andere Person, weder die Verwaltung noch die
Datenschutzstelle noch der Personalrat, auch nicht über „Sicht als“.

(4) Die Notabschaltung wird nur bei einer Störung oder einem Sicherheitsvorfall genutzt, höchstens
für [fünf Arbeitstage]; danach wird die Selbstauskunft wieder eingeschaltet oder der Personalrat
stimmt einer Verlängerung zu. Der Personalrat wird [unverzüglich] unterrichtet. Ein Auskunftsantrag
bei der Behörde bleibt immer möglich.

(5) Ein Export durch oder über die Datenschutzstelle wäre eine neue Änderung der Stufe Zustimmung.

#### 12. Fristen und Löschung

(1) Es gelten die Fristen der Anlage 3, auch für Sicherungen. Eine längere Frist bedarf der
Zustimmung des Personalrats.

(2) Für Chats ausgeschiedener Beschäftigter gilt, sobald die eingesetzte Version ihre Löschung nach
der Sperre durch die Verwaltung kennt:

1. Die Dienststelle sperrt das Konto einer ausgeschiedenen Person binnen [Frist] nach dem
   Ausscheiden. Kann ein Konto in der eingesetzten Version nicht gesperrt werden, etwa ein Konto
   eines Identitätsanbieters, gilt die Löschfrist dafür nicht; die Dienststelle regelt den Ersatz
   [Regelung].
2. Bei planbarem Ausscheiden erhält die Person vor der Sperre Gelegenheit zur Selbstauskunft; solange
   diese die Chats nicht umfasst, und nach der Sperre bis zum Löschdatum erhält sie Auskunft über die
   oder den Datenschutzbeauftragten.
3. Die Chats werden nach [Frist, nicht kürzer als die Untergrenze der Version] gelöscht. Eine
   Verlängerung bedarf der Zustimmung des Personalrats.
4. Die Löschung eines Kontos wird nur [auf Antrag der betroffenen Person / auf gerichtliche oder
   behördliche Anordnung] angehalten, nicht allein wegen eines Disziplinarverfahrens; der Personalrat
   wird unterrichtet.
5. Der Protokolleintrag über die Löschung dient nur deren Nachweis.

#### 13. Datenflüsse nach außen

Daten verlassen die Installation nur auf den Wegen der Anlage 5. Für das Sprachmodell gilt:
[Betrieb im Haus / Anbieter …, Vertrag vom …, mit Regelung zu Logs und ohne Training]. Ein neuer
Datenfluss oder ein neues Ziel bedarf der Zustimmung.

#### 14. Rechte und Rollen

(1) Wer welche Rolle hat, steht in Anlage 4. Änderungen teilt die Dienststelle dem Personalrat
[vierteljährlich] mit.

(2) Wer Bibliotheken verwaltet, gibt Dokumente mit Daten über Beschäftigte nur frei, soweit die
Aufgabe es erfordert. [Ausgeschlossene Quellen, etwa Personalakten.]

(3) Systemverwaltung und Revision werden vor Aufnahme ihrer Aufgabe zu dieser Vereinbarung geschult.

#### 15. Änderungen und Updates

(1) Die Anlagen gelten in der vereinbarten Fassung. Eine neue Fassung tritt an ihre Stelle, sobald
das Verfahren nach dieser Nummer abgeschlossen ist. Eine Formulierung „in der jeweils gültigen
Fassung“ gilt für keine Anlage. Anlage 7 ändert sich nur mit Beteiligung des Personalrats.

(2) Vor jedem Update legt die Dienststelle dem Personalrat den Update-Vermerk mit dem Delta von der
eingesetzten zur Zielversion vor. Das Delta im Update-Paket vergleicht nur mit dem vorigen finalen
Release; bei übersprungenen Versionen erzeugt der Betrieb das Delta über den ganzen Sprung.

(3) Fristen nach dieser Nummer beginnen erst, wenn die Unterlagen nach Absatz 2 vollständig vorliegen.
Der Personalrat kann eine Frist einmal um [zehn] Arbeitstage verlängern.

(4) Die Stufen im Delta sind Vorschläge; maßgeblich ist die Zuordnung nach den Absätzen 5 bis 7.
**Im Zweifel gilt die Stufe Zustimmung.** Eine Änderung, die keiner Liste eindeutig zugeordnet werden
kann, wird mit dem Vermerk vorgelegt, warum die Zuordnung offen ist. Ist die Zuordnung streitig, gilt
bis zur Klärung die Stufe Zustimmung [; geklärt wird nach Nummer 17 Absatz 3].

(5) **Bagatelle**, abschließend:

1. redaktionelle Präzisierung eines Manifest-Eintrags ohne inhaltliche Änderung
2. neuer Konfigurationsschlüssel mit der Markierung „ohne Datenwirkung“
3. neue Spalte oder Tabelle ohne Personenbezug

Bagatellen werden mit dem Update-Vermerk mitgeteilt; eine Beteiligung entfällt. Punkt 2 beruht auf
einer Markierung des Herstellers und wird deshalb wie eine Anzeige behandelt.

(6) **Anzeige:** Änderungen ohne neue Datenwirkung, die Beschäftigte dennoch wahrnehmen: kürzere
Frist, entfallene Daten, Felder, Ereignisse oder Datenflüsse, eingeschränkte Sichtbarkeit,
geänderter System-Prompt oder geändertes Standardmodell ohne Klasse Z1–Z7. Der Personalrat kann
binnen [zehn] Arbeitstagen widersprechen; ohne Widerspruch gilt die Zustimmung als erteilt. Nach
einem Widerspruch gilt Absatz 7.

(7) **Zustimmung**, wenn die Änderung mindestens eine dieser Klassen erfüllt:

| Klasse | Änderung |
|---|---|
| Z1 | neue personenbezogene Daten oder neue Protokollereignisse |
| Z2 | neue Sichtbarkeit: eine Rolle sieht mehr über andere Personen als vorher |
| Z3 | neue Auswertung oder neuer Export |
| Z4 | längere Frist |
| Z5 | neuer externer Datenfluss oder neues Ziel eines bestehenden |
| Z6 | geänderte KI-Einstufung |
| Z7 | geänderte Voreinstellung mit Datenwirkung, einschließlich eines Standardmodells, das einen Datenfluss ändert |

Zur Klarstellung: Eine neue Rolle oder Fähigkeit und ein neuer Leser eines Protokollereignisses sind
Z2; ein neuer Konnektor, der Daten über Beschäftigte liest, ist Z5.

(8) Dieselben Stufen gelten für Änderungen der Dienststelle an der Installation, etwa eine längere
Frist, eine neue Quelle oder ein anderer Modellanbieter. Grundlage ist der Vergleich zweier Auskünfte
über die Datenerhebung.

(9) **Funktionsupdates** werden erst eingespielt, wenn das Verfahren nach den Absätzen 5 bis 7
abgeschlossen ist. Vorher dürfen sie nur eingespielt werden, wenn jede Funktion der Stufe Zustimmung
in der Installation abgeschaltet ist und bis zur Zustimmung abgeschaltet bleibt; der Update-Vermerk
hält das als Auflage fest. Lässt sich eine solche Funktion nicht abschalten, wartet das Update.

(10) **Sicherheitsupdates:** Ein Patch-Release einer unterstützten Linie bringt keine neue Funktion;
es enthält nur Sicherheitskorrekturen und Behebungen kritischer Fehler. [Stelle] sichtet vor dem
Einspielen sein Delta. Enthält es keine Änderung der Stufe Zustimmung, wird es sofort eingespielt, und
der Personalrat erhält den Update-Vermerk binnen [fünf] Arbeitstagen. Sonst gilt Absatz 9, für eine Sicherheitskorrektur Absatz 11.

(11) Steht eine Sicherheitskorrektur nur in einem Funktionsupdate oder einem Patch-Release mit einer
Änderung der Stufe Zustimmung bereit, darf die Dienststellenleitung es als vorläufige Maßnahme sofort
einspielen. Das ist etwa der Fall, wenn die eingesetzte Linie ausgelaufen ist. Dann gilt:

1. Funktionen der Stufe Zustimmung, die sich abschalten lassen, werden abgeschaltet und bleiben es
   bis zur Zustimmung.
2. Eine Änderung der Stufe Zustimmung, die sich nicht abschalten lässt, darf bis zur Zustimmung weder
   genutzt noch ausgewertet werden; Daten, die sie erzeugt, unterliegen dem Verwertungsverbot nach
   Nummer 4.
3. Die Dienststelle unterrichtet den Personalrat unverzüglich, begründet die Maßnahme und holt die
   Beteiligung nach dieser Nummer nach.

(12) **Wechsel der Linie:** Die Dienststelle betreibt [nur LTS-Linien / auch normale Linien]. Für den
Wechsel auf die nächste Linie beginnt die Beteiligung, sobald der Release-Kandidat erscheint, mit dem
Delta von der eingesetzten Version zum Kandidaten. [Früher beginnt sie mit dem Delta zum jüngsten
finalen Release dazwischen; bei reinem LTS-Betrieb ist das das Dezember-Release, das den größten Teil
der neuen Funktionen schon enthält. Dann wird beim Kandidaten nur noch der Rest gestuft.] Vor dem
Einspielen des finalen Release legt die Dienststelle das Delta zwischen Kandidat und finalem Release
vor; es wird nach dieser Nummer erneut gestuft. Alle Deltas erzeugt der Betrieb nach Absatz 2.

#### 16. Rechte des Personalrats

(1) Der Personalrat erhält:

1. zu jedem Update den Update-Vermerk mit dem Delta nach Nummer 15 Absatz 2,
2. jährlich die Auskunft über die Datenerhebung mit dem Auszug für die Personalvertretung sowie die
   Zahl der anlassbezogenen Klärungen nach Zweck, ohne Namen,
3. auf Verlangen die Dokumentation nach Nummer 7 Absatz 3 und Nummer 8.

(2) Er kann die Einhaltung prüfen, sich das System vorführen lassen und eine Testinstanz der
eingesetzten Version mit Testkonten nutzen; vor einem Wechsel der Linie läuft dort der
Release-Kandidat.

(3) Er kann sachkundige Personen hinzuziehen; die Kosten trägt die Dienststelle.

#### 17. Beschwerden, Verstöße, Streit

(1) Beschäftigte können sich wegen eines Verstoßes gegen diese Vereinbarung an den Personalrat, die
oder den Datenschutzbeauftragten oder [Stelle] wenden, ohne dass ihnen daraus ein Nachteil entsteht.

(2) Bei einem Verstoß gilt das Verwertungsverbot nach Nummer 4; die Ergebnisse werden gelöscht, die
betroffenen Personen und der Personalrat werden unterrichtet, und die Dienststelle prüft
Konsequenzen für die Verantwortlichen.

(3) Streitigkeiten klären Dienststelle und Personalrat zuerst im Gespräch, sonst [vor der
Einigungsstelle / im Verfahren nach dem Personalvertretungsrecht].

#### 18. Information, Schulung, Überprüfung

(1) Die Dienststelle informiert die Beschäftigten vor der ersten Nutzung über diese Vereinbarung, die
Datenschutzhinweise und die Nutzungsrichtlinie.

(2) Dienststelle und Personalrat überprüfen die Vereinbarung nach [zwölf] Monaten gemeinsam.

#### 19. Inkrafttreten, Kündigung, Nachwirkung

(1) Die Vereinbarung tritt am [Datum] in Kraft. Sie kann mit einer Frist von [drei Monaten] zum
[Monatsende] gekündigt werden.

(2) Nach einer Kündigung gilt sie bis zu einer neuen Vereinbarung fort. Die Nummern 4 bis 12 und die
Dokumentation nach Nummer 7 Absatz 3 gelten für bereits erhobene Daten auch nach dem Ende der
Vereinbarung.

(3) Ist eine Bestimmung unwirksam, bleiben die übrigen wirksam; die Parteien ersetzen sie durch eine
Regelung, die ihrem Zweck am nächsten kommt.

[Ort, Datum, Unterschriften]

#### Anlagen

Jede Anlage nennt die OPAA-Version und den Commit, für die sie gilt. Version und Commit stehen im
Kopf jedes Faktenblatts und der Auskunft über die Datenerhebung.

| Anlage | Inhalt | Quelle |
|---|---|---|
| 1 | Beschreibung der Installation: Version, Linie und Supportende, Betrieb, angebundene Quellen, Modellanbieter | Behörde |
| 2 | gespeicherte Daten mit Zweck und technisch möglichen Auswertungen; Protokollereignisse | {{faktenblatt:datenkatalog}}, {{faktenblatt:protokollereignisse}} der Version [X.Y.Z] |
| 3 | Fristen und Voreinstellungen; Aufbewahrung der Sicherungen | {{faktenblatt:fristen}}, {{faktenblatt:voreinstellungen}} der Version [X.Y.Z]; Behörde |
| 4 | was jede Rolle über andere sieht; Liste der benannten Personen je Rolle, für den direkten Zugriff nach Nummer 7 und für die Freigabe anlassbezogener Klärungen | {{faktenblatt:rollensicht}} der Version [X.Y.Z]; Behörde |
| 5 | Datenflüsse nach außen | {{faktenblatt:datenfluesse}} der Version [X.Y.Z] |
| 6 | Auskunft über die Datenerhebung der Installation, mit dem Auszug für die Personalvertretung | Abruf vom [Datum] |
| 7 | [Nutzungsrichtlinie, Datenschutzhinweise] | Behörde |

---

## Gut zu wissen

- **Supportlinien:** Jede unterstützte Linie bekommt Patch-Releases ohne neue Funktion, ältere
  Linien über eigene Wartungsbranches, und das Juni-Release ist eine LTS-Linie mit rund zwölf
  Monaten Support ([Sicherheitsupdates](../../handbuch/betrieb/releases-und-nachweise.md#sicherheitsupdates)).
  Nummer 15 Absatz 11 bleibt für den Fall, dass eine Korrektur nur in einem Funktionsupdate
  bereitsteht, etwa nach dem Supportende der eingesetzten Linie.
- **Zeit für den Linienwechsel:** Der Release-Kandidat eines LTS erscheint nur wenige Wochen vor dem
  Supportende der laufenden LTS-Linie. Wer mehr Zeit braucht, beginnt mit dem Delta zum
  Dezember-Release (Nummer 15 Absatz 12).
- **Delta bei übersprungenen Versionen:** Das Update-Paket eines LTS-Release und seiner
  Kandidaten enthält das Delta über den ganzen Sprung von der vorigen LTS-Linie. Wer andere
  Versionen überspringt, braucht das selbst erzeugte Delta über den ganzen Sprung
  ([Das Update-Paket eines Release](../../handbuch/betrieb/nachvollziehbarkeit.md#das-update-paket-eines-release)).
- Ohne „standardmäßig aus“ kann ein Funktionsupdate eine zustimmungspflichtige Funktion sofort aktiv
  mitbringen. Nummer 15 fängt das auf: Ein Funktionsupdate wartet, bis zugestimmt oder die Funktion
  abgeschaltet ist ([Funktionen abschalten](../../handbuch/betrieb/konfiguration.md#funktionen-abschalten)).
- Das Delta misst an den Voreinstellungen. Hat die Installation eine Einstellung überschrieben,
  trifft eine Änderung sie womöglich anders; das zeigt der Vergleich mit der Auskunft über die
  Datenerhebung.
- Den direkten Zugriff auf Datenbank, Sicherungen und Logs kann das Produkt nicht beschränken.
  Deshalb regelt ihn Nummer 7 organisatorisch.

## Zum Nachlesen

**Arbeitshilfen** für Dienststelle und Personalrat:

- [Informationsmappe für den Personalrat](informationsmappe-personalrat.md).
- [Update-Vermerk](../09-betrieb-update/update-vermerk.md): Bewertung eines Updates durch alle
  Stellen.
- [Erläuterungen zur IT-Rahmendienstvereinbarung der Universität Oldenburg](https://uol.de/fileadmin/personalrat/UEberarbeitung_2025/Dienstvereinbarungen/Erlaeuterungen_zur_IT-Rahmen-DV.pdf)
  (Stand 19.03.2026): Vorbild des Stufenmodells mit Bagatellfall, Anzeige und Zustimmung.
- [Rahmendienstvereinbarung IT des Ministeriums für Wissenschaft, Energie, Klimaschutz und Umwelt Sachsen-Anhalt](https://mwu.sachsen-anhalt.de/api/media/MWU_HPR_DV_IT_09-2024-e3b0c442.pdf?collection=document)
  (09/2024), mit Bezug zu KI; abrufbar auf der
  [Seite des Hauptpersonalrats](https://mwu.sachsen-anhalt.de/ministerium/page/hauptpersonalrat).
- [IT-Rahmendienstvereinbarung der Universität Passau](https://www.uni-passau.de/fileadmin/dokumente/beschaeftigte/Personal/Formulare_Internetseite/Dienstvereinbarungen/IT-RDV.pdf):
  weiteres Muster einer Rahmenvereinbarung.

**Rechtsgrundlagen** für Justiziariat und Personalrat:

| Worum es geht | Fundstelle |
|---|---|
| Mitbestimmung bei technischen Einrichtungen zur Überwachung von Verhalten oder Leistung | § 80 Abs. 1 Nr. 21 [BPersVG](https://www.gesetze-im-internet.de/bpersvg_2021/__80.html) |
| daneben: Hebung der Arbeitsleistung, grundlegend neue Arbeitsmethoden | § 80 Abs. 1 Nr. 19 und 20 BPersVG |
| Objektive Eignung zur Überwachung genügt | [BVerwG, Beschluss vom 4.5.2023, 5 P 16.21, Pressemitteilung 34/2023](https://www.bverwg.de/de/pm/2023/34) |
| Zulässigkeit und Form von Dienstvereinbarungen | § 63 [BPersVG](https://www.gesetze-im-internet.de/bpersvg_2021/__63.html) |
| Unterrichtung des Personalrats | § 66 Abs. 1 [BPersVG](https://www.gesetze-im-internet.de/bpersvg_2021/__66.html) |
| Zustimmungsverfahren und Fristen | § 70 [BPersVG](https://www.gesetze-im-internet.de/bpersvg_2021/__70.html) |
| Vorläufige Maßnahmen, etwa ein dringendes Sicherheitsupdate | § 76 [BPersVG](https://www.gesetze-im-internet.de/bpersvg_2021/__76.html) |
| Einigungsstelle | §§ 72 bis 75 [BPersVG](https://www.gesetze-im-internet.de/bpersvg_2021/__72.html) |
| Hinzuziehung Sachverständiger und Kostentragung | zu prüfen |
| Kündigung und Nachwirkung von Dienstvereinbarungen | zu prüfen |
| Personalvertretungsgesetz des Landes, mit eigenen Tatbeständen und Fristen | zu prüfen |
| Vereinbarungen nach Hamburger Personalvertretungsrecht | zu prüfen |
| Daten über Beschäftigte | § 26 [BDSG](https://www.gesetze-im-internet.de/bdsg_2018/__26.html) bzw. Landesrecht |
