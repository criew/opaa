---
stand: 2026-10-23
gilt-fuer: main
erzeugung: redaktionell
rechtsstand: 2026-10-13
pruefvermerk: nicht rechtlich geprüft
naechste-durchsicht: 2027-04-23
---

# Verzeichnis von Verarbeitungstätigkeiten (VVT)

> Nicht rechtlich geprüft. Kann als Vorlage dienen; die Verantwortung bleibt bei der Behörde.

**Gilt für:** Behörden, deren Datenschutzaufsicht die oder der BfDI oder die
Landesdatenschutzbeauftragten sind.

Das VVT ist die Liste aller Verfahren einer Behörde, in denen personenbezogene Daten verarbeitet
werden, jeweils mit Zweck, Datenarten, Empfängern und Löschfristen. OPAA wird darin als neues Verfahren
eingetragen. Verantwortlich ist die Behörde; die Vorlage ersetzt nicht ihr Formular, sondern sagt je
Angabe, woher sie kommt.

> **Beispiel.** Ein Landratsamt trägt OPAA als eine Tätigkeit „Betrieb des KI-Assistenten“ in sein VVT
> ein. Fast alle Angaben verweisen auf die Faktenblätter der eingesetzten Version, die neben dem
> Eintrag abgelegt sind. Beim nächsten Update meldet das Update-Paket einen neuen Empfänger; die
> Projektleitung trägt die neue Version ein und ergänzt nur diese Zeile.

## Was OPAA liefert

| Angabe im VVT | Woher |
|---|---|
| Verantwortlicher, Datenschutzbeauftragte(r) | Behörde |
| Zwecke | Zeile „Zweck“ je Datum im {{faktenblatt:datenkatalog}}; Einsatzzweck aus der Nutzungsrichtlinie |
| Betroffene Personen | Zeile „Personenbezug“ im {{faktenblatt:datenkatalog}} |
| Datenarten | Übersicht und Zeile „Spalten mit Personenbezug“ im {{faktenblatt:datenkatalog}} |
| Empfänger | intern {{faktenblatt:rollensicht#matrix}}, extern {{faktenblatt:datenfluesse#datenfluesse}}, Auftragsverarbeiter nach der [AVV-Entscheidungsmatrix](./avv-entscheidungsmatrix.md) |
| Übermittlung in Länder außerhalb der EU | {{faktenblatt:datenfluesse#datenfluesse}} und die eingetragenen Modellanbieter |
| Löschfristen | {{faktenblatt:fristen#daten}}, {{faktenblatt:fristen#protokollereignisse}}, [Löschkonzept](./loeschkonzept.md) |
| Schutzmaßnahmen | [Technische und organisatorische Maßnahmen](./technische-und-organisatorische-massnahmen.md), Sicherheitskonzept der Behörde |

Die Faktenblätter zeigen die Voreinstellungen ({{faktenblatt:voreinstellungen#einstellungen}},
{{faktenblatt:fristen#konfigurationsschluessel}}). Was die eigene Installation anders eingestellt hat,
zeigt die [Auskunft über die Datenerhebung](../../handbuch/nachvollziehbarkeit.md#auskunft-über-die-datenerhebung-einer-installation);
im VVT gilt der Wert der Installation.

## Was die Behörde tun muss

1. **Zuschnitt wählen:** eine Tätigkeit für ganz OPAA oder je Bereich des Datenkatalogs eine, etwa
   Konten ({{faktenblatt:datenkatalog#bereich-accounts}}), Protokoll
   ({{faktenblatt:datenkatalog#bereich-audit-log}}), Chats ({{faktenblatt:datenkatalog#bereich-chats}})
   und Wissensbibliotheken ({{faktenblatt:datenkatalog#bereich-libraries}}). Mehrere Tätigkeiten
   passen, wenn verschiedene Stellen zuständig sind oder die Zwecke sich unterscheiden.
2. **Eintrag ausfüllen** nach dem Block unten, mit Verweis auf die Faktenblätter einer bestimmten
   Version.
3. **Rechtsgrundlage und DSFA eintragen**; ob eine
   [Datenschutz-Folgenabschätzung](./datenschutz-folgenabschaetzung.md) nötig ist, steht dort.
4. **Bei jedem Update nachziehen:** Das Update-Paket nennt Änderungen an Zweck, Datenarten, Empfängern
   oder Fristen. Die Behörde trägt die neue Version ein und prüft die geänderten Einträge.

## Ergebnis festhalten

```text
Verarbeitungstätigkeit:  [Name der Installation], KI-gestützter Wissensassistent
Eingesetzte Version:     [OPAA-Version, aus „Info zu …“]
Faktenblätter:           Faktenblätter der Version [OPAA-Version], abgelegt unter [Ablageort]
Zweck:                   [Einsatzzweck]; je Datum: Datenkatalog, Zeile „Zweck“
Rechtsgrundlage:         [für Beschäftigtendaten § 26 BDSG bzw. Landesrecht prüfen]
Betroffene Personen:     [Beschäftigte, die OPAA nutzen; Personen in Dokumenten; weitere]
Datenkategorien:         Datenkatalog der Version, Übersicht
Empfänger:               intern: Rollensicht; extern: Datenflüsse; Auftragsverarbeiter: [Liste]
Drittlandübermittlung:   [keine / Angaben]
Löschfristen:            Löschkonzept [Fundstelle]; abweichende Werte der Installation: [Liste]
TOM:                     TOM des Produkts, Sicherheitskonzept [Fundstelle]
DSFA:                    [erforderlich / nicht erforderlich, Begründung, Fundstelle]
Stand:                   [Datum]        geprüft durch: [Name]
```

## Gut zu wissen

- Dokumente in Wissensbibliotheken enthalten oft Daten Dritter, etwa aus Akten. Sie gehören in der
  Regel zu dem Verfahren, aus dem die Dokumente stammen. Ob OPAA dort als weiterer Zweck oder als
  eigene Tätigkeit einzutragen ist, klärt die Behörde mit der oder dem Datenschutzbeauftragten.
- Nutzt die Behörde das Muster ihrer Aufsichtsbehörde, überträgt sie die Angaben dorthin.

## Zum Nachlesen

**Arbeitshilfen** für die Projektleitung:

- [Muster und Hinweise zum Verzeichnis](https://www.datenschutzkonferenz-online.de/media/ah/201802_ah_muster_verantwortliche.pdf)
  der Datenschutzkonferenz: amtliches Formular mit Ausfüllhinweisen.

**Rechtsgrundlagen** für die oder den DSB:

| Worum es geht | Fundstelle |
|---|---|
| Pflichtangaben des Verzeichnisses (Buchstaben a bis g) | Art. 30 Abs. 1 [DSGVO](https://eur-lex.europa.eu/eli/reg/2016/679/oj/deu) |
| Daten über Beschäftigte | § 26 [BDSG](https://www.gesetze-im-internet.de/bdsg_2018/__26.html) bzw. Landesrecht |
