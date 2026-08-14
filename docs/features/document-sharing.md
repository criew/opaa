# Workspace-übergreifendes Dokument-Teilen

> **Status: Überholt — durch das Asset-Modell abgelöst. Nicht umsetzen.**
>
> Dieses Feature beschrieb, wie einzelne Dokumente über Workspace-Grenzen hinweg geteilt werden.
> Mit dem [Space- und Asset-Modell](./spaces-and-assets.md) entfällt der Bedarf: Dokumente liegen
> in **Wissensbibliotheken**, die eigenständige, teilbare Assets mit eigener Rechteliste sind.
>
> - Ein Bestand soll mehreren Teams zur Verfügung stehen → die **Bibliothek** wird in mehreren
>   Spaces assoziiert oder an weitere Nutzer und Gruppen freigegeben. Keine Kopien, kein
>   Vervielfachen von Chunks, eine Fassung.
> - Ein einzelnes Dokument soll weitergegeben werden → es wird in eine Bibliothek verschoben,
>   deren Leserkreis passt.
> - Teilen zwischen persönlichen Ablagen — im alten Modell strukturell unmöglich — funktioniert
>   jetzt über einen direkten Grant auf die persönliche Bibliothek.
>
> Damit entfällt auch die hier dokumentierte Sicherheitslücke: Es gibt keinen Vorgang mehr, bei dem
> ein Editor Inhalte in einen Kontext mit niedrigerer Vertraulichkeitsstufe schiebt. Die Reichweite
> ändert nur, wer am Asset dazu berechtigt ist.
>
> Der folgende Text bleibt zur Nachvollziehbarkeit der Entscheidungsgeschichte stehen.
> Maßgeblich ist [Spaces, Assets & Zugangskontrolle](./spaces-and-assets.md).

---

## Motivation

Benutzer und Teams müssen Dokumente über Workspace-Grenzen hinaus auffindbar machen. Beispielsweise möchte ein Team ein Design-Dokument mit einem anderen Team zum Review teilen, oder ein Benutzer möchte persönliche Notizen für sein Team zugänglich machen.

---

## Aktuelles Konzept (unter Überprüfung)

### Wie Teilen funktioniert

1. Benutzer wählt ein Dokument in einem Workspace aus, in dem er Editor-Rolle hat
2. Benutzer wählt "In Workspace teilen" und wählt einen Ziel-Workspace
3. Benutzer muss mindestens Editor-Rolle im Ziel-Workspace haben
4. Die indizierten Chunks des Dokuments erhalten die Ziel-Workspace-ID
5. Mitglieder des Ziel-Workspaces können das Dokument jetzt in Suchergebnissen finden
6. Das Originaldokument verbleibt in seinem Heimat-Workspace (Single Source of Truth)

Massen-Teilen wird unterstützt — Benutzer können mehrere Dokumente auf einmal teilen.

### Teilungs-Szenarien

```
Szenario 1: Persönlich → Team
  Alice teilt "Notes.md" aus "Meine Dokumente" → "Frontend-Team"
  Anforderung: Alice ist Editor in "Frontend-Team"

Szenario 2: Team → Team
  Alice teilt "API-Spec.md" aus "Backend-Team" → "Frontend-Team"
  Anforderung: Alice ist Editor in BEIDEN Workspaces

Szenario 3: Team → Projekt
  Alice teilt "Sprint-Results.md" aus "Frontend-Team" → "Phoenix"
  Anforderung: Alice ist Editor in BEIDEN Workspaces
```

### Teilungsmodell

```
Dokument: "Q1 Design Review"
  Owner: Sarah Chen
  Heimat-Workspace: Meine Dokumente (Sarah)
  Geteilt mit: [Engineering, Architecture]

  Sichtbarkeit:
    - Sarah: immer (Owner)
    - Engineering-Mitglieder: ja (geteilt)
    - Architecture-Mitglieder: ja (geteilt)
    - Marketing-Mitglieder: nein (nicht geteilt)
```

### Teilen vs. Verschieben

- **Teilen:** Dokument in mehreren Workspaces sichtbar. Single Source of Truth. Owner behält Kontrolle.
- **Verschieben:** (Nicht unterstützt) Dokumente haben immer einen Heimat-Workspace. Wenn ein Benutzer ein Dokument dauerhaft in einem Team-Workspace platzieren möchte, lädt er direkt in diesen Workspace hoch (erfordert Editor-Rolle).

### Teilungs-Benachrichtigungen

Wenn ein Dokument mit einem Workspace geteilt wird, werden Mitglieder des Ziel-Workspaces benachrichtigt.

### Geteilten Zugang widerrufen

- Dokument-Owner kann Teilen jederzeit widerrufen
- Workspace-Admin des Ziels kann ein geteiltes Dokument aus seinem Workspace entfernen
- Wenn Teilen widerrufen wird, verlieren die Chunks des Dokuments das Workspace-Tag und werden nicht mehr in den Suchergebnissen dieses Workspaces zurückgegeben
- Teilungsaktionen (Gewähren und Widerrufen) werden im Audit-Log aufgezeichnet

### Entfernung geteilter Dokumente

