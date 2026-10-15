---
stand: 2026-10-15
gilt-fuer: main
erzeugung: redaktionell
rechtsstand: 2026-10-13
pruefvermerk: nicht rechtlich geprüft
naechste-durchsicht: 2027-04-15
---

# C5:2026 für das Betriebsmodell Cloud-Anbieter

> Nicht rechtlich geprüft. Kann als Vorlage dienen; die Verantwortung bleibt bei der Behörde.

## Für wen diese Seite gilt

Nur für ein Betriebsmodell: **Ein Cloud-Anbieter betreibt OPAA als Dienst für eine oder mehrere
Behörden**, etwa ein Landes- oder Bundesrechenzentrum oder ein kommerzieller Anbieter. Betreibt eine
Behörde OPAA im eigenen Rechenzentrum, ist C5 für sie kein Prüfgegenstand; sie modelliert OPAA nach
IT-Grundschutz ([Grundschutz-Modellierung](grundschutz-modellierung.md)).

**OPAA ist nicht C5-testiert und wird es nie.** Der Kriterienkatalog C5 des BSI prüft den Betrieb
eines Cloud-Dienstes, also Organisation, Personal, Räume und Verfahren eines Anbieters, nicht eine
Software. Ein Testat erhält der Cloud-Anbieter, der OPAA betreibt, für seinen Dienst. Was OPAA dazu
beiträgt, beschreibt die Produktspezifikation als
[C5-Fähigkeit](../../features/security-and-compliance.md#c5-fähigkeit-statt-zertifizierung): Das
Produkt ist so gebaut und dokumentiert, dass ein Anbieter die Prüfung mit OPAA im Prüfumfang
bestehen kann.

## Quellen und Prüfstand

Grundlage ist die Rechtsrecherche vom 13.10.2026 (erster Kommentar in Epic
[#2542](https://github.com/criew/opaa/issues/2542)); die letzte Zeile stammt nicht aus dieser
Recherche:

| Aussage | Fundstelle | Art |
|---|---|---|
| C5:2026 ist seit April 2026 final und für Prüfzeiträume ab 1.6.2027 verbindlich | [heise](https://heise.de/-11247658), [KPMG](https://kpmg.com/de/de/themen/digital-transformation/cloud-computing/mindestanforderungen-fuer-die-sicherheit-von-cloud-diensten.html) | Fachbeitrag |
| C5 testiert Cloud-Anbieter, nicht Software | [heise](https://heise.de/-11247658) | Fachbeitrag |
| AIC4 besteht weiter | [BSI, AIC4](https://www.bsi.bund.de/DE/Themen/Unternehmen-und-Organisationen/Informationen-und-Empfehlungen/Kuenstliche-Intelligenz/AIC4/aic4.html) | amtliche Seite |
| Neu hinzu kommt der BSI-Prüfkatalog A5; sein Stand ist vor der Übernahme beim BSI zu prüfen | [comquent](https://comquent.de/ressourcen/blog/bsi-a5-pruefkatalog-ki) | Fachbeitrag |
| Der Mindeststandard zur Nutzung externer Cloud-Dienste verlangt vom Anbieter Nachweise, darunter ein C5-Testat | [BSI, Mindeststandards](https://www.bsi.bund.de/DE/Themen/Oeffentliche-Verwaltung/Mindeststandards/Mindeststandards_node.html) | amtliche Seite; nicht Teil der Recherche, vor Übernahme am Text des Mindeststandards zu prüfen |

Den Katalog selbst veröffentlicht das BSI auf seiner
[Seite zum Kriterienkatalog C5](https://www.bsi.bund.de/DE/Themen/Unternehmen-und-Organisationen/Informationen-und-Empfehlungen/Empfehlungen-nach-Angriffszielen/Cloud-Computing/Kriterienkatalog-C5/kriterienkatalog-c5_node.html).
Diese Vorlage bildet ihn nicht nach. Die Kürzel der Prüfbereiche unten folgen der Gliederung von
C5:2020, die auch die Produktspezifikation verwendet; vor der Übernahme gleicht der Anbieter sie mit
dem Wortlaut von C5:2026 ab.

## Wann eine Behörde ein C5-Testat verlangt

- **Bundesverwaltung:** Der
  [Mindeststandard zur Nutzung externer Cloud-Dienste](https://www.bsi.bund.de/DE/Themen/Oeffentliche-Verwaltung/Mindeststandards/Mindeststandards_node.html)
  knüpft die Nutzung an Nachweise des Anbieters, zu denen ein C5-Testat gehört. Welche Nachweise
  er im Einzelfall verlangt, entnimmt die Behörde dem Mindeststandard
  ([NIS2 in der Bundesverwaltung](nis2-bundesverwaltung.md)).
- **Länder und Kommunen:** nach Landesrecht und den Vorgaben des Hauses; ein C5-Testat ist dort ein
  verbreiteter, aber kein zwingender Nachweis.
- **Übergang:** Für Prüfzeiträume ab 1.6.2027 testiert der Anbieter nach C5:2026. Ein Testat nach
  der Vorgängerfassung bleibt bis dahin üblich; die Behörde fragt nach Fassung und Prüfzeitraum.

## Zuordnung der Prüfbereiche

„Beitrag von OPAA“ nennt, was der Anbieter aus dem Produkt vorlegen kann. „Nachweis des Anbieters“
nennt, was er selbst erbringt. Kein Prüfbereich ist durch OPAA allein erfüllt.

| Prüfbereich | Beitrag von OPAA | Nachweis des Anbieters |
|---|---|---|
| IDM Identitäts- und Berechtigungsmanagement | Anbindung an den Identitätsanbieter über OpenID Connect, Rollen- und Rechtemodell, Historisierung der Rechte; wer was sieht: {{faktenblatt:rollensicht}} | Verwaltung der Konten und Rollen, Notanker-Konto, Trennung der Kunden durch eigene Installationen |
| OPS Regelbetrieb | Betriebshandbuch mit Sicherung, Aktualisierung, Überwachung ([Betriebsübersicht](../../handbuch/betrieb.md)); Protokoll und Metriken | Kapazität, Überwachung, Sicherung, Protokollauswertung, Schutz vor Schadprogrammen |
| PSS Produktsicherheit | sichere Voreinstellungen ({{faktenblatt:voreinstellungen}}), Härtungshinweise ([Härtung](sicherheitsnachweise.md#härtung)) | Umsetzung der Härtung, Information der Kunden über Sicherheitsfunktionen |
| CRY Kryptographie und Schlüsselmanagement | verschlüsselte Ablage gespeicherter Zugangsdaten; Übersicht der Geheimnisse ([Schlüssel und Geheimnisse](../../handbuch/betrieb.md#schlüssel-und-geheimnisse)) | TLS, Verschlüsselung ruhender Daten, Erzeugung, Verwahrung und Wechsel der Schlüssel |
| PI Portabilität und Interoperabilität | Datenhaltung in einer Standard-PostgreSQL und einer Originalablage im Dateisystem oder Objektspeicher; Sicherung mit Standardwerkzeugen | Ausstiegs- und Übergabeverfahren für den Kunden |
| COM Compliance | Faktenblätter je Version, Behördenunterlagen dieses Ordners | Nachweisführung gegenüber Prüfstelle und Kunden |
| DEV Entwicklung und Änderung | geordneter Änderungsprozess, Review, Release-Verfahren, SBOM, Attestierungen ([Sicherheitsnachweise](sicherheitsnachweise.md)) | eigenes Änderungsmanagement: Test und Freigabe jeder OPAA-Version vor dem Einspielen |
| COS Kommunikationssicherheit | Netzwege und ausgehende Ziele je Version ({{faktenblatt:datenfluesse}}), NetworkPolicies im Helm-Chart, Zielprüfung für Abrufe des Servers | Netztrennung, Egress-Regeln, TLS |
| SIM Umgang mit Sicherheitsvorfällen | Meldeweg und Security Advisories des Projekts ([`SECURITY.md`](../../../SECURITY.md)); Nachweisprotokoll | Vorfallbehandlung, Meldung an Kunden und Behörden |
| BCM Kontinuität des Betriebs | Bestandteile, Abhängigkeiten, Wiederherstellungsweg ([Notfallkonzept](../09-betrieb-update/notfallkonzept.md)) | Notfallplanung, geübte Wiederherstellung ([Wiederherstellungsprobe](../09-betrieb-update/wiederherstellungsprobe.md)) |
| SSO Steuerung von Dienstleistern und Lieferanten | — (Hilfsmittel für die Bewertung: [Fragen zur Lieferkette](nis2-bundesverwaltung.md#lieferkette-fragen-an-das-produkt)) | OPAA als Lieferanten-Software bewerten, ebenso die Modellanbieter |
| OIS, SP, HR, AM, PS, INQ | — | vollständig beim Anbieter |

## Was beim Kunden bleibt

C5 kennt Kriterien, die der Kunde eines Cloud-Dienstes selbst erfüllen muss, damit die Kontrollen
des Anbieters wirken. Für eine Behörde, die OPAA als Cloud-Dienst bezieht, sind das typischerweise:

- **Rechte in der Anwendung vergeben:** Freigaben von Bibliotheken und Spaces, Rollen der
  Systemverwaltung ([Berechtigungen](../../handbuch/berechtigungen.md)).
- **Inhalte auswählen:** keine Verschlusssachen, keine Bibliotheken über dem vereinbarten
  Schutzbedarf ([Muster-Nutzungsrichtlinie](../08-barrierefreiheit-nutzung/nutzungsrichtlinie.md)).
- **Modellanbieter festlegen**, wenn der Anbieter die Wahl lässt, und die Datenflüsse in den
  Datenschutzunterlagen abbilden ({{faktenblatt:datenfluesse}}).
- **Identitätsanbieter des Hauses** anbinden und dessen Konten pflegen.

## Fragen an den Cloud-Anbieter

1. Liegt ein C5-Testat vor, nach welcher Fassung (C5:2026 oder Vorgänger), Typ 1 oder Typ 2, mit
   welchem Prüfzeitraum?
2. Ist OPAA ausdrücklich im Prüfumfang des Testats enthalten, einschließlich Datenbank,
   Originalablage und Modelldiensten?
3. Welche OPAA-Version betreiben Sie, und wie schnell spielen Sie Sicherheitsupdates ein, wenn nur
   das jüngste Release Korrekturen erhält
   ([Unterstützte Versionen](../../../SECURITY.md#unterstützte-versionen))?
4. Welche Modelldienste nutzen Sie, wo werden sie betrieben, und gelten für sie dieselben Nachweise?
5. Wie trennen Sie die Daten mehrerer Behörden? Eine OPAA-Installation führt genau eine
   Organisation ([Eine Organisation](../../handbuch/betrieb.md#eine-organisation)); getrennt wird
   deshalb über eigene Installationen samt Datenbank und Originalablage.
6. Welche Wiederherstellungszeit und welchen Wiederherstellungspunkt sagen Sie zu, und wann haben
   Sie die Wiederherstellung zuletzt geprobt?
7. Wie erhalten wir bei einem Anbieterwechsel unsere Daten, einschließlich der Originale und des
   Nachweisprotokolls?

## Außerhalb dieser Seite

- Prüfung nach AIC4 oder dem BSI-Prüfkatalog A5. Beide richten sich an Anbieter von KI-Diensten;
  OPAA ist danach nicht geprüft.
- Weitere Nachweise für Cloud-Anbieter, etwa der Katalog C3A des BSI zur digitalen Souveränität.
