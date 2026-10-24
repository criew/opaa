# ADR-0055: Ausleitung des Revisionsprotokolls an ein Sicherheitsmonitoring

## Status

Akzeptiert · zuletzt geändert 2026-10-24

## Kontext

Behörden betreiben ihr Sicherheitsmonitoring (SIEM) zentral für viele Fachverfahren; für die
Bundesverwaltung verlangt der Mindeststandard des BSI zur Protokollierung und Detektion die
Anbindung. Die Spezifikation ([Sicherheit und Compliance](../features/security-and-compliance.md#anbindung-an-ein-zentrales-sicherheitsmonitoring))
beschreibt das Ziel und begrenzt es: Was OPAA nicht nach Person gruppiert, liefert es auch nicht so
aus. Das Revisionsprotokoll ist nur anfügbar, wird nur über begrenzte Abfragewege der Revision und
die Selbstauskunft gelesen und trägt Pseudonyme handelnder und betroffener Personen
([ADR-0015](0015-eigentuemertrennung-protokollablage.md)). OPAA läuft mit einer oder mehreren
Instanzen ([ADR-0053](0053-mehrinstanzbetrieb-aktiv-aktiv.md)). Fehlgeschlagene Anmeldungen,
abgewiesene Netzbereiche und der Abflussalarm stehen bewusst nur im Anwendungslog.

## Entscheidung

### 1. Nur das Revisionsprotokoll

Ausgeleitet wird das Revisionsprotokoll, sonst nichts; es entsteht kein neuer Datenbestand.
Sicherheitsereignisse des Anwendungslogs greift die Behörde über ihre Log-Sammlung ab. So bleibt der
Schnitt des Protokolls („Zugriff verändert oder Verwaltungshandeln“) auch für das SIEM der Schnitt.

### 2. Ohne Pseudonyme

Je Eintrag gehen Ereignisart, Zeitpunkt, Ergebnis, Organisation und betroffenes Objekt hinaus, nie
das Pseudonym der handelnden oder betroffenen Person, nie Begründung, Nutzlast oder
Vorgangskennung. Ist das Objekt selbst einer Person zugeordnet (Konto, Zugangstoken, private
Bibliothek), fehlen seine Kennung und Bezeichnung (`SiemExportService`). Fragen zu einer Person
klärt die Behörde über die anlassbezogene Klärung in OPAA; im SIEM entsteht kein Auswertungspfad
nach Person.

### 3. Vollständiges Lesen nur, wenn eingeschaltet

`SiemExportService` liest `audit_log` der Reihe nach, mit genau den Spalten aus Entscheidung 2. Es ist
neben den Abfragewegen der Revision und der Selbstauskunft der einzige Leser
(`AuditLogReaderStructureTest`) und existiert nur bei `opaa.siem-export.enabled=true`. Die
Zugriffsregel steht im Handbuch, im Faktenblatt `datenfluesse` und in der Vorlage der
Dienstvereinbarung.

### 4. Syslog mit CEF über TCP, TLS als Vorgabe

Je Eintrag eine Nachricht nach RFC 5424 mit einem CEF-Körper, über TCP mit Längenpräfix (RFC 6587),
als Vorgabe mit TLS (RFC 5425), Vertrauensspeicher der JVM und Prüfung des Hostnamens
(`CefSyslogFormatter`, `SyslogSender`). Die Kennung des Eintrags steht in `externalId`. CEF über
Syslog nehmen die verbreiteten SIEM-Systeme ohne eigenen Parser an.

### 5. Ab dem Einschalten, ohne stillen Verlust

Die Position steht in `siem_export_cursor`; sie entsteht beim ersten Lauf auf der Datenbankuhr, ein
älterer Bestand wird nicht nachgeliefert. Sie rückt erst nach einer gelungenen Zustellung vor, per
bedingtem `UPDATE`; ein ausgefallener Empfänger bekommt dieselben Einträge später. Ein Lauf alle 30
Sekunden unter der Sperre `siem-export` auf einem eigenen Scheduler (ADR-0053, Entscheidung 7). Ein
Eintrag wird erst nach `opaa.siem-export.settle-delay` gesendet, damit ein später committeter Eintrag
nicht überholt wird. Abschalten hält die Position an; wieder eingeschaltet geht es dort weiter.

### 6. Lücken werden gekennzeichnet

Hat die Aufbewahrungsfrist Einträge entfernt, bevor sie gesendet waren (`last_cutoff` liegt hinter
der Position), geht eine Nachricht `AUDIT_EXPORT_GAP` mit dem Zeitraum hinaus und die Position
springt an die Grenze. Ein Ausfall des Empfängers ist keine Lücke.

## Konsequenzen

- Einfacher: Die Behörde bindet OPAA wie andere Fachverfahren an ihr SIEM an; das Protokoll bleibt
  die eine Quelle.
- Schwieriger: Das SIEM sieht Verwaltungshandeln ohne handelnde Person; wer gehandelt hat, klärt
  nur die anlassbezogene Klärung.
- Restrisiken: Die Zustellung ist mindestens einmal: Bricht ein Lauf zwischen Zustellung und
  Vorrücken ab, kommt derselbe Stapel noch einmal, erkennbar an `externalId`. Ein Eintrag, dessen
  Transaktion länger als die Wartezeit offen bleibt, wird überholt und nicht gesendet; dagegen hilft
  nur eine längere Wartezeit. Ohne TLS gehen die Einträge unverschlüsselt durchs Netz.

## Verworfene Alternativen

- Pseudonyme mitsenden — öffnete im SIEM eine Auswertung nach Person.
- Fehlversuche und Abflussalarm speichern und mitsenden — ein neuer Datenbestand über Verhalten.
- JSON Lines oder OpenTelemetry — weniger verbreitet in den SIEM-Systemen der Verwaltung.
- Ausgangstabelle, die jeder Schreiber füllt — ändert jeden Schreibweg des Protokolls.
- Nachliefern des Bestands beim Einschalten — nicht verlangt und ein unbegrenzter erster Lauf.

## Offen

- Client-Zertifikat für TLS und ein eigener Vertrauensspeicher neben dem der JVM.
