# ADR-0008: Space- und Asset-Modell

## Status

Akzeptiert

## Kontext

Das bisherige Workspace-Modell macht den Workspace zu drei Dingen gleichzeitig: Dokumentencontainer, Rechtegrenze und Ordnungsrahmen. Daraus folgen drei Probleme:

1. **KI-Assets sind nicht teilbar.** Ein guter Agent oder eine kuratierte Wissenssammlung gehört dem Workspace. Man kann sie nicht weitergeben, ohne den ganzen Workspace zu teilen oder sie zu kopieren. Die Produktvision verlangt aber ausdrücklich, dass Agenten, Prompt-Bibliotheken und Wissensbereiche erstklassige, teilbare, exportierbare Objekte sind — das ist eines der beiden Leitprinzipien.
2. **Cross-Workspace-Sharing bleibt ungelöst.** Der bisherige Ansatz (Dokumente über Workspace-Grenzen teilen, siehe `document-sharing.md`) hat bekannte Sicherheitslücken und wurde nie umgesetzt. Direktes Teilen zwischen persönlichen Ablagen war strukturell unmöglich.
3. **Der Rechteanker fehlt in der Implementierung überhaupt.** `Document` trägt keine Workspace-Zuordnung, die Liquibase-Changelogs kennen keine solche Spalte, und `QueryService` filtert nicht. Die in allen Konzeptdokumenten zentrale rechtebewusste Vektorsuche existierte bisher nur auf dem Papier.

### Vergleich: Wie andere Systeme Container und geteiltes Objekt verhältnismäßig zueinander stellen

Die entscheidende Frage ist, was passiert, wenn ein Objekt in mehreren Containern mit unterschiedlichen Rechten liegt. Vier Systeme wurden untersucht:

| System | Container | Objekt in mehreren Containern? | Rechteanker |
|---|---|---|---|
| **Confluence** | Space | Nein — eine Seite liegt in genau einem Space | Space-Permission ist der Boden; Page-Restriction kann nur **einschränken**, nie erweitern |
| **Notion** | Teamspace / Parent-Page | Nein — genau ein Parent; Verschieben ändert Rechte, Duplizieren erzeugt eine Kopie | Parent vererbt, Objekt kann überschreiben; Einladungen sind additiv |
| **Langdock** | — | Container ist **nicht rechtetragend** | Assistants und Knowledge Folders tragen eigene Freigabelisten; Ordner sind Bibliotheksordnung, keine Rechteträger |
| **Glean** | Collections | Ja, aber ohne Rechtewirkung | Per-Dokument-Rechte aus dem Quellsystem, zur Abfragezeit geprüft; Collections sind reine Kuratierung |

**Kein einziges dieser Systeme kennt „Objekt liegt in mehreren rechtetragenden Containern".** Entweder es gibt genau einen rechtetragenden Container (Confluence, Notion) oder gar keinen (Langdock, Glean). Die naheliegende Kombination — mehrere Container, jeder rechtetragend — hat kein Vorbild und erzeugt Fragen, die niemand sonst beantworten musste: Welcher Container gewinnt bei widersprüchlichen Rechten? Darf jemand durch Hinzufügen zu einem Container Rechte verteilen, die er selbst nicht vergeben dürfte?

Wir weichen **von beiden Mustern ab**, aber nicht in derselben Sache — wir wenden beide an, auf verschiedene Objektklassen:

- Für **Assets** übernehmen wir das Langdock/Glean-Muster: Der Container trägt keine Rechte.
- Für **space-eigene Inhalte** übernehmen wir das Confluence-Muster: Der Container trägt die Rechte.

Die Trennlinie ist die Entstehung: Was im Space entsteht, gehört ihm; was hineingereicht wird, behält seinen Eigentümer.

## Entscheidung

### 1. Zwei Objektklassen mit unterschiedlicher Rechtelogik

> **Was im Space entsteht, gehört dem Space. Was assoziiert wird, behält seinen Eigentümer.**

