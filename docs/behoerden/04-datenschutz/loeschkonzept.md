---
stand: 2026-10-23
gilt-fuer: main
erzeugung: redaktionell
rechtsstand: 2026-10-13
pruefvermerk: nicht rechtlich geprüft
naechste-durchsicht: 2027-04-23
---

# Löschkonzept

> Nicht rechtlich geprüft. Kann als Vorlage dienen; die Verantwortung bleibt bei der Behörde.

**Gilt für:** Behörden, deren Datenschutzaufsicht die oder der BfDI oder die
Landesdatenschutzbeauftragten sind.

Ein Löschkonzept legt fest, wann welche Daten gelöscht werden, wer das auslöst und wie es
nachgewiesen wird. Die Fristen gehören auch ins Verzeichnis von Verarbeitungstätigkeiten. Verantwortlich
ist die Behörde; OPAA liefert die Fristen und Löschwege des Produkts.

## Was OPAA liefert

| Angabe im Löschkonzept | Quelle |
|---|---|
| Datenarten | Übersicht im {{faktenblatt:datenkatalog}} |
| Frist je Datum | {{faktenblatt:fristen#daten}} |
| Frist der Protokolleinträge | {{faktenblatt:fristen#protokollereignisse}} |
| Einstellbare Fristen mit Standardwert und Wertebereich | {{faktenblatt:fristen#konfigurationsschluessel}} |
| Löschmechanismus je Datum | Zeile „Löschmechanismus“ im {{faktenblatt:datenkatalog}} |
| Welche Löschungen protokolliert werden | {{faktenblatt:protokollereignisse}} |
| Frist für Chats gesperrter Konten, mit Schalter | {{faktenblatt:fristen#opaa-chat-locked-account-cleanup-delete-after-days}}, {{faktenblatt:voreinstellungen#opaa-chat-locked-account-cleanup-enabled}} |
| Technisches Anwendungslog | {{faktenblatt:datenkatalog#application-log}} |

Die Faktenblätter nennen die Voreinstellung der Version. Was die eigene Installation anders eingestellt
hat, zeigt die [Auskunft über die Datenerhebung](../../handbuch/betrieb/nachvollziehbarkeit.md#auskunft-über-die-datenerhebung-einer-installation).

## Aufgaben der Behörde

1. **Fristen wählen:** je einstellbarer Frist entscheiden, ob die Voreinstellung bleibt, und die Wahl
   mit dem Zweck begründen (Tabelle unten).
2. **Regel für Chats festlegen:** Chats werden nur dort automatisch gelöscht, wo ein Space das
   einschaltet; ab Werk ist es aus, im persönlichen Space entscheidet nur die Person selbst
   ([Spaces: Chats](../../handbuch/spaces/README.md#7-chats)). Die Behörde legt fest, in welchen Spaces es
   eingeschaltet wird, etwa in allen gemeinsamen Spaces.
3. **Chats gesperrter Konten regeln:** Nach einer Sperre durch die Verwaltung löscht OPAA die Chats
   des Kontos nach einer Frist, ab Werk eingeschaltet
   ([Was mit den Chats eines gesperrten Kontos geschieht](../../handbuch/konten-und-rechte/benutzerverwaltung.md#was-mit-den-chats-eines-gesperrten-kontos-geschieht)).
   Die Behörde legt fest:
   - binnen welcher Zeit nach dem Ausscheiden das Konto in OPAA gesperrt wird; die Frist beginnt nur
     mit der Sperre durch die Verwaltung, ein schon wegen Inaktivität gesperrtes Konto sperrt die
     Verwaltung zusätzlich;
   - dass bloße Abwesenheit (Elternzeit, Krankheit, Abordnung) kein Anlass für diese Sperre ist;
   - wie sie mit Konten eines Identitätsanbieters umgeht: Sie lassen sich in OPAA nicht sperren, für
     sie greift die Frist nicht;
   - wer das Anhalten der Löschung je Konto veranlassen darf, etwa für ein Disziplinarverfahren;
   - dass die Person bei planbarem Ausscheiden vor der Sperre eine Selbstauskunft ziehen kann; danach
     erreicht sie sie nicht mehr.
4. **Anlässe regeln, die OPAA nicht kennt:**
   - Ausscheiden einer Person: wer das Konto wann sperrt; ein benutztes Konto wird meist gesperrt,
     nicht gelöscht ([Löschen oder sperren](../../handbuch/konten-und-rechte/benutzerverwaltung.md#8-löschen-oder-sperren)).
   - Dokumente: Sie folgen ihrer Quelle
     ([Änderungen und Löschungen](../../handbuch/arbeitsmittel/wissensbibliotheken/README.md#44-änderungen-und-löschungen)),
     ihre Fristen regelt die Behörde dort; für hochgeladene Dokumente festlegen, wer sie wann prüft.
   - Anwendungslog: die Frist festlegen, wenn sie laut Faktenblatt beim Betrieb liegt.
   - Sicherungen: Aufbewahrung festlegen und wie eine Rücksicherung mit bereits gelöschten Daten
     umgeht.
5. **Mit dem Archiv klären**, ob vor der Löschung Daten anzubieten sind, etwa das Protokoll.
   Chatverläufe sind meist nicht aktenrelevant, übernommene Ergebnisse schon; deshalb gehören sie nach
   der [Nutzungsrichtlinie](../08-nutzung/nutzungsrichtlinie.md#6-ergebnisse-in-die-akte) in die Akte.
6. **Nachweis regeln** für Löschungen außerhalb von OPAA: Sicherungen, Quellen, Anwendungslog.
7. **Bei jedem Update nachziehen:** Das Update-Paket weist geänderte Fristen, Daten und Löschwege aus.

## Ergebnis festhalten

| Frist (Faktenblatt) | Voreinstellung der Version | Wert der Installation | Begründung |
|---|---|---|---|
| [ ] | [ ] | [ ] | [ ] |

## Gut zu wissen

- Die Fristwahl ist meist auch Gegenstand der [Dienstvereinbarung](../05-mitbestimmung/dienstvereinbarung.md) mit
  dem Personalrat.
- Gelöschte Daten bleiben in Sicherungen, bis diese ablaufen. Wird eine Sicherung eingespielt, löscht
  die nächste Nacht die Chats gesperrter Konten erneut; sollen sie zurück, wird das Konto nach dem
  Einspielen und vor dem nächsten nächtlichen Lauf entsperrt oder die Löschung angehalten.
- Eine längere Frist für Chats gesperrter Konten ist eine längere Speicherdauer und damit in der Regel
  Gegenstand der Dienstvereinbarung.

## Zum Nachlesen

**Arbeitshilfen** für die Projektleitung:

- [Kurzpapier Nr. 11 der Datenschutzkonferenz](https://www.datenschutzkonferenz-online.de/kurzpapiere.html)
  „Recht auf Löschung“: Löschpflichten und ihre Ausnahmen.

**Rechtsgrundlagen** für die oder den DSB:

| Worum es geht | Fundstelle |
|---|---|
| Löschfristen im Verzeichnis von Verarbeitungstätigkeiten | Art. 30 Abs. 1 lit. f [DSGVO](https://eur-lex.europa.eu/eli/reg/2016/679/oj/deu) |
| Voreinstellungen begrenzen die Speicherdauer auf das Nötige | Art. 25 Abs. 2 DSGVO |
| Löschung, auch in Sicherungen | Art. 17 DSGVO |
| Unterlagen vor der Löschung dem Archiv anbieten | § 5 [BArchG](https://www.gesetze-im-internet.de/barchg_2017/__5.html) bzw. Landesarchivgesetz |
