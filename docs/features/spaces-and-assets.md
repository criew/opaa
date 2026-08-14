# Spaces, Assets & Zugangskontrolle

## Motivation

Wissen und KI-Können sollen in einer Organisation ankommen, nicht in Silos verharren. Das bisherige Workspace-Modell hat beides in denselben Behälter gesperrt: Ein Workspace war zugleich Dokumentencontainer, Rechtegrenze und Ordnungsrahmen. Damit war ein guter Agent nicht teilbar, ohne den ganzen Workspace zu teilen, und eine Wissenssammlung nicht mehrfach verwendbar, ohne sie zu duplizieren.

Dieses Dokument beschreibt das abgelöste Modell: **Assets** (Wissensbibliotheken, Agenten, Prompt-Bibliotheken) sind eigenständige, teilbare Objekte mit eigenem Eigentümer. **Spaces** sind thematische Arbeitsräume, in denen gearbeitet wird und in denen Ergebnisse entstehen.

---

## Überblick

1. **Assets** gehören ihrem Eigentümer und tragen ihre eigenen Rechte. Sie werden in Spaces *assoziiert* — die Assoziation gewährt keinerlei Zugriff.
2. **Spaces** sind Arbeitsräume. Chats und Artefakte entstehen *in* einem Space und gehören ihm — sie sind **zunächst privat** und werden für alle Mitglieder sichtbar, sobald der Ersteller sie **in den Space teilt**.
3. **Dokumente** liegen in Wissensbibliotheken, nicht in Spaces. Die rechtebewusste Vektorsuche filtert über die Bibliothek.
4. **Ein Chat läuft immer in einem Space.** Der Space bestimmt Aufbewahrung, Standard-Suchbereich, Modell-Policy und Zurechnung — aber keine Rechte an Assets.
5. **Rechte gelten für Nutzer und Gruppen.** Gruppen bilden die Aufbauorganisation ab und tragen die Verteilungsstufe „Fachbereich".
6. **Ein Agent liest immer mit den Rechten des Nutzers.** Damit ein geteilter Agent funktioniert, wird sein Wissen mitfreigegeben — es gibt keinen Umgehungsweg.
7. **Verteilt wird per Referenz, nicht per Kopie.** Verbesserungen wirken sofort bei allen; wer abweichen muss, erzeugt einen gekennzeichneten Abkömmling.
8. **Organisation** ist die harte Mandantengrenze, die nichts überschreitet.

---

## Die zentrale Merkregel

> **Was im Space entsteht, gehört dem Space.
> Was assoziiert wird, behält seinen Eigentümer.**

Aus dieser Regel folgen zwei Objektklassen mit **bewusst unterschiedlicher Rechtelogik**. Wer nur eine der beiden Regeln im Kopf hat, baut Fehler:

| | **Assoziierte Assets** | **Space-eigene Inhalte** |
|---|---|---|
| Beispiele | Wissensbibliothek, Agent, Prompt-Bibliothek | Chat, Artefakt (Excel, Chart; später Bericht, Entwurf, Auswertung) |
| Beziehung zum Space | Assoziation: 0..n Spaces, optional, jederzeit lösbar | Enthaltensein: genau 1 Space, zwingend, nicht lösbar |
| Rechteanker | **eigene ACL am Asset** | **Ersteller**, solange privat — danach **Space-Mitgliedschaft** |
| Entstehung | anderswo erzeugt, hineingereicht | im Space erzeugt |

**Präzise Fassung der Grundregel — beide Hälften gelten:**

- Space-Mitgliedschaft gewährt **keinen** Zugriff auf assoziierte Assets und deren Dokumente.
- Space-Mitgliedschaft gewährt **vollen** Zugriff auf **geteilte** space-eigene Inhalte. Private Inhalte gehören ausschließlich ihrem Ersteller — auch Space-Admins und System-Admins sehen sie nicht.

Ein pauschaler Satz in nur eine Richtung („der Space trägt keine Rechte" oder „der Space trägt die Rechte") wäre falsch.

---

## Assets

### Was ein Asset ist

Ein Asset ist ein benanntes, beschriebenes, auffindbares Objekt mit genau einem Eigentümer und einer eigenen Rechteliste. In dieser Ausbaustufe gibt es drei Typen; das Modell ist bewusst so geschnitten, dass weitere ohne Strukturänderung hinzukommen:

| Typ | Inhalt |
|---|---|
| **Wissensbibliothek** | Dokumente (aus Upload oder Konnektor) samt Chunks und Embeddings |
| **Agent** | Aufgabenbeschreibung, gebundene Wissensbibliotheken, Werkzeugrechte, Modellwahl |
| **Prompt-Bibliothek** | wiederverwendbare, benannte Prompts |

Die Ausdifferenzierung der Asset-Typen ist **nicht** Gegenstand dieses Dokuments. Gegenstand ist das Assoziations- und Rechtemodell, das für alle Typen gleich gilt.

### Asset-Rollen

| Rolle | Darf |
|---|---|
| `VIEWER` | das Asset benutzen und seine Konfiguration einsehen — Agent aufrufen, Bibliothek liefert Treffer, Aufgabenbeschreibung und Dokumentenliste sind sichtbar |
| `EDITOR` | zusätzlich ändern |
| `MANAGER` | zusätzlich teilen, Rechte vergeben, Freigabestufe und Auffindbarkeit setzen |
| `OWNER` | zusätzlich löschen und Eigentum übertragen |

Asset-Rollen sind eine **eigene Rangordnung**, getrennt von den Space-Rollen. **Kein Rollenname kommt in beiden Systemen vor** — deshalb heißt die verwaltende Asset-Rolle `MANAGER` und nicht `ADMIN`. Wer sagt „ich habe hier Admin-Rechte", meint damit immer einen Space; wer Asset-Rechte meint, sagt `MANAGER` oder `OWNER`. Das ist keine Kosmetik: Bei der Übergabe eines Vorgangs muss ohne Rückfrage klar sein, wovon die Rede ist.

#### Die beiden Rollensysteme im Überblick

Es gibt **zwei Rollensysteme** plus eine systemweite Rolle. Sie beantworten verschiedene Fragen und werden nie miteinander verrechnet:

| System | Rollen | Beantwortet die Frage |
|---|---|---|
| **Asset** | `VIEWER` · `EDITOR` · `MANAGER` · `OWNER` | Was darf ich mit diesem Agenten, dieser Bibliothek, dieser Prompt-Sammlung tun? |
| **Space** | `MEMBER` · `CURATOR` · `ADMIN` (+ `ownerId` als Attribut) | Was darf ich in diesem Arbeitsraum tun? |
| **systemweit** | System-Admin | Wer verwaltet Konnektoren, Verzeichnis, Policies und offene Nachfolgen? |

Beide Systeme sind **unabhängig**: Ein Space-`ADMIN` hat dadurch keinerlei Recht an den dort assoziierten Assets, und ein Asset-`OWNER` ist dadurch in keinem Space Mitglied.

**Gruppen tragen keine Rollen.** Eine Gruppe ist ausschließlich Rechtesubjekt — ein Grant an sie wirkt für ihre Mitglieder, mehr nicht. Es gibt keine Rolle „innerhalb" einer Gruppe und keine Instanz, die einer Freigabe an eine Gruppe zustimmen müsste (siehe [Verteilung von Assets](#verteilung-von-assets)).

### Rechte an einem Asset erhalten

Ein Nutzer erhält Rechte an einem Asset auf genau drei Wegen:

1. **Direkter Grant** an ihn persönlich.
2. **Grant an eine Gruppe**, der er angehört.
3. **Organisationsweite Freigabe** (`visibility = ORGANIZATION`).

```
lesbare_Bibliotheken(u) =
    { L : direkter Grant(u, L) ≥ USER }
  ∪ { L : Grant(g, L) ≥ USER für eine Gruppe g mit u ∈ g }
  ∪ { L : L.visibility = ORGANIZATION und gleiche Organisation }
```

**Space-Assoziationen kommen in diesem Ausdruck nicht vor.** Das ist die Kernaussage des Modells.

### Gruppen als Rechtesubjekt

Eine Rechteliste verweist auf **einen Nutzer oder eine Gruppe**. Gruppen sind kein Nachtrag, sondern tragend:

