"""Writes the unit test of the chart's alerts, deploy/helm/opaa/ci/prometheusrule-test.yaml, from the
case list below: gen_rule_tests.py RULES_JSON OUT. deploy/helm/ci/rule-test.sh runs it and fails
when the checked-in file differs; a new or changed case goes here, never into the YAML.

The expected summary of each alert is written out below (SUMMARY), so a summary that renders
wrong or empty fails. Description and runbook URL, and the static labels, are taken from the
rendered rules (RULES_JSON, JSON of .spec) with labels and the value filled in, so a case states
only the labels its alert adds; the expressions are what the cases test. Every series carries the labels the ServiceMonitor gives it,
the scrape job included, so a rule that groups by a label Prometheus sets itself shows up.
"""
import json
import re
import sys

rules = json.load(open(sys.argv[1]))
out_path = sys.argv[2]

by_name = {}
for g in rules["groups"]:
    for r in g["rules"]:
        by_name.setdefault(r["alert"], []).append(r)

S = 'namespace="opaa",service="r-opaa-backend",job="r-opaa-backend",instance="10.0.0.1:8081"'
DEP = "r-opaa-backend"


def ser(name, labels, values):
    lab = S + ("," + labels if labels else "")
    return (f"{name}{{{lab}}}", values)


def kube(name, dep, values):
    return (f'{name}{{namespace="opaa",deployment="{dep}"}}', values)


def fill(text, labels, value):
    text = re.sub(r"\{\{ \$labels\.(\w+) \}\}", lambda m: labels.get(m.group(1), ""), text)
    text = text.replace('{{ printf "%.0f" $value }}', "%.0f" % value if value is not None else "")
    return text


# Expected summary per alert, or per alert and severity; {label} is filled from the alert's labels.
SUMMARY = {
    "OpaaBackendNotReady": {"critical": "Kein Pod des OPAA-Backends bereit",
                            "warning": "Nicht alle Pods des OPAA-Backends bereit"},
    "OpaaBackendMetricsMissing": "Keine Metriken vom OPAA-Backend",
    "OpaaFrontendNotReady": {"critical": "Kein Pod des OPAA-Frontends bereit",
                             "warning": "Nicht alle Pods des OPAA-Frontends bereit"},
    "OpaaHeapUsageHigh": "Heap des OPAA-Backends nach der Speicherbereinigung fast voll",
    "OpaaDatabasePoolTimeouts": "Anfragen bekommen keine Datenbankverbindung",
    "OpaaInstanceHeartbeatStale": "Herzschlag einer OPAA-Instanz veraltet",
    "OpaaInvalidationBusDisconnected": "OPAA-Instanz hört keine Cache-Meldungen",
    "OpaaVersionMix": "Mehrere OPAA-Versionen laufen nebeneinander",
    "OpaaMetricUnreadable": "OPAA kann einen Betriebswert nicht aus der Datenbank lesen",
    "OpaaIdentityProviderRejected": "Anmeldung über den Identitätsanbieter gestört",
    "OpaaHighErrorRate": {"warning": "Viele Fehlerantworten des OPAA-Backends",
                          "critical": "Sehr viele Fehlerantworten des OPAA-Backends"},
    "OpaaChatSloBurn": {"critical": "Fehlerbudget der Chat-Fragen verbrennt schnell",
                        "warning": "Fehlerbudget der Chat-Fragen verbrennt anhaltend",
                        "info": "Fehlerbudget der Chat-Fragen verbrennt schleichend"},
    "OpaaTimeToFirstTokenHigh": "Chat antwortet langsam",
    "OpaaRetrievalMostlyEmpty": "Suche findet fast nichts mehr",
    "OpaaModelRoleDown": "Modellrolle {role} nicht erreichbar",
    "OpaaOutboundAuthFailing": "Ausgehende Aufrufe scheitern an Anmeldung oder Zertifikat",
    "OpaaTokenBudgetForecast": "Token-Verbrauch übersteigt das Monatsbudget",
    "OpaaOutboundThrottled":"Anbieter drosselt Aufrufe, auf die jemand wartet",
    "OpaaIndexingStalled": "Indexierung ohne Fortschritt",
    "OpaaLibrariesOverdue": "Bibliotheken verpassen ihren Zeitplan",
    "OpaaIndexingRunsFailing": "Viele Indexierungsläufe scheitern",
    "OpaaDirectorySyncStale": "Verzeichnisabgleich veraltet",
    "OpaaDirectorySyncPlanPending": "Abgleichplan wartet auf Bestätigung",
    "OpaaScheduledJobOverdue": "Geplanter Lauf überfällig",
    "OpaaQueueBacklog": "Warteschlange {queue} staut sich",
    "OpaaMailDeliveryFailing": "Mails werden nicht zugestellt",
    "OpaaAuditRetentionOverdue": "Löschlauf des Revisionsprotokolls ausgeblieben",
    "OpaaAuditPayloadDropped": "Revisionsprotokoll ohne nicht gelistete Angaben geschrieben",
    "OpaaAuditChainStalled": "Prüfsummenkette des Revisionsprotokolls steht",
    "OpaaAuditChainUnsealed": "Protokollmonat ohne Siegel",
    "OpaaAuditLogHorizon": {"warning": "Revisionsprotokoll bald ohne angelegten Monat",
                            "critical": "Revisionsprotokoll kann bald nicht mehr schreiben"},
    "OpaaMalwareScanFailing": "Virenprüfung scheitert",
    "OpaaMalwareDetected": "Virenfund",
    "OpaaMalwareSignaturesOutdated": "Virensignaturen veraltet",
    "OpaaGlobalRateLimitHit": "Globale Grenze {limit} erreicht",
    "OpaaLoginFailureSpike": "Ungewöhnlich viele abgewiesene Anmeldungen",
    "OpaaTargetValidationTableFull": "Zielprüfung lehnt neue Ziele ab",
    "OpaaSiemExportLagging": "Ausleitung an das Sicherheitsmonitoring im Rückstand",
    "OpaaInstallationPending": "Installation nicht abgeschlossen",
}


