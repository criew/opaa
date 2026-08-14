# Messaging — die Quelle der Wahrheit

Dieses Dokument legt fest, wie über OPAA gesprochen wird. Jedes nach außen gerichtete Material leitet
sich daraus ab: die Landing-Page in `page/`, die Pitch-Unterlagen und der One-Pager in `docs/`, der
Einstieg in `README.md`. Weicht ein Asset von hier ab, ist das Asset falsch, nicht dieses Dokument.

Grundlage ist [VISION.md](../VISION.md); die Ausrichtung ist in
[ADR-0014](../decisions/0014-produktausrichtung-oeffentliche-verwaltung.md) entschieden. Das Messaging
erfindet nichts hinzu — es entscheidet nur, was davon zuerst gesagt wird und in welchen Worten.

---

## Positionierung in einem Satz

> OPAA ist die quelloffene KI-Plattform für die öffentliche Verwaltung: Sie macht das Wissen eines Hauses
> belegbar befragbar, lässt Agenten wiederkehrende Aufgaben übernehmen und verteilt beides über die
> ganze Organisation — im eigenen Rechenzentrum, ohne dass Daten das Haus verlassen.

Kurzform für Aufzählungen, Metadaten und Kacheln:

> Souveräne, quelloffene KI-Plattform für die öffentliche Verwaltung.

---

## Die zwei Botschaften, auf die alles zurückführt

Jedes Argument in jedem Material lässt sich einer der beiden zuordnen. Lässt es sich das nicht, gehört es
nicht ins Material.

### Belegbarkeit — „Sie können der Antwort trauen, weil Sie sie nachprüfen können."

Eine Auskunft in der Verwaltung ist keine Meinung. Jemand steht mit seinem Namen dafür gerade, und Jahre
später muss nachvollziehbar sein, worauf sie sich stützte. OPAA bindet jede Aussage an ihre Fundstelle und
lässt sich für haftungskritische Zusammenhänge so schalten, dass ohne Beleg keine Antwort ergeht.

**Formulierungen, die tragen:** „jede Aussage mit Fundstelle", „lieber ‚nicht feststellbar' als plausibel
klingend", „nachvollziehbar auch in drei Jahren".

### Verteilbarkeit — „KI kommt bei allen an, nicht nur bei den paar Leuten, die es können."

Das reale Problem ist nicht, ob es ein gutes Modell gibt, sondern wie das Können von wenigen zu allen
kommt. Ohne Antwort darauf entsteht Schatten-KI. OPAA macht Agenten, Prompts und Wissensbestände zu
benannten, teilbaren, versionierbaren Objekten, die über Freigabestufen von einer Person bis in die ganze
Organisation wandern.

**Formulierungen, die tragen:** „die gute Arbeitsweise einer Abteilung wird zum Standard aller",
„einmal geprüft, überall nutzbar", „Schwarmintelligenz mit Freigabe statt Wildwuchs".

---

## Nutzenversprechen je Stakeholder

Wer im Beschaffungs- und Einführungsprozess einer Behörde mitredet, redet aus einer eigenen Sorge heraus.
Diese Sorge wird zuerst adressiert, nicht die Funktionsliste.

| Rolle | Ihre Sorge | Was ihr zuerst gesagt wird |
|---|---|---|
| **Fachbereich, Sachbearbeitung** | „Ich habe keine Zeit, ein neues Werkzeug zu lernen, und ich kann keine Antwort übernehmen, die ich nicht prüfen kann." | Die Frage wird in Umgangssprache gestellt. Die Antwort nennt die Fundstelle, und Sie springen mit einem Klick dorthin. Was nicht belegbar ist, wird nicht behauptet. |
| **Fach- und Amtsleitung** | „Ich verantworte, was mein Bereich abgibt. Und ich soll KI einführen, ohne zu wissen, wie das in der Fläche ankommen soll." | Was Ihr Bereich an Arbeitsweise entwickelt, wird zum geprüften, freigegebenen Standard — nachvollziehbar, wer wann welche Fassung freigegeben hat. Sie sehen, wo die Einführung trägt und wo nicht. |
| **IT und Betrieb** | „Ich muss das betreiben, aktuell halten und gegenüber Prüfern erklären." | Betrieb im eigenen Rechenzentrum bis hin zu Installationen ohne Netzanbindung. Modelle sind austauschbar, ohne dass Fachbereiche ihre Agenten anfassen. Quelloffen und damit prüfbar statt zugesichert. |
| **Datenschutz und Informationssicherheit** | „Wo laufen die Daten hin, und was passiert bei einer Prüfung?" | Daten verlassen das Haus nicht. Die Rechteprüfung sitzt in der Suche, nicht dahinter. Beschränkungen hängen an den Daten und nicht am Arbeitsraum, sind also nicht durch einen Raumwechsel zu umgehen. Nachweise sind Teil des Produkts. |
| **Personalvertretung** | „Wird hier Leistung und Verhalten kontrolliert?" | Sichtbarkeit ist eine Handlung, keine Automatik. Der persönliche Bereich ist unbeobachtet. Einen personenbezogenen Auswertungspfad gibt es nicht — nicht abgeschaltet, sondern nicht gebaut. Keine Ranglisten. Die Dienstvereinbarung wird zur Konfigurationsaufgabe statt zum Projektrisiko. |
| **KI-Koordination, Digitalisierung** | „Ich soll KI steuern, kann aber nur zusehen, was die Leute sich zusammenbasteln." | Einmal zentral festlegen, welche Modelle erlaubt sind und welche Vorgaben gelten — alle erben es. Zentrale Änderungen wirken sofort überall. |
| **Beitragende, Open-Source-Umfeld** | „Ist das echt offen oder nur so genannt?" | AGPL-3.0, der vollständige Funktionsumfang ist quelloffen. Entwicklung im Offenen, mit nachvollziehbarer Änderungshistorie. |

