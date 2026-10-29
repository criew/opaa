# ZAP-Baseline-Scan

Der ZAP-Baseline-Scan prüft nächtlich, was ein anonymer Aufrufer von einer laufenden
OPAA-Installation zu sehen bekommt: Sicherheits-Header, Cookies, Inhaltstypen, preisgegebene
Informationen. Er ergänzt die Prüfungen aus [`docs/cve-scanning.md`](../cve-scanning.md), die
Abhängigkeiten, Images und Quelltext untersuchen, um den Blick auf die laufende Anwendung (Issue
#3384).

## Was der Lauf tut

`.github/workflows/zap-baseline.yml` läuft nächtlich (`45 4 * * *`, nach `e2e.yml` und
`cve-scan.yml`), manuell über *Run workflow* und in einem Pull Request, der den Scan selbst ändert
(Workflow, `.github/zap/`, `zap_baseline.sh`, `zap_rules.py`). Andere Pull Requests verlangsamt er
nicht.

1. **Images aus dem Commit.** Backend und Frontend werden aus dem ausgecheckten Stand gebaut, mit
   dem Layer-Cache, den `e2e.yml` auf `main` schreibt; der Lauf selbst schreibt keinen Cache.
2. **Stack wie eine Installation.** `.github/scripts/zap_baseline.sh` startet denselben
   Compose-Aufbau wie das E2E-Ziel `local-auth`: Betriebsmodus `oidc` mit lokalen Konten aus
   `e2e/installation/local-auth.json`, ohne Keycloak, mit `ai-stub` und `mailpit`. Schlüssel und
   Signaturgeheimnis entstehen je Lauf. Gescannt wird über den nginx des Frontends, den einzigen
   Eingang einer Installation (Compose wie Helm-Chart); auch die API erreicht der Scan nur dort.
3. **Passiver Scan, anonym.** `zap-baseline.py` aus dem Image `ghcr.io/zaproxy/zaproxy`
   (Version und Index-Digest im Workflow) ruft die Startseite auf und folgt ihren Links. Weil weder
   die SPA noch die API auf API-Pfade verlinken, ruft `.github/zap/hooks.py` vorher eine feste
   Liste von Pfaden ab: jede eigene nginx-`location`, die anonymen API-Endpunkte und je ein Beispiel
   für eine abgewiesene (`401`) und eine unbekannte Route. Nur `GET`; der Scan greift nicht an,
   meldet sich nicht an und ändert keinen Zustand.
4. **Bewertung.** Jede Warnung der Stufe *Low* oder höher, die `.github/zap/rules.yaml` nicht
   unterdrückt, macht den Lauf rot; ebenso ein Scan, der keinen Bericht liefert. Informative
   Befunde (*Informational*) zählen nie, wie bei `zap-baseline.py` selbst.
5. **Ergebnis.** Die Zusammenfassung des Jobs listet neue, unterdrückte und informative Befunde und
   Einträge, die nichts mehr unterdrückt haben. Das Artefakt `zap-baseline` (14 Tage) enthält den
   Bericht als HTML, JSON und Markdown, das ZAP-Protokoll und die Container-Logs.

**Nicht im Umfang:** aktiver Scan (`zap-full-scan.py`, `zap-api-scan.py`), angemeldete Scans und
Scans echter Installationen. Was ein angemeldeter Aufrufer sieht, prüfen die Integrationstests des
Backends.

Ein roter Lauf öffnet kein Issue. Er fällt in der Liste der Workflow-Läufe auf und landet bei der
Person, die den Zeitplan zuletzt geändert hat; die Behebung geht danach den normalen Weg über ein
Issue.

## Umgang mit einem Befund

**Beheben ist der Normalfall.** Ein Header, ein Cookie-Attribut oder eine Antwort, die ZAP zu
Recht bemängelt, wird im Code oder in `frontend/nginx.conf` bzw.
`frontend/nginx-security-headers.conf` korrigiert, mit Test nach dem
[Reproduktionsnachweis](../../AGENTS.md#reproduktionsnachweis). Vorbild: das Cookie `XSRF-TOKEN`
ohne `SameSite` (Regel 10054), behoben im PR, der den Scan einführte.

**Unterdrücken nur mit Begründung und Ablaufdatum.** Ist der Befund gewollt oder ein Fehlalarm,
kommt ein Eintrag nach `.github/zap/rules.yaml`:

```yaml
rules:
  - id: 10010
    name: Cookie No HttpOnly Flag
    paths: ["/api/"]          # optional: reguläre Ausdrücke ab Pfadanfang
    params: ["XSRF-TOKEN"]    # optional: genauer Wert des Parameters, etwa ein Cookie-Name
    reason: >-
      Welcher Code die Antwort erzeugt, warum der Befund dort keine Lücke ist und wann die
      Begründung neu zu prüfen ist.
    expires: 2027-01-27
```

- **`id`, `name`, `reason` und `expires` sind Pflicht**; die Begründung folgt der
  [Begründungsqualität](../cve-scanning.md#unterdrücken-statt-dismissen) der Image-Scans.
- **`expires` liegt höchstens 92 Tage voraus.** Ab diesem Tag (00:00 UTC) gilt der Eintrag nicht
  mehr: Der Job `rules-check` scheitert, und der Scan lässt den Eintrag weg, sodass der Befund
  wieder zählt. Ein abgelaufener Eintrag wird erneuert, wenn die Begründung noch trägt, sonst wird
  der Befund behoben.
- **So eng wie möglich.** `paths` und `params` begrenzen den Eintrag; trifft ein Befund derselben
  Regel eine andere Stelle, bleibt er neu. ZAPs eigene Regeldatei kennt keine Parameter, deshalb
  setzt `params` allein die Auswertung durch.
- **Ein Eintrag ohne Befund** steht in der Zusammenfassung unter „Unterdrückungen ohne Befund“
  und wird entfernt.

`.github/scripts/zap_rules.py` prüft die Datei (`check`), erzeugt daraus ZAPs Regeldatei
(`render`) und bewertet den Bericht (`summary`); `test_zap_rules.py` hält das Verhalten und die
Anbindung an Workflow und Renovate fest und läuft im Job `report-script` von `ci.yml`.

**Neue ZAP-Version.** Renovate schlägt neue Release-Tags `X.Y.Z` als PR vor, ohne Auto-Merge;
der Scan läuft in diesem PR. Bringt die Version neue Regeln mit neuen Befunden, werden sie im
selben PR behoben oder begründet unterdrückt.

## Lokal ausführen

```bash
# aus dem Repository-Wurzelverzeichnis; braucht Docker, python3 mit PyYAML, curl und openssl
.github/scripts/zap_baseline.sh ghcr.io/zaproxy/zaproxy:2.17.0 zap-out
python3 .github/scripts/zap_rules.py summary --rules .github/zap/rules.yaml \
  --report zap-out/zap-report.json --zap-exit $?
```

Ohne `ZAP_SKIP_BUILD=true` baut Compose die Images selbst. Der Stack läuft als Compose-Projekt
`opaa-zap` (Frontend auf Port 13010, Subnetz 172.31.0.0/16, wie das E2E-Ziel `local-auth`, also
nicht gleichzeitig mit diesem) und wird nach dem Lauf wieder abgebaut.
