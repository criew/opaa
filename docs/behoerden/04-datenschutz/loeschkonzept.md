---
stand: 2026-10-15
gilt-fuer: main
erzeugung: redaktionell
rechtsstand: 2026-10-13
pruefvermerk: nicht rechtlich geprüft
naechste-durchsicht: 2027-04-15
---

# Baustein für das Löschkonzept

> Nicht rechtlich geprüft. Kann als Vorlage dienen; die Verantwortung bleibt bei der Behörde.

Ein Löschkonzept legt fest, wann welche Daten gelöscht werden, wer das auslöst und wie es
nachgewiesen wird. Dieser Baustein sagt, woher die Angaben für OPAA kommen und was die Behörde selbst
regeln muss. Fristen und Löschmechanismen stehen nicht hier, sondern in den Faktenblättern der
eingesetzten Version. Angaben in eckigen Klammern **[…]** ergänzt die Behörde.

## Quellen und Prüfstand

| Fundstelle | Inhalt | Herkunft |
|---|---|---|
| Art. 30 Abs. 1 lit. f [DSGVO](https://eur-lex.europa.eu/eli/reg/2016/679/oj/deu) | vorgesehene Fristen für die Löschung im Verzeichnis | Normtext |
| Art. 25 Abs. 2 DSGVO | Voreinstellungen begrenzen die Speicherfrist auf das Erforderliche | Recherche vom 13.10.2026 |
| § 5 [BArchG](https://www.gesetze-im-internet.de/barchg_2017/__5.html) bzw. Landesarchivgesetz | Anbietungspflicht an das zuständige Archiv vor der Löschung | Recherche vom 13.10.2026 |
| DSK, [Kurzpapiere](https://www.datenschutzkonferenz-online.de/kurzpapiere.html), Nr. 11 „Recht auf Löschung / ‚Recht auf Vergessenwerden‘“ | Löschpflichten und Ausnahmen | Muster |

Die Recherche vom 13.10.2026 steht im ersten Kommentar von Epic
[#2542](https://github.com/criew/opaa/issues/2542).

## Was OPAA liefert

| Angabe im Löschkonzept | Quelle |
|---|---|
| Datenarten | Übersicht im {{faktenblatt:datenkatalog}} |
| Frist je Datum, mit Konfiguration | {{faktenblatt:fristen#daten}} |
| Frist der Protokollereignisse | {{faktenblatt:fristen#protokollereignisse}} |
| Einstellbare Fristen mit Standardwert und Wertebereich | {{faktenblatt:fristen#konfigurationsschluessel}} |
| Löschmechanismus je Datum | Zeile „Löschmechanismus“ im {{faktenblatt:datenkatalog}} |
| Technisches Anwendungslog | {{faktenblatt:datenkatalog#application-log}} |

Die Faktenblätter nennen die Voreinstellung der Version. Die Fristen der Instanz trägt die Behörde
ein, wo sie abweichen [aus dem Konfigurationsexport, [#2558](https://github.com/criew/opaa/issues/2558)].

## Was die Behörde regelt

### Fristen wählen

Für jede einstellbare Frist entscheidet die Behörde, ob sie die Voreinstellung übernimmt. Die Wahl
begründet sie mit dem Zweck; sie ist in der Regel auch Gegenstand der Dienstvereinbarung
([#2566](https://github.com/criew/opaa/issues/2566)).

Die Liste der einstellbaren Fristen steht im {{faktenblatt:fristen#konfigurationsschluessel}}. Die
Behörde übernimmt jede Zeile in diese Tabelle:

| Frist (Faktenblatt) | Voreinstellung der Version | Wert der Instanz | Begründung |
|---|---|---|---|
| [ ] | [ ] | [ ] | [ ] |

Die Chat-Bereinigung wirkt nur in Spaces, in denen sie eingeschaltet ist
([Handbuch, Spaces: Chats](../../handbuch/spaces.md#7-chats)); die Behörde hält fest, in welchen.

### Anlässe, die OPAA nicht kennt

- **Ausscheiden einer Person.** Ein benutztes Konto wird in der Regel gesperrt, nicht gelöscht; was
  dabei bleibt und was endet, beschreibt das Handbuch
  ([Benutzerverwaltung, Löschen oder sperren](../../handbuch/benutzerverwaltung.md#8-löschen-oder-sperren)).
  Die Behörde legt fest, wer die Sperre auslöst und wann.
- **Dokumente in Wissensbibliotheken.** Dokumente einer angebundenen Quelle folgen ihrer Quelle
  ([Wissensbibliotheken, Änderungen und Löschungen](../../handbuch/wissensbibliotheken.md#44-änderungen-und-löschungen));
  Löschfristen für sie regelt die Behörde in der Quelle. Hochgeladene Dokumente löscht, wer die
  Bibliothek verwaltet; die Behörde legt fest, wer das wann prüft.
- **Technisches Anwendungslog.** Was dafür gilt, steht im
  {{faktenblatt:datenkatalog#application-log}}; liegt die Frist danach beim Betrieb, legt die Behörde
  sie hier fest.
- **Sicherungen.** Gelöschte Daten bleiben in Sicherungen, bis diese ablaufen. Die Behörde legt die
  Aufbewahrung der Sicherungen fest und hält im Löschkonzept fest, wie eine Rücksicherung mit bereits
  gelöschten Daten umgeht.

### Anbietungspflicht vor der Löschung

Vor der Löschung von Unterlagen gilt die Anbietungspflicht an das zuständige Archiv (§ 5 BArchG bzw.
Landesarchivgesetz). Nach der Recherche sind Chatverläufe meist nicht aktenrelevant, übernommene
Ergebnisse dagegen schon. Die Muster-Nutzungsrichtlinie regelt deshalb, dass übernommene Ergebnisse in
die Akte des Vorgangs gehören
([Nutzungsrichtlinie, Ergebnisse in die Akte](../08-nutzung/nutzungsrichtlinie.md#6-ergebnisse-in-die-akte)).
Ob darüber hinaus Daten aus OPAA anzubieten sind, etwa das Protokoll, klärt die Behörde mit ihrem
Archiv, bevor die automatische Löschung greift.

## Nachweis

Automatische Löschläufe und Löschungen durch Personen sind je Datum im Datenkatalog beschrieben;
welche davon protokolliert werden, steht im {{faktenblatt:protokollereignisse}}. Die Behörde legt fest,
wie sie Löschungen außerhalb von OPAA nachweist (Sicherungen, Quellen, Anwendungslog).

## Bei jedem Update

Ein Update kann Fristen, Daten und Löschmechanismen ändern. Das Update-Paket weist das aus
([Spezifikation, Update-Paket](../../features/behoerdenunterlagen.md#das-update-paket)). Die Behörde
zieht das Löschkonzept mit der neuen Version nach.
