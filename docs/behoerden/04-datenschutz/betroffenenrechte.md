---
stand: 2026-10-23
gilt-fuer: main
erzeugung: redaktionell
rechtsstand: 2026-10-13
pruefvermerk: nicht rechtlich geprüft
naechste-durchsicht: 2027-04-23
---

# Betroffenenrechte

> Nicht rechtlich geprüft. Kann als Vorlage dienen; die Verantwortung bleibt bei der Behörde.

**Gilt für:** Behörden, deren Datenschutzaufsicht die oder der BfDI oder die
Landesdatenschutzbeauftragten sind.

Betroffenenrechte sind die Rechte jeder Person gegenüber einer Stelle, die Daten über sie verarbeitet:
Auskunft, Berichtigung, Löschung, Einschränkung, Datenübertragbarkeit und Widerspruch. Diese Vorlage
ergänzt den Ablauf, den die Behörde für alle Verfahren hat, um das, was für OPAA gilt. Verantwortlich
ist die Behörde; die oder der Datenschutzbeauftragte (DSB) berät.

## Was OPAA liefert

- Welche Daten zu einer Person entstehen und wie man an sie kommt: {{faktenblatt:datenkatalog}},
  Zeilen „Spalten mit Personenbezug“ und „Zugriffsweg“.