def alert(name, severity, labels=None, value=None):
    """Expected alert: picks the rule of that name and severity (and labels like queue)."""
    labels = dict(labels or {})
    candidates = [r for r in by_name[name] if r["labels"]["severity"] == severity
                  and all(labels.get(k) == v for k, v in r["labels"].items()
                          if k not in ("severity", "component", "namespace"))]
    if len(candidates) > 1 and "component" in labels:
        candidates = [r for r in candidates if r["labels"]["component"] == labels["component"]]
    assert len(candidates) == 1, (name, severity, labels, len(candidates))
    r = candidates[0]
    all_labels = dict(r["labels"])
    all_labels.update(labels)
    ann = {k: fill(v, all_labels, value) for k, v in r["annotations"].items()}
    summary = SUMMARY[name]
    if isinstance(summary, dict):
        summary = summary[severity]
    ann["summary"] = summary.format(**all_labels)
    return all_labels, ann


CASES = []


def case(comment, series, checks, interval="1m"):
    CASES.append((comment, interval, series, checks))


# ---------------------------------------------------------------- platform
case("Idle for two hours, then a run that processes its first document after 20 minutes: no alert,\n"
     "because the run was not active through the whole window.",
     [ser("executor_active_threads", 'pod="r-opaa-backend-a",name="indexingTaskExecutor"', "0x119 1x60"),
      ser("opaa_indexing_documents_total", 'pod="r-opaa-backend-a",result="processed"', "5x139 6x40")],
     [("136m", "OpaaIndexingStalled", [])])
case("A run active from the pod start on without any document: no alert before the series is as old\n"
     "as the window, then after the window plus the 5 minutes of \"for\".",
     [ser("executor_active_threads", 'pod="r-opaa-backend-a",name="indexingTaskExecutor"', "1x90"),
      ser("opaa_indexing_documents_total", 'pod="r-opaa-backend-a",result="processed"', "5x90")],
     [("62m", "OpaaIndexingStalled", []),
      ("70m", "OpaaIndexingStalled", [alert("OpaaIndexingStalled", "warning", {"pod": "r-opaa-backend-a"})])])
case("A fresh pod registers the documents series of every connector at 0 on start: its first run,\n"
     "which never processes a document, still alerts instead of leaving the expression empty.",
     [ser("executor_active_threads", 'pod="r-opaa-backend-a",name="indexingTaskExecutor"', "1x90"),
      ser("opaa_indexing_documents_total", 'pod="r-opaa-backend-a",connector="confluence",result="processed"', "0x90"),
      ser("opaa_indexing_documents_total", 'pod="r-opaa-backend-a",connector="upload",result="failed"', "0x90")],
     [("70m", "OpaaIndexingStalled", [alert("OpaaIndexingStalled", "warning", {"pod": "r-opaa-backend-a"})])])
case("Two pods: a stalls while b keeps processing its own run. The pod of the stalled run alerts; the\n"
     "sum over both pods would hide it.",
     [ser("executor_active_threads", 'pod="r-opaa-backend-a",name="indexingTaskExecutor"', "1x90"),
      ser("opaa_indexing_documents_total", 'pod="r-opaa-backend-a",result="processed"', "5x90"),
      ser("executor_active_threads", 'pod="r-opaa-backend-b",name="indexingTaskExecutor"', "1x90"),
      ser("opaa_indexing_documents_total", 'pod="r-opaa-backend-b",result="processed"', "0+1x90")],
     [("70m", "OpaaIndexingStalled", [alert("OpaaIndexingStalled", "warning", {"pod": "r-opaa-backend-a"})])])
case("Stopped on purpose (replicas 0): neither readiness nor missing metrics alert.",
     [kube("kube_deployment_spec_replicas", DEP, "0x30"),
      kube("kube_deployment_status_replicas_available", DEP, "0x30")],
     [("25m", "OpaaBackendMetricsMissing", []), ("25m", "OpaaBackendNotReady", [])])
case("One replica wanted, none ready and no scrape: the critical readiness alert and the missing\n"
     "metrics fire, the partial one does not.",
     [kube("kube_deployment_spec_replicas", DEP, "1x30"),
      kube("kube_deployment_status_replicas_available", DEP, "0x30")],
     [("25m", "OpaaBackendMetricsMissing", [alert("OpaaBackendMetricsMissing", "warning")]),
      ("25m", "OpaaBackendNotReady", [alert("OpaaBackendNotReady", "critical", {"namespace": "opaa", "deployment": DEP})])])
case("Two of three pods ready: a ticket after 15 minutes, no page.",
     [kube("kube_deployment_spec_replicas", DEP, "3x30"),
      kube("kube_deployment_status_replicas_available", DEP, "2x30")],
     [("12m", "OpaaBackendNotReady", []),
      ("20m", "OpaaBackendNotReady", [alert("OpaaBackendNotReady", "warning", {"namespace": "opaa", "deployment": DEP})])])
case("Two pods wanted, one of them without scrape: the missing pod alerts although the other answers.",
     [kube("kube_deployment_spec_replicas", DEP, "2x30"),
      ser("up", 'pod="r-opaa-backend-a"', "1x30"), ser("up", 'pod="r-opaa-backend-b"', "0x30")],
     [("25m", "OpaaBackendMetricsMissing", [alert("OpaaBackendMetricsMissing", "warning")])])
case("Two pods wanted and both scraped: no alert.",
     [kube("kube_deployment_spec_replicas", DEP, "2x30"),
      ser("up", 'pod="r-opaa-backend-a"', "1x30"), ser("up", 'pod="r-opaa-backend-b"', "1x30")],
     [("25m", "OpaaBackendMetricsMissing", [])])
case("Without kube-state-metrics, no pod scraped at all still alerts.",
     [ser("up", 'pod="r-opaa-backend-a"', "0x30")],
     [("25m", "OpaaBackendMetricsMissing", [alert("OpaaBackendMetricsMissing", "warning")])])
case("Frontend: none of one pod ready pages after five minutes; one of two ready is a ticket.",
     [kube("kube_deployment_spec_replicas", "r-opaa-frontend", "1x20"),
      kube("kube_deployment_status_replicas_available", "r-opaa-frontend", "0x20")],
     [("3m", "OpaaFrontendNotReady", []),
      ("8m", "OpaaFrontendNotReady", [alert("OpaaFrontendNotReady", "critical", {"namespace": "opaa", "deployment": "r-opaa-frontend"})])])
case("Frontend with one of two pods ready: a ticket.",
     [kube("kube_deployment_spec_replicas", "r-opaa-frontend", "2x20"),
      kube("kube_deployment_status_replicas_available", "r-opaa-frontend", "1x20")],
     [("8m", "OpaaFrontendNotReady", [alert("OpaaFrontendNotReady", "warning", {"namespace": "opaa", "deployment": "r-opaa-frontend"})])])
