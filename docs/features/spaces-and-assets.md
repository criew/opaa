# Spaces, Assets & Zugangskontrolle

## Motivation

Wissen und KI-Können sollen in einer Organisation ankommen, nicht in Silos verharren. Das bisherige Workspace-Modell hat beides in denselben Behälter gesperrt: Ein Workspace war zugleich Dokumentencontainer, Rechtegrenze und Ordnungsrahmen. Damit war ein guter Agent nicht teilbar, ohne den ganzen Workspace zu teilen, und eine Wissenssammlung nicht mehrfach verwendbar, ohne sie zu duplizieren.

Dieses Dokument beschreibt das abgelöste Modell: **Assets** (Wissensbibliotheken, Agenten, Prompt-Bibliotheken) sind eigenständige, teilbare Objekte mit eigenem Eigentümer. **Spaces** sind thematische Arbeitsräume, in denen gearbeitet wird und in denen Ergebnisse entstehen.

---

## Überblick

1. **Assets** gehören ihrem Eigentümer und tragen ihre eigenen Rechte. Sie werden in Spaces *assoziiert* — die Assoziation gewährt keinerlei Zugriff.
2. **Spaces** sind Arbeitsräume. Chats und Artefakte entstehen *in* einem Space und gehören ihm — sie entstehen als **Entwurf beim Ersteller** und werden für alle Mitglieder sichtbar, wenn er sie **ablegt**.
3. **Dokumente** liegen in Wissensbibliotheken, nicht in Spaces. Die rechtebewusste Vektorsuche filtert über die Bibliothek.
4. **Ein Chat läuft immer in einem Space.** Der Space bestimmt Ablage, Standard-Suchbereich, Modell-Policy und Zurechnung — aber keine Rechte an Assets.
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
| Rechteanker | **eigene ACL am Asset** | **Ersteller**, bis er ablegt — danach **Space-Mitgliedschaft** |
| Entstehung | anderswo erzeugt, hineingereicht | im Space erzeugt |

**Präzise Fassung der Grundregel — beide Hälften gelten:**

- Space-Mitgliedschaft gewährt **keinen** Zugriff auf assoziierte Assets und deren Dokumente.
- Space-Mitgliedschaft gewährt **vollen** Zugriff auf **abgelegte** space-eigene Inhalte. Entwürfe gehören ausschließlich ihrem Ersteller — auch Space-Admins und System-Admins sehen sie nicht.

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
| `USER` | benutzen, ohne die Konfiguration zu sehen — Agent aufrufen, Bibliothek liefert Treffer in Antworten |
| `VIEWER` | zusätzlich die Konfiguration einsehen: Aufgabenbeschreibung, Wissensbindung, Dokumentenliste |
| `EDITOR` | zusätzlich ändern |
| `MANAGER` | zusätzlich teilen, Rechte vergeben, Freigabestufe und Auffindbarkeit setzen |
| `OWNER` | zusätzlich löschen und Eigentum übertragen |

Die Trennung **`USER` gegen `VIEWER`** ist der wesentliche Zugewinn: Ein Sachgebiet soll einen geprüften Agenten nutzen können, ohne dass jeder Nutzer seine Aufgabenbeschreibung ändern oder die zugrundeliegende Dokumentenliste einsehen kann.

Asset-Rollen sind eine **eigene Rangordnung**, getrennt von den Space-Rollen. **Kein Rollenname kommt in beiden Systemen vor** — deshalb heißt die verwaltende Asset-Rolle `MANAGER` und nicht `ADMIN`. Wer sagt „ich habe hier Admin-Rechte", meint damit immer einen Space; wer Asset-Rechte meint, sagt `MANAGER` oder `OWNER`. Das ist keine Kosmetik: Bei der Übergabe eines Vorgangs muss ohne Rückfrage klar sein, wovon die Rede ist.

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

