# Zugangskontrolle: Systemverwaltung, Identität & Audit

> **Hinweis:** Das Space-, Asset- und Rechtemodell ist in [Spaces, Assets & Zugangskontrolle](./spaces-and-assets.md) beschrieben und in [ADR-0008](../decisions/0008-space-and-asset-model.md) entschieden. Dieses Dokument behandelt die davon unabhängigen Themen: Systemverwaltung, Benutzeridentität, Audit und Compliance. Das frühere Workspace-Konzept in diesem Dokument ist abgelöst.

## Motivation

Nicht alles Organisationswissen ist für jeden bestimmt. Ein Haus hat öffentliche Richtlinien, fachbereichsbezogene Dokumentation, besonders geschützte Bestände und Unterlagen, die nur der Revision zugänglich sind.

Wer was sehen darf, regelt das Rechtemodell in [Spaces, Assets & Zugangskontrolle](./spaces-and-assets.md). Wer das System verwaltet, wie Identitäten entstehen und wie jede Handlung nachweisbar bleibt, regelt dieses Dokument.

---

## Überblick

Die Zugangskontrolle in OPAA hat vier Schichten:

1. **Asset-Rechte** — wer auf Wissensbibliotheken, Agenten und Prompt-Bibliotheken zugreift → [Spaces, Assets & Zugangskontrolle](./spaces-and-assets.md)
2. **Space-Rollen** — wer in einem Arbeitsraum mitarbeitet, kuratiert und verwaltet → ebenda
3. **System-Administration** — wer das System als Ganzes verwaltet → dieses Dokument
4. **Identität und Nachweis** — woher Nutzer kommen und wie Handlungen belegt werden → dieses Dokument

---

## Systemverwaltung

### System-Admin-Rolle

Über den Space- und Asset-Rollen steht die **System-Admin**-Rolle für organisationsweite Administration. Sie ist eine systemweite Rolle und wird auf der Benutzer-Entität gespeichert.

System-Admins können:

- Team- und Fachbereichs-Spaces anlegen und löschen (Projekt-Spaces legen Nutzer selbst an)
- Konnektoren konfigurieren
- Quell-Zuordnungen definieren — welche Quelle in welche Wissensbibliothek indiziert
- Die Freigabe-Obergrenze konnektor-gespeister Bibliotheken setzen
- Benutzerverzeichnis-Synchronisation und Gruppen konfigurieren
- Globale Modell-Policies und Governance-Einstellungen setzen
- Verwaiste Assets übernehmen und einer neuen Zuständigkeit zuweisen

**System-Admins existieren je Organisation.** Die Mandantengrenze gilt auch für sie; es gibt keine organisationsübergreifende Sicht.

Wichtige Abgrenzung: System-Admins verwalten das System, sind aber **nicht automatisch berechtigt, jeden Inhalt zu lesen**. Der Zugriff auf Wissensbibliotheken folgt der Rechteliste des Assets. Wo eine Übernahme nötig ist (verwaiste Assets, Offboarding), ist sie ein protokollierter Verwaltungsakt und keine stillschweigende Leseberechtigung.

### Dokumentenfluss: Konnektoren gegen Benutzer-Uploads

Die zwei Wege, auf denen Dokumente in OPAA gelangen, haben unterschiedliche Autorisierungsanforderungen:

- **Konnektoren (System-Admin):** System-Admins konfigurieren Konnektoren und legen fest, welche Quelle in welche Wissensbibliothek indiziert. Der primäre Weg für automatisierte Massenaufnahme.
- **Manuelle Uploads:** Wer an einer Wissensbibliothek mindestens `EDITOR` ist, kann Dokumente hochladen — in seine persönliche Bibliothek oder in jede andere, an der er dieses Recht hat.

Wesentliche Verschiebung gegenüber dem alten Modell: Der System-Admin entscheidet, **wohin** indiziert wird; der Bibliotheks-Eigentümer entscheidet, **wer es sieht**. Die Freigabe-Obergrenze verhindert, dass eingespeiste Bestände weiter geöffnet werden, als der Einspeisende es vorgesehen hat.