- **Assoziierte Assets** (Wissensbibliothek, Agent, Prompt-Bibliothek): eigener Eigentümer, eigene Rechteliste, in 0..n Spaces assoziiert. **Die Assoziation gewährt keinerlei Zugriff.**
- **Space-eigene Inhalte** (Chat, Artefakt; später Bericht, Entwurf, Auswertung): in genau einem Space enthalten. **Space-Mitgliedschaft gewährt vollen Zugriff.**

Die Grundregel wird ausdrücklich zweiteilig formuliert. Ein pauschaler Satz in nur eine Richtung wäre falsch und würde in der Implementierung zu Fehlern führen.

### 2. Dokumente liegen in Wissensbibliotheken

Jedes Dokument gehört zu genau einer Bibliothek; jeder Chunk trägt die Bibliotheks-Kennung als Filterachse der Vektorsuche. Eine Konnektor-Quelle wird genau einer Bibliothek zugeordnet.

### 3. Gruppen sind Rechtesubjekt und tragen die Verteilungsstufen

Rechtelisten verweisen auf Nutzer **oder Gruppen**. Gruppen sind nicht optional: Die Verteilungsstufe „Fachbereich" aus der Produktvision wird als Grant an die Abteilungs- oder Amts-Gruppe abgebildet, weil es bewusst kein Abteilungs-Objekt gibt. Ohne Gruppen hätte das flache Modell an dieser Stelle eine Lücke. Außerdem können Gruppen **Eigentümer** eines Assets sein, was zentral gepflegte Bibliotheken vom Verbleib einzelner Personen entkoppelt.

### 4. Ein Agent liest ausschließlich mit den Rechten des aufrufenden Nutzers

Es gibt keinen Modus, in dem ein Agent mit eigenen Rechten liest. Der Regelweg, damit ein geteilter Agent beim Empfänger funktioniert, ist die **Freigabekette**: Beim Teilen eines Agenten wird sichtbar, welche Bibliotheken er benötigt; deren Eigentümer geben mit frei oder lehnen ab. Damit erhält der Empfänger auch Lesezugriff auf das Rohmaterial — bewusst so, weil er die Antworten fachlich verantworten muss.

Die Folge wird als bewusste Auslassung dokumentiert: Ein Agent, dessen Wissen nicht freigegeben werden darf, ist nicht teilbar. Die Rechteschicht hat keinen Umgehungsweg.

### 5. Der Space ist Ausführungskontext, aber kein Rechteträger für Assets

Ein Chat läuft immer in einem Space und ist ein persistentes Objekt darin. Der Space bestimmt Ablage, Standard-Suchbereich, Modell-Obergrenze, Verfügbarkeitsrahmen und Zurechnung. Er **verengt** den Suchbereich eines ungebundenen Chats, **verengt aber nicht den eines Agenten** — sonst wäre ein geprüftes Agenten-Release nicht mehr reproduzierbar.

### 6. Space-Rollen schrumpfen auf drei

`MEMBER`, `CURATOR`, `ADMIN`, dazu `ownerId` als Attribut. Asset-Rollen sind eine eigene Rangordnung: `USER`, `VIEWER`, `EDITOR`, `ADMIN`, `OWNER`.

### 7. Modell-Policies sind ausschließlich Obergrenzen

Die maßgebliche Menge ist die restriktivste über System, Space, jede beteiligte Bibliothek und den Agenten. Datenschutzrelevante Beschränkungen hängen an der **Bibliothek**, nicht am Space.

### 8. Verteilung per Referenz, Anpassung per Abkömmling

Ein verteiltes Asset ist eine **Referenz**; alle arbeiten mit demselben Objekt, Verbesserungen wirken sofort bei allen, mit vollständiger Versionshistorie und Rückrollmöglichkeit. Wer abweichen muss, erzeugt einen **Abkömmling**, der seine Herkunft dauerhaft trägt und sichtbar als abgeleitet gekennzeichnet ist. Stilles Kopieren gibt es nicht.

Damit kleine Anpassungen keinen Abkömmling erzwingen, erklärt ein Asset eine kurze, typisierte Liste von **Parametern** (etwa Register, Anrede, Zusatzhinweis), die Empfangende je Nutzer, Gruppe oder Space setzen, ohne die Wartungsverbindung zum Original zu kappen. Bewusst kein Vorlagensystem.

