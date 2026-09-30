# Zeitraumsbericht 31.08.–30.09.2026

> **Abgenommener Bericht zum Stichtag 30.09.2026.** Er beruht auf der Fortschreibung mit
> Datenstand 30.09.2026 (`main@17ec7fca8`, 502 geschlossene Issues und 489 gemergte PRs im
> Zeitraum — siehe [anker.md](./anker.md)). Er enthält nur das **Delta zum Stichtag 31.08.2026**;
> den konsolidierten Stand führt [gesamtstand.md](../gesamtstand.md). Jede Aussage ist über
> Issue- und PR-Nummer auf Code rückführbar. Wo Lieferung und Vorgang auseinanderfallen, steht die
> Begründung im [Baustein mit Befund](./bausteine.md). Der Commit, der diesen Berichtsstand
> einführt, trägt das Git-Tag `inventur-20260930`.

## Management Summary

Der September war ein regulärer Arbeitsmonat; 22 Epics wurden abgeschlossen. Die großen
Phase-1-Lücken des Vormonats sind zum Großteil geschlossen: hybride Suche mit Reranking, echter Verzeichnisanschluss, Kontenlebenszyklus und zwei weitere lesende
Konnektoren. Dazu kamen die ersten Phase-2-Bausteine: OPAA als Wissensschicht für fremde
KI-Werkzeuge per MCP und die Prompt-Bibliothek als erster teilbarer Asset-Typ neben den
Wissensbibliotheken.

**Phase 1 der Produktvision („Souveräner Wissensassistent“) ist zu geschätzt rund 85 % umgesetzt
(Vormonat: rund 80 %).** Herleitung: Von der priorisierten Restliste des Vormonats sind die beiden
gewichtigsten Punkte in der Kernfunktion erledigt, die hybride Suche mit Reranking und der echte
Verzeichnisanschluss mit Kontenlebenszyklus. Offen bleiben Streaming der Antworten, Konfidenz, Leichte
Sprache, DSGVO-Vollständigkeit und der Betrieb bis air-gapped. Die Ausbreitung in die Breite
(weitere Konnektoren, Formate, Oberfläche) zählt bei der Schätzung weniger als diese Kernlücken.

**Was nach diesem Zeitraum neu möglich ist:**

- **Präziser finden:** Die Suche kombiniert Vektor- und Volltextsuche. Aktenzeichen und
  Paragraphen werden dadurch wörtlich gefunden. Reranking lässt sich als eigene Modellrolle
  zuschalten. Ergebnisse lassen sich nach Dokumentart und Datum filtern. Jede Suchstufe ist in
  einer Diagnoseansicht erklärt, auf Wunsch auch aus der Sicht einer bestimmten Person, dann
  befugt und protokolliert.
- **Mehr Quellen anbinden:** Confluence (Cloud und Data Center) und S3-kompatible Objektspeicher
  sind neu als Quellen verfügbar, mit inkrementellem Abgleich, Webhooks bzw. Ereignissen und
  Anfragebudget. Jedes gängige Büroformat hat eine eigene, strukturbewusste Aufbereitung: PDF,
  Word, PowerPoint, OpenDocument, Tabellen, HTML, E-Mail und Markdown. Anhänge werden eigene,
  auffindbare Dokumente.
- **Metadaten pflegen:** Kernfelder und bibliothekseigene Felder mit kontrolliertem Vokabular
  lassen sich automatisch ermitteln, von Hand korrigieren und als Suchfilter nutzen.
- **Anmelden, wie es das Haus verlangt:** mehrere Identitätsanbieter parallel oder eine lokale
  Benutzerverwaltung mit Passwort, Selbstbedienung und Mail-Anbindung.
- **Rechte aus dem Verzeichnis:** Gruppen und Kontostatus kommen per Verzeichnisabgleich aus
  Keycloak. Gruppen können Space-Mitglieder sein. Anlegerechte werden als Fähigkeiten vergeben.
  Die Oberfläche erklärt, warum jemand etwas sieht, und das Recht lässt sich zu einem Stichtag
  auskunftsfähig rekonstruieren. Ausgeschiedene Konten werden gesperrt, ihre Rechte lassen sich
  übertragen.
