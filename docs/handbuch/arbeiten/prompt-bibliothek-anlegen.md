# Eine Prompt-Bibliothek anlegen

> **Wer darf das:** wer das Anlegerecht „Prompt-Bibliotheken anlegen“ hat; ab Werk jedes Konto.
> Prompts schreiben darf danach, wer an der Bibliothek mindestens *Bearbeiter* ist.

Sie sammeln Formulierungshilfen an einer Stelle, die Sie selbst oder Ihr Referat im Chat einsetzen.

1. Im [Katalog](katalog.md) „Neuer Katalogeintrag“, dann „Prompts“ und „Weiter“.
2. **Stammdaten:** Name und Beschreibung.
3. **Eigentümer:** „Mein Konto“ oder „Eine Gruppe“. Für Prompts, die ein Referat gemeinsam pflegt,
   ist die Gruppe die haltbarere Wahl.
4. **Rechte:** auf Wunsch Rollen für Personen und Gruppen vormerken.
5. Anlegen. Danach im Reiter „Prompts“ „Neuer Prompt“ wählen.

## Einen Prompt schreiben

| Feld | Inhalt |
|---|---|
| Titel | die Bezeichnung, etwa „Anhörung Bußgeld“ |
| Befehl | der Aufruf nach `/`; folgt dem Titel, etwa `anhoerung-bussgeld` |
| Beschreibung | wofür der Prompt gedacht ist |
| Text | die Anweisung, mit Platzhaltern wie `{{aktenzeichen}}` |

Jeder Platzhalter im Text bekommt eine Zeile in der **Variablentabelle**: Beschriftung, Typ (Text,
mehrzeiliger Text, Auswahl, Datum), Pflicht und Vorbelegung. Systemvariablen setzen das heutige
Datum und Ihren Namen ein. Die **Vorschau** zeigt, was beim Einsetzen herauskommt.

Vor dem Speichern prüft der Dialog alles auf einmal und nennt jeden Befund.

**Damit andere den Prompt nutzen**, braucht es zwei Dinge: ein Leserecht an der Bibliothek (Reiter
„Freigaben“) und die Zuordnung zu ihrem Space ([Den Katalog nutzen](katalog.md#in-einem-space-verwenden)).

**Rollen an der Bibliothek:**

| Rolle | Darf |
|---|---|
| Leser | Prompts lesen und einsetzen |
| Bearbeiter | zusätzlich Prompts anlegen, ändern, löschen |
| Verwalter | zusätzlich Name, Beschreibung und Rechte ändern |
| Eigentümer | zusätzlich die Bibliothek löschen |

**Gut zu wissen:**

- Ein Prompt ist reiner Text: Er bindet kein Wissen und wählt kein Modell.
- Ein Text hat höchstens 8000 Zeichen und 20 Variablen.