**Abdriften von Abkömmlingen** wird als eigenes Risiko behandelt: sichtbarer Versionsstand, Benachrichtigung des Verantwortlichen bei neuer Version, einsehbare Änderungen ohne automatisches Zusammenführen — und bei **Deaktivierung des Originals** eine Prüfaufforderung mit Frist, nach deren Ablauf der Abkömmling automatisch deaktiviert wird. Das ist die einzige Stelle, an der ein Asset ohne Zutun seines Eigentümers den Zustand wechselt; der Fall einer unbemerkt weiterlaufenden überholten Rechtsauffassung wiegt schwerer.

**Rückruf erfolgt durch Deaktivieren, nie durch Löschen.** Bestehende Chatverläufe bleiben lesbar und tragen einen Warnhinweis mit Grund und Datum, weil auf Grundlage der damaligen Antworten Bescheide ergangen sein können. Wer deaktiviert, muss einen Grund angeben.

**Mitgelieferte Assets** sind ein eigener Herkunftstyp: Sie gehören keinem Nutzer und werden mit Produkt-Updates aktualisiert. Anpassung erfolgt per Abkömmling, der von Updates unangetastet bleibt — ein Update kann behördeneigene Änderungen nie überschreiben.

### 9. Kuratoren sind an Organisationseinheiten gebunden

Die Verteilungsstufen bilden sich auf die **Aufbauorganisation** ab, die aus dem Verzeichnis kommt. `ORG_UNIT`-Gruppen sind zugleich Rechtesubjekt und Freigabeziel — dasselbe Objekt in zwei Verwendungen; eine Freigabe an eine Einheit ist ein Grant an deren Gruppe, neu ist nur, wer ihn erteilen darf. `AD_HOC`-Gruppen sind reine Rechtesubjekte ohne Kurator.

Kuratoren sind je Einheit besetzbar; fehlt einer, fällt die Zuständigkeit an die nächsthöhere Einheit. Eine Pilotbehörde ist damit mit einem einzigen zentralen Kurator arbeitsfähig.

Zwei Richtungen, die nicht verwechselt werden dürfen: **Mitgliedschaft vererbt nicht** (sie kommt so, wie das Verzeichnis sie führt), **Zuständigkeit vererbt aufwärts**. Damit ist auch präzisiert, dass die einzige Hierarchie im System die Aufbauorganisation ist — Spaces und Assets bleiben flach.

### 10. Organisation als harte Mandantengrenze

`organizationId` an Space, Asset, Nutzer und Gruppe. Nichts überschreitet die Grenze, auch keine Systemverwaltung.

### Verworfene Alternativen

**Vereinigung — Space-Mitgliedschaft gewährt automatisch Asset-Rechte.** Bequem und intuitiv, aber ein Kurator könnte ein Asset, das ihm nicht gehört, in einen großen Space hängen und damit dessen Mitgliedern Zugriff verschaffen. Confluence verbietet genau diese Richtung ausdrücklich.

**Schnittmenge — Space-Rolle und Asset-Recht müssen beide erlauben.** Scheitert an der Mehrfachzuordnung (welcher Space ist maßgeblich, wenn das Asset in zweien mit unterschiedlichen Rollen liegt) und zerstört das direkte Teilen ohne gemeinsamen Space.

**Assoziation mit explizitem, gedeckeltem Grant.** Sicher, aber sie verlangt bei jeder Zuordnung eine zusätzliche Entscheidung durch den Asset-Verantwortlichen. Zugunsten des einfacheren und strikteren Modells verworfen.

**Space-Hierarchie zur Abbildung der Verteilungsstufen.** Verworfen. Die Stufen „persönlich → Team → Fachbereich → organisationsweit" werden über das Rechtesubjekt abgebildet — persönlich, Team-Gruppe, Abteilungs-Gruppe, organisationsweit — kombiniert mit `visibility` und `listed`. Keine Topologie der Spaces, kein Abteilungs-Objekt.

**Kopie beim Verteilen.** Verworfen — sie ist der Grund, warum heute veraltete Prompt-Fassungen per Mail kursieren. Verteilt wird per Referenz.