case("Frontend stopped on purpose (replicas 0): no alert.",
     [kube("kube_deployment_spec_replicas", "r-opaa-frontend", "0x20"),
      kube("kube_deployment_status_replicas_available", "r-opaa-frontend", "0x20")],
     [("15m", "OpaaFrontendNotReady", [])])
case("Two pods, live data after garbage collection near the maximum on a, low on b: a alerts.",
     [ser("jvm_gc_live_data_size_bytes", 'pod="r-opaa-backend-a"', "90x30"),
      ser("jvm_gc_max_data_size_bytes", 'pod="r-opaa-backend-a"', "100x30"),
      ser("jvm_gc_live_data_size_bytes", 'pod="r-opaa-backend-b"', "20x30"),
      ser("jvm_gc_max_data_size_bytes", 'pod="r-opaa-backend-b"', "100x30")],
     [("10m", "OpaaHeapUsageHigh", []),
      ("20m", "OpaaHeapUsageHigh", [alert("OpaaHeapUsageHigh", "warning", {"pod": "r-opaa-backend-a"})])])
case("A connection timeout in the pool alerts at once; without one nothing.",
     [ser("hikaricp_connections_timeout_total", 'pod="r-opaa-backend-a",pool="HikariPool-1"', "0x10 1x20"),
      ser("hikaricp_connections_timeout_total", 'pod="r-opaa-backend-b",pool="HikariPool-1"', "0x30")],
     [("5m", "OpaaDatabasePoolTimeouts", []),
      ("15m", "OpaaDatabasePoolTimeouts", [alert("OpaaDatabasePoolTimeouts", "warning", {"pod": "r-opaa-backend-a"})])])
case("A heartbeat older than three intervals for five minutes alerts for that pod only.",
     [ser("opaa_instance_heartbeat_age_seconds", 'pod="r-opaa-backend-a"', "120x20"),
      ser("opaa_instance_heartbeat_age_seconds", 'pod="r-opaa-backend-b"', "10x20")],
     [("3m", "OpaaInstanceHeartbeatStale", []),
      ("10m", "OpaaInstanceHeartbeatStale", [alert("OpaaInstanceHeartbeatStale", "warning", {"pod": "r-opaa-backend-a"})])])
case("A pod off the invalidation bus for ten minutes alerts; a short drop does not.",
     [ser("opaa_invalidation_bus_connected", 'pod="r-opaa-backend-a"', "0x20"),
      ser("opaa_invalidation_bus_connected", 'pod="r-opaa-backend-b"', "1x5 0x3 1x12")],
     [("15m", "OpaaInvalidationBusDisconnected", [alert("OpaaInvalidationBusDisconnected", "warning", {"pod": "r-opaa-backend-a"})])])
case("Two versions for 40 minutes, reported alike by both pods: one alert; after 20 minutes none yet.",
     [ser("opaa_instances_versions", 'pod="r-opaa-backend-a"', "2x45"),
      ser("opaa_instances_versions", 'pod="r-opaa-backend-b"', "2x45")],
     [("20m", "OpaaVersionMix", []), ("40m", "OpaaVersionMix", [alert("OpaaVersionMix", "warning")])])
case("A queue gauge NaN on every pod alerts after 30 minutes with the metric named; NaN on one pod\n"
     "only, the OCR backlog (NaN while text recognition is off) and private inventory do not.",
     [ser("opaa_queue_depth", 'pod="r-opaa-backend-a",queue="mail_outbox"', "NaN+0x40"),
      ser("opaa_queue_depth", 'pod="r-opaa-backend-b",queue="mail_outbox"', "NaN+0x40"),
      ser("opaa_queue_depth", 'pod="r-opaa-backend-a",queue="source_events"', "NaN+0x40"),
      ser("opaa_queue_depth", 'pod="r-opaa-backend-b",queue="source_events"', "3x40"),
      ser("opaa_queue_depth", 'pod="r-opaa-backend-a",queue="ocr_backlog"', "NaN+0x40"),
      ser("opaa_inventory_documents", 'pod="r-opaa-backend-a",kind="private"', "NaN+0x40")],
     [("20m", "OpaaMetricUnreadable", []),
      ("35m", "OpaaMetricUnreadable", [alert("OpaaMetricUnreadable", "warning", {"metric": "opaa_queue_depth", "queue": "mail_outbox"})])])
OUT = "opaa_outbound_requests_seconds_count"
case("Most calls to the identity provider fail (refused, unreachable): a page; the directory alone\n"
     "does not page.",
     [ser(OUT, 'pod="r-opaa-backend-a",target="oidc",kind="none",outcome="auth_error",path_class="interactive"', "0+1x30"),
      ser(OUT, 'pod="r-opaa-backend-a",target="oidc",kind="none",outcome="unreachable",path_class="interactive"', "0+1x30"),
      ser(OUT, 'pod="r-opaa-backend-a",target="oidc",kind="none",outcome="success",path_class="interactive"', "0+1x30"),
      ser(OUT, 'pod="r-opaa-backend-a",target="directory",kind="keycloak",outcome="auth_error",path_class="background"', "0+0.5x30")],
     [("20m", "OpaaIdentityProviderRejected", [alert("OpaaIdentityProviderRejected", "critical")])])
case("Client errors of single requests (a wrong or refused request of one person) say nothing about the\n"
     "provider: no alert, however many.",
     [ser(OUT, 'pod="r-opaa-backend-a",target="oidc",kind="none",outcome="client_error",path_class="interactive"', "0+5x30"),
      ser(OUT, 'pod="r-opaa-backend-a",target="oidc",kind="none",outcome="success",path_class="interactive"', "0+1x30")],
     [("20m", "OpaaIdentityProviderRejected", [])])
case("Three failed calls among few: below the minimum volume, no alert.",
     [ser(OUT, 'pod="r-opaa-backend-a",target="oidc",kind="none",outcome="unreachable",path_class="interactive"', "0x5 1 2 3x25")],
     [("20m", "OpaaIdentityProviderRejected", [])])
