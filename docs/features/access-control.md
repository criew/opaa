# Identität, Rechte & Mandanten: Systemverwaltung, Anmeldung und Kontenlebenszyklus

> **Status: Entwurf — wesentliche Festlegungen stehen, einzelne Fragen sind offen.**
>
> **Phasenlage:** Phase 1. Anmeldung über den Verzeichnisdienst, Kontenlebenszyklus, Gruppen als
> Rechtesubjekt und rechtebewusste Suche gehören zum Fundament; ohne sie ergibt ein Start in einer
> Behörde keinen Sinn. Feinschliff an Sitzungsverwaltung und Rezertifizierung folgt in Phase 2.

> **Abgrenzung:** Das Space-, Asset- und Rechtemodell ist in
> [Spaces, Assets & Zugangskontrolle](./spaces-and-assets.md) beschrieben und entschieden und wird hier
> **nicht wiederholt**. Die Sicherheits-, Nachweis- und Prüfbarkeitsthemen — revisionssicheres
> Protokoll, Rechtehistorie, DSGVO-Vollständigkeit, C5-Fähigkeit, Mitbestimmungsfähigkeit — stehen in
> [Sicherheit, Nachweis & Prüfbarkeit](./security-and-compliance.md). Dieses Dokument behandelt
> Systemverwaltung, Identität und den Lebenszyklus der Konten. Das frühere Workspace-Konzept ist
> abgelöst.

## Motivation

Nicht alles Organisationswissen ist für jeden bestimmt. Ein Haus hat öffentliche Richtlinien,
fachbereichsbezogene Dokumentation, besonders geschützte Bestände und Unterlagen, die nur der Revision
zugänglich sind.

Wer was sehen darf, regelt das Rechtemodell in
[Spaces, Assets & Zugangskontrolle](./spaces-and-assets.md). Wer das System verwaltet, woher Identitäten
kommen, wie sie entstehen und wie sie wieder verschwinden, regelt dieses Dokument. Der Lebenszyklus ist
dabei der sicherheitskritische Teil: Ein Konto, das nach dem Ausscheiden weiterbesteht, ist ein
Zugangsweg, den niemand mehr beobachtet.

---

## Überblick

Die Zugangskontrolle in OPAA hat vier Schichten:

1. **Asset-Rechte** — wer auf Wissensbibliotheken, Agenten und Prompt-Bibliotheken zugreift →
   [Spaces, Assets & Zugangskontrolle](./spaces-and-assets.md)
2. **Space-Rollen** — wer in einem Arbeitsraum mitarbeitet, kuratiert und verwaltet → ebenda
3. **Systemverwaltung** — wer das System als Ganzes verwaltet → dieses Dokument
4. **Identität und Kontenlebenszyklus** — woher Nutzer kommen, wie sie sich anmelden und wie ihr Zugang
   endet → dieses Dokument

Die fünfte Schicht — der **Nachweis**, dass diese vier Schichten gewirkt haben — steht in
[Sicherheit, Nachweis & Prüfbarkeit](./security-and-compliance.md).

---

## Systemverwaltung

### System-Admin-Rolle

Über den Space- und Asset-Rollen steht die **System-Admin**-Rolle für organisationsweite Administration.
Sie ist eine systemweite Rolle und wird auf der Benutzer-Entität gespeichert.

System-Admins können:

- Team- und Fachbereichs-Spaces anlegen und löschen (Projekt-Spaces legen Nutzer selbst an)
- Konnektoren konfigurieren
- Quell-Zuordnungen definieren — welche Quelle in welche Wissensbibliothek indiziert
- Die Freigabe-Obergrenze konnektor-gespeister Bibliotheken setzen
- Benutzerverzeichnis-Synchronisation und Gruppen konfigurieren
- Globale Modell-Policies und Governance-Einstellungen setzen
- Assets im Zustand „Nachfolge offen" einer neuen Zuständigkeit zuweisen

**System-Admins existieren je Organisation.** Die Mandantengrenze gilt auch für sie; es gibt keine
organisationsübergreifende Sicht.

Wichtige Abgrenzung: System-Admins verwalten das System, sind aber **nicht automatisch berechtigt, jeden
Inhalt zu lesen**. Der Zugriff auf Wissensbibliotheken folgt der Rechteliste des Assets. Wo eine Übernahme
nötig ist (offene Nachfolge, Offboarding), ist sie ein protokollierter Verwaltungsakt und keine
stillschweigende Leseberechtigung. Entwürfe und persönliche Spaces bleiben auch dabei unlesbar.

