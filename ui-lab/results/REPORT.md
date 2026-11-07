# UI-Lab #2312 · Quick-Tour für neue Nutzer (Stufe „schnell“)

Prototyp: https://claude.ai/artifact/M19T3bRyHYpf4kAFZcPymF (Variante in der Prototyp-Leiste wählen;
`?variant=none|dialog|coach|list|invite`). Lab-Branch, wird nicht gemergt.

## Entscheidung

Der Maintainer hat am 07.11.2026 **Variante D** gewählt (Hinweis „Neu hier?“ im leeren Chat, startet
die Tour mit Hervorhebungen). Der Juror empfahl unabhängig davon dieselbe Variante. Festgelegt dazu:

| Frage | Entscheidung |
| --- | --- |
| Speicherung „Hinweis erledigt“ | serverseitig je Person |
| Umfang des gespeicherten Zustands | nur „erledigt“; kein Fortschritt, keine Schritte, keine Auswertung |
| Zielgruppe | alle Konten, bis sie die Tour starten oder ablehnen; Admins mit Zusatzstation |
| Betreiber | nur abschalten (`opaa.features.quick-tour.enabled`), keine eigenen Inhalte |
| schmale Ansicht | Stationen der Schublade als ein Schritt „Alles Weitere im Menü“ (Juror F3) |
| Beleg-Beispiel | Miniatur einer Antwort mit Fußnotenmarken in Schritt 1, kein Beispiel-Chat (Juror F5) |

## Varianten

| Variante | Form | Bewertung des Jurors (Kurzfassung) |
| --- | --- | --- |
| A Begrüßungsdialog | Modal, 4 Seiten mit Miniaturen echter Bausteine | öffnet ungefragt; letzte Seite ohne Abbruchstelle (P51); erklärt losgelöst von der Oberfläche |
| B Hervorhebungen | modale Tour mit Hervorhebungen über der echten Oberfläche | öffnet ungefragt; bei 320 px viermal derselbe Menüknopf; „Anbindungen“ kein Glossarbegriff |
| C Erste Schritte | Checkliste statt Begrüßung | Haken beim Klick statt beim Tun; `empty` führt auf „Chat nicht gefunden“; Fokusverlust |
| **D Hinweis + Hervorhebungen** | Hinweis im leeren Chat, startet B | ruhig und an der echten Oberfläche; Fokusverlust nach „Nicht jetzt“ |

## Juror-Befunde zu D und ihre Erledigung

| Befund | Status |
| --- | --- |
| Fokus nach „Nicht jetzt“, Escape und ✕ auf `body` (accessibility 2.1, P26) | erledigt: Fokus ins Eingabefeld |
| 320 px: vier Schritte auf denselben Menüknopf | erledigt: ein Schritt „Alles Weitere im Menü“ (3 statt 6 Schritte) |
| „Anbindungen“ kein Glossarbegriff (P19) | erledigt: „Konten, Gruppen und Modelle“ |
| ✕ nachgebaut statt gemeinsamer Titelbaustein (5.6) | erledigt: `DialogTitleWithClose`, Name „Schließen“ |
| Karte verdeckt die nächste Station | erledigt: Karte über Eingabefeld und Chip-Zeile |
| → beendet die Tour auf dem letzten Schritt | erledigt: Ende nur über „Fertig“ |
| Neuladen mitten in der Tour verliert die Position | bewusst nicht: die Tour beginnt neu, der Hinweis bleibt bis zur Entscheidung |
| drei Textblöcke übereinander (Begrüßung, Hinweis, KI-Hinweis) | bewusst nicht: vertretbar laut Juror; der doppelte KI-Hinweis (P50) betrifft den Ist-Stand |

## Kennzahlen (Messlauf, `compare/metrics-summary.md`)

Szenarien `small` (alle) und `empty` (D), hell und dunkel, 1280 und 320 px:

- Kontrastverstöße 0, Überlauf bei 320 px 0, Zählbudgets eingehalten (Bedienwörter Median D 59, Ist 57)
- axe: `aria-allowed-role` und `region` je Aufnahme wie im Ist-Stand (bestehender Chat)
- „Fokus verdeckt“ nur im Zustand `again` (Profilmenü offen, Auslöser darunter); Messartefakt eines offenen Menüs

## Für die Umsetzung

- Anker über stabile Merkmale (`data-tour` o. ä.) statt `aria-label`- und Klassen-Selektoren; die Lab-Fassung
  sucht Eingabefeld, Chip und Rail-Einträge über ihre Beschriftungen
- Schleier der Tour aus der Theme-Rolle statt aus Skalenwerten im Baustein
- Erweiterungspunkt des leeren Chats (`renderEmptyChatGreeting`) ist eine Lab-Naht; die Umsetzung braucht eine
  abgestimmte Naht zwischen Shell und Chat