case("Sign-ins whose token cannot be checked because the signing keys are out of reach: a page, even\n"
     "without any outbound call counted.",
     [ser("opaa_auth_logins_total", 'pod="r-opaa-backend-a",method="oidc",result="idp_error"', "0+1x30")],
     [("20m", "OpaaIdentityProviderRejected", [alert("OpaaIdentityProviderRejected", "critical")])])
case("The directory service refuses the access of the synchronisation: a ticket for the directory.",
     [ser(OUT, 'pod="r-opaa-backend-a",target="directory",kind="keycloak",outcome="auth_error",path_class="background"', "0+1x30")],
     [("20m", "OpaaOutboundAuthFailing", [alert("OpaaOutboundAuthFailing", "warning",
                                                {"component": "directory", "target": "directory", "kind": "keycloak"})])])

# ---------------------------------------------------------------- chat
case("Two failures among few requests do not alert.",
     [ser("http_server_requests_seconds_count", 'status="500"', "0+0.2x40"),
      ser("http_server_requests_seconds_count", 'status="200"', "0+0.4x40")],
     [("30m", "OpaaHighErrorRate", [])])
case("10 % failures of the API among many scrapes of the management port: the scrapes stay out of the\n"
     "share, still a ticket.",
     [ser("http_server_requests_seconds_count", 'uri="/api/v1/query",status="500"', "0+1x40"),
      ser("http_server_requests_seconds_count", 'uri="/api/v1/query",status="200"', "0+9x40"),
      ser("http_server_requests_seconds_count", 'uri="/actuator/prometheus",status="200"', "0+200x40")],
     [("30m", "OpaaHighErrorRate", [alert("OpaaHighErrorRate", "warning")])])
case("A steady 10 % failure share is a ticket, not a page.",
     [ser("http_server_requests_seconds_count", 'status="500"', "0+1x40"),
      ser("http_server_requests_seconds_count", 'status="200"', "0+9x40")],
     [("30m", "OpaaHighErrorRate", [alert("OpaaHighErrorRate", "warning")])])
case("30 % failures at enough volume: ticket and page.",
     [ser("http_server_requests_seconds_count", 'status="500"', "0+3x40"),
      ser("http_server_requests_seconds_count", 'status="200"', "0+7x40")],
     [("30m", "OpaaHighErrorRate", [alert("OpaaHighErrorRate", "warning"), alert("OpaaHighErrorRate", "critical")])])
case("10 % of the questions fail at two questions a minute: every burn window fires.",
     [ser("opaa_query_count_total", 'pod="r-opaa-backend-a",mode="rag",status="error"', "0+0.2x100"),
      ser("opaa_query_count_total", 'pod="r-opaa-backend-a",mode="rag",status="success"', "0+1.8x100")],
     [("70m", "OpaaChatSloBurn", [alert("OpaaChatSloBurn", "critical"), alert("OpaaChatSloBurn", "warning")]),
      ("90m", "OpaaChatSloBurn", [alert("OpaaChatSloBurn", "critical"), alert("OpaaChatSloBurn", "warning"),
                                  alert("OpaaChatSloBurn", "info")])])
case("1 % of the questions fail: only the slow burn of three days.",
     [ser("opaa_query_count_total", 'pod="r-opaa-backend-a",mode="rag",status="error"', "0+0.02x100"),
      ser("opaa_query_count_total", 'pod="r-opaa-backend-a",mode="rag",status="success"', "0+1.98x100")],
     [("90m", "OpaaChatSloBurn", [alert("OpaaChatSloBurn", "info")])])
case("Half of ten questions an hour fail, and many rejected questions besides: below the volume, and\n"
     "rejected questions do not count, so no alert.",
     [ser("opaa_query_count_total", 'pod="r-opaa-backend-a",mode="rag",status="error"', "0+0.08x80"),
      ser("opaa_query_count_total", 'pod="r-opaa-backend-a",mode="rag",status="success"', "0+0.08x80"),
      ser("opaa_query_count_total", 'pod="r-opaa-backend-a",mode="rag",status="rejected"', "0+5x80")],
     [("70m", "OpaaChatSloBurn", [])])
case("The 95th percentile of the first word above ten seconds at two questions a minute: info.",
     [ser("opaa_query_time_to_first_token_seconds_bucket", 'pod="r-opaa-backend-a",mode="rag",le="5.0"', "0+0.5x40"),
      ser("opaa_query_time_to_first_token_seconds_bucket", 'pod="r-opaa-backend-a",mode="rag",le="10.0"', "0+0.6x40"),
      ser("opaa_query_time_to_first_token_seconds_bucket", 'pod="r-opaa-backend-a",mode="rag",le="30.0"', "0+2x40"),
      ser("opaa_query_time_to_first_token_seconds_bucket", 'pod="r-opaa-backend-a",mode="rag",le="+Inf"', "0+2x40"),
      ser("opaa_query_time_to_first_token_seconds_count", 'pod="r-opaa-backend-a",mode="rag"', "0+2x40")],
     [("30m", "OpaaTimeToFirstTokenHigh", [alert("OpaaTimeToFirstTokenHigh", "info")])])
case("The same distribution at one question every two minutes: below the volume.",
     [ser("opaa_query_time_to_first_token_seconds_bucket", 'pod="r-opaa-backend-a",mode="rag",le="10.0"', "0+0.1x40"),
      ser("opaa_query_time_to_first_token_seconds_bucket", 'pod="r-opaa-backend-a",mode="rag",le="+Inf"', "0+0.5x40"),
      ser("opaa_query_time_to_first_token_seconds_count", 'pod="r-opaa-backend-a",mode="rag"', "0+0.5x40")],
     [("30m", "OpaaTimeToFirstTokenHigh", [])])
case("90 % of the searches find nothing at two a minute: a page.",
     [ser("opaa_retrieval_empty_total", 'pod="r-opaa-backend-a"', "0+1.8x40"),
      ser("opaa_retrieval_hits_count", 'pod="r-opaa-backend-a"', "0+2x40")],
     [("30m", "OpaaRetrievalMostlyEmpty", [alert("OpaaRetrievalMostlyEmpty", "critical")])])
case("All searches empty, but one every two minutes, or half of many searches empty: no alert.",
     [ser("opaa_retrieval_empty_total", 'pod="r-opaa-backend-a"', "0+0.5x40"),
      ser("opaa_retrieval_hits_count", 'pod="r-opaa-backend-a"', "0+0.5x40")],
     [("30m", "OpaaRetrievalMostlyEmpty", [])])