### Dokumentenfluss: Konnektoren gegen Benutzer-Uploads

Die zwei Wege, auf denen Dokumente in OPAA gelangen, haben unterschiedliche Autorisierungsanforderungen:

- **Konnektoren (System-Admin):** System-Admins konfigurieren Konnektoren und legen fest, welche Quelle in
  welche Wissensbibliothek indiziert. Der primäre Weg für automatisierte Massenaufnahme.
- **Manuelle Uploads:** Wer an einer Wissensbibliothek mindestens `EDITOR` ist, kann Dokumente hochladen —
  in seine persönliche Bibliothek oder in jede andere, an der er dieses Recht hat.

Wesentliche Verschiebung gegenüber dem alten Modell: Der System-Admin entscheidet, **wohin** indiziert
wird; der Bibliotheks-Eigentümer entscheidet, **wer es sieht**.

**Die Freigabe-Obergrenze ist die einzige technische Sicherung zwischen „Fachverfahrensdaten eingespeist"
und „organisationsweit lesbar" und deshalb genau zu bestimmen:**

- Gedeckelt werden `visibility`, `listed` und Grants an Gruppen oberhalb einer festgelegten Größe.
- Wird die Obergrenze **nachträglich gesenkt**, werden bereits erteilte weitergehende Grants
  **ausgesetzt, nicht stillschweigend entzogen**: Sie stehen auf einer Liste des Bibliotheks-Eigentümers
  und wirken nicht mehr, bis er sie anpasst. Für eine Prüfung ist das der Unterschied zwischen „behoben"
  und „nicht behoben"; ein stilles Weiterwirken wäre das eine, ein stiller Entzug das andere Extrem.
- Eine Bibliothek, die sowohl aus einem Konnektor als auch aus manuellem Upload gespeist wird, **trägt die
  Obergrenze ebenfalls** — sonst wäre der manuelle Upload der Weg an ihr vorbei.

### Löschung eines Space

Ein Space zu löschen ist unter dem neuen Modell ein vergleichsweise harmloser Vorgang: Er vernichtet
**keine Dokumente**, weil diese in Wissensbibliotheken liegen, die anderen gehören. Gelöst werden die
Assoziationen; die Assets selbst bleiben unberührt.

**Space-eigene Inhalte werden dabei nicht gelöscht.** Die Regel „Zurückziehen statt Löschen" gilt auch
hier: Abgelegte Chats und Artefakte werden zurückgezogen und bleiben für ihre Autoren und im Nachweis
erhalten; Entwürfe bleiben ihren Erstellern erhalten. Andernfalls wäre die Space-Löschung ein
Massenlöschpfad für die Arbeitsspuren fremder Beschäftigter — genau das, was der Schutz an anderer Stelle
ausschließt. Ein Protokolleintrag hält den Vorgang fest.

Löschen darf nur der im Space als Verantwortlicher hinterlegte Nutzer oder ein System-Admin.

---

## Anmeldung und Identität

Benutzer authentifizieren sich über:

- **Single Sign-On (SSO)** — OIDC oder SAML (empfohlen)
- **Lokale Konten** — Benutzername und Passwort (nur als Rückfallebene)
- **API-Tokens** — für programmatischen Zugang

**Empfohlen ist die SSO-Anbindung an das im Haus vorhandene Identitätsmanagement.** Sie ist nicht nur
bequemer, sondern die Voraussetzung dafür, dass der Kontenlebenszyklus überhaupt an einer Stelle geführt
werden kann. Lokale Konten sind eine Rückfallebene für den Anlaufbetrieb und für Notfallzugänge; jedes
dauerhaft betriebene lokale Konto ist eine Ausnahme, die begründet und regelmäßig überprüft gehört, weil
es am zentralen Ausscheideprozess vorbeiläuft.

Die Mandantengrenze gilt auch für die Anmeldung: Eine Identität gehört zu **genau einer** Organisation.
Es gibt kein Konto, das mehrere Mandanten sieht, und keinen Wechsel zwischen ihnen innerhalb einer
Sitzung.

---

