# Prompt-Bibliothek anlegen

**Ziel:** Formulierungshilfen an einer Stelle, die Sie oder Ihr Referat im Chat einsetzen.
{: .kopf }

1. Im [Katalog](katalog.md) „Neuer Katalogeintrag“, dann „Prompts“ und „Weiter“.
2. **Stammdaten:** Name und Beschreibung.
3. **Eigentümer:** „Mein Konto“ oder „Eine Gruppe“. Für Prompts, die ein Referat gemeinsam pflegt,
   ist die Gruppe die haltbarere Wahl.
4. **Rechte:** auf Wunsch Rollen für Personen und Gruppen vormerken.
5. Anlegen. Danach im Reiter „Prompts“ „Neuer Prompt“ wählen.

## Prompt schreiben

| Feld | Inhalt |
|---|---|
| Titel | die Bezeichnung, etwa „Anhörung Bußgeld“ |
| Befehl | der Aufruf nach `/`; folgt dem Titel, etwa `anhoerung-bussgeld` |
| Beschreibung | wofür der Prompt gedacht ist |
| Text | die Anweisung, mit Platzhaltern wie `{{aktenzeichen}}` |

Jeder Platzhalter im Text bekommt eine Zeile in der **Variablentabelle**: Beschriftung, Typ (Text,
mehrzeiliger Text, Auswahl, Datum), Pflicht und Vorbelegung. Systemvariablen setzen das heutige
Datum und Ihren Namen ein. Die **Vorschau** zeigt, was beim Einsetzen herauskommt.

Vor dem Speichern prüft der Dialog alles auf einmal und nennt jeden Befund. Fest gelten: Befehl aus
Kleinbuchstaben, Ziffern und einzelnen Bindestrichen, eindeutig in der Bibliothek, höchstens 64
Zeichen; Titel 255, Beschreibung 2000, Text 8000 Zeichen; höchstens 20 Variablen, eine Auswahl mit
1 bis 50 Werten. `{{ aktenzeichen }}` speichert OPAA als `{{aktenzeichen}}`; jede andere Folge in
doppelten Klammern, etwa `{{akten zeichen}}`, lehnt es ab.

**Damit andere den Prompt nutzen**, braucht es zwei Dinge: ein Leserecht an der Bibliothek (Reiter
„Freigaben“) und die Zuordnung zu ihrem Space ([Katalog nutzen](katalog.md#in-einem-space-verwenden)).

**Rollen an der Bibliothek:**

| Rolle | Darf |
|---|---|
| Leser | Prompts lesen und einsetzen |
| Bearbeiter | zusätzlich Prompts anlegen, ändern, löschen |
| Verwalter | zusätzlich Name, Beschreibung und Rechte ändern |
| Eigentümer | zusätzlich die Bibliothek löschen |

An „Alle Konten“ geht höchstens die Rolle *Bearbeiter*.

**Gut zu wissen:**

- Ein Prompt ist reiner Text: Er bindet kein Wissen und wählt kein Modell.
- Im Nachweisprotokoll stehen Anlegen, Ändern und Löschen, bei einer Änderung nur die Namen der
  geänderten Felder, nie Titel oder Text.
