#!/usr/bin/env python3
"""Builds the Grafana dashboards of OPAA: the start page with one traffic light per building block,
the indexing drill-down and the four areas Betrieb, Wissensbestand, Modelle and Sicherheit.

The thresholds come from the defaults of metrics.prometheusRule in the chart's values.yaml, the same
values the PrometheusRule renders with, so a tile turns yellow or red where the alert fires. The
values are read as JSON (yq -o json '.metrics' values.yaml) from the file given with --values.

Rules of every panel: traffic lights only on opaa_* metrics, latencies as percentiles, nothing per
person, space, library or document. Gauges every pod reads from the database are combined with max,
counters of the pods with sum.

Usage: build_dashboards.py --values METRICS_JSON --out DIR [--check]
"""
import argparse
import difflib
import json
import os
import sys

DS = {"type": "prometheus", "uid": "${DS_PROMETHEUS}"}
SEL = 'namespace=~"$namespace", host=~"$host"'
KSEL = 'namespace=~"$namespace"'

# Light-scheme status colours of the design guidelines; white text reaches at least 4.5:1 on each.
OK, WARN, CRIT, GREY = "#11774F", "#8F5A00", "#CE2C31", "#5A6270"
NAVY, BLUE = "#012142", "#0B6FBC"
STATUS_MAP = [{"type": "value", "options": {
    "-1": {"text": "nicht eingerichtet", "color": GREY, "index": 0},
    "0": {"text": "in Ordnung", "color": OK, "index": 1},
    "1": {"text": "prüfen", "color": WARN, "index": 2},
    "2": {"text": "gestört", "color": CRIT, "index": 3}}}]
STATUS_THR = {"mode": "absolute", "steps": [{"color": GREY, "value": None}, {"color": OK, "value": 0},
                                            {"color": WARN, "value": 1}, {"color": CRIT, "value": 2}]}
LOW = [{"type": "value", "options": {"-1": {"text": "wenig Verkehr", "color": GREY, "index": 0}}}]
MIN, H, DAYS = "suffix: min", "suffix: h", "suffix: Tage"

UIDS = {"start": "opaa-start", "indexing": "opaa-indexing", "operations": "opaa-operations",
        "knowledge": "opaa-knowledge", "models": "opaa-models", "security": "opaa-security"}


def num(x):
    return f"{x:g}"


