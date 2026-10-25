# ADR-0055: Ausleitung des Revisionsprotokolls an ein Sicherheitsmonitoring

## Status

Akzeptiert · zuletzt geändert 2026-10-25

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

### 2. Ohne Pseudonyme, als Vorgabe ohne Namen

Je Eintrag gehen Ereignisart, Zeitpunkt, Ergebnis, Organisation sowie Art und Kennung des
betroffenen Objekts hinaus, dazu die Version des Ereigniskatalogs (`schema_version`), unter der er
geschrieben ist, nie das Pseudonym der handelnden oder betroffenen Person, nie
Begründung, Nutzlast oder Vorgangskennung. Den Namen des Objekts sendet OPAA nur mit
`opaa.siem-export.include-labels` (datenwirksam, aus), weil Namen von Spaces und Gruppen oft Personen
nennen. Gehört das Objekt genau einer Person, fehlen Kennung und Name immer: Konto und Zugangstoken
an ihrer Art; persönlicher Bereich, private Bibliothek und privater Prompt am Eigentum, das die
Module über den Port `PersonalAuditObjects` melden. Dieser Port setzt schon beim Schreiben
(`AuditLogService#record`) die neutrale Bezeichnung (`AuditNeutralLabels`), gleich was der Schreiber
übergibt; die Ausleitung maskiert danach im SQL und fragt den Port für jeden Stapel noch einmal. Fragen zu einer Person klärt die Behörde über die anlassbezogene Klärung in OPAA.

Die eine Nutzlast, die hinausgeht, ist das Monatssiegel der Prüfsummenkette
([ADR-0058](0058-ereignisvertrag-aufbewahrung-und-integritaet-des-revisionsprotokolls.md),
Entscheidung 4): Mit dem Eintrag `AUDIT_LOG_MONTH_SEALED` gehen Monat, Anzahl der verketteten
Einträge und Kopf-Prüfsumme als eigene CEF-Felder (`cs5`, `cn2`, `cs6`) hinaus, gelesen aus
`audit_chain_seals` über die Kennung des Eintrags, nicht aus `after`. So liegt das Siegel außerhalb
der Datenbank, ohne dass die Ausleitung eine Nutzlastspalte liest.

Einen Weg zur Person gibt es dennoch: Manche Zeilen des Anwendungslogs nennen die Kennung eines
Kontos, etwa bei einer Kontosperre; liegen Log und Ausleitung im selben SIEM, lassen sie sich über
den Zeitpunkt verbinden. Das regelt die Dienstvereinbarung (Verknüpfung nur in der anlassbezogenen
Klärung, Speicherfrist im SIEM).

### 3. Vollständiges Lesen nur, wenn eingeschaltet

`SiemExportService` liest `audit_log` der Reihe nach, mit genau den Spalten aus Entscheidung 2. Es ist
neben den Abfragewegen der Revision, der Selbstauskunft und der Prüfsummenkette (ADR-0058,
Entscheidung 4) der einzige Leser, existiert nur bei `opaa.siem-export.enabled=true`, wird nur vom
Zeitplan aufgerufen und nennt in seinem SQL keine Spalte mit Personenbezug, Begründung oder Nutzlast
(`AuditLogReaderStructureTest`). Gelesen wird über
den Index `(recorded_at, event_id)` und nur ab der Position, sodass ältere Monatspartitionen außen
vor bleiben. Die
Zugriffsregel steht im Handbuch, im Faktenblatt `datenfluesse` und in der Vorlage der
Dienstvereinbarung.

### 4. Syslog mit CEF über TCP, TLS als Vorgabe

Je Eintrag eine Nachricht nach RFC 5424 mit einem CEF-Körper, über TCP mit Längenpräfix (RFC 6587),
als Vorgabe mit TLS (RFC 5425), Vertrauensspeicher der JVM und Prüfung des Hostnamens
(`CefSyslogFormatter`, `SyslogSender`). Die Kennung des Eintrags steht in `externalId`, die Version
des Ereigniskatalogs als Zahlenfeld `cn1` mit `cn1Label=schemaVersion`; ein Eintrag von vor dem
Katalog trägt keine, das Feld fehlt dann. So liest ein Empfänger Ereignisart und Version als
Vertrag, auch wenn er keine Nutzlast erhält. Nach der letzten
Meldung schließt OPAA die Sendeseite und wartet kurz auf das Verbindungsende des Empfängers. Ein
Empfänger, der die Verbindung schon verloren hat, lässt die Zustellung dort meist scheitern; hat er
sie gleich nach dem Annehmen geschlossen, kann sie dennoch als gelungen gelten. Eine
Bestätigung je Meldung gibt es nicht, die Zustellung ist mindestens einmal; RELP wäre der Weg zu
einer Bestätigung. CEF über Syslog nehmen die verbreiteten SIEM-Systeme ohne eigenen Parser an.

