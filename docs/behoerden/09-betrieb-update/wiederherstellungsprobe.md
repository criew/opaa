---
stand: 2026-10-16
gilt-fuer: main
erzeugung: redaktionell
rechtsstand: 2026-10-13
pruefvermerk: nicht rechtlich geprüft
naechste-durchsicht: 2027-04-15
---

# Wiederherstellungsprobe für OPAA

> Nicht rechtlich geprüft. Kann als Vorlage dienen; die Verantwortung bleibt bei der Behörde.

## Zweck

Eine Sicherung, deren Wiederherstellung nie geprüft wurde, ist keine Sicherung. Diese Vorlage
beschreibt eine Probe, die zeigt, dass Datenbank, Vektorindex und Originalablage aus einer Sicherung
**zueinander passend** zurückkommen, und die Wiederherstellungszeit (RTO) und den
Wiederherstellungspunkt (RPO) misst, die das [Notfallkonzept](notfallkonzept.md) annimmt. Das Ergebnis
gehört mit Version und Datum in das Notfallkonzept und in das Sicherheitskonzept
([Grundschutz-Modellierung](../06-informationssicherheit/grundschutz-modellierung.md), Bausteine
CON.3 und DER.4).

Die Probe stützt sich auf die Sicherungs- und Wiederherstellungswege des Handbuchs und baut keinen
eigenen. Die Anforderungen an einen Wiederherstellungstest aus der Produktspezifikation
([Notfallwiederherstellung](../../features/deployment-infrastructure.md#notfallwiederherstellung))
sind darin umgesetzt.

## Ergebnis

| Feld | Eintrag |
|---|---|
| Stand im Projekt | **Automatisiert durchgeführt**, mit den Abweichungen unter [Automatisierte Probe im Projekt](#automatisierte-probe-im-projekt); erster grüner Lauf: [Lauf 38857652429, Job 116832643624](https://github.com/criew/opaa/actions/runs/38857652429/job/116832643624) |
| Datum der Probe | 2026-10-16 |
| OPAA-Version (Release-Tag, Image-Digests) | Entwicklungsstand `main`, Commit `ef3b795`, Backend `0.0.0-dev`, kein Release; Images aus dem Commit gebaut |
| Helm-Chart-Version bzw. Compose-Stand | Chart `0.0.0-dev` aus dem Repository |
| Betriebsweg und Ablage | Kubernetes (kind), Erprobungsdatenbank, Originale auf einem Volume |
| Größe des Bestands | kleiner Testbestand: drei Dokumente mit je einem Original; Größen von Dump und Originalen siehe Zusammenfassung des Laufs |
| RTO-Ist (Beginn der Wiederherstellung bis arbeitsfähig) | 59 s bis zum bereiten Backend (`t3 − t0`), 62 s bis zum Abschluss der Prüfungen (`t4 − t0`); davon Originale 35 s, Datenbank 3 s, Start 21 s |
| RPO-Ist (Sicherungszeitpunkt bis simulierter Ausfall) | 60 s, im Lauf gesetzt |
| Zeitpunkte (UTC) | tS 05:56:23, tA 05:57:23, t0 05:57:23, t1 05:57:58, t2 05:58:01, t3 05:58:22, t4 05:58:25 |
| Prüfkriterien P1 bis P12 | P1 bis P4, P6 und P9 bis P12 erfüllt; P5, P7 und P8 nicht automatisiert, sie bleiben in der manuellen Probe |
| Abweichungen und Folgemaßnahmen | P4: Der erste Start des Ziels holte zwei Indizes auf dem Vektorindex nach, weil die Quelle nur einmal gestartet war; P4 und der [Rückweg](../../handbuch/kubernetes.md#rückweg) nennen diese Ausnahme |
| Durchgeführt von, abgenommen von | CI des Projekts; nicht abgenommen |

Die Zeiten stammen aus dem kleinen Testbestand auf einem CI-Rechner und sind nicht auf den Betrieb
übertragbar. Eine Behörde trägt hier das Ergebnis ihrer eigenen Probe ein.

Zeigt die Probe eine Abweichung vom Handbuch, ist das ein Fehler in der Dokumentation oder im
Produkt; bitte als Issue im Projekt melden, bei einer Sicherheitsfrage über den
[Meldeweg](../../../SECURITY.md#meldeweg).

## Automatisierte Probe im Projekt

Das Projekt führt die Probe selbst aus: Im Workflow
[`helm-chart.yml`](../../../.github/workflows/helm-chart.yml) folgt im Job „Installationstest auf
kind“ auf den Installationstest der Schritt „Wiederherstellungsprobe“ mit dem Skript
[`deploy/helm/ci/restore-test.sh`](../../../deploy/helm/ci/restore-test.sh). Er läuft nächtlich, in
Pull Requests mit Änderungen an Chart, Workflow oder Images und auf Anforderung über das Label
`helm-suite`; das Skript läuft lokal gegen einen kind-Cluster genauso. Es baut den Referenzaufbau mit
zwei Namespaces auf, folgt dem Ablauf unten mit den Befehlen des Handbuchs und bricht bei der ersten
verfehlten Prüfung ab. Geprüft sind damit das Sichern der Erprobungsdatenbank und der Originale und
die Wiederherstellung in eine neue Installation mit neuem Signaturgeheimnis. Nicht geprüft sind das
Zurückspielen in dieselbe Installation, das Ersetzen des Signaturgeheimnisses im vorhandenen Secret
und das Leeren und Neuanlegen der Erprobungsdatenbank vor dem Einspielen. Die Zusammenfassung des
Laufs nennt Commit, Version des Backends, Datum, das Ergebnis jeder Prüfung, die Dauern aus der
[Messung](#messung) und die Größen von Dump, Originalen und Bestand.

Geprüft werden P1 bis P4, P6 und P9 bis P12. Gegenüber dem Verfahren für eine Installation weicht
der Lauf so ab:

| Punkt | Im automatisierten Lauf |
|---|---|
| Modell-Endpunkt | keiner; die Modelladressen der Werte-Datei sind nicht auflösbar. Die Indexierung schlägt fehl, die Dokumente bleiben mit ihren Originalen stehen |
| Testbestand | T1 mit drei Textdateien (TXT, Markdown, CSV), eine davon mit dem Probe-Marker, dazu T6 und T7. T3 bis T5 entfallen, weil die Prüfungen fehlen, die sie brauchen |
| Bedienung | über die API mit dem Systemverwalter-Konto statt über die Oberfläche |
| Ausfall | Nach dem Upload aus Schritt 3 wird das Backend der Quelle angehalten |
| P4 | Statt der Zahl werden die Changesets einzeln verglichen; zulässig sind nur die beiden nachgeholten Indizes, die P4 nennt |
| P9 | nur der Upload; die Frage nach M2 und der Chat entfallen mangels Chat-Modell |
| P10 | Statt der Browsersitzung dient ein Zugangstoken aus Schritt 1. Seine Gültigkeit ist im Lauf verlängert, damit die Ablehnung nur vom neuen Signaturgeheimnis kommen kann; die Quelle nimmt es nach ihrem Neustart in Schritt 2 noch an |
| P11 | Der Verbindungstest erreicht keinen Endpunkt, entschlüsselt den gespeicherten Schlüssel aber vor dem Aufruf. Erwartet wird im Ziel genau die Antwort der Quelle; mit einem falschen Schlüssel endet der Test mit einem Serverfehler |
| P12 | nur die Anzeige der hinterlegten Zugangsdaten, ohne Verbindungstest |
| Messung | Zeiten mit dem kleinen Testbestand auf einem CI-Rechner; sie belegen das Verfahren, nicht die Wiederherstellungszeit einer Installation |

**P5, P7 und P8 bleiben in der manuellen Probe.** P7 und P8 brauchen ein Chat-Modell für die belegte
Antwort, alle drei einen gefüllten Vektorindex und damit ein Embedding-Modell. Ein Modell-Server mit
kleinem Embedding-Modell im Lauf brächte Modell-Download und Indexierung in jeden Lauf und deckte P7
und P8 trotzdem nicht ab.

## Rahmen

- **Auf ein leeres System.** Zurückgespielt wird in eine zweite, leere Installation, nie in die
  laufende. Nur so zeigt sich, ob die Sicherung vollständig ist.
- **Dieselbe Version.** Quelle und Ziel laufen mit derselben OPAA-Version und demselben
  Embedding-Modell in derselben Dimension (Annahmen A6 und A7 im
  [Notfallkonzept](notfallkonzept.md#annahmen)).
- **Dieselben Verschlüsselungsschlüssel, ein neues Signaturgeheimnis.** Das Ziel bekommt die beiden
  Verschlüsselungsschlüssel und das Datenbankpasswort der Quelle, aber ein neu erzeugtes
  Signaturgeheimnis ([Schlüssel und Geheimnisse](../../handbuch/betrieb.md#schlüssel-und-geheimnisse)).
- **Testbestand statt Echtdaten**, solange die Probe nicht in der Produktivumgebung stattfindet. Wer
  mit einer Kopie echter Daten probt, behandelt Sicherung und Ziel wie die Produktivdaten und löscht
  beides danach.

## Referenzaufbau

Das Verfahren ist so beschrieben, dass es auf einem Testcluster mit dem Helm-Chart nachvollziehbar
ist: Quelle und Ziel sind zwei Namespaces mit je einem Release `opaa`, beide mit der Werte-Datei
aus [Erprobung ohne Eingang und ohne eigene Datenbank](../../handbuch/kubernetes.md#erprobung-ohne-eingang-und-ohne-eigene-datenbank)
(Erprobungsdatenbank, Originale auf einem Volume). Voraussetzung ist ein Modell-Endpunkt mit dem
Chat- und dem Embedding-Modell dieser Werte-Datei.

Für die eigene Installation der Behörde gilt dasselbe Verfahren mit deren Werkzeugen:

| Schritt | Referenzaufbau | Docker Compose | Eigene PostgreSQL, Objektspeicher |
|---|---|---|---|
| Backend anhalten | `kubectl scale … --replicas=0` | `docker compose stop backend` | wie links |
| Datenbank sichern und zurückspielen | [Erprobungsdatenbank sichern](../../handbuch/kubernetes.md#erprobungsdatenbank-sichern) | [Sicherung und Wiederherstellung](../../handbuch/docker-compose.md#sicherung-und-wiederherstellung) | Werkzeuge des Datenbankbetriebs, mit den [Voraussetzungen](../../handbuch/datenbank.md#voraussetzungen-einer-eigenen-postgresql) |
| Originale sichern und zurückspielen | Kopier-Pod aus [Sicherung und Wiederherstellung](../../handbuch/kubernetes.md#sicherung-und-wiederherstellung) | `tar` und Eigentum zurückgeben, wie im Handbuch | Werkzeuge des Objektspeichers, in einen leeren Bucket ([Originalablage](../../handbuch/originalablage.md#sicherung-und-wiederherstellung)) |
| Ziel aufbauen | [Wiederherstellung auf einem neuen Cluster](../../handbuch/kubernetes.md#sicherung-und-wiederherstellung) | zweiter Host oder zweites Projektverzeichnis | eigene leere Datenbank und eigener leerer Bucket |

## Testbestand

Vor der Sicherung legt die Systemverwaltung in der Quelle an:

| Nr. | Objekt | Zweck |
|---|---|---|
| T1 | Eine Upload-Bibliothek mit mindestens drei einfachen Dateien (etwa PDF, DOCX, TXT), ohne Archive und ohne E-Mails mit Anhängen | Originale und Index; einfache Dateien haben genau ein Original je Dokument, das macht P6 eindeutig |
| T2 | In einer dieser Dateien den Satz „Probe-Marker vor der Sicherung: [zufälliges Wort M1]“ | belegte Antwort nach der Wiederherstellung |
| T3 | Einen Space mit zwei Mitgliedern, T1 zugeordnet | Rechte und Mitgliedschaften |
| T4 | Eine Testperson ohne Zugriff auf T1 (lokales Konto oder Konto beim Identitätsanbieter) | Rechteprobe |
| T5 | Einen Chat mit einer Frage nach M1 | Chats |
| T6 | Einen zweiten, nicht aktiven Chat-Modell-Eintrag auf denselben Modell-Endpunkt, mit einem beliebigen Zugangsschlüssel; „Verbindung testen“ gelingt | Schlüssel für die Einstellungen richtig zurückgespielt (P11) |
| T7 | Eine Bibliothek mit Webquelle und hinterlegten Zugangsdaten für die Basic-Anmeldung (`benutzer:passwort`), am besten gegen eine Quelle, die die Anmeldung verlangt | Schlüssel für die Zugangsdaten richtig zurückgespielt (P12) |

Wo die Installation weitere Bausteine nutzt, kommen sie dazu, etwa eine Bibliothek mit einer
angebundenen Quelle oder der Zugriff externer KI-Werkzeuge.

## Ablauf

Die Zeitpunkte werden fortlaufend in eine Datei geschrieben, etwa mit
`echo "t0 $(date -u +%FT%TZ)" >> zeiten.txt`. Im Referenzaufbau gilt:

```bash
QUELLE=opaa-probe-quelle
ZIEL=opaa-probe-ziel
VERSION=<X.Y.Z>
```

### 1. Ausgangsstand festhalten

1. Version notieren: Startzeile des Backends oder `/actuator/info`
   ([Diagnose ohne Shell](../../handbuch/betrieb.md#diagnose-ohne-shell)), dazu die Image-Digests.
2. Auf der Seite „Suche & Indexierung“ das Embedding-Modell und den Indexstatus notieren.
3. Die Zahl der Dokumente in T1 notieren, wie die Bibliotheksansicht sie zeigt.
4. Als Testperson T4 oder als Mitglied von T3 angemeldet bleiben; der Browser behält die Sitzung für
   P10.

### 2. Sichern (Zeitpunkt tS)

Das Backend wird angehalten, damit Datenbank und Originale genau zusammenpassen
([Sicherung und Wiederherstellung](../../handbuch/kubernetes.md#sicherung-und-wiederherstellung)).

1. Backend anhalten, `tS` notieren.
2. **Zählung der Quelle.** Die folgende Abfrage zählt die Zeilen jeder Tabelle der Datenbank, ohne
   Tabellennamen zu kennen; sie bleibt deshalb über Versionen gültig. Als `zaehlung.sql` speichern:

   ```sql
   SELECT format('%I.%I', n.nspname, c.relname) AS tabelle,
          (xpath('/row/n/text()',
                 query_to_xml(format('SELECT count(*) AS n FROM %I.%I', n.nspname, c.relname),
                              false, true, '')))[1]::text AS zeilen
     FROM pg_class c
     JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE c.relkind = 'r'
      AND n.nspname NOT IN ('pg_catalog', 'information_schema')
      AND n.nspname NOT LIKE 'pg\_toast%'
    ORDER BY 1;
   ```

   ```bash
   kubectl -n "$QUELLE" exec -i opaa-postgresql-0 -- \
     sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -At -F";" -f -' < zaehlung.sql > zaehlung-quelle.txt
   ```

3. **Datenbank sichern** mit `pg_dump -Fc` nach dem Handbuch ([Erprobungsdatenbank sichern](../../handbuch/kubernetes.md#erprobungsdatenbank-sichern)).
4. **Originale sichern** mit dem Kopier-Pod nach dem Handbuch. In dessen Pod-Beschreibung steht
   der Namespace der Quelle statt `opaa`, und unter `securityContext` kommt `fsGroup: 65532` hinzu,
   wie in der Werte-Datei. Vor dem Packen eine Prüfsummenliste aller Originale erzeugen:

   ```bash
   kubectl -n "$QUELLE" exec opaa-uploads-kopie -- \
     sh -c 'cd /uploads && find . -type f -exec sha256sum {} \;' | sort -k2 > originale-quelle.txt
   ```

5. Prüfsummen der beiden Sicherungsdateien festhalten: `sha256sum opaa-db.dump opaa-uploads.tar`.
6. Kopier-Pod löschen (`kubectl -n "$QUELLE" delete pod opaa-uploads-kopie`), wie im Handbuch: Ein
   `ReadWriteOnce`-Volume hält er sonst fest, und das Backend startet nicht.
7. Backend der Quelle wieder starten.

### 3. Datenverlust erzeugen (Zeitpunkt tA)

1. In T1 eine weitere Datei mit dem Satz „Probe-Marker nach der Sicherung: [zufälliges Wort M2]“
   hochladen und einen Chat mit einer Frage nach M2 führen.
2. Den Ausfall annehmen und `tA` notieren. Alles zwischen `tS` und `tA` gilt als verloren; die
   Probe zeigt, dass es im Ziel tatsächlich fehlt.

### 4. Wiederherstellen (ab Zeitpunkt t0)

Die Reihenfolge folgt [Wiederherstellung auf einem neuen Cluster](../../handbuch/kubernetes.md#sicherung-und-wiederherstellung).

1. `t0` notieren.
2. Namespace des Ziels anlegen wie bei der [Installation](../../handbuch/kubernetes.md#1-namespace-anlegen).
3. Chart in derselben Version installieren, **Backend angehalten**, mit den Verschlüsselungsschlüsseln
   und dem Datenbankpasswort der Quelle und einem neuen Signaturgeheimnis:

   ```bash
   NEUES_JWT="$(openssl rand -base64 48 | tr -d '\n')"
   helm install opaa oci://ghcr.io/criew/charts/opaa --version "$VERSION" -n "$ZIEL" \
     -f opaa-erprobung.yaml --set backend.replicas=0 --set-string secrets.jwtSecret="$NEUES_JWT"
   ```

4. **Originale zurückspielen** in das noch leere Volume, mit dem Kopier-Pod im Namespace des Ziels.
   Danach dieselbe Prüfsummenliste im Ziel erzeugen (`originale-ziel.txt`) und den Kopier-Pod
   löschen (`kubectl -n "$ZIEL" delete pod opaa-uploads-kopie`), bevor das Backend startet. `t1`
   notieren.
5. **Datenbank zurückspielen** in die leere Erprobungsdatenbank: zuerst die Rolle
   `opaa_audit_owner` anlegen, die nicht im Dump steht
   ([Erprobungsdatenbank sichern](../../handbuch/kubernetes.md#erprobungsdatenbank-sichern)), dann
   einspielen und den Rückgabewert festhalten. `t2` notieren.

   ```bash
   kubectl -n "$ZIEL" wait --for=condition=Ready pod/opaa-postgresql-0 --timeout=5m
   kubectl -n "$ZIEL" exec opaa-postgresql-0 -- sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
     -c "CREATE ROLE opaa_audit_owner NOLOGIN" -c "GRANT opaa_audit_owner TO \"$POSTGRES_USER\""'
   kubectl -n "$ZIEL" exec -i opaa-postgresql-0 -- \
     sh -c 'pg_restore -U "$POSTGRES_USER" -d "$POSTGRES_DB" --exit-on-error' < opaa-db.dump
   echo "pg_restore: $?"
   ```
6. **Zählung des Ziels** mit derselben Abfrage, **bevor** das Backend startet
   (`zaehlung-ziel.txt`).
7. **Backend starten**, mit demselben neuen Signaturgeheimnis; ohne `--set-string` setzte das
   Upgrade wieder das der Werte-Datei, also das der Quelle. Warten, bis die Bereitschaftsprobe `UP`
   meldet; `t3` notieren.

   ```bash
   helm upgrade opaa oci://ghcr.io/criew/charts/opaa --version "$VERSION" -n "$ZIEL" \
     -f opaa-erprobung.yaml --set-string secrets.jwtSecret="$NEUES_JWT"
   kubectl -n "$ZIEL" rollout status deploy/opaa-backend --timeout=15m
   ```
8. Die Portweiterleitung von der Quelle auf das Ziel umstellen, unter derselben Adresse wie
   `publicBaseUrl` (`kubectl -n "$ZIEL" port-forward svc/opaa-frontend 8080:80`). Dann die
   Prüfungen P4 bis P12 durchführen. Sind sie bestanden, `t4` notieren. Die Gesundheitsgruppen für
   P5 antworten nur auf dem Management-Port des Backends, den das Frontend nicht weiterreicht
   ([Management-Port](../../handbuch/kubernetes.md#management-port)); sie verlangen ein gültiges
   Zugangstoken ([Bereitschaft und Lebendigkeit](../../handbuch/betrieb.md#bereitschaft-und-lebendigkeit)):

   ```bash
   kubectl -n "$ZIEL" port-forward deploy/opaa-backend 8081:8081
   curl -s -H "Authorization: Bearer <token>" http://localhost:8081/actuator/health/embedding-model
   curl -s -H "Authorization: Bearer <token>" http://localhost:8081/actuator/health/vector-store
   ```

## Prüfkriterien

| Nr. | Prüfung | Erwartet |
|---|---|---|
| P1 | `pg_restore --exit-on-error` | Rückgabewert 0, keine Fehlermeldung |
| P2 | `diff zaehlung-quelle.txt zaehlung-ziel.txt` | keine Ausgabe: jede Tabelle hat im Ziel so viele Zeilen wie in der Quelle zum Zeitpunkt `tS`, einschließlich Vektor- und Volltextindex und Nachweisprotokoll |
| P3 | `diff originale-quelle.txt originale-ziel.txt` | keine Ausgabe: jedes Original ist bitgleich zurück |
| P4 | Version nach dem Start; Zahl der Zeilen in `databasechangelog` vor und nach dem Start ([Rückweg](../../handbuch/kubernetes.md#rückweg)) | Version wie in Schritt 1; die Zahl bleibt gleich, der Start wendet kein Changeset erneut an. Einzige Ausnahme: War das Backend der Quelle vor der Sicherung nur ein einziges Mal gestartet, holt der erste Start des Ziels zwei Indizes auf dem Vektorindex nach, und die Zahl wächst um genau zwei |
| P5 | Seite „Suche & Indexierung“; Gesundheitsgruppen `embedding-model` und `vector-store` | gleiches Embedding-Modell wie in Schritt 1, kein Hinweis „Index veraltet“, Vektorsuche aktiv; beide Gruppen `UP` |
| P6 | Bericht verwaister Originale für T1 ([Originale einer vorhandenen Bibliothek](../../handbuch/originalablage.md#originale-einer-vorhandenen-bibliothek)) | keine verwaisten Originale; die Zahl der referenzierten Originale gleich der Zahl der Dokumente aus Schritt 1, also kein Dokument ohne Original |
| P7 | Fachliche Stichprobe: Anmeldung, Space T3 mit seinen Mitgliedern, Frage nach M1, „Original öffnen“ an der Fundstelle | belegte Antwort mit Fundstelle in T1; das geöffnete Original hat dieselbe Prüfsumme wie die hochgeladene Datei; der Chat T5 ist vorhanden |
| P8 | Rechteprobe: Testperson T4 fragt nach M1 | T1 ist für sie nicht sichtbar, die Antwort nennt keine Fundstelle aus T1 |
| P9 | Wiederherstellungspunkt: Frage nach M2, Chatliste | M2 ist nicht auffindbar, der Chat aus Schritt 3 fehlt |
| P10 | Sitzung aus Schritt 1 im selben Browser gegen das Ziel | Die alte Sitzung gilt nicht, eine neue Anmeldung ist nötig |
| P11 | Chat-Modell T6: „Verbindung testen“ im Menü des Modells, ohne neuen Schlüssel; der Test entschlüsselt dabei den gespeicherten Schlüssel | Der Test gelingt wie in der Quelle, ohne Meldung, der Schlüssel könne nicht entschlüsselt werden. Dass der Schlüssel als hinterlegt angezeigt wird, genügt nicht: Die Anzeige prüft nur, ob ein Geheimtext gespeichert ist |
| P12 | Bibliothek T7: Quelle bearbeiten; bei einer Quelle mit Anmeldung „Verbindung testen“ ohne neue Zugangsdaten | Die Zugangsdaten gelten als hinterlegt. Diese Anzeige entschlüsselt: Ein unlesbarer Wert erscheint als „nicht gesetzt“, und das Protokoll trägt eine Warnung ([Zugangsdaten-Verschlüsselung](../../handbuch/betrieb.md#zugangsdaten-verschlüsselung)). Der Verbindungstest gelingt wie in der Quelle |

P2 und P3 zeigen, dass die Sicherung vollständig zurückkam; P5 und P6 zeigen, dass Index, Datenbank
und Originalablage zueinander passen; P7 und P8 zeigen, dass Inhalte und Rechte stimmen; P11 und P12
zeigen, dass beide Verschlüsselungsschlüssel zur Datenbank passen.

## Messung

| Kennzahl | Berechnung | Hinweis |
|---|---|---|
| RTO-Ist | `t4 − t0` | misst nur die technische Wiederherstellung. Für den Ernstfall kommen Erkennung, Entscheidung und gegebenenfalls Beschaffung hinzu |
| davon Originale | `t1 − t0` | wächst mit der Menge der Originale |
| davon Datenbank | `t2 − t1` | wächst mit der Größe der Datenbank, vor allem mit dem Vektorindex |
| davon Start | `t3 − t2` | |
| RPO-Ist | `tA − tS` | in der Probe gesetzt; im Betrieb höchstens der Abstand zweier Sicherungen. P9 belegt, dass alles nach `tS` fehlt |

Für die Hochrechnung auf den Produktivbestand gehören die Größe der Datenbank, die Menge der
Originale und die Zahl der Dokumente in das Ergebnis. Die Laufzeiten von Datenbank und Originalen
wachsen etwa linear mit ihrer Größe; eine Probe mit dem Testbestand belegt das Verfahren, eine Probe
mit einer Kopie des Produktivbestands belegt die Wiederherstellungszeit.

## Nach der Probe

- Ziel-Namespace samt Volumes löschen, Sicherungsdateien des Testbestands löschen.
- Ergebnis oben eintragen und im [Notfallkonzept](notfallkonzept.md#wiederherstellungszeit-und-wiederherstellungspunkt)
  die Spalte „Belegt durch Probe vom“ fortschreiben.
- Die Probe wiederholen: regelmäßig nach dem Plan des Hauses, nach jedem Update mit
  Vorbereitungsschritten und nach jeder Änderung am Aufbau der Installation.
