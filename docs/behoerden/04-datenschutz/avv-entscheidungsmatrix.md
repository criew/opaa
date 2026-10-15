---
stand: 2026-10-15
gilt-fuer: main
erzeugung: redaktionell
rechtsstand: 2026-10-13
pruefvermerk: nicht rechtlich geprüft
naechste-durchsicht: 2027-04-15
---

# Entscheidungsmatrix zur Auftragsverarbeitung

> Nicht rechtlich geprüft. Kann als Vorlage dienen; die Verantwortung bleibt bei der Behörde.

Diese Matrix hilft einer Behörde zu erkennen, mit wem sie für den Einsatz von OPAA einen Vertrag zur
Auftragsverarbeitung (AVV) nach Art. 28 DSGVO braucht. Sie ordnet den Einsatz an drei Achsen ein:
**wer betreibt**, **wo läuft das Sprachmodell** und **wo liegen die Quellen** der Wissensbibliotheken.
Die Matrix ist eine Orientierung. Ob im Einzelfall Auftragsverarbeitung, gemeinsame Verantwortung oder
eine eigene Verantwortung des Partners vorliegt, bewertet die Behörde.

Welche Daten an welches Ziel gehen, schreibt die Matrix nicht ab. Es steht im
{{faktenblatt:datenfluesse#datenfluesse}} der eingesetzten Version.

## Quellen und Prüfstand

| Fundstelle | Inhalt | Herkunft |
|---|---|---|
| Art. 28 Abs. 1 bis 4 [DSGVO](https://eur-lex.europa.eu/eli/reg/2016/679/oj/deu) | Auswahl des Auftragsverarbeiters, Vertrag oder anderes Rechtsinstrument, Mindestinhalt, Unterauftragsverarbeiter | Normtext; Art. 28 in der Recherche vom 13.10.2026 |
| Art. 44 ff. DSGVO | Übermittlung in Drittländer | Normtext |
| DSK, [Kurzpapiere](https://www.datenschutzkonferenz-online.de/kurzpapiere.html), Nr. 13 „Auftragsverarbeitung, Art. 28 DSGVO“ | Abgrenzung, auch zu Wartung und Fernzugriff | Muster |
| EU-Kommission, [Standardvertragsklauseln nach Art. 28 Abs. 7 DSGVO](https://eur-lex.europa.eu/eli/dec_impl/2021/915/oj/deu) (Durchführungsbeschluss (EU) 2021/915) | amtliches Vertragsmuster | Muster |
| DSK, [Orientierungshilfe „Künstliche Intelligenz und Datenschutz“](https://www.datenschutzkonferenz-online.de/media/oh/20240506_DSK_Orientierungshilfe_KI_und_Datenschutz.pdf) (6.5.2024) | Prüfpunkte für extern bezogene KI-Dienste | Recherche vom 13.10.2026 |

Die Recherche vom 13.10.2026 steht im ersten Kommentar von Epic
[#2542](https://github.com/criew/opaa/issues/2542). Ein eigenes Vertragsmuster enthält diese Vorlage
nicht; die Behörde verwendet ihr Muster oder die Standardvertragsklauseln der Kommission.

## Die drei Achsen

**Betrieb**

| | Bedeutung | Hinweis |
|---|---|---|
| **B1 selbst** | Die Behörde betreibt OPAA auf eigener Infrastruktur mit eigenem Personal. | Aus dem Betrieb selbst entsteht keine Auftragsverarbeitung. |
| **B2 Dienstleister** | Ein IT-Dienstleister betreibt OPAA für die Behörde, etwa ein Rechenzentrum oder ein kommunaler IT-Dienstleister. | AVV mit dem Dienstleister; er erfasst Datenbank, Originalablage, Sicherungen und Logs. Grundlage ist ein Vertrag oder ein anderes Rechtsinstrument (Art. 28 Abs. 3 DSGVO). Unterauftragsverarbeiter des Dienstleisters offenlegen lassen. |
| **B3 Wartung mit Fernzugriff** | Die Behörde betreibt selbst; ein Dritter wartet OPAA oder die Plattform und kann dabei auf Systeme mit personenbezogenen Daten zugreifen. | Ob das Auftragsverarbeitung ist, prüft die Behörde nach dem DSK-Kurzpapier Nr. 13. Zugriff nur nach Freigabe, protokolliert, auf den Wartungszweck begrenzt. |

**Sprachmodell**

| | Bedeutung | Hinweis |
|---|---|---|
| **M1 lokal** | Die Modelle laufen auf Infrastruktur der Behörde oder ihres Betriebsdienstleisters. | Kein weiterer Partner. Läuft das Modell beim Dienstleister aus B2, gehört es in dessen AVV. |
| **M2 externe API** | Mindestens eine Modellrolle ruft einen Anbieter außerhalb des eigenen Betriebs auf. | AVV mit dem Modellanbieter. Achten auf: Verarbeitungsort und Drittlandübermittlung (Art. 44 ff. DSGVO), keine Verwendung der Eingaben für eigene Zwecke des Anbieters (etwa Training), Speicherdauer beim Anbieter, Unterauftragsverarbeiter. Jede Modellrolle einzeln betrachten ([Handbuch, Modelle](../../handbuch/modelle.md#vier-modellrollen)). |

**Quellen der Wissensbibliotheken**

| | Bedeutung | Hinweis |
|---|---|---|
| **K1 im Haus** | Alle angebundenen Quellen laufen im eigenen Netz oder beim Betriebsdienstleister. | Kein weiterer Partner aus OPAA heraus. |
| **K2 Cloud** | Mindestens eine Quelle ist ein Cloud-Dienst ([Handbuch, Konnektoren im Überblick](../../handbuch/konnektoren/README.md#3-die-konnektoren-im-überblick)). | Der Cloud-Anbieter verarbeitet die Dokumente bereits für die Behörde, meist mit eigenem AVV. Zu prüfen ist, ob dieser Vertrag den Abruf durch OPAA über Dienstkonto, App-Registrierung oder Zugang abdeckt ([Handbuch, Zugänge](../../handbuch/zugaenge.md)). |

## Die Matrix

| # | Betrieb | Sprachmodell | Quellen | AVV nötig mit | Worauf achten |
|---|---|---|---|---|---|
| 1 | B1 selbst | M1 lokal | K1 im Haus | keinem Partner aus diesen Achsen | [weitere Ziele](#weitere-ziele-außerhalb-der-achsen) |
| 2 | B1 selbst | M1 lokal | K2 Cloud | Cloud-Anbieter der Quelle (bestehender Vertrag) | K2 |
| 3 | B1 selbst | M2 externe API | K1 im Haus | Modellanbieter | M2 |
| 4 | B1 selbst | M2 externe API | K2 Cloud | Modellanbieter; Cloud-Anbieter der Quelle (bestehender Vertrag) | M2, K2 |
| 5 | B2 Dienstleister | M1 lokal | K1 im Haus | Dienstleister | B2; Modellbetrieb im Vertrag |
| 6 | B2 Dienstleister | M1 lokal | K2 Cloud | Dienstleister; Cloud-Anbieter der Quelle (bestehender Vertrag) | B2, K2 |
| 7 | B2 Dienstleister | M2 externe API | K1 im Haus | Dienstleister; Modellanbieter, direkt oder als Unterauftragsverarbeiter des Dienstleisters | B2, M2; wer den Vertrag mit dem Modellanbieter schließt |
| 8 | B2 Dienstleister | M2 externe API | K2 Cloud | Dienstleister; Modellanbieter; Cloud-Anbieter der Quelle (bestehender Vertrag) | B2, M2, K2 |
| 9 | B3 Wartung | M1 lokal | K1 im Haus | Wartungsdienstleister, wenn Auftragsverarbeitung | B3 |
| 10 | B3 Wartung | M1 lokal | K2 Cloud | Wartungsdienstleister, wenn Auftragsverarbeitung; Cloud-Anbieter der Quelle (bestehender Vertrag) | B3, K2 |
| 11 | B3 Wartung | M2 externe API | K1 im Haus | Wartungsdienstleister, wenn Auftragsverarbeitung; Modellanbieter | B3, M2 |
| 12 | B3 Wartung | M2 externe API | K2 Cloud | Wartungsdienstleister, wenn Auftragsverarbeitung; Modellanbieter; Cloud-Anbieter der Quelle (bestehender Vertrag) | B3, M2, K2 |

Mischformen (etwa Betrieb durch einen Dienstleister und zusätzlich Wartung durch einen weiteren)
ergeben sich als Summe der Zeilen.

## Weitere Ziele außerhalb der Achsen

Unabhängig von der Zeile prüft die Behörde jedes weitere Ziel, an das OPAA in ihrer Instanz Daten
sendet. Die Liste der Datenflüsse steht im {{faktenblatt:datenfluesse#datenfluesse}}. Typische
Kandidaten, je nach Einrichtung:

- ein Identitätsanbieter als Cloud-Dienst ([Handbuch, Authentifizierung](../../handbuch/authentifizierung.md)),
- ein externer Dienst für den E-Mail-Versand ([Handbuch, E-Mail-Versand](../../handbuch/e-mail.md)),
- fremde Werkzeuge über Fremdzugänge, die Inhalte an ihre eigenen Modellanbieter weitergeben können
  ([Handbuch, Fremdzugänge](../../handbuch/fremdzugaenge.md)),
- ein Ausgangsproxy eines Dritten ([Handbuch, Ausgangsproxy](../../handbuch/betrieb.md#ausgangsproxy)).

## Ergebnis festhalten

```text
Einordnung: Betrieb [B1/B2/B3] · Sprachmodell [M1/M2] · Quellen [K1/K2] → Zeile [Nr.]
Partner mit AVV:        [Name, Gegenstand, Vertrag vom, Unterauftragsverarbeiter]
Drittlandübermittlung:  [keine / Anbieter, Land, Garantie]
Weitere Ziele geprüft:  [Liste aus dem Faktenblatt Datenflüsse der Version [OPAA-Version]]
Geprüft am:             [Datum]        durch: [ ]
```

Ändert ein Update die Datenflüsse, weist das Update-Paket das aus
([Spezifikation, Stufenregeln](../../features/behoerdenunterlagen.md#die-stufenregeln), Klasse Z5);
die Behörde prüft die Einordnung dann erneut.
