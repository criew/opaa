# Spaces, Assets & Zugangskontrolle

## Motivation

Wissen und KI-Können sollen in einer Organisation ankommen, nicht in Silos verharren. Das bisherige Workspace-Modell hat beides in denselben Behälter gesperrt: Ein Workspace war zugleich Dokumentencontainer, Rechtegrenze und Ordnungsrahmen. Damit war ein guter Agent nicht teilbar, ohne den ganzen Workspace zu teilen, und eine Wissenssammlung nicht mehrfach verwendbar, ohne sie zu duplizieren.

Dieses Dokument beschreibt das abgelöste Modell: **Assets** (Wissensbibliotheken, Agenten, Prompt-Bibliotheken) sind eigenständige, teilbare Objekte mit eigenem Eigentümer. **Spaces** sind thematische Arbeitsräume, in denen gearbeitet wird und in denen Ergebnisse entstehen.

---

## Überblick

1. **Assets** gehören ihrem Eigentümer und tragen ihre eigenen Rechte. Sie werden in Spaces *assoziiert* — die Assoziation gewährt keinerlei Zugriff.
2. **Spaces** sind Arbeitsräume. Chats und Artefakte entstehen *in* einem Space und gehören ihm; jedes Space-Mitglied sieht sie.
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
| Rechteanker | **eigene ACL am Asset** | **Space-Mitgliedschaft** |
| Entstehung | anderswo erzeugt, hineingereicht | im Space erzeugt |

**Präzise Fassung der Grundregel — beide Hälften gelten:**

- Space-Mitgliedschaft gewährt **keinen** Zugriff auf assoziierte Assets und deren Dokumente.
- Space-Mitgliedschaft gewährt **vollen** Zugriff auf space-eigene Inhalte.

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
| `ADMIN` | zusätzlich teilen, Rechte vergeben, Freigabestufe und Auffindbarkeit setzen |
| `OWNER` | zusätzlich löschen und Eigentum übertragen |

Die Trennung **`USER` gegen `VIEWER`** ist der wesentliche Zugewinn: Ein Sachgebiet soll einen geprüften Agenten nutzen können, ohne dass jeder Nutzer seine Aufgabenbeschreibung ändern oder die zugrundeliegende Dokumentenliste einsehen kann.

