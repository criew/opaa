---
stand: 2026-10-23
gilt-fuer: main
erzeugung: redaktionell
rechtsstand: 2026-10-13
pruefvermerk: nicht rechtlich geprüft
naechste-durchsicht: 2027-04-23
---

# Auftragsverarbeitung (AVV)

> Nicht rechtlich geprüft. Kann als Vorlage dienen; die Verantwortung bleibt bei der Behörde.

**Gilt für:** Behörden, deren Datenschutzaufsicht die oder der BfDI oder die
Landesdatenschutzbeauftragten sind.

Verarbeitet ein Partner personenbezogene Daten im Auftrag der Behörde, etwa ein Rechenzentrum oder
ein Modellanbieter, braucht es einen Vertrag zur Auftragsverarbeitung (AVV), der regelt, was der
Partner mit den Daten tun darf. Diese Matrix zeigt, mit wem das beim Einsatz von OPAA der Fall sein
kann. Ob im Einzelfall Auftragsverarbeitung oder eine andere Form der Verantwortung vorliegt,
bewertet die Behörde mit ihrer oder ihrem Datenschutzbeauftragten (DSB).

> **Beispiel.** Ein Landratsamt lässt OPAA vom kommunalen IT-Dienstleister betreiben, die Modelle
> laufen dort im Rechenzentrum, die Dokumente kommen aus dem eigenen Dateiserver. Ein AVV ist nur mit
> dem Dienstleister nötig; der Modellbetrieb gehört in denselben Vertrag. Als später eine Bibliothek
> aus einem Cloud-Speicher dazukommt, prüft die Projektleitung, ob der bestehende Vertrag mit dem
> Cloud-Anbieter den Abruf durch OPAA abdeckt.

## Was OPAA liefert

Welche Daten an welches Ziel gehen, steht im {{faktenblatt:datenfluesse#datenfluesse}} der
eingesetzten Version. Welche Modelle wofür eingesetzt werden, beschreibt das Handbuch unter
[Modelle](../../handbuch/modelle.md#vier-modellrollen), welche Quellen Cloud-Dienste sind, unter
[Konnektoren im Überblick](../../handbuch/konnektoren/README.md#3-die-konnektoren-im-überblick).

## Die Matrix

Die Behörde ordnet ihren Einsatz an drei Fragen ein. Jede Antwort ergibt die Partner in der letzten
Spalte; die Partner aller drei Antworten zusammen sind die AVV-Partner.

| Frage | Antwort | AVV nötig mit | Worauf achten |
|---|---|---|---|
| Wer betreibt? | **B1** die Behörde selbst | niemandem | — |
| | **B2** ein IT-Dienstleister | dem Dienstleister | Vertrag deckt Datenbank, Ablage, Sicherungen und Logs ab; Unterauftragnehmer offenlegen lassen |
| | **B3** die Behörde, ein Dritter wartet mit Fernzugriff | der Wartungsfirma, wenn es Auftragsverarbeitung ist | Zugriff nur nach Freigabe, protokolliert, auf die Wartung begrenzt |
| Wo läuft das Sprachmodell? | **M1** im Haus oder beim Dienstleister aus B2 | niemandem weiter; bei B2 im Vertrag des Dienstleisters | — |
| | **M2** bei einem externen Anbieter | dem Modellanbieter, direkt oder über den Dienstleister | Verarbeitungsort und Übermittlung in Länder außerhalb der EU, keine Nutzung der Eingaben etwa für Training, Speicherdauer, Unterauftragnehmer; jede Modellrolle einzeln |
| Wo liegen die Quellen? | **K1** im eigenen Netz oder beim Dienstleister | niemandem weiter | — |
| | **K2** mindestens eine in der Cloud | dem Cloud-Anbieter, meist schon vorhanden | deckt der Vertrag den Abruf durch OPAA ab ([Zugänge](../../handbuch/zugaenge.md))? |

Mischformen, etwa Betrieb durch einen Dienstleister und Wartung durch einen weiteren, ergeben sich als
Summe der Antworten.

## Was die Behörde tun muss

1. **Einordnen:** je Frage eine Antwort wählen und die Partner ablesen.
2. **Weitere Ziele prüfen:** jedes Ziel aus dem Faktenblatt Datenflüsse, das die eigene Installation
   nutzt, etwa ein Identitätsanbieter in der Cloud
   ([Authentifizierung](../../handbuch/authentifizierung.md)), ein externer E-Mail-Dienst
   ([E-Mail-Versand](../../handbuch/e-mail.md)), externe KI-Werkzeuge, die Inhalte an eigene
   Modellanbieter weitergeben ([Externe KI-Werkzeuge](../../handbuch/fremdzugaenge.md)), oder ein
   Ausgangsproxy eines Dritten ([Ausgangsproxy](../../handbuch/betrieb.md#ausgangsproxy)).
3. **Verträge schließen** mit dem eigenen Muster oder den Standardvertragsklauseln der EU-Kommission.
4. **Bei jedem Update nachprüfen:** Ändert ein Update die Datenflüsse, weist das Update-Paket das aus.

## Ergebnis festhalten

```text
Einordnung:             Betrieb [B1/B2/B3] · Sprachmodell [M1/M2] · Quellen [K1/K2]
Partner mit AVV:        [Name, Gegenstand, Vertrag vom, Unterauftragnehmer]
Drittlandübermittlung:  [keine / Anbieter, Land, Garantie]
Weitere Ziele geprüft:  [Liste aus dem Faktenblatt Datenflüsse der Version [OPAA-Version]]
Geprüft am:             [Datum]        durch: [ ]
```

## Zum Nachlesen

**Arbeitshilfen** für die Projektleitung:

- [Kurzpapier Nr. 13 der Datenschutzkonferenz](https://www.datenschutzkonferenz-online.de/kurzpapiere.html)
  „Auftragsverarbeitung“: wann ein Partner Auftragsverarbeiter ist, auch bei Wartung und Fernzugriff.
- [Standardvertragsklauseln der EU-Kommission](https://eur-lex.europa.eu/eli/dec_impl/2021/915/oj/deu):
  amtliches Vertragsmuster für den AVV.
- [Orientierungshilfe „Künstliche Intelligenz und Datenschutz“](https://www.datenschutzkonferenz-online.de/media/oh/20240506_DSK_Orientierungshilfe_KI_und_Datenschutz.pdf)
  der Datenschutzkonferenz (6.5.2024): Prüfpunkte für extern bezogene KI-Dienste.

**Rechtsgrundlagen** für die oder den DSB:

| Worum es geht | Fundstelle |
|---|---|
| Auswahl des Auftragsverarbeiters, Vertrag, Mindestinhalt, Unterauftragnehmer | Art. 28 Abs. 1 bis 4 [DSGVO](https://eur-lex.europa.eu/eli/reg/2016/679/oj/deu) |
| Standardvertragsklauseln | Art. 28 Abs. 7 DSGVO, [Durchführungsbeschluss (EU) 2021/915](https://eur-lex.europa.eu/eli/dec_impl/2021/915/oj/deu) |
| Übermittlung in Drittländer | Art. 44 ff. DSGVO |
