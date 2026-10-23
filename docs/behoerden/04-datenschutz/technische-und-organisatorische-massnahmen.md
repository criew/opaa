---
stand: 2026-10-23
gilt-fuer: main
erzeugung: redaktionell
rechtsstand: 2026-10-13
pruefvermerk: nicht rechtlich geprüft
naechste-durchsicht: 2027-04-23
---

# Technische und organisatorische Maßnahmen (TOM)

> Nicht rechtlich geprüft. Kann als Vorlage dienen; die Verantwortung bleibt bei der Behörde.

**Gilt für:** Behörden, deren Datenschutzaufsicht die oder der BfDI oder die
Landesdatenschutzbeauftragten sind.

TOM sind die Vorkehrungen, mit denen personenbezogene Daten gegen Verlust, Missbrauch und unbefugten
Zugriff geschützt werden. Verantwortlich ist die Behörde, bei Auftragsverarbeitung auch ihr
Dienstleister. Ein Teil der Maßnahmen steckt im Produkt; diese Vorlage trennt, was OPAA mitbringt und
was der Betrieb selbst leisten muss.

> **Beispiel.** Ein Landratsamt schreibt sein Sicherheitskonzept für OPAA. Die Projektleitung übernimmt
> die Spalte „OPAA bringt mit“ als Verweis und füllt mit dem IT-Betrieb die Spalte „Betrieb leistet“:
> verschlüsselte Sicherungen, Anbindung an das Active Directory, Netzregeln für ausgehende
> Verbindungen. Die Tabelle wird Anlage zum Verzeichnis von Verarbeitungstätigkeiten.

## Was OPAA liefert

| Schutzziel | OPAA bringt mit | Betrieb leistet, etwa |
|---|---|---|
| **Pseudonymisierung** | Protokoll mit Pseudonymen, Zuordnung getrennt gehalten ({{faktenblatt:datenkatalog#audit-pseudonyms}}, [Unveränderlichkeit und Löschrecht](../../features/security-and-compliance.md#unveränderlichkeit-und-löschrecht)) | Datenbankzugriff beschränken, damit die Trennung hält |
| **Verschlüsselung** | Zugangsdaten verschlüsselt gespeichert ([Schlüssel und Geheimnisse](../../handbuch/betrieb.md#schlüssel-und-geheimnisse)); Passwörter nur als Prüfwert ([Sichere Voreinstellungen](../../features/security-and-compliance.md#sichere-voreinstellungen-und-produkthärtung)) | TLS am Eingang ([Netzwerkzugang](../../handbuch/betrieb.md#netzwerkzugang)), verschlüsselte Datenträger und Sicherungen, Schlüssel getrennt verwahren |
| **Vertraulichkeit** | Rechte je Arbeitsmittel und Space ([Berechtigungen](../../handbuch/berechtigungen.md)), Sichtbarkeit je Rolle ({{faktenblatt:rollensicht}}), Anmeldung mit Sperren und Fristen ([Authentifizierung](../../handbuch/authentifizierung.md)) | Rollenkonzept, Anbindung an den Identitätsanbieter, Vier-Augen-Prinzip für die Revision |
| **Integrität** | Protokoll, an das nur angefügt wird ({{faktenblatt:protokollereignisse}}), Rechtehistorie ({{faktenblatt:datenkatalog#permission-history}}) | Datenbankrechte, Weiterleitung an ein Sicherheitsmonitoring |
| **Verfügbarkeit** | Bereitschafts- und Lebendigkeitsprüfung ([Betrieb](../../handbuch/betrieb.md#bereitschaft-und-lebendigkeit)), Begrenzungen am Anmeldeweg | Überwachung, Kapazität, Ausfall des Modellanbieters einplanen |
| **Ausgehende Verbindungen** | Zielprüfung ([Zielprüfung](../../handbuch/betrieb.md#zielprüfung-abrufe-des-servers)), ein Ausgangsproxy ([Ausgangsproxy](../../handbuch/betrieb.md#ausgangsproxy)), Datenflüsse ({{faktenblatt:datenfluesse#datenfluesse}}) | Netzregeln, Zielliste am Proxy |
| **Wiederherstellung** | Sicherungsumfang und Reihenfolge beschrieben ([Sicherung und Wiederherstellung](../../handbuch/betrieb.md#sicherung-und-wiederherstellung)) | Sicherungen durchführen, Rücksicherung regelmäßig proben |
| **Regelmäßige Überprüfung** | Stückliste je Image ([SBOM](../../sbom.md)), [CVE-Scanning](../../cve-scanning.md), Meldeweg für Lücken ([SECURITY.md](../../../SECURITY.md)), Faktenblätter und Update-Paket je Version | Updates zeitnah einspielen, Update-Paket lesen, eigene Prüfungen |

## Was die Behörde tun muss

1. **Spalte „Betrieb leistet“ füllen** im eigenen Sicherheitskonzept, gemeinsam mit IT-Betrieb oder
   Dienstleister.
2. **Voreinstellungen dokumentieren:** Datenschutzfreundliche Voreinstellungen, also Werkseinstellungen,
   die nur das Nötige erheben, stehen im {{faktenblatt:voreinstellungen#einstellungen}}, die
   KI-Funktionen im {{faktenblatt:voreinstellungen#ki-funktionen}}. Die Behörde hält fest, welche sie
   unverändert übernimmt, welche sie mit Begründung ändert und welche ab Werk ausgeschalteten
   Funktionen sie einschaltet.
3. **Bei jedem Update nachprüfen:** Das Update-Paket weist geänderte Voreinstellungen aus. Sie wirken
   nur dort, wo die Behörde den Wert nicht selbst gesetzt hat.

## Gut zu wissen

- Was die eigene Installation anders eingestellt hat, zeigt die
  [Auskunft über die Datenerhebung](../../handbuch/nachvollziehbarkeit.md#auskunft-über-die-datenerhebung-einer-installation).
- Die Grundsätze hinter den Voreinstellungen beschreibt die Spezifikation unter
  [Sichere Voreinstellungen](../../features/security-and-compliance.md#sichere-voreinstellungen-und-produkthärtung)
  und [Mitbestimmungsfähigkeit](../../features/security-and-compliance.md#mitbestimmungsfähigkeit).
- Wie das Sicherheitskonzept nach IT-Grundschutz aufgebaut wird, ist nicht Teil dieser Vorlage.

## Zum Nachlesen

**Arbeitshilfen** für die Projektleitung:

- [Standard-Datenschutzmodell](https://www.datenschutzzentrum.de/sdm/) der Datenschutzkonferenz:
  Methode, um Maßnahmen den Schutzzielen zuzuordnen.

**Rechtsgrundlagen** für die oder den DSB:

| Worum es geht | Fundstelle |
|---|---|
| Sicherheit der Verarbeitung; Schutzziele der Tabelle (Buchstaben a bis d) | Art. 32 Abs. 1 [DSGVO](https://eur-lex.europa.eu/eli/reg/2016/679/oj/deu) |
| Datenschutz durch Technikgestaltung und datenschutzfreundliche Voreinstellungen | Art. 25 Abs. 1 und 2 DSGVO |