### Löschung eines Space

Ein Space zu löschen ist unter dem neuen Modell ein vergleichsweise harmloser Vorgang: Er vernichtet **keine Dokumente**, weil diese in Wissensbibliotheken liegen, die anderen gehören. Gelöscht werden die Assoziationen (die Assets selbst bleiben unberührt) sowie die space-eigenen Inhalte — Chats und Artefakte —, sofern sie nicht zuvor in eine Wissensbibliothek überführt wurden. Ein Audit-Eintrag hält die Löschung fest.

Löschen darf nur der im Space als Verantwortlicher hinterlegte Nutzer oder ein System-Admin.

---

## Benutzerverwaltung

### Benutzeridentität

Benutzer authentifizieren sich über:

- **Single Sign-On (SSO)** — OIDC, SAML (empfohlen)
- **Lokale Konten** — Benutzername und Passwort (nur als Rückfallebene)
- **API-Tokens** — für programmatischen Zugang

**Empfohlen:** SSO-Anbindung an das vorhandene Identitätsmanagement (Keycloak, Entra, Okta).

### Benutzerverzeichnis- und Gruppensynchronisation

OPAA kann mit dem Verzeichnisdienst abgleichen:

```
Sync-Häufigkeit: konfigurierbar (z. B. alle 6 Stunden)

Aus dem Verzeichnis:
  - Benutzernamen und E-Mail-Adressen
  - Gruppenmitgliedschaften
  - Organisationseinheit (Referat, Abteilung, Amt)
  - Funktionsbezeichnung
```

Die übernommenen **Gruppen sind Rechtesubjekt**: Rechte an Assets werden an Nutzer oder an Gruppen vergeben, und die Verteilungsstufe „Fachbereich" ist ein Grant an die Abteilungs- oder Amts-Gruppe. Details im [Rechtemodell](./spaces-and-assets.md#gruppen-als-rechtesubjekt).

Die Synchronisation ändert nur die **Herkunft** von Gruppenmitgliedschaften, nicht das Rechtemodell. In der ersten Ausbaustufe werden Gruppen im System gepflegt.

### Offboarding

Wenn ein Nutzer die Organisation verlässt:

1. Die Verzeichnis-Synchronisation entfernt ihn; er kann sich nicht mehr anmelden.
2. **Die Deaktivierung wird nie durch offene Eigentumsfragen aufgehalten.** Eine Regel, die das verlangt, wird am Freitagnachmittag umgangen und schützt dann gerade nicht.
3. Seine Assets gehen in den Zustand **„Nachfolge offen"**: nutzbar und mit unveränderten Rechten, aber mit **eingefrorener Reichweite** — keine neuen Grants, keine höhere Freigabestufe, keine neue Bereitstellung. Zuständig für die Nachfolge ist der Kurator seiner Organisationseinheit, mit Frist und Eskalation.
4. Für zentral gepflegte Bestände ist Gruppen-Eigentum der Regelfall und verhindert das Problem von vornherein.
5. Sein persönlicher Space wird deaktiviert, nicht gelöscht (Nachweisgründe) — und **nicht lesbar gemacht**. Entwürfe darin bleiben unzugänglich. Chats und Artefakte unterliegen der Aufbewahrungsregel.

### API-Tokens und Service-Accounts

```
API-Token erstellen:
  Name:        "Fachverfahren-Anbindung"
  Rechte:      erbt die Asset-Rechte des ausstellenden Nutzers oder Service-Accounts
  Umfang:      [read_documents, ask_questions]
  Rotation:    90 Tage
  IP-Bereich:  optional (CIDR)
  Rate-Limit:  konfigurierbar
```

Ein Token kann **nie mehr Rechte haben als sein Inhaber**. Service-Accounts sind reine API-Identitäten ohne interaktive Anmeldung; sie erhalten ihre Rechte wie jeder andere Träger von Rechten über Grants oder Gruppen.

---

## Nachweisbarkeit: Historisierung von Rechten

