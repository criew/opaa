---
stand: 2026-10-15
gilt-fuer: main
erzeugung: redaktionell
rechtsstand: 2026-10-13
pruefvermerk: nicht rechtlich geprüft
naechste-durchsicht: 2027-04-15
---

# Baustein für die Datenschutz-Folgenabschätzung

> Nicht rechtlich geprüft. Kann als Vorlage dienen; die Verantwortung bleibt bei der Behörde.

Dieser Baustein liefert den Teil einer Datenschutz-Folgenabschätzung (DSFA) nach Art. 35 DSGVO, den
OPAA beisteuern kann: die Beschreibung der Verarbeitung, typische Risiken eines KI-Assistenten und die
Maßnahmen, die das Produkt mitbringt. Bewertung, Abwägung und Entscheidung bleiben bei der Behörde.
Angaben in eckigen Klammern **[…]** ergänzt sie.

## Quellen und Prüfstand

| Fundstelle | Inhalt | Herkunft |
|---|---|---|
| Art. 35 Abs. 1, 7 [DSGVO](https://eur-lex.europa.eu/eli/reg/2016/679/oj/deu) | Pflicht zur DSFA bei voraussichtlich hohem Risiko; Mindestinhalt | Normtext |
| Art. 35 Abs. 4 DSGVO | Listen der Aufsichtsbehörden, für welche Verarbeitungen eine DSFA durchzuführen ist („Muss-Listen“) | Normtext |
| Art. 35 Abs. 11 DSGVO | Überprüfung der DSFA, zumindest wenn sich das Risiko ändert | Recherche vom 13.10.2026 |
| § 26 [BDSG](https://www.gesetze-im-internet.de/bdsg_2018/__26.html) bzw. Landesrecht | Beschäftigtendaten | Recherche vom 13.10.2026 |
| DSK, [Orientierungshilfe „Künstliche Intelligenz und Datenschutz“](https://www.datenschutzkonferenz-online.de/media/oh/20240506_DSK_Orientierungshilfe_KI_und_Datenschutz.pdf) (6.5.2024) | Prüfraster für Auswahl, Einführung und Nutzung von KI-Anwendungen | Recherche vom 13.10.2026 |
| DSK, [Kurzpapiere](https://www.datenschutzkonferenz-online.de/kurzpapiere.html), Nr. 5 „Datenschutz-Folgenabschätzung“ | Ablauf einer DSFA | Muster |

Die Recherche vom 13.10.2026 steht im ersten Kommentar von Epic
[#2542](https://github.com/criew/opaa/issues/2542).

## Ist eine DSFA nötig?

Diese Vorlage enthält **keine eigene Schwellwertanalyse**. Maßgeblich sind:

1. **Die Muss-Liste der zuständigen Aufsichtsbehörde** nach Art. 35 Abs. 4 DSGVO:
   - **Bundesbehörden:** die Liste der oder des Bundesbeauftragten für den Datenschutz und die
     Informationsfreiheit ([BfDI](https://www.bfdi.bund.de/)).
   - **Landes- und Kommunalbehörden:** die Liste der oder des Landesbeauftragten für den Datenschutz
     des jeweiligen Landes, auf deren Seiten.
   - Die [Liste der Datenschutzkonferenz](https://www.datenschutzkonferenz-online.de/media/ah/20181017_ah_DSK_DSFA_Muss-Liste_Version_1.1_Deutsch.pdf)
     gilt nur für den **nicht-öffentlichen Bereich**. Für Behörden ist sie höchstens eine
     Orientierung, nicht die maßgebliche Liste.
2. **Die DSK-Orientierungshilfe „KI und Datenschutz“** als Prüfraster für den KI-Anteil.

Nach der Recherche erfassen die Muss-Listen Beschäftigtenüberwachung und KI-Verfahren regelmäßig. Wie
viele Daten über Beschäftigte entstehen, zeigt die Spalte „Personenbezug“ im
{{faktenblatt:datenkatalog}}. Entscheidet die Behörde gegen eine DSFA, hält sie die Begründung fest.

## 1. Systematische Beschreibung der Verarbeitung

| Teil | Quelle |
|---|---|
| Zweck und Einsatzzweck | [Einsatzzweck der Behörde]; je Datum die Zeile „Zweck“ im {{faktenblatt:datenkatalog}} |
| Daten und betroffene Personen | {{faktenblatt:datenkatalog}} |
| Wer was sieht | {{faktenblatt:rollensicht}} |
| Was protokolliert wird | {{faktenblatt:protokollereignisse}} |
| Fristen | {{faktenblatt:fristen}} |
| Datenflüsse nach außen | {{faktenblatt:datenfluesse#datenfluesse}} |
| KI-Funktionen und System-Prompts | {{faktenblatt:voreinstellungen#ki-funktionen}}, {{faktenblatt:voreinstellungen#system-prompts}} |
| Betriebsmodell, Modellanbieter, Quellen | [Angaben der Behörde], Einordnung in der [AVV-Entscheidungsmatrix](./avv-entscheidungsmatrix.md) |
| Eingesetzte Version | [OPAA-Version] |

Die Faktenblätter beschreiben die Voreinstellung der Version. Abweichende Werte der Instanz trägt die
Behörde ein [aus dem Konfigurationsexport, [#2558](https://github.com/criew/opaa/issues/2558)].

## 2. Notwendigkeit und Verhältnismäßigkeit

[Warum der Assistent für den Zweck erforderlich ist; welche Bibliotheken und Spaces eingerichtet
werden und warum; ob Dokumente mit besonders schutzbedürftigen Daten ausgeschlossen werden; welche
Fristen die Behörde wählt und warum, mit Verweis auf das [Löschkonzept](./loeschkonzept.md).]

## 3. Risiken und Maßnahmen

Die Tabelle nennt typische Risiken eines KI-Assistenten in einer Behörde. Die Spalte „Produkt“
verweist auf das, was OPAA dazu mitbringt; ob das genügt, bewertet die Behörde. Weitere Risiken
ergänzt sie.

| Risiko | Wo es sich zeigt | Produkt | Maßnahmen der Behörde | Bewertung |
|---|---|---|---|---|
| Leistungs- und Verhaltenskontrolle der Beschäftigten | Zeile „Mögliche Auswertungen“ im {{faktenblatt:datenkatalog}}; {{faktenblatt:protokollereignisse}} | Pseudonymisiertes Protokoll ({{faktenblatt:datenkatalog#audit-pseudonyms}}), Lesezugriff der Revision ({{faktenblatt:rollensicht#auditor}}); [Mitbestimmungsfähigkeit](../../features/security-and-compliance.md#mitbestimmungsfähigkeit) | [Dienstvereinbarung, [#2566](https://github.com/criew/opaa/issues/2566); Rollenvergabe] | [ ] |
| Inhalte von Fragen und Antworten werden Dritten bekannt | {{faktenblatt:datenkatalog#chats}} | Sichtbarkeit und Löschung laut Datenkatalog; Bereinigung je Space ({{faktenblatt:fristen#opaa-chat-auto-cleanup-delete-after-days}}) | [Regeln der Nutzungsrichtlinie] | [ ] |
| Zu weit gefasste Rechte an Dokumenten mit Personendaten | {{faktenblatt:datenkatalog#documents}}, {{faktenblatt:datenkatalog#rights}} | Rechte je Arbeitsmittel, Rechtehistorie ({{faktenblatt:datenkatalog#permission-history}}); [Handbuch, Berechtigungen](../../handbuch/berechtigungen.md) | [Freigabeprozess für Bibliotheken; Ausschluss bestimmter Quellen] | [ ] |
| Abfluss an einen externen Modellanbieter oder ein fremdes Werkzeug | {{faktenblatt:datenfluesse#datenfluesse}}; {{faktenblatt:datenkatalog#external-access-tokens}} | Voreingestellt lokale Modelle ([Handbuch, Modelle](../../handbuch/modelle.md#llm-anbieter)); externe KI-Werkzeuge mit eigenem Schalter ([Handbuch, Externe KI-Werkzeuge](../../handbuch/fremdzugaenge.md)) | [Wahl des Anbieters, AVV, Netzregeln] | [ ] |
| Falsche oder erfundene Aussagen über Personen in Antworten | {{faktenblatt:voreinstellungen#ki-funktion-chat-answer}} | Antworten mit Fundstellen und Belegprüfung ([Handbuch, Suche](../../handbuch/suche.md#7-belege-prüfen-und-fundstellen-bilden)) | [Regel „Antwort ist Entwurf“ der Nutzungsrichtlinie; keine Entscheidungen über Personen] | [ ] |
| Daten bleiben länger als nötig | {{faktenblatt:fristen}} | Fristen und Löschmechanismen je Datum im Datenkatalog | [gewählte Fristen, Löschkonzept] | [ ] |
| Unbefugter Zugriff auf Datenbank, Sicherungen oder Geheimnisse | Zeile „Zugriffsweg“ im {{faktenblatt:datenkatalog}} | [TOM des Produktanteils](./technische-und-organisatorische-massnahmen.md) | [Sicherheitskonzept, Betrieb] | [ ] |

## 4. Ergebnis

```text
DSFA für:                 [Name der Installation], OPAA-Version [Version]
Erforderlich nach:        [Muss-Liste / eigene Prüfung], Begründung: [ ]
Restrisiko:               [ ]
Stellungnahme der oder des Datenschutzbeauftragten (Art. 35 Abs. 2 DSGVO): [ ]
Entscheidung:             [ ]        durch: [ ]        am: [ ]
Nächste Überprüfung:      spätestens beim nächsten Update-Paket mit Hinweis auf DSFA-Überprüfung
```

Bleibt nach den Maßnahmen ein hohes Risiko, prüft die Behörde die vorherige Konsultation der
Aufsichtsbehörde (Art. 36 DSGVO, Normtext).

## Überprüfung bei Updates

Art. 35 Abs. 11 DSGVO verlangt eine Überprüfung, wenn sich das Risiko ändert. Das Update-Paket jeder
Version nennt je Änderung, ob die DSFA zu überprüfen ist, und schlägt das bei jeder Änderung der Stufe
„Zustimmung“ vor ([Spezifikation, Stufenregeln](../../features/behoerdenunterlagen.md#die-stufenregeln),
im Aufbau mit [#2554](https://github.com/criew/opaa/issues/2554)). Dieser Vorschlag ist eine
Heuristik, keine Ableitung aus der Norm; ob sich das Risiko ändert, bewertet die Behörde. Die
Überprüfung hält sie mit Version und Datum in Abschnitt 4 fest.