Workspace-Admins des Ziel-Workspaces können eingehende geteilte Dokumente aus ihrem Workspace entfernen. Dies entfernt die Workspace-Tags von den Chunks des Dokuments — das Originaldokument im Quell-Workspace ist nicht betroffen.

### Externes Teilen (Freigabe-Links)

Begrenzter externer Zugang über Freigabe-Links:

```
Freigabe-Link erstellen mit:
  - Ablaufdatum (z. B. 7 Tage)
  - Nur-Lesen-Zugang
  - Optionales Passwort
  - Tracking aktiviert (sehen, wer zugegriffen hat)

Link: https://opaa.company.com/share/abc123xyz
  - Gültig bis 23. Februar 2024
  - Kann jederzeit widerrufen werden
  - Zugang im Audit-Trail geloggt
```

---

## Bekannte Sicherheitsbedenken

Das aktuelle "Editor in beiden Workspaces"-Modell hat eine **grundlegende Sicherheitslücke**:

### Problem: Unbeabsichtigte Informationsoffenlegung

Betrachten Sie zwei Workspaces:
- **Workspace A:** "Vertrauliche Manager-Dokumente" (Mitglieder: nur Manager)
- **Workspace B:** "Mitarbeiter-FAQs" (Mitglieder: alle Mitarbeiter)

Wenn ein Manager Editor in beiden Workspaces ist, könnte er ein vertrauliches Gehaltsdokument aus Workspace A mit Workspace B teilen. Alle Mitarbeiter in Workspace B würden dieses Dokument dann in ihren Suchergebnissen sehen — eine klare Sicherheitsverletzung.

Das aktuelle Modell setzt voraus, dass Editor-Rolle im Quell-Workspace das Recht impliziert, seinen Inhalt zu verteilen. Dies ist nicht notwendigerweise wahr. **Das Erlaubt-sein, Dokumente zu bearbeiten, bedeutet nicht das Erlaubt-sein, sie zu deklassifizieren.**

### Was geklärt werden muss

Vor der Implementierung von Teilen müssen folgende Fragen beantwortet werden:

1. **Berechtigungsprüfung im Ziel-Workspace:** Sollte überprüft werden, ob das Sensitivitätsniveau des geteilten Dokuments mit dem Publikum des Ziel-Workspaces kompatibel ist? Falls ja, wie wird Sensitivität bestimmt?
2. **Genehmigungsworkflow:** Sollte Teilen explizite Genehmigung durch einen Admin des Ziel-Workspaces erfordern? Dies würde unilaterales Teilen verhindern, fügt aber Reibung hinzu.
3. **Rollenbasierte Teilungseinschränkungen:** Ist "Editor in beiden" die richtige Berechtigungsanforderung? Vielleicht sollte Teilen Admin-Rolle im Quell-Workspace erfordern (höhere Autorität zur Verteilung) oder eine neue "Teilen"-Berechtigung getrennt von "Bearbeiten".
4. **Sichtbarkeitsregeln für geteilte Dokumente:** Wenn ein Dokument in einen Workspace geteilt wird, wird es für ALLE Mitglieder des Ziel-Workspaces sichtbar oder nur für Mitglieder mit einer bestimmten Rolle?
5. **Klassifizierungsbasierte Kontrollen:** Sollten Dokumente ein Klassifizierungslevel haben (z. B. Öffentlich, Intern, Vertraulich, Eingeschränkt), das einschränkt, in welche Workspaces sie geteilt werden können?

---

## Offene Fragen

- **Benutzer-zu-Benutzer-Teilen:** Direktes Teilen zwischen persönlichen Workspaces ist derzeit nicht möglich (Teilen erfordert Editor-Rolle in beiden Workspaces, und persönliche Workspaces erlauben keine anderen Mitglieder). Mögliche Lösungen: (a) über einen gemeinsamen Workspace teilen, (b) einen Benutzerebenen-Teilmechanismus einführen (z. B. "Dokument mit Benutzer X teilen"), oder (c) den gemeinsamen Workspace-Workaround als bewusste Designentscheidung akzeptieren.
- Sollten geteilte Dokumente **Nur-Lesen vs. bearbeitbares** Teilen unterstützen?
- Sollte es eine **Begrenzung** geben, wie viele Workspaces ein Dokument geteilt werden kann?
- Sollten Workspace-Admins in der Lage sein, Dokumente aus persönlichen Workspaces von Benutzern zu **"anfordern"**?
- Wie interagiert Teilen mit **Konnektor-Berechtigungen aus Quellsystemen** (z. B. Confluence-Space-Berechtigungen)?
- Sollte es ein **Teilungs-Audit-Dashboard** geben, das alle aktiven Teilungen in der Organisation zeigt?

---

## Integrationspunkte

- **Zugangskontrolle & Workspaces:** Teilen erweitert workspace-ebenen Berechtigungen — siehe [Zugangskontrolle & Workspaces](./access-control.md)
- **Daten-Indizierung & RAG:** Geteilte Dokumente erhalten zusätzliche `workspace_ids`-Tags auf ihren Chunks
- **Benutzer-Frontends:** Teilungs-UI, Teilungs-Verwaltung, Benachrichtigungen
- **Audit & Compliance:** Alle Teilungsaktionen geloggt
