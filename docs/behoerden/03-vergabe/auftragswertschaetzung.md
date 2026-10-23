---
stand: 2026-10-17
gilt-fuer: main
erzeugung: redaktionell
rechtsstand: 2026-10-13
pruefvermerk: nicht rechtlich geprüft
naechste-durchsicht: 2027-01-15
---

# Auftragswertschätzung mit Rechenbeispiel

> Nicht rechtlich geprüft. Kann als Vorlage dienen; die Verantwortung bleibt bei der Behörde.

## Hinweise zur Verwendung

Der geschätzte Auftragswert entscheidet über die Verfahrensart. Bei quelloffener Software ohne
Lizenzentgelt besteht er aus den Leistungen eines Dienstleisters: Einführung, Betrieb, Pflege,
Support, Schulung und Anpassungen (Gruppe L im [Anforderungskatalog](anforderungskatalog.md)).
Das Rechenbeispiel zeigt den Aufbau; alle Zahlen darin sind **Annahmen**, keine Marktpreise. Die
Behörde ersetzt sie durch eigene Erfahrungswerte oder die Ergebnisse ihrer Markterkundung und hält
die Herleitung im Vergabevermerk fest.

Nicht Teil dieser Schätzung sind Server, Grafikprozessoren und eigenes Personal. Sie gehören in die
Wirtschaftlichkeitsbetrachtung der Entscheidungsvorlage
([#2562](https://github.com/criew/opaa/issues/2562)) oder in eine eigene Beschaffung.

## Rechenbeispiel

### Annahmen

| Nr. | Annahme | Wert im Beispiel |
|---|---|---|
| A1 | Vertragslaufzeit einschließlich aller Verlängerungsoptionen | 4 Jahre (48 Monate) |
| A2 | Tagessatz des Dienstleisters, netto | 1.100 € |
| A3 | Einführung: Installation, Anbindung von Identitätsanbieter, zwei Ablagen und Modelldienst, Abnahme | 15 Personentage |
| A4 | Schulung: zwei Termine für die Verwaltung, vier für Anwenderinnen und Anwender, je ein halber Tag Vorbereitung eingerechnet | 6 Personentage |
| A5 | Betrieb durch den Dienstleister als Monatspauschale, netto | 1.500 € je Monat |
| A6 | Pflege und Support: Updates einspielen, Fehler analysieren, Anfragen beantworten | 1 Personentag je Monat |
| A7 | Anpassungen als Option, als Abrufkontingent | 20 Personentage |

### Rechnung

| Leistung | Rechnung | Betrag netto |
|---|---|---|
| Einführung | 15 PT × 1.100 € | 16.500 € |
| Schulung | 6 PT × 1.100 € | 6.600 € |
| Betrieb | 48 Monate × 1.500 € | 72.000 € |
| Pflege und Support | 48 PT × 1.100 € | 52.800 € |
| Anpassungen (Option) | 20 PT × 1.100 € | 22.000 € |
| **Geschätzter Auftragswert** | | **169.900 €** |

Varianten mit denselben Annahmen:

| Variante | Weggelassen | Betrag netto |
|---|---|---|
| Eigenbetrieb, der Dienstleister pflegt nur | Betrieb | 97.900 € |
| Eigenbetrieb und eigene Pflege, nur Einführung und Schulung | Betrieb, Pflege und Support, Anpassungen | 23.100 € |

Die zweite Variante liegt unter der Grenze für Direktaufträge des Bundes (siehe unten). Sie trägt
nur, wenn die Behörde Betrieb und Pflege tatsächlich selbst übernimmt; eine Aufteilung, die den
Auftrag nur unter eine Grenze drücken soll, ist unzulässig (siehe Quellen).

## Schwellen und Landesrecht

| Bereich | Was gilt |
|---|---|
| Oberhalb der EU-Schwellenwerte | Vergabeverordnung; Markterkundung nach § 28 VgV. Der Schwellenwert für Dienstleistungen hängt von der Art des Auftraggebers ab und wird regelmäßig angepasst; die Behörde nimmt den zum Zeitpunkt der Einleitung geltenden Wert (zu prüfen). |
| Unterhalb der EU-Schwellenwerte | Unterschwellenvergabeordnung oder Landesrecht, je nachdem, was Bund oder Land anwendet; Markterkundung nach § 20 UVgO bzw. Landesrecht. |
| Direktauftrag des Bundes | Seit 1.7.2026 bis 50.000 € netto (Vergabebeschleunigungsgesetz, BGBl. I 2026 Nr. 137). |
| Direktauftrag von Ländern und Kommunen | Die Grenze des Bundes gilt nicht automatisch. Maßgeblich ist das Landesrecht; die UVgO wird bis Ende 2026 neu gefasst. Diese Vorlage wird danach durchgesehen (`naechste-durchsicht`). |

Zu den Möglichkeiten, wenn auf eine Ausschreibung kein Angebot eingeht, siehe
[Wenn sich niemand bewirbt](ohne-angebot.md).

## Quellen und Prüfstand

| Aussage | Fundstelle | Herkunft |
|---|---|---|
| Markterkundung oberhalb der EU-Schwellenwerte | [§ 28 VgV](https://www.gesetze-im-internet.de/vgv_2016/__28.html) | Normtext; Recherche vom 13.10.2026 |
| Markterkundung unterhalb der EU-Schwellenwerte | § 20 UVgO bzw. Landesrecht; geltende Fassung des Bundes oder Landes zu prüfen | Recherche vom 13.10.2026 |
| Direktauftrag des Bundes bis 50.000 € netto seit 1.7.2026 | Vergabebeschleunigungsgesetz, BGBl. I 2026 Nr. 137; [IHK Schwerin](https://www.ihk.de/schwerin/recht/aktuelles/vergabebeschleunigungsgesetz-ab-01-07-2026-7060088) | Fachbeitrag; Normtext zu prüfen |
| Grenze gilt nicht automatisch für Länder und Kommunen; UVgO wird bis Ende 2026 neu gefasst | [IHK Schwerin](https://www.ihk.de/schwerin/recht/aktuelles/vergabebeschleunigungsgesetz-ab-01-07-2026-7060088) | Fachbeitrag |
| Höhe der EU-Schwellenwerte | geltende Fassung zu prüfen | nicht Teil der Recherche |
| Optionen und Verlängerungen zählen zum Auftragswert; keine Aufteilung, um eine Grenze zu unterschreiten | Schätzungsregeln von VgV, UVgO bzw. Landesrecht; Fundstelle zu prüfen | nicht Teil der Recherche |

Die Recherche vom 13.10.2026 steht im ersten Kommentar von Epic
[#2542](https://github.com/criew/opaa/issues/2542).
