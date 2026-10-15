---
stand: 2026-10-15
gilt-fuer: main
erzeugung: redaktionell
rechtsstand: 2026-10-13
pruefvermerk: nicht rechtlich geprüft
naechste-durchsicht: 2027-04-15
---

# Baustein für das Verzeichnis von Verarbeitungstätigkeiten

> Nicht rechtlich geprüft. Kann als Vorlage dienen; die Verantwortung bleibt bei der Behörde.

Dieser Baustein hilft einer Behörde, OPAA in ihr Verzeichnis von Verarbeitungstätigkeiten (VVT) nach
Art. 30 Abs. 1 DSGVO aufzunehmen. Er ersetzt nicht das Formular, das die Behörde bereits führt; er
sagt je Pflichtangabe, woher sie kommt. Angaben in eckigen Klammern **[…]** ergänzt die Behörde.

Was OPAA speichert, zu welchem Zweck und wie lange, schreibt dieser Baustein nicht ab. Es steht in den
Faktenblättern der Version, die die Behörde einsetzt
([Fakten verweisen, nicht abschreiben](../README.md#fakten-verweisen-nicht-abschreiben)). Das VVT
bleibt damit über Updates hinweg richtig, solange es auf die Faktenblätter einer bestimmten Version
verweist und diese Version mit jedem Update nachgezogen wird.

## Quellen und Prüfstand

| Fundstelle | Inhalt | Herkunft |
|---|---|---|
| Art. 30 Abs. 1 [DSGVO](https://eur-lex.europa.eu/eli/reg/2016/679/oj/deu) | Pflichtangaben des Verzeichnisses für den Verantwortlichen | Normtext |
| Art. 30 DSGVO | Das Verzeichnis ist laufend aktuell zu halten, auch bei Updates | Recherche vom 13.10.2026 |
| § 26 [BDSG](https://www.gesetze-im-internet.de/bdsg_2018/__26.html) bzw. Landesrecht | Verarbeitung von Beschäftigtendaten; welche Norm für die Behörde gilt, prüft sie selbst | Recherche vom 13.10.2026 |
| DSK, [Muster und Hinweise zum Verzeichnis für Verantwortliche](https://www.datenschutzkonferenz-online.de/media/ah/201802_ah_muster_verantwortliche.pdf) | amtliches Muster der Datenschutzkonferenz | Muster |

Die Recherche vom 13.10.2026 steht im ersten Kommentar von Epic
[#2542](https://github.com/criew/opaa/issues/2542). Verwendet die Behörde das Muster ihrer
Aufsichtsbehörde, übernimmt sie die Zuordnung unten in dieses Muster.

## Zuschnitt: eine oder mehrere Verarbeitungstätigkeiten

Wie fein die Behörde OPAA im VVT zerlegt, entscheidet sie. Zwei verbreitete Wege:

- **Eine Tätigkeit** „Betrieb des KI-Assistenten [Name der Installation]“ mit allen Daten aus dem
  {{faktenblatt:datenkatalog}}.
- **Je Bereich eine Tätigkeit**, entlang der Bereiche des Datenkatalogs, etwa Konten und Anmeldung
  ({{faktenblatt:datenkatalog#bereich-accounts}}), Protokoll
  ({{faktenblatt:datenkatalog#bereich-audit-log}}), Chats ({{faktenblatt:datenkatalog#bereich-chats}})
  und Wissensbibliotheken ({{faktenblatt:datenkatalog#bereich-libraries}}). Das passt, wenn
  verschiedene Stellen zuständig sind oder Bereiche unterschiedliche Zwecke haben.

Dokumente in Wissensbibliotheken enthalten oft personenbezogene Daten Dritter, etwa aus Akten oder
Fachverfahren. Diese Daten gehören in der Regel zu der Verarbeitungstätigkeit, aus der die Dokumente
stammen. Ob die Aufnahme in OPAA dort als weiterer Zweck oder als eigene Tätigkeit einzutragen ist,
klärt die Behörde mit ihrer oder ihrem Datenschutzbeauftragten.

## Pflichtangaben nach Art. 30 Abs. 1 DSGVO

| Angabe | Fundstelle | Woher | Eintrag der Behörde |
|---|---|---|---|
| Name und Kontaktdaten des Verantwortlichen, ggf. Vertreter, Datenschutzbeauftragte | lit. a | Behörde | [Behörde, Anschrift, Kontakt der oder des Datenschutzbeauftragten] |
| Zwecke der Verarbeitung | lit. b | Zeile „Zweck“ je Datum im {{faktenblatt:datenkatalog}}; der Einsatzzweck im Haus aus der Nutzungsrichtlinie | [Einsatzzweck, etwa „Beantwortung von Fragen zu internen Dokumenten mit Belegen“] |
| Kategorien betroffener Personen | lit. c | Zeile „Personenbezug“ im {{faktenblatt:datenkatalog}} | [Beschäftigte, die OPAA nutzen; Personen, deren Daten in Dokumenten stehen; weitere] |
| Kategorien personenbezogener Daten | lit. c | Übersicht und Zeile „Spalten mit Personenbezug“ im {{faktenblatt:datenkatalog}} | Verweis auf den Datenkatalog der eingesetzten Version |
| Kategorien von Empfängern | lit. d | intern: {{faktenblatt:rollensicht#matrix}}; extern: {{faktenblatt:datenfluesse#datenfluesse}} | [Auftragsverarbeiter nach der [AVV-Entscheidungsmatrix](./avv-entscheidungsmatrix.md)] |
| Übermittlung an Drittländer | lit. e | {{faktenblatt:datenfluesse#datenfluesse}} und die eingetragenen Modellanbieter der Instanz | [keine / Anbieter, Land, Garantie] |
| Fristen für die Löschung | lit. f | {{faktenblatt:fristen#daten}} und {{faktenblatt:fristen#protokollereignisse}} | Verweis auf das [Löschkonzept](./loeschkonzept.md) |
| Allgemeine Beschreibung der technischen und organisatorischen Maßnahmen | lit. g | [TOM des Produktanteils](./technische-und-organisatorische-massnahmen.md) | [Verweis auf das Sicherheitskonzept der Behörde] |

Ergänzend führen viele Behörden im VVT die **Rechtsgrundlage**, die Angabe, ob eine
**Datenschutz-Folgenabschätzung** nötig ist ([DSFA-Baustein](./datenschutz-folgenabschaetzung.md)),
und die **Version** der eingesetzten Software.

## Werte der Instanz

Die Faktenblätter beschreiben die Voreinstellung einer Version. Hat die Behörde eine Frist, eine
Voreinstellung oder einen Modellanbieter geändert, gilt im VVT der Wert ihrer Instanz:

- Voreinstellungen und Standardwerte: {{faktenblatt:voreinstellungen#einstellungen}}
- Fristen mit Konfigurationsschlüssel: {{faktenblatt:fristen#konfigurationsschluessel}}
- Wirksame Werte der Instanz: [aus der Auskunft über die Datenerhebung mit Konfigurationsexport,
  [#2558](https://github.com/criew/opaa/issues/2558); bis dahin von der Systemverwaltung erhoben]

## Bei jedem Update

Das VVT ist laufend aktuell zu halten. Das Update-Paket nennt je Änderung, ob das VVT anzupassen ist
(Änderung an Zweck, Datenkategorie, Empfänger oder Frist), siehe
[Spezifikation, Stufenregeln](../../features/behoerdenunterlagen.md#die-stufenregeln). Die
Behörde trägt dann die neue Version ein und prüft die geänderten Einträge.

## Eintrag im VVT

```text
Verarbeitungstätigkeit:       [Name der Installation], KI-gestützter Wissensassistent
Eingesetzte Version:          [OPAA-Version, aus „Info zu …“]
Faktenblätter:                Faktenblätter der Version [OPAA-Version], abgelegt unter [Ablageort]
Zweck:                        [Einsatzzweck]; je Datum: Datenkatalog, Zeile „Zweck“
Rechtsgrundlage:              [Rechtsgrundlage; für Beschäftigtendaten § 26 BDSG bzw. Landesrecht prüfen]
Betroffene Personen:          [Kategorien]
Datenkategorien:              Datenkatalog der Version, Übersicht
Empfänger:                    intern: Rollensicht; extern: Datenflüsse; Auftragsverarbeiter: [Liste]
Drittlandübermittlung:        [keine / Angaben]
Löschfristen:                 Löschkonzept [Fundstelle]; Fristen der Version, abweichende Werte der Instanz: [Liste]
TOM:                          TOM des Produktanteils, Sicherheitskonzept [Fundstelle]
DSFA:                         [erforderlich / nicht erforderlich, Begründung, Fundstelle]
Stand:                        [Datum]        geprüft durch: [Name]
```