Asset-Rollen sind eine **eigene Rangordnung**. Sie haben mit den Space-Rollen weder Namensraum noch Rangfolge gemeinsam, auch wenn einzelne Namen ähnlich klingen.

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
- **Sie tragen die Verteilungsstufen** (siehe nächster Abschnitt) und lösen das Problem der verwaisten Assets (siehe [Eigentümerschaft](#eigentümerschaft-und-verwaisung)).

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

**Regelung:**

1. **Eigentümer kann eine Person oder eine Gruppe sein.** Für zentral gepflegte Assets — Rechtsquellen, Dienstanweisungen, hausweite Agenten — ist die **Gruppe der Regelfall**: Eigentümer ist „Referat Z 2", nicht Frau Müller. Damit übersteht das Asset jeden Personalwechsel, und die Zuständigkeit ist im Katalog als Organisationseinheit ausgewiesen.
2. **Erzwungene Nachfolge beim Offboarding.** Ein Nutzerkonto kann nicht deaktiviert werden, solange es Assets besitzt. Die Deaktivierung verlangt eine Übertragung — an eine Person oder eine Gruppe.
3. **Verwaist-Status als Sicherheitsnetz.** Fällt ein Eigentümer trotzdem weg (Massen-Deprovisionierung aus dem Verzeichnis, gelöschte Gruppe), wird das Asset als **verwaist** markiert und fällt an den System-Admin. Es wird dabei **niemals** stillschweigend gelöscht und **niemals** stillschweigend in seiner Reichweite verändert. Bestehende Grants bleiben unverändert bestehen, damit die laufende Arbeit nicht abreißt; das Asset erscheint aber in einer Aufräumliste.

Verfall — also automatisches Löschen verwaister Assets — wird ausdrücklich verworfen: In der Verwaltung ist der Verlust einer gepflegten Wissensbibliothek teurer als ihr Weiterbestehen unter unklarer Zuständigkeit.

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
| `MEMBER` | Space betreten; Chats anlegen und führen; **alle** Chats und Artefakte des Space lesen; kuratierte Assets sehen — gefiltert auf den eigenen Zugriff |
| `CURATOR` | zusätzlich Assets assoziieren und lösen, Inhalte ordnen |
| `ADMIN` | zusätzlich Mitglieder und Rollen verwalten, Einstellungen und Policy-Obergrenze setzen, Chats und Artefakte moderieren und löschen |

Dazu trägt jeder Space eine `ownerId` als **Attribut** — den fachlich Verantwortlichen, der im Verzeichnis ausgewiesen wird. Einen Space löschen oder die Verantwortung übertragen darf nur der Verantwortliche selbst oder ein System-Admin.

Warum drei statt der bisherigen vier Rollen:

- `VIEWER` und `EDITOR` implizierten Zugriff auf Dokumente. Genau diesen Fehlschluss soll das neue Modell vermeiden; die Umbenennung ist semantisch notwendig.
- `OWNER` als eigener Rang trug sein Gewicht daraus, dass eine Workspace-Löschung alle Dokumente vernichtete. Das ist nicht mehr so — Dokumente liegen in Bibliotheken, die anderen gehören. Der Schutz bleibt über das `ownerId`-Attribut erhalten, ohne vierte Rangstufe.
- `ADMIN` gewinnt dagegen an Gewicht, weil Policy-Obergrenze und Mitgliederverwaltung an ihm hängen — und Mitgliederverwaltung ist in gemischten Spaces folgenreich (siehe [Das Ableitungsleck](#das-ableitungsleck)).

### Assets in einen Space assoziieren

Ein Kurator kann jedes Asset, auf das er selbst Zugriff hat, in seinen Space assoziieren. Das ist unbedenklich, weil die Assoziation **keine Rechte gewährt** — sie stellt das Asset lediglich im Space zur Verfügung, und zwar nur für die Mitglieder, die ohnehin Zugriff darauf haben.

Der Eigentümer des Assets sieht alle Assoziationen und kann jede davon jederzeit einseitig lösen. Das Asset bleibt Herr über seine Verbreitung.

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

### Chats

Ein Chat ist ein **persistentes Objekt im Space**, kein flüchtiger Kontext. Ein Space enthält n Chats. Alle Space-Mitglieder sehen alle Chats des Space — der Space ist ein gemeinsamer Arbeitsraum, und Transparenz im Team ist gewollt.

Diese Regel gilt **ohne Ausnahme**, auch im referatsübergreifenden Projekt-Space: Es gibt keine private Markierung und keinen Ausnahmemechanismus für einzelne Chats. Die Regel ist bewusst einfach gehalten, weil jede Ausnahme die Vorhersagbarkeit zerstört: Sobald es private Chats gäbe, müsste jeder Nutzer bei jeder Nachricht prüfen, in welchem Modus er gerade ist. Wer Vertrauliches bespricht, wählt einen anderen Space — das ist die einzige Regel, die man sich merken muss.

**Konsequenz für die Nutzerführung.** Weil die Regel nicht am einzelnen Chat, sondern am Space hängt, muss der Space als Ort jederzeit präsent sein. Verbindlich:

- Der Chat zeigt dauerhaft, **in welchem Space** er läuft und **wer mitliest** — nicht in einem Untermenü, sondern im Kopfbereich, mit Zugriff auf die Mitgliederliste.
- Beim Anlegen eines Chats in einem Space mit vielen oder referatsfremden Mitgliedern wird der Leserkreis **vor** der ersten Nachricht benannt.
- Der Wechsel des Space ist eine sichtbare Handlung, nie eine stillschweigende Voreinstellung.
- Der persönliche Space ist als der Ort erkennbar, an dem niemand mitliest.

Ein Chat kann an einen Agenten gebunden sein. Ist er das, bestimmt der Agent den Suchbereich; ist er es nicht, bestimmt ihn der Space.

Das Datenmodell hält von Anfang an die Achsen offen, die für Mensch+KI-Gruppenräume gebraucht werden (Teilnehmer mit Lese-/Schreibrolle, Antwort-Bezug für Threads, Erwähnungen), auch wenn diese Funktionen erst später gebaut werden. So steht das Modell einer späteren Ausbaustufe nicht im Weg.

### Artefakte

In einem Space entstehen Ergebnisse: eine Excel-Auswertung, ein Diagramm, später Berichte, Entwürfe und Analysen. Diese **Artefakte** sind ebenfalls space-eigene Inhalte und für alle Space-Mitglieder sichtbar.

Die Objektklasse ist bewusst allgemein gehalten, damit weitere Ergebnistypen ohne Modelländerung hinzukommen können.

#### Lebenszyklus

Ohne Ordnung liegt in einem Projekt-Space nach kurzer Zeit ein unsortierter Haufen. Deshalb gilt:

- **Zuordnung:** Jedes Artefakt kennt den Chat, aus dem es entstanden ist, und seinen Erzeugungszeitpunkt.
- **Versionierung:** Ein neues Artefakt kann ein bestehendes ersetzen. Das ersetzte wird als überholt markiert, bleibt aber auffindbar.
- **Status:** `DRAFT` (nur für den Ersteller) → `ACTIVE` → `SUPERSEDED` oder `DELETED`.
- **Löschen:** durch Ersteller und Space-Admin, protokolliert.
- **Aufbewahrung:** Je Space konfigurierbare Regel, damit Projekt-Spaces nicht unbegrenzt wachsen.
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

Dies ist der schwierigste Punkt des Modells. Chats und Artefakte sind zwei Ausprägungen **desselben** Problems und werden deshalb hier gemeinsam behandelt.

### Das Problem

Wissen fließt aus einer Bibliothek mit **engem** Leserkreis in ein space-eigenes Objekt mit **weiterem** Leserkreis. Dabei wechselt der Rechteanker: von der Asset-Rechteliste zur Space-Mitgliedschaft. Jedes im Space entstehende Objekt ist damit ein potenzieller Kanal — die Antwort im Chat, das Zitat im Verlauf, die Zahl in der Excel-Auswertung.

Der Zitierzwang verschärft das: Er ist ein Kernversprechen von OPAA und sorgt dafür, dass wörtliche Passagen dauerhaft im Verlauf stehen.

**Der Preis dieser Modellentscheidung, in einem Satz:**

> In einem Space, in dem Bibliotheken mit engerem Leserkreis genutzt werden, wird die Space-Mitgliedschaft faktisch zum effektiven Leserkreis für alles, was dort entsteht. Jemanden einem solchen Space hinzuzufügen ist so folgenreich, wie ihm Leserechte an diesen Bibliotheken zu geben — nur sieht es nicht so aus.

Das ist eine bewusste Entscheidung zugunsten der Zusammenarbeit, keine Fehlfunktion. Sie muss aber bekannt sein, weil sonst die Mitgliederverwaltung gemischter Spaces unterschätzt wird.

### Warum Zitat-Redaktion das Problem nicht löst

Naheliegend wäre, Zitate beim Lesen gegen die Rechte des Lesenden zu prüfen und zu maskieren. Das ist **unvollständig, nicht bloß aufwendig**:

1. **Der Antworttext bleibt.** Der eigentliche Kanal ist nicht das wörtliche Zitat, sondern die Aussage, die das Modell daraus gebildet hat. Maskiert man nur das Zitat, bleibt die Information erhalten — nur schlechter belegt. Maskiert man auch die Aussage, bleibt vom Chat nichts übrig.
2. **Sie zerstört die Transparenz, für die der gemeinsame Raum da ist.** Jeder sähe einen anderen Verlauf.
3. **Sie verlagert die Prüfung auf jeden Lesevorgang** statt auf den Entstehungsvorgang — teurer und fehleranfälliger.

Davon zu unterscheiden sind **Zitat-Sprungmarken**: Der Sprung in das Quelldokument wird beim Klick gegen die Rechte des Lesenden geprüft und gegebenenfalls verweigert. Das ist billig und sinnvoll und bleibt bestehen — es verhindert, dass sich jemand von einem Zitat in den vollen Bestand weiterhangelt. Das Substanzleck löst es nicht.

**Schlussfolgerung:** Die Lösung liegt **vor** der Entstehung, nicht beim Lesen — nicht Redaktion, sondern Abgleich der Leserkreise plus Rechenschaft.

### Lösung für Chats

Ein Chat entsteht nicht in einem Moment, sondern wächst; seine Herkunft ergibt sich erst im Verlauf. Ein Umschlagen der Sichtbarkeit mitten im Gespräch wäre unbrauchbar. Deshalb wirkt die Steuerung früher:

1. **Leserkreis-Abgleich bei der Assoziation.** Assoziiert ein Kurator eine Bibliothek in einen Space, dessen Mitglieder nicht sämtlich Lesezugriff auf sie haben, wird die Assoziation als **gemischt** gekennzeichnet, und der Kurator bestätigt einmal: *„Antworten aus dieser Bibliothek werden für alle Space-Mitglieder sichtbar, auch für die, die sie selbst nicht öffnen dürfen."* Das ist der Moment, in dem der Kanal geöffnet wird — hier gehört die Entscheidung hin.
2. **Kennzeichnung.** Ein Space mit mindestens einer gemischten Assoziation trägt eine sichtbare Markierung, ebenso jeder Chat, der aus einer solchen Bibliothek Treffer verwendet hat.
3. **Herkunftsverfolgung.** Jeder Chat führt mit, aus welchen Bibliotheken tatsächlich Treffer stammten. Grundlage für Kennzeichnung, Audit und spätere Rückfragen.
4. **Strikt-Modus je Space (optional).** Es dürfen nur Bibliotheken assoziiert und genutzt werden, deren Leserkreis alle Space-Mitglieder umfasst. Ein solcher Space ist garantiert leckfrei. Standardmäßig aus; empfohlen für Räume wie „Rechnungsprüfung" oder „Personal" und durch den System-Admin vorgebbar.
5. **Zitat-Sprungmarken bleiben rechtegeprüft.**

Damit bleibt die Zusage erhalten, dass alle Mitglieder alle Chats sehen — und die Entscheidung über den Kanal fällt bei der Kuratierung, durch einen Menschen, protokolliert.

### Lösung für Artefakte

Für Artefakte trägt Redaktion **prinzipiell** nicht: Ein Diagramm oder eine Tabelle ist abgeleitetes Material ohne Quellenstruktur. Es gibt keine markierten Stellen zum Ausblenden, und eine teilredigierte Tabelle ist wertlos. Deshalb greift hier eine andere Lösung.

Geprüfte Richtungen:

| Richtung | Bewertung |
|---|---|
| Sichtbarkeit folgt der Schnittmenge der Quell-Leserkreise | **Verworfen.** Ein Artefakt aus drei Bibliotheken wäre oft für niemanden außer dem Ersteller sichtbar, und die Sichtbarkeit änderte sich rückwirkend bei jeder Rechteänderung |
| Erzeugung nur aus Quellen erlaubt, die alle Mitglieder sehen dürfen | **Verworfen als Pflicht** — macht Artefakterzeugung in gemischten Spaces unbrauchbar. Bleibt als Strikt-Modus sinnvoll |
| Erzeugung gilt als bewusster Freigabeakt, protokolliert | **Trägt** — entspricht dem Verwaltungshandeln: Wer lesen durfte, darf berichten und verantwortet es |
| Herkunftskennzeichnung am Artefakt | **Trägt als Ergänzung**, nicht allein — Kennzeichnung ohne Entscheidungsmoment bleibt folgenlos |

**Gewählte Lösung — bewusster Freigabeakt mit Herkunftskennzeichnung, aber mit gezielter statt pauschaler Reibung:**

- Stammt das Artefakt **ausschließlich** aus Bibliotheken, die alle Space-Mitglieder lesen dürfen, wird es ohne Rückfrage space-sichtbar. Das ist der Normalfall und darf keine Klickstrecke auslösen.
- Ist **mindestens eine** Quelle enger als der Space, entsteht das Artefakt zunächst **nur für den Ersteller sichtbar**, mit der Aktion „für den Space freigeben". Erst diese Freigabe macht es sichtbar; sie wird mit Herkunft, Zeitpunkt und Person protokolliert.
- Jedes Artefakt trägt eine sichtbare **Herkunftskennzeichnung** und ist auf seinen Ursprungs-Chat rückführbar.
- Ersteller und Eigentümer jeder beteiligten Bibliothek können ein freigegebenes Artefakt nachträglich wieder einschränken.

Warum nicht einfach ein Bestätigungsdialog bei jeder Erzeugung: Reibung, die immer auftritt, wird binnen einer Woche blind weggeklickt und schützt dann nichts mehr. Sie wirkt nur, wenn sie selten und begründet ist. Der kritische Fall ist technisch erkennbar, weil die Herkunft ohnehin geführt wird.

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
| **Chat im Space** | **Gilt nicht.** Bewusst geöffneter Kanal — die Entscheidung fällt bei der Assoziation und wird protokolliert |
| **Artefakt im Space** | **Gilt eingeschränkt.** Bei gemischter Herkunft erst nach ausdrücklicher Freigabe sichtbar |

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

Zwei Eigenschaften dieses Modells berühren in einer deutschen Behörde das Personalvertretungsrecht — nicht am Rande, sondern als **Einführungshindernis**: Ohne Dienstvereinbarung beginnt kein Rollout, und der Personalrat wird genau diese Punkte ansprechen.

1. **Alle Space-Mitglieder sehen alle Chats.** Damit ist für jeden Beschäftigten nachvollziehbar, wer im Space womit gearbeitet hat, wie oft und mit welchem Ergebnis.
2. **Nutzungstransparenz je Asset** („wer nutzt welches Asset wie oft"), die die Produktvision als Steuerungsinstrument für den KI-Rollout vorsieht.

Beides zusammen ist geeignet, Verhalten und Leistung von Beschäftigten abzubilden. Damit liegt der Mitbestimmungstatbestand der Einführung und Anwendung technischer Einrichtungen nahe, die zur Überwachung von Verhalten oder Leistung **bestimmt oder geeignet** sind (BPersVG und die entsprechenden Landespersonalvertretungsgesetze). Das ist eine Einschätzung aus Produktsicht und keine Rechtsberatung; die Bewertung obliegt der einführenden Stelle.

Hinzu kommt als dritte Datenquelle das revisionssichere **Audit-Log**, das für die C5-Fähigkeit ohnehin gebraucht wird.

### Stellschrauben, die das Produkt anbietet

Die Aufgabe des Produkts ist nicht, die Mitbestimmung zu umgehen, sondern der Behörde die Regelungsmöglichkeiten zu geben, die eine Dienstvereinbarung braucht:

| Stellschraube | Ausprägung |
|---|---|
| **Aggregation statt Personenbezug** | Nutzungsstatistiken standardmäßig nur aggregiert je Organisationseinheit, mit Mindestgruppengröße; personenbezogene Auswertung nur, wenn ausdrücklich eingeschaltet |
| **Keine Ranglisten** | Kein Vergleich einzelner Beschäftigter, keine Bestenlisten, keine Aktivitätsbewertung — auch nicht als Gamification |
| **Abschaltbarkeit** | Nutzungsstatistiken je Asset und je Organisationseinheit vollständig deaktivierbar, ohne dass die Fachfunktion leidet |
| **Aufbewahrungsfristen** | Chatverläufe und Artefakte mit konfigurierbarer, je Space-Art vorgebbarer Frist; automatische Löschung nach Ablauf |
| **Getrennte Zugriffswege** | Auswertungen für die Revision sind nicht dieselben wie Auswertungen für die Dienststellenleitung; Audit-Zugriff ist selbst protokolliert |
| **Zweckbindung dokumentiert** | Für jede erhobene Kennzahl ist dokumentiert, wofür sie da ist — Grundlage der Dienstvereinbarung |
| **Auskunft für den Personalrat** | Export, welche personenbeziehbaren Daten erhoben werden, in welcher Granularität und wie lange sie liegen |

### Wirkung auf das Modell

Der Space-Zuschnitt ist auch aus dieser Sicht das entscheidende Werkzeug: Wer einen Chat in seinem persönlichen Space führt, arbeitet unbeobachtet; wer ihn in einem Team-Space führt, arbeitet sichtbar. Weil diese Wahl **bewusst, sichtbar und jederzeit erkennbar** ist (siehe [Chats](#chats)), ist sie auch gegenüber der Personalvertretung darstellbar — anders als eine Protokollierung, die im Hintergrund läuft.

---

## Offene Punkte

- Herkunft und Pflege von Gruppen aus dem Verzeichnisdienst (Synchronisation, Verschachtelung, Konfliktbehandlung) — das Rechtemodell steht, die Anbindung ist offen.
- Konkrete Voreinstellungen für Aufbewahrungsfristen je Space-Art.
- Freigabe- und Review-Workflow sowie Versionierung von Assets — bewusst außerhalb dieser Ausbaustufe.
- Übernahme von Berechtigungen aus Quellsystemen zusätzlich zu den Bibliotheksrechten.
- Verhalten bei Auflösung einer Organisationseinheit, die Eigentümerin von Assets ist.

---

## Verwandte Dokumente

- [ADR-0008: Space- und Asset-Modell](../decisions/0008-space-and-asset-model.md) — Entscheidung mit Optionen und Konsequenzen
- [Zugangskontrolle](./access-control.md) — Systemverwaltung, Nutzerverwaltung, Audit und Compliance
- [Daten-Indizierung & RAG](./data-indexing-rag.md) — Aufnahme, Chunking, Abfrageablauf
- [Diskussion: Workspace-Konzept](../discussions/discussion-workspace-concept.md) — Vorgeschichte und abgelöste Annahmen