Die Rechtemenge eines Nutzers ist eine **berechnete Größe** aus drei Quellen — direkte Grants, Gruppengrants und organisationsweite Freigaben —, von denen sich eine, die Gruppenmitgliedschaft, per Verzeichnissynchronisation ändert. Rechte, die aus mehreren Quellen zusammengerechnet werden, muss man erklären können.

Die Prüferfrage lautet nicht „was hat Frau K. getan", sondern: *„Worauf hatte Frau K. am 3. März Zugriff, und belegen Sie, dass die Bibliothek `Personalvorgänge` nicht dazugehörte."* Die **Negativfrage** ist die schwierigere, und ein Ereignisprotokoll kann sie nicht beantworten, solange es Lücken haben kann.

Deshalb werden **Grants und Gruppenmitgliedschaften historisiert**: Zu jedem Zeitpunkt ist rekonstruierbar, wer welche Rechte hatte, seit wann und aufgrund welchen Vorgangs. Die Rechtemenge eines beliebigen Stichtags wird aus der Historie berechnet, nicht aus dem Protokoll gelesen.

Das ist bewusst **anders gelöst als über eine Protokollzeile je Abfrage**: Die Rechtemenge bei jeder Suche mitzuschreiben würde das Protokoll um eine erhebliche Menge personenbezogener Daten erweitern — genau das, was die Datensparsamkeit vermeiden soll — und wäre trotzdem lückenanfällig. Die Historie liefert dieselbe Aussage mit weniger Daten.

**Folge für die Compliance-Berichte:** Einen Bericht „abgelehnte Zugriffe" kann es nicht geben. Weil der Filter Teil der Vektorsuche ist, existiert kein abgelehnter Zugriff, den man protokollieren könnte — unberechtigte Chunks werden nie geladen. Was es gibt, ist der Nachweis über die Rechtehistorie und über den bei jeder Abfrage protokollierten **angewandten Suchbereich**.

---

## Audit & Compliance

### Der Protokollsatz

```json
{
  "timestamp": "2026-02-16T14:30:15Z",
  "user_id": "user-123",
  "organization_id": "org-1",
  "action": "search",
  "space_id": "space-veranlagung",
  "libraries_searched": ["lib-rechtsquellen"],
  "results_count": 5,
  "documents_accessed": ["doc-1", "doc-2"],
  "result": "success"
}
```

**Die Netzadresse ist nicht Teil des Standardsatzes.** Sie unterscheidet Dienststelle von Homeoffice und ist damit ein Anwesenheitsmerkmal. Sie kann für Sicherheitszwecke ausdrücklich eingeschaltet werden; dann ist die Einschaltung zu begründen, und das Feld bleibt aus Berichten und Exporten ausgeschlossen. Ob eine C5-Prüfung das Feld zwingend verlangt, ist offen; sollte das so sein, ist es schriftlich zu begründen.

### Besonders protokollpflichtige Handlungen

Handlungen, an denen sich Rechte, Reichweiten oder die Beobachtbarkeit ändern:

- Rechtevergabe und -entzug an Assets, einschließlich Mitfreigaben aus der Freigabekette
- **Ablegen** eines Chats oder Artefakts im Space und **Zurückziehen** durch Ersteller oder Space-Admin
- Aufnahme und Entfernen von Space-Mitgliedern; die Aufnahme **externer** Personen in einen Space mit abgelegten Inhalten zusätzlich mit ausdrücklicher Bestätigung
- Bereitstellung einer Bibliothek in einem Space, dessen Mitglieder nicht sämtlich Lesezugriff haben
- Änderung der Freigabestufe oder Auffindbarkeit eines Assets
- Übernahme von Assets ohne Zuständigkeit und Eigentümerwechsel
- Änderungen an Modell-Policies
- **Änderungen an Governance-Einstellungen** — Aufbewahrungsfristen, Aggregation, Statistik, Audit-Konfiguration. Ohne diesen Punkt bleibt eine spätere Abweichung von der Dienstvereinbarung unbemerkt; die Änderung wird zusätzlich angezeigt
- Jede bewirkte Rechteänderung aus einem Verzeichnissynchronisationslauf — je Änderung, nicht je Lauf