- **Sie skalieren, wo Einzelfreigaben es nicht tun.** Eine Stabsstelle braucht Lesezugriff quer über viele Referate; das über Einzelgrants zu pflegen ist aussichtslos und veraltet ab dem ersten Personalwechsel.
- **Sie bilden die Aufbauorganisation ab.** Verwaltung ist nach Referat, Abteilung und Amt gegliedert. Genau diese Struktur steht im Verzeichnisdienst und wird später über die Verzeichnis-Synchronisation übernommen.
- **Sie tragen die Verteilungsstufen** (siehe nächster Abschnitt) und lösen das Problem der Assets ohne Zuständigkeit (siehe [Eigentümerschaft](#eigentümerschaft-und-verwaisung)).

In der ersten Ausbaustufe werden Gruppen im System gepflegt; die Übernahme aus dem Verzeichnisdienst folgt und ändert nichts am Rechtemodell, sondern nur an der Herkunft der Mitgliedschaften.

### Freigabestufen und Auffindbarkeit

Die in der Produktvision beschriebene Verteilung „persönlich → Team → Fachbereich → organisationsweit" ist eine Eigenschaft **des Assets**, keine Topologie der Spaces. Es gibt keine Space-Hierarchie — und es gibt auch **kein Abteilungs- oder Amts-Objekt**.

Die Stufe ergibt sich stattdessen daraus, **wem** der Grant gilt, kombiniert mit der Auffindbarkeit:

| Stufe der Vision | Umsetzung im Modell |
|---|---|
| persönlich | `visibility = PRIVATE`, keine Grants außer dem Eigentümer |
| Team | `visibility = SHARED`, Grant an die Team-Gruppe |
| **Fachbereich** | `visibility = SHARED`, **Grant an die Abteilungs- oder Amts-Gruppe** |
| organisationsweit | `visibility = ORGANIZATION` |

Ein Asset „an die ganze Abteilung freigeben" heißt also: **Grant an die Gruppe, die die Abteilung abbildet** — erteilt von einem `MANAGER` des Assets, ohne dass die Abteilung zustimmen muss. Ohne Gruppen gäbe es die Stufe „Fachbereich" nicht; sie ist der Punkt, an dem das flache Space-Modell sonst eine Lücke hätte. Das ist der eigentliche Grund, warum Gruppen früh und nicht später gebraucht werden. Einzelheiten unter [Verteilung von Assets](#verteilung-von-assets).

Zwei Felder steuern das:

| Feld | Werte | Wirkung |
|---|---|---|
| `visibility` | `PRIVATE` \| `SHARED` \| `ORGANIZATION` | Reichweite der Freigabe |
| `listed` | `true` \| `false` | Auffindbarkeit im Katalog, unabhängig vom Zugriff |

`listed` ist standardmäßig `false`. Erst mit der Freigabe ab Fachbereichsebene kann ein Asset gelistet werden, und das ist eine bewusste Entscheidung des Freigebenden. Damit ist ein Asset auffindbar, ohne zugänglich zu sein — aber nur, wenn jemand das ausdrücklich wollte.

### Eigentümerschaft und Verwaisung

Jedes Asset hat genau einen Eigentümer. Wenn dieser Eigentümer eine **Person** ist, hängt das Asset in dem Moment, in dem sie die Behörde verlässt oder das Referat wechselt — ein realer und häufiger Fall, den die bisherige Spezifikation nur für Dokumente und persönliche Ablagen regelt, nicht für Agenten und Bibliotheken.

**Grundsatz: Der Zugang wird nie durch die Eigentumsfrage aufgehalten.** Eine frühere Fassung verlangte, ein Konto könne nicht deaktiviert werden, solange es Assets besitzt. Das ist nicht haltbar: Ein Beschäftigter scheidet Freitagnachmittag kurzfristig aus, der Zugang muss sofort weg, und niemand kann in diesem Moment für dutzende Objekte entscheiden, wer künftig zuständig ist. Eine Regel, die das verlangt, wird umgangen — man deprovisioniert im Verzeichnis, und der Sicherungsmechanismus greift genau dann nicht, wenn er gebraucht wird.

**Regelung:**

1. **Eigentümer kann eine Person oder eine Gruppe sein.** Für zentral gepflegte Assets — Rechtsquellen, Dienstanweisungen, hausweite Agenten — ist die **Gruppe der Regelfall**: Eigentümer ist „Referat Z 2", nicht Frau Müller. Damit übersteht das Asset jeden Personalwechsel, und die Zuständigkeit ist im Katalog als Organisationseinheit ausgewiesen. Das ist die eigentliche Lösung; alles Folgende ist Auffangnetz.
2. **Die Deaktivierung eines Kontos ist immer sofort möglich** und wird nie blockiert.
3. **Assets des Ausgeschiedenen gehen in den Zustand „Nachfolge offen".** Sie funktionieren weiter und behalten ihre Rechte, damit die laufende Arbeit nicht abreißt — **aber ihre Reichweite kann nicht mehr wachsen**: keine neuen Grants, keine Erhöhung der Freigabestufe, keine neue Assoziation. Ein Asset ohne fachlich Verantwortlichen darf sich nicht weiter verbreiten.
4. **Die Nachfolge hat einen benannten Adressaten und eine Frist.** Zuständig ist der System-Admin; der Vorgang erscheint mit Frist auf der Governance-Arbeitsliste. Er verfällt nicht und landet nicht in einer namenlosen Sammelliste.
5. **Nichts wird stillschweigend gelöscht oder in seiner Reichweite verändert.**

Damit gibt es keine Regel mehr, die eine andere aufhebt: Der Zugang endet sofort, die Zuständigkeit wird nachgezogen, und in der Zwischenzeit ist das Asset nutzbar, aber eingefroren.

Verfall — also automatisches Löschen von Assets ohne Zuständigkeit — wird ausdrücklich verworfen: In der Verwaltung ist der Verlust einer gepflegten Wissensbibliothek teurer als ihr Weiterbestehen unter geklärter Einschränkung.

---

## Verteilung von Assets

Bis hierher ging es darum, **wer** auf ein Asset zugreifen darf. Jetzt geht es darum, **wie** ein Asset durch die Organisation wandert — der Kern des Verteilungsversprechens.

### Organisationseinheiten sind Gruppen

Die Verteilungsstufen der Produktvision — persönlich → Team → Fachbereich → organisationsweit — bilden sich auf die **Aufbauorganisation** ab, die im Verzeichnisdienst ohnehin gepflegt wird. Es gibt kein eigenes Abteilungs- oder Amts-Objekt und keine Space-Hierarchie.

Gruppen haben zwei Ausprägungen:

| `Group.kind` | Herkunft | Verwendung |
|---|---|---|
| `ORG_UNIT` | aus dem Verzeichnis synchronisiert (Referat, Abteilung, Amt) | Rechtesubjekt **und** Freigabeziel; kennt ihre übergeordnete Einheit |
| `AD_HOC` | im System angelegt | nur Rechtesubjekt (z. B. „Projektbeteiligte Phoenix", „Stabsstelle Leserunde") |

**Verhältnis von Rechtesubjekt und Freigabeziel:** Es ist **dasselbe Objekt in zwei Verwendungen**, und materiell derselbe Vorgang. „Ein Asset an die Abteilung 5 freigeben" heißt: ein Grant an die Gruppe „Abteilung 5". Erteilt wird er wie jede andere Rechtevergabe von einem `MANAGER` des Assets — für beide Gruppenarten gleich.

**Mitgliedschaft vererbt nicht.** Wer in einer Einheit Mitglied ist, sagt das Verzeichnis. OPAA erfindet keine Vererbung nach unten: Ein Grant an „Amt 5" erreicht nur, wen das Verzeichnis dieser Gruppe zurechnet.

Damit ist auch die frühere Aussage „keine Hierarchie" präzisiert: **Die einzige Hierarchie im System ist die Aufbauorganisation, und sie kommt aus dem Verzeichnis.** Sie dient der Anzeige und der Aggregation von Auswertungen, trägt aber keine Zuständigkeit. Spaces bleiben flach, Assets bleiben flach.

### Freigabe an eine Gruppe braucht keine Zustimmung

Eine Verteilung hätte zwei Seiten haben können: die Gebeseite — ein `MANAGER` des Assets erteilt den Grant — und eine Annahmeseite, auf der die empfangende Einheit zustimmt. **Die Annahmeseite gibt es nicht.**

> Ein Grant an eine Gruppe wird allein vom `MANAGER` des Assets erteilt. Niemand muss ihn annehmen, und es gibt keine Größenschwelle, ab der eine Zustimmung nötig würde.

Der Grund ist die Asymmetrie zwischen Geben und Empfangen: **Ein Grant setzt niemanden etwas aus.** Er gewährt Zugriff, er verteilt keine Inhalte an Unbeteiligte. Wer ihn nicht nutzen will, nutzt ihn nicht. Das Risiko ist deshalb kein Datenabfluss, sondern Katalog-Rauschen — dass jemand vierhundert Personen ein Asset in die Liste legt, das sie nicht angefordert haben.

Dagegen wirken zwei Mittel, die es ohnehin gibt:

- **`listed` ist standardmäßig `false`.** Ein Asset ist zugänglich, ohne im Katalog aufzutauchen; die Aufnahme in den Katalog ist eine eigene, bewusste Entscheidung (siehe [Freigabestufen und Auffindbarkeit](#freigabestufen-und-auffindbarkeit)).
- **Die Governance-Arbeitsliste.** Der System-Admin sieht, was breit verteilt wurde, und kann eingreifen — Freigaben laufen frei, die Aufsicht schaut hinterher.

**Was damit ersatzlos entfällt:** Kuratoren als Objekt an der Organisationseinheit, die Zuständigkeitsvererbung nach oben, die konfigurierbare Größenschwelle, die Sonderbehandlung des Umgehungswegs über `AD_HOC`-Gruppen sowie Freigabeanfragen mit Frist, Eskalation und Liegezeit-Listen. Eine frühere Fassung sah das alles vor; die Begründung für die Streichung steht unter [Geprüfte und verworfene Alternativen](#verteilung-von-assets-1).

**Wo eine Obergrenze bleibt:** Bibliotheken, die aus einem Konnektor gespeist werden, tragen weiterhin eine vom System-Admin gesetzte Freigabe-Obergrenze (siehe [Konnektoren und Quellzuordnung](#konnektoren-und-quellzuordnung)). Sie ist die einzige Stelle, an der die Reichweite eines Grants technisch gedeckelt ist — und sie schützt den Fall, der es braucht: einen Bestand, den ein Admin eingespeist hat und über den ein Bibliotheks-Eigentümer sonst frei verfügen könnte.

### Referenz statt Kopie

**Ein verteiltes Asset ist eine Referenz.** Alle Nutzenden arbeiten mit demselben Objekt; es wird beim Verteilen nicht kopiert. Daraus folgt unmittelbar:

**Verbesserungen wirken sofort bei allen.** Korrigiert der Eigentümer einen Agenten, arbeiten alle Nutzenden ab dem nächsten Aufruf mit der neuen Fassung. Dazu gehört zwingend:

- **Vollständige Versionshistorie** — jede Änderung ist eine Version mit Urheber, Zeitpunkt und Anlass.
- **Rückrollmöglichkeit** — eine frühere Version kann wieder aktiv gesetzt werden. Das Zurückrollen ist selbst ein Vorgang mit Eintrag, kein Löschen der Zwischenversionen.

Versioniert wird die **Konfiguration** des Assets: bei einem Agenten die Aufgabenbeschreibung, die gebundenen Wissensbibliotheken, die Modellwahl und die Parameter; bei einer Prompt-Bibliothek ihre Prompts; bei einer Wissensbibliothek ihre Konfiguration — **nicht** der Dokumentenbestand. Dokumentenversionierung ist ein eigenes Thema und hier nicht gemeint.

### Anpassen ohne Fork: Parameter

Bevor jemand ein Asset abwandelt, sollte er es einstellen können. Der Asset-Eigentümer erklärt am Asset eine kleine Zahl **Parameter** — benannt, typisiert, mit erlaubten Werten und Vorbelegung:

```
Agent "Auskunft Beihilfe"
  Parameter:
    register        : Amtssprache | Leichte Sprache      (Vorgabe: Amtssprache)
    zusatzhinweis   : Freitext, max. 200 Zeichen         (Vorgabe: leer)
    anrede          : Sie | neutral                      (Vorgabe: Sie)
```

Empfangende setzen Werte je Nutzer, Gruppe oder Space, **ohne zu forken**. Das Asset bleibt eine Referenz, Verbesserungen fließen weiter.

Das ist bewusst **kein Vorlagensystem** — keine Schleifen, keine Bedingungen, keine freie Textersetzung im Systemprompt. Eine kurze, typisierte Liste. Der Zweck ist ausschließlich, den häufigsten Fork-Anlass zu vermeiden: Wer nur einen anderen Tonfall oder einen Zusatzhinweis braucht, soll dafür nicht die Wartungsverbindung zum Original kappen.

**Zur Reihenfolge:** Parameter gehören in dieselbe Auslieferung wie das Teilen von Agenten, nicht in eine spätere. Forks, die in der Zwischenzeit entstehen, lassen sich nachträglich nicht mehr einsammeln — sie sind dauerhaft.

### Abkömmlinge (Forks)

Reicht die Parametrisierung nicht, wird ein **Abkömmling** erzeugt. Nie ein stilles Kopieren:

- Der Abkömmling trägt seine **Herkunft dauerhaft**: aus welchem Asset und aus welcher Version er entstanden ist.
- Er ist überall sichtbar **als abgeleitet gekennzeichnet** — im Katalog, in der Detailansicht und dort, wo er benutzt wird.
- Er hat einen eigenen Eigentümer und eigene Rechte und ist ein vollwertiges Asset.

### Das Abdriften von Abkömmlingen

**Dies ist die Kehrseite von Referenz plus sofort wirksamen Verbesserungen, und sie ist gefährlich.** Referat 51 zweigt wegen einer Kleinigkeit im Tonfall ab und bekommt danach die fachlichen Korrekturen von Referat 34 nicht mehr. Bei einer Gesetzesänderung ist das ein fachlicher Fehler mit Außenwirkung — und in Verbindung mit der Deaktivierung (siehe unten) noch schärfer: Das Original wird gesperrt, weil es überholt ist, der Abkömmling läuft weiter, und **niemand merkt es**.

Die Gefahr ist nicht, dass ein Abkömmling existiert. Die Gefahr ist, dass niemand hinsieht. Die Behandlung setzt deshalb genau dort an:

1. **Versionsstand ist immer sichtbar.** Der Abkömmling zeigt: „basiert auf Version 3 — das Original steht bei Version 5".
2. **Der Verantwortliche wird benachrichtigt**, wenn das Original eine neue Version bekommt. Bei Gruppen-Eigentum geht die Nachricht an die Einheit und nicht an eine Person, die im Urlaub sein kann.
3. **Änderungen sind einsehbar.** Der Verantwortliche sieht, was sich am Original geändert hat, und entscheidet selbst, ob er es übernimmt. Ein automatisches Zusammenführen gibt es **nicht** und ist ausdrücklich nicht geplant — es wäre bei frei formulierten Aufgabenbeschreibungen nicht verlässlich.
4. **Bei Deaktivierung des Originals reicht eine Benachrichtigung nicht.** Der Abkömmling erhält eine **Prüfaufforderung mit Frist** — dieser Teil ist bewusst **zurückgestellt** und nicht Bestandteil der ersten Ausbaustufe, weil er voraussetzt, dass Abkömmlinge in nennenswerter Zahl entstehen:
   - Er läuft zunächst weiter, trägt aber für **alle Nutzenden** — nicht nur für den Verantwortlichen — einen deutlichen Hinweis mit dem Grund der Deaktivierung des Originals.
   - Der Verantwortliche muss innerhalb der Frist ausdrücklich bestätigen, dass der Abkömmling fachlich weiter gilt, oder ihn selbst deaktivieren.
   - Bleibt die Bestätigung aus, wird der Abkömmling **automatisch deaktiviert**.

Punkt 4 ist die einzige Stelle im Modell, an der ein Asset ohne Zutun seines Eigentümers seinen Zustand ändert. Das ist beabsichtigt: Der Fall, dass eine überholte Rechtsauffassung unbemerkt weiterläuft, wiegt schwerer als die Unannehmlichkeit einer erzwungenen Prüfung.

**Voraussetzung dafür ist eine Begründungspflicht:** Wer ein Asset deaktiviert, gibt einen Grund an („SGB II § 7 geändert zum 1. Januar"). Dieser Grund ist es, der bei Abkömmlingen und in Chatverläufen angezeigt wird. Ohne ihn ist der Hinweis Rauschen und wird ignoriert.

### Rückruf durch Deaktivieren

Ein fachlich überholtes Asset wird **deaktiviert, nicht gelöscht**:

- Es ist nicht mehr aufrufbar und erscheint nicht mehr im Katalog.
- **Bestehende Chatverläufe bleiben vollständig lesbar** und tragen einen sichtbaren Warnhinweis mit Grund und Datum. Der Hinweis wird **je Verlauf beim Öffnen** aus dem Zustand des Assets aufgelöst, nicht je Nachricht materialisiert — sonst trüge nach der Deaktivierung einer viel genutzten Rechtsquellen-Bibliothek eine sechsstellige Zahl von Nachrichten dieselbe Meldung, und der Hinweis würde zu dem Rauschen, das er vermeiden soll.

Der Grund ist die Nachvollziehbarkeit: Auf Grundlage der damaligen Antworten können Bescheide ergangen sein. Ein Löschen würde die Spur zerstören, die eine Revision oder ein Widerspruchsverfahren später braucht. Deshalb wird nichts entfernt, sondern nur unbrauchbar gemacht und gekennzeichnet.

Für Wissensbibliotheken gilt dasselbe: Eine deaktivierte Bibliothek liefert keine Treffer mehr; vorhandene Zitate in Verläufen bleiben lesbar, und ihre Sprungmarken bleiben rechtegeprüft.

Der Hinweis hängt an den Nachrichten, die das Asset tatsächlich genutzt haben — das setzt die Herkunftsverfolgung voraus, die ohnehin für das Ableitungsleck geführt wird.

### Der Freigabeweg: vorschlagen, prüfen, freigeben, veröffentlichen

Bis hierher ist die Reichweite eines Assets die Entscheidung einer einzigen Person: Ein `MANAGER` erteilt einen Grant, und niemand muss zustimmen (siehe [Freigabe an eine Gruppe braucht keine Zustimmung](#freigabe-an-eine-gruppe-braucht-keine-zustimmung)). Für ein Asset, auf das sich ein Referat stützt, genügt das. Für eines, auf das sich ein ganzes Amt stützt, nicht: Wer eine Auskunft mit seinem Namen trägt, muss Jahre später belegen können, **wer welche Fassung wann fachlich geprüft und freigegeben hat**.

Der Freigabeweg hat vier Schritte:

| Schritt | Wer handelt | Was entsteht |
|---|---|---|
| **Vorschlagen** | `MANAGER` oder `OWNER` des Assets | Eine benannte Fassung wird zur Prüfung für eine Freigabestufe eingereicht |
| **Fachlich prüfen** | die für diese Stufe benannte prüfende Stelle | Prüfvermerk mit Ergebnis; bei Agenten gehört der Bericht des [Prüfstands](./agents-and-tools.md#agenten-prüfstand-vor-der-freigabe) zur Unterlage |
| **Freigeben** | dieselbe Stelle | Freigabestempel an der **Version**: wer, wann, für welche Stufe, auf welcher Prüfgrundlage |
| **Veröffentlichen** | `MANAGER` des Assets | Grants und `listed` werden gesetzt — der Schritt, der die Reichweite tatsächlich herstellt |

**Der Freigabestempel hängt an der Version, nicht am Asset.** Sonst wäre er nach der ersten Änderung wertlos — und genau diese Frage stellt eine Revision: nicht „war der Agent freigegeben", sondern „war *diese* Fassung freigegeben, als *dieser* Bescheid erging".

**Verhältnis zur Sofortwirkung von Verbesserungen.** [Referenz statt Kopie](#referenz-statt-kopie) sagt zu, dass eine Verbesserung sofort bei allen wirkt. Das gilt uneingeschränkt für Assets ohne Freigabestempel. Für ein freigegebenes Asset gilt es nicht: Dort bleibt die **geprüfte Fassung aktiv**, bis eine neue freigegeben ist; die neue läuft bis dahin als Entwurf und ist nur für den Eigentümer aufrufbar. Andernfalls wäre die Freigabe eine Momentaufnahme, die der nächste Tippfehler im Systemprompt aushebelt. Der Preis ist ehrlich zu benennen: Eine dringende fachliche Korrektur an einem freigegebenen Agenten braucht einen Prüfdurchlauf und ist damit nicht sofort wirksam. Wo das nicht hinnehmbar ist, bleibt das Deaktivieren (siehe [Rückruf durch Deaktivieren](#rückruf-durch-deaktivieren)) — lieber kein Agent als ein falscher.

**Der Freigabeweg ist kein Zustimmungsvorbehalt der Empfänger.** Er beschreibt den Reifegrad des Assets, nicht die Annahmeseite. Ein Grant an eine Gruppe braucht weiterhin niemandes Einverständnis; was hinzukommt, ist die Anforderung, dass ein Asset ab der Stufe Fachbereich **fachlich geprüft** ist, bevor es dort ankommt.

**Wer prüft.** Die prüfende Stelle wird je Freigabestufe benannt — als Person oder, besser, als Gruppe. Ist für eine Stufe keine Stelle benannt, ist die Stufe schlicht nicht erreichbar; das System erfindet keine Ersatzzuständigkeit und lässt die Prüfung auch nicht entfallen. Ein Vorschlag läuft nicht ins Leere: Er trägt eine Frist und erscheint nach Ablauf auf der Governance-Arbeitsliste des System-Admins — derselbe Mechanismus wie bei der Mitfreigabe von Wissen und bei offener Nachfolge.

Die Zustände einer Version sind eine **eigene Achse**, unabhängig von `visibility` und `listed`:

| Zustand | Bedeutung |
|---|---|
| `DRAFT` | in Arbeit, nur für Berechtigte am Asset aufrufbar |
| `IN_REVIEW` | zur Prüfung eingereicht, unverändert bis zur Entscheidung |
| `RELEASED` | freigegeben, mit Stempel und Prüfgrundlage |
| `REJECTED` | mit Begründung zurückgewiesen; die Fassung bleibt erhalten und nachvollziehbar |

*Phasenlage: Der Freigabeweg gehört zu Phase 3. Die Versionierung, auf der er aufsetzt, entsteht in Phase 2.*

### Der Katalog

Der Katalog ist die Antwort auf den Satz „das hätte ich gebraucht, ich wusste nur nicht, dass es das gibt". **KI-Assets sollen gefunden und nicht herumgemailt werden.**

**Auffindbarkeit ist ein eigenes Merkmal, getrennt von der Zugänglichkeit.** Das Feld `listed` ist bereits unter [Freigabestufen und Auffindbarkeit](#freigabestufen-und-auffindbarkeit) eingeführt; hier steht, was daraus folgt. Vier Kombinationen sind möglich, und alle vier kommen vor:

| | `listed = true` | `listed = false` |
|---|---|---|
| **zugänglich** | Regelfall für geprüfte Assets: nutzbar und im Katalog auffindbar | Regelfall für Arbeitsstände und für gezielt geteilte Assets: nutzbar, aber nicht beworben |
| **nicht zugänglich** | Schaufenster: der Eintrag ist sichtbar, die Nutzung nicht — man erfährt, dass es etwas gibt, und an wen man sich wendet | Das Asset existiert für diesen Nutzer nicht |

Der Fall unten links ist der einzige, der die Zusage zur Nicht-Sichtbarkeit berührt: Ein Katalogeintrag verrät die **Existenz** eines Assets. Deshalb ist `listed` standardmäßig `false` und die Listung eine ausdrückliche Entscheidung des Freigebenden.

Ein Katalogeintrag enthält **Beschreibungen, keine Inhalte**:

- Name, Kurzbeschreibung und der Anwendungsfall in einem Satz
- die zuständige Stelle — bei Gruppen-Eigentum die Organisationseinheit, nicht eine Person
- Fachbereich, abgeleitet aus der Gruppe, an die freigegeben wurde
- Freigabestand: Stufe, aktive Version, Datum des Freigabestempels
- Herkunft: mitgeliefert, lokal angelegt, abgeleitet oder importiert
- Nutzungsangaben — aggregiert, siehe [Nutzungstransparenz](#nutzungstransparenz)

Die Suche im Katalog läuft über die Assets, auf die der Nutzer Zugriff hat, **vereinigt** mit den gelisteten. Sie überschreitet **nie** die Organisationsgrenze (siehe [Organisation als Mandantengrenze](#organisation-als-mandantengrenze)).

*Phasenlage: durchsuchbarer Katalog in Phase 2; der organisationsweite Katalog mit Freigabestand in Phase 3.*

### Vorlagenkatalog nach Fachbereich

Ein leerer Katalog hilft niemandem, und die Frage „was macht man damit überhaupt" ist am ersten Tag die häufigste. Der **Vorlagenkatalog** beantwortet sie mit Beispielen statt mit Erklärungen: kuratierte Assets, nach Fachbereich geordnet — „so arbeitet die Rechtsbehelfsstelle", „so arbeitet die Kämmerei".

- Der Vorlagenkatalog ist **kein zweites Objekt**, sondern eine Sicht auf den Katalog: kuratierte, gelistete Assets mit gepflegter Beschreibung und Fachbereichszuordnung.
- Der Werksanteil kommt aus den mitgelieferten Assets (siehe [Mitgelieferte Assets](#mitgelieferte-assets)), der Rest aus dem, was die Behörde selbst freigegeben hat.
- Wer eine Vorlage übernimmt, nutzt sie als Referenz oder passt sie über [Parameter](#anpassen-ohne-fork-parameter) an; erst wenn das nicht reicht, entsteht ein gekennzeichneter Abkömmling.

*Phasenlage: Phase 3.*

### Portabilität: Export und Import

Ein Asset lässt sich als Paket **exportieren** und in einer anderen Installation **importieren** — von der Test- in die Produktivumgebung und, als spätere Ausbaustufe, von Haus zu Haus.

| Im Paket enthalten | Nicht im Paket |
|---|---|
| Aufgabenbeschreibung, Konfiguration, Modellvorgaben | Dokumente und Chunks der gebundenen Wissensbibliotheken |
| Parameterdeklaration und Vorbelegungen | Grants, Gruppen, Rechte jeder Art |
| Prüffälle, sofern mit exportiert | Nutzungsdaten, Chats, Artefakte |
| Versionsstand und Herkunftsangabe | der Freigabestempel als wirksame Freigabe |

Daraus folgen drei Eigenschaften, die beim Import sichtbar gemacht werden müssen, weil sie sonst Enttäuschung erzeugen:

1. **Wissen wandert nicht mit.** Ein exportierter Agent verweist auf Bibliotheken, die es beim Empfänger nicht gibt. Der Import benennt jede offene Bindung und der Agent ist erst aufrufbar, wenn sie zugeordnet ist. Die Zusage „ein geteilter Agent bringt sein Wissen mit" gilt **innerhalb** einer Installation über die [Freigabekette](#einen-agenten-weitergeben-die-freigabekette) — über Installationsgrenzen hinweg gilt sie nicht, weil das Wissen dem anderen Haus gehört.
2. **Der Freigabestempel wandert nicht mit.** Er gilt für die Installation, in der er erteilt wurde. Das ist keine Förmelei: Der importierte Agent arbeitet auf anderen Beständen, und eine Prüfung, die dort galt, sagt hier nichts. Das Paket führt den fremden Stempel als **Herkunftsangabe** mit — nachlesbar, nicht wirksam.
3. **Das importierte Asset ist ein neues Objekt** mit eigenem Eigentümer, `origin = LOCAL` und dauerhaft mitgeführter Herkunft (Quellinstallation, Fassung, Zeitpunkt).

Der Export ist eine Handlung des `MANAGER` oder `OWNER` und wird protokolliert. Er enthält keine Dokumente und ist deshalb kein Weg, an Bestände zu kommen — wohl aber kann eine Aufgabenbeschreibung interne Festlegungen enthalten, und das genügt als Grund für die Protokollpflicht.

*Phasenlage: Export und Import in Phase 2; der behördenübergreifende Austausch geprüfter Pakete in Phase 4.*

### Nutzungstransparenz

Kuratierung braucht eine Tatsachengrundlage: Welches Asset wird tatsächlich genutzt, welches liegt seit einem Jahr unbenutzt im Katalog, wo häufen sich schlechte Rückmeldungen. Dieselbe Grundlage belegt der Leitung, ob der KI-Rollout in der Fläche ankommt.

Sichtbar sind je Asset:

- Nutzungshäufigkeit, aufgeschlüsselt nach Organisationseinheit
- Rückmeldungen aus der Nutzung, aggregiert
- Zahl der Abkömmlinge und deren Versionsabstand zum Original
- Alter der aktiven Fassung und Datum des letzten Freigabestempels

**Die Auswertung ist ausschließlich aggregiert je Organisationseinheit, unterhalb der Mindestgruppengröße wird unterdrückt, und es gibt keine Ranglisten.** Das ist keine Einstellung, sondern eine Eigenschaft: Ein personenbezogener Auswertungspfad existiert nicht (siehe [Kein personenbezogener Auswertungspfad](#kein-personenbezogener-auswertungspfad)). Auch der Eigentümer eines Assets sieht, **wie oft** und **in welcher Einheit** es genutzt wird — nicht, von wem.

Die Nutzungsstatistik ist vollständig abschaltbar, ohne dass die Fachfunktion leidet.

*Phasenlage: Phase 2, gemeinsam mit dem Katalog.*

### Mitgelieferte Assets

OPAA liefert erprobte Verwaltungs-Agenten und -Prompts ab Werk aus. Sie sind ein eigener **Herkunftstyp**:

| `Asset.origin` | Bedeutung |
|---|---|
| `BUILT_IN` | mitgeliefert; gehört keinem Nutzer, in der Behörde nicht änderbar, wird über den Aktualisierungsweg des Produkts gepflegt |
| `LOCAL` | von der Behörde angelegt oder abgezweigt |

Wer ein mitgeliefertes Asset anpassen will, erzeugt einen Abkömmling. Der Abkömmling ist `LOCAL` und wird von Produkt-Updates **nicht angefasst**. Damit kann ein Update niemals behördeneigene Änderungen überschreiben — die häufigste und ärgerlichste Form von Datenverlust bei ausgelieferten Vorlagen.

Ein Produkt-Update des Originals löst bei Abkömmlingen dieselbe Anzeige und Benachrichtigung aus wie jede andere neue Version. Ein mitgeliefertes Asset, das die Behörde nicht einsetzen will, kann sie **lokal deaktivieren**, ohne es zu löschen.

---

## Verzeichnissynchronisation als Rechteereignis

Gruppen kommen aus dem Verzeichnisdienst. Ein Synchronisationslauf, der Mitgliedschaften entfernt, ist damit ein **Massen-Rechteentzug ohne menschlichen Entscheidungspunkt** — die einzige Stelle im gesamten Modell, an der sich Rechte in großer Zahl ändern, ohne dass jemand eine Entscheidung trifft. Die Formel „die Synchronisation ändert nur die Herkunft der Mitgliedschaften, nicht das Rechtemodell" ist deshalb irreführend: Die Herkunft **ist** das Risiko.

An ihr hängen die Verwaisung, der Zustand von Strikt-Spaces und die Nachweisbarkeit. Sie ist keine spätere Ausbaustufe, sondern eine Voraussetzung für den ersten Produktivbetrieb.

### Verbindliche Anforderungen

- **Stabile Kennung statt Name.** Der Abgleich erfolgt über die unveränderliche Kennung des Verzeichnisses (etwa `objectGUID` oder die SCIM-`externalId`), niemals über den Gruppennamen. Andernfalls ist jede Umbenennung ein Totalschaden: Alte Gruppen laufen leer, neue entstehen, Grants zeigen ins Leere, und die Verwaisungsregel greift auf jedes Asset, dessen Eigentümer eine solche Gruppe war.
- **Plausibilitätsschwelle mit Abbruch.** Übersteigt ein Lauf einen konfigurierbaren Anteil geänderter Mitgliedschaften, wird er **abgebrochen und gemeldet**, statt durchgeführt. Der klassische Auslöser ist kein Personalereignis, sondern ein Konfigurations- oder Zertifikatswechsel, nach dem das Verzeichnis leere Gruppen liefert.
- **Trockenlauf mit Differenzbericht.** Vor dem ersten Lauf und vor jeder Konfigurationsänderung lässt sich der Lauf ohne Wirkung ausführen; der Bericht zeigt, welche Rechte sich ändern würden.
- **Verhalten bei nicht erreichbarem Verzeichnis: last-known-good.** Der letzte bekannte Stand bleibt in Kraft, es werden **keine** Rechte entzogen, und der Zustand wird gemeldet. Ein Entzug aufgrund fehlender Information wäre der schlechtere Fehler: Er legt die Arbeit still, ohne die Sicherheit zu erhöhen.
- **Eine Protokollzeile je bewirkter Rechteänderung**, nicht je Lauf. Ohne sie ist im Nachhinein nicht feststellbar, warum jemand ab einem bestimmten Tag etwas nicht mehr sehen konnte.

### Gruppengebundene Spaces sind mitbetroffen

Ein Space mit `memberSource = GROUP` leitet seine Mitgliederliste aus einer Verzeichnisgruppe ab (siehe [Gruppengebundene Spaces](#gruppengebundene-spaces)). Ein Synchronisationslauf ändert damit nicht nur Grants an Assets, sondern auch **den Leserkreis geteilter Inhalte**: Wer neu in ein Referat kommt, sieht ab dem nächsten Lauf alle dort geteilten Chats und Artefakte; wer es verlässt, verliert den Zugang.

Das ist fachlich richtig — er gehört dazu beziehungsweise nicht mehr —, aber es ist eine Rechteänderung ohne menschlichen Entscheidungspunkt, und sie trifft Inhalte, für die ein Beschäftigter persönlich die Weitergabe verantwortet hat. Deshalb gilt zusätzlich:

- **Die Autoren-Benachrichtigung löst auch bei Sync-Zuwachs aus.** Die unter [Chats](#chats) zugesagte Nachricht „der Leserkreis eines von dir geteilten Inhalts hat sich wesentlich erweitert" darf nicht davon abhängen, ob ein Mensch das Mitglied aufgenommen hat oder ein Verzeichnislauf. Andernfalls ist die Zusage genau dort wirkungslos, wo der Zuwachs am wenigsten sichtbar ist.
- **Der Zuwachs zählt als Mitgliederaufnahme**, auch für die Prüfung eines Strikt-Space (siehe [Der Strikt-Modus](#der-strikt-modus)). Da ein Verzeichnislauf nicht an Ort und Stelle abgelehnt werden kann, geht ein Strikt-Space, dessen Voraussetzung dadurch bricht, in den Zustand „Voraussetzung verletzt".

### Reorganisation, Umbenennung, Zusammenlegung

In einer Behörde findet alle paar Jahre eine Reorganisation statt. Umbenennung und Zusammenlegung sind dabei die häufigeren Fälle, nicht die Auflösung.

- **Umbenennung** ist folgenlos, weil über die stabile Kennung abgeglichen wird.
- **Zusammenlegung und Neuschnitt:** Verschwindet eine Einheit aus dem Verzeichnis, werden ihre Gruppen als **aufgelöst** markiert. Bestehende Grants an sie bleiben bestehen und wirken für die verbliebenen Mitglieder weiter, können aber nicht erweitert werden. Assets, deren Eigentümerin eine aufgelöste Einheit war, gehen in den Zustand **„Nachfolge offen"** — derselbe Mechanismus wie beim Ausscheiden einer Person (siehe [Eigentümerschaft und Verwaisung](#eigentümerschaft-und-verwaisung)).
- Der System-Admin erhält einen **Reorganisationsbericht**: welche Einheiten weggefallen sind, welche Assets und Grants betroffen sind, wer die Nachfolge übernehmen muss.

Ohne diese Behandlung liegt nach der ersten Reorganisation ein erheblicher Teil des Assetbestands ohne Zuständigkeit da, während die Fachbereiche weiterarbeiten wollen — der teuerste denkbare Betriebsvorfall in diesem Modell.

---

## Spaces

### Was ein Space ist

Ein Space ist ein thematischer Arbeitsraum — für ein Projekt, ein Team, einen Fachbereich oder für die eigene Arbeit. Er ist **kein Sicherheitssilo für Dokumente**, sondern der Ort, an dem gearbeitet wird und an dem die Ergebnisse dieser Arbeit liegen.

### Die fünf Funktionen des Space

1. **Ordnungsrahmen für space-eigene Inhalte.** Chats und Artefakte liegen hier und sind thematisch gruppiert. Ein Space „Projekt 1" enthält n Chats zu verschiedenen Themen und die daraus entstandenen Artefakte. Das ist die Primärfunktion.
2. **Standard-Suchbereich** für Chats ohne gebundenen Agenten — verengend, nie erweiternd.
3. **Policy-Kontext** — welche Modelle hier zulässig sind, als Obergrenze.
4. **Verfügbarkeitsrahmen** — welche Assets hier angeboten werden, gefiltert auf den Zugriff des jeweiligen Nutzers.
5. **Zurechnungspunkt** für Nutzungsstatistik, Kostenzuordnung und Audit; zugleich Vorauswahl beim Hochladen neuer Dokumente.

### Es gibt nur eine Art von Space

**Alle Spaces sind gleich gebaut.** Ein Raum für die eigene Arbeit, ein Projektraum und ein Referatsraum unterscheiden sich nur darin, wer Mitglied ist und woher die Mitgliedschaft kommt — nicht in ihrem Typ. Zwei Attribute genügen:

| Attribut | Bedeutung |
|---|---|
| `isDefault` | Der beim ersten Login automatisch erzeugte Space. Genau einer je Nutzer, nicht löschbar. Ansonsten ein Space wie jeder andere |
| `memberSource` | `MANUAL` — Mitglieder werden eingeladen; `GROUP` — die Mitgliedschaft folgt einer Gruppe aus dem Verzeichnis |

Daraus folgt:

- **Jeder Nutzer darf beliebig viele Spaces anlegen**, auch mehrere, in denen er allein arbeitet. Fünf kleine Vorhaben dürfen fünf Räume haben; die frühere Regel „genau ein persönlicher Space je Nutzer" entfällt.
- **„Persönlich" ist kein Typ, sondern ein Zustand:** ein Space, in dem niemand sonst Mitglied ist. Er braucht keine Sonderbehandlung, weil private Inhalte ohnehin nur ihrem Ersteller gehören (siehe [Die Grundregel](#die-grundregel-zunächst-privat-sichtbar-durch-teilen)).
- **Gruppengebundene Spaces legt nur der System-Admin an.** Das ist eine Berechtigung, keine Space-Art.

Space-Namen sind **nicht global eindeutig**. Zwei Nutzer dürfen beide einen Space „Phoenix" haben. Eindeutigkeit gilt höchstens je Organisation und Name.

#### Gruppengebundene Spaces

Bei `memberSource = GROUP` wird die Mitgliederliste nicht gepflegt, sondern abgeleitet: Wer laut Verzeichnis der Gruppe angehört, ist Mitglied des Space. Das erspart die doppelte Pflege von Referatszugehörigkeit und Raumzugehörigkeit — macht einen Synchronisationslauf aber zu einem Ereignis, das Lesezugriff auf geteilte Inhalte erteilt und entzieht. Was daraus folgt, steht unter [Verzeichnissynchronisation als Rechteereignis](#verzeichnissynchronisation-als-rechteereignis).

Ein Space wird **nicht automatisch für jede Organisationseinheit angelegt**. Vierzig Referate ergäben vierzig Räume, von denen die meisten leer blieben. Die Bindung ist eine bewusste Entscheidung beim Anlegen.

### Space-Sichtbarkeit

Mitgliedschaft und Assetzugriff sind entkoppelt; deshalb braucht der Space eine eigene Sichtbarkeitsachse:

| `visibility` | Bedeutung |
|---|---|
| `PRIVATE` | nur Mitglieder wissen, dass er existiert — Vorgabe für jeden neu angelegten Space |
| `DISCOVERABLE` | im Space-Verzeichnis sichtbar, Beitritt auf Antrag |
| `OPEN` | im Verzeichnis sichtbar, Selbstbeitritt mit einem Klick |

**Chatten setzt Mitgliedschaft voraus.** Das ist keine Hürde, sondern eine Folge des Modells: Der Chat *liegt* im Space, und ohne Mitgliedschaft gäbe es keinen definierten Zurechnungspunkt für Aufbewahrung, Kosten und Audit. Bei `OPEN`-Spaces ist der Beitritt ein Klick und wird protokolliert.

### Space-Rollen

| Rolle | Darf |
|---|---|
| `MEMBER` | Space betreten; Chats anlegen und führen; **alle geteilten** Chats und Artefakte des Space lesen; kuratierte Assets sehen — gefiltert auf den eigenen Zugriff |
| `CURATOR` | zusätzlich Assets assoziieren und lösen, Inhalte ordnen |
| `ADMIN` | zusätzlich Mitglieder und Rollen verwalten, Einstellungen und Policy-Obergrenze setzen, geteilte Inhalte **zurückziehen** (nicht löschen) |

Dazu trägt jeder Space eine `ownerId` als **Attribut** — den fachlich Verantwortlichen, der im Verzeichnis ausgewiesen wird. Einen Space löschen oder die Verantwortung übertragen darf nur der Verantwortliche selbst oder ein System-Admin.

Warum drei statt der bisherigen vier Rollen:

- `VIEWER` und `EDITOR` implizierten Zugriff auf Dokumente. Genau diesen Fehlschluss soll das neue Modell vermeiden; die Umbenennung ist semantisch notwendig.
- `OWNER` als eigener Rang trug sein Gewicht daraus, dass eine Workspace-Löschung alle Dokumente vernichtete. Das ist nicht mehr so — Dokumente liegen in Bibliotheken, die anderen gehören. Der Schutz bleibt über das `ownerId`-Attribut erhalten, ohne vierte Rangstufe.
- `ADMIN` gewinnt dagegen an Gewicht, weil Policy-Obergrenze und Mitgliederverwaltung an ihm hängen.

**Grenze der Admin-Rechte:** Ein Space-Admin kann geteilte Inhalte aus dem Space entfernen, aber nicht beseitigen (siehe [Chats sind vor fremder Löschung geschützt](#chats-sind-vor-fremder-löschung-geschützt)). Er kann die privaten Inhalte anderer Mitglieder **nicht sehen** — auch nicht als Admin.

### Assets in einen Space assoziieren

Ein Space-`CURATOR` kann jedes Asset, auf das er selbst Zugriff hat, in seinen Space assoziieren. Das ist unbedenklich, weil die Assoziation **keine Rechte gewährt** — sie stellt das Asset lediglich im Space zur Verfügung, und zwar nur für die Mitglieder, die ohnehin Zugriff darauf haben.

Der Eigentümer des Assets sieht alle Assoziationen und kann jede davon jederzeit einseitig lösen. Das Asset bleibt Herr über seine Verbreitung.

**Benachrichtigung statt Zustimmung.** Wird eine Bibliothek in einem Space bereitgestellt, dessen Mitglieder nicht sämtlich Lesezugriff darauf haben, **wird ihr Eigentümer aktiv benachrichtigt**. Er muss nicht zustimmen — die Assoziation setzt niemanden etwas aus, weil Inhalte erst durch das Teilen sichtbar werden —, aber er erfährt davon, ohne in eine Liste schauen zu müssen. Das schließt die Lücke, dass ein Referatsleiter erst zufällig bemerkt, wo sein Wissen bereitsteht.

**Selbstschutz des Eigentümers.** Ein Bibliotheks-Eigentümer kann seine Bibliothek als **strikt-only** kennzeichnen. Die Kennzeichnung wirkt an **zwei** Stellen, und die zweite ist die wichtigere:

- Die Bibliothek darf nur in Strikt-Spaces bereitgestellt werden.
- **Sie darf nur von Agenten gebunden werden, die selbst ausschließlich in Strikt-Spaces aufrufbar sind.**

Ohne die zweite Regel liefe die Kennzeichnung ins Leere: Sie würde die Assoziation begrenzen — die dieses Dokument an anderer Stelle ausdrücklich für harmlos erklärt — und den verbliebenen Weg offen lassen. Eine Sachbearbeiterin mit persönlichem Grant auf eine geschützte Bibliothek könnte sonst in einem gewöhnlichen Space einen daran gebundenen Agenten aufrufen, weil der Space den Agenten nicht verengt, und das Ergebnis anschließend teilen. Erst die Bindungsregel macht aus der Kennzeichnung ein Werkzeug gegen das Ableitungsleck.

**Nachträgliche Umstellung auf strikt-only** ist möglich, aber nicht stillschweigend: Bestehen bereits Bereitstellungen in Nicht-Strikt-Spaces oder Bindungen durch entsprechende Agenten, zeigt das System sie auf und der Eigentümer entscheidet — lösen oder abbrechen. Es gibt keinen Zustandswechsel im Hintergrund.

**Was die Kennzeichnung nicht leistet:** Sie gilt für die Bibliothek als Ganzes, nicht je Bereitstellung. Für einen Bestand, der in den meisten Räumen breit verfügbar sein soll und nur in einem einzelnen gemischten Projektraum eine Prüfung verdiente, ist sie das falsche Werkzeug — der Eigentümer müsste im Voraus über künftige Verwendungen entscheiden, die er noch nicht kennt. Für diesen Fall bleiben die Benachrichtigung, das Lösen der Bereitstellung und die Nennung des Eigentümers im Teilen-Dialog.

**Folge für die Oberfläche:** Zwei Mitglieder desselben Space sehen unterschiedlich viele Assets. Das ist gewollt, wirkt aber ohne Erklärung wie ein Fehler und muss in der Oberfläche einmal deutlich benannt werden.

#### Leitbeispiel: gemeinsame Rechtsquellen

Der Kernnutzen der Assoziation zeigt sich an dem Fall, der in jeder Behörde vorliegt:

```
Wissensbibliothek "Rechtsquellen Soziales"
  Eigentümer:  Gruppe "Referat 50 · Grundsatz"      (nicht eine Person)
  Inhalt:      SGB II, SGB XII, VwVfG, Dienstanweisungen, Rundschreiben
  Pflege:      ein Konnektor, ein Zuständiger, eine Fassung

  assoziiert in:
    Space "Team Leistungsgewährung"
    Space "Team Eingliederung"
    Space "Widerspruchsstelle"
    Space "Projekt Bürgergeld-Umstellung"
    … beliebig viele weitere
```

Die Rechtsquellen liegen **genau einmal**, werden **an einer Stelle** gepflegt und sind trotzdem überall verfügbar. Ändert sich eine Dienstanweisung, wirkt das sofort in allen Spaces — ohne Kopien, ohne Abgleich, ohne veraltete Zweitfassungen. Genau das war mit einem Workspace als Dokumentencontainer nicht möglich; es hätte in jedem Team eine eigene Kopie gebraucht.

Voraussetzung ist eine **benannte pflegende Stelle**. Deshalb ist der Eigentümer hier eine Gruppe und keine Person (siehe [Eigentümerschaft und Verwaisung](#eigentümerschaft-und-verwaisung)) — eine zentrale Bibliothek, deren Zuständigkeit an einem Namen hängt, ist beim nächsten Stellenwechsel führungslos.

---

## Space-eigene Inhalte: Chats und Artefakte

### Die Grundregel: zunächst privat, sichtbar durch Teilen

Für **alle** space-eigenen Inhalte — Chats, Artefakte und alles, was später hinzukommt — gilt eine einzige Regel:

> **Was du erzeugst, gehört zunächst dir. Sichtbar für den Space wird es, wenn du es dort teilst.**

Das ist die vertrauteste Regel der Verwaltung überhaupt: Was zur Akte gegeben wird, sehen alle. Was auf dem Schreibtisch liegt, nicht. Es gibt keinen Sonderweg, keine von der Herkunft abhängige Ausnahme und keinen Modus, den man sich merken müsste.

| Status | Sichtbar für |
|---|---|
| `PRIVATE` | nur den Ersteller |
| `SHARED` | alle Mitglieder des Space |
| `SUPERSEDED` | alle Mitglieder, als überholt gekennzeichnet |
| `WITHDRAWN` | niemand außer Ersteller und Space-Admin, bleibt nachweisbar |

Das Teilen ist **eine bewusste, protokollierte Handlung** des Erstellers. Es ist die Stelle, an der er die Verantwortung für die Weitergabe übernimmt — und die einzige Stelle, an der Inhalte aus seinem Arbeitsbereich in den Leserkreis des Space übergehen.

<!-- „Teilen" bezeichnet damit zwei Vorgänge: ein Asset teilen heißt Rechte vergeben, einen Chat in
den Space teilen heißt ihn für die Mitglieder sichtbar machen. Die Objekte sind verschieden genug,
dass der jeweilige Satz eindeutig bleibt; wo Verwechslungsgefahr besteht, heißt es „in den Space
teilen". -->

**Was diese Regel kostet, ausdrücklich benannt:** Sie tauscht automatische gegen freiwillige Transparenz. Ein Chat, den niemand teilt, ist für die Organisation nicht vorhanden, und es gibt keine Garantie, dass Wertvolles geteilt wird. Die Gegenrechnung: Automatische Sichtbarkeit erzeugt Ausweichverhalten — gearbeitet wird dann in einem Raum, in dem man allein ist, oder außerhalb des Systems, und in den gemeinsamen Raum wandert nur das Vorzeigbare. Die freiwillige Variante dürfte am Ende mehr sichtbar machen als die erzwungene, aber das ist eine Annahme über Verhalten und keine Gewissheit. Das Teilen muss deshalb **ein Klick** sein und darf nie hinter einem Menü liegen.

### Chats

Ein Chat ist ein **persistentes Objekt im Space**, kein flüchtiger Kontext. Ein Space enthält n Chats.

Ein Chat entsteht als `PRIVATE` und ist ausschließlich für seinen Autor sichtbar. Erst wenn der Autor ihn in den Space teilt, sehen ihn alle Mitglieder. Damit existiert der Denkraum vor dem Teilen: die unfertige Einschätzung, die schwierige Personalsache, die dreimal gestellte Rückfrage, bei der man unsicher ist — all das findet statt, ohne dass jemand mitliest, und niemand muss dafür den Space wechseln oder auf E-Mail ausweichen.

**Konsequenz für die Nutzerführung.** Verbindlich:

- Der Chat zeigt dauerhaft seinen Status und, sobald geteilt, **wer mitliest** — im Kopfbereich mit Zugriff auf die Mitgliederliste, nicht in einem Untermenü.
- Beim Teilen wird der Leserkreis benannt, **bevor** es wirksam wird.
- Enthält der Chat Treffer aus Bibliotheken, die nicht alle Space-Mitglieder lesen dürfen, steht das als Hinweis **im Teilen-Dialog**. Kein zusätzlicher Dialog: die Information erscheint dort, wo die Entscheidung ohnehin getroffen wird.
- Der Hinweis nennt **den Eigentümer der betroffenen Bibliothek**, nicht aber Anzahlen oder Inhalte. Ohne diese Angabe kann der Teilende nicht abwägen, ob die Weitergabe vertretbar ist — er weiß sonst nur, dass „irgendetwas eingeschränkt" ist, und klickt den Hinweis weg. Der Name der verantwortlichen Stelle ist, anders als der Inhalt, kein schützenswertes Geheimnis; er erlaubt im Zweifel eine kurze Rückfrage statt einer Entscheidung in Unkenntnis.
- Der Wechsel des Space ist eine sichtbare Handlung, nie eine stillschweigende Voreinstellung.
- Der Autor kann einen geteilten Chat **zurückziehen** (`WITHDRAWN`). Das entfernt ihn aus der Space-Ansicht, löscht ihn aber nicht; bereits erfolgte Einsichtnahmen macht es nicht rückgängig.
- **Der Autor wird benachrichtigt, wenn sich der Leserkreis eines von ihm geteilten Inhalts wesentlich erweitert** — bei Aufnahme neuer Mitglieder, insbesondere externer Personen, bei Öffnung des Space und bei Zuwachs über einen Verzeichnislauf in einem gruppengebundenen Space. Ohne diese Nachricht wäre die Entscheidung zu teilen nachträglich eine andere geworden als die, die er getroffen hat: Er hat im Februar sieben Kolleginnen und den Referatsleiter zugestimmt, nicht der externen Beraterin, die im Juni dazukommt. Die Legitimation des ganzen Modells ruht darauf, dass der Ersteller weiß, was er tut; das Zurückziehen ist nur dann ein Werkzeug, wenn er von der Änderung erfährt.

Ein Chat kann an einen Agenten gebunden sein. Ist er das, bestimmt der Agent den Suchbereich; ist er es nicht, bestimmt ihn der Space.

Das Datenmodell hält von Anfang an die Achsen offen, die für Mensch+KI-Gruppenräume gebraucht werden (Teilnehmer mit Lese-/Schreibrolle, Antwort-Bezug für Threads, Erwähnungen), auch wenn diese Funktionen erst später gebaut werden.

#### Private Inhalte: der Hauptbestand des Systems

Das Modell dreht die Mengenverhältnisse um. **Nicht der private Inhalt ist die Ausnahme, sondern das Teilen.** Die meisten Chats werden nie geteilt — Rückfragen, Fehlversuche, Verworfenes. Private Inhalte sind damit der Hauptbestand, nicht der Bodensatz, und brauchen dieselbe Sorgfalt wie geteilte.

**Private Inhalte sind nicht Teil der Akte.** Dieser Satz muss vor der Einführung gesagt sein und nicht im ersten Widerspruchsverfahren auffallen. Es gilt dieselbe Aussage wie für ein persönliches E-Mail-Postfach: Was aktenrelevant ist, wird geteilt oder in eine Wissensbibliothek überführt.

#### Nichts verschwindet ohne Ansage

Niemand räumt den Schreibtisch eines Kollegen ohne Vorwarnung ab. Verbindlich:

- **Vorwarnung vor Fristablauf** an den Autor, rechtzeitig genug, um zu handeln. Das Exportrecht nützt nur, wer weiß, dass er es ausüben muss.
- **Verlängerungsmöglichkeit.** Ein Vorgang, der ein Jahr ruht, ist Verwaltungsalltag — Widerspruch, Gerichtsverfahren, Rückstellung. Ein privater Chat dazu darf nicht ablaufen, nur weil nichts passiert ist.
- **Eine Liste „deine privaten Inhalte"** für den Autor, damit ein vergessener Chat sichtbar wird, bevor die Frist ihn löscht.
- **Export schließt private Inhalte ein.**

#### Wer die Frist für private Inhalte setzt

Der überwiegende Teil aller Inhalte bleibt privat. Stellt der Nutzer deren Aufbewahrungsfrist selbst ein, läuft sie nie ab und der Bestand wächst unbegrenzt. Deshalb setzt sie der **System-Admin** — was bedeutet, dass das System Arbeit löscht, die niemand sonst je gesehen hat. Das ist nur mit den beiden Sicherungen oben vertretbar und ohne sie nicht.

#### Was beim Ausscheiden geschieht

Für Assets gilt: Der Zugang wird nie durch die Eigentumsfrage aufgehalten, das Objekt bleibt nutzbar, die Zuständigkeit wird nachgezogen. Für private Inhalte ist die Lage anders, weil niemand sie sehen darf — auch kein Nachfolger. Deshalb gilt:

1. **Im geordneten Austritt** wird der Autor vor der Deaktivierung aufgefordert, seine privaten Inhalte zu teilen oder zu exportieren. Das löst den Regelfall.
2. **Als Auffangregel** werden private Inhalte eines ausgeschiedenen Nutzers nach einer benannten Frist gelöscht.

**Private Inhalte werden nicht für Dritte lesbar gemacht** — auch nicht für Nachfolger, auch nicht im Vier-Augen-Verfahren. Die Zusage, dass dort niemand mitliest, ist zu wertvoll, um sie für den Einzelfall aufzugeben. Der Preis ist, dass eine halbfertige Einschätzung zu einem laufenden Vorgang mit dem Ausscheiden verloren geht; deshalb ist die Aufforderung zu teilen im Austrittsverfahren die eigentliche Lösung und die Löschung nur das Netz darunter.

#### Speicherbedarf ohne Auswertungspfad

Private Inhalte treiben das Speicherwachstum, und wegen des Zitierzwangs enthalten sie wörtliche Passagen aus den Quelldokumenten. Der Betrieb muss die Kapazität planen können, ohne dass jemand in fremde Daten sieht. Ein Speicherbericht je Nutzer wäre wörtlich eine Gruppierung von Chatdaten nach Person und ist damit ausgeschlossen. Stattdessen:

- **Belegung nur aggregiert je Organisationseinheit**, mit derselben Mindestgruppengröße wie die Nutzungsstatistik.
- **Eine technisch durchgesetzte Obergrenze je Konto statt eines Berichts.** Eine Quote braucht keinen Auswertungspfad: Das System setzt sie durch und meldet sie **dem Betroffenen selbst**. Damit ist auch der Ausreißerfall beherrschbar — ein Konto, das durch eine fehlgeschlagene Automatisierung Millionen private Chats erzeugt.

#### Was über private Inhalte nicht angezeigt wird

Es gibt **keinen Zähler, keine Aktivitätsanzeige und keine Fortschrittsanzeige** über die privaten Inhalte einer anderen Person. Ob das Teilen überhaupt genutzt wird, ist nur **aggregiert je Organisationseinheit oberhalb der Mindestgruppengröße** messbar — nie je Person und nie je Space unterhalb der Schwelle. Das ist ein bewusst gezahlter Preis: Genauer zu messen hieße, die Zusage zu brechen, die das Konzept gegenüber der Personalvertretung trägt.

### Chats sind vor fremder Löschung geschützt

Für Chats gilt dasselbe wie für Assets: **Zurückziehen statt Löschen.** Ein Space-Admin kann einen geteilten Chat aus dem Space entfernen — er kann ihn nicht beseitigen. Der Chat bleibt für seinen Autor und im Nachweis erhalten, die Entfernung wird mit Grund protokolliert.

Der Grund ist derselbe wie bei Assets: Ein Chat kann eine fachliche Einschätzung dokumentieren, auf die sich später jemand beruft. Es wäre nicht vertretbar, für Assets „Rückruf durch Deaktivieren, nie durch Löschen" zu fordern und die Arbeitsspuren der Beschäftigten schlechter zu stellen.

Der Autor kann seinen Chat jederzeit exportieren.

### Artefakte

In einem Space entstehen Ergebnisse: eine Excel-Auswertung, ein Diagramm, später Berichte, Entwürfe und Analysen. Für sie gilt **dieselbe** Regel wie für Chats — sie sind zunächst privat und werden durch Teilen space-sichtbar. Kein Sonderweg, keine Abhängigkeit von der Herkunft der Daten.

Die Objektklasse ist bewusst allgemein gehalten, damit weitere Ergebnistypen ohne Modelländerung hinzukommen können.

#### Lebenszyklus

- **Zuordnung:** Jedes Artefakt kennt den Chat, aus dem es entstanden ist, und seinen Erzeugungszeitpunkt.
- **Versionierung:** Ein neues Artefakt kann ein bestehendes ersetzen. Das ersetzte wird `SUPERSEDED`, bleibt aber auffindbar.
- **Herkunftskennzeichnung:** Jedes Artefakt zeigt, aus welchen Bibliotheken es abgeleitet wurde, und ist auf seinen Ursprungs-Chat rückführbar.
- **Zurückziehen:** durch Ersteller und Space-Admin, mit Grund protokolliert — kein Löschen.
- **Aufbewahrung:** Je Space konfigurierbare Frist, damit Spaces nicht unbegrenzt wachsen. Private Inhalte unterliegen ihr ebenso. Die Frist ist mit den Fach- und Rechtsbehelfsfristen abzustimmen: Sonst wird genau die Spur gelöscht, die nach dem Grundsatz „deaktivieren statt löschen" für ein laufendes Widerspruchsverfahren erhalten bleiben soll. Das Produkt warnt, wenn die eingestellte Frist kürzer ist als die konfigurierte Rechtsbehelfsfrist — analog zur Warnung bei der Audit-Frist.
- **Übergang ins Wissen:** Ein Artefakt kann in eine Wissensbibliothek übernommen werden und wird dabei zu einem Dokument. **Ab dann gelten die Rechte der Bibliothek, nicht mehr die des Space.** Das ist der Rückweg aus der space-eigenen in die assoziierte Welt und der einzige Weg, auf dem ein Ergebnis dauerhaft und rechtegeführt wird.

### Warum Chats und Artefakte keine Assets sind

Der Gedanke, alles einheitlich als Asset zu modellieren, ist naheliegend, trägt aber nicht:

1. **Kardinalität und Lebenszyklus passen nicht.** Assets sind wenige, benannt, kuratiert, versioniert, katalogfähig. Chats und Artefakte sind viele, oft unbenannt, häufig wegwerfbar. Katalog, Freigabe-Workflow, Export/Import und Agenten-Prüfstand sind auf sie nicht anwendbar.
2. **Die Beziehung ist eine andere.** Ein Asset liegt in 0..n Spaces, optional. Ein Chat liegt in genau einem Space, zwingend. Das ist Komposition, nicht Assoziation.
3. **Die Rechtelogik wäre die falsche.** Wäre ein Chat ein Asset, gewährte der Space nichts an ihm — die Chats eines Space wären für seine Mitglieder unsichtbar. Das ist das Gegenteil des Gewollten.
4. **Das Sicherheitsprofil ist asymmetrisch.** Ein geteiltes Asset gibt eine *Fähigkeit* weiter, ein geteilter Chat oder ein Artefakt gibt *Ergebnisse* weiter. Dafür braucht es eigene Regeln, die nicht vom Asset geerbt werden dürfen.

---

## Dokumente und rechtebewusste Suche

### Dokumente liegen in Bibliotheken

**Umsetzungsstand:** Die Wissensbibliothek als Container ist mit #201 umgesetzt (Migration 012 in
[docs/migrations/012-knowledge-library.md](../migrations/012-knowledge-library.md)) — Eigentümerschaft
(Nutzer oder Gruppe), Organisationsgrenze, Sichtbarkeitsstufen, `listed`-Flag und die Zuweisung jedes
bestehenden Dokuments an eine System-Bibliothek. Die abgestuften Asset-Rollen weiter unten
(`VIEWER`/`EDITOR`/`MANAGER`/`OWNER`) und die rechtebewusste Vektorsuche folgen mit #202 — bis
dahin gilt eine grobe Zugriffslogik (Eigentümer, Gruppenmitglied, `ORGANIZATION`-Sichtbarkeit,
System-Admin), dokumentiert auf `KnowledgeLibraryService`.

Der Dokumentencontainer ist die Wissensbibliothek, nicht der Space. Jedes Dokument gehört zu genau einer Bibliothek; jeder Chunk trägt die Bibliotheks-Kennung als Filterachse.

Das ist einfacher als das bisherige Konzept: Aus einer n:m-Beziehung Dokument→Workspaces wird eine n:1-Beziehung Dokument→Bibliothek. Die Mehrfachverwendung wandert eine Ebene höher — eine Bibliothek ist in mehreren Spaces assoziiert —, wo sie nichts kostet, weil sie nicht je Chunk materialisiert werden muss.

### Durchsetzung zur Abfragezeit

Die Berechtigungsprüfung ist **Teil der Vektorsuche**, kein Nachfilter. Die Menge der lesbaren Bibliotheken des Nutzers wird als Metadatenfilter übergeben; unberechtigte Chunks werden nie geladen und nie gerankt.

### Suchbereich je Chatart

| Chatart | Suchbereich |
|---|---|
| Chat ohne Agent in Space S | assoziierte Bibliotheken von S **geschnitten mit** den lesbaren Bibliotheken des Nutzers |
| Chat mit Agent A | die vom Agenten gebundenen Bibliotheken **geschnitten mit** den lesbaren Bibliotheken des Nutzers |

In beiden Fällen ist der Rechtekontext derselbe — der des aufrufenden Nutzers. Es gibt keinen zweiten.

Der Space **verengt** den ungebundenen Chat, **verengt aber nicht den Agenten**. Diese Asymmetrie ist beabsichtigt: Nur wenn die Wissensbindung eines Agenten unabhängig davon ist, wo er ausgeführt wird, bleibt ein Agenten-Release versionierbar und prüfbar. Würde der Space zusätzlich verengen, antwortete dieselbe geprüfte Fassung eines Agenten je nach Space anders — und ein Prüfbericht würde wertlos.

**Ein Space ohne assoziierte Bibliotheken verengt nicht:** Dort ist der Suchbereich **alles, was der Nutzer lesen darf**. Das ersetzt die frühere Sonderregel für den persönlichen Space und kommt ohne Space-Art aus — in einem Raum, in dem nichts kuratiert wurde, gibt es nichts zu verengen. Damit steht ein Raum, in dem jemand allein arbeitet, fachlich nie schlechter da als ein gemeinsamer, und die Möglichkeit, unbeobachtet zu arbeiten, ist keine bloß formale: Wer dorthin ausweicht, verliert keinen Zugang zu Wissen.

Ein Space, in dem der Nutzer auf keine assoziierte Bibliothek Zugriff hat, ist ein **zulässiger Zustand, kein Fehler**: Der Suchbereich ist leer, und im Zitierzwang-Modus verweigert das System folgerichtig die Antwort. Die Meldung darf dabei **keine Anzahlen nennen**. Zulässig: „In diesem Space ist für dich derzeit kein Wissen verfügbar." Unzulässig: „3 von 4 Bibliotheken sind für dich gesperrt."

### Einen Agenten weitergeben: die Freigabekette

Ein Agent, der beim Empfänger nichts findet, ist wertlos. Die Produktvision verspricht deshalb, dass ein geteilter Agent „sein Wissen mitbringt". Der **Regelweg** dafür ist nicht, dass der Agent mit fremden Rechten liest, sondern dass **die Freigabe des Agenten die Freigabe seines Wissens nach sich zieht**.

Ablauf beim Teilen eines Agenten:

```
1. Der Agenten-Eigentümer gibt den Agenten an eine Person oder Gruppe frei.
2. Das System zeigt an, welche Wissensbibliotheken der Agent benötigt
   und ob der Empfänger darauf bereits Zugriff hat.
3. Für jede fehlende Bibliothek geht eine Anfrage an deren Eigentümer.
4. Jeder Bibliotheks-Eigentümer gibt mit frei oder lehnt ab —
   die Mitfreigabe ist nichts anderes als ein zusätzlicher Grant.
5. Ergebnis wird zurückgemeldet: "3 von 3 Bibliotheken mitfreigegeben"
   oder "2 von 3 — der Agent arbeitet beim Empfänger eingeschränkt".
```

Kein neuer Mechanismus, sondern die vorhandene Rechtevergabe an der richtigen Stelle sichtbar gemacht. Ein Agent, dessen Kette vollständig durchlief, funktioniert beim Empfänger garantiert.

**Bewusst in Kauf genommene Konsequenz:** Der Empfänger bekommt damit auch **Lesezugriff auf das Rohmaterial**, nicht nur auf die Antworten des Agenten. Das ist beabsichtigt — es ist der ehrliche Weg, weil der Empfänger die Antworten ohnehin fachlich verantworten muss und dafür die Quelle prüfen können soll. Der Bibliotheks-Eigentümer entscheidet in Schritt 4 in voller Kenntnis dieser Wirkung.

Eine Teilablehnung blockiert die Weitergabe nicht. Der Agent wird geteilt und arbeitet mit dem, was freigegeben wurde; Empfänger und Absender sehen, welcher Teil fehlt.

**Nicht-Reaktion ist der Regelfall, nicht die Ablehnung** — Urlaub, unklare Zuständigkeit, Postfach ohne Betreuung. Ohne Behandlung hinge ein Agent dauerhaft unvollständig, ohne dass jemand weiß, woran es liegt. Deshalb:

- Jede Anfrage hat eine **Frist**. Läuft sie ab, erscheint sie auf der Governance-Arbeitsliste des System-Admins.
- Der Zustand ist für beide Seiten **jederzeit sichtbar**: „wartet seit 6 Tagen auf Referat 34".
- **Der Empfänger sieht am Agenten selbst**, dass eine Wissensquelle fehlt — nicht erst an schlechteren Antworten. Sonst hält er den Agenten für untauglich, statt zu erkennen, dass eine Freigabe aussteht.

**Ehrlich zu benennen ist der Preis dieser Kette:** Wer einen Agenten annimmt, erhält Lesezugriff auf das *gesamte* Rohmaterial der beteiligten Bibliotheken, nicht nur auf das, was der Agent tatsächlich verwendet. Ein Eigentümer, der seinen Bestand nicht öffnen will, wird ablehnen — und der Agent ist dann nicht teilbar. Diese Folge muss dem Eigentümer **im Moment seiner Entscheidung** angezeigt werden, und sie darf beim Bewerben des Produkts nicht verschwiegen werden: Geteilte Agenten funktionieren garantiert nur dort, wo die Kette vollständig durchläuft.

### Ein Agent liest immer mit den Rechten des Nutzers

**Ein Agent ruft Wissen ausschließlich mit den Rechten des aufrufenden Nutzers ab.** Es gibt keinen Umschalter, keinen Modus „Agent liest mit eigenen Rechten" und keine admin-aktivierbare Sonderoption. Die Rechteschicht hat an dieser Stelle **keinen Umgehungsweg**.

Diese Frage wurde geprüft und bewusst so entschieden. Die naheliegende Alternative — der Agent liest mit seinen eigenen Rechten, damit er beim Empfänger sicher etwas findet — ist mit der Freigabekette überflüssig geworden: Es gibt genau einen Weg, auf dem ein geteilter Agent beim Empfänger funktioniert, nämlich die ordentliche Mitfreigabe des benötigten Wissens. Ein zweiter Mechanismus wäre redundant und wäre zugleich der riskantere von beiden, weil er dauerhaft Inhalte an Personen liefert, die sie nicht sehen dürfen.

**Die daraus folgende Einschränkung ist gewollt und keine offene Lücke:**

> Ein Agent, dessen Wissensbibliothek nicht freigegeben werden soll oder darf, ist **nicht teilbar**. Wer seine Arbeitsweise trotzdem in einem anderen Referat einsetzen will, legt dort einen eigenen Agenten mit eigener Bibliothek an.

Das ist der Preis dafür, dass es keinen Kanal gibt, über den Wissen an der Rechteschicht vorbeifließt. Er wird bewusst gezahlt.

**Nebeneffekt für die Umsetzung:** Die Vektorsuche braucht nur **einen** Rechtekontext — den des aufrufenden Nutzers. Sie kann ihn dem Sicherheitskontext entnehmen und muss ihn nicht als Parameter durchreichen. Das vereinfacht die Signatur der Retrieval-Schicht und entfernt eine Fehlerquelle, die sonst jede spätere Erweiterung mitgeschleppt hätte.

### Konnektoren und Quellzuordnung

Eine Konnektor-Quelle wird **genau einer** Wissensbibliothek zugeordnet. Wird derselbe Bestand an mehreren Stellen gebraucht, wird die Bibliothek assoziiert und nicht das Dokument vervielfacht.

Damit verschiebt sich eine Verantwortung: Bisher entschied der System-Admin mit dem Quell-Mapping zugleich, wer die Dokumente sieht. Künftig entscheidet er nur, in welche Bibliothek indiziert wird; wer sie sieht, entscheidet der Bibliotheks-Eigentümer. Das ist die richtige Trennung von Technik und Fachlichkeit — braucht aber eine Sicherung: Bibliotheken, die aus einem Konnektor gespeist werden, tragen eine vom System-Admin gesetzte **Obergrenze der Freigabe**. Sonst könnte ein Bibliotheks-Eigentümer Bestände organisationsweit freigeben, die ein System-Admin eingespeist hat.

Der Ausschluss einzelner Konnektor-Dokumente bleibt inhaltlich unverändert, wandert aber vom Workspace in die Bibliothek. Er wirkt damit an genau einer Stelle statt je Workspace.

---

## Das Ableitungsleck

Wissen fließt aus einer Bibliothek mit **engem** Leserkreis in ein space-eigenes Objekt mit **weiterem** Leserkreis. Dabei wechselt der Rechteanker: von der Asset-Rechteliste zur Space-Mitgliedschaft. Der Zitierzwang verschärft das, weil wörtliche Passagen dauerhaft im Verlauf stehen.

### Warum das Problem klein geworden ist

Mit der Grundregel (siehe [Space-eigene Inhalte](#die-grundregel-zunächst-privat-sichtbar-durch-teilen)) ist der Übergang **kein automatischer Vorgang mehr, sondern eine Handlung**. Nichts fließt in den Leserkreis des Space, ohne dass ein Mensch es dorthin legt — und dieser Mensch ist immer jemand, der die Inhalte selbst lesen durfte.

Das entspricht dem Verwaltungshandeln: Wer etwas lesen darf, darf es seinen Kollegen **im Rahmen der Zweckbindung des Bestands** berichten und verantwortet das. Die Einschränkung ist wesentlich und nicht bloß vorsichtshalber angefügt: Bei Sozialdaten, Personalakten und Steuerdaten ist die Weitergabe an nicht zuständige Kollegen gerade nicht zulässig, auch wenn der Zugriff selbst rechtmäßig war. Das Teilen entbindet niemanden von der Zweckbindung, die für den Bestand ohnehin gilt. Neu ist nur, dass die Weitergabe sichtbar, zurechenbar und protokolliert ist statt beiläufig.

**Wichtig — das Leck ist damit nicht verschwunden, sondern in einen verantworteten Akt überführt.** Ein geteilter Chat kann weiterhin Passagen enthalten, die andere Space-Mitglieder nie hätten öffnen dürfen. Der Unterschied ist, dass es jetzt eine Person gibt, die diese Entscheidung getroffen hat, und einen Zeitpunkt, an dem sie getroffen wurde.

### Was daraufhin entfallen ist

Drei Mechanismen aus einem früheren Entwurf sind ersatzlos gestrichen, weil sie den Kanal an einer Stelle absichern sollten, an der er sich gar nicht mehr öffnet:

| Entfallen | Grund |
|---|---|
| **Bestätigungspflicht des Space-`CURATOR` bei gemischter Assoziation** | Die Assoziation setzt niemanden mehr etwas aus. Sie stellt eine Bibliothek bereit; sichtbar wird ein Ergebnis erst durch das Teilen |
| **Dauerhafte Kennzeichnung gemischter Spaces** | In einer realen Behörde fallen Leserkreise praktisch nie exakt zusammen — ein Teilzeitbeschäftigter, eine externe Kraft, ein Abgeordneter genügt. Damit wäre so gut wie *jeder* Space gekennzeichnet, und ein Warnzeichen, das an allem klebt, informiert über nichts |
| **Herkunftsabhängiger Sonderweg bei Artefakten** | Ein Ergebnis war mal sofort sichtbar und mal nicht, ohne dass der Ersteller den Unterschied erklären konnte. Jetzt gilt für alles dieselbe Regel |

Was **bleibt**, ist das Billige und Wirksame:

1. **Herkunftsverfolgung.** Jeder Chat und jedes Artefakt führt mit, aus welchen Bibliotheken tatsächlich Treffer stammten — Grundlage für den Hinweis im Teilen-Dialog, für den Nachweis und für die Kennzeichnung am Artefakt.
2. **Hinweis im Teilen-Dialog**, wenn der Inhalt aus Bibliotheken stammt, die nicht alle Mitglieder lesen dürfen — ohne Anzahlen, ohne Namen, und ohne zusätzlichen Dialog: die Information steht dort, wo die Entscheidung ohnehin fällt.
3. **Zitat-Sprungmarken bleiben rechtegeprüft.** Der Sprung in das Quelldokument wird beim Klick gegen die Rechte des Lesenden geprüft und gegebenenfalls verweigert. Das verhindert das Weiterhangeln vom Zitat in den vollen Bestand.
4. **Benachrichtigung des Bibliotheks-Eigentümers**, wenn seine Bibliothek in einem Space bereitgestellt wird, dessen Mitglieder nicht sämtlich Lesezugriff haben (siehe [Assets in einen Space assoziieren](#assets-in-einen-space-assoziieren)).

### Warum Zitat-Redaktion weiterhin nicht gebaut wird

Naheliegend wäre, Zitate beim Lesen gegen die Rechte des Lesenden zu maskieren. Das ist **unvollständig, nicht bloß aufwendig**:

1. **Der Antworttext bleibt.** Der eigentliche Kanal ist nicht das wörtliche Zitat, sondern die Aussage, die das Modell daraus gebildet hat. Maskiert man nur das Zitat, bleibt die Information erhalten — nur schlechter belegt. Maskiert man auch die Aussage, bleibt vom Chat nichts übrig.
2. **Sie zerstört den gemeinsamen Arbeitsraum.** Jeder sähe einen anderen Verlauf.
3. **Sie verlagert die Prüfung auf jeden Lesevorgang** statt auf den einen Entstehungsvorgang.

### Der Strikt-Modus

Für die wenigen Räume, in denen eine technische Zusicherung gebraucht wird statt einer verantworteten Handlung, gibt es den **Strikt-Modus je Space**. Er ist standardmäßig aus und wird vom System-Admin oder vom Space-Verantwortlichen gesetzt — empfohlen für Räume wie Rechnungsprüfung, Revision oder Personal.

In einem Strikt-Space gilt:

- Es dürfen nur Bibliotheken assoziiert werden, deren Leserkreis **alle** Space-Mitglieder umfasst.
- **Ein Agent, dessen gebundene Bibliotheken nicht sämtlich zu dieser Menge gehören, kann in diesem Space nicht aufgerufen werden.**
- **Eine Mitgliederaufnahme, die die Voraussetzung brechen würde, wird an Ort und Stelle abgelehnt**, mit dem Hinweis, welche Freigabe fehlt. Das ist der einzige Auslöser, den der Handelnde selbst kontrolliert und vorher sieht; ohne die Prüfung sperrt ein Space-Admin mit der Aufnahme einer neuen Kollegin im selben Moment seinen eigenen Raum für alle Mitglieder.

Der zweite Punkt schließt eine Lücke, die eine frühere Fassung offen ließ: Der Space verengt den Suchbereich eines Agenten nicht (siehe [Suchbereich je Chatart](#suchbereich-je-chatart)), weil ein geprüftes Agenten-Release sonst nicht mehr reproduzierbar wäre. Ein Agent könnte damit im Strikt-Space aus einer engen, dort nicht assoziierten Bibliothek liefern.

Die Auflösung ist **weder den Agenten zu verengen noch die Zusicherung zurückzunehmen, sondern den Aufruf zu verweigern**: Der Agent läuft entweder mit seiner vollständigen, reproduzierbaren Bindung oder gar nicht. Die Reproduzierbarkeit bleibt unangetastet, weil sein Suchbereich nie stillschweigend verkleinert wird — er wird nur an einem Ort nicht zugelassen. Der Nutzer erhält einen klaren Hinweis, dass dieser Agent in diesem Raum nicht verwendet werden darf.

### Wenn die Voraussetzung eines Strikt-Space nachträglich bricht

Die Bedingung „alle Mitglieder dürfen alles lesen" hängt an Größen, die sich außerhalb des Space ändern: eine Verzeichnissynchronisation entfernt jemanden aus einer Gruppe, ein Bibliotheks-Eigentümer nimmt einen Grant zurück, eine Freigabe-Obergrenze wird gesenkt.

Das System löst dann **weder Assoziationen automatisch** (das entzöge einem ganzen Team sein Wissen, weil eine Person versetzt wurde) **noch entfernt es Mitglieder** (das koppelte eine Rechteänderung an einer Bibliothek an die Mitgliedschaft in einem Arbeitsraum). Stattdessen geht der Space in den Zustand **„Voraussetzung verletzt"**:

- Bestehende Inhalte bleiben unangetastet und lesbar.
- **Neues Teilen und Agentenaufrufe sind gesperrt**, bis der Zustand behoben ist.

Das ist fail-closed für neue Exposition, ohne etwas zu zerstören, und es gibt keinen stillschweigenden Zustandswechsel im Hintergrund. Ein Sperrzustand ohne Zuständigen wäre allerdings nur die halbe Regelung — er erzeugt **Arbeitsstillstand**, und zwar in genau dem Raum, für den dieses Dokument den Strikt-Modus empfiehlt. Deshalb gilt derselbe Zuschnitt wie bei „Nachfolge offen":

- **Benannter Adressat und Frist.** Der Vorgang erscheint mit Frist auf der Governance-Arbeitsliste des System-Admins. Ohne das hängt die Arbeitsfähigkeit einer Prüfstelle an der Reaktionszeit eines Referatsleiters, den sie unter Umständen gerade prüft.
- **Der System-Admin erhält eine Liste mit Liegezeit** — wie bei der Nachfolge. Sonst sieht niemand, wie viele Räume betroffen sind und wie lange schon, und ein Space kann monatelang gesperrt daliegen, weil eine Nachricht im Urlaub ankam.
- **Die Neubewertung wird von jeder Rechteänderung an jeder Bibliothek ausgelöst, die in irgendeinem Strikt-Space bereitgestellt ist.** Sonst löst sich der Zustand nicht von selbst, wenn seine Ursache wegfällt, sondern bleibt hängen, bis jemand einen Knopf drückt.
- **Die Ursache geht im Klartext an den Space-Verantwortlichen:** welche Bibliothek, welches Ereignis, welcher Zeitpunkt.
- **Die Meldung an den Nutzer weist den Zustand als fachlichen Vorgang aus, nicht als Störung**, und benennt die zuständige Stelle. Sinngemäß: „Dieser Raum ist gesperrt, weil eine Zugriffsvoraussetzung nicht mehr erfüllt ist. Zuständig ist der Space-Verantwortliche."
- **Der Zustandswechsel ist ausdrücklich kein Bereitschaftsereignis.** Ein fail-closed-Zustand, der nachts eintritt und tagsüber fachlich aufgelöst wird, ist richtig — aber nur, wenn niemand nachts daran zieht und zur „Behebung" eine Mitgliedschaft zurücksetzt.
- Der Vorgang steht im Protokoll.

---

## Modell-Policies

Modell-Policies sind **ausschließlich Obergrenzen**. Keine Ebene kann erweitern, was eine andere eingeschränkt hat:

```
erlaubte Modelle = Systempolicy
                 ∩ Space-Policy
                 ∩ Policy jeder Bibliothek im Suchbereich
                 ∩ Policy des Agenten
```

Die Bibliothek trägt ihre Obergrenze **selbst mit sich**. Das ist wesentlich: Unter diesem Modell hat der Space keine Hoheit über die Bibliotheken, die in ihm auftauchen — jeder Space-`CURATOR` kann jede Bibliothek assoziieren, auf die er Zugriff hat. Eine Bibliothek mit besonders geschützten Daten kann also in einem Space landen, dessen Policy Cloud-Modelle erlaubt. Eine ausschließlich space-gebundene Policy schützt genau diesen Fall nicht.

> **Datenschutzrelevante Modellbeschränkungen gehören an die Daten, nicht an den Raum.**

Die Space-Policy bleibt sinnvoll — ein Space kann strenger sein als das Haus —, aber sie ist nicht die Sicherung.

---

## Organisation als Mandantengrenze

Space, Asset, Nutzer und Gruppe tragen eine Organisations-Zugehörigkeit. **Kein Grant, keine Assoziation, kein Katalogtreffer und keine Suche überschreitet je eine Organisationsgrenze — auch nicht für System-Admins**, die pro Organisation existieren.

In der ersten Ausbaustufe gibt es genau eine Organisation. Die Ebene wird trotzdem jetzt eingezogen, weil eine nachträglich eingefügte Mandantengrenze jede Rechteabfrage, jeden Grant und jede Katalogsuche berührt.

Damit ruht die Mandantentrennung nicht mehr auf dem Space. Das ist notwendig, weil der Space bewusst kein Sicherheitssilo mehr ist.

---

## Was aus der Zusage zur Nicht-Sichtbarkeit wird

Die bisherige Zusage lautete: *„Der Nutzer weiß nie, dass Dokumente existieren, auf die er nicht zugreifen kann."* Sie gilt differenziert weiter:

| Ebene | Gilt |
|---|---|
| **Chunk / Suche** | **Unverändert.** Der Filter über die Bibliothek ist Teil der Vektorsuche; unberechtigte Chunks werden nie geladen und nie gerankt |
| **Asset / Katalog** | **Umformuliert:** Der Nutzer sieht nur Assets, auf die er Zugriff hat, oder die bewusst zur Auffindbarkeit veröffentlicht wurden |
| **Space-Ansicht** | Nur zugängliche Assets; Meldungen nennen keine Anzahlen |
| **Agent** | **Unverändert.** Ein Agent liest immer mit den Rechten des Nutzers; es gibt keinen Umgehungsweg |
| **Privater Inhalt** | **Unverändert.** Ein nicht geteilter Chat oder ein nicht geteiltes Artefakt ist ausschließlich für seinen Ersteller sichtbar — auch für Space-Admins und System-Admins nicht |
| **Geteilter Inhalt** | **Gilt nicht — und das ist eine bewusste Handlung.** Wer teilt, gibt weiter, was er selbst lesen durfte, und verantwortet das. Der Vorgang wird protokolliert |

---

## Migration vom Workspace-Modell

| Bestand | Behandlung |
|---|---|
| Persönliche Workspaces | werden der Standard-Space des Nutzers (`isDefault`); zusätzlich entsteht je Nutzer eine persönliche Wissensbibliothek „Meine Dokumente", die dort assoziiert wird |
| Gemeinsame Workspaces | werden gewöhnliche Spaces mit `memberSource = MANUAL` |
| Mitgliedschaften | `VIEWER→MEMBER`, `EDITOR→CURATOR`, `ADMIN→ADMIN`, `OWNER→ADMIN`; die Verantwortlichkeit steckt bereits im `ownerId`-Attribut |
| Bestehende Dokumente | haben heute **keine** Workspace-Zuordnung. Sie werden einer System-Bibliothek zugewiesen, die zunächst **nur für System-Admins lesbar** ist. Eine organisationsweit lesbare Voreinstellung wäre in einer Verwaltungsumgebung nicht vertretbar |
| Global eindeutige Namen | entfallen |
| Endpunkt für Workspace-Dokumente | war nie implementiert und entfällt ersatzlos; Nachfolger ist der Bibliotheks-Endpunkt |

Günstige Ausgangslage: Dokumente tragen heute **keine** Workspace-Zuordnung, und die Suche filtert nicht. Der Rechteanker wird also nicht verschoben, sondern erstmals eingezogen.

---

## Mitbestimmung und Personalvertretung

In einer deutschen Behörde ist die Mitbestimmung ein **Einführungshindernis, kein Randthema**: Ohne Dienstvereinbarung beginnt kein Rollout. Das Produkt hat deshalb die Aufgabe, die Dienstvereinbarung zu einer Konfigurationsaufgabe zu machen statt zu einem Projektrisiko.

### Drei Datenquellen mit Personenbezug

| Quelle | Was daraus ableitbar ist |
|---|---|
| **Abgelegte Chats und Artefakte** | wer im Space womit gearbeitet hat |
| **Nutzungsstatistik je Asset** | wer welches Asset wie oft nutzt — von der Produktvision als Steuerungsinstrument gewollt |
| **Audit-Log** | **die eigentliche Quelle** — ein zeitgestempelter Tätigkeitsnachweis, der unabhängig von jeder Statistikfunktion anfällt |

Beides zusammen ist geeignet, Verhalten und Leistung von Beschäftigten abzubilden. Damit liegt der Mitbestimmungstatbestand der Einführung und Anwendung technischer Einrichtungen nahe, die zur Überwachung von Verhalten oder Leistung **bestimmt oder geeignet** sind (BPersVG und die entsprechenden Landespersonalvertretungsgesetze). Das ist eine Einschätzung aus Produktsicht und keine Rechtsberatung; die Bewertung obliegt der einführenden Stelle.

**Die dritte Quelle ist die entscheidende, und sie wurde zunächst unterschätzt.** Eine abschaltbare Nutzungsstatistik schützt nicht, wenn das Audit-Log denselben Sachverhalt ohnehin erhebt: Wer wann wie oft gearbeitet hat, in welchem Sachgebiet und von welcher Netzadresse aus, ergibt sich unmittelbar aus den Protokollsätzen. Die Abschaltbarkeit betrifft dann nur die bequeme Auswertung, nicht die Erhebung — und was heute abgeschaltet ist, ist morgen eingeschaltet, mit rückwirkend auswertbaren Daten von gestern.

### Kein personenbezogener Auswertungspfad

Deshalb die schärfste Festlegung dieses Kapitels:

> **Es gibt keine Schnittstelle und keine Oberfläche, die Nutzungs-, Chat- oder Herkunftsdaten nach Person filtert, gruppiert oder sortiert. Diese Funktion ist nicht abschaltbar vorhanden — sie existiert nicht.**

Eine Funktion, die es nicht gibt, kann niemand einschalten. Für eine Dienstvereinbarung ist das der Unterschied zwischen einer Zusage und einer Tatsache, und der Bau kostet weniger als das Nachrüsten der Kontrollen, die eine vorhandene Funktion sonst braucht.

Zwei Wege bleiben notwendigerweise offen, und beide sind kein Auswertungspfad:

- **Selbstauskunft.** Jede Person kann ihre eigenen Daten einsehen und exportieren. Das ist keine Überwachung, sondern das Auskunftsrecht der betroffenen Person. Sie ist **nicht delegierbar**: Der Export geht an die Person selbst und ist für niemanden sonst auslösbar — weder über eine Vertretungsfunktion noch durch einen Admin „im Auftrag“ noch in ein fremdes Postfach.
- **Anlassbezogene Klärung** bei einem konkreten Sicherheitsvorfall — über den Audit-Pfad, mit dokumentiertem Anlass, im Vier-Augen-Prinzip unter Beteiligung der Personalvertretung und mit eigenem Protokolleintrag über den Zugriff. Ein Produkt ohne jede Möglichkeit, einen Vorfall aufzuklären, wäre nicht betreibbar; ein Produkt, in dem diese Aufklärung der Normalweg ist, wäre nicht zustimmungsfähig.

**Diese Ausnahme ist inhaltlich begrenzt, nicht nur formal.** Nach der Streichung des Auswertungspfads ist sie der einzige verbliebene Weg von den Daten zu einer Person — und damit auch der einzige, der an die Verhaltensspur aus dem privaten Arbeitsbereich heranreicht. Alles, was jemand wissen will, drückt künftig durch dieses eine Nadelöhr; ein dokumentierter Anlass ist schnell geschrieben, und zwei Augen sind schnell gefunden, wenn beide derselben Leitung berichten. Deshalb gilt:

1. **Zweckausschluss.** Der Pfad steht für arbeitsrechtliche, disziplinarische und leistungsbezogene Fragen **nicht** zur Verfügung — auch dann nicht, wenn ein Sachverhalt beides berührt.
2. **Umfangsbegrenzung vorab.** Person, Zeitraum und Zweck werden vor der Freigabe festgelegt und begrenzen die Abfrage **technisch**. Sonst klärt man einen Vorfall vom Mai und liest dabei zwei Jahre.
3. **Unterrichtung der betroffenen Person** nach Abschluss, mit Anlass und Umfang — außer die Klärung richtet sich gegen einen Dritten.
4. **Jahresbericht an die Personalvertretung:** Zahl der Fälle und Anlässe in Kategorien, ohne Namen.

### Stellschrauben, die das Produkt anbietet

| Stellschraube | Ausprägung |
|---|---|
| **Aggregation statt Personenbezug** | Nutzungsstatistiken nur je Organisationseinheit, mit Mindestgruppengröße; unterhalb der Schwelle wird der Wert **unterdrückt**, nicht angezeigt |
| **Keine Ranglisten** | Kein Vergleich einzelner Beschäftigter, keine Bestenlisten, keine Aktivitätsbewertung — auch nicht als spielerisches Element |
| **Abschaltbarkeit** | Nutzungsstatistiken je Asset und Organisationseinheit vollständig deaktivierbar, ohne dass die Fachfunktion leidet |
| **Aufbewahrung mit Ober- und Untergrenze** | Für Chats, Artefakte, Herkunftsdaten **und das Audit-Log**: konfigurierbare Frist mit einer **Höchstdauer**, nicht nur einer Mindestdauer, und automatischer Löschung nach Ablauf |
| **Datensparsamkeit im Protokollsatz** | Die Netzadresse ist **nicht Teil des Standardsatzes**. Sie ist ein Anwesenheitsmerkmal, weil sie Dienststelle von Homeoffice unterscheidet. Wird sie für Sicherheitszwecke benötigt, wird sie ausdrücklich eingeschaltet, begründet und aus Berichten und Exporten ausgeschlossen |
| **Zweckbindung** | Für jede erhobene Angabe ist dokumentiert, wofür sie da ist — **auch für das Audit-Log und die Herkunftsverfolgung**, nicht nur für Kennzahlen |
| **Abschließend geregelter Audit-Zugriff** | Benannter Personenkreis, dokumentierter Anlass, technisch durchgesetzte Trennung der Auswertungswege für Revision und Dienststellenleitung; der Audit-Zugriff ist selbst protokolliert |
| **SIEM-Export ist keine Umgehung** | Was exportiert wird, unterliegt denselben Zweck- und Zugriffsregeln und derselben Datensparsamkeit; andernfalls verliert die Trennung ihren Sinn |
| **Governance-Änderungen sind protokollpflichtig** | Jede Änderung an Aufbewahrung, Aggregation, Statistik oder Audit-Einstellungen wird protokolliert und angezeigt — sonst bleibt eine spätere Abweichung von der Dienstvereinbarung unbemerkt |
| **Auskunft für die Personalvertretung** | Export, welche personenbeziehbaren Daten erhoben werden, in welcher Granularität und wie lange sie liegen — vor dem Rollout einmal vollständig vorlegbar |

### Wirkung der Grundregel

Sie ist zugleich die wichtigste Antwort auf die häufigste Sorge der Beschäftigten. Weil ein Chat erst durch eine **Handlung seines Autors** sichtbar wird, entsteht Sichtbarkeit nicht mehr nebenbei:

- Die dreimal gestellte Rückfrage zu einer Rechtsgrundlage, an der jemand unsicher ist, bleibt privat und wird nicht zur dauerhaft sichtbaren Wissenslücke in Schriftform.
- Die Führungskraft, die in aller Regel Mitglied des gemeinsamen Space ist, sieht geteilte Arbeitsergebnisse — nicht den Arbeitsweg dorthin.
- Wer teilt, tut es bewusst; das ist gegenüber der Personalvertretung darstellbar, anders als eine Sichtbarkeit, die im Hintergrund entsteht.

### Private Inhalte sind unbeobachtet

Ausdrücklich zugesagt und nicht nur als Nebenwirkung gemeint:

- **Alle privaten Inhalte** sind für System-Admins, Revision und Dienststellenleitung **nicht lesbar** — in jedem Space, nicht nur in einem dafür vorgesehenen. Ein System-Admin kann im Rahmen des Offboardings einen Space deaktivieren; er kann ihn nicht einsehen.
- Diese Zusage ist durch den Wegfall der Space-Arten **stärker** geworden, nicht schwächer: Sie hängt nicht mehr daran, dass jemand im richtigen Raum gearbeitet hat.
- **Geschützt ist der Inhalt, nicht die Tatsache der Nutzung.** Der Protokollsatz entsteht bei jeder Abfrage, unabhängig davon, ob ein Chat geteilt ist und in welchem Space er läuft. Wer privat arbeitet, tut das inhaltlich unbeobachtet — dass er arbeitet, wann und wie oft, wird protokolliert. Das gehört ausgesprochen, damit eine Auskunft an die Beschäftigten stimmt: *„Dass du arbeitest, wird protokolliert. Was du schreibst, nicht. Und es gibt keine Funktion, die das nach dir sortiert."* Der einzige Weg, der von dieser Spur noch zu einer Person führt, ist die anlassbezogene Klärung — deshalb ist deren Begrenzung so wichtig.
- Ein Raum, in dem jemand allein arbeitet, steht fachlich **nicht schlechter** da als ein gemeinsamer: Der Suchbereich umfasst dort alles, was der Nutzer lesen darf (siehe [Suchbereich je Chatart](#suchbereich-je-chatart)). Ohne diese Zusage wäre die Ausweichmöglichkeit nur formal und der Zwang zum sichtbaren Raum faktisch.

### Was das Produkt nicht regeln kann

- **Freiwilligkeit.** Ob die Nutzung verpflichtend wird und ob Beschäftigten ein Nachteil entsteht, die den Assistenten nicht oder nur für sich nutzen, entscheidet die Dienststelle. Das gehört in die Dienstvereinbarung.
- **Die Höhe der Mindestgruppengröße.** In einem Referat mit vier Beschäftigten ist auch ein Aggregatwert personenbeziehbar, sobald zwei im Urlaub sind. Das Produkt setzt eine Voreinstellung und erzwingt eine Untergrenze; die angemessene Zahl folgt aus dem tatsächlichen Zuschnitt der Einheiten.
- **Die Aufnahme externer Personen** in Spaces mit geteilten Inhalten ist aus Beschäftigtensicht die heikelste Konstellation — Externe erhalten Einblick in die Arbeitsergebnisse namentlich bekannter Beschäftigter. Das Produkt kennzeichnet externe Konten, verlangt bei der Aufnahme eine ausdrückliche Bestätigung und protokolliert sie. Ob der Vorgang mitbestimmungspflichtig ist, entscheidet die Dienststelle.
- **Ob der Umfang des Protokollsatzes für eine C5-Prüfung erforderlich ist.** Sollte ein Feld zwingend sein, das hier als verzichtbar behandelt wird, ist das schriftlich zu begründen und das Feld aus Berichten und Exporten auszuschließen.

---

## Geprüfte und verworfene Alternativen

Dieser Abschnitt hält fest, welche naheliegenden Modelle geprüft und aus welchem Grund verworfen wurden. Er steht hier, damit ein bereits entschiedener Punkt nicht in einem halben Jahr erneut als neue Idee auftaucht. Die Hauptlast des gewählten Modells ist bekannt und in Kauf genommen: **Zwei Rechtelogiken müssen nebeneinander verstanden werden** — ein Space-Admin darf das Regal umräumen, aber nicht die Bücher lesen.

### Wie andere Systeme Container und geteiltes Objekt zueinander stellen

Die entscheidende Frage ist, was passiert, wenn ein Objekt in mehreren Containern mit unterschiedlichen Rechten liegt. Vier Systeme wurden untersucht:

| System | Container | Objekt in mehreren Containern? | Rechteanker |
|---|---|---|---|
| **Confluence** | Space | Nein — eine Seite liegt in genau einem Space | Space-Permission ist der Boden; Page-Restriction kann nur **einschränken**, nie erweitern |
| **Notion** | Teamspace / Parent-Page | Nein — genau ein Parent; Verschieben ändert Rechte, Duplizieren erzeugt eine Kopie | Parent vererbt, Objekt kann überschreiben; Einladungen sind additiv |
| **Langdock** | — | Container ist **nicht rechtetragend** | Assistants und Knowledge Folders tragen eigene Freigabelisten; Ordner sind Bibliotheksordnung, keine Rechteträger |
| **Glean** | Collections | Ja, aber ohne Rechtewirkung | Per-Dokument-Rechte aus dem Quellsystem, zur Abfragezeit geprüft; Collections sind reine Kuratierung |

**Kein einziges dieser Systeme kennt „Objekt liegt in mehreren rechtetragenden Containern".** Entweder es gibt genau einen rechtetragenden Container (Confluence, Notion) oder gar keinen (Langdock, Glean). Die naheliegende Kombination — mehrere Container, jeder rechtetragend — hat kein Vorbild und erzeugt Fragen, die niemand sonst beantworten musste: Welcher Container gewinnt bei widersprüchlichen Rechten? Darf jemand durch Hinzufügen zu einem Container Rechte verteilen, die er selbst nicht vergeben dürfte?

Das Modell weicht **von beiden Mustern ab**, aber nicht in derselben Sache — es wendet beide an, auf verschiedene Objektklassen: Für **Assets** gilt das Langdock/Glean-Muster (der Container trägt keine Rechte), für **space-eigene Inhalte** das Confluence-Muster (der Container trägt die Rechte). Die Trennlinie ist die Entstehung.

### Rechtemodell

- **Vereinigung — Space-Mitgliedschaft gewährt automatisch Asset-Rechte.** Bequem und intuitiv, aber ein Space-`CURATOR` könnte ein Asset, das ihm nicht gehört, in einen großen Space hängen und damit dessen Mitgliedern Zugriff verschaffen. Confluence verbietet genau diese Richtung ausdrücklich.
- **Schnittmenge — Space-Rolle und Asset-Recht müssen beide erlauben.** Scheitert an der Mehrfachzuordnung (welcher Space ist maßgeblich, wenn das Asset in zweien mit unterschiedlichen Rollen liegt) und zerstört das direkte Teilen ohne gemeinsamen Space.
- **Assoziation mit explizitem, gedeckeltem Grant.** Sicher, aber sie verlangt bei jeder Zuordnung eine zusätzliche Entscheidung durch den Asset-Verantwortlichen. Zugunsten des einfacheren und strikteren Modells verworfen.
- **Eine Asset-Rolle `USER` unterhalb von `VIEWER`** — benutzen, ohne die Konfiguration zu sehen. Zunächst als „wesentlicher Zugewinn" vorgesehen, dann verworfen: **Die Zusage ist nicht durchsetzbar.** Wer einen Agenten aufrufen darf, kann ihn nach seinen Anweisungen fragen — die Aufgabenbeschreibung steht in seinem Kontext, und kein Rechtemodell hält ein Sprachmodell davon ab, sie wiederzugeben. Bei einer Bibliothek läuft die Trennung weitgehend leer, weil eine Antwort mit Quellenangabe die Dokumenttitel ohnehin nennt. Eine Rolle, die etwas zusichert, was die Technik nicht hält, ist schlechter als keine: Sie verleitet dazu, Bestände breiter freizugeben, als man es täte, wenn man die Wirkung richtig einschätzte. `VIEWER` ist damit die unterste Asset-Rolle.
- **Kuratoren als Objekt an der Organisationseinheit, mit Zuständigkeitsvererbung nach oben.** Eine Freigabe an eine Einheit hätte die Zustimmung ihres Kurators erfordert; bei Nichtbesetzung wäre die Zuständigkeit an die nächsthöhere Einheit gefallen, im Zweifel bis zur Gesamtorganisation. Verworfen zugunsten der Regel, dass ein Grant an eine Gruppe **keine** Zustimmung braucht:
  1. **Ein Grant setzt niemanden etwas aus.** Er gewährt Zugriff, er verteilt keine Inhalte. Das Risiko ist Katalog-Rauschen, nicht Datenabfluss — und dagegen wirken `listed = false` und die Governance-Arbeitsliste.
  2. **Der Auffangfall endet ohnehin zentral.** Ist niemand benannt, landet die Entscheidung beim System-Admin. Die Eskalationskette ist der teurere Weg zum selben Ergebnis.
  3. **Sie setzt eine Besetzung voraus, die eine Pilotbehörde nicht hat.** Referats- und Abteilungsebene mit Arbeitszeit zu hinterlegen ist ein Einführungsprojekt; bis dahin ist die Kette auf jeder Stufe leer.
  4. **Sie erzeugt einen Zustandsautomaten für einen Verwaltungsvorgang** — Frist je Stufe, Weiterreichen bei Ablauf, Liegezeit je Station.

  Mit den Kuratoren entfallen auch die konfigurierbare Größenschwelle und die Sonderbehandlung des Umgehungswegs über `AD_HOC`-Gruppen: Ohne Zustimmungspflicht gibt es nichts zu umgehen. Die Aufbauorganisation bleibt als Herkunft der Gruppen und als Aggregationsachse erhalten. Wer die Annahmeseite später doch braucht, kann sie als Rolle *in* der Gruppe ergänzen — so löst es Langdock —, ohne das Rechtemodell anzufassen.
- **Space-Hierarchie zur Abbildung der Verteilungsstufen.** Die Stufen „persönlich → Team → Fachbereich → organisationsweit" werden über das Rechtesubjekt abgebildet — persönlich, Team-Gruppe, Abteilungs-Gruppe, organisationsweit — kombiniert mit `visibility` und `listed`. Keine Topologie der Spaces, kein Abteilungs-Objekt.
- **Drei Space-Arten (`PERSONAL`, `PROJECT`, `TEAM`).** Verworfen zugunsten eines einzigen Typs mit den Attributen `isDefault` und `memberSource`. `PERSONAL` war nichts anderes als „ein Space, in dem nur eine Person Mitglied ist", und alles, was daran hing, hängt in Wahrheit woanders: der unverengte Suchbereich an der Abwesenheit assoziierter Bibliotheken, die Zusage an die Personalvertretung am Privatstatus der Inhalte, die Aufbewahrungsfrist ebenso. `TEAM` unterschied sich von `PROJECT` nur darin, wer ihn anlegen darf — eine Berechtigung, keine Art. Entscheidend ist, dass die Grundregel die Arten überflüssig macht: Wenn private Inhalte ohnehin nur ihrem Ersteller gehören, ist jeder Space, in dem nichts geteilt wird, faktisch privat. Nebenwirkung der Streichung, ausdrücklich gewollt: Ein Nutzer darf beliebig viele Räume anlegen, in denen er allein arbeitet, statt genau einen.
- **Die Statusnamen `DRAFT` und `PLACED`.** Ersetzt durch `PRIVATE` und `SHARED`. „Entwurf" und „ablegen" sind Aktenjargon für einen Vorgang, den jeder aus jedem Werkzeug kennt; die Sache wird davon nicht präziser, nur fremder. Bewusst in Kauf genommen wird, dass „teilen" nun zwei Vorgänge bezeichnet — ein Asset teilen heißt Rechte vergeben, einen Chat in den Space teilen heißt ihn sichtbar machen. Die Objekte sind verschieden genug, dass der jeweilige Satz eindeutig bleibt; wo es eng wird, heißt es „in den Space teilen".
- **Chat und Artefakt als Asset-Typen.** Falsche Kardinalität (viele, wegwerfbar statt wenige, kuratiert), falsche Beziehung (Komposition statt Assoziation), falsche Rechtelogik (die Chats eines Space wären für seine Mitglieder unsichtbar) und ein abweichendes Sicherheitsprofil (Ergebnisse statt Fähigkeiten). Siehe [Warum Chats und Artefakte keine Assets sind](#warum-chats-und-artefakte-keine-assets-sind).

### Sichtbarkeit und Ableitungsleck

- **Chats automatisch space-sichtbar.** Zunächst so entschieden, dann revidiert. Die Regel war einfach und vorhersagbar, erzeugte aber Ausweichverhalten: Gearbeitet wird dann in einem Raum, in dem man allein ist, oder außerhalb des Systems, und in den gemeinsamen Raum wandert nur das Vorzeigbare. Sie war zudem der einzige Grund für das Ableitungsleck in seiner scharfen Form und für die halbe Kennzeichnungsmaschinerie. Der Preis der Revision ist der Tausch automatischer gegen freiwillige Transparenz — ein Chat, den niemand teilt, ist für die Organisation nicht vorhanden. Deshalb muss das Teilen ein Klick sein.
- **Harte Invariante: Bibliothek nur assoziierbar, wenn alle Space-Mitglieder Lesezugriff haben.** Das ist der Strikt-Modus als Pflicht. Drei Gründe gegen die Pflichtform: Sie hängt von einer Größe ab, die sich außerhalb des Systems ändert (eine Verzeichnissynchronisation bricht sie bei jedem Referatswechsel), sie lässt bei Verletzung nur schlechte Optionen (Assoziationen automatisch lösen, Mitglieder entfernen oder den Zustand dulden), und sie macht referatsübergreifende Projekträume — den eigentlichen Anwendungsfall — entweder unmöglich oder erzwingt die Vollfreigabe aller Bestände an alle Beteiligten. Als wählbarer [Strikt-Modus](#der-strikt-modus) bleibt sie erhalten.
- **Zitat-Redaktion beim Lesen.** Verworfen als unvollständig, nicht bloß aufwendig: Der Antworttext trägt die Information weiter, und ein je Leser unterschiedlicher Verlauf zerstört den gemeinsamen Arbeitsraum. Siehe [Warum Zitat-Redaktion weiterhin nicht gebaut wird](#warum-zitat-redaktion-weiterhin-nicht-gebaut-wird).
- **Agent liest mit eigenen Rechten (Rechtedelegation).** Zunächst als admin-aktivierbare Ausnahme vorgesehen, dann vollständig verworfen. Mit der [Freigabekette](#einen-agenten-weitergeben-die-freigabekette) gibt es bereits einen Weg, auf dem ein geteilter Agent beim Empfänger funktioniert; ein zweiter wäre redundant und zugleich der riskantere von beiden. Bewusst in Kauf genommen: Ein Agent, dessen Wissen nicht freigegeben werden darf, ist nicht teilbar.

### Verteilung von Assets

- **Kopie beim Verteilen.** Sie ist der Grund, warum heute veraltete Prompt-Fassungen per Mail kursieren. Verteilt wird per Referenz.
- **Automatisches Zusammenführen von Abkömmling und Original.** Bei frei formulierten Aufgabenbeschreibungen ist ein verlässliches Zusammenführen nicht möglich; ein unzuverlässiges wäre schlimmer als keines. Der Verantwortliche sieht die Änderungen und entscheidet selbst.
- **Abkömmling bei Deaktivierung des Originals sofort mitdeaktivieren.** Zu hart — es bricht die Arbeit einer Einheit zu einem willkürlichen Zeitpunkt ab. Stattdessen Prüfaufforderung mit Frist und automatischer Deaktivierung erst danach.
- **Nur benachrichtigen, wenn das Original deaktiviert wird.** Zu schwach — genau dieser Fall (überholtes Original gesperrt, Abkömmling läuft unbemerkt weiter) ist der gefährlichste.
- **Verfall — automatisches Löschen von Assets ohne Zuständigkeit.** In der Verwaltung ist der Verlust einer gepflegten Wissensbibliothek teurer als ihr Weiterbestehen unter geklärter Einschränkung. Siehe [Eigentümerschaft und Verwaisung](#eigentümerschaft-und-verwaisung).

### Protokollierung

- **Die Rechtemenge je Abfrage mitschreiben.** Verworfen zugunsten der Historisierung von Grants und Gruppenmitgliedschaften: Das Mitschreiben erweitert das Protokoll um erhebliche personenbezogene Daten und bliebe trotzdem lückenanfällig. Die Prüferfrage ist die **Negativfrage** („belegen Sie, dass Frau K. am 3. März keinen Zugriff hatte"), und die kann ein Ereignisprotokoll nicht beantworten.
- **Abschaltbare personenbezogene Auswertung.** Verworfen zugunsten des vollständigen Verzichts: Eine abschaltbare Statistik schützt nicht, weil das Audit-Log denselben Sachverhalt unabhängig davon erhebt und was heute aus ist, morgen an sein kann — mit rückwirkend auswertbaren Daten. Siehe [Kein personenbezogener Auswertungspfad](#kein-personenbezogener-auswertungspfad).

---

## Offene Punkte

- Konkrete Voreinstellungen für Aufbewahrungsfristen je Space-Art und für die Mindestgruppengröße bei Auswertungen.
- Verschachtelte Gruppen im Verzeichnis (Gruppe als Mitglied einer Gruppe) — Auflösungsregel offen.
- Voreinstellung der Aufbewahrungsfrist für private Inhalte — lang genug, um „vergessen, aber noch rettbar" von „endgültig verloren" zu trennen.
- Freigabe- und Prüfworkflow sowie Versionierung von Assets sind unter [Verteilung von Assets](#verteilung-von-assets) beschrieben, liegen aber bewusst außerhalb der ersten Ausbaustufe.
- Wer die fachliche Prüfung wahrnimmt, wenn eine Behörde für eine Freigabestufe keine Stelle benennt — die hier gewählte Antwort „dann ist die Stufe nicht erreichbar" ist streng und in einer Pilotbehörde noch nicht erprobt.
- Voreinstellung der Frist, nach der ein unbearbeiteter Freigabevorschlag auf der Governance-Arbeitsliste erscheint.
- Ob ein Freigabestempel über Installationsgrenzen hinweg nachweisbar bleiben soll (Signatur der Herkunftsinstallation) — heute wandert er als bloße Herkunftsangabe mit.
- Übernahme von Berechtigungen aus Quellsystemen zusätzlich zu den Bibliotheksrechten.
- Konkreter Aktualisierungsweg für mitgelieferte Assets in einem Netz ohne Internetanbindung (Signatur, Prüfung, Einspielung).

---

## Verwandte Dokumente

- [Identität, Rechte & Mandanten](./access-control.md) — Systemverwaltung, Anmeldung und Kontenlebenszyklus
- [Sicherheit, Nachweis & Prüfbarkeit](./security-and-compliance.md) — Protokoll, DSGVO, C5-Fähigkeit, Mitbestimmungsfähigkeit
- [Monitoring, Kosten & Governance](./monitoring-and-governance.md) — Grenzen, Kosten und aggregierte Auswertung
- [Daten-Indizierung & RAG](./data-indexing-rag.md) — Aufnahme, Chunking, Abfrageablauf
- [Agenten, Prompts & Werkzeuge](./agents-and-tools.md) — was in einem Agenten steckt, wie er entsteht und wie er vor der Freigabe geprüft wird