case("Half of many searches empty: no alert.",
     [ser("opaa_retrieval_empty_total", 'pod="r-opaa-backend-a"', "0+1x40"),
      ser("opaa_retrieval_hits_count", 'pod="r-opaa-backend-a"', "0+2x40")],
     [("30m", "OpaaRetrievalMostlyEmpty", [])])

# ---------------------------------------------------------------- model
case("Chat role down on both pods while questions come in: a page after ten minutes.",
     [ser("opaa_model_role_available", 'pod="r-opaa-backend-a",role="chat"', "0x30"),
      ser("opaa_model_role_available", 'pod="r-opaa-backend-b",role="chat"', "0x30"),
      ser("opaa_model_role_available", 'pod="r-opaa-backend-a",role="embedding"', "1x30"),
      ser("opaa_query_count_total", 'pod="r-opaa-backend-a",mode="rag",status="error"', "0+1x30")],
     [("5m", "OpaaModelRoleDown", []),
      ("15m", "OpaaModelRoleDown", [alert("OpaaModelRoleDown", "critical", {"role": "chat"})])])
case("Chat role down without any question (the value stands still), or down on one pod only: none.",
     [ser("opaa_model_role_available", 'pod="r-opaa-backend-a",role="chat"', "0x30"),
      ser("opaa_model_role_available", 'pod="r-opaa-backend-a",role="embedding"', "0x30"),
      ser("opaa_model_role_available", 'pod="r-opaa-backend-b",role="embedding"', "1x30"),
      ser("opaa_query_count_total", 'pod="r-opaa-backend-a",mode="rag",status="success"', "5x30")],
     [("25m", "OpaaModelRoleDown", [])])
case("Rerank down with calls to it: a ticket after 30 minutes; text recognition down without calls\n"
     "stays silent.",
     [ser("opaa_model_role_available", 'pod="r-opaa-backend-a",role="rerank"', "0x50"),
      ser("opaa_model_role_available", 'pod="r-opaa-backend-a",role="ocr"', "0x50"),
      ser("opaa_outbound_requests_seconds_count", 'pod="r-opaa-backend-a",target="rerank",kind="none",outcome="io_error",path_class="interactive"', "0+1x50"),
      ser("opaa_outbound_requests_seconds_count", 'pod="r-opaa-backend-a",target="ocr",kind="none",outcome="io_error",path_class="background"', "4x50")],
     [("20m", "OpaaModelRoleDown", []),
      ("40m", "OpaaModelRoleDown", [alert("OpaaModelRoleDown", "warning", {"role": "rerank"})])])
case("A SharePoint connector refused for authentication most of the time: a ticket routed to\n"
     "indexing with target and kind; rerank fails on TLS: a ticket routed to the model operators.",
     [ser("opaa_outbound_requests_seconds_count", 'pod="r-opaa-backend-a",target="connector",kind="sharepoint",outcome="auth_error",path_class="background"', "0+1x30"),
      ser("opaa_outbound_requests_seconds_count", 'pod="r-opaa-backend-a",target="connector",kind="sharepoint",outcome="success",path_class="background"', "0+0.5x30"),
      ser("opaa_outbound_requests_seconds_count", 'pod="r-opaa-backend-a",target="rerank",kind="none",outcome="tls_error",path_class="interactive"', "0+1x30")],
     [("20m", "OpaaOutboundAuthFailing", [
         alert("OpaaOutboundAuthFailing", "warning", {"component": "indexing", "target": "connector", "kind": "sharepoint"}),
         alert("OpaaOutboundAuthFailing", "warning", {"component": "model", "target": "rerank", "kind": "none"})])])
case("Few refused calls (below the minimum of five in ten minutes): no alert.",
     [ser("opaa_outbound_requests_seconds_count", 'pod="r-opaa-backend-a",target="connector",kind="smb",outcome="auth_error",path_class="background"', "0+0.2x30")],
     [("20m", "OpaaOutboundAuthFailing", [])])
case("Half of the calls a person waits for are throttled: a ticket; throttling in the background\n"
     "is normal and stays silent.",
     [ser("opaa_outbound_requests_seconds_count", 'pod="r-opaa-backend-a",target="rerank",kind="none",outcome="throttled",path_class="interactive"', "0+1x40"),
      ser("opaa_outbound_requests_seconds_count", 'pod="r-opaa-backend-a",target="rerank",kind="none",outcome="success",path_class="interactive"', "0+1x40"),
      ser("opaa_outbound_requests_seconds_count", 'pod="r-opaa-backend-a",target="ocr",kind="none",outcome="throttled",path_class="background"', "0+5x40")],
     [("35m", "OpaaOutboundThrottled", [alert("OpaaOutboundThrottled", "warning", {"target": "rerank"})])])
case("Tokens at a pace that exceeds the monthly budget of 100000: a ticket after the hour of \"for\".",
     [ser("opaa_model_tokens_total", 'pod="r-opaa-backend-a",purpose="answer",token_type="input",model="m"', "0+1000x120")],
     [("30m", "OpaaTokenBudgetForecast", []),
      ("110m", "OpaaTokenBudgetForecast", [alert("OpaaTokenBudgetForecast", "warning")])])

# ---------------------------------------------------------------- indexing
case("Two pods report the same overdue SharePoint library: one alert per type after an hour.",
     [ser("opaa_indexing_libraries_overdue", 'pod="r-opaa-backend-a",connector="sharepoint"', "1x80"),
      ser("opaa_indexing_libraries_overdue", 'pod="r-opaa-backend-b",connector="sharepoint"', "1x80"),
      ser("opaa_indexing_libraries_overdue", 'pod="r-opaa-backend-a",connector="confluence"', "0x80")],
     [("30m", "OpaaLibrariesOverdue", []),
      ("70m", "OpaaLibrariesOverdue", [alert("OpaaLibrariesOverdue", "warning", {"connector": "sharepoint"}, value=1)])])