### Aufbewahrung und Zugriff

- **Frist mit Ober- und Untergrenze**, konfigurierbar, mit automatischer Löschung nach Ablauf. Eine reine Untergrenze („mindestens ein Jahr") ist keine Regelung, sondern eine unbefristete Speicherung mit Mindestdauer.
- Die Audit-Frist muss **mindestens so lang** gewählt werden wie die Aufbewahrung der Inhalte, auf die sie sich bezieht. Sonst existiert ein Chatverlauf noch, aber es ist nicht mehr belegbar, wer ihn wann gelesen hat. Das Produkt warnt bei einer inkonsistenten Einstellung. Die konkrete Dauer folgt aus Fachrecht und Aktenordnung der einführenden Stelle.
- **Abschließend geregelter Zugriff:** benannter Personenkreis, dokumentierter Anlass. Die Trennung der Auswertungswege für Revision und Dienststellenleitung ist technisch durchgesetzt, nicht nur organisatorisch zugesagt. Der Audit-Zugriff erzeugt selbst einen Eintrag — protokollierter Zugriff ist aber kein begrenzter Zugriff, beides ist nötig.
- **Der SIEM-Export ist keine Umgehung.** Was exportiert wird, unterliegt denselben Zweck-, Zugriffs- und Sparsamkeitsregeln.
- **Kein personenbezogener Auswertungspfad.** Es gibt keine Schnittstelle und keine Oberfläche, die Nutzungs-, Chat- oder Herkunftsdaten nach Person filtert, gruppiert oder sortiert — auch nicht abschaltbar. Offen bleiben nur die Selbstauskunft der betroffenen Person und die anlassbezogene Klärung eines Sicherheitsvorfalls im Vier-Augen-Prinzip. Siehe [Mitbestimmung und Personalvertretung](./spaces-and-assets.md#mitbestimmung-und-personalvertretung).

### Unveränderlichkeit und Löschrecht

Ein nur anfügendes Protokoll und ein nachträgliches Schwärzen schließen einander aus. Der Widerspruch wird zugunsten der Unveränderlichkeit aufgelöst:

**Der Personenbezug wird ab dem Schreibzeitpunkt pseudonymisiert.** Das Protokoll enthält eine Kennung, die Zuordnung zur Person liegt in einer getrennt gehaltenen Tabelle. Beim Löschen eines Kontos entfällt dieser Eintrag — das Protokoll bleibt unverändert und ist danach nicht mehr auf eine Person zurückführbar. Es wird nichts nachträglich verändert und nichts überschrieben.

### Compliance-Berichte

- **Zugangsbericht:** wer hat wann worauf zugegriffen
- **Rechteänderungen:** wer hat wem was freigegeben, mit Rechtestand zum Stichtag aus der Historie
- **Zugriff auf geschützte Bestände**
- **Auskunftsexport:** welche personenbeziehbaren Daten erhoben werden, in welcher Granularität und wie lange sie liegen — vor dem Rollout vollständig vorlegbar

### Datenlöschung (DSGVO)

Wenn ein Benutzerkonto gelöscht wird:

```
1. Zugang sofort deaktivieren — nie durch offene Eigentumsfragen aufgehalten
2. Assets in den Zustand "Nachfolge offen" versetzen: nutzbar, aber Reichweite eingefroren
3. Nutzer aus allen Spaces und Gruppen entfernen
4. Konto und Auth-Tokens löschen
5. Pseudonymzuordnung entfernen — das Protokoll bleibt unverändert bestehen
```

Entwürfe des Nutzers folgen den Regeln des persönlichen Space. Abgelegte Chats und Artefakte in geteilten Spaces sind Arbeitsergebnisse der Organisation und verschwinden nicht mit dem Konto ihres Erstellers, werden aber nach Ablauf der Aufbewahrungsfrist gelöscht.

Dokumente werden über ihre Wissensbibliothek gelöscht. Für konnektor-indizierte Dokumente gilt weiterhin der Ausschluss-Mechanismus, weil sie beim nächsten Lauf sonst erneut aufgenommen würden.

---

## Sonderfälle

### Breiter Lesezugriff für Stabsstellen und Leitung

Nicht über eine Sonderrolle, sondern über **Gruppen**: Die Stabsstelle erhält als Gruppe Leserechte an den einschlägigen Wissensbibliotheken. Das skaliert, ist im Katalog nachvollziehbar und läuft über denselben Weg wie jede andere Freigabe — kein Sonderpfad, der bei einer Prüfung erklärt werden müsste.

### Revision und Rechnungsprüfung

Prüfende Stellen brauchen Unabhängigkeit. Empfohlen ist ein eigener Space im **Strikt-Modus** (nur Bibliotheken, deren Leserkreis alle Mitglieder umfasst), damit in der Prüfung keine Inhalte an Unberechtigte gelangen und die Prüfakte sauber abgegrenzt bleibt.

### Externe Beteiligte

```
Nutzer:      externe Beraterin
Spaces:      [Projekt-X]
Assets:      Grant USER auf genau die benötigte Bibliothek
Befristung:  bis 2026-03-31
Hinweis:     Sie sieht alle Chats und Artefakte des Space — vor der Aufnahme prüfen
```

Die Aufnahme externer Personen ist besonders folgenreich, weil ihnen damit alle **abgelegten** Inhalte des Space offenstehen — also die Arbeitsergebnisse namentlich bekannter Beschäftigter. Externe Konten sind gekennzeichnet, die Aufnahme verlangt eine ausdrückliche Bestätigung und wird protokolliert. Ein bloßer Hinweistext genügt hier nicht. Für solche Fälle ist ein eigener, eng geschnittener Space der richtige Weg.

---

## Integrationspunkte

- **Authentifizierung:** SSO-Anbieter und Verzeichnisdienst
- **Benutzer-Frontends:** Rechte an jeder Schnittstelle durchsetzen
- **Daten-Indizierung:** Zuordnung von Quellen zu Wissensbibliotheken
- **RAG-Engine:** Filter über die lesbaren Bibliotheken des Nutzers, als Teil der Vektorsuche
- **Deployment:** Nutzer- und Gruppendaten aus dem Verzeichnis

---

## Offene Fragen

- Attributbasierte Zugangskontrolle (ABAC) zusätzlich zu Rollen und Gruppen?
- Zeitlich befristete Rechte mit automatischem Verfall?
- Genehmigungsworkflows für besonders geschützte Bestände?
- Klassifizierungsstufen (offen, intern, vertraulich) als eigenes Merkmal?
- **Rechte aus Quellsystemen:** Sollen z. B. Confluence-Space-Berechtigungen zusätzlich zu den Bibliotheksrechten durchgesetzt werden? Grundsätzlich erwünscht, aber aufwendig — Benutzerkennungen und Rechtemodelle stimmen zwischen Quellsystem und OPAA nicht notwendig überein.

---

## Erfolgs-Metriken

- **Compliance:** Audit-Logs vollständig aufbewahrt und zugänglich
- **Leistung:** Rechteprüfung erhöht die Abfragezeit um weniger als 50 ms
- **Genauigkeit:** keine unbeabsichtigten Zugriffe
- **Verständlichkeit:** Anteil der Support-Anfragen, die sich auf „warum sehe ich das nicht“ beziehen, sinkt über die ersten drei Monate. (Die frühere Formulierung „Nutzer verstehen den Unterschied ohne Schulung“ ist gestrichen — ADR-0008 bezeichnet dieselbe Sache als „Hauptlast des Modells“; eine Metrik, deren Erfüllung die eigene Architekturentscheidung für unwahrscheinlich erklärt, ist keine Metrik.)

---

## Verwandte Dokumente

- [Spaces, Assets & Zugangskontrolle](./spaces-and-assets.md) — das Rechtemodell
- [ADR-0008: Space- und Asset-Modell](../decisions/0008-space-and-asset-model.md)
- [Daten-Indizierung & RAG](./data-indexing-rag.md)
