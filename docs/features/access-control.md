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
2. **Assets in seinem Eigentum müssen übertragen werden** — an eine Person oder an eine Gruppe. Ein Konto kann nicht deaktiviert werden, solange es Assets besitzt. Für zentral gepflegte Bestände ist Gruppen-Eigentum der Regelfall und verhindert das Problem von vornherein.
3. Fällt ein Eigentümer trotzdem weg, wird das Asset als **verwaist** markiert und fällt an den System-Admin. Es wird niemals stillschweigend gelöscht und seine Reichweite nie stillschweigend verändert; bestehende Rechte bleiben, damit laufende Arbeit nicht abreißt.
4. Sein persönlicher Space wird deaktiviert, nicht gelöscht (Nachweisgründe). Chats und Artefakte darin unterliegen der Aufbewahrungsregel.

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

## Audit & Compliance

### Audit-Logging

Jede relevante Handlung wird protokolliert:

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
  "result": "success",
  "ip_address": "10.0.1.45"
}
```

Besonders protokollpflichtig sind die Handlungen, an denen sich Rechte oder Reichweiten ändern:

- Rechtevergabe und -entzug an Assets, einschließlich Mitfreigaben aus der Freigabekette beim Teilen eines Agenten
- Assoziation einer Bibliothek in einen Space mit **gemischtem Leserkreis** samt Bestätigung des Kurators
- Freigabe eines Artefakts mit gemischter Herkunft
- Änderung der Freigabestufe oder Auffindbarkeit eines Assets
- Übernahme verwaister Assets und Eigentümerwechsel
- Änderungen an Modell-Policies

Protokolle:

- Aufbewahrung mindestens ein Jahr, konfigurierbar
- An ein SIEM exportierbar
- Unveränderlich, nur anfügend

**Der Audit-Zugriff ist selbst protokolliert** und von den Auswertungswegen der Dienststellenleitung getrennt — siehe [Mitbestimmung und Personalvertretung](./spaces-and-assets.md#mitbestimmung-und-personalvertretung).

### Compliance-Berichte

- **Zugangsbericht:** wer hat wann worauf zugegriffen
- **Rechteänderungen:** wer hat wem was freigegeben
- **Zugriff auf geschützte Bestände**
- **Abgelehnte Zugriffe**

Verwendet für Revisionsnachweise, C5-Prüfpfade, DSGVO-Auskunftsersuchen und interne Untersuchungen.

### Datenlöschung (DSGVO)

Wenn ein Benutzerkonto gelöscht wird:

```
1. Eigentum an Assets übertragen (erzwungen, siehe Offboarding)
2. Nutzer aus allen Spaces und Gruppen entfernen
3. Konto und Auth-Tokens löschen
4. Audit-Logs behalten (Compliance), Personenbezug schwärzen
5. Personenbezogene Daten anonymisieren
```

Für Chats und Artefakte in geteilten Spaces gilt die Aufbewahrungsregel des jeweiligen Space: Sie sind Arbeitsergebnisse der Organisation und verschwinden nicht mit dem Konto ihres Erstellers, werden aber nach Fristablauf gelöscht.

Dokumente werden über ihre Wissensbibliothek gelöscht. Für konnektor-indizierte Dokumente gilt weiterhin der Ausschluss-Mechanismus, weil sie beim nächsten Lauf sonst erneut aufgenommen würden — er wirkt jetzt an der Bibliothek und damit an genau einer Stelle.

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

Die Aufnahme externer Personen in einen Space mit gemischtem Leserkreis ist besonders folgenreich, weil space-eigene Inhalte vollständig sichtbar sind. Für solche Fälle ist ein eigener, eng geschnittener Space der richtige Weg.

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
- **Verständlichkeit:** Neue Nutzer verstehen den Unterschied zwischen Space-Mitgliedschaft und Asset-Recht ohne Schulung

---

## Verwandte Dokumente

- [Spaces, Assets & Zugangskontrolle](./spaces-and-assets.md) — das Rechtemodell
- [ADR-0008: Space- und Asset-Modell](../decisions/0008-space-and-asset-model.md)
- [Daten-Indizierung & RAG](./data-indexing-rag.md)
