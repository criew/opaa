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
Release neu verhandelt werden, und keine Anlage erweitert den Überwachungsumfang unbemerkt.

## Was OPAA liefert

- **Fakten für die Anlagen:** die Faktenblätter der eingesetzten Version, etwa
  {{faktenblatt:datenkatalog}}, {{faktenblatt:rollensicht}} und {{faktenblatt:datenfluesse}}, im
  Update-Paket am GitHub-Release der Version.
- **Stand der eigenen Installation:** die
  [Auskunft über die Datenerhebung](../../handbuch/betrieb/nachvollziehbarkeit.md#auskunft-über-die-datenerhebung-einer-installation)
  mit allen abweichenden Einstellungen und der Beschreibung des Protokolls (dem Auszug für die
  Personalvertretung).
- **Zu jedem Update** ein Delta mit Stufenvorschlag nach denselben Klassen wie in Nummer 11 des
  Mustertexts, dazu die Vorlage [Update-Vermerk](../09-betrieb-update/update-vermerk.md). Die Klassen
  stammen aus einer gemeinsamen Quelle, den
  [Stufenregeln](../../features/behoerdenunterlagen.md#die-stufenregeln).
- **Supportlinien:** Patch-Releases bringen nur Sicherheitskorrekturen und Behebungen kritischer
  Fehler, keine neue Funktion ([Linien und Supportzeiträume](../../handbuch/betrieb/releases-und-nachweise.md#linien-und-supportzeiträume)).
- Die [Informationsmappe für den Personalrat](informationsmappe-personalrat.md) erklärt all das in
  Alltagssprache.

## Aufgaben der Behörde

1. **Version festlegen,** die eingeführt wird, und deren Update-Paket herunterladen.
2. **Personalrat früh einbinden:** Informationsmappe übergeben, Testinstanz anbieten, schon vor einer
   Teststellung mit echten Beschäftigten.
3. **Mustertext anpassen:** Angaben in eckigen Klammern ersetzen, Nummern streichen, die die
   eingesetzte Version nicht betreffen, Landesrecht prüfen lassen.
4. **Anlagen zusammenstellen** und jede mit Version und Commit aus dem Kopf des Faktenblatts
   beschriften.
5. **Rollen benennen:** wer Systemverwaltung, Revision und Datenbankbetrieb wahrnimmt und wer bei
   einer anlassbezogenen Klärung freigibt.
6. **Abschließen** und bekannt machen; danach jedes Update nach Nummer 11 behandeln.

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
[Name der Installation] nicht nutzt oder nur in privaten Chats arbeitet, hat dadurch keinen Nachteil.

#### 4. Keine Leistungs- und Verhaltenskontrolle

(1) [Name der Installation] wird nicht zur Kontrolle von Leistung oder Verhalten der Beschäftigten
eingesetzt.

(2) Erkenntnisse, die entgegen dieser Vereinbarung gewonnen wurden, dürfen für keine personelle
Maßnahme verwendet werden, insbesondere nicht für Beurteilungen, Abmahnungen, disziplinarische oder
arbeitsrechtliche Schritte (Verwertungsverbot).

#### 5. Auswertungsverbot und Zugriff auf Datenbank und Protokoll

(1) Zulässig sind nur die personenbezogenen Auswertungen, die Anlage 2 als mögliche Auswertung
nennt, nur zu dem dort genannten Zweck und nur im Rahmen der Nummern 4 und 6. Jede weitere ist
unzulässig.

(2) Das gilt für jeden Zugriffsweg: Oberfläche, Schnittstellen, Protokoll, direkter Zugriff auf
Datenbank, Sicherungen, Originalablage und Log. Direkten Zugriff haben nur die in Anlage 4 benannten
Personen, und nur für Betrieb, Sicherung und Fehlerbehebung.

(3) Ein Zugriff, bei dem Inhalte oder Daten bestimmter Beschäftigter sichtbar werden können, geschieht
im Vier-Augen-Prinzip mit einer zweiten benannten Person, wird mit Anlass, Umfang und Beteiligten
dokumentiert und dem Personalrat [binnen … Arbeitstagen] mitgeteilt.

(4) Das Protokoll wird nur nach Objekt, Zeitraum, Ereignisart oder Vorgang abgefragt. Eine Abfrage
nach einer Person ist nur als anlassbezogene Klärung zulässig: zur Aufklärung eines
Sicherheitsvorfalls, mit vorab festgelegter Person, Zeitraum und Zweck, freigegeben von einer zweiten
Person der Revision. Ausgeschlossen sind arbeitsrechtliche, disziplinarische und leistungsbezogene
Zwecke, auch bei gemischten Sachverhalten. Der Personalrat [wird vor der Freigabe unterrichtet /
benennt ein Mitglied, das vor der Freigabe gehört wird]; die betroffene Person wird nach Abschluss
unterrichtet, außer die Klärung richtet sich gegen Dritte.

(5) Die Diagnose im Rechtekontext einer Person („Sicht als“) dient nur der Behebung von Störungen
und wird nicht zur Kontrolle der Person genutzt.

#### 6. Negativliste

Ausgeschlossen sind insbesondere:

1. Ranglisten, Vergleiche oder Bewertungen einzelner Beschäftigter, auch als spielerisches Element,
2. Auswertungen je Person über Nutzungshäufigkeit, Zeiten, Dauer oder Inhalte von Fragen und Chats,
3. jede Einsicht in private Chats Dritter,
4. Rückschlüsse auf Anwesenheit oder Arbeitsort, etwa über Netzadressen,
5. Statistiken unterhalb der Mindestgruppengröße; sie beträgt [Wert, nicht kleiner als die
   Voreinstellung der Version, {{faktenblatt:voreinstellungen#opaa-permission-minimum-group-size}}],
6. die Verknüpfung mit anderen Systemen über Beschäftigte, etwa Zeiterfassung oder
   Personalverwaltung,
7. Entscheidungen über Beschäftigte allein auf Grundlage einer Antwort des Assistenten,
8. die Weitergabe von Protokoll- oder Nutzungsdaten an Vorgesetzte außerhalb einer anlassbezogenen
   Klärung.

#### 7. Selbstauskunft

Diese Nummer gilt, sobald die eingesetzte Version die Selbstauskunft enthält, mit der jede Person
die zu ihr gespeicherten Daten selbst herunterlädt.

(1) Niemand darf verlangen, dass Beschäftigte ihre Selbstauskunft vorlegen oder weitergeben. Eine
dennoch vorgelegte Auskunft wird nicht verwertet.

(2) Wer wann eine Selbstauskunft abruft, wird nicht ausgewertet. Die Protokolleinträge dazu dienen
nur dem Nachweis, dass das Auskunftsrecht erfüllt wurde, und der Erkennung missbräuchlicher Abrufe.
Sie stehen mit Zweck, Zugriff und Frist im Auszug für die Personalvertretung.

(3) Niemand exportiert die Selbstauskunft für eine andere Person, weder die Verwaltung noch die
Datenschutzstelle noch der Personalrat, auch nicht über „Sicht als“.

(4) Die Notabschaltung wird nur bei einer Störung oder einem Sicherheitsvorfall genutzt; der
Personalrat wird [unverzüglich] unterrichtet. Ein Auskunftsantrag bei der Behörde bleibt immer
möglich.

(5) Ein Export durch oder über die Datenschutzstelle wäre eine neue Änderung der Stufe Zustimmung.

#### 8. Fristen und Löschung

(1) Es gelten die Fristen der Anlage 3. Eine längere Frist bedarf der Zustimmung des Personalrats.

(2) Für Chats ausgeschiedener Beschäftigter gilt, sobald die eingesetzte Version ihre Löschung nach
der Sperre kennt:

1. Die Dienststelle sperrt das Konto einer ausgeschiedenen Person binnen [Frist] nach dem
   Ausscheiden. Kann ein Konto in der eingesetzten Version nicht gesperrt werden, etwa ein Konto
   eines Identitätsanbieters, gilt die Löschfrist dafür nicht; die Dienststelle regelt den Ersatz
   [Regelung].
2. Bloße Abwesenheit, etwa Elternzeit, Krankheit, Abordnung oder Freistellung, ist kein Grund für eine
   Sperre.
3. Bei planbarem Ausscheiden erhält die Person vor der Sperre Gelegenheit zur Selbstauskunft; danach
   erhält sie Auskunft über die oder den Datenschutzbeauftragten bis zum Löschdatum.
4. Die Chats werden nach [Frist, nicht kürzer als die Untergrenze der Version] gelöscht. Eine
   Verlängerung bedarf der Zustimmung des Personalrats.
5. Die Löschung eines Kontos anhalten darf nur [Stelle] bei [Anlass, etwa einem
   Disziplinarverfahren]; der Personalrat wird unterrichtet.
6. Der Protokolleintrag über die Löschung dient nur deren Nachweis.

#### 9. Datenflüsse nach außen

Daten verlassen die Installation nur auf den Wegen der Anlage 5. Für das Sprachmodell gilt:
[Betrieb im Haus / Anbieter …, Vertrag vom …]. Ein neuer Datenfluss oder ein neues Ziel bedarf der
Zustimmung.

#### 10. Rechte und Rollen

(1) Wer welche Rolle hat, steht in Anlage 4. Änderungen teilt die Dienststelle dem Personalrat
[vierteljährlich] mit.

(2) Wer Bibliotheken verwaltet, gibt Dokumente mit Daten über Beschäftigte nur frei, soweit die
Aufgabe es erfordert. [Ausgeschlossene Quellen, etwa Personalakten.]

#### 11. Änderungen und Updates

(1) Die Anlagen gelten in der vereinbarten Fassung. Eine neue Fassung tritt an ihre Stelle, sobald
das Verfahren nach dieser Nummer abgeschlossen ist. Eine Formulierung „in der jeweils gültigen
Fassung“ gilt für keine Anlage.

(2) Vor jedem Update legt die Dienststelle dem Personalrat den Update-Vermerk mit dem Delta aus dem
Update-Paket vor. Die Stufen im Delta sind Vorschläge; maßgeblich ist die Zuordnung nach den Absätzen
3 bis 5. **Im Zweifel gilt die Stufe Zustimmung.** Eine Änderung, die keiner Liste eindeutig
zugeordnet werden kann, wird mit dem Vermerk vorgelegt, warum die Zuordnung offen ist.

(3) **Bagatelle**, abschließend:

1. redaktionelle Präzisierung eines Manifest-Eintrags ohne inhaltliche Änderung
2. neuer Konfigurationsschlüssel mit der Markierung „ohne Datenwirkung“
3. neue Spalte oder Tabelle ohne Personenbezug

Bagatellen werden mit dem Update-Vermerk mitgeteilt; eine Beteiligung entfällt.

(4) **Anzeige:** Änderungen ohne neue Datenwirkung, die Beschäftigte dennoch wahrnehmen: kürzere
Frist, entfallene Daten, Felder, Ereignisse oder Datenflüsse, eingeschränkte Sichtbarkeit,
geänderter System-Prompt oder geändertes Standardmodell ohne Klasse Z1–Z7. Der Personalrat kann
binnen [zehn] Arbeitstagen widersprechen; ohne Widerspruch gilt die Zustimmung als erteilt. Nach
einem Widerspruch gilt Absatz 5.

(5) **Zustimmung**, wenn die Änderung mindestens eine dieser Klassen erfüllt:

| Klasse | Änderung |
|---|---|
| Z1 | neue personenbezogene Daten oder neue Protokollereignisse |
| Z2 | neue Sichtbarkeit: eine Rolle sieht mehr über andere Personen als vorher |
| Z3 | neue Auswertung oder neuer Export |
| Z4 | längere Frist |
| Z5 | neuer externer Datenfluss oder neues Ziel eines bestehenden |
| Z6 | geänderte KI-Einstufung |
| Z7 | geänderte Voreinstellung mit Datenwirkung, einschließlich eines Standardmodells, das einen Datenfluss ändert |

(6) Dieselben Stufen gelten für Änderungen der Dienststelle an der Installation, etwa eine längere
Frist, eine neue Quelle oder ein anderer Modellanbieter. Grundlage ist der Vergleich zweier Auskünfte
über die Datenerhebung.

(7) **Funktionsupdates** werden erst eingespielt, wenn das Verfahren nach den Absätzen 3 bis 5
abgeschlossen ist. Vorher dürfen sie nur eingespielt werden, wenn jede Funktion der Stufe Zustimmung
in der Installation abgeschaltet ist und bis zur Zustimmung abgeschaltet bleibt; der Update-Vermerk
hält das als Auflage fest. Lässt sich eine solche Funktion nicht abschalten, wartet das Update.

(8) **Sicherheitsupdates:** Ein Patch-Release einer unterstützten Linie bringt nur
Sicherheitskorrekturen und Behebungen kritischer Fehler, keine neue Funktion. Es wird sofort
eingespielt; der Personalrat erhält den Update-Vermerk binnen [fünf] Arbeitstagen. Zeigt das Delta
dennoch eine Änderung der Stufe Zustimmung, gilt Absatz 7.

(9) **Wechsel der Linie:** Die Dienststelle betreibt [nur LTS-Linien / auch normale Linien]. Für den
Wechsel auf die nächste Linie beginnt die Beteiligung mit dem Release-Kandidaten und seinem
Update-Paket, spätestens [sechs] Wochen vor dem Supportende der eingesetzten Linie. Das Delta
zwischen Kandidat und finalem Release legt die Dienststelle nach.

(10) Steht eine Sicherheitskorrektur nur in einem Funktionsupdate bereit, etwa weil die eingesetzte
Linie ausgelaufen ist, kann die Dienststellenleitung es als vorläufige Maßnahme einspielen. Absatz 7
Satz 2 gilt; die Dienststelle begründet die Maßnahme und leitet das Verfahren unverzüglich ein.

#### 12. Rechte des Personalrats

(1) Der Personalrat erhält:

1. zu jedem Update den Update-Vermerk mit dem Update-Paket,
2. jährlich die Auskunft über die Datenerhebung mit dem Auszug für die Personalvertretung sowie die
   Zahl der anlassbezogenen Klärungen nach Anlass, ohne Namen,
3. auf Verlangen die Dokumentation nach Nummer 5 Absatz 3.

(2) Er kann die Einhaltung prüfen, sich das System vorführen lassen und eine Testinstanz der
eingesetzten Version mit Testkonten nutzen. [Er kann nach Maßgabe des Personalvertretungsrechts
sachkundige Personen hinzuziehen.]

#### 13. Information der Beschäftigten

Die Dienststelle informiert die Beschäftigten vor der ersten Nutzung über diese Vereinbarung, die
Datenschutzhinweise und die Nutzungsrichtlinie.

#### 14. Inkrafttreten, Kündigung, Nachwirkung

(1) Die Vereinbarung tritt am [Datum] in Kraft. Sie kann mit einer Frist von [drei Monaten] zum
[Monatsende] gekündigt werden.

(2) Nach einer Kündigung gilt sie bis zu einer neuen Vereinbarung fort. Die Nummern 4 bis 7 gelten
für bereits erhobene Daten auch nach dem Ende der Vereinbarung.

[Ort, Datum, Unterschriften]

#### Anlagen

Jede Anlage nennt die OPAA-Version und den Commit, für die sie gilt. Version und Commit stehen im
Kopf jedes Faktenblatts und der Auskunft über die Datenerhebung.

| Anlage | Inhalt | Quelle |
|---|---|---|
| 1 | Beschreibung der Installation: Version, Linie und Supportende, Betrieb, angebundene Quellen, Modellanbieter | Behörde |
| 2 | gespeicherte Daten mit Zweck und möglichen Auswertungen; Protokollereignisse | {{faktenblatt:datenkatalog}}, {{faktenblatt:protokollereignisse}} der Version [X.Y.Z] |
| 3 | Fristen und Voreinstellungen | {{faktenblatt:fristen}}, {{faktenblatt:voreinstellungen}} der Version [X.Y.Z] |
| 4 | was jede Rolle über andere sieht; Liste der benannten Personen je Rolle, für den direkten Zugriff nach Nummer 5 und für die Freigabe anlassbezogener Klärungen | {{faktenblatt:rollensicht}} der Version [X.Y.Z]; Behörde |
| 5 | Datenflüsse nach außen | {{faktenblatt:datenfluesse}} der Version [X.Y.Z] |
| 6 | Auskunft über die Datenerhebung der Installation, mit dem Auszug für die Personalvertretung | Abruf vom [Datum] |
| 7 | [Nutzungsrichtlinie, Datenschutzhinweise] | Behörde |

---

## Gut zu wissen

- Ohne „standardmäßig aus“ kann ein Funktionsupdate eine zustimmungspflichtige Funktion sofort aktiv
  mitbringen. Nummer 11 fängt das auf zwei Wegen auf: Sicherheitsupdates kommen über die
  Supportlinien ohne neue Funktion, und ein Funktionsupdate wartet, bis zugestimmt oder die Funktion
  abgeschaltet ist ([Funktionen abschalten](../../handbuch/betrieb/konfiguration.md#funktionen-abschalten)).
- Das Delta misst an den Voreinstellungen. Hat die Installation eine Einstellung überschrieben,
  trifft eine Änderung sie womöglich anders; das zeigt der Vergleich mit der Auskunft über die
  Datenerhebung.
- Den direkten Zugriff auf die Datenbank kann das Produkt nicht beschränken. Deshalb regelt ihn
  Nummer 5 organisatorisch.

## Zum Nachlesen

**Arbeitshilfen** für Dienststelle und Personalrat:

- [Informationsmappe für den Personalrat](informationsmappe-personalrat.md).
- [Update-Vermerk](../09-betrieb-update/update-vermerk.md): Bewertung eines Updates durch alle
  Stellen.
- [Erläuterungen zur IT-Rahmendienstvereinbarung der Universität Oldenburg](https://uol.de/fileadmin/personalrat/UEberarbeitung_2025/Dienstvereinbarungen/Erlaeuterungen_zur_IT-Rahmen-DV.pdf)
  (Stand 19.03.2026): Vorbild des Stufenmodells mit Bagatellfall, Anzeige und Zustimmung.
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
| Kündigung und Nachwirkung von Dienstvereinbarungen | zu prüfen |
| Personalvertretungsgesetz des Landes, mit eigenen Tatbeständen und Fristen | zu prüfen |
| Daten über Beschäftigte | § 26 [BDSG](https://www.gesetze-im-internet.de/bdsg_2018/__26.html) bzw. Landesrecht |
