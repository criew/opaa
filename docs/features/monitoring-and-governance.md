# Monitoring, Kosten & Governance

> **Status: Früher Entwurf — der Rahmen steht, Kennzahlen und Schwellen sind offen.**
>
> **Phasenlage:** Grenzen je Nutzer und die Kostentransparenz gehören zu Phase 1, weil ein Betrieb ohne
> Verbrauchsgrenzen nicht verantwortbar ist. Das Auswertungscockpit und der Export für Berichte gehören
> zu Phase 2; die Transparenz über den Fortschritt der KI-Einführung je Organisationseinheit entfaltet
> ihren Wert erst mit dem organisationsweiten Rollout in Phase 3.

> **Verbindliche Vorgabe:** Was ausgewertet werden darf, entscheidet
> [Sicherheit, Nachweis & Prüfbarkeit](./security-and-compliance.md#mitbestimmungsfähigkeit) — nicht
> dieses Dokument. Es gibt **keinen personenbezogenen Auswertungspfad**, und diese Festlegung gilt hier
> **ausnahmslos**. Jede Kennzahl in diesem Dokument ist aggregiert zu lesen, auch wo es nicht eigens
> dabeisteht.

## Motivation

Wer KI in einer Behörde einführt, muss drei Fragen beantworten können, und zwar gegenüber drei
verschiedenen Adressaten.

Der **Betrieb** fragt: Läuft das System, und wie stark ist es ausgelastet? Ohne Grenzen je Nutzer ist eine
einzelne Auswertung über einem großen Bestand in der Lage, die Antwortzeiten des ganzen Hauses zu
verderben.

Die **Haushaltsstelle** fragt: Was kostet das, und woher kommen die Kosten? Bei lokal betriebenen Modellen
sind es Rechenkapazität und Strom, bei freigegebenen externen Modellen ein Betrag je Anfrage. In beiden
Fällen muss der Verbrauch einer Organisationseinheit zurechenbar sein, sonst ist die Fortschreibung im
nächsten Haushalt eine Schätzung ins Blaue.

Die **Leitung** fragt: Kommt die KI-Fähigkeit tatsächlich im Haus an? Das ist die Frage, die das
Leitprinzip der Verteilbarkeit messbar macht. Eine Einführung, bei der drei Referate alles nutzen und
zwanzig gar nichts, ist keine erfolgreiche Einführung — aber ohne Zahlen fällt das erst auf, wenn jemand
zufällig danach fragt.

Alle drei Fragen sind **aggregiert beantwortbar**. Keine von ihnen verlangt zu wissen, was eine bestimmte
Person getan hat. Genau darauf ist dieses Kapitel gebaut: Es liefert Steuerungswissen über
Organisationseinheiten und Assets, nicht über Beschäftigte.

---

## Überblick

1. **Grenzen je Nutzer** — technisch durchgesetzt, dem Betroffenen selbst angezeigt.
2. **Kostentransparenz** — Verbrauch je Sitzung, je Asset, je Organisationseinheit; keine Zuordnung zur
   Person.
3. **Auswertungscockpit** — Nutzung, Auslastung und Verläufe für Betrieb und Leitung.
4. **Fortschritt der KI-Einführung** — welche Bereiche nutzen KI wie stark, welche Assets tragen, wo ist
   die Verbreitung schwach.
5. **Export für Berichte** — damit Zahlen in Haushalts-, Jahres- und Gremienberichte gelangen, ohne dass
   jemand sie abtippt.
6. **Die Grenze** — was es bewusst nicht gibt, und warum das keine Lücke ist.

---

## Grenzen je Nutzer

Eine Grenze ist kein Auswertungsinstrument. Sie wird **durchgesetzt**, nicht beobachtet, und ihr Ergebnis
geht an die betroffene Person selbst.

```
Grenzen (organisationsweite Voreinstellung, je Gruppe überschreibbar):
  Anfragen je Zeitraum
  Verbrauch an Modellkapazität je Zeitraum
  gleichzeitig laufende Agenten- und Auswertungsläufe
  Größe und Anzahl von Uploads
  belegter Speicher der persönlichen Bestände
```

- **Sichtbar für die betroffene Person.** Wer sich einer Grenze nähert, sieht das rechtzeitig, mit dem
  verbleibenden Rest — nicht erst durch eine Ablehnung mitten in der Arbeit.
- **Nicht sichtbar für andere.** Der Verbrauchsstand einer Person ist für Vorgesetzte, Systemverwaltung
  und Leitung nicht abfragbar. Eine Grenze braucht keinen Auswertungspfad: Das System setzt sie durch und
  sagt es der betroffenen Person.
- **Für den Betrieb aggregiert.** Dass Grenzen im Haus insgesamt oder in einer Organisationseinheit
  häufig greifen, ist eine Betriebsinformation und wird aggregiert angezeigt — als Anlass, die Grenze zu
  überprüfen, nicht als Anlass, jemanden anzusprechen.
- **Anhebung ist ein Verwaltungsakt** mit Protokolleintrag, nicht eine stille Einstellungsänderung.
- **Grenzen greifen auch für API-Tokens und Service-Accounts** — sonst wäre die Schnittstelle der Weg an
  ihnen vorbei.

Die Voreinstellung ist bewusst so gewählt, dass die alltägliche Arbeit sie nicht berührt. Eine Grenze,
gegen die regelmäßig jemand läuft, erzieht nur dazu, sie zu umgehen.

---

## Kostentransparenz

Die Frage lautet nicht „wer verbraucht", sondern **„wo entsteht"**. Kosten werden deshalb entlang der
Struktur des Systems zugeordnet, nicht entlang der Personen:

| Bezugsgröße | Wofür sie taugt |
|---|---|
| **je Sitzung** | die Größenordnung eines typischen Vorgangs — Grundlage jeder Hochrechnung |
| **je Asset** | ob ein Agent teuer ist, weil er wertvoll ist, oder weil er schlecht geschnitten ist |
| **je Wissensbibliothek** | was Indizierung und Aktualisierung eines Bestands laufend kosten |
| **je Modell** | die Verteilung zwischen lokalen und freigegebenen externen Modellen |
| **je Organisationseinheit** | die Zurechnung für Haushalt und Fortschreibung, oberhalb der Mindestgruppengröße |

Erfasst wird der Verbrauch in Token und Rechenzeit sowie, wo ein externes Modell freigegeben ist, der
daraus folgende Betrag nach den vom Betreiber hinterlegten Sätzen. Das Produkt hinterlegt keine Preise; es
rechnet mit denen, die der Betreiber einträgt.

**Zwei Ableitungen, die den Nutzen ausmachen:**

- Ein **Kostenvergleich zwischen Modellen bei gleicher Aufgabe** macht die Modellvorgabe der
  Systemverwaltung zu einer belegten Entscheidung statt zu einer Vermutung.
- Eine **Vorschau vor teuren Läufen** — etwa einer Deep-Research-Anfrage über einen großen Bestand — nennt
  die zu erwartende Größenordnung, bevor der Lauf startet.

Kostendaten unterliegen derselben Aufbewahrungsfrist und derselben Zweckbindung wie alle übrigen
Kennzahlen. Eine Kostenauswertung, die sich auf einzelne Beschäftigte herunterbrechen ließe, wäre ein
Auswertungspfad unter anderem Namen und gibt es deshalb nicht.

---

## Auswertungscockpit

Eine Betriebs- und Steuerungsansicht für Systemverwaltung und Leitung, in getrennten Zuschnitten:

```
┌─ Betrieb ──────────────────────────────────────────────────┐
│ Anfragen je Stunde · Antwortzeiten (Median, 95. Perzentil) │
│ Auslastung der Modelle · Warteschlangen · Fehlerquote      │
│ Indizierungsläufe: Dauer, Umfang, Fehlschläge              │
│ Wie oft Grenzen gegriffen haben (aggregiert)               │
└────────────────────────────────────────────────────────────┘
┌─ Nutzung ──────────────────────────────────────────────────┐
│ Aktive Nutzung je Organisationseinheit (ab Mindestgröße)   │
│ Meistgenutzte Assets · Suchanfragen ohne Treffer           │
│ Anteil der Antworten mit belegter Quelle                   │
│ Verläufe über Wochen und Monate                            │
└────────────────────────────────────────────────────────────┘
```

Drei Festlegungen dazu:

- **Die kleinste auswertbare Einheit ist die Organisationseinheit**, nicht der Mensch. Unterhalb der
  Mindestgruppengröße wird der Wert **unterdrückt statt angezeigt** — nicht gerundet, nicht „anonymisiert
  dargestellt", sondern nicht ausgegeben. Auch nicht dann, wenn er sich aus zwei anderen Werten
  errechnen ließe; Aggregate, deren Differenz eine einzelne Person freilegt, werden ebenfalls
  unterdrückt.
- **Betriebskennzahlen sind keine Nutzungskennzahlen.** Antwortzeiten, Fehlerquoten und Auslastung sind
  Eigenschaften des Systems und in dieser Sicht ohne Bezug zu Personen oder Einheiten. Sie sind der Teil
  des Cockpits, der ohne mitbestimmungsrechtliche Fragen auskommt.
- **Nutzungsstatistiken sind vollständig abschaltbar**, ohne dass Suche, Chat oder Assets darunter leiden.
  Das ist eine Zusage an die Dienstvereinbarung: Ein Haus, das gar keine Nutzungsauswertung will, bekommt
  ein voll funktionsfähiges Produkt.

Die Ansicht für die Leitung enthält keine Angabe, die die Ansicht für die Revision nicht enthalten dürfte,
und umgekehrt — die Trennung der Zugriffswege aus
[Sicherheit, Nachweis & Prüfbarkeit](./security-and-compliance.md) ist hier durchgesetzt und nicht nur
gestaltet.

---

## Fortschritt der KI-Einführung

Das ist der Teil, der über den Betrieb hinausgeht und dem Leitprinzip der **Verteilbarkeit** eine Zahl
gibt. Er beantwortet vier Fragen:

1. **Welche Bereiche nutzen KI wie stark?** Nutzungsintensität je Organisationseinheit im Verlauf. Nicht
   um Einheiten zu vergleichen, sondern um zu sehen, wo die Einführung angekommen ist und wo nicht.
2. **Welche Assets sind erfolgreich?** Welche Agenten, Prompt-Bibliotheken und Wissensbibliotheken
   tatsächlich genutzt werden — und welche im Katalog stehen, ohne dass sie jemand aufruft. Ein Asset ist
   ein Objekt und keine Person; hier ist die Auswertung uneingeschränkt möglich und ausdrücklich
   erwünscht.
3. **Wo ist die Verbreitung schwach?** Einheiten mit auffällig geringer Nutzung sind der Anlass für
   Kuratierung und Begleitung — nicht für Nachfragen an einzelne Beschäftigte. Eine schwache Verbreitung
   ist in aller Regel ein Befund über das Angebot, nicht über die Leute.
4. **Was fehlt?** Suchanfragen ohne brauchbaren Treffer, wiederkehrende Themen ohne passendes Asset,
   Bestände mit hoher Nachfrage und schlechter Abdeckung. Das ist der wertvollste Teil, weil er sagt,
   welches Asset als Nächstes entstehen sollte.

**Warum das keine Leistungsüberwachung ist:** Die Auswertung richtet sich auf das **Angebot** — Assets,
Bestände, Kuratierung — und auf **Einheiten oberhalb der Mindestgruppengröße**. Sie stellt keine Frage,
deren Antwort ein Name wäre. Ein Referat, das den Assistenten wenig nutzt, erzeugt einen Befund über die
Einführung, nicht über seine Beschäftigten; die Folge ist Begleitung, nicht Ansprache.

Die Kehrseite gehört an dieselbe Stelle: Ob das Modell trägt, wird damit nur **grob und spät** erkennbar.
Das ist ein bewusst gezahlter Preis. Genauer zu messen hieße, die Zusage zu brechen, die das Konzept
gegenüber der Personalvertretung überhaupt erst tragfähig macht.

---

## Export für Berichte

Zahlen, die nur in einer Oberfläche stehen, landen nicht im Bericht — sie werden abgetippt, und dabei
entstehen Fehler und Auslegungen.

- **Maschinenlesbarer Export** aller Cockpit-Kennzahlen für einen wählbaren Zeitraum, in offenen Formaten.
- **Wiederkehrende Berichte** zu festen Stichtagen, an einen benannten Empfängerkreis, mit
  festgeschriebenem Umfang — damit nicht jede Auswertung einzeln beauftragt werden muss.
- **Der Export ist keine Umgehung.** Alles, was im Cockpit unterdrückt wird, fehlt auch im Export. Ein
  Export, der feiner auflöst als die Ansicht, wäre die Hintertür in genau den Pfad, den es nicht gibt.
- **Jede exportierte Kennzahl trägt ihren dokumentierten Zweck.** Damit ist der Auszug für die
  Personalvertretung derselbe Datensatz wie der für die Leitung — nur mit der Zweckdokumentation als
  Beiblatt.
- **Kostenexport für die Haushaltsstelle** mit der Zurechnung je Organisationseinheit; die
  Verrechnungssätze setzt der Betreiber.

---

## Die Grenze: was es bewusst nicht gibt

| Nicht vorhanden | Begründung |
|---|---|
| Auswertung nach Person, in Oberfläche, Schnittstelle oder Export | Nicht abgeschaltet, sondern nicht gebaut. Die Festlegung steht in [Sicherheit, Nachweis & Prüfbarkeit](./security-and-compliance.md#2-einen-personenbezogenen-auswertungspfad-gibt-es-nicht) und ist hier ohne Ausnahme durchgesetzt |
| Ranglisten, Bestenlisten, Aktivitätsbewertungen | Auch nicht als spielerisches Element. Der spielerische Rahmen ändert die Datengrundlage nicht |
| Anzeige, wann jemand zuletzt aktiv war | Ein Anwesenheitsmerkmal, unabhängig davon, wie beiläufig es gestaltet ist |
| Zähler über Entwürfe oder Arbeitsstände anderer | Entwürfe sind unbeobachtet; ein Zähler darüber wäre eine Beobachtung |
| Werte unterhalb der Mindestgruppengröße | Werden unterdrückt, nicht angezeigt — einschließlich der Werte, die sich aus anderen errechnen ließen |
| Die Netzadresse als Auswertungsmerkmal | Sie ist nicht Teil des Standard-Protokollsatzes und in Berichten und Exporten ausgeschlossen |

Diese Tabelle ist die Übersetzung der Mitbestimmungszusagen in die Sprache der Auswertung. Sie enthält
nichts, was in [Sicherheit, Nachweis & Prüfbarkeit](./security-and-compliance.md) nicht schon steht — im
Zweifel gilt dort die Fassung, nicht hier.

---

## Integrationspunkte

- **Sicherheit und Nachweis:** liefert die verbindliche Grenze dieses Dokuments und die Protokollpflicht
  für Änderungen an Governance-Einstellungen →
  [security-and-compliance.md](./security-and-compliance.md)
- **Identität und Mandanten:** Organisationseinheiten und Gruppen aus dem Verzeichnisdienst sind die
  Aggregationsachse → [access-control.md](./access-control.md)
- **Spaces und Assets:** Assets sind die zweite Auswertungsachse; die Mindestgruppengröße und die
  Abschaltbarkeit stammen aus dem dortigen Mitbestimmungskapitel →
  [spaces-and-assets.md](./spaces-and-assets.md)
- **Modelle:** Verbrauchs- und Kostendaten je Modell tragen die zentrale Modellvorgabe →
  [llm-integration.md](./llm-integration.md)
- **Suchqualität:** Treffer- und Belegquoten sind die fachliche Ergänzung der Nutzungszahlen →
  [search-quality-evaluation.md](./search-quality-evaluation.md)
- **Betrieb:** Betriebskennzahlen gehören in die vorhandene Überwachung des Rechenzentrums →
  [deployment-infrastructure.md](./deployment-infrastructure.md)

---

## Offene Fragen

- **Höhe der Mindestgruppengröße** als Voreinstellung und als erzwungene Untergrenze. Sie folgt aus dem
  tatsächlichen Zuschnitt der Einheiten und gehört in die Dienstvereinbarung — das Produkt muss aber
  einen verteidigungsfähigen Ausgangswert setzen.
- Wie wird verhindert, dass sich ein unterdrückter Wert aus mehreren zulässigen Aggregaten rekonstruieren
  lässt? Der Grundsatz steht, das Verfahren nicht.
- Welche Kennzahlen gehören in die erste Ausbaustufe? Die Liste hier ist ein Zielbild, kein Schnitt.
- Wie werden Beschäftigte abgebildet, die zwei Organisationseinheiten angehören, ohne doppelt zu zählen?
- Zurechnung der Kosten lokal betriebener Modelle: Rechenzeit ist messbar, ihre Umrechnung in einen Betrag
  ist eine Festlegung des Betreibers. Welche Bezugsgröße liefert das Produkt dafür?
- Sollen Rückmeldungen der Nutzenden zu Antworten (hilfreich / nicht hilfreich) in die Auswertung
  eingehen? Sie sind fachlich wertvoll und zugleich die naheliegendste Stelle, an der wieder ein
  Personenbezug entstehen könnte.

---

## Erfolgs-Metriken

- **Verbreitung:** Der Anteil der Organisationseinheiten mit regelmäßiger Nutzung steigt über die ersten
  zwölf Monate — das ist die eigentliche Zielgröße des Leitprinzips der Verteilbarkeit.
- **Steuerbarkeit:** Kuratierungsentscheidungen — welches Asset entsteht, welches wird zurückgezogen —
  lassen sich auf eine Kennzahl aus dem Cockpit zurückführen statt auf einen Eindruck.
- **Kostenklarheit:** Die Fortschreibung für den nächsten Haushalt beruht auf gemessenem Verbrauch.
- **Unauffälligkeit der Grenzen:** Nur ein sehr kleiner Anteil der alltäglichen Vorgänge läuft in eine
  Grenze.
- **Belastbarkeit der Zusage:** Ein Test gegen alle Auswertungs- und Exportwege belegt, dass keiner nach
  Person auflöst und keiner unterhalb der Mindestgruppengröße ausgibt.

---

## Verwandte Dokumente

- [Sicherheit, Nachweis & Prüfbarkeit](./security-and-compliance.md) — die verbindliche Grenze
- [Spaces, Assets & Zugangskontrolle](./spaces-and-assets.md) — Mitbestimmung und Stellschrauben
- [Identität, Rechte & Mandanten](./access-control.md) — Organisationseinheiten und Grenzen je Nutzer
- [Produktvision](../VISION.md) — Einordnung in die Themenbereiche und Phasen