case("Three of six runs of a type failed in six hours: a ticket; one of two failed: below the minimum.",
     [ser("opaa_indexing_runs_total", 'pod="r-opaa-backend-a",connector="sharepoint",mode="delta",result="failed",failure_category="credentials_rejected"', "0x5 1x10 2x10 3x20"),
      ser("opaa_indexing_runs_total", 'pod="r-opaa-backend-a",connector="sharepoint",mode="delta",result="completed",failure_category="none"', "0x5 1x10 2x10 3x20"),
      ser("opaa_indexing_runs_total", 'pod="r-opaa-backend-a",connector="confluence",mode="delta",result="failed",failure_category="not_connected"', "0x5 1x40"),
      ser("opaa_indexing_runs_total", 'pod="r-opaa-backend-a",connector="confluence",mode="delta",result="completed",failure_category="none"', "0x5 1x40")],
     [("8h", "OpaaIndexingRunsFailing", [alert("OpaaIndexingRunsFailing", "warning", {"connector": "sharepoint"})])],
     interval="10m")

# ---------------------------------------------------------------- directory
case("No applied synchronisation for 27 hours: a ticket; the provider \"none\" never alerts.",
     [ser("opaa_directory_sync_last_success_seconds", 'pod="r-opaa-backend-a",provider="keycloak"', "0x420"),
      ser("opaa_directory_sync_last_success_seconds", 'pod="r-opaa-backend-a",provider="none"', "0x420"),
      ser("opaa_directory_sync_plan_pending_age_seconds", 'pod="r-opaa-backend-a",provider="keycloak"', "0x420")],
     [("25h", "OpaaDirectorySyncStale", []),
      ("27h", "OpaaDirectorySyncStale", [alert("OpaaDirectorySyncStale", "warning", {"provider": "keycloak"})])],
     interval="4m")
case("A plan awaits confirmation for 25 hours: its own alert, the stale synchronisation stays silent.",
     [ser("opaa_directory_sync_last_success_seconds", 'pod="r-opaa-backend-a",provider="keycloak"', "0x420"),
      ser("opaa_directory_sync_plan_pending_age_seconds", 'pod="r-opaa-backend-a",provider="keycloak"', "0+240x420")],
     [("27h", "OpaaDirectorySyncStale", []),
      ("27h", "OpaaDirectorySyncPlanPending", [alert("OpaaDirectorySyncPlanPending", "warning", {"provider": "keycloak"})])],
     interval="4m")

# ---------------------------------------------------------------- background
case("A five-minute job that never succeeds after the pod start (last success = start): overdue after\n"
     "three intervals and 15 minutes plus the ten of \"for\"; the audit runs have their own alerts.",
     [ser("opaa_scheduled_last_success", 'pod="r-opaa-backend-a",scheduled_job="mail-outbox"', "0x40"),
      ser("opaa_scheduled_expected_interval_seconds", 'pod="r-opaa-backend-a",scheduled_job="mail-outbox"', "300x40"),
      ser("opaa_scheduled_last_success", 'pod="r-opaa-backend-a",scheduled_job="audit-chain"', "0x40"),
      ser("opaa_scheduled_expected_interval_seconds", 'pod="r-opaa-backend-a",scheduled_job="audit-chain"', "60x40")],
     [("20m", "OpaaScheduledJobOverdue", []),
      ("30m", "OpaaScheduledJobOverdue", [alert("OpaaScheduledJobOverdue", "warning", {"scheduled_job": "mail-outbox"})])])
case("The job runs under its lock on pod b only: the latest success over the pods counts, no alert.",
     [ser("opaa_scheduled_last_success", 'pod="r-opaa-backend-a",scheduled_job="mail-outbox"', "0x40"),
      ser("opaa_scheduled_expected_interval_seconds", 'pod="r-opaa-backend-a",scheduled_job="mail-outbox"', "300x40"),
      ser("opaa_scheduled_last_success", 'pod="r-opaa-backend-b",scheduled_job="mail-outbox"', "0+60x40"),
      ser("opaa_scheduled_expected_interval_seconds", 'pod="r-opaa-backend-b",scheduled_job="mail-outbox"', "300x40")],
     [("35m", "OpaaScheduledJobOverdue", [])])
case("Each queue against its own threshold: a mail older than 30 minutes and an indexing order older\n"
     "than two hours alert, a scan waiting 40 minutes for text recognition does not.",
     [ser("opaa_queue_oldest_age_seconds", 'pod="r-opaa-backend-a",queue="mail_outbox"', "2400x20"),
      ser("opaa_queue_oldest_age_seconds", 'pod="r-opaa-backend-b",queue="mail_outbox"', "2400x20"),
      ser("opaa_queue_oldest_age_seconds", 'pod="r-opaa-backend-a",queue="indexing"', "9000x20"),
      ser("opaa_queue_oldest_age_seconds", 'pod="r-opaa-backend-a",queue="ocr_backlog"', "2400x20"),
      ser("opaa_queue_oldest_age_seconds", 'pod="r-opaa-backend-a",queue="source_events"', "600x20")],
     [("10m", "OpaaQueueBacklog", [alert("OpaaQueueBacklog", "warning", {"queue": "indexing"}),
                                   alert("OpaaQueueBacklog", "warning", {"queue": "mail_outbox"})])])
case("Most mails finally discarded at enough attempts: a ticket; one invalid address does not alert.",
     [ser("opaa_mail_sent_total", 'pod="r-opaa-backend-a",result="failed"', "0+1x40"),
      ser("opaa_mail_sent_total", 'pod="r-opaa-backend-a",result="sent"', "0+0.5x40")],
     [("35m", "OpaaMailDeliveryFailing", [alert("OpaaMailDeliveryFailing", "warning")])])
case("A single discarded mail among few: no alert.",
     [ser("opaa_mail_sent_total", 'pod="r-opaa-backend-a",result="failed"', "0x20 1x20"),
      ser("opaa_mail_sent_total", 'pod="r-opaa-backend-a",result="sent"', "0+0.05x40")],
     [("35m", "OpaaMailDeliveryFailing", [])])

# ---------------------------------------------------------------- audit (unchanged cases)
RET = "opaa_audit_retention_last_run_age_seconds"
case("Two pods report the same overdue retention run: one alert without a pod label, after the hour of\n\"for\".",
     [ser(RET, 'pod="r-opaa-backend-a"', "3024001x90"), ser(RET, 'pod="r-opaa-backend-b"', "3024001x90")],
     [("30m", "OpaaAuditRetentionOverdue", []), ("61m", "OpaaAuditRetentionOverdue", [alert("OpaaAuditRetentionOverdue", "warning")])])