## Verzeichnisdienst: Synchronisation und Kontenlebenszyklus

### Was übernommen wird

OPAA gleicht mit dem Verzeichnisdienst ab — bevorzugt über eine Bereitstellungsschnittstelle, die
Änderungen aktiv meldet, ersatzweise über einen wiederkehrenden Abgleich:

```
Abgleich: ereignisgesteuert, ersatzweise turnusmäßig (z. B. alle 6 Stunden)

Aus dem Verzeichnis:
  - Benutzernamen und E-Mail-Adressen
  - Gruppenmitgliedschaften
  - Organisationseinheit (Referat, Abteilung, Amt)
  - Funktionsbezeichnung
  - Kontostatus (aktiv / gesperrt / ausgeschieden)
```

Das Verzeichnis ist die **führende Quelle**. Wer dort gesperrt ist, ist in OPAA gesperrt; ein
abweichender Zustand in OPAA ist kein Zustand, den ein Admin von Hand herstellen können sollte.

### Der Lebenszyklus eines Kontos

```
   Anlage im            erster            Rollenwechsel /        Ausscheiden
   Verzeichnis          Login             Gruppenänderung        im Verzeichnis
        │                 │                     │                     │
        ▼                 ▼                     ▼                     ▼
   Konto bereit  →   Konto aktiv   →   Rechte neu berechnet   →   sofort deaktiviert
                                              │                          │
                                              ▼                          ▼
                                    Rechteereignis im Protokoll   Assets: „Nachfolge offen"
```

1. **Anlage.** Ein Konto entsteht durch die Bereitstellung aus dem Verzeichnis, nicht durch eine
   Einladung im Produkt. Wer im Verzeichnis nicht existiert, hat in OPAA nichts.
2. **Erste Anmeldung.** Persönlicher Space und persönliche Wissensbibliothek entstehen dabei; ein
   Anlaufbestand an Assets ergibt sich aus den Gruppen der Person.
3. **Änderung.** Wechselt jemand das Referat, ändern sich seine Gruppenmitgliedschaften — und damit
   seine Rechte, **ohne dass jemand in OPAA etwas tut**. Das ist der Regelfall und der Grund, warum die
   Synchronisation als Rechteereignis behandelt wird (siehe unten).
4. **Ausscheiden.** Verschwindet ein Konto im Verzeichnis oder wird es dort gesperrt, wird der Zugang in
   OPAA **beim nächsten Abgleich automatisch entzogen** — ohne Ticket, ohne Handgriff und ohne
   Bedingung. Das ist die Anforderung, an der der IT-Grundschutz und jede Prüfung als Erstes ansetzen.