- Wer sie sieht: {{faktenblatt:rollensicht}}.
- Die **Selbstauskunft**: Jede angemeldete Person lädt ihre Daten nach erneuter Anmeldung selbst als
  ZIP herunter ([Benutzerverwaltung](../../handbuch/konten-und-rechte/benutzerverwaltung.md#meine-daten-die-selbstauskunft)).
  Was enthalten ist und was nicht, steht je Datum im {{faktenblatt:datenkatalog}}, Zeile „Zuordnung
  zur Person“.
- Was jede Person außerdem einsehen kann: eigene Angaben
  ([Benutzerverwaltung](../../handbuch/konten-und-rechte/benutzerverwaltung.md#11-was-nutzende-selbst-können)), eigene
  Chats ([Suche in eigenen Chats](../../handbuch/spaces/suche.md#72-suche-in-eigenen-chats)), eigene
  Zugangstokens ([Externe KI-Werkzeuge](../../handbuch/spaces/fremdzugaenge.md#5-zugangstokens-aus-sicht-der-person)).

Nicht in der Selbstauskunft sind Daten über die Person in Dokumenten, Inhalte anlassbezogener
Klärungen, das technische Log und Sicherungen; das stellt bei einem Antrag weiterhin die Behörde zusammen.

## Aufgaben der Behörde

1. **Eingang festhalten:** Die Antwortfrist läuft ab Eingang.
2. **Identität prüfen**, bei Beschäftigten etwa über ihr Anmeldekonto.
3. **Betroffene Daten bestimmen:** Daten über die Person als Nutzende (Konto, Chats, Rechte,
   Protokoll) und Daten über sie in Dokumenten der Wissensbibliotheken.
4. **Handeln** nach der Tabelle unten.
5. **Antworten und dokumentieren**, mit der eingesetzten OPAA-Version.

| Recht | Was die Behörde in OPAA tut |
|---|---|
| **Auskunft** | auf die Selbstauskunft hinweisen; ergänzen, was sie nicht enthält; bei Bedarf alles nach dem Datenkatalog zusammenstellen |
| **Berichtigung** | lokales Konto ändert die Systemverwaltung ([Benutzerverwaltung](../../handbuch/konten-und-rechte/benutzerverwaltung.md)), sonst im Identitätsanbieter |
| **Löschung** | Person löscht eigene Chats; Konto löschen oder sperren ([Pseudonymisierung im Protokoll](../../handbuch/betrieb/nachvollziehbarkeit.md#pseudonymisierung-im-protokoll)); Dokumente in Bibliothek oder Quelle löschen; Fristen nach dem [Löschkonzept](./loeschkonzept.md) |
| **Einschränkung** | Konto sperren; [Regel der Behörde für Dokumente] |
| **Datenübertragbarkeit** | [prüfen, ob das Recht greift; dann aus den Wegen der Auskunft] |
| **Widerspruch** | [Prüfung durch die Behörde] |

## Ergebnis festhalten

```text
Anfrage nach Art. [15–21] DSGVO
Eingang:              [Datum]          Frist bis: [Datum]
Person:               [Name, Konto]    Identität geprüft durch: [ ]
OPAA-Version:         [Version]
Betroffene Daten:     [Datenkatalog-Einträge]
Maßnahmen:            [ ]
Antwort am:           [Datum]          durch: [ ]
```

## Gut zu wissen

- **Die Selbstauskunft ersetzt keinen Antrag.** Ein Antrag auf anderem Weg wird bearbeitet wie
  bisher; niemand darf verlangen, dass die Person ihre Auskunft vorlegt.
- **Notabschaltung** ({{faktenblatt:voreinstellungen#self-disclosure-settings-enabled}}): Die
  Oberfläche verweist dann auf den Antrag bei der Behörde.
- **Protokoll:** Start und Ende jedes Downloads stehen ohne Inhalt im Protokoll
  ({{faktenblatt:protokollereignisse#self-disclosure-export-started}},
  {{faktenblatt:protokollereignisse#self-disclosure-export-finished}}); die eigenen Einträge erhält
  die Person mit ihrer Selbstauskunft.
- **Konten lassen sich oft nur sperren.** Ein benutztes Konto ist meist nicht löschbar, weil
  Nachweiseinträge darauf verweisen. Dann bleibt der Personenbezug im Protokoll bestehen; die Behörde
  berücksichtigt das bei der Antwort auf ein Löschbegehren.
- **Das Protokoll kennt Pseudonyme**, also Kennungen statt Namen
  ({{faktenblatt:datenkatalog#audit-pseudonyms}}); Klarnamen sieht die Revision nur in den Grenzen
  der {{faktenblatt:rollensicht#auditor}}. Wird das Konto gelöscht, bleibt das Protokoll unverändert,
  nur der Personenbezug entfällt
  ([Löschen oder sperren](../../handbuch/konten-und-rechte/benutzerverwaltung.md#8-löschen-oder-sperren)).
  Ausnahme: Eine Freigabe für eine anlassbezogene Klärung, die die Person nennt, ordnet ihre
  Einträge bis zu deren Löschfrist weiter zu ({{faktenblatt:datenkatalog#audit-incident-scopes}}).
  Regelt eine [Dienstvereinbarung](../05-mitbestimmung/dienstvereinbarung.md) den Zugriff auf das Protokoll, gilt deren Verfahren.
- **Dokumente folgen ihrer Quelle.** Wird ein Dokument dort gelöscht oder berichtigt, zieht OPAA nach
  ([Änderungen und Löschungen](../../handbuch/arbeitsmittel/wissensbibliotheken/README.md#44-änderungen-und-löschungen)).
  Antworten in bestehenden Chats, die es zitiert haben, bleiben bis zur Löschung des Chats.

## Zum Nachlesen

**Arbeitshilfen** für die Projektleitung:

- [Kurzpapiere Nr. 6 und Nr. 11 der Datenschutzkonferenz](https://www.datenschutzkonferenz-online.de/kurzpapiere.html):
  Umfang und Ausnahmen beim Auskunftsrecht und beim Recht auf Löschung.

**Rechtsgrundlagen** für die oder den DSB:

| Worum es geht | Fundstelle |
|---|---|
| Form und Frist der Antwort | Art. 12 Abs. 3 [DSGVO](https://eur-lex.europa.eu/eli/reg/2016/679/oj/deu) |
| Die einzelnen Rechte | Art. 15 bis 21 DSGVO |
| Ausnahmen und Beschränkungen | BDSG bzw. Landesrecht, zu prüfen im Einzelfall |
| Daten über Beschäftigte | § 26 [BDSG](https://www.gesetze-im-internet.de/bdsg_2018/__26.html) bzw. Landesrecht |