- **Mit Gesprächsverlauf fragen:** Nachfragen beziehen sich auf das Gespräch. Eine sichtbare und
  löschbare Gesprächsnotiz hält den Kontext fest. Viele Chats bleiben über Suche, Anheften und
  Archiv beherrschbar.
- **Vorlagen teilen:** Prompt-Bibliotheken mit Variablen werden angelegt, freigegeben und im
  Chat per Slash-Befehl eingesetzt. Ein Katalog zeigt alles, was organisationsweit geteilt ist.
- **OPAA aus anderen KI-Werkzeugen nutzen:** Über persönliche Zugangstokens und einen MCP-Server
  durchsuchen Claude Code, Cursor, VS Code und andere Werkzeuge die freigegebenen Bibliotheken,
  rechtekonform und mit installationsweitem Notaus.
- **Sicherer betreiben:** Das Backend-Image ist schlank und läuft ohne root, die Laufzeit-Images werden wöchentlich
  neu gebaut. Stücklisten, CVE-Scans und Alarme kommen automatisch. Upload-Originale lassen sich
  wahlweise im Objektspeicher ablegen.

---

## 1 · Wissensschicht & Retrieval (A)

- **Hybride Suche mit Reranking gebaut** (Epic #1045). Die Abfragestrecke wurde verhaltensneutral
  in benannte Stufen mit Pflicht-Erklärprotokoll umgebaut (#1046). Ein lexikalischer Volltextpfad
  mit Kennungsschutz für Paragraphen und Aktenzeichen kam dazu, der Rechtefilter sitzt in der
  Abfrage (#1048). Beide Pfade wurden in einer RRF-Fusion zusammengeführt (#1049). Reranking kam als
  konfigurierbare Modellrolle mit Zeitlimit und Ausfallwächter hinzu (#1050, #1153, #1154, #1209). Die
  Volltextsuche wurde so umgestellt, dass sie bei Versionsanhebungen vollständig verfügbar bleibt (#1346, ADR-0028).
- **Suchdiagnose für die Administration:** Die Seite „Suche & Indexierung“ zeigt Status und
  Diagnose je Pipeline-Stufe (#1053, #1120). Die Chunk-Ansicht wird je Dokument angezeigt (#1230).
  Die Diagnose im Rechtekontext einer Person („Sicht als“) ist befugt und protokolliert (#1150,
  #1052, #1124, #1257).
- **Metadatenschema gebaut** (Epic #1065): Kernfelder mit Herkunftsangabe (#1066), ein
  deterministischer Bestandslauf (#1067), manuelle Korrektur mit Audit (#1068, #1069), der
  Kernfeld-Filter in beiden Suchpfaden samt Oberfläche (#1070, PR#1298) und Bibliotheksfelder mit
  kontrolliertem Vokabular (#1071). Hinzu kommen Metadaten im Kontextpräfix (#1072) und eine
  modellgestützte Extraktion, per Voreinstellung aus (#1073). Die Nachkalibrierung (Epic #1364)
  hat Titel- und Datumsermittlung verbessert, der Titelfehler sank von 14 auf 0 % (#1360). Dazu
  kam die zweiphasige Umschlüsselung von Wertelisten (#1361). Mail-Kopfdaten sind als filterbare
  Formatfelder ins Schema eingegangen (#1242).
- **Gesprächsgedächtnis gebaut** (Epic #1482, ADR-0031): kurzes, wörtliches Suchfenster (#1486),
  eine sichtbare und löschbare Gesprächsnotiz (#1487, #1488) und Teilfragen-Zerlegung mit Kenntnis
  des Verlaufs (#1684). Die Systemanweisung der Antwortstufe wurde auf Deutsch umgestellt (#1635).
- **Suchqualitäts-Benchmark ausgebaut** (Epic #1036): ein zweiter Messpfad durch die produktive
  Pipeline (#1039, #1040), Variantenvergleiche (#1041) und die deutschsprachige Verwaltungsdomäne
  „Kalkstadt“ mit 46 Golden-Fällen in fünf neuen Fallklassen (#1042, #1043). Dazu kamen eine
  Mehrfachlauf-Regel (#1044), ein Mehrrunden-Messpfad mit 27 Fällen samt nächtlichem Job (#1484,
  #1485, #1553) und die Rangreserve als Grenzstabilitäts-Kennzahl (#1151, #1210). Messfestpunkte
  (Ollama-Herkunft, Kontextpräfix-Abdruck, CPU-Variante) machen die Baselines reproduzierbar
  (#1522, #1650, #1652, #1658, #1671).
- Das **Handbuchkapitel „Suche“** beschreibt die neun Stufen der Abfragestrecke (PR#1430).

## 2 · Wissensquellen & Indizierung (B)

- **Ingestion-Pipelines je Dokumenttyp** (Epic #1054): Die Pipeline-Abstraktion mit
  Routing-Schlüssel und selektivem Reindex ist gebaut (#1056, #1126, #1167). Eigene Aufbereitungen
  gibt es für PDF, DOCX und PPTX mit Gliederungszuschnitt (#1061), für ODT und ODP mit eigenen
  Lesern (#1110), für XLSX und CSV mit wiederholten Spaltenköpfen (#1058), für HTML (#1059), für
  EML und MSG (#1060) und für Markdown (#1103). PDF-Tabellen werden zeilenweise gelesen (#2033),
  Scan-PDFs ohne Textebene erkannt (#1055).
- **Anhänge sind eigene Dokumente** (ADR-0022, Epic #1178). Das gilt für Mail- und
  Feed-Anhänge aus allen Aufnahmewegen, mit Elternbezug, gruppierter Anzeige und Öffnen per
  Nachextraktion aus der Elternmail (#1180–#1184, #1218, #1219, #1239, #1243).
- **Confluence-Konnektor für Cloud und Data Center** (Epic #1129, ADR-0023): Verbindungstest mit
  Editionserkennung, Wizard, Vollabgleich und inkrementeller Abgleich mit getrennter Löschsemantik,
  Makro-Regelwerk und Webhooks (#1131–#1142, #1191, #1200). Dazu kommen Anfragebudget,
  Wiederaufnahme und eine Integrationssuite gegen ein echtes Data Center im Container (#1141,
  #1171).
- **S3-Konnektor** (Epic #1291, ADR-0027): Er funktioniert mit AWS, MinIO, Ceph und kompatiblen
  Speichern, mit Geltungsbereichen, ETag-Änderungserkennung und Präfixen als Ordner. Hinzu kommen
  Wiederaufnahme, ein Ereignisweg und der Beleg-Sprung ins Original im Bucket (#1373–#1383, #1524).
- **Ingestion konsolidiert und neu gegliedert** (Epics #1316, #1401, #1415): ein gemeinsamer
  Laufrahmen für alle Konnektoren, eine Dokumentstrecke statt fünf Einstiegen und eine
  Pipeline-Basisklasse. Budget und Unterbrechung sitzen einmal im Laufrahmen, Webhooks und
  S3-Ereignisse laufen durch einen gemeinsamen Ereignis-Intake (#1310–#1315, #1396–#1399,
  #1416–#1418). Ein neues Format braucht seitdem nur Klasse und Bean.
- **Steckbare Konnektoren** (ADR-0038): Jeder Konnektor beschreibt sich über eine Schnittstelle,
  seine Einstellungen liegen als JSON. Kern und API kennen keine Konnektornamen mehr (#1976,
  #1977).
- **Härtung:** Der Crawler blockiert kodierten Pfadaufstieg (#1287) und gibt Zugangsdaten bei
  Weiterleitungen aus dem Unterbaum nicht weiter (#1301). Ein Download-Deckel greift (#1236). Alte
  Chunks bleiben stehen, bis die neue Fassung erfolgreich geparst ist (#1268). HTTP-Verzeichnisse
  werden als schreibgeschützte Ordner gespiegelt (#1277).
- **Produkthandbuch** mit Kapiteln zu Indexierung, allen fünf Konnektoren und den Formaten
  (PR#1283, PR#1304, #1383).

## 3 · Identität, Rechte & Mandanten (F)

- **Mehrere OIDC-Anbieter** (Epic #1294, ADR-0025): Anbieter liegen in der Datenbank und wirken
  ohne Neustart. Konten sind je (Issuer, Subject) getrennt, Rollen und Gruppen kommen je Anbieter
  aus dem Token. Beim Login lässt sich der Anbieter wählen, eine eigene Verwaltungsoberfläche ist
  gebaut (#1327–#1334). Automatische Anmeldung bei laufender Anbieter-Sitzung (#1631), Direktlinks
  überstehen die Anmeldung (#1685).
- **Lokale Benutzerverwaltung** (Epic #1529, ADR-0033): lokale Konten mit Passwort, ein
  Notanker-Systemverwalter und Token-Ausstellung mit rotierenden Refresh-Tokens. Dazu kommen
  Mail-Subsystem, Selbstbedienung (Registrierung, Passwort vergessen), Einladung, Sperre und
  Ablauf sowie die Kontoübergabe an einen Identitätsanbieter (#1531–#1543, #1563, #1601).
- **Berechtigungsmodell mit Gruppen und Fähigkeiten** (Epic #1295, ADR-0036):
  - typunabhängige Grants im eigenen Paket (#1811)
  - Gruppenherkunft je Anbieter (#1812), interne Gruppen mit Verantwortlichen (#1814) und Gruppen
    als Space-Mitglieder (#1815)
  - Anlegerechte als vergebbare Fähigkeiten (#1813)
  - **Verzeichnisabgleich je Anbieter mit Keycloak als erstem produktivem Konnektor** (#1816,
    #1817) und Übernahme des Kontostatus (#1818)
  - „Nachfolge offen“ (#1819) und Übertragung von Rechten und Eigentum (#1834)
  - Herleitung „warum sehe ich das“ und Stichtagsauskunft für die Revision (#1822)
  - Verwaltungsoberflächen (#1820, #1821, PR#1873)

  Die Robustheit gegen fehlende oder unbrauchbare Claims ist abgesichert (#1807, #1830).
- **Rechtehistorie gehärtet:** streng monotone Intervallgrenzen (#1497, ADR-0032), ein
  Integrationstest über alle 17 Schreibpfade (#1428) und eine Aufbewahrungshöchstdauer mit
  Löschlauf (#1833).

## 4 · Kanäle & Oberflächen (I)

- **OPAA als Wissensschicht für fremde KI-Werkzeuge** (Epic #1715, ADR-0035):
  - installationsweiter Schalter mit Notaus, Netzbereichen und Kontingent (#1717)
  - persönliche Zugangstokens (#1718, #1719)
  - befristete Bibliotheksfreigabe für Fremdzugänge (#1731)
  - Such-Endpunkt ohne Antwortgenerierung (#1720)
  - MCP-Server mit `search`, `fetch` und `list_libraries` (#1721, #1766)
  - Handbuchkapitel für Claude Code, Cursor, VS Code und OpenCode (#1722)
  - 13 E2E-Szenarien (#1723)
- **Chat:**
  - Chatliste mit Titelfilter, Anheften, Archiv und Volltext-Chatsuche mit Sprung an die
    Trefferstelle (Epic #1762)
  - Belege über „Belege anzeigen“ und klickbare Fußnoten (#1449)
  - Kopierfunktion als Markdown (#1924)
  - mehrzeilige Fragen mit Zeichenzähler (#1993)
  - Antwort bleibt nach Chatwechsel am eigenen Chat (#1574)
- **Detailansicht der Wissensbibliothek** (Epic #1927): Reiter Dokumente, Quelle, Metadaten und
  Freigaben, dazu ein neuer Anlage-Assistent für alle Konnektoren (#1939–#1944).
- **Zwei Politur-Runden:**
  - Verwaltungsbereich: gemeinsamer Seitenkopf, ruhige Flächen, Tabellen mit Zeilenmenü, eigenes
    Bestätigungs-Overlay statt 25 Browser-Dialogen, Reiter für „Suche & Indexierung“ und „Modelle“
    (#1604, #1607–#1610, #1614, #1616, #1617, #1619, #1621, #1623, #1625, #1627)
  - Anmeldeseite, Übersichten, Space-Einstellungen und Navigation (#1910–#1922, #1970)
  - Die Hausfarbe wird als Textfarbe automatisch kontrastsicher (#1600).
- **REST-API mit sauberem Fehlerverhalten:**
  - 405, 415 und 406 statt 500 (#1707)
  - Fehlerrumpf nur bei passender Inhaltsaushandlung (#1780)
  - einheitliche Statuscode-Regel über alle 201 Operationen mit Wächtertest (#1781, #1785)

## 5 · Spaces, Assets & Agenten (C/D)

- **Gemeinsame Asset-Schale** (#1899, #1900): typunabhängige Freigaben, Herleitung und Nachfolge
  gelten für jeden Asset-Typ. Ein organisationsweiter Katalog über alle Typen (#1904).
- **Prompt-Bibliothek als zweiter Asset-Typ** (Epic #1726): Modell, API, Oberfläche und Einsatz im
  Chat per Slash-Befehl mit Variablenformular (#1901–#1903). Das ist der erste tragfähige Schnitt
  der Verteilungs-Säule.
- **Reichweite wurde zur Freigabe:** „Für alle“ wurde eine gewöhnliche Freigabe an „Alle Konten“ in
  derselben Rechteliste (#1931, ADR-0037). Für Konnektorbibliotheken kam eine
  Freigabe-Obergrenze hinzu (#797).
- **Werkzeugschleife als Spike** hinter Schalter (#1789). Das Fundament-Epic #1747 ist bewusst
  zurückgestellt.

## 6 · Sicherheit, Nachweis & Betrieb (G/J)

- **Lieferkette und Images:**
  - SBOM als Image-Attestierung und als CycloneDX-Artefakt (#1078)
  - CVE-Erkennung per Dependabot und Trivy (#1079), Triage von 209 Alerts (#1450)
  - Backend-Image auf jlink-JRE über Distroless, Alerts 58 → 18 (#1459, ADR-0029)
  - Backend-Betrieb als Nicht-root-Nutzer (#1471)
  - wöchentlicher Neubau (#1456), automatischer Alarm bei critical-Befunden (#1461)
  - versionierte Unterdrückungsliste mit Ablaufdatum (#1466)
- **Ratenbegrenzung hinter Reverse-Proxys** mit Kontosperre (#1535). Die Löschläufe von
  Nachweisprotokoll und Diagnosekontext greifen nach der eingestellten Frist (#1851). Die
  Mitgliederliste einer Gruppe gibt es für die Systemverwaltung nur noch über den protokollierten
  Abruf (#1989).
- **Originalablage** (Epic #1440, ADR-0030): Upload-Originale liegen wahlweise im Dateisystem oder
  S3-kompatibel, mit Organisation im Ablageschlüssel und zweistufigem Aufräumlauf (#1474–#1478,
  #1518, #1544).
- **Betrieb:**
  - Datenbankschema per `OPAA_DB_SCHEMA` wählbar (#1366, ADR-0034)
  - sanftes Herunterfahren und Readiness ohne Abhängigkeit von KI-Diensten (#1710)
  - zweiseitiger Flyer mit Installationsvoraussetzungen (#1895)
- **Demo „Stadt Rheinfurt“ ausgebaut und neu aufgesetzt** (Epic #2012): Gruppen mit
  Verzeichnisabgleich, Prompt-Bibliotheken, Aktenplan-Ordner, Ratsinformationen im Objektspeicher,
  Fachformate, Formattest auf S3, angepasstes Drehbuch (#2013–#2020, #1519, #1520).

## 7 · Technisches Fundament und Arbeitsweise (T1–T3, V, P)

- **Backend modularisiert** (Epic #1906): Es gibt keine Paketzyklen mehr, die logischen Module und
  ihre Schichtung werden per ArchUnit im Build erzwungen (#2000). Controller liegen in `web`-Paketen
  ihrer Module (#2034, PR#2039, PR#2040). Die OpenAPI-Spezifikation ist in 26 Dateien je Thema
  aufgeteilt (#2002), das Liquibase-Changelog je Modul (#2001, #2003). Retrieval, Formate und
  Metadaten sind eigene Module (#2045–#2047). Vorarbeit war das Struktur-Review des query-Pakets:
  `QueryService` wurde von 894 auf 369 Zeilen gekürzt und in sieben Unterpakete geschnitten
  (#1444, #1455, #1457).
- **Tests und CI:**
  - Spring-Testkontexte von 23 auf vier konsolidiert (#1481)
  - Testisolation über Wächter gesichert (#1573, #1584)
  - Backend-Tests in drei parallelen CI-Shards, CI-Zeit von 7:20 auf 4:09 Minuten (PR#1411)
  - E2E nächtlich und auf `main`, mit automatischem Alarm-Issue
  - Container-Suiten für Confluence, S3 und Keycloak
- **Agenten-Organisation:**
  - Review ab PR-Eröffnung (#1245)
  - Epic-Schnittregel (#1929)
  - Opus als Entwickler-Voreinstellung (#1223)
  - `AGENTS.md` je Modul (#1863, #2005)
- **Konzeption:**
  - Feature-Spezifikationen zu Benchmark, Hybrid-Suche, Ingestion, Metadaten und Fremdzugängen
    (#1032, #1063, PR#1724)
  - geschärfte Vision mit „Was OPAA unterscheidet“ (#1725)
  - Agenten-Konzept mit Marktbefunden und Szenario-Stufen (#1733, #1745)
  - Entscheidung „Chats bleiben privat, bis sie geteilt werden“ (PR#1763)
- **Produkthandbuch** unter `docs/handbuch/` mit Einstiegsseite und Pflegeregel (PR#1436). ADRs:
  36 gepflegt, 17 davon im Zeitraum neu (ADR-0022 bis ADR-0038).

## 8 · Lücken und bewusste Schnitte

- **Reranking ist gebaut, aber per Voreinstellung aus.** Auf CPU kostet es rund drei Minuten je
  Frage. Das Latenzprofil auf Referenzhardware wurde nicht gebaut, weil die Aktivierung neu
  gedacht wird (#1051).
- **Die Modell-Extraktion der Metadaten ist nicht abnahmefähig.** Die Handstichprobe fand die
  Dokumentart oberhalb der Konfidenzschwelle zu 94 % falsch (PR#1358). Sie bleibt ausgeschaltet. Die Kalibrierung ist mit den
  übrigen Nacharbeiten geparkt (#1364, #1704, #1359).
- **Der Qualitätsgewinn des Gesprächsgedächtnisses ist nicht belegt.** Mit dem kleinen
  Eval-Modell senken Suchfenster und Notiz die Mehrrunden-Treffer (#1587). Die erste
  positive Nachmessung war ein Hardware-Artefakt und wurde zurückgezogen (#1490). Mit dem
  Produktionsmodell löst der Messpfad 19 statt 5 von 27 Fällen (#1674). Das Epic ist dennoch
  geschlossen (#1482).
- **Die Diagnosesperre schützt nicht gegen die Systemverwaltung.** Die Systemverwaltung kann sich
  selbst in die Eigentümergruppe eintragen. Tragend ist die Nachvollziehbarkeit über das
  Protokoll, nicht ein Vier-Augen-Prinzip (#1052, #1124).
- **Modularisierung nur logisch:** Statt echter Gradle-Module erzwingen ArchUnit-Regeln die
  Grenzen. Die Gliederung des Frontends ist in Epic #2057 ausgelagert (#1906).
- **Kurswechsel im Zeitraum**, im Report nur mit dem Endzustand geführt:
  - Volltext-Nachzug gebaut und entfernt, weil es keine Bestandssysteme gibt (#1047, #1270)
  - Ansprechstellen an Anbietergruppen nach vier Tagen zurückgebaut (#1875, #1978)
  - Verteilungsstufe `visibility` durch Freigaben ersetzt (#1931)
  - MinIO durch RustFS ersetzt (#1949)
  - Kontowechsel-Link entfernt statt repariert (#1629)
- **Nicht gebaut:**
  - Bestandsnachzüge für Altdaten, bewusst (#1127, #1215)
  - Testcontainers-Reuse (#1414)
  - strukturelle Absicherungen der Rechtehistorie, als unverhältnismäßig eingestuft (#1505,
    #1517)
- **Abweichung von einem ADR:** Die Proxy-Auflösung nutzt einen eigenen Resolver statt der in
  ADR-0033 beschlossenen `RemoteIpValve`. Das ADR ist nicht nachgezogen (#1535).
- **Nicht abwärtskompatibel:** Die Schema-Umstellung verlangt bei bestehenden Installationen
  einen Eingriff (#1366).

## 9 · Offen für Phase 1 — priorisierte Restliste

Gegen die Phase-1-Definition der [Vision](../../VISION.md), nach Gewicht:

**Kern des Wissensassistenten**
1. **Streaming der Antworten** — größter Einzelfaktor der gefühlten Antwortzeit, weiter
   nicht gebaut
2. **Konfidenz als erklärte Größe** — das Erklärprotokoll und die Chunk-Ansicht liefern die
   Grundlage für erklärbares Chunking, eine Konfidenzaussage an der Antwort fehlt
3. **Reranking produktiv machen** — Aktivierungsstrategie und Hardwareprofil (#1051)
4. **Textwerkzeuge einschließlich Leichter Sprache** — Bereich K, weiter ohne Vorgang

**Modelle & Nachweis**
5. **DSGVO-Vollständigkeit** — bis vor den Produktivbetrieb zurückgestellt (#143, #798)
6. **Schadsoftwareprüfung des Uploads**
7. **Integrität des Audit-Trails** und ein extern anbindbarer Ereignisstrom (Epic #1296)

**Betrieb**
8. **Kubernetes und große Installationen** (Epic #1439), Multiinstanzbetrieb (Epic #1292)
   und die **air-gapped-Lieferung**. SBOM und CVE-Scans sind gebaut, signierte Builds fehlen.
9. Mandantenfähigkeit mit mehreren Organisationen in einer Installation (Epic #1442)

**Nachrangig, aus der Restliste des Vormonats fortgeführt**
10. Sitzungsverwaltung mit erzwungener Neuanmeldung und Einschränkung auf Netzbereiche (Punkt 7
    des Vormonats; Netzbereiche gibt es bisher nur für Fremdzugänge, #1717)
11. Antwort-Bewertung mit Speicherung (Hälfte von Punkt 13 des Vormonats) — die konzeptlosen
    Daumen-Schaltflächen wurden entfernt (#1447), ein Konzept steht aus
12. Modell-Obergrenze je Space und Bibliothek (Punkt 6 des Vormonats) — die zentrale
    Modellkonfiguration durch die Systemverwaltung ist gebaut; eine Einschränkung darunter wird
    erst relevant, wenn mehr als ein Chat-Modell zur Wahl steht

Erledigt gegenüber der Restliste des Vormonats: hybride Suche mit Reranking (1), echter
Verzeichnisanschluss mit Kontenlebenszyklus (5), API-Tokens (Hälfte von 13) sowie SBOM und
Sicherheits-Scans in der CI (Teil von 10). Die Modell-Obergrenze (6) ist herabgestuft, weil die
zentrale Modellkonfiguration steht und eine Einschränkung darunter erst mit Modellauswahl greift.

**Begonnen in Phase 2:** OPAA als Wissensschicht mit Zugangstokens und MCP-Server (Epic #1715,
vollständig), die Prompt-Bibliothek als teilbares Asset mit Katalog (Epic #1726), ein Spike zur
Werkzeugschleife (#1789). Offen: das Fundament für Werkzeugaufrufe (Epic #1747) und Skills als
Objektart (Epic #1727).

## 10 · Zahlen zum Zeitraum

| | |
|---|---|
| Gemergte Pull Requests | 489, davon 54 Renovate-Updates |
| Geschlossene Issues | 502, davon 9 „not planned“ und 28 automatisch geöffnete und wieder geschlossene Alarm-Issues aus CI-Läufen |
| Abgeschlossene Epics | 22 |
| Vorgänge mit Befund | 43 von 616 geprüften (7 %; Vorgänger-Stichtag: 131 von 562) |
| Architecture Decision Records | 36 gepflegt, 17 im Zeitraum neu |
| Produkthandbuch | 25 Kapitel unter `docs/handbuch/` |
| Themenbereiche ohne Vorgang im Zeitraum | E (Modelle), H (Monitoring/Kosten), K (Verwaltungs-Spezifika) |

---

*Erstellt nach dem Verfahren in [../README.md](../README.md) (Issue #2068). Datenstand:
`main@17ec7fca8`, GitHub-Abfrage vom 30.09.2026. Folgende Fortschreibungen erheben das Delta ab
den Marken in [anker.md](./anker.md).*