### 5. Ab dem Einschalten, ohne stillen Verlust

Die Position steht in `siem_export_cursor`; sie entsteht beim ersten Lauf auf der Datenbankuhr, ein
älterer Bestand wird nicht nachgeliefert. Sie rückt erst nach einer gelungenen Zustellung vor, per
bedingtem `UPDATE`; ein ausgefallener Empfänger bekommt dieselben Einträge später, nach Fehlschlägen
mit wachsendem Abstand bis zehn Minuten. Ein Lauf alle 30 Sekunden unter der Sperre `siem-export` auf
einem eigenen Scheduler (ADR-0053, Entscheidung 7). Gesendet wird nur, was vor dem Beginn der ältesten
noch offenen Transaktion einer Client-Sitzung der Datenbank liegt (`pg_stat_activity.xact_start`,
`backend_type = 'client backend'`), gelesen in einer eigenen Anweisung vor den Einträgen
(`AuditSettledLimit`, dieselbe Grenze nutzt die Prüfsummenkette): `recorded_at` entsteht in der
Transaktion des Eintrags, ein noch nicht sichtbarer Eintrag liegt also nie davor. Die Datenquelle muss der Primär sein, kein Replikat.
`opaa.siem-export.settle-delay` ist nur ein kleiner Puffer gegen Sprünge der Uhr. Startet eine Instanz
mit abgeschalteter Ausleitung, merkt sie das an der Position an (`disabled_at`); wieder eingeschaltet
beginnt die Ausleitung neu.

### 6. Lücken werden gekennzeichnet

Hat die Aufbewahrungsfrist Einträge entfernt, bevor sie gesendet waren (`last_cutoff` liegt hinter
der Position), geht eine Nachricht `AUDIT_EXPORT_GAP` mit `reason=retention` und dem Zeitraum hinaus
und die Position springt an die Grenze; ändert sich die Grenze während eines Laufs, entscheidet erst
der nächste. Eine Pause der Ausleitung meldet `reason=disabled`. Ein Ausfall des Empfängers ist keine
Lücke.

## Konsequenzen

- Einfacher: Die Behörde bindet OPAA wie andere Fachverfahren an ihr SIEM an; das Protokoll bleibt
  die eine Quelle.
- Schwieriger: Das SIEM sieht Verwaltungshandeln ohne handelnde Person; wer gehandelt hat, klärt
  nur die anlassbezogene Klärung.
- Restrisiken: Die Zustellung ist mindestens einmal: Bricht ein Lauf zwischen Zustellung und
  Vorrücken ab, kommt derselbe Stapel noch einmal, erkennbar an `externalId`. Ein Empfänger, der die
  Daten annimmt und danach verwirft, fällt ohne Bestätigung je Meldung nicht auf. Eine lang offene
  Transaktion hält die Ausleitung an, bis sie endet; die Metrik `opaa.siem.export.lag` zeigt den
  Rückstand, und das Log warnt mit der Sitzung, die ihn verursacht. Die Sicht auf `pg_stat_activity` umfasst nur
  Sitzungen derselben Datenbankrolle; schreibt eine andere Rolle ins Protokoll, braucht das
  Anwendungskonto `pg_read_all_stats`. Ohne TLS gehen die Einträge unverschlüsselt durchs Netz.

## Verworfene Alternativen

- Pseudonyme mitsenden — öffnete im SIEM eine Auswertung nach Person.
- Fehlversuche und Abflussalarm speichern und mitsenden — ein neuer Datenbestand über Verhalten.
- JSON Lines oder OpenTelemetry — weniger verbreitet in den SIEM-Systemen der Verwaltung.
- Ausgangstabelle, die jeder Schreiber füllt — ändert jeden Schreibweg des Protokolls.
- Nachliefern des Bestands beim Einschalten — nicht verlangt und ein unbegrenzter erster Lauf.
- Feste Wartezeit statt der ältesten offenen Transaktion — überholt jeden längeren Vorgang still.
- Nach einer Pause an der alten Position weitermachen — lieferte Monate an ein womöglich neues Ziel.

## Offen

- Client-Zertifikat für TLS (mTLS); eine interne Zertifizierungsstelle deckt unter Helm
  `extraCACertificates` ab.