**Automatisches Zusammenführen von Abkömmling und Original.** Verworfen. Bei frei formulierten Aufgabenbeschreibungen ist ein verlässliches Zusammenführen nicht möglich; ein unzuverlässiges wäre schlimmer als keines. Der Verantwortliche sieht die Änderungen und entscheidet selbst.

**Abkömmling bei Deaktivierung des Originals sofort mitdeaktivieren.** Verworfen als zu hart — es bricht die Arbeit einer Einheit zu einem willkürlichen Zeitpunkt ab. Stattdessen Prüfaufforderung mit Frist und automatischer Deaktivierung erst danach.

**Nur benachrichtigen, wenn das Original deaktiviert wird.** Verworfen als zu schwach — genau dieser Fall (überholtes Original gesperrt, Abkömmling läuft unbemerkt weiter) ist der gefährlichste.

**Agent liest mit eigenen Rechten (Rechtedelegation).** Zunächst als admin-aktivierbare Ausnahme vorgesehen, dann vollständig verworfen. Mit der Freigabekette gibt es bereits einen Weg, auf dem ein geteilter Agent beim Empfänger funktioniert; ein zweiter wäre redundant und zugleich der riskantere von beiden. Bewusst in Kauf genommen: Ein Agent, dessen Wissen nicht freigegeben werden darf, ist nicht teilbar.

**Chat und Artefakt als Asset-Typen.** Verworfen: falsche Kardinalität (viele, wegwerfbar statt wenige, kuratiert), falsche Beziehung (Komposition statt Assoziation), falsche Rechtelogik (die Chats eines Projekt-Space wären für die Projektmitglieder unsichtbar) und ein abweichendes Sicherheitsprofil (Ergebnisse statt Fähigkeiten).

## Konsequenzen

### Einfacher

- **Assets sind teilbar, exportierbar und katalogfähig** — die Voraussetzung für Verteilungsstufen, Freigabe-Workflow und behördenübergreifenden Austausch.
- **Die Filterachse der Suche wird schlanker:** Aus einer n:m-Beziehung Dokument→Workspaces wird n:1 Dokument→Bibliothek. Die Mehrfachverwendung wandert eine Ebene höher, wo sie nicht je Chunk materialisiert werden muss.
- **Cross-Workspace-Sharing entfällt als Problem.** Man teilt keine Dokumente über Space-Grenzen, sondern die Bibliothek. Auch das bislang unmögliche Teilen zwischen persönlichen Ablagen ist damit gelöst.
- **Der Ausschluss von Konnektor-Dokumenten wirkt an genau einer Stelle** statt je Workspace.
- **Trennung von Technik und Fachlichkeit:** Der System-Admin entscheidet, wohin indiziert wird; der Bibliotheks-Eigentümer entscheidet, wer es sieht.
- **Die Migration ist billiger als erwartet**, weil es keine Dokument-zu-Workspace-Daten gibt: Der Rechteanker wird erstmals eingezogen, nicht verschoben.

### Schwieriger

