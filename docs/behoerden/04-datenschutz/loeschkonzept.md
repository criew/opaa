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

> **Beispiel.** Ein Landratsamt übernimmt für OPAA die meisten voreingestellten Fristen, wählt für eine
> aber einen kürzeren Wert und begründet das mit dem Zweck. Die Wahl stimmt die Projektleitung mit
> dem Personalrat ab, weil sie auch in die Dienstvereinbarung gehört. Mit dem Kreisarchiv klärt sie,
> dass Chatverläufe nicht angeboten werden müssen, übernommene Ergebnisse aber in die Akte gehören.

## Was OPAA liefert

| Angabe im Löschkonzept | Quelle |
|---|---|
| Datenarten | Übersicht im {{faktenblatt:datenkatalog}} |
| Frist je Datum | {{faktenblatt:fristen#daten}} |
| Frist der Protokolleinträge | {{faktenblatt:fristen#protokollereignisse}} |
| Einstellbare Fristen mit Standardwert und Wertebereich | {{faktenblatt:fristen#konfigurationsschluessel}} |
| Löschmechanismus je Datum | Zeile „Löschmechanismus“ im {{faktenblatt:datenkatalog}} |
| Welche Löschungen protokolliert werden | {{faktenblatt:protokollereignisse}} |
| Technisches Anwendungslog | {{faktenblatt:datenkatalog#application-log}} |

Die Faktenblätter nennen die Voreinstellung der Version. Was die eigene Installation anders eingestellt
hat, zeigt die [Auskunft über die Datenerhebung](../../handbuch/nachvollziehbarkeit.md#auskunft-über-die-datenerhebung-einer-installation).

## Aufgaben der Behörde

1. **Fristen wählen:** je einstellbarer Frist entscheiden, ob die Voreinstellung bleibt, und die Wahl
   mit dem Zweck begründen (Tabelle unten). Festhalten, in welchen Spaces die Chat-Bereinigung
   eingeschaltet ist ([Spaces: Chats](../../handbuch/spaces.md#7-chats)).
2. **Anlässe regeln, die OPAA nicht kennt:**
   - Ausscheiden einer Person: wer das Konto wann sperrt; ein benutztes Konto wird meist gesperrt,
     nicht gelöscht ([Löschen oder sperren](../../handbuch/benutzerverwaltung.md#8-löschen-oder-sperren)).
   - Dokumente: Sie folgen ihrer Quelle
     ([Änderungen und Löschungen](../../handbuch/wissensbibliotheken.md#44-änderungen-und-löschungen)),
     ihre Fristen regelt die Behörde dort; für hochgeladene Dokumente festlegen, wer sie wann prüft.
   - Anwendungslog: die Frist festlegen, wenn sie laut Faktenblatt beim Betrieb liegt.
   - Sicherungen: Aufbewahrung festlegen und wie eine Rücksicherung mit bereits gelöschten Daten
     umgeht.
3. **Mit dem Archiv klären**, ob vor der Löschung Daten anzubieten sind, etwa das Protokoll.
   Chatverläufe sind meist nicht aktenrelevant, übernommene Ergebnisse schon; deshalb gehören sie nach
   der [Nutzungsrichtlinie](../08-nutzung/nutzungsrichtlinie.md#6-ergebnisse-in-die-akte) in die Akte.
4. **Nachweis regeln** für Löschungen außerhalb von OPAA: Sicherungen, Quellen, Anwendungslog.
5. **Bei jedem Update nachziehen:** Das Update-Paket weist geänderte Fristen, Daten und Löschwege aus.

## Ergebnis festhalten

| Frist (Faktenblatt) | Voreinstellung der Version | Wert der Installation | Begründung |
|---|---|---|---|
| [ ] | [ ] | [ ] | [ ] |

## Gut zu wissen

- Die Fristwahl ist meist auch Gegenstand der Dienstvereinbarung mit dem Personalrat.
- Gelöschte Daten bleiben in Sicherungen, bis diese ablaufen.

## Zum Nachlesen

**Arbeitshilfen** für die Projektleitung:

- [Kurzpapier Nr. 11 der Datenschutzkonferenz](https://www.datenschutzkonferenz-online.de/kurzpapiere.html)
  „Recht auf Löschung“: Löschpflichten und ihre Ausnahmen.

**Rechtsgrundlagen** für die oder den DSB:

| Worum es geht | Fundstelle |
|---|---|
| Löschfristen im Verzeichnis von Verarbeitungstätigkeiten | Art. 30 Abs. 1 lit. f [DSGVO](https://eur-lex.europa.eu/eli/reg/2016/679/oj/deu) |
| Voreinstellungen begrenzen die Speicherdauer auf das Nötige | Art. 25 Abs. 2 DSGVO |
| Unterlagen vor der Löschung dem Archiv anbieten | § 5 [BArchG](https://www.gesetze-im-internet.de/barchg_2017/__5.html) bzw. Landesarchivgesetz |
