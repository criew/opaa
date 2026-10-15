---
stand: 2026-10-15
gilt-fuer: main
erzeugung: redaktionell
rechtsstand: 2026-10-13
pruefvermerk: nicht rechtlich geprüft
naechste-durchsicht: 2027-04-15
---

# Technische und organisatorische Maßnahmen: Produktanteil

> Nicht rechtlich geprüft. Kann als Vorlage dienen; die Verantwortung bleibt bei der Behörde.

Die technischen und organisatorischen Maßnahmen (TOM) nach Art. 32 DSGVO trifft der Verantwortliche,
also die Behörde, und bei Auftragsverarbeitung auch ihr Dienstleister. Ein Teil davon steckt im
Produkt. Dieser Baustein trennt beide: **Produktanteil** heißt, OPAA bringt die Maßnahme mit;
**Betreiberanteil** heißt, die Behörde oder ihr Dienstleister muss sie selbst treffen. Die Spalte
„Betreiberanteil“ füllt die Behörde in ihrem Sicherheitskonzept; Angaben in eckigen Klammern **[…]**
sind Beispiele.

Der Produktanteil ist hier nur benannt. Wie eine Maßnahme wirkt und wie sie eingestellt wird, steht in
der verlinkten Stelle des Handbuchs oder im Faktenblatt der eingesetzten Version.

## Quellen und Prüfstand