---

## Was wir sagen — und wie

### Ansprache und Ton

- **„Sie", durchgehend.** Adressat ist eine Behörde, keine Startup-Belegschaft.
- **Nur Deutsch** für Landing-Page, Pitch und One-Pager. Die technische Dokumentation im Repository
  bleibt davon unberührt.
- **Sachlich statt begeistert.** Kein „revolutionär", „bahnbrechend", „einfach genial". Der Wert entsteht
  aus der Beschreibung der Situation, nicht aus dem Adjektiv davor.
- **Konkret statt abstrakt.** Nicht „steigert die Effizienz", sondern „die Fundstelle steht in der
  Antwort, statt zwanzig Minuten gesucht zu werden".
- **Ehrlich über den Stand.** OPAA ist im Aufbau. Was noch nicht gebaut ist, wird nicht so beschrieben,
  als wäre es fertig. Ein Zielbild darf ein Zielbild heißen.

### Begriffe

Diese Begriffe werden einheitlich verwendet; sie stammen aus [CONCEPTS.md](../CONCEPTS.md) und
[VISION.md](../VISION.md).

| Wir sagen | Nicht |
|---|---|
| OPAA | opaa, OPAA-System |
| Behörde, Haus, Verwaltung | Unternehmen, Firma, Kunde |
| Beschäftigte, Sachbearbeitung, Fachbereich | Mitarbeiter, User, Endanwender |
| Wissensbibliothek | Wissensbereich, Ordner, Datenraum |
| Space | Workspace, Projektraum |
| Asset (Agent, Prompt-Bibliothek, Wissensbibliothek) | Baustein, Modul |
| eigene, lokal betriebene Modelle | BYOM (im Fließtext), On-Device |
| Fundstelle, Beleg, Quellenangabe | Zitat-Feature, Citation |
| revisionssicheres Protokoll | Audit-Log, Logging |
| Betrieb ohne Netzanbindung | air-gapped (nur, wo der Fachbegriff erwartet wird) |

---

## Was wir nicht sagen

Diese Liste ist verbindlich. Sie gilt für jedes Material, das das Projekt verlässt, und für die
Dokumentation im Repository gleichermaßen.

