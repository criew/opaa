# Verwaltungs-Spezifika

> **Status: Entwurf.** Themenbereich K der Produktvision. Phasenlage: Leichte Sprache und
> Amtssprache als Textwerkzeug sowie die Revisionssicherheit gehören in **Phase 1**;
> Barrierefreiheit nach BITV und der Feinschliff der Amtssprache in **Phase 3**; die Anbindung an
> elektronische Akte und Dokumentenmanagement in **Phase 4** und nur projektgetrieben. Der Assistent
> für Bürgerinnen und Bürger und ein öffentlich eingebettetes Widget sind Ausblick; siehe
> [#357](https://github.com/criew/opaa/issues/357).

## Motivation

Die meisten Anforderungen an OPAA gelten für jede Organisation. Die in diesem Dokument beschriebenen
gelten nur in der öffentlichen Verwaltung — und sie sind dort in vielen Fällen keine Frage des
Komforts, sondern der Rechtslage.

Das verschiebt den Maßstab. Eine Funktion, die anderswo als „nice to have" geführt würde, entscheidet
hier darüber, ob eine Einführung überhaupt zulässig ist. Wer Leichte Sprache als Zusatzwerkzeug baut,
Barrierefreiheit als Abschlussarbeit und Protokollierung als Betriebsdetail, wird an diesen Punkten
scheitern — nicht an der Qualität der Antworten.

Dieses Dokument fasst zusammen, was die Zielgruppe von einem allgemeinen Wissenswerkzeug
unterscheidet.

---

## Überblick

1. **Leichte Sprache und Amtssprache sind ein Werkzeugpaar in beide Richtungen** — vereinfachen und
   in die Fachsprache zurückführen. Beide Richtungen werden gebraucht.
2. **Barrierefreiheit nach BITV ist eine Eigenschaft der Oberfläche**, keine nachgelagerte Prüfung.
3. **Revisionssicherheit entsteht aus der Kopplung zweier Dinge**: dem Protokoll darüber, was geschah,
   und der Belegpflicht darüber, worauf eine Aussage beruhte. Einzeln trägt keines von beiden.
4. **Die Anbindung an elektronische Akte und Dokumentenmanagement ist eine projektgetriebene Option**
   — sie wird gebaut, wenn ein konkretes Einführungsvorhaben sie trägt, sonst nicht.
5. **Der Bürger-Scope ist Ausblick.** Er wird festgehalten, damit er Entscheidungen der ersten Phase
   nicht unbemerkt beeinflusst — nicht, um ihn einzuplanen.

---

## Leichte Sprache und Amtssprache

### Warum das keine Komfortfunktion ist

Behörden sind verpflichtet, Menschen mit Behinderungen Erläuterungen in Leichter Sprache
bereitzustellen und Bescheide auf Verlangen barrierefrei zu erläutern; das
Behindertengleichstellungsrecht des Bundes und die entsprechenden Landesregelungen halten das
ausdrücklich fest. Hinzu kommt die allgemeine Pflicht zur verständlichen Auskunft und Beratung.
Die Anforderung ist damit rechtlich gesetzt — offen ist nur, wie aufwendig ihre Erfüllung ist.

Genau dort liegt der Nutzen. Eine Übertragung in Leichte Sprache ist heute Handarbeit und wird deshalb
selten gemacht, obwohl sie geschuldet ist. Ein Werkzeug, das einen Entwurf in Sekunden liefert,
verändert nicht die Pflicht, sondern die Wahrscheinlichkeit, dass sie eingelöst wird.

### Beide Richtungen

| Richtung | Zweck | Typische Nutzung |
|---|---|---|
| **Amtssprache → Leichte Sprache** | Bescheide, Merkblätter und Formulare verständlich machen | Bürgeranschreiben, Erläuterungen, Aushänge, Internetauftritt |
| **Alltagssprache → Amtssprache** | eine formlose Notiz in eine tragfähige Formulierung überführen | Vermerk, Stellungnahme, Zuarbeit, Antwortentwurf |

Die zweite Richtung wird leicht übersehen, ist im Alltag aber die häufigere: Sachbearbeitung weiß, was
sie sagen will, und ringt mit der Form. Auch hier gilt die Belegbarkeit — eine Formulierungshilfe darf
keine Rechtsfolge hinzuerfinden, die im Ausgangstext nicht steht.

### Grenzen, die benannt gehören

- **Der Entwurf bleibt ein Entwurf.** Die fachliche Verantwortung für einen Text liegt bei der
  Person, die ihn zeichnet. Eine Übertragung in Leichte Sprache ist eine Zuarbeit, keine Freigabe.
- **Leichte Sprache ist ein Regelwerk, kein Stil.** Ergebnisse sind an den einschlägigen Regeln zu
  prüfen; wo ein Haus eine Prüfung durch die Zielgruppe vorsieht, ersetzt das Werkzeug sie nicht.
- **Vereinfachung kann Bedeutung verändern.** Wo ein Begriff eine Rechtsfolge trägt, ist ein Ersatz
  gefährlich. Solche Stellen sind zu kennzeichnen, statt sie stillschweigend aufzulösen.
- **Kein Persönlichkeitsprofil.** Ein Register — Amtssprache, Leichte Sprache, Bürgeranschreiben — ist
  eine Eigenschaft des Texts, nicht eine Charakterbeschreibung des Systems.

---

## Barrierefreiheit

Die Barrierefreie-Informationstechnik-Verordnung (BITV) verpflichtet öffentliche Stellen, ihre
Anwendungen barrierefrei bereitzustellen; sie verweist auf die europäische Norm für barrierefreie IKT
und damit auf die Erfolgskriterien der Stufe AA. Für eine Anwendung, die in einer Behörde am
Arbeitsplatz eingesetzt wird, ist das keine Empfehlung.

**Was das für OPAA bedeutet:**

- **Vollständige Bedienbarkeit über die Tastatur**, mit erkennbarem Fokus und ohne Fallen, in denen
  der Fokus stecken bleibt.
- **Verwendbarkeit mit Hilfsmitteln**: sinnvolle Beschriftungen, korrekte Rollen und Zustände,
  Ankündigung dynamischer Änderungen — insbesondere bei Antworten, die schrittweise erscheinen.
- **Wahrnehmbarkeit**: ausreichende Kontraste, Vergrößerbarkeit, Verzicht auf Farbe als einziges
  Unterscheidungsmerkmal, verzichtbare Bewegung.
- **Verständlichkeit**: klare Fehlermeldungen, die sagen, was zu tun ist, und deutsche Bezeichnungen
  in der Oberfläche.
- **Erklärung zur Barrierefreiheit**: Wer OPAA betreibt, muss den Stand erklären können. Das Produkt
  liefert dafür die Grundlage — eine geprüfte Aussage über die Anwendung, nicht eine Behauptung.

Zwei Punkte sind eigen an einem KI-Assistenten und verdienen besondere Beachtung: die **schrittweise
erscheinende Antwort**, die für ein Vorleseprogramm sonst zu einem Strom unzusammenhängender
Ankündigungen wird, und die **Darstellung von Fundstellen**, die ihren Zweck verliert, wenn der Sprung
zur Quelle nur mit der Maus möglich ist.

Die Umsetzung ist eine Eigenschaft der Web-Oberfläche (siehe
[user-frontends.md](./user-frontends.md)); die Anforderung steht hier, weil sie aus dem
Verwaltungskontext stammt und nicht aus der Oberfläche selbst.

---

## Revisionssicherheit

Revisionssicherheit ist in der Verwaltung kein Protokoll, das man aktiviert. Sie ist die Fähigkeit,
Jahre später zu beantworten, **was** entschieden wurde, **worauf** es sich stützte und **wer** dafür
einstand. Ein KI-Assistent macht diese Frage schwieriger, weil zwischen Quelle und Ergebnis ein
Schritt liegt, den niemand von Hand nachvollzieht.

OPAA beantwortet das über die Kopplung zweier Mechanismen, die einzeln nicht ausreichen:

| Mechanismus | Beantwortet | Alleine unzureichend, weil |
|---|---|---|
| **Revisionssicheres Protokoll** | Wer hat wann was getan? Welcher Agent, welche Fassung, welche Aktion? | Es sagt nichts darüber, ob das Ergebnis in den Quellen gedeckt war |
| **Belegpflicht** (Zitierzwang, Fundstellen, Konfidenz) | Worauf beruhte eine Aussage? | Ein Beleg ohne Protokoll lässt sich nachträglich nicht mehr einer Handlung zuordnen |

Erst zusammen ergeben sie die geschuldete Nachvollziehbarkeit: Zu einer protokollierten Handlung
gehören die Fundstellen, auf denen sie beruhte, und zu einer Fundstelle gehört die Handlung, in der
sie verwendet wurde.

**Daraus folgende Anforderungen:**

- **Nachträglich unveränderbare Einträge** mit gesicherter Zeitangabe; Zugriffe auf Protokolldaten
  erzeugen selbst einen Eintrag.
- **Aufbewahrung mit Ober- und Untergrenze.** Eine Untergrenze, weil sonst die Nachvollziehbarkeit
  entfällt; eine Obergrenze, weil eine unbegrenzte Speicherung datenschutzrechtlich nicht zu
  rechtfertigen ist und der Mitbestimmung entgegensteht.
- **Zuordenbarkeit der Fassung.** Bei einer Auskunft muss erkennbar bleiben, welche Fassung eines
  Agenten, welche Modellvorgabe und welcher Wissensstand zugrunde lagen. Eine spätere Verbesserung
  darf die Beurteilung eines alten Vorgangs nicht rückwirkend verändern.
- **Getrennte Zugriffswege** für Revision und Dienststellenleitung, technisch durchgesetzt.
- **Auswertbarkeit ohne Personenbezug.** Nachweise werden je Organisationseinheit geführt; einen
  personenbezogenen Auswertungspfad gibt es nicht (siehe
  [spaces-and-assets.md](./spaces-and-assets.md)).

Die Umsetzung im Rechtemodell und im Protokoll ist in [access-control.md](./access-control.md)
beschrieben; die Aufbewahrung im Betrieb in
[deployment-infrastructure.md](./deployment-infrastructure.md).

---

## Elektronische Akte und Dokumentenmanagement

In vielen Häusern liegt der eigentliche Wissensschatz nicht in Dateiablagen, sondern in der
elektronischen Akte und im Dokumentenmanagement. Eine Anbindung dorthin ist deshalb naheliegend — und
zugleich der aufwendigste Konnektor überhaupt.

**Warum das eine Option bleibt und keine Zusage:**

- **Der Markt ist zersplittert.** Es gibt mehrere verbreitete Systeme, jedes mit eigener
  Schnittstelle, eigenem Rechtemodell und eigener Aktenlogik. Eine Anbindung ist je System eine eigene
  Entwicklung; es gibt keinen gemeinsamen Weg, der alle abdeckt.
- **Die Aktenlogik ist keine Ordnerstruktur.** Aktenzeichen, Vorgang, Dokument, Fassung, Zeichnung und
  Aufbewahrungsfrist tragen fachliche Bedeutung, die beim Indizieren nicht verlorengehen darf. Wer sie
  auf Dateien reduziert, erzeugt Treffer ohne Zusammenhang.
- **Die Rechte sind fein und verbindlich.** Aktenrechte sind kein Vorschlag. Sie müssen gespiegelt
  werden, und zwar so, dass eine Rechteänderung im Quellsystem sofort wirkt — sonst entsteht über die
  Suche ein Zugang, den die Akte gerade verweigert.
- **Schreiben ist ein eigenes Thema.** Etwas zur Akte zu geben ist ein Verwaltungsakt mit
  Formvorschriften, nicht das Ablegen einer Datei. Lesender Zugriff und schreibender Zugriff sind
  daher getrennt zu betrachten.

**Die Konsequenz:** Es wird kein Konnektor auf Vorrat gebaut. Sobald ein konkretes
Einführungsvorhaben ein Zielsystem trägt, wird dieses eine angebunden — mit dem Aufwand, den es
verdient, statt mit einer oberflächlichen Unterstützung für viele. Bis dahin bleibt hier ein
bewusster Platzhalter.

---

## Ausblick: Assistent für Bürgerinnen und Bürger

Ein Assistent, der sich an Bürgerinnen und Bürger richtet, und ein öffentlich in eine Webseite
eingebettetes Widget sind **Ausblick, nicht Fundament**. Der primäre Nutzerkreis von OPAA ist die
interne Verwaltung.

Der Unterschied ist nicht die Oberfläche, sondern alles dahinter: ein anonymer statt eines
angemeldeten Gegenübers, eine Außenwirkung statt einer internen Zuarbeit, eine Auskunft, die als
Aussage der Behörde gelesen wird, und ein Missbrauchsrisiko, das im Innenverhältnis nicht besteht.

Festgehalten wird das hier aus einem einzigen Grund: **damit Entscheidungen der ersten Phase diesen
Weg nicht unbemerkt verbauen.** Welche das sind — Rechtemodell, Mandantentrennung, Umgang mit
anonymem Zugriff, Barrierefreiheit — und welche davon billig offenzuhalten sind, wird in
[#357](https://github.com/criew/opaa/issues/357) geklärt. Hier wird nichts davon entschieden und
nichts eingeplant.

---

## Integrationspunkte

- **[user-frontends.md](./user-frontends.md)** — die Web-Oberfläche, an der sich die Barrierefreiheit
  entscheidet
- **[access-control.md](./access-control.md)** — Identität, Rechte und das revisionssichere Protokoll
- **[spaces-and-assets.md](./spaces-and-assets.md)** — Mitbestimmungsfähigkeit, Aufbewahrung und die
  Abwesenheit eines personenbezogenen Auswertungspfads
- **[data-indexing-rag.md](./data-indexing-rag.md)** — Belegpflicht und Fundstellen, ohne die
  Revisionssicherheit nicht trägt
- **[deployment-infrastructure.md](./deployment-infrastructure.md)** — Aufbewahrung, Löschung und
  Nachweisführung im Betrieb
- **[VISION.md](../VISION.md)** — Einordnung der Themenbereiche und der Phasen

---

## Offene Fragen / Zukünftige Erweiterungen

- Wie wird eine Übertragung in Leichte Sprache gegen ihr Regelwerk geprüft — durch das Modell selbst,
  durch eine Prüfliste in der Oberfläche oder gar nicht automatisiert?
- Wie werden Begriffe gekennzeichnet, deren Vereinfachung eine Rechtsfolge verändern würde?
- Auf welchem Weg wird die Barrierefreiheit nachgewiesen — durch eine fortlaufende automatisierte
  Prüfung, durch eine externe Begutachtung oder durch beides?
- Welche Aufbewahrungsfristen sind voreingestellt, und welche muss ein Haus zwingend selbst festlegen?
- Welches Zielsystem für die elektronische Akte wird zuerst angebunden, falls ein Vorhaben es trägt?
- Bürger-Scope: welche Entscheidungen der ersten Phase sind billig offenzuhalten? Siehe
  [#357](https://github.com/criew/opaa/issues/357).

---

## Erfolgs-Metriken

- Eine Übertragung in Leichte Sprache entsteht in einem Arbeitsschritt statt in einem eigenen
  Vorgang — und wird deshalb tatsächlich gemacht.
- Die Web-Oberfläche ist ohne Maus vollständig bedienbar, einschließlich des Sprungs von einer
  Antwort zu ihrer Fundstelle.
- Zu einer beliebigen protokollierten Auskunft lassen sich Jahre später die zugrunde liegenden
  Fundstellen und die verwendete Fassung des Agenten benennen.
- Eine Erklärung zur Barrierefreiheit lässt sich vom Betreiber auf einer geprüften Grundlage
  abgeben, nicht auf einer Annahme.