case("Neither pod can read the state (NaN): one alert after the hour of \"for\", as for a missed run.",
     [ser(RET, 'pod="r-opaa-backend-a"', "NaN+0x90"), ser(RET, 'pod="r-opaa-backend-b"', "NaN+0x90")],
     [("30m", "OpaaAuditRetentionOverdue", []), ("61m", "OpaaAuditRetentionOverdue", [alert("OpaaAuditRetentionOverdue", "warning")])])
case("One pod cannot read the state, the other reports a recent run: no alert.",
     [ser(RET, 'pod="r-opaa-backend-a"', "NaN+0x90"), ser(RET, 'pod="r-opaa-backend-b"', "864000x90")],
     [("80m", "OpaaAuditRetentionOverdue", [])])
case("The backend runs but reports no series: one alert after the hour of \"for\".",
     [kube("kube_deployment_spec_replicas", DEP, "1x90")],
     [("61m", "OpaaAuditRetentionOverdue", [alert("OpaaAuditRetentionOverdue", "warning")])])
case("The backend is stopped on purpose (replicas 0): no series and no alert.",
     [kube("kube_deployment_spec_replicas", DEP, "0x90")],
     [("80m", "OpaaAuditRetentionOverdue", [])])
case("A run in the current month: no alert.",
     [ser(RET, 'pod="r-opaa-backend-a"', "864000x90")],
     [("80m", "OpaaAuditRetentionOverdue", [])])
LAG = "opaa_audit_chain_lag_seconds"
case("The oldest unchained entry is seven hours old on every pod: the chain stalled.",
     [ser(LAG, 'pod="r-opaa-backend-a"', "25200x90"), ser(LAG, 'pod="r-opaa-backend-b"', "25200x90")],
     [("30m", "OpaaAuditChainStalled", []), ("61m", "OpaaAuditChainStalled", [alert("OpaaAuditChainStalled", "warning")])])
case("Neither pod can read the chain (NaN): one alert after the hour of \"for\".",
     [ser(LAG, 'pod="r-opaa-backend-a"', "NaN+0x90"), ser(LAG, 'pod="r-opaa-backend-b"', "NaN+0x90")],
     [("61m", "OpaaAuditChainStalled", [alert("OpaaAuditChainStalled", "warning")])])
case("One pod cannot read the chain, the other reports a fresh one: no alert.",
     [ser(LAG, 'pod="r-opaa-backend-a"', "NaN+0x90"), ser(LAG, 'pod="r-opaa-backend-b"', "540x90")],
     [("80m", "OpaaAuditChainStalled", [])])
case("The backend runs but reports no chain series: one alert after the hour of \"for\".",
     [kube("kube_deployment_spec_replicas", DEP, "1x90")],
     [("61m", "OpaaAuditChainStalled", [alert("OpaaAuditChainStalled", "warning")])])
case("The backend is stopped on purpose (replicas 0): no chain series and no alert.",
     [kube("kube_deployment_spec_replicas", DEP, "0x90")],
     [("80m", "OpaaAuditChainStalled", [])])
case("A lag of a few minutes between two runs: no alert.",
     [ser(LAG, 'pod="r-opaa-backend-a"', "540x90")],
     [("80m", "OpaaAuditChainStalled", [])])
DROP = "opaa_audit_payload_dropped_total"
case("The counter starts at 0 with the pod; the first dropped key alerts for an hour, then silence\n"
     "without a further drop.",
     [ser(DROP, 'pod="r-opaa-backend-a",event_type="SPACE_CHANGED"', "0x29 1x120")],
     [("20m", "OpaaAuditPayloadDropped", []),
      ("35m", "OpaaAuditPayloadDropped", [alert("OpaaAuditPayloadDropped", "warning", {"event_type": "SPACE_CHANGED"})]),
      ("100m", "OpaaAuditPayloadDropped", [])])
case("A further drop on an older counter: an alert per event type, summed over the pods.",
     [ser(DROP, 'pod="r-opaa-backend-a",event_type="SPACE_CHANGED"', "1x89 2x60"),
      ser(DROP, 'pod="r-opaa-backend-b",event_type="SPACE_CHANGED"', "3x150")],
     [("85m", "OpaaAuditPayloadDropped", []),
      ("95m", "OpaaAuditPayloadDropped", [alert("OpaaAuditPayloadDropped", "warning", {"event_type": "SPACE_CHANGED"})])])
case("Nothing dropped, but a scrape gap of 20 minutes and a pod whose series begins only after 70\n"
     "minutes (pod start or Prometheus restart): no alert.",
     [ser(DROP, 'pod="r-opaa-backend-a",event_type="SPACE_CHANGED"', "0x39 _x19 0x60"),
      ser(DROP, 'pod="r-opaa-backend-b",event_type="SPACE_CHANGED"', "_x69 0x50")],
     [("65m", "OpaaAuditPayloadDropped", []), ("80m", "OpaaAuditPayloadDropped", [])])
UNS = "opaa_audit_chain_unsealed_months"
case("One ended month without a seal for seven hours: the chain does not reach its end, or a seal\n"
     "was removed; alert after the six hours of \"for\".",
     [ser(UNS, 'pod="r-opaa-backend-a"', "1x420"), ser(UNS, 'pod="r-opaa-backend-b"', "1x420")],
     [("5h", "OpaaAuditChainUnsealed", []), ("7h", "OpaaAuditChainUnsealed", [alert("OpaaAuditChainUnsealed", "warning")])])
case("The month changes and the run seals it twenty minutes later: no alert.",
     [ser(UNS, 'pod="r-opaa-backend-a"', "0x60 1x20 0x400")],
     [("8h", "OpaaAuditChainUnsealed", [])])
HOR = "opaa_audit_log_horizon_seconds"
case("Ten days left: a ticket, no page.",
     [ser(HOR, 'pod="r-opaa-backend-a"', "864000x90")],
     [("80m", "OpaaAuditLogHorizon", [alert("OpaaAuditLogHorizon", "warning")])])
case("One day left: ticket and page.",
     [ser(HOR, 'pod="r-opaa-backend-a"', "86400x90")],
     [("80m", "OpaaAuditLogHorizon", [alert("OpaaAuditLogHorizon", "warning"), alert("OpaaAuditLogHorizon", "critical")])])
case("A month ahead: no alert.",
     [ser(HOR, 'pod="r-opaa-backend-a"', "2592000x90")],
     [("80m", "OpaaAuditLogHorizon", [])])