class Builder:
    def __init__(self, metrics):
        self.runbook = metrics["runbookBaseUrl"]
        self.r = metrics["prometheusRule"]
        self._id = 0

    def nid(self):
        self._id += 1
        return self._id

    # ------------------------------------------------------------ panels

    @staticmethod
    def gp(x, y, w, h):
        return {"x": x, "y": y, "w": w, "h": h}

    @staticmethod
    def targets(items, instant=False, fmt=None, interval=None):
        out = []
        for i, (expr, legend) in enumerate(items):
            t = {"refId": "ABCDEFGHIJKLMNOP"[i], "datasource": DS, "expr": expr, "legendFormat": legend or "__auto",
                 "range": not instant, "instant": instant, "editorMode": "code"}
            if fmt:
                t["format"] = fmt
            if interval:
                t["interval"] = interval
            out.append(t)
        return out

    @staticmethod
    def thr(*steps):
        out = [{"color": steps[0], "value": None}]
        out += [{"color": c, "value": v} for c, v in steps[1:]]
        return {"mode": "absolute", "steps": out}

    @staticmethod
    def ov(name, **props):
        """Override by field name; custom__width becomes custom.width."""
        return {"matcher": {"id": "byName", "options": name},
                "properties": [{"id": k.replace("__", "."), "value": v} for k, v in props.items()]}

    @staticmethod
    def fixed(color):
        return {"mode": "fixed", "fixedColor": color}

    def text(self, html, pos):
        return {"id": self.nid(), "type": "text", "title": "", "gridPos": pos, "transparent": True,
                "options": {"mode": "html", "content": html}}

    def banner(self, title, subtitle, pos):
        return self.text(
            f'<div style="background:{NAVY};color:#fff;padding:10px 18px;border-radius:6px;display:flex;'
            f'justify-content:space-between;align-items:center;gap:16px;height:100%;box-sizing:border-box">'
            f'<span style="font-size:20px;font-weight:600">{title}</span>'
            f'<span style="font-size:13px">{subtitle}</span></div>', pos)

    def status(self, title, desc, items, pos, links=None, text_mode="value_and_name", font=None):
        """Stat panel of status codes -1/0/1/2, shown as words on coloured tiles."""
        return {"id": self.nid(), "type": "stat", "title": title, "description": desc, "gridPos": pos,
                "datasource": DS, "targets": self.targets(items, instant=True),
                "fieldConfig": {"defaults": {"mappings": STATUS_MAP, "thresholds": STATUS_THR,
                                             "color": {"mode": "thresholds"}, "noValue": "keine Daten",
                                             "links": links or []}, "overrides": []},
                "options": {"reduceOptions": {"calcs": ["lastNotNull"], "fields": "", "values": False},
                            "colorMode": "background_solid", "graphMode": "none", "textMode": text_mode,
                            "justifyMode": "center", "orientation": "auto", "wideLayout": True,
                            "showPercentChange": False, "text": font or {}}}

    def stat(self, title, desc, items, pos, unit="short", thresholds=None, decimals=None, color_mode="background_solid",
             overrides=None, mappings=None, text_mode="auto", font=None):
        d = {"unit": unit, "thresholds": thresholds or self.thr("text"), "color": {"mode": "thresholds"},
             "noValue": "–", "mappings": mappings or []}
        if decimals is not None:
            d["decimals"] = decimals
        return {"id": self.nid(), "type": "stat", "title": title, "description": desc, "gridPos": pos,
                "datasource": DS, "targets": self.targets(items, instant=True),
                "fieldConfig": {"defaults": d, "overrides": overrides or []},
                "options": {"reduceOptions": {"calcs": ["lastNotNull"], "fields": "", "values": False},
                            "colorMode": color_mode, "graphMode": "none", "textMode": text_mode,
                            "justifyMode": "center", "orientation": "auto", "wideLayout": True,
                            "showPercentChange": False, "text": font or {}}}

    def ts(self, title, desc, items, pos, unit="short", stack=False, bars=False, thresholds=None, thr_style="off",
           overrides=None, decimals=None, interval=None, max_=None, fill=12, soft_max=None):
        custom = {"drawStyle": "bars" if bars else "line", "lineWidth": 2, "fillOpacity": 70 if bars else fill,
                  "gradientMode": "none", "showPoints": "never", "spanNulls": 300000, "lineInterpolation": "smooth",
                  "barAlignment": -1, "axisBorderShow": False, "axisPlacement": "auto", "axisSoftMin": 0,
                  "stacking": {"mode": "normal" if stack else "none", "group": "A"},
                  "thresholdsStyle": {"mode": thr_style}}
        if soft_max is not None:
            custom["axisSoftMax"] = soft_max
        d = {"unit": unit, "custom": custom, "color": {"mode": "palette-classic"},
             "thresholds": thresholds or self.thr("green")}
        if decimals is not None:
            d["decimals"] = decimals
        if max_ is not None:
            d["max"] = max_
        p = {"id": self.nid(), "type": "timeseries", "title": title, "description": desc, "gridPos": pos,
             "datasource": DS, "targets": self.targets(items, interval=interval),
             "fieldConfig": {"defaults": d, "overrides": overrides or []},
             "options": {"legend": {"displayMode": "list", "placement": "bottom", "showLegend": True},
                         "tooltip": {"mode": "multi", "sort": "desc"}}}
        if interval:
            p["interval"] = interval
        return p

    def bargauge(self, title, desc, items, pos, thresholds=None, max_=None, unit="short"):
        d = {"unit": unit, "thresholds": thresholds or self.thr(OK), "color": {"mode": "thresholds"},
             "decimals": 0, "min": 0}
        if max_ is not None:
            d["max"] = max_
        return {"id": self.nid(), "type": "bargauge", "title": title, "description": desc, "gridPos": pos,
                "datasource": DS, "targets": self.targets(items, instant=True),
                "fieldConfig": {"defaults": d, "overrides": []},
                "options": {"reduceOptions": {"calcs": ["lastNotNull"], "fields": "", "values": False},
                            "orientation": "horizontal", "displayMode": "basic", "valueMode": "color",
                            "namePlacement": "left", "showUnfilled": True, "sizing": "auto", "minVizHeight": 16,
                            "maxVizHeight": 28, "legend": {"showLegend": False}}}

    def table(self, title, desc, items, pos, transformations, overrides=None, sort=None, no_value="nichts"):
        return {"id": self.nid(), "type": "table", "title": title, "description": desc, "gridPos": pos,
                "datasource": DS, "targets": self.targets(items, instant=True, fmt="table"),
                "transformations": transformations,
                "fieldConfig": {"defaults": {"custom": {"align": "auto", "cellOptions": {"type": "auto"},
                                                        "inspect": False},
                                             "noValue": no_value, "thresholds": self.thr("text")},
                                "overrides": overrides or []},
                "options": {"showHeader": True, "cellHeight": "sm", "footer": {"show": False},
                            "sortBy": sort or []}}

    # ------------------------------------------------------------ PromQL building blocks

    @staticmethod
    def worst(*scores, base="vector(0)"):
        """Worst of several status scores: the highest score above 0, else the base (0, or -1 for
        too little traffic). A score without data is left out. label_replace keeps the parts apart,
        which "or" would otherwise merge."""
        parts = [f'label_replace(({s}) > 0, "k", "s{i}", "", "")' for i, s in enumerate(scores)]
        parts.append(f'label_replace(({base}), "k", "base", "", "")')
        return f'max({" or ".join(parts)})'

    @staticmethod
    def gt(expr, warn, crit):
        return f"(({expr}) > bool {num(warn)}) + (({expr}) > bool {num(crit)})"

    @staticmethod
    def with_min(expr, volume, minimum):
        """A quotient only counts from a minimum volume on, as in the alerts."""
        return f"(({expr}) and on() (({volume}) >= {num(minimum)}))"

    @staticmethod
    def disp(expr):
        """Quotient for display: below the minimum volume "wenig Verkehr" instead of no value."""
        return f"({expr}) or on() vector(-1)"

    def blocks(self):
        r = self.r
        b = {}
        q = f'opaa_query_count_total{{{SEL}, status=~"success|error"}}'
        b["questions_1h"] = f"sum(increase({q}[1h]))"
        b["error_share_1h"] = self.with_min(
            f'sum(increase(opaa_query_count_total{{{SEL}, status="error"}}[1h])) / {b["questions_1h"]}',
            b["questions_1h"], r["chatSloMinQuestions"])
        b["success_share_1h"] = self.with_min(
            f'sum(increase(opaa_query_count_total{{{SEL}, status="success"}}[1h])) / {b["questions_1h"]}',
            b["questions_1h"], r["chatSloMinQuestions"])
        ttft_count = f"sum(increase(opaa_query_time_to_first_token_seconds_count{{{SEL}}}[30m]))"
        b["ttft_p95"] = self.with_min(
            f"histogram_quantile(0.95, sum by (le) (rate(opaa_query_time_to_first_token_seconds_bucket{{{SEL}}}[30m])))",
            ttft_count, r["timeToFirstTokenMinQuestions"])
        searches = f"sum(increase(opaa_retrieval_hits_count{{{SEL}}}[30m]))"
        b["searches_30m"] = searches
        b["empty_share"] = self.with_min(
            f"sum(increase(opaa_retrieval_empty_total{{{SEL}}}[30m])) / {searches}", searches,
            r["retrievalEmptyMinQuestions"])
        out = "opaa_outbound_requests_seconds_count"
        idp_all = f'sum by (target) (increase({out}{{{SEL}, target=~"oidc|directory"}}[10m]))'
        b["idp"] = (f'2 * max((sum by (target) (increase({out}{{{SEL}, target=~"oidc|directory", outcome="auth_error"}}[10m]))'
                    f' / {idp_all} > bool {num(r["identityProviderAuthErrorShare"])})'
                    f' and {idp_all} >= {num(r["identityProviderMinRequests"])})')
        thr_all = f'sum by (target) (increase({out}{{{SEL}, path_class="interactive"}}[30m]))'
        b["throttled"] = (f'max((sum by (target) (increase({out}{{{SEL}, path_class="interactive", outcome="throttled"}}[30m]))'
                          f' / {thr_all} > bool {num(r["throttledShare"])})'
                          f' and {thr_all} >= {num(r["throttledMinRequests"])})')
        auth_all = f'sum by (target, kind) (increase({out}{{{SEL}, target!~"oidc|directory"}}[10m]))'
        b["outbound_auth"] = (f'max((sum by (target, kind) (increase({out}{{{SEL}, target!~"oidc|directory", outcome=~"auth_error|tls_error"}}[10m]))'
                              f' / {auth_all} > bool {num(r["outboundAuthErrorShare"])})'
                              f' and {auth_all} >= {num(r["outboundAuthErrorMinRequests"])})')
        b["overdue_libs"] = f'sum(max by (connector) (opaa_indexing_libraries_overdue{{{SEL}, connector=~"$connector"}}))'
        runs = f'sum by (connector) (increase(opaa_indexing_runs_total{{{SEL}, connector=~"$connector"}}[6h]))'
        b["run_failure_share"] = (
            f'max(sum by (connector) (increase(opaa_indexing_runs_total{{{SEL}, connector=~"$connector", result="failed"}}[6h]))'
            f' / {runs} and {runs} >= {num(r["indexingRunFailureMinRuns"])})')
        since = f'(time() - max by (job) (opaa_scheduled_last_success{{{SEL}, job!~"audit-chain|audit-retention"}}))'
        b["jobs_overdue"] = (f'count({since} > on(job) ({num(r["scheduledOverdueFactor"])} * max by (job) '
                             f'(opaa_scheduled_expected_interval_seconds{{{SEL}}})) and {since} > '
                             f'{num(r["scheduledOverdueMinMinutes"] * 60)}) or vector(0)')
        qmax = r["queueMaxAgeMinutes"]
        b["queue_age"] = {qn: f'max(opaa_queue_oldest_age_seconds{{{SEL}, queue="{qn}"}})' for qn in qmax}
        b["queues_late"] = ("count(" + " or ".join(
            f'max by (queue) (opaa_queue_oldest_age_seconds{{{SEL}, queue="{qn}"}}) > {num(m * 60)}'
            for qn, m in sorted(qmax.items())) + ") or vector(0)")
        b["queue_oldest"] = f'max(opaa_queue_oldest_age_seconds{{{SEL}}})'
        mails = f"sum(increase(opaa_mail_sent_total{{{SEL}}}[30m]))"
        b["mail_failing"] = self.with_min(
            f'sum(increase(opaa_mail_sent_total{{{SEL}, result="failed"}}[30m])) / {mails} > bool {num(r["mailFailureShare"])}',
            mails, r["mailFailureMinAttempts"])
        b["dirsync_age"] = f'time() - max(opaa_directory_sync_last_success_seconds{{{SEL}, provider!="none"}})'
        b["dirsync_pending"] = f'max(opaa_directory_sync_plan_pending_age_seconds{{{SEL}}})'
        b["evidence"] = {
            "chain": f"max(opaa_audit_chain_lag_seconds{{{SEL}}})",
            "unsealed": f"max(opaa_audit_chain_unsealed_months{{{SEL}}})",
            "siem": f"max(opaa_siem_export_lag_seconds{{{SEL}}})",
            "retention": f"max(opaa_audit_retention_last_run_age_seconds{{{SEL}}})",
            "horizon": f"max(opaa_audit_log_horizon_seconds{{{SEL}}})",
        }
        http_all = f"sum(increase(http_server_requests_seconds_count{{{SEL}}}[10m]))"
        http_share = f'sum(increase(http_server_requests_seconds_count{{{SEL}, status=~"5.."}}[10m])) / {http_all}'
        b["http_5xx"] = self.worst(
            self.with_min(f"({http_share}) > bool {num(r['errorRateThreshold'])}", http_all, r["errorRateMinRequests"]),
            self.with_min(f"2 * (({http_share}) > bool {num(r['errorRateCriticalThreshold'])})", http_all,
                          r["errorRateCriticalMinRequests"]))
        return b

    def deploy_score(self, suffix):
        a = f'sum(kube_deployment_status_replicas_available{{{KSEL}, deployment=~".+-{suffix}"}})'
        s = f'sum(kube_deployment_spec_replicas{{{KSEL}, deployment=~".+-{suffix}"}})'
        return f"((2 * ({a} == bool 0)) + (({a} < bool {s}) * ({a} > bool 0))) and on() ({s} > 0)"

    def role_score(self, role, level):
        """-1 = role not set up (no series), 0 available, level when no pod reaches it."""
        return (f'({level} * (max(opaa_model_role_available{{{SEL}, role="{role}"}}) == bool 0))'
                f' or on() vector(-1)')

    ROLES = [("chat", 2, "Chat-Modell"), ("embedding", 2, "Embedding"), ("rerank", 1, "Rerank"),
             ("ocr", 1, "Texterkennung"), ("transcription", 1, "Transkription"), ("vision", 1, "Bildverstehen")]

    def scores(self):
        r, b = self.r, self.blocks()
        budget = 1 - r["chatSloObjective"]
        s = {}
        s["backend"] = self.worst(self.deploy_score("backend"),
                                  f'2 * (max(up{{{KSEL}, service=~".+-backend"}}) == bool 0)')
        s["frontend"] = f"({self.deploy_score('frontend')}) or on() vector(-1)"
        s["database"] = self.worst(
            f'2 * (max(opaa_health_group_status{{{SEL}, health_group="readiness"}}) == bool 0)',
            f"sum(increase(hikaricp_connections_timeout_total{{{SEL}}}[10m])) > bool 0")
        s["idp"] = f"({b['idp']}) or on() vector(0)"
        s["chat"] = self.worst(
            self.gt(b["error_share_1h"], 6 * budget, 14.4 * budget),
            f"({b['ttft_p95']}) > bool {num(r['timeToFirstTokenSeconds'])}",
            base=f"-1 * (({b['questions_1h']} or on() vector(0)) < bool {num(r['chatSloMinQuestions'])})")
        s["search"] = self.worst(
            f"2 * (({b['empty_share']}) >= bool {num(r['retrievalEmptyShare'])})",
            f'2 * (max(opaa_model_role_available{{{SEL}, role="embedding"}}) == bool 0)',
            f'max(opaa_model_role_available{{{SEL}, role="rerank"}}) == bool 0',
            base=f"-1 * (({b['searches_30m']} or on() vector(0)) < bool {num(r['retrievalEmptyMinQuestions'])})")
        s["models"] = self.worst(
            *[f'{lvl} * (max(opaa_model_role_available{{{SEL}, role="{role}"}}) == bool 0)' for role, lvl, _ in self.ROLES],
            b["throttled"], b["outbound_auth"])
        s["indexing"] = self.worst(
            f"({b['overdue_libs']}) > bool 0",
            f"({b['run_failure_share']}) > bool {num(r['indexingRunFailureShare'])}",
            *[f"{b['queue_age'][qn]} > bool {num(r['queueMaxAgeMinutes'][qn] * 60)}"
              for qn in ("indexing", "ocr_backlog", "source_events") if qn in r["queueMaxAgeMinutes"]])
        s["background"] = self.worst(
            f"({b['jobs_overdue']}) > bool 0",
            *[f"{b['queue_age'][qn]} > bool {num(r['queueMaxAgeMinutes'][qn] * 60)}"
              for qn in ("mail_outbox",) if qn in r["queueMaxAgeMinutes"]],
            b["mail_failing"],
            f"({b['dirsync_age']}) > bool {num(r['directorySyncMaxAgeHours'] * 3600)}",
            f"({b['dirsync_pending']}) > bool {num(r['directorySyncPlanPendingHours'] * 3600)}",
            f"max(opaa_invalidation_bus_connected{{{SEL}}}) < bool 1",
            f"max(opaa_instance_heartbeat_age_seconds{{{SEL}}}) > bool {num(r['heartbeatMaxAgeSeconds'])}")
        s["platform"] = self.worst(
            s["backend"], s["frontend"], s["database"], b["idp"], b["http_5xx"],
            f"max(sum by (host) (jvm_gc_live_data_size_bytes{{{SEL}}}) / sum by (host) (jvm_gc_max_data_size_bytes{{{SEL}}}))"
            f" > bool {num(r['heapUsageThreshold'])}",
            f"max(opaa_instances_versions{{{SEL}}}) > bool 1")
        e = b["evidence"]
        s["evidence"] = self.worst(
            f"{e['chain']} > bool 21600", f"{e['unsealed']} > bool 0",
            f"{e['siem']} > bool {num(r['siemExportLagMinutes'] * 60)}", f"{e['retention']} > bool 3024000",
            f"({e['horizon']} < bool {num(r['auditLogHorizonWarningDays'] * 86400)})"
            f" + ({e['horizon']} < bool {num(r['auditLogHorizonCriticalHours'] * 3600)})")
        s["alerts"] = ('max(2 * (ALERTS{alertstate="firing", alertname=~"Opaa.*", severity="critical"} == bool 1)'
                       ' or (ALERTS{alertstate="firing", alertname=~"Opaa.*", severity="warning"} == bool 1)) or vector(0)')
        s["security"] = self.worst(
            s["evidence"],
            self.with_min(f'sum(increase(opaa_malware_scans_seconds_count{{{SEL}, result="failed"}}[15m]))'
                          f' / sum(increase(opaa_malware_scans_seconds_count{{{SEL}}}[15m]))'
                          f' > bool {num(r["malwareScanFailureShare"])}',
                          f"sum(increase(opaa_malware_scans_seconds_count{{{SEL}}}[15m]))", r["malwareScanMinScans"]),
            f"max(opaa_malware_signatures_age_seconds{{{SEL}}}) > bool {num(r['malwareSignatureMaxAgeDays'] * 86400)}",
            f'sum(increase(opaa_rate_limit_rejected_total{{{SEL}, scope="global"}}[15m])) > bool 0',
            f"max(opaa_installation_pending{{{SEL}}}) == bool 1")
        return s, b

    # ------------------------------------------------------------ shared panels

    def link(self, title, key):
        return [{"title": title, "url": f"/d/{UIDS[key]}?${{__url_time_range}}&${{namespace:queryparam}}&${{host:queryparam}}"}]

    def alerts_table(self, pos):
        sev = [{"type": "value", "options": {
            "critical": {"text": "sofort", "color": CRIT, "index": 0},
            "warning": {"text": "Ticket", "color": WARN, "index": 1},
            "info": {"text": "Info", "color": GREY, "index": 2}}}]
        joined = ('label_join(ALERTS{alertstate="firing", alertname=~"Opaa.*"}, "betrifft", " ", '
                  '"role", "target", "kind", "connector", "queue", "job", "provider", "pod", "metric", "limit", "event_type")')
        hidden = {k: True for k in ("Time", "Value", "__name__", "alertstate", "role", "target", "kind", "connector",
                                    "queue", "job", "provider", "pod", "metric", "limit", "event_type", "namespace",
                                    "deployment", "container", "endpoint", "instance", "service", "prometheus")}
        return self.table(
            "Was feuert gerade?",
            "Aktive OPAA-Alarme mit Schweregrad und Zuständigkeit (component). Ein Klick auf den Namen öffnet das "
            "Runbook im Handbuch.",
            [(joined, "")], pos,
            [{"id": "labelsToFields", "options": {"mode": "columns"}}, {"id": "merge", "options": {}},
             {"id": "organize", "options": {
                 "excludeByName": hidden, "indexByName": {"alertname": 0, "severity": 1, "component": 2},
                 "renameByName": {"alertname": "Alarm", "severity": "Schweregrad", "component": "Zuständig"}}}],
            overrides=[self.ov("Schweregrad", mappings=sev, custom__width=90,
                               custom__cellOptions={"type": "color-background", "mode": "basic"}),
                       self.ov("Zuständig", custom__width=95),
                       self.ov("Alarm", custom__width=250, links=[
                           {"title": "Runbook öffnen", "url": "${runbook}#${__value.raw}", "targetBlank": True}])],
            no_value="Keine aktiven Alarme")

    def version_table(self, pos):
        return self.table(
            "Was hat sich geändert? Version je Pod",
            "Version und Startzeit je Backend-Pod. Zwei Versionen nebeneinander: ein Rolling Update läuft oder hängt. "
            "Neustarts erscheinen zusätzlich als Markierung auf den Zeitreihen.",
            [(f"opaa_build_info{{{SEL}}}", ""), (f"process_start_time_seconds{{{SEL}}} * 1000", "")], pos,
            [{"id": "merge", "options": {}},
             {"id": "organize", "options": {
                 "excludeByName": {"Time": True, "Value #A": True, "__name__": True, "job": True, "application": True,
                                   "instance": True, "namespace": True, "service": True, "endpoint": True,
                                   "container": True, "pod": True},
                 "indexByName": {"host": 0, "version": 1, "commit": 2, "Value #B": 3},
                 "renameByName": {"host": "Pod", "version": "Version", "commit": "Commit", "Value #B": "gestartet"}}}],
            overrides=[self.ov("gestartet", unit="dateTimeAsLocalNoDateIfToday", custom__width=110),
                       self.ov("Pod", custom__width=250)])

    def blocks_stat(self, s, pos, title):
        items = [(s["backend"], "Backend"), (s["frontend"], "Frontend"), (s["database"], "Datenbank"),
                 (s["idp"], "Anmeldedienst")] + [(self.role_score(r, lvl), name) for r, lvl, name in self.ROLES]
        return self.status(
            title,
            "Je Baustein ein Zustand. Backend und Frontend: bereite Pods gegen Soll (Teilausfall: prüfen). Datenbank: "
            "Readiness und Wartezeiten im Connection Pool. Anmeldedienst: abgelehnte Aufrufe an Identitätsanbieter und "
            "Verzeichnisdienst. Modellrollen: erreichbar für mindestens einen Pod; nicht eingerichtete Rollen sind grau.",
            items, pos, font={"titleSize": 13, "valueSize": 15})

    def evidence_stat(self, b, pos):
        e, r = b["evidence"], self.r
        return self.stat(
            "Nachweispflichten",
            "Prüfsummenkette: Rückstand (Schwelle 6 h). Monate ohne Siegel. Ausleitung an das Sicherheitsmonitoring: "
            "Rückstand. Letzter Löschlauf (Schwelle 35 Tage). Protokollhorizont: Zeit, bis das Revisionsprotokoll nicht "
            "mehr schreiben kann.",
            [(f"{e['chain']} / 60", "Prüfsummenkette Rückstand"), (e["unsealed"], "Monate ohne Siegel"),
             (f"{e['siem']} / 60", "SIEM-Rückstand"), (f"{e['retention']} / 86400", "Löschlauf vor"),
             (f"{e['horizon']} / 86400", "Protokollhorizont")], pos, unit=MIN, decimals=0, text_mode="value_and_name",
            thresholds=self.thr(OK, (WARN, 360)), font={"titleSize": 14, "valueSize": 24},
            overrides=[self.ov("Monate ohne Siegel", unit="short", thresholds=self.thr(OK, (WARN, 1))),
                       self.ov("SIEM-Rückstand", thresholds=self.thr(OK, (WARN, r["siemExportLagMinutes"]))),
                       self.ov("Löschlauf vor", unit=DAYS, thresholds=self.thr(OK, (WARN, 35))),
                       self.ov("Protokollhorizont", unit=DAYS, thresholds=self.thr(
                           CRIT, (WARN, r["auditLogHorizonCriticalHours"] / 24), (OK, r["auditLogHorizonWarningDays"])))])

    # ------------------------------------------------------------ variables, frame

    @staticmethod
    def query_var(name, label, query, hide=0):
        return {"name": name, "label": label, "type": "query", "datasource": DS,
                "query": {"query": query, "refId": "v"}, "definition": query, "refresh": 2, "includeAll": True,
                "multi": True, "allValue": ".*", "current": {"text": "All", "value": "$__all"}, "sort": 1,
                "hide": hide}

    def variables(self, *extra):
        out = [{"name": "DS_PROMETHEUS", "label": "Datenquelle", "type": "datasource", "query": "prometheus",
                "current": {}, "hide": 0, "refresh": 1, "regex": "", "includeAll": False, "multi": False},
               self.query_var("namespace", "Namespace", "label_values(opaa_build_info, namespace)"),
               self.query_var("host", "Pod", 'label_values(opaa_build_info{namespace=~"$namespace"}, host)'),
               {"name": "runbook", "type": "constant", "query": self.runbook, "hide": 2,
                "current": {"text": self.runbook, "value": self.runbook}}]
        if "connector" in extra:
            out.append(self.query_var("connector", "Konnektortyp",
                                      'label_values(opaa_indexing_runs_total{namespace=~"$namespace"}, connector)'))
        if "connector-hidden" in extra:
            out.append(self.query_var("connector", "Konnektortyp",
                                      'label_values(opaa_indexing_runs_total{namespace=~"$namespace"}, connector)', hide=2))
        if "model" in extra:
            out.append(self.query_var("model", "Modell",
                                      'label_values(opaa_model_tokens_total{namespace=~"$namespace"}, model)'))
            for name, label in (("price_input", "Preis Eingabe (€ je 1 Mio. Tokens)"),
                                ("price_output", "Preis Ausgabe (€ je 1 Mio. Tokens)")):
                out.append({"name": name, "label": label, "type": "textbox", "query": "0", "hide": 0,
                            "current": {"text": "0", "value": "0"}})
        return out

    def dashboard(self, key, title, panels, variables, desc):
        return {"uid": UIDS[key], "title": title, "description": desc, "tags": ["opaa"],
                "timezone": "browser", "editable": False, "graphTooltip": 1, "schemaVersion": 41,
                "time": {"from": "now-24h", "to": "now"}, "refresh": "1m", "fiscalYearStartMonth": 0,
                "annotations": {"list": [
                    {"builtIn": 1, "datasource": {"type": "grafana", "uid": "-- Grafana --"}, "enable": True,
                     "hide": True, "iconColor": "rgba(0, 211, 255, 1)", "name": "Annotations & Alerts",
                     "type": "dashboard"},
                    {"datasource": DS, "enable": True, "iconColor": BLUE, "name": "Neustart oder Versionswechsel",
                     "expr": f"changes(process_start_time_seconds{{{SEL}}}[2m]) > 0", "step": "60s",
                     "titleFormat": "Neustart {{host}}", "textFormat": "Pod neu gestartet (Rolling Update oder Absturz)",
                     "tagKeys": "host", "useValueForTime": False}]},
                "links": [{"title": "OPAA", "type": "dashboards", "tags": ["opaa"], "asDropdown": False,
                           "includeVars": True, "keepTime": True, "targetBlank": False, "icon": "external link"}],
                "templating": {"list": variables}, "panels": panels, "version": 1}

    # ------------------------------------------------------------ dashboards

    def start(self):
        self._id = 0
        s, b = self.scores()
        r = self.r
        budget = 1 - r["chatSloObjective"]
        legend = "Grün: nichts zu tun · Gelb: Ticket, nächster Arbeitstag · Rot: sofort handeln · Grau: nicht eingerichtet oder wenig Verkehr"
        tiles = [
            ("Chat", s["chat"], "operations",
             f"Können die Leute arbeiten? Anteil gescheiterter Fragen in der letzten Stunde (ab {num(r['chatSloMinQuestions'])} "
             f"Fragen) über {num(6 * budget * 100)} % = prüfen, über {num(14.4 * budget * 100)} % = gestört; "
             f"Zeit bis zum ersten Wort p95 über {num(r['timeToFirstTokenSeconds'])} s = prüfen."),
            ("Suche & Quellen", s["search"], "knowledge",
             f"Gestört, wenn mindestens {num(r['retrievalEmptyShare'] * 100)} % der Suchen nichts finden (ab "
             f"{num(r['retrievalEmptyMinQuestions'])} in 30 min) oder das Embedding-Modell ausfällt; prüfen, wenn "
             "das Rerank-Modell ausfällt."),
            ("KI-Modelle", s["models"], "models",
             "Gestört, wenn Chat- oder Embedding-Modell für keinen Pod erreichbar ist; prüfen bei Ausfall einer anderen "
             "Rolle, bei Drosselung von Aufrufen, auf die jemand wartet, oder bei abgelehnter Anmeldung an einem Dienst."),
            ("Indexierung", s["indexing"], "indexing",
             "Prüfen, wenn eine Bibliothek ihren Zeitplan verpasst hat, viele Läufe eines Konnektortyps scheitern oder "
             "eine Warteschlange der Indexierung über ihrer Schwelle liegt."),
            ("Hintergrund", s["background"], "operations",
             "Geplante Läufe überfällig, Mail-Ausgang im Verzug oder Zustellung gescheitert, Verzeichnisabgleich veraltet "
             "oder Plan wartet, Instanz ohne Cache-Meldungen oder mit veraltetem Herzschlag."),
            ("Plattform", s["platform"], "operations",
             "Backend- und Frontend-Pods gegen Soll, Datenbank, Anmeldedienst, Anteil der Fehlerantworten, Heap nach "
             "der Speicherbereinigung, mehrere Versionen nebeneinander."),
            ("Nachweis", s["evidence"], "security",
             "Prüfsummenkette, Monatssiegel, Rückstand der Ausleitung, Löschlauf und Protokollhorizont. Grün nur, wenn "
             "alles grün ist."),
            ("Alarme", s["alerts"], "start",
             "Schwerster aktiver OPAA-Alarm: rot bei critical, gelb bei warning. Info-Alarme färben nicht."),
        ]
        panels = [self.banner("OPAA · Lage", legend, self.gp(0, 0, 24, 2))]
        for i, (name, expr, target, desc) in enumerate(tiles):
            panels.append(self.status(name, desc, [(expr, name)], self.gp(i * 3, 2, 3, 5),
                                      links=self.link(f"Details: {name}", target), text_mode="value",
                                      font={"valueSize": 22}))
        q = f'opaa_query_count_total{{{SEL}, status=~"success|error"}}'
        panels += [
            self.ts("Fragen pro Stunde",
                    "Beantwortete und gescheiterte Fragen der ganzen Installation pro Stunde, als Kontext für die "
                    "Quoten: nachts ist wenig Verkehr. Keine Aufteilung nach Kanal oder Person.",
                    [(f"sum(increase({q}[$__interval]))", "Fragen"),
                     (f'sum(increase(opaa_query_count_total{{{SEL}, status="error"}}[$__interval]))', "gescheitert")],
                    self.gp(0, 7, 8, 8), bars=True, interval="1h",
                    overrides=[self.ov("Fragen", color=self.fixed("blue")), self.ov("gescheitert", color=self.fixed("red"))]),
            self.ts("Zeit bis zum ersten Wort (p95)",
                    "Wartezeit vom Absenden bis zum ersten sichtbaren Wort der Antwort; 95 % der Fragen waren schneller.",
                    [(f"histogram_quantile(0.95, sum by (le) (rate(opaa_query_time_to_first_token_seconds_bucket{{{SEL}}}[15m])))", "p95"),
                     (f"histogram_quantile(0.5, sum by (le) (rate(opaa_query_time_to_first_token_seconds_bucket{{{SEL}}}[15m])))", "Median")],
                    self.gp(8, 7, 8, 8), unit="s", thresholds=self.thr(OK, (WARN, r["timeToFirstTokenSeconds"])),
                    thr_style="line+area", fill=0,
                    overrides=[self.ov("p95", color=self.fixed("blue")), self.ov("Median", color=self.fixed("#8AB8FF"))]),
            self.alerts_table(self.gp(16, 7, 8, 8)),
            self.version_table(self.gp(0, 15, 12, 7)),
            self.stat("Über Nacht liegen geblieben?",
                      "Bibliotheken, die ihren Zeitplan verpasst haben (nur Anzahl; welche, zeigt der Laufstatus der "
                      "Bibliotheken), überfällige geplante Läufe, Warteschlangen über ihrer Schwelle und ihr ältester Eintrag.",
                      [(f"{b['overdue_libs']} or vector(0)", "Bibliotheken überfällig"), (b["jobs_overdue"], "Läufe überfällig"),
                       (b["queues_late"], "Warteschlangen im Verzug"), (f"{b['queue_oldest']} / 60", "ältester Eintrag")],
                      self.gp(12, 15, 12, 7), thresholds=self.thr(OK, (WARN, 1)), text_mode="value_and_name",
                      font={"titleSize": 14, "valueSize": 40},
                      overrides=[self.ov("ältester Eintrag", unit=MIN, decimals=0, thresholds=self.thr(
                          OK, (WARN, min(r["queueMaxAgeMinutes"].values()))))]),
        ]
        return self.dashboard("start", "OPAA · Lage", panels, self.variables("connector-hidden"),
                              "Startseite: eine Ampel je Baustein, darunter wenige Kernkurven; jede Kachel öffnet ihren Bereich.")

    def indexing(self):
        self._id = 100
        s, b = self.scores()
        r = self.r
        C = f'{SEL}, connector=~"$connector"'
        qm = r["queueMaxAgeMinutes"]
        panels = [
            self.stat("Bibliotheken überfällig",
                      "Bibliotheken mit Zeitplan, deren letzter erfolgreicher Lauf länger als zwei ihrer Intervalle "
                      "zurückliegt. Nur Anzahl; welche, zeigt der Laufstatus der Bibliotheken.",
                      [(f"{b['overdue_libs']} or vector(0)", "")], self.gp(0, 0, 4, 3), thresholds=self.thr(OK, (WARN, 1))),
            self.stat("Läufe gescheitert (6 h)",
                      f"Anteil gescheiterter Läufe in sechs Stunden, schlechtester Konnektortyp, ab "
                      f"{num(r['indexingRunFailureMinRuns'])} Läufen. Einzelne defekte Dateien zählen nicht.",
                      [(self.disp(b["run_failure_share"]), "")], self.gp(4, 0, 4, 3), unit="percentunit", decimals=1,
                      mappings=LOW, thresholds=self.thr(OK, (WARN, r["indexingRunFailureShare"]))),
            self.stat("Dokumente verarbeitet", "Neu oder geändert indizierte Dokumente im gewählten Zeitraum.",
                      [(f'sum(increase(opaa_indexing_documents_total{{{C}, result="processed"}}[$__range]))', "")],
                      self.gp(8, 0, 4, 3), color_mode="none", decimals=0, unit="locale"),
            self.stat("Ältester Auftrag Indexierung",
                      f"Seit wann der älteste angenommene Lauf wartet oder läuft; schließt den längsten laufenden Lauf "
                      f"ein. Schwelle {num(qm.get('indexing', 0))} min.",
                      [(f"{b['queue_age']['indexing']} / 60", "")], self.gp(12, 0, 4, 3), unit=MIN, decimals=0,
                      thresholds=self.thr(OK, (WARN, qm.get("indexing", 0)))),
            self.stat("Ältester Scan Texterkennung",
                      f"Wie lange der älteste hochgeladene Scan auf die Texterkennung wartet. Schwelle "
                      f"{num(qm.get('ocr_backlog', 0))} min.",
                      [(f"{b['queue_age']['ocr_backlog']} / 60", "")], self.gp(16, 0, 4, 3), unit=MIN, decimals=0,
                      thresholds=self.thr(OK, (WARN, qm.get("ocr_backlog", 0)))),
            self.stat("Ältestes Quellereignis",
                      f"Wie lange die älteste gemeldete Änderung einer Quelle wartet. Schwelle {num(qm.get('source_events', 0))} min.",
                      [(f"{b['queue_age']['source_events']} / 60", "")], self.gp(20, 0, 4, 3), unit=MIN, decimals=0,
                      thresholds=self.thr(OK, (WARN, qm.get("source_events", 0)))),
            self.table("Je Konnektortyp (24 h)",
                       "Nur Anzahlen je Konnektortyp, keine einzelnen Bibliotheken; private Bibliotheken zusammengefasst "
                       "als private. Laufdauer als 95. Perzentil.",
                       [(f"max by (connector) (opaa_indexing_libraries_overdue{{{C}}})", ""),
                        (f"sum by (connector) (increase(opaa_indexing_runs_total{{{C}}}[24h]))", ""),
                        (f'sum by (connector) (increase(opaa_indexing_runs_total{{{C}, result="failed"}}[24h]))', ""),
                        (f'sum by (connector) (increase(opaa_indexing_documents_total{{{C}, result="processed"}}[24h]))', ""),
                        (f'histogram_quantile(0.95, sum by (connector, le) (rate(opaa_indexing_run_duration_seconds_bucket{{{C}, result="completed"}}[24h])))', ""),
                        (f"(time() - max by (connector) (opaa_indexing_run_last_success_seconds{{{C}}})) / 3600", "")],
                       self.gp(0, 3, 16, 8),
                       [{"id": "merge", "options": {}},
                        {"id": "organize", "options": {"excludeByName": {"Time": True}, "renameByName": {
                            "connector": "Konnektortyp", "Value #A": "überfällig", "Value #B": "Läufe",
                            "Value #C": "gescheitert", "Value #D": "Dokumente", "Value #E": "Laufdauer p95",
                            "Value #F": "jüngster Erfolg vor"}}}],
                       overrides=[self.ov("überfällig", thresholds=self.thr(OK, (WARN, 1)),
                                          custom__cellOptions={"type": "color-background", "mode": "basic"}),
                                  self.ov("Läufe", decimals=0), self.ov("gescheitert", decimals=0),
                                  self.ov("Dokumente", decimals=0), self.ov("Laufdauer p95", unit="s", decimals=0),
                                  self.ov("jüngster Erfolg vor", unit=H, decimals=1)],
                       sort=[{"displayName": "überfällig", "desc": True}]),
            self.bargauge("Gescheiterte Läufe nach Grund (24 h)",
                          "Grund gescheiterter Läufe, etwa abgelehnte Zugangsdaten oder eine nicht verbundene Quelle; "
                          "abandoned beendete ein Neustart, Ausfall oder eine Zeitüberschreitung.",
                          [(f'sum by (failure_category) (increase(opaa_indexing_runs_total{{{C}, result="failed"}}[24h]))', "{{failure_category}}")],
                          self.gp(16, 3, 8, 8), thresholds=self.thr(WARN)),
            self.ts("Verarbeitete Dokumente je Konnektortyp", "Neu oder geändert indizierte Dokumente pro Stunde.",
                    [(f'sum by (connector) (increase(opaa_indexing_documents_total{{{C}, result="processed"}}[$__interval]))', "{{connector}}")],
                    self.gp(0, 11, 12, 7), bars=True, stack=True, interval="1h"),
            self.ts("Warteschlangen: ältester Eintrag", "Alter des ältesten Eintrags je Warteschlange der Indexierung.",
                    [(f'max by (queue) (opaa_queue_oldest_age_seconds{{{SEL}, queue=~"indexing|ocr_backlog|source_events"}}) / 60', "{{queue}}")],
                    self.gp(12, 11, 12, 7), unit=MIN),
            self.ts("Dauer je Verarbeitungsschritt (p95)",
                    "95. Perzentil je Schritt: herunterladen, lesen, Texterkennung, Abschnitte, Embedding, speichern. "
                    "Zeigt den Engpass.",
                    [(f"histogram_quantile(0.95, sum by (stage, le) (rate(opaa_indexing_stage_duration_seconds_bucket{{{SEL}}}[15m])))", "{{stage}}")],
                    self.gp(0, 18, 12, 7), unit="s", fill=0),
            self.ts("Laufende und wartende Läufe", "Läufe, die die Pods gerade ausführen, und Läufe, die auf einen Thread warten.",
                    [(f"sum(opaa_indexing_runs_active{{{SEL}}})", "laufend"), (f"sum(opaa_indexing_runs_queued{{{SEL}}})", "wartend")],
                    self.gp(12, 18, 12, 7), decimals=0),
            self.ts("Nicht lesbare Dokumente nach Dateiklasse", "Dokumente ohne verwertbaren Text pro Stunde.",
                    [(f"sum by (mime_class) (increase(opaa_indexing_parse_failures_total{{{SEL}}}[$__interval]))", "{{mime_class}}")],
                    self.gp(0, 25, 8, 7), bars=True, stack=True, interval="1h"),
            self.ts("Heruntergeladen je Konnektortyp", "Bytes, die Läufe von ihren Quellen geladen haben, pro Stunde.",
                    [(f"sum by (connector) (increase(opaa_indexing_bytes_total{{{C}}}[$__interval]))", "{{connector}}")],
                    self.gp(8, 25, 8, 7), bars=True, stack=True, interval="1h", unit="bytes"),
            self.ts("Texterkennung und Neueinbettung",
                    "Per Texterkennung gesendete Seiten pro Stunde und Abschnitte, die ein Wechsel des Embedding-Modells "
                    "noch einbetten muss.",
                    [(f"sum(increase(opaa_ocr_pages_total{{{SEL}}}[$__interval]))", "Seiten Texterkennung"),
                     (f"max(opaa_indexing_reindex_remaining{{{SEL}}})", "Neueinbettung offen")],
                    self.gp(16, 25, 8, 7), interval="1h",
                    overrides=[self.ov("Neueinbettung offen", custom__axisPlacement="right")]),
        ]
        return self.dashboard("indexing", "OPAA · Indexierung & Konnektoren", panels, self.variables("connector"),
                              "Drilldown der Kachel Indexierung: Läufe, Rückstände und Schritte je Konnektortyp.")

    def operations(self):
        self._id = 200
        s, b = self.scores()
        r = self.r
        budget = 1 - r["chatSloObjective"]
        panels = [
            self.blocks_stat(s, self.gp(0, 0, 24, 3), "Bausteine"),
            self.stat("Nutzerseite",
                      f"Anteil beantworteter Fragen in der letzten Stunde (ab {num(r['chatSloMinQuestions'])} Fragen; "
                      "abgewiesene Fragen zählen nicht) und Zeit bis zum ersten Wort (p95, 30 min).",
                      [(self.disp(b["success_share_1h"]), "Fragen beantwortet"), (self.disp(b["ttft_p95"]), "Erstes Wort (p95)")],
                      self.gp(0, 3, 12, 4), unit="percentunit", decimals=1, text_mode="value_and_name", mappings=LOW,
                      thresholds=self.thr(CRIT, (WARN, 1 - 14.4 * budget), (OK, 1 - 6 * budget)),
                      font={"titleSize": 14, "valueSize": 26},
                      overrides=[self.ov("Erstes Wort (p95)", unit="s", mappings=LOW,
                                         thresholds=self.thr(GREY, (OK, 0), (WARN, r["timeToFirstTokenSeconds"])))]),
            self.stat("Hintergrund",
                      "Überfällige geplante Läufe, Warteschlangen über ihrer Schwelle, letzter angewendeter "
                      "Verzeichnisabgleich, Pods ohne Cache-Meldungen.",
                      [(b["jobs_overdue"], "Läufe überfällig"), (b["queues_late"], "Warteschlangen im Verzug"),
                       (f"({b['dirsync_age']}) / 3600", "Abgleich angewendet vor"),
                       (f"count(opaa_invalidation_bus_connected{{{SEL}}} == 0) or vector(0)", "Pods ohne Cache-Meldungen")],
                      self.gp(12, 3, 12, 4), thresholds=self.thr(OK, (WARN, 1)), text_mode="value_and_name",
                      font={"titleSize": 14, "valueSize": 26},
                      overrides=[self.ov("Abgleich angewendet vor", unit=H, decimals=1,
                                         thresholds=self.thr(OK, (WARN, r["directorySyncMaxAgeHours"])))]),
            self.ts("Antwortzeit je Route (p95)",
                    "95. Perzentil der HTTP-Antwortzeit je Route. Gestreamte Chat-Antworten stehen unter Zeit bis zum "
                    "ersten Wort.",
                    [(f'histogram_quantile(0.95, sum by (uri, le) (rate(http_server_requests_seconds_bucket{{{SEL}, uri!="/api/v1/query/stream"}}[5m])))', "{{uri}}")],
                    self.gp(0, 7, 12, 7), unit="s", fill=0),
            self.ts("Fehlerantworten (5xx) je Pod", "HTTP-Antworten mit Status 5xx pro Minute.",
                    [(f'sum by (host) (rate(http_server_requests_seconds_count{{{SEL}, status=~"5.."}}[5m])) * 60', "{{host}}")],
                    self.gp(12, 7, 12, 7), decimals=1),
            self.ts("Heap nach der Speicherbereinigung",
                    f"Live-Daten nach der Speicherbereinigung gegen das Maximum je Pod; Schwelle {num(r['heapUsageThreshold'] * 100)} %.",
                    [(f"sum by (host) (jvm_gc_live_data_size_bytes{{{SEL}}}) / sum by (host) (jvm_gc_max_data_size_bytes{{{SEL}}})", "{{host}}")],
                    self.gp(0, 14, 8, 7), unit="percentunit", max_=1,
                    thresholds=self.thr("transparent", ("orange", r["heapUsageThreshold"])), thr_style="dashed"),
            self.ts("Datenbankverbindungen", "Aktive und wartende Verbindungen im Connection Pool und vergebliche Wartezeiten.",
                    [(f"sum(hikaricp_connections_active{{{SEL}}})", "aktiv"), (f"sum(hikaricp_connections_pending{{{SEL}}})", "wartend"),
                     (f"sum(increase(hikaricp_connections_timeout_total{{{SEL}}}[5m]))", "Timeouts")],
                    self.gp(8, 14, 8, 7), overrides=[self.ov("Timeouts", color=self.fixed("red"))]),
            self.table("Geplante Läufe",
                       "Je Lauf: letzter Erfolg auf irgendeinem Pod, erwartetes Intervall und Fehler in 24 h.",
                       [(f"(time() - max by (job) (opaa_scheduled_last_success{{{SEL}}})) / 60", ""),
                        (f"max by (job) (opaa_scheduled_expected_interval_seconds{{{SEL}}}) / 60", ""),
                        (f"sum by (job) (increase(opaa_scheduled_failures_total{{{SEL}}}[24h]))", "")],
                       self.gp(16, 14, 8, 7),
                       [{"id": "merge", "options": {}},
                        {"id": "organize", "options": {"excludeByName": {"Time": True}, "renameByName": {
                            "job": "Lauf", "Value #A": "zuletzt vor", "Value #B": "Intervall", "Value #C": "Fehler 24 h"}}}],
                       overrides=[self.ov("zuletzt vor", unit=MIN, decimals=0, custom__width=90),
                                  self.ov("Intervall", unit=MIN, decimals=0, custom__width=80),
                                  self.ov("Fehler 24 h", decimals=0, thresholds=self.thr(OK, (WARN, 1)),
                                          custom__cellOptions={"type": "color-text"})]),
            self.table("Warteschlangen", "Einträge und Alter des ältesten Eintrags je Warteschlange.",
                       [(f"max by (queue) (opaa_queue_depth{{{SEL}}})", ""),
                        (f"max by (queue) (opaa_queue_oldest_age_seconds{{{SEL}}}) / 60", "")],
                       self.gp(0, 21, 8, 7),
                       [{"id": "merge", "options": {}},
                        {"id": "organize", "options": {"excludeByName": {"Time": True}, "renameByName": {
                            "queue": "Warteschlange", "Value #A": "Einträge", "Value #B": "ältester"}}}],
                       overrides=[self.ov("Einträge", decimals=0), self.ov("ältester", unit=MIN, decimals=0)]),
            self.ts("Instanzen und Sperren",
                    "Laufende Instanzen nach dem Instanzregister, Zahl ihrer Versionen und Alter der ältesten Sperre "
                    "eines geplanten Laufs.",
                    [(f"max(opaa_instances_active{{{SEL}}})", "Instanzen"), (f"max(opaa_instances_versions{{{SEL}}})", "Versionen"),
                     (f"max(opaa_scheduled_locks_oldest_age_seconds{{{SEL}}}) / 60", "älteste Sperre (min)")],
                    self.gp(8, 21, 8, 7), decimals=0,
                    overrides=[self.ov("älteste Sperre (min)", custom__axisPlacement="right")]),
            self.ts("Trefferquote der Zwischenspeicher", "Anteil der Abrufe, die ein Zwischenspeicher selbst beantwortet.",
                    [(f'sum by (cache) (rate(cache_gets_total{{{SEL}, result="hit"}}[15m])) / sum by (cache) (rate(cache_gets_total{{{SEL}}}[15m]))', "{{cache}}")],
                    self.gp(16, 21, 8, 7), unit="percentunit", max_=1, fill=0),
        ]
        return self.dashboard("operations", "OPAA · Betrieb & Bereitschaft", panels, self.variables("connector-hidden"),
                              "Bereich Betrieb: Bausteine, Nutzerseite, Hintergrund und Plattform.")

    def knowledge(self):
        self._id = 300
        s, b = self.scores()
        r = self.r
        C = f'{SEL}, connector=~"$connector"'
        panels = [
            self.stat("Bestand",
                      "Bibliotheken, Dokumente und Abschnitte der ganzen Installation. Werte privater Bibliotheken "
                      "erscheinen erst ab der Mindestgruppengröße.",
                      [(f"sum(max by (kind) (opaa_inventory_libraries{{{SEL}}}))", "Bibliotheken"),
                       (f"sum(max by (kind) (opaa_inventory_documents{{{SEL}}}))", "Dokumente"),
                       (f"sum(max by (kind) (opaa_inventory_chunks{{{SEL}}}))", "Abschnitte"),
                       (f"max(opaa_inventory_spaces{{{SEL}}})", "geteilte Spaces")],
                      self.gp(0, 0, 12, 4), color_mode="none", decimals=0, unit="locale", text_mode="value_and_name"),
            self.stat("Aktualität",
                      "Überfällige Bibliotheken, Dokumente des Zeitraums und nicht verarbeitbare Dokumente.",
                      [(f"{b['overdue_libs']} or vector(0)", "überfällig"),
                       (f'sum(increase(opaa_indexing_documents_total{{{C}, result="processed"}}[$__range]))', "neu oder geändert"),
                       (f'sum(increase(opaa_indexing_documents_total{{{C}, result="failed"}}[$__range]))', "nicht verarbeitbar")],
                      self.gp(12, 0, 12, 4), decimals=0, text_mode="value_and_name", thresholds=self.thr(OK, (WARN, 1)),
                      overrides=[self.ov("neu oder geändert", color={"mode": "fixed", "fixedColor": "text"}, unit="locale"),
                                 self.ov("nicht verarbeitbar", color={"mode": "fixed", "fixedColor": "text"}, unit="locale")]),
            self.bargauge("Überfällige Bibliotheken je Konnektortyp", "Nur Anzahl je Konnektortyp.",
                          [(f"max by (connector) (opaa_indexing_libraries_overdue{{{C}}})", "{{connector}}")],
                          self.gp(0, 4, 8, 8), thresholds=self.thr(OK, (WARN, 1)), max_=3),
            self.ts("Neue und geänderte Dokumente je Konnektortyp", "Pro Stunde.",
                    [(f'sum by (connector) (increase(opaa_indexing_documents_total{{{C}, result="processed"}}[$__interval]))', "{{connector}}")],
                    self.gp(8, 4, 16, 8), bars=True, stack=True, interval="1h"),
            self.ts("Suchen ohne Fundstelle",
                    f"Anteil der Fragen mit Suche ohne einzige Fundstelle (30 min). Ab {num(r['retrievalEmptyShare'] * 100)} % "
                    "ist die Suche gestört; einzelne leere Suchen sagen etwas über die Frage, nicht über den Betrieb.",
                    [(f"sum(increase(opaa_retrieval_empty_total{{{SEL}}}[30m])) / sum(increase(opaa_retrieval_hits_count{{{SEL}}}[30m]))", "leer")],
                    self.gp(0, 12, 8, 8), unit="percentunit", max_=1,
                    thresholds=self.thr("transparent", ("red", r["retrievalEmptyShare"])), thr_style="dashed"),
            self.ts("Belegstand der Antworten", "Antworten pro Stunde nach Belegstand.",
                    [(f"sum by (level) (increase(opaa_query_evidence_total{{{SEL}}}[$__interval]))", "{{level}}")],
                    self.gp(8, 12, 8, 8), bars=True, stack=True, interval="1h"),
            self.ts("Speicher", "Belegter Speicher der Bibliotheken nach Art.",
                    [(f"max by (kind) (opaa_inventory_storage_used_bytes{{{SEL}}})", "{{kind}}")],
                    self.gp(16, 12, 8, 8), unit="bytes"),
            self.ts("Gescheiterte Läufe je Konnektortyp", "Pro Stunde. Dauerhaft gescheiterte Läufe deuten auf abgelaufene Zugänge.",
                    [(f'sum by (connector) (increase(opaa_indexing_runs_total{{{C}, result="failed"}}[$__interval]))', "{{connector}}")],
                    self.gp(0, 20, 12, 7), bars=True, stack=True, interval="1h"),
            self.ts("Verzeichnisabgleich",
                    "Stunden seit dem letzten angewendeten Abgleich und seit ein Plan auf Bestätigung wartet.",
                    [(f'(time() - max by (provider) (opaa_directory_sync_last_success_seconds{{{SEL}, provider!="none"}})) / 3600', "angewendet vor {{provider}}"),
                     (f"max by (provider) (opaa_directory_sync_plan_pending_age_seconds{{{SEL}}}) / 3600", "Plan wartet {{provider}}")],
                    self.gp(12, 20, 12, 7), unit=H, decimals=1,
                    thresholds=self.thr("transparent", ("orange", r["directorySyncPlanPendingHours"])), thr_style="dashed"),
        ]
        return self.dashboard("knowledge", "OPAA · Wissensbestand & Bibliotheken", panels, self.variables("connector"),
                              "Bereich Wissensbestand: Bestand, Aktualität, Suche und Verzeichnisabgleich.")

    def models(self):
        self._id = 400
        s, b = self.scores()
        M = f'{SEL}, model=~"$model"'
        tokens_in = f'sum(increase(opaa_model_tokens_total{{{M}, token_type="input"}}[$__range]))'
        tokens_out = f'sum(increase(opaa_model_tokens_total{{{M}, token_type="output"}}[$__range]))'
        cost = f"(({tokens_in}) * $price_input + ({tokens_out}) * $price_output) / 1e6"
        out = "opaa_outbound_requests_seconds"
        panels = [
            self.status("Modellrollen",
                        "Erreichbarkeit je Rolle für mindestens einen Pod. Nicht eingerichtete Rollen sind grau.",
                        [(self.role_score(r, lvl), name) for r, lvl, name in self.ROLES], self.gp(0, 0, 12, 4),
                        font={"titleSize": 13, "valueSize": 16}),
            self.stat("Steuerung (gewählter Zeitraum)",
                      "Tokens des Chat-Modells und daraus berechnete Kosten. Die Kosten rechnet Grafana aus den Preisen "
                      "oben (Euro je eine Million Tokens); OPAA speichert keine Kosten.",
                      [(f"(({tokens_in}) + ({tokens_out})) / 1e6", "Tokens"), (cost, "Kosten"),
                       (f"{cost} / ($__range_s / 86400) * 30", "Hochrechnung 30 Tage"),
                       (f'sum(increase(opaa_outbound_throttled_total{{{SEL}}}[$__range]))', "gedrosselt")],
                      self.gp(12, 0, 12, 4), color_mode="none", text_mode="value_and_name",
                      overrides=[self.ov("Kosten", unit="currencyEUR", decimals=2),
                                 self.ov("Hochrechnung 30 Tage", unit="currencyEUR", decimals=0),
                                 self.ov("Tokens", unit="suffix: Mio.", decimals=1), self.ov("gedrosselt", decimals=0)]),
            self.ts("Antwortzeit der Chat- und Embedding-Modelle (p95)",
                    "95. Perzentil der Aufrufdauer je Operation und Modell, nur zur Anzeige.",
                    [(f"histogram_quantile(0.95, sum by (gen_ai_operation_name, gen_ai_request_model, le) (rate(gen_ai_client_operation_seconds_bucket{{{SEL}}}[10m])))",
                      "{{gen_ai_operation_name}} {{gen_ai_request_model}}")],
                    self.gp(0, 4, 12, 8), unit="s", fill=0),
            self.ts("Ausgehende Aufrufe nach Ergebnis",
                    "Aufrufe an Rerank, Transkription, Texterkennung, Sandbox, Quellen und Anmeldedienste pro Minute, "
                    "ohne Erfolge. client_error aus Proben im Hintergrund ist erwartbar.",
                    [(f'sum by (target, outcome) (rate({out}_count{{{SEL}, outcome!="success"}}[5m])) * 60', "{{target}} {{outcome}}")],
                    self.gp(12, 4, 12, 8), stack=True, decimals=1),
            self.ts("Dauer ausgehender Aufrufe (p95)", "95. Perzentil je Ziel.",
                    [(f"histogram_quantile(0.95, sum by (target, le) (rate({out}_bucket{{{SEL}}}[10m])))", "{{target}}")],
                    self.gp(0, 12, 12, 7), unit="s", fill=0),
            self.ts("Gedrosselte Aufrufe", "Antworten 429 oder 503 mit Wartehinweis pro Stunde, nach Ziel und Pfad. Im "
                    "Hintergrund bremst die Indexierung damit nur; interaktiv wartet eine Person.",
                    [(f"sum by (target, path_class) (increase(opaa_outbound_throttled_total{{{SEL}}}[$__interval]))", "{{target}} {{path_class}}")],
                    self.gp(12, 12, 12, 7), bars=True, stack=True, interval="1h"),
            self.ts("Tokens pro Stunde nach Zweck", "Tokens des Chat-Modells nach Zweck: Antwort, Zerlegung, Notiz, Nebenaufgaben, Agent.",
                    [(f"sum by (purpose) (increase(opaa_model_tokens_total{{{M}}}[$__interval]))", "{{purpose}}")],
                    self.gp(0, 19, 12, 8), bars=True, stack=True, interval="1h"),
            self.ts("Kosten pro Tag nach Modell", "Aus Tokens und den Preisen oben berechnet, in Euro.",
                    [(f'(sum by (model) (increase(opaa_model_tokens_total{{{M}, token_type="input"}}[$__interval])) * $price_input'
                      f' + sum by (model) (increase(opaa_model_tokens_total{{{M}, token_type="output"}}[$__interval])) * $price_output) / 1e6', "{{model}}")],
                    self.gp(12, 19, 12, 8), bars=True, stack=True, interval="1d", unit="currencyEUR", decimals=2),
            self.ts("Ende der Antworten", "Antworten pro Stunde nach dem Ende, das das Modell meldet; length heißt an der Ausgabegrenze abgeschnitten.",
                    [(f"sum by (reason) (increase(opaa_query_finish_reason_total{{{SEL}}}[$__interval]))", "{{reason}}")],
                    self.gp(0, 27, 12, 7), bars=True, stack=True, interval="1h"),
            self.ts("Agentenläufe nach Ergebnis", "Agentenläufe pro Stunde nach Ergebnis, ohne Agentennamen.",
                    [(f"sum by (result) (increase(opaa_agent_runs_total{{{SEL}}}[$__interval]))", "{{result}}")],
                    self.gp(12, 27, 12, 7), bars=True, stack=True, interval="1h"),
        ]
        return self.dashboard("models", "OPAA · KI-Modelle & Kosten", panels, self.variables("model"),
                              "Bereich Modelle: Erreichbarkeit, Antwortzeit, Drosselung, Tokens und Kosten. Kosten nur hier.")

    def security(self):
        self._id = 500
        s, b = self.scores()
        r = self.r
        mal = "opaa_malware_scans_seconds_count"
        panels = [
            self.evidence_stat(b, self.gp(0, 0, 24, 4)),
            self.stat("Schutzfunktionen (24 h)",
                      "Virenfunde, gescheiterte Virenprüfungen, Alter der Virensignaturen, Abweisungen an einer "
                      "installationsweiten Grenze, Sperren lokaler Konten, Alarme für Massenabrufe externer Zugänge und "
                      "abgelehnte neue Ziele der Zielprüfung.",
                      [(f'sum(increase({mal}{{{SEL}, result="infected"}}[24h]))', "Virenfunde"),
                       (f'sum(increase({mal}{{{SEL}, result="failed"}}[24h]))', "Prüfung gescheitert"),
                       (f"max(opaa_malware_signatures_age_seconds{{{SEL}}}) / 3600", "Signaturen alt"),
                       (f'sum(increase(opaa_rate_limit_rejected_total{{{SEL}, scope="global"}}[24h]))', "globale Grenze"),
                       (f"sum(increase(opaa_auth_local_account_lockout_total{{{SEL}}}[24h]))", "Kontosperren"),
                       (f"sum(increase(opaa_external_access_alarms_total{{{SEL}}}[24h]))", "Massenabrufe"),
                       (f"sum(increase(opaa_target_validation_refused_checks_total{{{SEL}}}[24h]))", "Ziele abgelehnt")],
                      self.gp(0, 4, 24, 4), decimals=0, thresholds=self.thr(OK, (WARN, 1)), text_mode="value_and_name",
                      overrides=[self.ov("Signaturen alt", unit=H, thresholds=self.thr(OK, (WARN, r["malwareSignatureMaxAgeDays"] * 24))),
                                 self.ov("Virenfunde", thresholds=self.thr(OK, (GREY, 1))),
                                 self.ov("Kontosperren", thresholds=self.thr(OK, (GREY, 1)))]),
            self.ts("Abgewiesene Anmeldungen", "Abgewiesene Anmeldungen pro Stunde nach Methode und Grund. Erfolgreiche "
                    "Anmeldungen zählt OPAA nicht.",
                    [(f"sum by (method, result) (increase(opaa_auth_logins_total{{{SEL}}}[$__interval]))", "{{method}} {{result}}")],
                    self.gp(0, 8, 12, 7), bars=True, stack=True, interval="1h"),
            self.ts("Abgewiesen an einer Grenze", "Abweisungen je Grenze und Geltungsbereich pro Stunde.",
                    [(f"sum by (limit, scope) (increase(opaa_rate_limit_rejected_total{{{SEL}}}[$__interval]))", "{{limit}} ({{scope}})")],
                    self.gp(12, 8, 12, 7), bars=True, stack=True, interval="1h"),
            self.ts("Virenprüfung", "Prüfungen pro Stunde nach Ergebnis.",
                    [(f"sum by (result) (increase({mal}{{{SEL}}}[$__interval]))", "{{result}}")],
                    self.gp(0, 15, 8, 7), bars=True, stack=True, interval="1h",
                    overrides=[self.ov("failed", color=self.fixed("orange")), self.ov("infected", color=self.fixed("red"))]),
            self.ts("Rückstand von Ausleitung und Prüfsummenkette",
                    f"Alter des ältesten nicht ausgeleiteten Eintrags (Schwelle {num(r['siemExportLagMinutes'])} min) und "
                    "des ältesten Eintrags ohne Kontrollpunkt der Prüfsummenkette (Schwelle 6 h).",
                    [(f"{b['evidence']['siem']} / 60", "Ausleitung"), (f"{b['evidence']['chain']} / 60", "Prüfsummenkette")],
                    self.gp(8, 15, 8, 7), unit=MIN, decimals=1),
            self.ts("Revisionsprotokoll", "Größe des Revisionsprotokolls und verbleibende Tage bis zum Ende des letzten angelegten Monats.",
                    [(f"max(opaa_audit_log_size_bytes{{{SEL}}})", "Größe"), (f"{b['evidence']['horizon']} / 86400", "Horizont (Tage)")],
                    self.gp(16, 15, 8, 7),
                    overrides=[self.ov("Größe", unit="bytes"), self.ov("Horizont (Tage)", custom__axisPlacement="right")]),
        ]
        return self.dashboard("security", "OPAA · Sicherheit & Nachweis", panels, self.variables(),
                              "Bereich Informationssicherheit und Revision: Schutzfunktionen und Nachweispflichten.")


FILES = {"opaa-start.json": "start", "opaa-indexing.json": "indexing", "opaa-operations.json": "operations",
         "opaa-knowledge.json": "knowledge", "opaa-models.json": "models", "opaa-security.json": "security"}


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--values", required=True, help="JSON of .metrics of the chart's values.yaml")
    ap.add_argument("--out", required=True, help="directory of the dashboard files")
    ap.add_argument("--check", action="store_true", help="fail if a file differs instead of writing it")
    args = ap.parse_args()
    with open(args.values) as f:
        builder = Builder(json.load(f))
    stale = []
    for name, method in FILES.items():
        text = json.dumps(getattr(builder, method)(), indent=2, ensure_ascii=False) + "\n"
        path = os.path.join(args.out, name)
        if args.check:
            current = open(path).read() if os.path.exists(path) else ""
            if current != text:
                stale.append(name)
                sys.stdout.writelines(difflib.unified_diff(current.splitlines(True), text.splitlines(True), path, "generated", n=1))
        else:
            os.makedirs(args.out, exist_ok=True)
            with open(path, "w") as f:
                f.write(text)
    if stale:
        print(f"::error::Dashboards out of date: {', '.join(stale)}. Run deploy/monitoring/render.sh.", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