| Fundstelle | Inhalt | Herkunft |
|---|---|---|
| Art. 32 Abs. 1 [DSGVO](https://eur-lex.europa.eu/eli/reg/2016/679/oj/deu) | Sicherheit der Verarbeitung, Buchstaben a bis d | Normtext |
| Art. 25 Abs. 1 und 2 DSGVO | Datenschutz durch Technikgestaltung und datenschutzfreundliche Voreinstellungen | Recherche vom 13.10.2026 |
| [Standard-Datenschutzmodell](https://www.datenschutzzentrum.de/sdm/) der Datenschutzkonferenz | Methode, um Maßnahmen Gewährleistungszielen zuzuordnen | Muster |

Die Recherche vom 13.10.2026 steht im ersten Kommentar von Epic
[#2542](https://github.com/criew/opaa/issues/2542). Wie das Sicherheitskonzept der Behörde nach
IT-Grundschutz aufgebaut wird, ist nicht Teil dieses Bausteins
([#2567](https://github.com/criew/opaa/issues/2567)).

## Maßnahmen nach Art. 32 Abs. 1 DSGVO

| Art. 32 Abs. 1 | Produktanteil | Betreiberanteil |
|---|---|---|
| **lit. a** Pseudonymisierung | Protokoll mit Pseudonymen, Zuordnung getrennt gehalten: {{faktenblatt:datenkatalog#audit-pseudonyms}}, [Unveränderlichkeit und Löschrecht](../../features/security-and-compliance.md#unveränderlichkeit-und-löschrecht) | [Zugriff auf die Datenbank beschränken, damit die Trennung nicht umgangen wird] |
| **lit. a** Verschlüsselung | Zugangsdaten von Quellen, Zugängen und Modellen verschlüsselt gespeichert ([Handbuch, Schlüssel und Geheimnisse](../../handbuch/betrieb.md#schlüssel-und-geheimnisse)); Passwörter lokaler Konten nur als Prüfwert ([Sichere Voreinstellungen](../../features/security-and-compliance.md#sichere-voreinstellungen-und-produkthärtung)) | [TLS am Eingang ([Handbuch, Netzwerkzugang](../../handbuch/betrieb.md#netzwerkzugang)); verschlüsselte Datenträger und Sicherungen; Schlüssel getrennt von der Datenbank verwahren] |
| **lit. b** Vertraulichkeit | Rechte je Arbeitsmittel und Space ([Handbuch, Berechtigungen](../../handbuch/berechtigungen.md)); Sichtbarkeit je Rolle: {{faktenblatt:rollensicht}}; Anmeldung über Identitätsanbieter oder lokale Konten mit Sperren und Fristen ([Handbuch, Authentifizierung](../../handbuch/authentifizierung.md)) | [Rollenvergabe nach Rollenkonzept; Anbindung an den Identitätsanbieter des Hauses; Vier-Augen-Prinzip für die Revision] |
| **lit. b** Integrität | Revisionssicheres Protokoll, nur anfügend: {{faktenblatt:protokollereignisse}}; Rechtehistorie: {{faktenblatt:datenkatalog#permission-history}} | [Datenbankrechte; Weiterleitung an ein zentrales Sicherheitsmonitoring] |
| **lit. b** Verfügbarkeit und Belastbarkeit | Bereitschafts- und Lebendigkeitsprüfung ([Handbuch, Betrieb](../../handbuch/betrieb.md#bereitschaft-und-lebendigkeit)); Begrenzungen am Anmeldeweg ([Sichere Voreinstellungen](../../features/security-and-compliance.md#sichere-voreinstellungen-und-produkthärtung)) | [Betrieb, Überwachung, Kapazität; Ausfall des Modellanbieters einplanen] |
| **lit. b** Schutz ausgehender Verbindungen | Zieladressprüfung für eingetragene Adressen ([Handbuch, Zielprüfung](../../handbuch/betrieb.md#zielprüfung-abrufe-des-servers)); ein Ausgangsproxy für alle HTTP(S)-Verbindungen ([Handbuch, Ausgangsproxy](../../handbuch/betrieb.md#ausgangsproxy)); Datenflüsse: {{faktenblatt:datenfluesse#datenfluesse}} | [Egress-Regeln auf Netzebene; Zielliste am Proxy] |
| **lit. c** Wiederherstellung | Sicherungsumfang und Reihenfolge beschrieben ([Handbuch, Sicherung und Wiederherstellung](../../handbuch/betrieb.md#sicherung-und-wiederherstellung)) | [Sicherungen durchführen, Wiederherstellung regelmäßig proben] |
| **lit. d** Überprüfung und Bewertung | Stückliste und Attestierung je Image ([SBOM](../../sbom.md)); CVE-Erkennung und Triage ([CVE-Scanning](../../cve-scanning.md)); Meldeweg für Sicherheitslücken ([SECURITY.md](../../../SECURITY.md)); Faktenblätter und Update-Paket je Version | [Updates zeitnah einspielen; Update-Paket lesen; Prüfungen im eigenen Turnus] |

## Datenschutzfreundliche Voreinstellungen (Art. 25 Abs. 2 DSGVO)

Welche Voreinstellungen OPAA mit Wirkung auf Daten, Sichtbarkeit, Auswertung oder Datenflüsse
ausliefert, steht mit Standardwert und Wirkung im {{faktenblatt:voreinstellungen#einstellungen}}; die
KI-Funktionen mit ihrer Kennzeichnung im {{faktenblatt:voreinstellungen#ki-funktionen}}. Die
Grundsätze dahinter beschreibt die Spezifikation unter
[Sichere Voreinstellungen und Produkthärtung](../../features/security-and-compliance.md#sichere-voreinstellungen-und-produkthärtung)
und [Mitbestimmungsfähigkeit](../../features/security-and-compliance.md#mitbestimmungsfähigkeit).

Für ihre Dokumentation nach Art. 25 hält die Behörde fest:

- welche Voreinstellungen sie **unverändert** übernimmt (Verweis auf das Faktenblatt der Version),
- welche sie **ändert**, mit neuem Wert und Begründung [aus dem Konfigurationsexport,
  [#2558](https://github.com/criew/opaa/issues/2558)],
- welche Funktionen sie **einschaltet**, die ab Werk aus sind.

Ein Update kann Voreinstellungen ändern. Das betrifft nur Instanzen, die den Wert nicht überschrieben
haben; das Update-Paket weist geänderte Voreinstellungen aus
([Spezifikation, Instanzbezug](../../features/behoerdenunterlagen.md#instanzbezug)).