**Keine Mitbewerber.** Keine Produktnamen, keine Vergleichstabellen mit benannten Anbietern, keine Sätze
des Musters „anders als X" oder „statt X". Eine Abgrenzung wird mit dem Sachgrund begründet — warum etwas
in einer Behörde nicht taugt, nicht wer es sonst anbietet. Ein generischer Vergleich mit einer
Produktkategorie („gehostete Dienste") ist zulässig, solange er keine Firma erkennbar macht.

Unzulässig ist damit die Nennung **zur Positionierung**: ein Name, der OPAA einordnet, besser aussehen
lässt, eine Marktlage beschreibt oder einen Kaufgrund liefert. Das gilt für jedes Material, das jemand
liest, um sich ein Bild von OPAA zu machen — Landing-Page, Pitch, One-Pager, Einstieg im Repository,
Vision.

Zulässig ist die Nennung als **nachprüfbarer Sachbeleg**. Stellt ein Dokument eine technische Behauptung
über ein fremdes System auf, die den eigenen Entwurf trägt, gehört der Name zum Beleg: Anonymisiert wäre
die Aussage nicht mehr nachprüfbar und damit wertlos. So belegt die Vorbild-Analyse in
[features/spaces-and-assets.md](../features/spaces-and-assets.md#wie-andere-systeme-container-und-geteiltes-objekt-zueinander-stellen),
dass die gewählte Rechtekonstruktion in keinem der untersuchten Systeme ein Vorbild hat — genau das
verhindert, dass ein entschiedener Punkt in einem halben Jahr als neue Idee wiederkommt. Ebenfalls
zulässig sind Namen als **Arbeitsanweisung an eine interne Rolle**, etwa als Startpunkt einer Recherche
in `agents/roles/`; das ist kein Text über OPAA. Namen von Bausteinen des eigenen Stacks, von Modellen
und von Quellsystemen, die OPAA anbindet, sind keine Mitbewerbernennung und von dieser Regel nicht
berührt.

Drei Fragen entscheiden den Einzelfall. Nur wenn alle drei zugunsten des Namens ausfallen, bleibt er:

1. **Wozu steht der Name da?** Um OPAA einzuordnen oder zu empfehlen — dann streichen. Um eine konkrete
   Behauptung über ein fremdes System nachprüfbar zu machen — dann bleibt er.
2. **Was passiert, wenn man ihn entfernt?** Bleibt die Aussage richtig und nachprüfbar, war der Name
   entbehrlich. Verliert sie ihre Nachprüfbarkeit, ist er Teil des Belegs.
3. **Wer liest die Stelle?** Steht sie in einem Dokument, das jemand liest, um OPAA zu bewerten oder zu
   beschaffen, ist der Name unzulässig — auch dann, wenn er sachlich zuträfe.

**Keine Beteiligten.** Keine Namen von Personen, Unternehmen, Beratungen oder Partnern; keine Aussagen
darüber, wer an OPAA mitarbeitet, es finanziert oder vertreibt.

**Keine Referenzen.** Keine Kundennamen, keine Pilotbehörden, keine Zitate von Anwendern, keine
Logo-Leisten — auch nicht anonymisiert in einer Form, die Rückschlüsse zulässt.

**Keine Zahlen zu Geld und Aufwand.** Keine Preise, keine Preisbänder, keine Lizenzmodelle, keine
Aufwands- oder Kostenschätzungen, keine Angaben zu Personentagen oder Projektdauern.

**Keine Zertifizierungsbehauptung.** OPAA ist nicht zertifiziert und wird es als Software auch nicht
werden. Gesagt wird, dass OPAA darauf ausgelegt ist, dass ein Betreiber die Prüfung mit OPAA im
Prüfumfang besteht. Formulierungen wie „C5-zertifiziert" oder „BSI-konform" sind falsch und werden nicht
verwendet.

**Keine Versprechen zu Terminen.** Keine Roadmap mit Daten, keine Zusage, wann eine Phase fertig ist.

**Kein Anspruch auf Vollständigkeit im Vergleich.** Sätze wie „das einzige Produkt, das …" oder
„als einziges …" werden nicht verwendet.

---

## Beweisführung — was ein Argument tragen darf

Behauptungen brauchen einen Beleg im Repository. Diese Zuordnung hält das Material ehrlich:

| Aussage | Belegt durch |
|---|---|
| Antworten sind an Fundstellen gebunden | `docs/features/data-indexing-rag.md` |
| Rechte werden zur Abfragezeit geprüft | `docs/features/spaces-and-assets.md` |
| Assets sind teilbar und versionierbar | `docs/features/spaces-and-assets.md` |
| Kein personenbezogener Auswertungspfad | `docs/features/spaces-and-assets.md`, Issue #239 |
| Betrieb im eigenen Haus | `docs/features/deployment-infrastructure.md`, `docs/deployment.md` |
| Quelloffen unter AGPL-3.0 | `LICENSE` |
| Suchqualität ist messbar | `docs/features/search-quality-evaluation.md` |

Was heute nur geplant ist, wird als geplant gekennzeichnet. Den Stand führt das Statusdokument im
Repository; Marketing-Material übernimmt ihn, statt einen eigenen zu behaupten.

---

## Assets, die sich hieraus ableiten

| Asset | Zweck | Umfang |
|---|---|---|
| `page/index.html` | Erster Kontakt im Netz, Einstieg für Interessierte und Beitragende | Kernbotschaft, die drei Säulen, Souveränität, Verweis auf Demo und Quellcode |
| `docs/onepager-de.html` | Eine Seite zum Weiterreichen im Haus | Problem, drei Säulen, Belegbarkeit und Verteilbarkeit, Souveränität, Phasenlage |
| `docs/OPAA-pitch-de.html` | Ausführlichere Vorstellung | Wie der One-Pager, plus Anwendungsfälle und Themenbereiche |
| `README.md` | Einstieg im Repository | Kurzfassung der Positionierung, dann technischer Einstieg |

Jedes dieser Assets nennt dieselbe Kernbotschaft in denselben Worten. Abweichende Formulierungen für
dieselbe Sache sind kein Stilmittel, sondern ein Fehler.