# ---------------------------------------------------------------- security
MAL = "opaa_malware_scans_seconds_count"
case("The scanner fails on most uploads: a ticket; one infected file shows as info.",
     [ser(MAL, 'pod="r-opaa-backend-a",result="failed"', "0+1x30"),
      ser(MAL, 'pod="r-opaa-backend-a",result="clean"', "0+0.5x30"),
      ser(MAL, 'pod="r-opaa-backend-a",result="infected"', "0x10 1x20")],
     [("15m", "OpaaMalwareScanFailing", [alert("OpaaMalwareScanFailing", "warning")]),
      ("15m", "OpaaMalwareDetected", [alert("OpaaMalwareDetected", "info")])])
case("Two failed scans in 15 minutes: below the minimum of three.",
     [ser(MAL, 'pod="r-opaa-backend-a",result="failed"', "0x5 1x5 2x20")],
     [("20m", "OpaaMalwareScanFailing", [])])
case("Signatures four days old: a ticket after the hour of \"for\"; two days old: none.",
     [ser("opaa_malware_signatures_age_seconds", 'pod="r-opaa-backend-a"', "345600x80"),
      ser("opaa_malware_signatures_age_seconds", 'pod="r-opaa-backend-b"', "172800x80")],
     [("30m", "OpaaMalwareSignaturesOutdated", []),
      ("70m", "OpaaMalwareSignaturesOutdated", [alert("OpaaMalwareSignaturesOutdated", "warning")])])
case("A refusal at the global limit alerts; refusals of single clients do not.",
     [ser("opaa_rate_limit_rejected_total", 'pod="r-opaa-backend-a",limit="login",scope="global"', "0x10 1x20"),
      ser("opaa_rate_limit_rejected_total", 'pod="r-opaa-backend-a",limit="login",scope="client"', "0+5x30")],
     [("5m", "OpaaGlobalRateLimitHit", []),
      ("15m", "OpaaGlobalRateLimitHit", [alert("OpaaGlobalRateLimitHit", "warning", {"limit": "login"})])])
LOG = "opaa_auth_logins_total"
case("75 refused local sign-ins in 15 minutes without history: above the minimum, an alert.",
     [ser(LOG, 'pod="r-opaa-backend-a",method="local",result="bad_credentials"', "0+5x30")],
     [("20m", "OpaaLoginFailureSpike", [alert("OpaaLoginFailureSpike", "warning", value=75)])])
case("30 refused sign-ins in 15 minutes: below the minimum; refused OIDC tokens do not count.",
     [ser(LOG, 'pod="r-opaa-backend-a",method="local",result="bad_credentials"', "0+2x30"),
      ser(LOG, 'pod="r-opaa-backend-a",method="oidc",result="bad_credentials"', "0+20x30")],
     [("20m", "OpaaLoginFailureSpike", [])])
case("60 refusals every quarter hour for eight days: above the minimum, but the usual level at this\n"
     "time of the days before, no alert.",
     [ser(LOG, 'pod="r-opaa-backend-a",method="local",result="bad_credentials"', "0+20x2310")],
     [("8d", "OpaaLoginFailureSpike", [])],
     interval="5m")
case("Eight days at about twelve refusals a quarter hour, then 120 in one: ten times the median of the\n"
     "same quarter hour on the days before, an alert.",
     [ser(LOG, 'pod="r-opaa-backend-a",method="local",result="bad_credentials"', "0+4x2304 9256+40x6")],
     [("8d", "OpaaLoginFailureSpike", []),
      ("8d20m", "OpaaLoginFailureSpike", [alert("OpaaLoginFailureSpike", "warning", value=120)])],
     interval="5m")
case("A refused check of a new target because the table is full: a ticket.",
     [ser("opaa_target_validation_refused_checks_total", 'pod="r-opaa-backend-a"', "0x10 1x20")],
     [("15m", "OpaaTargetValidationTableFull", [alert("OpaaTargetValidationTableFull", "warning")])])
case("The oldest entry not yet exported is over an hour old for 30 minutes: a ticket.",
     [ser("opaa_siem_export_lag_seconds", 'pod="r-opaa-backend-a"', "4000x40")],
     [("20m", "OpaaSiemExportLagging", []),
      ("35m", "OpaaSiemExportLagging", [alert("OpaaSiemExportLagging", "warning")])])
case("An installation left open for an hour: a ticket; a finished one stays silent.",
     [ser("opaa_installation_pending", 'pod="r-opaa-backend-a"', "1x70")],
     [("30m", "OpaaInstallationPending", []),
      ("65m", "OpaaInstallationPending", [alert("OpaaInstallationPending", "warning")])])
case("A finished installation: no alert.",
     [ser("opaa_installation_pending", 'pod="r-opaa-backend-a"', "0x70")],
     [("65m", "OpaaInstallationPending", [])])


def q(v):
    return json.dumps(v, ensure_ascii=False)


lines = [
    "# Generated by deploy/helm/ci/gen_rule_tests.py; edit the cases there. Unit test of the alerts in",
    "# templates/prometheusrule.yaml (release \"r\", namespace \"opaa\"), run from",
    "# the repository root with deploy/helm/ci/rule-test.sh; that script renders the rules into",
    "# opaa-rules.yaml next to a copy of this file. Every alert has a case that fires and one that does",
    "# not, quotients also one below their minimum volume.",
    "rule_files:",
    "  - opaa-rules.yaml",
    "evaluation_interval: 1m",
    "tests:",
]
for comment, interval, series, checks in CASES:
    for c in comment.split("\n"):
        lines.append("  # " + c)
    lines.append(f"  - interval: {interval}")
    lines.append("    input_series:")
    for s, v in series:
        lines.append(f"      - series: '{s}'")
        lines.append(f"        values: '{v}'")
    lines.append("    alert_rule_test:")
    for t, name, exps in checks:
        lines.append(f"      - eval_time: {t}")
        lines.append(f"        alertname: {name}")
        if not exps:
            lines.append("        exp_alerts: []")
            continue
        lines.append("        exp_alerts:")
        for labels, ann in exps:
            first = True
            lines.append("          - exp_labels:")
            for k, v in labels.items():
                lines.append(f"              {k}: {q(v)}")
            lines.append("            exp_annotations:")
            for k, v in ann.items():
                lines.append(f"              {k}: {q(v)}")
open(out_path, "w").write("\n".join(lines) + "\n")
print(len(CASES), "cases")