Ein Asset „an die ganze Abteilung freigeben" heißt also: **Grant an die Gruppe, die die Abteilung abbildet** — erteilt mit Zustimmung des Kurators dieser Einheit. Ohne Gruppen gäbe es die Stufe „Fachbereich" nicht; sie ist der Punkt, an dem das flache Space-Modell sonst eine Lücke hätte. Das ist der eigentliche Grund, warum Gruppen früh und nicht später gebraucht werden. Einzelheiten unter [Verteilung von Assets](#verteilung-von-assets).

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
4. **Die Nachfolge hat einen benannten Adressaten und eine Frist.** Zuständig ist der Kurator der Organisationseinheit des Ausgeschiedenen; ist keiner benannt, greift die Eskalation nach oben (siehe [Kuratoren](#kuratoren)). Der Vorgang erscheint auf seiner Liste mit Frist und wird bei Ablauf eskaliert — er verfällt nicht und landet nicht in einer namenlosen Sammelliste.
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
| `ORG_UNIT` | aus dem Verzeichnis synchronisiert (Referat, Abteilung, Amt) | Rechtesubjekt **und** Freigabeziel; kennt ihre übergeordnete Einheit; kann Kuratoren haben |
| `AD_HOC` | im System angelegt | nur Rechtesubjekt (z. B. „Projektbeteiligte Phoenix", „Stabsstelle Leserunde") |

**Verhältnis von Rechtesubjekt und Freigabeziel:** Es ist **dasselbe Objekt in zwei Verwendungen**, und materiell derselbe Vorgang. „Ein Asset an die Abteilung 5 freigeben" heißt: ein Grant an die Gruppe „Abteilung 5". Neu ist nicht *was* passiert, sondern *wer es erteilen darf* — bei einer Organisationseinheit ist dafür deren Kurator zuständig (siehe unten). Ein Grant an eine `AD_HOC`-Gruppe braucht keinen Kurator und wird wie jede andere Rechtevergabe vom Asset-Admin erteilt.

**Zwei Richtungen, die nicht verwechselt werden dürfen:**

- **Mitgliedschaft vererbt nicht.** Wer in einer Einheit Mitglied ist, sagt das Verzeichnis. OPAA erfindet keine Vererbung nach unten: Ein Grant an „Amt 5" erreicht nur, wen das Verzeichnis dieser Gruppe zurechnet.
- **Zuständigkeit vererbt aufwärts.** Ist für eine Einheit kein Kurator benannt, fällt die Zuständigkeit an die nächsthöhere Einheit, im Zweifel an die Gesamtorganisation.

Damit ist auch die frühere Aussage „keine Hierarchie" präzisiert: **Die einzige Hierarchie im System ist die Aufbauorganisation, und sie kommt aus dem Verzeichnis.** Spaces bleiben flach, Assets bleiben flach.

### Kuratoren

Ein **Kurator** ist an eine Organisationseinheit gebunden, nicht global. Es gibt Kuratoren für Referate, für Abteilungen und für die Gesamtorganisation.

- Eine Freigabe an eine Einheit erfordert die Zustimmung ihres Kurators.
- Ist für eine Einheit kein Kurator benannt, greift der Kurator der nächsthöheren Einheit.
- Die Besetzung ist damit **optional und wächst mit**: Eine Pilotbehörde benennt einen einzigen zentralen Kurator und ist sofort arbeitsfähig. Eine große Behörde besetzt Referats- und Abteilungsebene und steuert fein.

Der Kurator entscheidet über die Aufnahme in seinen Verantwortungsbereich — er wird dadurch nicht Eigentümer des Assets. Eigentum und Kuratierung sind getrennt.

**Die Zuständigkeit hängt an der Reichweite, nicht an der Gruppenart.** Sonst gäbe es einen offenen Umgehungsweg: Wer eine Freigabe an „Abteilung 5" nicht durch den Kurator bringt, legt eine `AD_HOC`-Gruppe mit denselben Personen an und erteilt den Grant ohne jede Kuratierung — materiell dieselbe Reichweite, formal keine Freigabe an die Einheit. Deshalb gilt:

> Ein Grant an eine Gruppe ab einer konfigurierbaren Größe erfordert die Zustimmung eines Kurators — **unabhängig davon, ob es eine Organisationseinheit oder eine Ad-hoc-Gruppe ist.**

Zuständig ist bei einer Ad-hoc-Gruppe der Kurator der Organisationseinheit des Erteilenden. Kleine Ad-hoc-Gruppen — der übliche Projektkreis — bleiben frei von Kuratierung; sie sind der Grund, warum es diese Gruppenart überhaupt gibt.

**Voraussetzung dafür ist, dass die Kuratorenrolle tatsächlich besetzt und mit Arbeitszeit hinterlegt ist.** Bleibt sie unbesetzt, fällt über die Eskalation alles auf die zentrale Ebene, dort stapeln sich die Anfragen, und die dokumentierte Freigabekette ist nicht mehr die gelebte. Das ist keine Frage der Technik, sondern der Einführung — die einführende Stelle muss die Rolle benennen und ausstatten. Das Produkt macht sie sichtbar: Offene Anfragen mit Liegezeit stehen auf einer Liste, und die Liegezeit ist auswertbar.

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
4. **Bei Deaktivierung des Originals reicht eine Benachrichtigung nicht.** Der Abkömmling erhält eine **Prüfaufforderung mit Frist**:
   - Er läuft zunächst weiter, trägt aber für **alle Nutzenden** — nicht nur für den Verantwortlichen — einen deutlichen Hinweis mit dem Grund der Deaktivierung des Originals.
   - Der Verantwortliche muss innerhalb der Frist ausdrücklich bestätigen, dass der Abkömmling fachlich weiter gilt, oder ihn selbst deaktivieren.
   - Bleibt die Bestätigung aus, wird der Abkömmling **automatisch deaktiviert**.

Punkt 4 ist die einzige Stelle im Modell, an der ein Asset ohne Zutun seines Eigentümers seinen Zustand ändert. Das ist beabsichtigt: Der Fall, dass eine überholte Rechtsauffassung unbemerkt weiterläuft, wiegt schwerer als die Unannehmlichkeit einer erzwungenen Prüfung.

**Voraussetzung dafür ist eine Begründungspflicht:** Wer ein Asset deaktiviert, gibt einen Grund an („SGB II § 7 geändert zum 1. Januar"). Dieser Grund ist es, der bei Abkömmlingen und in Chatverläufen angezeigt wird. Ohne ihn ist der Hinweis Rauschen und wird ignoriert.

### Rückruf durch Deaktivieren

Ein fachlich überholtes Asset wird **deaktiviert, nicht gelöscht**:

- Es ist nicht mehr aufrufbar und erscheint nicht mehr im Katalog.
- **Bestehende Chatverläufe bleiben vollständig lesbar** und tragen an den Stellen, an denen das Asset gewirkt hat, einen sichtbaren Warnhinweis mit Grund und Datum.

Der Grund ist die Nachvollziehbarkeit: Auf Grundlage der damaligen Antworten können Bescheide ergangen sein. Ein Löschen würde die Spur zerstören, die eine Revision oder ein Widerspruchsverfahren später braucht. Deshalb wird nichts entfernt, sondern nur unbrauchbar gemacht und gekennzeichnet.

Für Wissensbibliotheken gilt dasselbe: Eine deaktivierte Bibliothek liefert keine Treffer mehr; vorhandene Zitate in Verläufen bleiben lesbar, und ihre Sprungmarken bleiben rechtegeprüft.

Der Hinweis hängt an den Nachrichten, die das Asset tatsächlich genutzt haben — das setzt die Herkunftsverfolgung voraus, die ohnehin für das Ableitungsleck geführt wird.

### Mitgelieferte Assets

OPAA liefert erprobte Verwaltungs-Agenten und -Prompts ab Werk aus. Sie sind ein eigener **Herkunftstyp**:

| `Asset.origin` | Bedeutung |
|---|---|
| `BUILT_IN` | mitgeliefert; gehört keinem Nutzer, wird mit Produkt-Updates aktualisiert, in der Behörde nicht änderbar |
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
5. **Zurechnungspunkt** für Nutzungsstatistik, Kostenzuordnung und Audit; zugleich Vorauswahl für die Ablage neuer Uploads.

### Space-Arten

| `kind` | Anlage durch | Zweck |
|---|---|---|
| `PERSONAL` | automatisch, genau einer je Nutzer | eigene Arbeit, eigene Ablage |
| `PROJECT` | jeder Nutzer | eigene Vorhaben, nur selbst eingeladene Mitglieder |
| `TEAM` | System-Admin | Team, Fachbereich, organisationsweite Räume |

Nutzer dürfen also eigene Projekt-Spaces anlegen, aber keine Team- oder Fachbereichsräume gründen. Persönliche Spaces können nicht gelöscht und nicht geteilt werden.

Space-Namen sind **nicht global eindeutig**. Zwei Nutzer dürfen beide einen Projekt-Space „Phoenix" haben. Eindeutigkeit gilt höchstens je Organisation und Name.

### Space-Sichtbarkeit

Mitgliedschaft und Assetzugriff sind entkoppelt; deshalb braucht der Space eine eigene Sichtbarkeitsachse:

| `visibility` | Bedeutung |
|---|---|
| `PRIVATE` | nur Mitglieder wissen, dass er existiert — zwingend für persönliche Spaces, Vorgabe für Projekt-Spaces |
| `DISCOVERABLE` | im Space-Verzeichnis sichtbar, Beitritt auf Antrag |
| `OPEN` | im Verzeichnis sichtbar, Selbstbeitritt mit einem Klick |

**Chatten setzt Mitgliedschaft voraus.** Das ist keine Hürde, sondern eine Folge des Modells: Der Chat *liegt* im Space, und ohne Mitgliedschaft gäbe es keinen definierten Zurechnungspunkt für Aufbewahrung, Kosten und Audit. Bei `OPEN`-Spaces ist der Beitritt ein Klick und wird protokolliert.

### Space-Rollen

| Rolle | Darf |
|---|---|
| `MEMBER` | Space betreten; Chats anlegen und führen; **alle abgelegten** Chats und Artefakte des Space lesen; kuratierte Assets sehen — gefiltert auf den eigenen Zugriff |
| `CURATOR` | zusätzlich Assets assoziieren und lösen, Inhalte ordnen |
| `ADMIN` | zusätzlich Mitglieder und Rollen verwalten, Einstellungen und Policy-Obergrenze setzen, abgelegte Inhalte **zurückziehen** (nicht löschen) |

Dazu trägt jeder Space eine `ownerId` als **Attribut** — den fachlich Verantwortlichen, der im Verzeichnis ausgewiesen wird. Einen Space löschen oder die Verantwortung übertragen darf nur der Verantwortliche selbst oder ein System-Admin.

Warum drei statt der bisherigen vier Rollen:

- `VIEWER` und `EDITOR` implizierten Zugriff auf Dokumente. Genau diesen Fehlschluss soll das neue Modell vermeiden; die Umbenennung ist semantisch notwendig.
- `OWNER` als eigener Rang trug sein Gewicht daraus, dass eine Workspace-Löschung alle Dokumente vernichtete. Das ist nicht mehr so — Dokumente liegen in Bibliotheken, die anderen gehören. Der Schutz bleibt über das `ownerId`-Attribut erhalten, ohne vierte Rangstufe.
- `ADMIN` gewinnt dagegen an Gewicht, weil Policy-Obergrenze und Mitgliederverwaltung an ihm hängen.

**Grenze der Admin-Rechte:** Ein Space-Admin kann abgelegte Inhalte aus dem Space entfernen, aber nicht beseitigen (siehe [Chats sind vor fremder Löschung geschützt](#chats-sind-vor-fremder-löschung-geschützt)). Er kann Entwürfe anderer Mitglieder **nicht sehen** — auch nicht als Admin.

### Assets in einen Space assoziieren

Ein Kurator kann jedes Asset, auf das er selbst Zugriff hat, in seinen Space assoziieren. Das ist unbedenklich, weil die Assoziation **keine Rechte gewährt** — sie stellt das Asset lediglich im Space zur Verfügung, und zwar nur für die Mitglieder, die ohnehin Zugriff darauf haben.

Der Eigentümer des Assets sieht alle Assoziationen und kann jede davon jederzeit einseitig lösen. Das Asset bleibt Herr über seine Verbreitung.

**Benachrichtigung statt Zustimmung.** Wird eine Bibliothek in einem Space bereitgestellt, dessen Mitglieder nicht sämtlich Lesezugriff darauf haben, **wird ihr Eigentümer aktiv benachrichtigt**. Er muss nicht zustimmen — die Assoziation setzt niemanden etwas aus, weil Inhalte erst durch das Ablegen sichtbar werden —, aber er erfährt davon, ohne in eine Liste schauen zu müssen. Das schließt die Lücke, dass ein Referatsleiter erst zufällig bemerkt, wo sein Wissen bereitsteht.

**Selbstschutz des Eigentümers.** Ein Bibliotheks-Eigentümer kann für seine Bibliothek festlegen, dass sie **nur in Strikt-Spaces** assoziiert werden darf. Damit liegt das Werkzeug gegen das Ableitungsleck nicht nur in fremder Hand: Wer einen besonders geschützten Bestand verantwortet, kann dessen Verbreitung selbst begrenzen, statt auf die Sorgfalt fremder Kuratoren angewiesen zu sein.

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

### Die Grundregel: entstehen als Entwurf, sichtbar durch Ablegen

Für **alle** space-eigenen Inhalte — Chats, Artefakte und alles, was später hinzukommt — gilt eine einzige Regel:

> **Was du erzeugst, gehört zunächst dir. Sichtbar für den Space wird es, wenn du es dort ablegst.**

Das ist die vertrauteste Regel der Verwaltung überhaupt: Was zur Akte gegeben wird, sehen alle. Was auf dem Schreibtisch liegt, nicht. Es gibt keinen Sonderweg, keine von der Herkunft abhängige Ausnahme und keinen Modus, den man sich merken müsste.

| Status | Sichtbar für |
|---|---|
| `DRAFT` | nur den Ersteller |
| `PLACED` | alle Mitglieder des Space |
| `SUPERSEDED` | alle Mitglieder, als überholt gekennzeichnet |
| `WITHDRAWN` | niemand außer Ersteller und Space-Admin, bleibt nachweisbar |

Das Ablegen ist **eine bewusste, protokollierte Handlung** des Erstellers. Es ist die Stelle, an der er die Verantwortung für die Weitergabe übernimmt — und die einzige Stelle, an der Inhalte aus seinem Arbeitsbereich in den Leserkreis des Space übergehen.

**Was diese Regel kostet, ausdrücklich benannt:** Sie tauscht automatische gegen freiwillige Transparenz. Ein Chat, den niemand ablegt, ist für die Organisation nicht vorhanden, und es gibt keine Garantie, dass Wertvolles abgelegt wird. Die Gegenrechnung: Automatische Sichtbarkeit erzeugt Ausweichverhalten — gearbeitet wird dann im persönlichen Space oder außerhalb des Systems, und in den gemeinsamen Raum wandert nur das Vorzeigbare. Die freiwillige Variante dürfte am Ende mehr sichtbar machen als die erzwungene, aber das ist eine Annahme über Verhalten und keine Gewissheit. Das Ablegen muss deshalb **ein Klick** sein und darf nie hinter einem Menü liegen.

### Chats

Ein Chat ist ein **persistentes Objekt im Space**, kein flüchtiger Kontext. Ein Space enthält n Chats.

Ein Chat entsteht als `DRAFT` und ist ausschließlich für seinen Autor sichtbar. Erst wenn der Autor ihn im Space ablegt, sehen ihn alle Mitglieder. Damit existiert der Denkraum vor der Ablage: die unfertige Einschätzung, die schwierige Personalsache, die dreimal gestellte Rückfrage, bei der man unsicher ist — all das findet statt, ohne dass jemand mitliest, und niemand muss dafür den Space wechseln oder auf E-Mail ausweichen.

**Konsequenz für die Nutzerführung.** Verbindlich:

- Der Chat zeigt dauerhaft seinen Status und, sobald abgelegt, **wer mitliest** — im Kopfbereich mit Zugriff auf die Mitgliederliste, nicht in einem Untermenü.
- Beim Ablegen wird der Leserkreis benannt, **bevor** die Ablage wirksam wird.
- Enthält der Chat Treffer aus Bibliotheken, die nicht alle Space-Mitglieder lesen dürfen, steht das als Hinweis **im Ablagedialog** — ohne Anzahlen und ohne Namen der Bibliotheken zu nennen. Kein zusätzlicher Dialog: die Information erscheint dort, wo die Entscheidung ohnehin getroffen wird.
- Der Wechsel des Space ist eine sichtbare Handlung, nie eine stillschweigende Voreinstellung.
- Der Autor kann einen abgelegten Chat **zurückziehen** (`WITHDRAWN`). Das entfernt ihn aus der Space-Ansicht, löscht ihn aber nicht; bereits erfolgte Einsichtnahmen macht es nicht rückgängig.

Ein Chat kann an einen Agenten gebunden sein. Ist er das, bestimmt der Agent den Suchbereich; ist er es nicht, bestimmt ihn der Space.

Das Datenmodell hält von Anfang an die Achsen offen, die für Mensch+KI-Gruppenräume gebraucht werden (Teilnehmer mit Lese-/Schreibrolle, Antwort-Bezug für Threads, Erwähnungen), auch wenn diese Funktionen erst später gebaut werden.

#### Chats sind vor fremder Löschung geschützt

Für Chats gilt dasselbe wie für Assets: **Zurückziehen statt Löschen.** Ein Space-Admin kann einen abgelegten Chat aus dem Space entfernen — er kann ihn nicht beseitigen. Der Chat bleibt für seinen Autor und im Nachweis erhalten, die Entfernung wird mit Grund protokolliert.

Der Grund ist derselbe wie bei Assets: Ein Chat kann eine fachliche Einschätzung dokumentieren, auf die sich später jemand beruft. Es wäre nicht vertretbar, für Assets „Rückruf durch Deaktivieren, nie durch Löschen" zu fordern und die Arbeitsspuren der Beschäftigten schlechter zu stellen.

Der Autor kann seinen Chat jederzeit exportieren.

### Artefakte

In einem Space entstehen Ergebnisse: eine Excel-Auswertung, ein Diagramm, später Berichte, Entwürfe und Analysen. Für sie gilt **dieselbe** Regel wie für Chats — sie entstehen als Entwurf beim Ersteller und werden durch Ablegen space-sichtbar. Kein Sonderweg, keine Abhängigkeit von der Herkunft der Daten.

Die Objektklasse ist bewusst allgemein gehalten, damit weitere Ergebnistypen ohne Modelländerung hinzukommen können.

#### Lebenszyklus

- **Zuordnung:** Jedes Artefakt kennt den Chat, aus dem es entstanden ist, und seinen Erzeugungszeitpunkt.
- **Versionierung:** Ein neues Artefakt kann ein bestehendes ersetzen. Das ersetzte wird `SUPERSEDED`, bleibt aber auffindbar.
- **Herkunftskennzeichnung:** Jedes Artefakt zeigt, aus welchen Bibliotheken es abgeleitet wurde, und ist auf seinen Ursprungs-Chat rückführbar.
- **Zurückziehen:** durch Ersteller und Space-Admin, mit Grund protokolliert — kein Löschen.
- **Aufbewahrung:** Je Space konfigurierbare Frist, damit Projekt-Spaces nicht unbegrenzt wachsen. Entwürfe unterliegen ihr ebenso.
- **Übergang ins Wissen:** Ein Artefakt kann in eine Wissensbibliothek übernommen werden und wird dabei zu einem Dokument. **Ab dann gelten die Rechte der Bibliothek, nicht mehr die des Space.** Das ist der Rückweg aus der space-eigenen in die assoziierte Welt und der einzige Weg, auf dem ein Ergebnis dauerhaft und rechtegeführt wird.

### Warum Chats und Artefakte keine Assets sind

Der Gedanke, alles einheitlich als Asset zu modellieren, ist naheliegend, trägt aber nicht:

1. **Kardinalität und Lebenszyklus passen nicht.** Assets sind wenige, benannt, kuratiert, versioniert, katalogfähig. Chats und Artefakte sind viele, oft unbenannt, häufig wegwerfbar. Katalog, Freigabe-Workflow, Export/Import und Agenten-Prüfstand sind auf sie nicht anwendbar.
2. **Die Beziehung ist eine andere.** Ein Asset liegt in 0..n Spaces, optional. Ein Chat liegt in genau einem Space, zwingend. Das ist Komposition, nicht Assoziation.
3. **Die Rechtelogik wäre die falsche.** Wäre ein Chat ein Asset, gewährte der Space nichts an ihm — die Chats eines Projekt-Space wären für die Projektmitglieder unsichtbar. Das ist das Gegenteil des Gewollten.
4. **Das Sicherheitsprofil ist asymmetrisch.** Ein geteiltes Asset gibt eine *Fähigkeit* weiter, ein geteilter Chat oder ein Artefakt gibt *Ergebnisse* weiter. Dafür braucht es eigene Regeln, die nicht vom Asset geerbt werden dürfen.

---

## Dokumente und rechtebewusste Suche

### Dokumente liegen in Bibliotheken

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

**Der persönliche Space ist die Ausnahme von der Verengung:** Dort ist der Suchbereich **alles, was der Nutzer lesen darf** — es gibt in einem Ein-Personen-Raum nichts zu kuratieren. Damit steht der persönliche Space fachlich nie schlechter da als ein Team-Space, und die Möglichkeit, unbeobachtet zu arbeiten, ist keine bloß formale: Wer dorthin ausweicht, verliert keinen Zugang zu Wissen.

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

- Jede Anfrage hat eine **Frist**. Läuft sie ab, wird sie an den Kurator der Organisationseinheit des Bibliotheks-Eigentümers eskaliert, von dort nach oben.
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

Mit der Ablage-Regel (siehe [Space-eigene Inhalte](#die-grundregel-entstehen-als-entwurf-sichtbar-durch-ablegen)) ist der Übergang **kein automatischer Vorgang mehr, sondern eine Handlung**. Nichts fließt in den Leserkreis des Space, ohne dass ein Mensch es dorthin legt — und dieser Mensch ist immer jemand, der die Inhalte selbst lesen durfte.

Das entspricht dem Verwaltungshandeln: Wer etwas lesen darf, darf es seinen Kollegen berichten und verantwortet das. Neu ist nur, dass die Weitergabe sichtbar, zurechenbar und protokolliert ist statt beiläufig.

**Wichtig — das Leck ist damit nicht verschwunden, sondern in einen verantworteten Akt überführt.** Ein abgelegter Chat kann weiterhin Passagen enthalten, die andere Space-Mitglieder nie hätten öffnen dürfen. Der Unterschied ist, dass es jetzt eine Person gibt, die diese Entscheidung getroffen hat, und einen Zeitpunkt, an dem sie getroffen wurde.

### Was daraufhin entfallen ist

Drei Mechanismen aus einem früheren Entwurf sind ersatzlos gestrichen, weil sie den Kanal an einer Stelle absichern sollten, an der er sich gar nicht mehr öffnet:

| Entfallen | Grund |
|---|---|
| **Bestätigungspflicht des Kurators bei gemischter Assoziation** | Die Assoziation setzt niemanden mehr etwas aus. Sie stellt eine Bibliothek bereit; sichtbar wird ein Ergebnis erst durch das Ablegen |
| **Dauerhafte Kennzeichnung gemischter Spaces** | In einer realen Behörde fallen Leserkreise praktisch nie exakt zusammen — ein Teilzeitbeschäftigter, eine externe Kraft, ein Abgeordneter genügt. Damit wäre so gut wie *jeder* Space gekennzeichnet, und ein Warnzeichen, das an allem klebt, informiert über nichts |
| **Herkunftsabhängiger Sonderweg bei Artefakten** | Ein Ergebnis war mal sofort sichtbar und mal nicht, ohne dass der Ersteller den Unterschied erklären konnte. Jetzt gilt für alles dieselbe Regel |

Was **bleibt**, ist das Billige und Wirksame:

1. **Herkunftsverfolgung.** Jeder Chat und jedes Artefakt führt mit, aus welchen Bibliotheken tatsächlich Treffer stammten — Grundlage für den Hinweis im Ablagedialog, für den Nachweis und für die Kennzeichnung am Artefakt.
2. **Hinweis im Ablagedialog**, wenn der Inhalt aus Bibliotheken stammt, die nicht alle Mitglieder lesen dürfen — ohne Anzahlen, ohne Namen, und ohne zusätzlichen Dialog: die Information steht dort, wo die Entscheidung ohnehin fällt.
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

Der zweite Punkt schließt eine Lücke, die eine frühere Fassung offen ließ: Der Space verengt den Suchbereich eines Agenten nicht (siehe [Suchbereich je Chatart](#suchbereich-je-chatart)), weil ein geprüftes Agenten-Release sonst nicht mehr reproduzierbar wäre. Ein Agent könnte damit im Strikt-Space aus einer engen, dort nicht assoziierten Bibliothek liefern.

Die Auflösung ist **weder den Agenten zu verengen noch die Zusicherung zurückzunehmen, sondern den Aufruf zu verweigern**: Der Agent läuft entweder mit seiner vollständigen, reproduzierbaren Bindung oder gar nicht. Die Reproduzierbarkeit bleibt unangetastet, weil sein Suchbereich nie stillschweigend verkleinert wird — er wird nur an einem Ort nicht zugelassen. Der Nutzer erhält einen klaren Hinweis, dass dieser Agent in diesem Raum nicht verwendet werden darf.

### Wenn die Voraussetzung eines Strikt-Space nachträglich bricht

Die Bedingung „alle Mitglieder dürfen alles lesen" hängt an Größen, die sich außerhalb des Space ändern: eine Verzeichnissynchronisation entfernt jemanden aus einer Gruppe, ein Bibliotheks-Eigentümer nimmt einen Grant zurück, eine Freigabe-Obergrenze wird gesenkt.

Das System löst dann **weder Assoziationen automatisch** (das entzöge einem ganzen Team sein Wissen, weil eine Person versetzt wurde) **noch entfernt es Mitglieder** (das koppelte eine Rechteänderung an einer Bibliothek an die Mitgliedschaft in einem Arbeitsraum). Stattdessen geht der Space in den Zustand **„Voraussetzung verletzt"**:

- Bestehende Inhalte bleiben unangetastet und lesbar.
- **Neue Ablagen und Agentenaufrufe sind gesperrt**, bis der Zustand behoben ist.
- Space-Verantwortlicher und die Eigentümer der betroffenen Bibliotheken werden benachrichtigt; der Vorgang steht im Protokoll.

Das ist fail-closed für neue Exposition, ohne etwas zu zerstören, und es gibt keinen stillschweigenden Zustandswechsel im Hintergrund.

---

## Modell-Policies

Modell-Policies sind **ausschließlich Obergrenzen**. Keine Ebene kann erweitern, was eine andere eingeschränkt hat:

```
erlaubte Modelle = Systempolicy
                 ∩ Space-Policy
                 ∩ Policy jeder Bibliothek im Suchbereich
                 ∩ Policy des Agenten
```

Die Bibliothek trägt ihre Obergrenze **selbst mit sich**. Das ist wesentlich: Unter diesem Modell hat der Space keine Hoheit über die Bibliotheken, die in ihm auftauchen — jeder Kurator kann jede Bibliothek assoziieren, auf die er Zugriff hat. Eine Bibliothek mit besonders geschützten Daten kann also in einem Space landen, dessen Policy Cloud-Modelle erlaubt. Eine ausschließlich space-gebundene Policy schützt genau diesen Fall nicht.

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
| **Entwurf** | **Unverändert.** Ein nicht abgelegter Chat oder ein nicht abgelegtes Artefakt ist ausschließlich für seinen Ersteller sichtbar — auch für Space-Admins und System-Admins nicht |
| **Abgelegter Inhalt** | **Gilt nicht — und das ist eine bewusste Handlung.** Wer ablegt, gibt weiter, was er selbst lesen durfte, und verantwortet das. Der Vorgang wird protokolliert |

---

## Migration vom Workspace-Modell

| Bestand | Behandlung |
|---|---|
| Persönliche Workspaces | werden persönliche Spaces; zusätzlich entsteht je Nutzer eine persönliche Wissensbibliothek „Meine Dokumente", die dort assoziiert wird |
| Gemeinsame Workspaces | werden Team-Spaces |
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

- **Selbstauskunft.** Jede Person kann ihre eigenen Daten einsehen und exportieren. Das ist keine Überwachung, sondern das Auskunftsrecht der betroffenen Person.
- **Anlassbezogene Klärung** bei einem konkreten Sicherheitsvorfall — über den Audit-Pfad, mit dokumentiertem Anlass, im Vier-Augen-Prinzip unter Beteiligung der Personalvertretung und mit eigenem Protokolleintrag über den Zugriff. Ein Produkt ohne jede Möglichkeit, einen Vorfall aufzuklären, wäre nicht betreibbar; ein Produkt, in dem diese Aufklärung der Normalweg ist, wäre nicht zustimmungsfähig.

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

### Wirkung des Ablage-Modells

Die Ablage-Regel ist zugleich die wichtigste Antwort auf die häufigste Sorge der Beschäftigten. Weil ein Chat erst durch eine **Handlung seines Autors** sichtbar wird, entsteht Sichtbarkeit nicht mehr nebenbei:

- Die dreimal gestellte Rückfrage zu einer Rechtsgrundlage, an der jemand unsicher ist, liegt im Entwurf und wird nicht zur dauerhaft sichtbaren Wissenslücke in Schriftform.
- Die Führungskraft, die in aller Regel Mitglied des Team-Space ist, sieht abgelegte Arbeitsergebnisse — nicht den Arbeitsweg dorthin.
- Wer ablegt, tut es bewusst; das ist gegenüber der Personalvertretung darstellbar, anders als eine Sichtbarkeit, die im Hintergrund entsteht.

### Der persönliche Space ist unbeobachtet

Ausdrücklich zugesagt und nicht nur als Nebenwirkung gemeint:

- Inhalte des persönlichen Space und **alle Entwürfe** sind für System-Admins, Revision und Dienststellenleitung **nicht lesbar**. Ein System-Admin kann im Rahmen des Offboardings einen persönlichen Space deaktivieren; er kann ihn nicht einsehen.
- Der persönliche Space steht fachlich **nicht schlechter** da als ein Team-Space: Der Suchbereich umfasst dort alles, was der Nutzer lesen darf (siehe [Suchbereich je Chatart](#suchbereich-je-chatart)). Ohne diese Zusage wäre die Ausweichmöglichkeit nur formal und der Zwang zum sichtbaren Raum faktisch.

### Was das Produkt nicht regeln kann

- **Freiwilligkeit.** Ob die Nutzung verpflichtend wird und ob Beschäftigten ein Nachteil entsteht, die den Assistenten nicht oder nur im persönlichen Space nutzen, entscheidet die Dienststelle. Das gehört in die Dienstvereinbarung.
- **Die Höhe der Mindestgruppengröße.** In einem Referat mit vier Beschäftigten ist auch ein Aggregatwert personenbeziehbar, sobald zwei im Urlaub sind. Das Produkt setzt eine Voreinstellung und erzwingt eine Untergrenze; die angemessene Zahl folgt aus dem tatsächlichen Zuschnitt der Einheiten.
- **Die Aufnahme externer Personen** in Spaces mit abgelegten Inhalten ist aus Beschäftigtensicht die heikelste Konstellation — Externe erhalten Einblick in die Arbeitsergebnisse namentlich bekannter Beschäftigter. Das Produkt kennzeichnet externe Konten, verlangt bei der Aufnahme eine ausdrückliche Bestätigung und protokolliert sie. Ob der Vorgang mitbestimmungspflichtig ist, entscheidet die Dienststelle.
- **Ob der Umfang des Protokollsatzes für eine C5-Prüfung erforderlich ist.** Sollte ein Feld zwingend sein, das hier als verzichtbar behandelt wird, ist das schriftlich zu begründen und das Feld aus Berichten und Exporten auszuschließen.

---

## Offene Punkte

- Konkrete Voreinstellungen für Aufbewahrungsfristen je Space-Art und für die Mindestgruppengröße bei Auswertungen.
- Verschachtelte Gruppen im Verzeichnis (Gruppe als Mitglied einer Gruppe) — Auflösungsregel offen.
- Ob und wie mitgelieferte Assets in ein Netz ohne Internetanbindung gelangen (Aktualisierungsweg, Signatur, Prüfung).
- Freigabe- und Review-Workflow sowie Versionierung von Assets — bewusst außerhalb dieser Ausbaustufe.
- Übernahme von Berechtigungen aus Quellsystemen zusätzlich zu den Bibliotheksrechten.
- Verhalten bei Auflösung einer Organisationseinheit, die Eigentümerin von Assets ist.

---

## Verwandte Dokumente

- [ADR-0008: Space- und Asset-Modell](../decisions/0008-space-and-asset-model.md) — Entscheidung mit Optionen und Konsequenzen
- [Zugangskontrolle](./access-control.md) — Systemverwaltung, Nutzerverwaltung, Audit und Compliance
- [Daten-Indizierung & RAG](./data-indexing-rag.md) — Aufnahme, Chunking, Abfrageablauf
- [Diskussion: Workspace-Konzept](../discussions/discussion-workspace-concept.md) — Vorgeschichte und abgelöste Annahmen
