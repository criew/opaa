---
stand: 2026-10-17
gilt-fuer: main
erzeugung: redaktionell
rechtsstand: 2026-10-13
pruefvermerk: nicht rechtlich geprüft
naechste-durchsicht: 2027-01-15
---

# Wenn sich niemand bewirbt

> Nicht rechtlich geprüft. Kann als Vorlage dienen; die Verantwortung bleibt bei der Behörde.

## Hinweise zur Verwendung

Für quelloffene Software gibt es oft wenige Dienstleister. Geht auf eine Ausschreibung kein oder
kein wertbares Angebot ein, bleiben mehrere Wege. Diese Seite beschreibt sie nebeneinander; welcher
im Einzelfall zulässig ist, entscheidet die Vergabestelle nach dem für sie geltenden Recht. Das
Projekt selbst bietet keine Leistungen an und führt kein Verzeichnis von Dienstleistern.

## Zuerst: Warum kam kein Angebot?

Bevor ein anderer Weg gewählt wird, prüft die Behörde die Ursache, etwa durch Nachfrage bei den
Unternehmen aus der Markterkundung:

- **Zuschnitt:** Ein Gesamtpaket aus Einführung, Betrieb, Pflege und Schulung über mehrere Jahre
  schließt kleine Anbieter aus.
- **Muss-Anforderungen:** Engt eine Anforderung den Bieterkreis stärker ein als nötig? Die
  Begründung aus dem Vergabevermerk ([Anforderungskatalog](anforderungskatalog.md#hinweise-zur-verwendung))
  wird erneut geprüft.
- **Risiko:** Sind Zusagen zu Verfügbarkeit oder Fehlerbehebung für Code eines Dritten verlangt, die
  ein Dienstleister nicht kalkulieren kann ([Gewährleistung und Haftung](gewaehrleistung-haftung.md))?
- **Wert:** Deckt die [Auftragswertschätzung](auftragswertschaetzung.md) den tatsächlichen Aufwand?

## Wege

| Weg | Was er bedeutet | Was die Behörde braucht |
|---|---|---|
| **Losaufteilung** | Einführung und Schulung, Betrieb sowie Pflege werden als getrennte Lose ausgeschrieben; kleinere Anbieter können auf einzelne Lose bieten. | Schnittstellen zwischen den Losen, etwa wer bei einem Fehler zwischen Betrieb und Pflege zuständig ist. Der Wert aller Lose zusammen bestimmt die Verfahrensart. |
| **Eigenbetrieb** | Die Behörde betreibt und aktualisiert die Installation selbst; ausgeschrieben werden nur Einführung und Schulung, gegebenenfalls Pflege. | Personal mit Erfahrung im Betrieb von Containern, Datenbank und Modellen; [Handbuch](../../handbuch/README.md), [Notfallkonzept](../09-betrieb-update/notfallkonzept.md) und [Update-Vermerk](../09-betrieb-update/update-vermerk.md). |
| **Zusammenarbeit von Behörden** | Inhouse-Vergabe an eine kontrollierte Einrichtung oder horizontale Zusammenarbeit mit anderen öffentlichen Auftraggebern, etwa eine Behörde betreibt für mehrere (§ 108 GWB). | Prüfung der Voraussetzungen der Vorschrift im Einzelfall. |
| **Rahmenverträge** | Abruf aus einem bestehenden Rahmenvertrag einer anderen Stelle oder einer zentralen Beschaffungsstelle, wenn er passende Leistungen umfasst. | Prüfung, ob die Behörde abrufberechtigt ist und die Leistung vom Vertrag gedeckt ist. |
| **Erneutes Verfahren** | Ausschreibung mit geändertem Zuschnitt, geänderten Anforderungen oder einer anderen Verfahrensart. | Die Vergaberegeln enthalten Wege für den Fall, dass kein geeignetes Angebot einging; die Fundstelle prüft die Vergabestelle (zu prüfen). |

§ 108 GWB betrifft nur die Zusammenarbeit innerhalb des öffentlichen Bereichs. Direktaufträge und
Rahmenverträge sind eigene Wege; für sie gelten die Wertgrenzen und Regeln aus der
[Auftragswertschätzung](auftragswertschaetzung.md#schwellen-und-landesrecht).

## Was das Projekt beiträgt

Unabhängig vom Weg stehen jedem Betreiber dieselben Unterlagen zur Verfügung: das Handbuch, die
[Faktenblätter und das Update-Paket](../../handbuch/nachvollziehbarkeit.md#wo-die-faktenblätter-liegen)
jeder Version und die Vorlagen in diesem Ordner. Fragen und Fehler nimmt das Projekt über die
[Issues](https://github.com/criew/opaa/issues) entgegen, ohne Zusage zu Reaktionszeiten.

## Quellen und Prüfstand

| Aussage | Fundstelle | Herkunft |
|---|---|---|
| Inhouse-Vergabe und horizontale Zusammenarbeit von Behörden; nicht Direktauftrag und Rahmenverträge | [§ 108 GWB](https://www.gesetze-im-internet.de/gwb/__108.html) | Normtext; Recherche vom 13.10.2026 |
| Verfahrenswege nach einer Ausschreibung ohne geeignetes Angebot | Fundstelle zu prüfen | nicht Teil der Recherche |

Die Recherche vom 13.10.2026 steht im ersten Kommentar von Epic
[#2542](https://github.com/criew/opaa/issues/2542).
