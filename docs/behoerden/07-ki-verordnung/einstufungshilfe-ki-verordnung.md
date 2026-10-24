---
stand: 2026-10-24
gilt-fuer: main
erzeugung: redaktionell
rechtsstand: 2026-10-23
pruefvermerk: nicht rechtlich geprüft
naechste-durchsicht: 2027-04-24
---

# KI-Verordnung: Einstufung

> Nicht rechtlich geprüft. Kann als Vorlage dienen; die Verantwortung bleibt bei der Behörde.

**Gilt für:** alle Behörden, die OPAA einsetzen; die KI-Verordnung unterscheidet nicht zwischen Bund,
Ländern und Kommunen.

Die KI-Verordnung der EU legt fest, welche Pflichten beim Einsatz eines KI-Systems gelten. Welche das
sind, hängt von der Rolle der Behörde und vom Einsatzzweck ab, nicht von der Software allein. Die
Einstufung trifft die Behörde selbst und wiederholt sie, wenn sich der Zweck ändert.

## Was OPAA liefert

- **KI-Funktionen und Modelle** der Version: {{faktenblatt:voreinstellungen#ki-funktionen}}; welche
  Daten an einen Modellanbieter gehen: {{faktenblatt:datenfluesse}}.
- **Eigener Name:** Unter Administration → Branding ersetzt die Behörde Produktname, Claim und Logo.
- **Kennzeichnung:** Der Chat nennt vor der ersten Frage und dauerhaft unter dem Eingabefeld, dass
  Antworten von einer KI stammen, und jede Antwort trägt das Kennzeichen „KI-erzeugt“. Den
  Hinweistext pflegt die Behörde unter Administration → Branding: änderbar, nicht abschaltbar. Der
  Kopierknopf hängt denselben Text mit Produktname und Datum an, ohne Modellname; von Hand markierter
  Text bleibt unverändert. Die API kennzeichnet Antworten maschinenlesbar.
- **Nicht geliefert:** ein Wasserzeichen im Text, ein signiertes Manifest und eine Erkennung, ob ein
  Text aus OPAA stammt. Eine Markierung im Text kann nur das Modell setzen
  ([Handbuch, Modelle](../../handbuch/betrieb/modelle.md#kennzeichnung-erzeugter-texte)).
- **Kein Schulungsmaterial;** das Handbuch zur Suche taugt als Grundlage einer Einweisung.
- **Updates:** Das Update-Paket weist geänderte Standardmodelle und Prompts aus.

## Aufgaben der Behörde

1. **Einsatzzweck schriftlich festlegen:** wofür, für wen, mit welchen Bibliotheken und Spaces. Der
   natürliche Ort ist die [Nutzungsrichtlinie](../08-nutzung/nutzungsrichtlinie.md).
2. **Rolle klären:** Betreiber ist, wer ein KI-System in eigener Verantwortung verwendet; Anbieter, wer
   es unter eigenem Namen oder eigener Marke in Betrieb nimmt. Wer OPAA unter eigenem Namen betreibt,
   kann so selbst Anbieter werden und braucht dann die Mittel zur Kennzeichnung, die OPAA liefert. Ob
   schon der unveränderte Name „OPAA“ genügt, ist offen. Die Ausnahme für quelloffene Software hilft
   hier nicht.
3. **Hochrisiko prüfen:** Hochriskant ist ein Einsatz in einem der Bereiche, die die Verordnung
   aufzählt, für Behörden etwa Personal (Bewerbungen, Beförderung, Leistungsbewertung), Anspruch auf
   öffentliche Leistungen, Strafverfolgung, Migration und Rechtspflege. Ein Wissensassistent mit
   Belegen ist in der Regel nicht hochriskant. Trifft ein Bereich zu, wird die Behörde Anbieter eines
   Hochrisiko-Systems; dann reicht diese Hilfe nicht.
4. **Über die KI informieren:** Nutzende erfahren spätestens bei der ersten Nutzung, klar und
   barrierefrei, dass sie mit einer KI arbeiten. Die Behörde prüft den Hinweistext, passt ihn bei
   Bedarf an und prüft die Barrierefreiheit in ihrer Umgebung. Übernimmt sie eine Antwort in eine
   Veröffentlichung, legt sie die KI-Herkunft offen, außer ein Mensch hat den Text geprüft und trägt
   die redaktionelle Verantwortung; der Kopiervermerk hilft, ersetzt diese Regel aber nicht.
5. **Markierung belegen:** Als Anbieterin stützt sich die Behörde auf die Markierung des
   Modellanbieters und hält Modell und Zusage fest. Markiert das Modell nicht, etwa ein lokales,
   dokumentiert sie Lückenanalyse und Begründung (siehe Restrisiko). Bei Modellwechsel prüft sie neu.
6. **KI-Kompetenz unterstützen:** die Beschäftigten einweisen. Die Handbuchkapitel allein erfüllen das
   nicht.
7. **Ergebnis festhalten** und neu einstufen bei geändertem Zweck (etwa Spaces mit Personalakten oder
   Antworten, die in Entscheidungen über Personen einfließen), anderem Namen, Weitergabe an andere
   Stellen, einem Update mit geänderter KI-Einstufung oder neuer Rechtslage.

## Ergebnis festhalten

```text
Einstufung KI-Verordnung für: [Behörde], OPAA-Version [Version]
Name im Haus:          [ ]
Einsatzzweck:          [ ]
Rolle:                 [Anbieter / Betreiber], Begründung: [ ]
Hochrisiko:            geprüfte Bereiche [ ], Ergebnis [ ]
Hinweis auf KI:        wo [ ], ab wann [ ], barrierefrei [ ]
Hinweistext:           [Standard / angepasst]
Modell, Markierung:    [ ], Anbieter markiert Text [ja, Nachweis / nein, Lückenanalyse]
Veröffentlichungen:    Regel zur redaktionellen Kontrolle [ ]
KI-Kompetenz:          Maßnahmen [ ]
Geprüft am:            [ ]        durch: [ ]
```

## Restrisiko bei lokalen Modellen

Ein lokales Modell ohne Wasserzeichen markiert keinen Text, und weder OPAA noch die Behörde kann das
nachholen. Bleibt das Argument „technisch nicht machbar“, gestützt auf Lückenanalyse und die
sichtbaren Mittel von OPAA. Ob die Aufsicht das genügen lässt, ist offen. Ein Wasserzeichen oder
Manifest im Text liefert OPAA nicht und sagt es nicht zu.

## Gut zu wissen

- Die Transparenzpflichten gelten seit August 2026, die Hochrisiko-Pflichten für Behördenbereiche ab
  Dezember 2027.
- Für die Markierung erzeugter Texte gilt bis Dezember 2026 eine Übergangsfrist, nur für Systeme, die
  vor August 2026 in Betrieb waren; der Hinweis auf die KI hat keine Frist.
- Die Angaben zur Änderungsverordnung von 2026 (Digital-Omnibus) folgen der konsolidierten Fassung
  im KI-VO-Explorer der EU-Kommission; maßgeblich ist der Wortlaut im Amtsblatt.
- Die Pflichten des Modellanbieters liegen außerhalb dieser Hilfe.

## Zum Nachlesen

**Arbeitshilfen** für die Projektleitung:

- [Handbuch, Suche](../../handbuch/spaces/suche.md): wie eine Antwort entsteht und wie man Belege prüft;
  Grundlage für die Einweisung.
- [Handbuch, Branding](../../handbuch/betrieb/branding.md): wo der eigene Name und der Hinweistext
  gepflegt werden.
- [Handbuch, Modelle](../../handbuch/betrieb/modelle.md#kennzeichnung-erzeugter-texte): was die
  Markierung erzeugter Texte vom Modell abhängig macht.
- [Update-Paket eines Release](../../handbuch/betrieb/nachvollziehbarkeit.md#das-update-paket-eines-release):
  welche Änderungen ein Update meldet und mit welcher Stufe es sie zur Bewertung vorschlägt.

**Rechtsgrundlagen** für Justiziariat und Datenschutz:

| Worum es geht | Fundstelle |
|---|---|
| Begriffe Anbieter und Betreiber | Art. 3 Nr. 3 und 4 [KI-VO](https://eur-lex.europa.eu/eli/reg/2024/1689/oj/deu) |
| Ausnahme für quelloffene Software gilt nicht für die Transparenzpflichten | Art. 2 Abs. 12 KI-VO |
| Hochrisiko-Bereiche und Ausnahmen | Anhang III, Art. 6 Abs. 3 KI-VO |
| Behörde wird Anbieter durch hochriskanten Einsatz | Art. 25 Abs. 1 lit. c KI-VO |
| Transparenzpflichten (Hinweis, Kennzeichnung, Veröffentlichung, Form) | Art. 50 Abs. 1, 2, 4 und 5 KI-VO |
| Auslegung durch die Kommission: Hinweis vor der ersten Interaktion, Markierung durch den vorgelagerten Modellanbieter (Rn. 74), „technisch möglich“ (Rn. 81), Lückenanalyse ohne Unterzeichnung des Kodex (Rn. 148) | [Leitlinien zu Art. 50, C(2026) 5054 vom 20.7.2026](https://digital-strategy.ec.europa.eu/en/library/guidelines-transparency-obligations-providers-and-deployers-ai-systems) |
| Verhaltenskodex zur Kennzeichnung KI-erzeugter Inhalte (freiwillig) und seine Bewertung als angemessen | [Kodex](https://digital-strategy.ec.europa.eu/en/policies/code-practice-ai-generated-content), [Bewertung der Kommission](https://digital-strategy.ec.europa.eu/en/library/commission-opinion-assessment-code-practice-transparency-ai-generated-content) |
| KI-Kompetenz, Neufassung durch den Digital-Omnibus | [Art. 4 KI-VO](https://ai-act-service-desk.ec.europa.eu/de/ai-act/article-4) (konsolidiert) |
| Geltungsbeginn von Art. 4, Art. 5 und Art. 50 | [Art. 113 KI-VO](https://ai-act-service-desk.ec.europa.eu/de/ai-act/article-113) (konsolidiert) |
| Änderungsverordnung (Digital-Omnibus) | [VO (EU) 2026/1744](https://eur-lex.europa.eu/eli/reg/2026/1744/oj/deu) |
| Übergangsfrist Kennzeichnung bis 2.12.2026, nur Anbieter von Altsystemen | [Art. 111 Abs. 4 KI-VO](https://ai-act-service-desk.ec.europa.eu/de/ai-act/article-111) (konsolidiert) |
| Hochrisiko-Pflichten ab 2.12.2027 (Anhang III) bzw. 2.8.2028 (Anhang I) | [Art. 113 lit. c KI-VO](https://ai-act-service-desk.ec.europa.eu/de/ai-act/article-113) (konsolidiert) |