**Die Deaktivierung wird nie durch offene Eigentumsfragen aufgehalten.** Eine Regel, die verlangt, erst
die Nachfolge für dutzende Assets zu klären, wird am Freitagnachmittag umgangen und schützt dann gerade
nicht. Was mit den Assets geschieht, steht unter [Offboarding](#offboarding).

### Gruppensynchronisation ist ein Rechteereignis

Die übernommenen **Gruppen sind Rechtesubjekt**: Rechte an Assets werden an Nutzer oder an Gruppen
vergeben, und die Verteilungsstufe „Fachbereich" ist ein Grant an die Abteilungs- oder Amts-Gruppe.
Details im [Rechtemodell](./spaces-and-assets.md#gruppen-als-rechtesubjekt).

Daraus folgt eine Festlegung, die leicht übersehen wird: Ein Synchronisationslauf ist **keine technische
Wartungsroutine, sondern eine Rechteänderung im laufenden Betrieb**. Eine einzige geänderte
Gruppenmitgliedschaft kann Zugriff auf ganze Bestände geben oder nehmen.

- Jede **bewirkte** Rechteänderung wird einzeln festgehalten — je Änderung, nicht je Lauf. Ein Lauf, der
  meldet „412 Objekte verarbeitet", beantwortet keine Prüferfrage.
- Änderungen wirken **sofort** auf die rechtebewusste Suche; es gibt keinen zwischengespeicherten
  Rechtestand, der eine Entziehung überdauert.
- Ein Lauf, der eine **auffällig große** Zahl an Entzügen oder Zuweisungen bewirken würde — etwa weil im
  Verzeichnis eine Gruppe umbenannt wurde —, wird angezeigt und ist bestätigungspflichtig, statt
  stillschweigend durchzulaufen. Der häufigste Fehlerfall ist nicht der Angriff, sondern die
  fehlgeschlagene Umstellung.
- Fällt der Verzeichnisdienst aus, gilt der **letzte bekannte Stand weiter**, und der Ausfall wird
  gemeldet. Ein leeres Abgleichergebnis darf nie als „alle Gruppenmitgliedschaften entfallen" gedeutet
  werden.

Die Synchronisation ändert nur die **Herkunft** von Gruppenmitgliedschaften, nicht das Rechtemodell. In
der ersten Ausbaustufe werden Gruppen im System gepflegt.

Der Nachweis, worauf eine Person zu einem beliebigen Stichtag Zugriff hatte, entsteht aus der
Historisierung dieser drei Quellen und ist in
[Sicherheit, Nachweis & Prüfbarkeit](./security-and-compliance.md#nachweisbarkeit-historisierung-von-rechten)
beschrieben.

---

## Sitzungen, Netzbereiche und erzwungene Neuanmeldung

### Einschränkung auf Netzbereiche

Der Zugang lässt sich auf Netzbereiche einschränken (CIDR-Notation), getrennt für die interaktive
Anmeldung und für API-Identitäten:

```
Netzbereiche (organisationsweite Vorgabe):
  interaktiv:   Hausnetz + VPN-Bereich der Dienststelle
  API-Tokens:   je Token eng gesetzt, Voreinstellung: nur Hausnetz
  Ausnahmen:    benannt, befristet, begründet
```

Die Einschränkung ist eine **Zugangs-, keine Auswertungsfunktion**. Sie prüft, ob eine Verbindung
zulässig ist; sie erzeugt keinen Aufenthaltsnachweis. Die Netzadresse ist deshalb auch **nicht Teil des
Standard-Protokollsatzes** — begründet in
[Sicherheit, Nachweis & Prüfbarkeit](./security-and-compliance.md#der-protokollsatz). Der abgewiesene
Verbindungsversuch wird als Sicherheitsereignis festgehalten, der zulässige nicht.

### Sitzungsverwaltung

- **Höchstdauer und Leerlauffrist** sind organisationsweit gesetzt, nicht je Nutzer verhandelbar.
- Eine Sitzung ist an ihre Identität gebunden. Endet die Gültigkeit beim Identitätsanbieter, endet sie in
  OPAA — spätestens beim nächsten Erneuerungsversuch, nicht erst nach Ablauf der eigenen Frist.
- **Übersicht der eigenen Sitzungen** für jede Person, mit der Möglichkeit, einzelne oder alle zu
  beenden. Das ist eine Selbstauskunfts- und Selbstschutzfunktion und für niemanden sonst sichtbar.
- **Laufende Antworten und Agentenläufe** werden beim Ende einer Sitzung abgebrochen, nicht im
  Hintergrund fortgeführt. Ein Lauf, der die Rechte einer beendeten Sitzung weiterträgt, wäre genau die
  Lücke, die die sofortige Wirkung von Rechteänderungen aushebelt.

### Erzwungene Neuanmeldung

Eine erzwungene Neuanmeldung beendet bestehende Sitzungen und verlangt eine erneute Authentisierung. Sie
wird ausgelöst:

- durch die Systemverwaltung — für eine Person, eine Gruppe oder alle, etwa nach einem
  Sicherheitsvorfall oder einer Änderung an den Modell- und Governance-Vorgaben, die vor der
  Weiterarbeit zur Kenntnis zu nehmen ist;
- **automatisch** bei Sperrung oder Ausscheiden im Verzeichnis;
- **automatisch** bei einer Rechteänderung, die den Zugang selbst betrifft (Entzug der
  System-Admin-Rolle, Wechsel der Organisationseinheit).

Der Vorgang ist protokollpflichtig. Er ist ein Verwaltungsakt gegenüber der betroffenen Person und kein
stiller Eingriff: Wer neu anmelden muss, erfährt beim nächsten Aufruf, dass und warum.

---

## API-Tokens und Service-Accounts

```
API-Token erstellen:
  Name:        "Fachverfahren-Anbindung"
  Rechte:      erbt die Asset-Rechte des ausstellenden Nutzers oder Service-Accounts
  Umfang:      [read_documents, ask_questions]
  Rotation:    90 Tage
  Netzbereich: optional (CIDR)
  Rate-Limit:  konfigurierbar
```

Ein Token kann **nie mehr Rechte haben als sein Inhaber**. Service-Accounts sind reine API-Identitäten
ohne interaktive Anmeldung; sie erhalten ihre Rechte wie jeder andere Träger von Rechten über Grants oder
Gruppen.

Für den Lebenszyklus gilt dieselbe Logik wie für Personen: Ein Token, dessen ausstellender Nutzer
ausscheidet, **verliert seine Wirkung mit dessen Konto**. Ein Token, das die Deaktivierung überdauert,
wäre der bequemste Weg, den Kontenlebenszyklus zu umgehen. Service-Accounts brauchen deshalb einen
benannten menschlichen Verantwortlichen, der selbst dem Lebenszyklus unterliegt — fällt er weg, greift
dieselbe Nachfolgeregelung wie bei Assets.

---

## Offboarding

Wenn ein Nutzer die Organisation verlässt:

1. Die Verzeichnis-Synchronisation entfernt oder sperrt ihn; er kann sich nicht mehr anmelden, bestehende
   Sitzungen enden, seine Tokens wirken nicht mehr.
2. **Die Deaktivierung wird nie durch offene Eigentumsfragen aufgehalten.**
3. Seine Assets gehen in den Zustand **„Nachfolge offen"**: nutzbar und mit unveränderten Rechten, aber
   mit **eingefrorener Reichweite** — keine neuen Grants, keine höhere Freigabestufe, keine neue
   Bereitstellung. Zuständig für die Nachfolge ist der Kurator der Organisationseinheit, ersatzweise der
   System-Admin; der Vorgang erscheint mit Frist auf der Governance-Arbeitsliste.
4. Für zentral gepflegte Bestände ist Gruppen-Eigentum der Regelfall und verhindert das Problem von
   vornherein.
5. Sein persönlicher Space wird deaktiviert, nicht gelöscht (Nachweisgründe) — und **nicht lesbar
   gemacht**. Entwürfe darin bleiben unzugänglich. Chats und Artefakte unterliegen der Aufbewahrungsregel.

Die **Löschung** eines Kontos ist davon zu unterscheiden: Sie ist ein Vorgang nach DSGVO und in
[Sicherheit, Nachweis & Prüfbarkeit](./security-and-compliance.md#vollständigkeit-nach-dsgvo-löschung-und-export)
beschrieben.

---

## Sonderfälle

### Breiter Lesezugriff für Stabsstellen und Leitung

Nicht über eine Sonderrolle, sondern über **Gruppen**: Die Stabsstelle erhält als Gruppe Leserechte an den
einschlägigen Wissensbibliotheken. Das skaliert, ist im Katalog nachvollziehbar und läuft über denselben
Weg wie jede andere Freigabe — kein Sonderpfad, der bei einer Prüfung erklärt werden müsste.

### Revision und Rechnungsprüfung

Prüfende Stellen brauchen Unabhängigkeit. Empfohlen ist ein eigener Space im **Strikt-Modus** (nur
Bibliotheken, deren Leserkreis alle Mitglieder umfasst), damit in der Prüfung keine Inhalte an
Unberechtigte gelangen und die Prüfakte sauber abgegrenzt bleibt.

**Der Preis gehört an dieselbe Stelle wie die Empfehlung:** Ein hausweit geteilter Agent ist in aller
Regel an mindestens eine Bibliothek gebunden, deren Leserkreis die Prüfstelle nicht umfasst — im
Strikt-Modus ist er dort nicht aufrufbar. Die Prüfstelle verliert damit faktisch den größten Teil der
geteilten Agenten des Hauses. Das ist vertretbar und für die Unabhängigkeit sogar folgerichtig, muss aber
vor der Entscheidung bekannt sein und nicht drei Monate später auffallen.

### Externe Beteiligte

```
Nutzer:      externe Beraterin
Spaces:      [Projekt-X]
Assets:      Grant USER auf genau die benötigte Bibliothek
Befristung:  bis 2026-03-31
Hinweis:     Sie sieht alle Chats und Artefakte des Space — vor der Aufnahme prüfen
```

Die Aufnahme externer Personen ist besonders folgenreich, weil ihnen damit alle **abgelegten** Inhalte des
Space offenstehen — also die Arbeitsergebnisse namentlich bekannter Beschäftigter. Externe Konten sind
gekennzeichnet, die Aufnahme verlangt eine ausdrückliche Bestätigung und wird protokolliert. Ein bloßer
Hinweistext genügt hier nicht. Für solche Fälle ist ein eigener, eng geschnittener Space der richtige Weg.

Externe Konten stammen häufig **nicht** aus dem Verzeichnis des Hauses. Für sie ist die Befristung des
Kontos deshalb Pflicht und nicht Option — sie ersetzt den Ausscheideprozess, der bei eigenen Beschäftigten
automatisch greift.

---

## Integrationspunkte

- **Authentifizierung:** SSO-Anbieter und Verzeichnisdienst
- **Benutzer-Frontends:** Rechte an jeder Schnittstelle durchsetzen →
  [user-frontends.md](./user-frontends.md)
- **Daten-Indizierung:** Zuordnung von Quellen zu Wissensbibliotheken →
  [data-indexing-rag.md](./data-indexing-rag.md)
- **RAG-Engine:** Filter über die lesbaren Bibliotheken des Nutzers, als Teil der Vektorsuche
- **Modelle:** zentrale Vorgaben gelten je Organisation → [llm-integration.md](./llm-integration.md)
- **Sicherheit und Nachweis:** jede Rechte- und Kontenänderung erzeugt einen Protokolleintrag →
  [security-and-compliance.md](./security-and-compliance.md)
- **Betrieb:** Nutzer- und Gruppendaten aus dem Verzeichnis →
  [deployment-infrastructure.md](./deployment-infrastructure.md)

---

## Offene Fragen

- Attributbasierte Zugangskontrolle (ABAC) zusätzlich zu Rollen und Gruppen?
- Zeitlich befristete Rechte mit automatischem Verfall und turnusmäßiger Rezertifizierung — als Arbeit
  erfasst, im Schnitt aber noch offen.
- Genehmigungsworkflows für besonders geschützte Bestände?
- Klassifizierungsstufen (offen, intern, vertraulich) als eigenes Merkmal?
- **Rechte aus Quellsystemen:** Sollen Berechtigungen des Quellsystems zusätzlich zu den
  Bibliotheksrechten durchgesetzt werden? Grundsätzlich erwünscht, aber aufwendig — Benutzerkennungen und
  Rechtemodelle stimmen zwischen Quellsystem und OPAA nicht notwendig überein.
- **Mehrfachzugehörigkeit:** Wie werden Beschäftigte abgebildet, die zwei Organisationseinheiten
  angehören? Das Verzeichnis kennt den Fall, das Aggregationsmodell der Auswertung muss ihn ebenfalls
  vertragen.
- Welche Ereignisse eine erzwungene Neuanmeldung auslösen, ist als Ausgangsliste festgehalten und wird
  sich im Betrieb schärfen.

---

## Erfolgs-Metriken

- **Wirksamkeit des Lebenszyklus:** Zeit zwischen Sperrung im Verzeichnis und Wirkungslosigkeit des
  Zugangs in OPAA, einschließlich aller Sitzungen und Tokens. Ziel ist eine Größenordnung von Minuten,
  nicht von Tagen.
- **Leistung:** Rechteprüfung erhöht die Abfragezeit um weniger als 50 ms.
- **Genauigkeit:** keine unbeabsichtigten Zugriffe; kein Synchronisationslauf, der eine Rechteänderung
  bewirkt hat, ohne sie einzeln festzuhalten.
- **Verständlichkeit:** Der Anteil der Support-Anfragen, die sich auf „warum sehe ich das nicht" beziehen,
  sinkt über die ersten drei Monate.

---

## Verwandte Dokumente

- [Spaces, Assets & Zugangskontrolle](./spaces-and-assets.md) — das Rechtemodell
- [Sicherheit, Nachweis & Prüfbarkeit](./security-and-compliance.md) — Protokoll, Rechtehistorie, DSGVO,
  C5-Fähigkeit, Mitbestimmungsfähigkeit
- [Monitoring, Kosten & Governance](./monitoring-and-governance.md) — Grenzen, Kosten und Auswertung
- [Daten-Indizierung & RAG](./data-indexing-rag.md)