- **Zwei Rechtelogiken müssen nebeneinander verstanden werden.** Das ist die Hauptlast des Modells. Ein Space-Admin darf das Regal umräumen, aber nicht die Bücher lesen — das ist ungewohnt und muss in der Oberfläche deutlich werden.
- **Zwei Mitglieder desselben Space sehen unterschiedlich viele Assets.** Gewollt, wirkt ohne Erklärung wie ein Fehler.
- **Das Ableitungsleck entsteht neu.** Weil alle Space-Mitglieder alle Chats und Artefakte sehen, wird die Space-Mitgliedschaft faktisch zum effektiven Leserkreis für alles, was dort aus engeren Bibliotheken entsteht. Jemanden einem gemischten Space hinzuzufügen ist so folgenreich wie eine Rechtevergabe an diesen Bibliotheken — nur sieht es nicht so aus. Abgefedert durch Kennzeichnung gemischter Assoziationen, Herkunftsverfolgung, Freigabeakt bei Artefakten mit gemischter Herkunft und einen optionalen Strikt-Modus je Space.
- **Zitat-Redaktion löst dieses Leck nicht** und wird bewusst nicht gebaut: Der Antworttext trüge die Information weiterhin, und ein je Leser unterschiedlicher Verlauf zerstörte den gemeinsamen Arbeitsraum. Zitat-Sprungmarken bleiben dagegen rechtegeprüft.
- **Persistente Chats sind ein Neubau.** Bisher existiert nur ein In-Memory-Chatgedächtnis.
- **Breiter Umbenennungsschnitt:** Entitäten, Liquibase-Changelogs, OpenAPI-Spezifikation, generierte DTOs auf beiden Seiten, Frontend-Store und -Seiten. Ein harter Schnitt ohne Kompatibilitätsschicht ist vertretbar, weil das Projekt vor 1.0 steht und keine externen Clients existieren.
- **Konnektor-gespeiste Bibliotheken brauchen eine Freigabe-Obergrenze** vom System-Admin, sonst könnte ein Bibliotheks-Eigentümer eingespeiste Bestände organisationsweit freigeben.
- **Abkömmlinge driften ab.** Wer abzweigt, verliert die fachlichen Korrekturen des Originals. Das ist nicht vollständig lösbar, nur beherrschbar: sichtbarer Versionsstand, Benachrichtigung, Prüfaufforderung mit Frist bei Deaktivierung. Der beste Hebel dagegen ist, Abkömmlinge seltener nötig zu machen — dafür sind die Parameter da, und deshalb müssen sie zusammen mit dem Teilen von Agenten ausgeliefert werden und nicht danach: Bereits entstandene Abkömmlinge lassen sich nicht mehr einsammeln.
- **Eine Prüfaufforderung mit Frist kann ein Asset ohne Zutun seines Eigentümers deaktivieren.** Die einzige solche Stelle im Modell, bewusst in Kauf genommen.
- **Deaktivieren verlangt eine Begründung.** Ohne sie ist der Warnhinweis in Verläufen und bei Abkömmlingen Rauschen und wird ignoriert.
- **Ein Agent, dessen Wissen nicht mitfreigegeben werden darf, ist nicht teilbar.** Bewusste Auslassung, kein Versehen — sie ist der Preis dafür, dass die Rechteschicht keinen Umgehungsweg hat.
- **Gruppen werden früh gebraucht**, nicht erst mit der Verzeichnis-Anbindung. Das vergrößert den ersten Umsetzungsschritt spürbar, ist aber unvermeidbar: Ohne Gruppen fehlt die Verteilungsstufe „Fachbereich", und Eigentümerschaft bliebe an Personen gebunden.
- **Assets brauchen eine Verwaisungsregel.** Eigentum kann an einer Gruppe hängen; Offboarding erzwingt eine Nachfolge; als Sicherheitsnetz gibt es einen Verwaist-Status ohne stillschweigende Löschung oder Reichweitenänderung.
- **Mitbestimmungspflicht ist wahrscheinlich.** Space-weite Chat-Sichtbarkeit und Nutzungstransparenz je Asset sind geeignet, Verhalten und Leistung abzubilden. Das Produkt muss dafür Stellschrauben mitbringen — Aggregation statt Personenbezug, Abschaltbarkeit, Aufbewahrungsfristen, getrennte Zugriffswege. Ohne sie scheitert die Einführung nicht an der Technik, sondern an der Dienstvereinbarung.
- **Die Produktvision muss nachgezogen werden** an fünf Stellen: Verteilungsstufen über Gruppen statt Space-Topologie, Auflösung des Widerspruchs „Space bringt Wissen mit" gegen „Agent bringt Wissen mit", Mandantentrennung nicht mehr am Space, Modell-Policies im Verhältnis zum nicht-rechtetragenden Space, und die proaktive Adressierung der Mitbestimmung im Vertriebsmaterial.

## Verwandte Dokumente

- [Spaces, Assets & Zugangskontrolle](../features/spaces-and-assets.md) — die ausformulierte Spezifikation
- [Diskussion: Workspace-Konzept](../discussions/discussion-workspace-concept.md) — Vorgeschichte
- [ADR-0006: OpenAPI-First-DTO-Generierung](./0006-openapi-dto-generation.md) — betrifft den Umbenennungsschnitt an den DTOs
