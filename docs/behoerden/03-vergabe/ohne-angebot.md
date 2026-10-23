---
stand: 2026-10-23
gilt-fuer: main
erzeugung: redaktionell
rechtsstand: 2026-10-13
pruefvermerk: nicht rechtlich geprüft
naechste-durchsicht: 2027-04-23
---

# Kein Angebot eingegangen

> Nicht rechtlich geprüft. Kann als Vorlage dienen; die Verantwortung bleibt bei der Behörde.

**Gilt für:** öffentliche Auftraggeber, also Behörden und auch kommunale Gesellschaften, die
ausschreiben müssen, oberhalb und unterhalb der EU-Schwellenwerte.

Für quelloffene Software gibt es oft wenige Dienstleister. Geht auf eine Ausschreibung kein oder kein
wertbares Angebot ein, bleiben mehrere Wege. Welcher im Einzelfall zulässig ist, entscheidet die
Vergabestelle nach dem für sie geltenden Recht.

> **Beispiel.** Auf die Ausschreibung eines Landratsamts geht kein Angebot ein. Zwei Unternehmen aus
> der Markterkundung sagen, das Paket aus Einführung, Betrieb und vier Jahren Pflege sei zu groß. Das
> Amt schreibt in zwei Losen neu aus und erhält Angebote.

## Was OPAA liefert

Jedem Betreiber stehen dieselben Unterlagen offen: das [Handbuch](../../handbuch/README.md), die
[Faktenblätter und das Update-Paket](../../handbuch/nachvollziehbarkeit.md#wo-die-faktenblätter-liegen)
jeder Version und diese Vorlagen. Fragen nimmt das Projekt auf [GitHub](https://github.com/criew/opaa)
entgegen, ohne Zusage zu Reaktionszeiten. Es bietet selbst keine Leistungen an und führt kein
Verzeichnis von Dienstleistern.

## Aufgaben der Behörde

1. **Ursache klären,** etwa durch Nachfrage bei den Unternehmen aus der Markterkundung:
   - **Zuschnitt:** Schließt ein Gesamtpaket über mehrere Jahre kleine Anbieter aus?
   - **Muss-Anforderungen:** Engen sie den Bieterkreis stärker ein als nötig
     ([Anforderungskatalog](anforderungskatalog.md#aufgaben-der-behörde))?
   - **Risiko:** Sind Zusagen für fremden Code verlangt, die niemand kalkulieren kann
     ([Gewährleistung und Haftung](gewaehrleistung-haftung.md))?
   - **Wert:** Deckt die [Auftragswertschätzung](auftragswertschaetzung.md) den Aufwand?
2. **Einen Weg wählen** aus der Tabelle unten und die Zulässigkeit von der Vergabestelle prüfen
   lassen.

## Wege

| Weg | Was er bedeutet | Was die Behörde braucht |
|---|---|---|
| **Losaufteilung** | Einführung und Schulung, Betrieb sowie Pflege werden als getrennte Lose ausgeschrieben; kleinere Anbieter können auf einzelne Lose bieten. | Klare Schnittstellen, etwa wer bei einem Fehler zwischen Betrieb und Pflege zuständig ist. Der Wert aller Lose zusammen bestimmt die Verfahrensart. |
| **Eigenbetrieb** | Die Behörde betreibt und aktualisiert die Installation selbst; ausgeschrieben werden nur Einführung und Schulung, gegebenenfalls Pflege. | Personal mit Erfahrung im Betrieb von Containern, Datenbank und Modellen; [Notfallkonzept](../09-betrieb-update/notfallkonzept.md) und [Update-Vermerk](../09-betrieb-update/update-vermerk.md). |
| **Zusammenarbeit von Behörden** | Inhouse-Vergabe, also Vergabe an eine Einrichtung, die die Behörde kontrolliert, oder Zusammenarbeit mit anderen öffentlichen Auftraggebern, etwa eine Behörde betreibt für mehrere. | Prüfung der Voraussetzungen im Einzelfall. |
| **Rahmenverträge** | Abruf aus einem bestehenden Rahmenvertrag einer anderen Stelle oder einer zentralen Beschaffungsstelle. | Prüfung, ob die Behörde abrufberechtigt ist und die Leistung vom Vertrag gedeckt ist. |
| **Erneutes Verfahren** | Ausschreibung mit geändertem Zuschnitt, geänderten Anforderungen oder anderer Verfahrensart. | Die Vergaberegeln enthalten Wege für den Fall, dass kein geeignetes Angebot einging; die Vergabestelle prüft sie. |

## Gut zu wissen

- Die Regeln zur Zusammenarbeit von Behörden betreffen nur den öffentlichen Bereich. Direktaufträge
  und Rahmenverträge sind eigene Wege mit eigenen
  [Wertgrenzen](auftragswertschaetzung.md#schwellen-und-landesrecht).

## Zum Nachlesen

**Arbeitshilfen** für die Projektleitung:

- [Notfallkonzept](../09-betrieb-update/notfallkonzept.md): was ein Eigenbetrieb für Ausfälle
  vorbereiten muss.

**Rechtsgrundlagen** für die Vergabestelle:

| Worum es geht | Fundstelle |
|---|---|
| Inhouse-Vergabe und horizontale Zusammenarbeit von Behörden; nicht Direktauftrag und Rahmenverträge | § 108 [GWB](https://www.gesetze-im-internet.de/gwb/__108.html) |
| Verfahrenswege nach einer Ausschreibung ohne geeignetes Angebot | zu prüfen |
