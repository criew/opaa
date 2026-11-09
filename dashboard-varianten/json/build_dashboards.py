#!/usr/bin/env python3
"""Build the OPAA Grafana dashboard variants (A, B, C, D) as JSON files.

All panels query Prometheus with the planned metric names from the target catalogue. Traffic lights
use `opaa_*` metrics only (never raw `gen_ai_*`), latencies are percentiles, nothing is broken down per
person, space, library or document. Usage: python3 build_dashboards.py OUTPUT_DIR
"""
import json
import os
import sys

DS = {"type": "prometheus", "uid": "prometheus"}
I = 'instance=~"$instance"'

# Status colours: light-scheme role colours of the OPAA design guidelines; white text reaches >= 4.5:1 on each
OK, WARN, CRIT, GREY = "#11774F", "#8F5A00", "#CE2C31", "#5A6270"
NAVY, BLUE = "#012142", "#0B6FBC"
STATUS_MAP = [{"type": "value", "options": {
    "-1": {"text": "nicht eingerichtet", "color": GREY, "index": 0},
    "0": {"text": "in Ordnung", "color": OK, "index": 1},
    "1": {"text": "prüfen", "color": WARN, "index": 2},
    "2": {"text": "gestört", "color": CRIT, "index": 3}}}]
STATUS_THR = [{"color": GREY, "value": None}, {"color": OK, "value": 0}, {"color": WARN, "value": 1}, {"color": CRIT, "value": 2}]
HANDBUCH = "https://opaa.example/handbuch/betrieb/alarme/"

_id = [0]


def nid():
    _id[0] += 1
    return _id[0]


def gp(x, y, w, h):
    return {"x": x, "y": y, "w": w, "h": h}


def tgt(expr, legend="", ref="A", instant=False, fmt=None, interval=None):
    t = {"refId": ref, "datasource": DS, "expr": expr, "legendFormat": legend or "__auto",
         "range": not instant, "instant": instant, "editorMode": "code"}
    if fmt:
        t["format"] = fmt
    if interval:
        t["interval"] = interval
    return t


def targets(*items, instant=False, fmt=None):
    refs = "ABCDEFGHIJKLMNOP"
    return [tgt(e, l, refs[i], instant=instant, fmt=fmt) for i, (e, l) in enumerate(items)]


def thr(*steps):
    """thr(OK, (WARN, 1), (CRIT, 2)) -> threshold steps."""
    out = [{"color": steps[0], "value": None}]
    for color, value in steps[1:]:
        out.append({"color": color, "value": value})
    return {"mode": "absolute", "steps": out}


# ------------------------------------------------------------------ panels

def text(title, html, pos, transparent=True):
    return {"id": nid(), "type": "text", "title": title, "gridPos": pos, "transparent": transparent,
            "options": {"mode": "html", "content": html}}


def banner(title, subtitle, pos):
    html = (f'<div style="background:{NAVY};color:#fff;padding:10px 18px;border-radius:6px;display:flex;'
            f'justify-content:space-between;align-items:center;gap:16px;height:100%;box-sizing:border-box">'
            f'<span style="font-size:20px;font-weight:600;letter-spacing:.2px">{title}</span>'
            f'<span style="font-size:13px;opacity:.95">{subtitle}</span></div>')
    return text("", html, pos)


def status_stat(title, desc, items, pos, links=None, text_mode="value_and_name", min_width=None, font=None):
    """Stat panel whose values are status codes -1/0/1/2 shown as words on coloured tiles."""
    p = {"id": nid(), "type": "stat", "title": title, "description": desc, "gridPos": pos, "datasource": DS,
         "targets": targets(*items, instant=True),
         "fieldConfig": {"defaults": {"mappings": STATUS_MAP, "thresholds": {"mode": "absolute", "steps": STATUS_THR},
                                      "color": {"mode": "thresholds"}, "noValue": "keine Daten",
                                      "links": links or []}, "overrides": []},
         "options": {"reduceOptions": {"calcs": ["lastNotNull"], "fields": "", "values": False},
                     "colorMode": "background_solid", "graphMode": "none", "textMode": text_mode,
                     "justifyMode": "center", "orientation": "auto", "wideLayout": True,
                     "showPercentChange": False, "text": {}}}
    if min_width:
        p["options"]["minVizWidth"] = min_width
    if font:
        p["options"]["text"] = font
    return p


def stat(title, desc, items, pos, unit="short", thresholds=None, decimals=None, color_mode="background_solid",
         overrides=None, no_value="–", mappings=None, text_mode="auto", graph="none", links=None, font=None):
    d = {"unit": unit, "thresholds": thresholds or thr("text"), "color": {"mode": "thresholds"},
         "noValue": no_value, "mappings": mappings or [], "links": links or []}
    if decimals is not None:
        d["decimals"] = decimals
    return {"id": nid(), "type": "stat", "title": title, "description": desc, "gridPos": pos, "datasource": DS,
            "targets": targets(*items, instant=(graph == "none")),
            "fieldConfig": {"defaults": d, "overrides": overrides or []},
            "options": {"reduceOptions": {"calcs": ["lastNotNull"], "fields": "", "values": False},
                        "colorMode": color_mode, "graphMode": graph, "textMode": text_mode,
                        "justifyMode": "center", "orientation": "auto", "wideLayout": True,
                        "showPercentChange": False, "text": font or {}}}


def ov(name, **props):
    """Override by field display name: ov("Chat", unit="s", thresholds=..., decimals=1)."""
    m = {"unit": "unit", "thresholds": "thresholds", "decimals": "decimals", "color": "color",
         "mappings": "mappings", "links": "links", "noValue": "noValue", "displayName": "displayName",
         "min": "min", "max": "max", "custom.cellOptions": "custom.cellOptions", "custom.width": "custom.width",
         "custom.drawStyle": "custom.drawStyle", "custom.fillOpacity": "custom.fillOpacity",
         "custom.lineStyle": "custom.lineStyle", "custom.axisPlacement": "custom.axisPlacement",
         "custom.stacking": "custom.stacking", "custom.lineWidth": "custom.lineWidth"}
    return {"matcher": {"id": "byName", "options": name},
            "properties": [{"id": m[k.replace("__", ".")], "value": v} for k, v in props.items()]}


def fixed(color):
    return {"mode": "fixed", "fixedColor": color}


def ts(title, desc, items, pos, unit="short", stack=False, bars=False, thresholds=None, thr_style="off",
       overrides=None, decimals=None, interval=None, min_=None, max_=None, fill=12, legend="list", soft_max=None):
    custom = {"drawStyle": "bars" if bars else "line", "lineWidth": 2, "fillOpacity": 70 if bars else fill,
              "gradientMode": "none", "showPoints": "never", "spanNulls": 300000, "lineInterpolation": "smooth",
              "barAlignment": -1, "axisBorderShow": False, "axisPlacement": "auto", "axisSoftMin": 0,
              "stacking": {"mode": "normal" if stack else "none", "group": "A"},
              "thresholdsStyle": {"mode": thr_style}}
    if soft_max is not None:
        custom["axisSoftMax"] = soft_max
    d = {"unit": unit, "custom": custom, "color": {"mode": "palette-classic"},
         "thresholds": thresholds or thr("green")}
    if decimals is not None:
        d["decimals"] = decimals
    if min_ is not None:
        d["min"] = min_
    if max_ is not None:
        d["max"] = max_
    tg = targets(*items)
    if interval:
        for t in tg:
            t["interval"] = interval
    p = {"id": nid(), "type": "timeseries", "title": title, "description": desc, "gridPos": pos, "datasource": DS,
         "targets": tg, "fieldConfig": {"defaults": d, "overrides": overrides or []},
         "options": {"legend": {"displayMode": legend, "placement": "bottom", "showLegend": legend != "hidden"},
                     "tooltip": {"mode": "multi", "sort": "desc"}}}
    if interval:
        p["interval"] = interval
    return p


def bargauge(title, desc, items, pos, unit="short", thresholds=None, decimals=0, max_=None, mode="basic"):
    d = {"unit": unit, "thresholds": thresholds or thr(OK), "color": {"mode": "thresholds"}, "decimals": decimals,
         "min": 0}
    if max_ is not None:
        d["max"] = max_
    return {"id": nid(), "type": "bargauge", "title": title, "description": desc, "gridPos": pos, "datasource": DS,
            "targets": targets(*items, instant=True),
            "fieldConfig": {"defaults": d, "overrides": []},
            "options": {"reduceOptions": {"calcs": ["lastNotNull"], "fields": "", "values": False},
                        "orientation": "horizontal", "displayMode": mode, "valueMode": "color",
                        "namePlacement": "left", "showUnfilled": True, "sizing": "auto", "minVizHeight": 16,
                        "maxVizHeight": 28, "legend": {"showLegend": False},
                        "text": {"titleSize": 14, "valueSize": 18}}}


def table(title, desc, items, pos, transformations, overrides=None, sort=None, no_value="nichts"):
    return {"id": nid(), "type": "table", "title": title, "description": desc, "gridPos": pos, "datasource": DS,
            "targets": targets(*items, instant=True, fmt="table"),
            "transformations": transformations,
            "fieldConfig": {"defaults": {"custom": {"align": "auto", "cellOptions": {"type": "auto"}, "inspect": False},
                                         "noValue": no_value, "thresholds": thr("text")},
                            "overrides": overrides or []},
            "options": {"showHeader": True, "cellHeight": "sm", "footer": {"show": False},
                        "sortBy": sort or []}}


def row(title, y, collapsed=False, panels=None):
    return {"id": nid(), "type": "row", "title": title, "gridPos": gp(0, y, 24, 1), "collapsed": collapsed,
            "panels": panels or []}


# ------------------------------------------------------------------ PromQL building blocks

def lr(expr, key):
    return f'label_replace({expr}, "k", "{key}", "", "")'


def worst(*scores):
    """Worst status of several 0/1/2 scores; scores without data are ignored, nothing at all = 0."""
    parts = " or ".join(lr(f"({s})", f"s{i}") for i, s in enumerate(scores))
    return f'max({parts} or {lr("vector(0)", "zero")})'


def gt(expr, warn, crit):
    return f"(({expr}) > bool {warn}) + (({expr}) > bool {crit})"


def lt(expr, warn, crit):
    return f"(({expr}) < bool {warn}) + (({expr}) < bool {crit})"


def with_min(expr, volume_expr, minimum):
    """Quotients only count above a minimum volume (stakeholder review 3.2)."""
    return f"(({expr}) and on() (({volume_expr}) >= {minimum}))"


Q15 = f'sum(increase(opaa_query_count_total{{{I}}}[15m]))'
CHAT_OK_15 = with_min(f'sum(increase(opaa_query_count_total{{status="success",{I}}}[15m])) / {Q15}', Q15, 8)
LOGIN_ALL_15 = f'sum(increase(opaa_auth_logins_total{{result=~"success|idp_error",{I}}}[15m]))'
LOGIN_OK_15 = with_min(f'sum(increase(opaa_auth_logins_total{{result="success",{I}}}[15m])) / {LOGIN_ALL_15}',
                       LOGIN_ALL_15, 5)
TTFT_P95_15 = with_min(f'histogram_quantile(0.95, sum by (le) (rate(opaa_query_time_to_first_token_seconds_bucket{{{I}}}[15m])))', Q15, 8)
Q_PER_H = f'sum(increase(opaa_query_count_total{{{I}}}[1h]))'


def deploy_score(dep):
    a = f'sum(kube_deployment_status_replicas_available{{deployment="{dep}"}})'
    s = f'sum(kube_deployment_spec_replicas{{deployment="{dep}"}})'
    return f"(2 * ({a} == bool 0)) + (({a} < bool {s}) * ({a} > bool 0))"


DB_SCORE = worst("2 * (min(pg_up) == bool 0)",
                 f'(sum(increase(hikaricp_connections_timeout_total{{{I}}}[10m])) > bool 0)')
IDP_ALL = f'sum(increase(opaa_auth_logins_total{{method="oidc",{I}}}[10m]))'
IDP_SCORE = with_min(f'2 * ((sum(increase(opaa_auth_logins_total{{method="oidc",result="idp_error",{I}}}[10m])) / {IDP_ALL}) > bool 0.5)', IDP_ALL, 10)
CHAT_THR_30 = f'sum(increase(opaa_model_requests_total{{role="chat",{I}}}[30m]))'
CHAT_THROTTLE = with_min(f'(sum(increase(opaa_model_requests_total{{role="chat",result="throttled",{I}}}[30m])) / {CHAT_THR_30}) > bool 0.2', CHAT_THR_30, 20)


def role_score(role, down_level):
    """-1 = role not configured (no series), 0 ok, down_level when unavailable."""
    base = f'({down_level} * (min(opaa_model_role_available{{role="{role}",{I}}}) == bool 0))'
    if role == "chat":
        base = worst(base, CHAT_THROTTLE).replace("max(", "max(", 1)
        base = f'({base} and on() count(opaa_model_role_available{{role="chat",{I}}}))'
    return f'{base} or on() vector(-1)'


ROLES = [("chat", 2, "Chat-Modell"), ("embedding", 2, "Embedding"), ("rerank", 1, "Rerank"),
         ("ocr", 1, "Texterkennung"), ("transcription", 1, "Transkription"), ("vision", 1, "Bildverstehen")]

BLOCK_ITEMS = [(deploy_score("opaa-backend"), "Backend"), (deploy_score("opaa-frontend"), "Frontend"),
               (DB_SCORE, "Datenbank"), (f"({IDP_SCORE}) or on() vector(0)", "Anmeldedienst (IdP)")] + \
              [(role_score(r, lvl), name) for r, lvl, name in ROLES]

OVERDUE_LIBS = 'sum(max by (connector) (opaa_indexing_libraries_overdue{connector=~"$connector"}))'
JOBS_OVERDUE = (f'count(((time() - max by (job) (opaa_scheduled_last_success_seconds{{job!~"audit-.*"}})) '
                f'> 3 * max by (job) (opaa_scheduled_expected_interval_seconds)) > 900) or vector(0)')
QUEUE_THRESHOLDS = {"mail_outbox": 1800, "source_event_batches": 3600, "ocr": 21600, "indexing": 7200}
QUEUES_LATE = "count(" + " or ".join(
    f'max by (queue) (opaa_queue_oldest_age_seconds{{queue="{q}"}}) > {t}' for q, t in QUEUE_THRESHOLDS.items()) + ") or vector(0)"
QUEUE_OLDEST = 'max(opaa_queue_oldest_age_seconds)'
DIRSYNC_AGE = 'time() - max(opaa_directory_sync_last_applied_seconds)'
DIRSYNC_PENDING = 'max(opaa_directory_sync_plan_pending)'
RUNS6 = 'sum by (connector) (increase(opaa_indexing_runs_total{connector=~"$connector"}[6h]))'
RUN_FAIL_6H = with_min(f'max(sum by (connector) (increase(opaa_indexing_runs_total{{result="failed",connector=~"$connector"}}[6h])) / {RUNS6})',
                       f'min({RUNS6})', 4)

NIGHT_SCORE = worst(f"({OVERDUE_LIBS}) > bool 0", f"({JOBS_OVERDUE}) > bool 0", f"({QUEUES_LATE}) > bool 0",
                    gt(DIRSYNC_AGE, 2700, 93600), f"{DIRSYNC_PENDING} > bool 0")

EVIDENCE = {
    "chain": "max(opaa_audit_chain_lag_seconds)",
    "unsealed": "max(opaa_audit_chain_unsealed_months)",
    "siem": "max(opaa_siem_export_lag_seconds)",
    "retention": "max(opaa_audit_retention_last_run_age_seconds)",
    "horizon": "min(opaa_audit_log_horizon_seconds)",
}
EVIDENCE_SCORE = worst(f'{EVIDENCE["chain"]} > bool 21600', f'{EVIDENCE["unsealed"]} > bool 0',
                       f'{EVIDENCE["siem"]} > bool 3600', f'{EVIDENCE["retention"]} > bool 3024000',
                       lt(EVIDENCE["horizon"], 1209600, 172800))

CHAT_SCORE = worst(lt(CHAT_OK_15, 0.99, 0.95), gt(TTFT_P95_15, 3, 10), lt(LOGIN_OK_15, 0.98, 0.9))
RAG_Q30 = f'sum(increase(opaa_query_count_total{{mode="rag",{I}}}[30m]))'
SEARCH_SCORE = worst(with_min(f'2 * ((sum(increase(opaa_retrieval_empty_total{{{I}}}[30m])) / {RAG_Q30}) > bool 0.8)', RAG_Q30, 30),
                     f'2 * (min(opaa_model_role_available{{role="embedding",{I}}}) == bool 0)',
                     f'min(opaa_model_role_available{{role="rerank",{I}}}) == bool 0')
MODEL_SCORE = worst(*[f'{lvl} * (min(opaa_model_role_available{{role="{r}",{I}}}) == bool 0)' for r, lvl, _ in ROLES],
                    CHAT_THROTTLE)
INDEX_SCORE = worst(f"({OVERDUE_LIBS}) > bool 0", f"({RUN_FAIL_6H}) > bool 0.25",
                    'max(opaa_queue_oldest_age_seconds{queue="indexing"}) > bool 7200',
                    'max(opaa_queue_oldest_age_seconds{queue="ocr"}) > bool 21600')
BACKGROUND_SCORE = worst(f"({JOBS_OVERDUE}) > bool 0",
                         'max(opaa_queue_oldest_age_seconds{queue="mail_outbox"}) > bool 1800',
                         'max(opaa_queue_oldest_age_seconds{queue="source_event_batches"}) > bool 3600',
                         gt(DIRSYNC_AGE, 2700, 93600), f"{DIRSYNC_PENDING} > bool 0",
                         'min(opaa_invalidation_bus_connected) == bool 0')
PLATFORM_SCORE = worst(deploy_score("opaa-backend"), deploy_score("opaa-frontend"), DB_SCORE,
                       f"({IDP_SCORE})", f'max(jvm_gc_live_data_size_bytes{{{I}}} / jvm_gc_max_data_size_bytes{{{I}}}) > bool 0.85',
                       'count(count by (version) (opaa_build_info)) > bool 1')
ALERT_SCORE = ('(max(2 * (ALERTS{alertstate="firing",alertname=~"Opaa.*",severity="critical"} == bool 1)'
               ' or (ALERTS{alertstate="firing",alertname=~"Opaa.*",severity="warning"} == bool 1)) or vector(0))')
SECURITY_SCORE = worst(EVIDENCE_SCORE,
                       f'sum(increase(opaa_malware_scans_total{{result="failed",{I}}}[15m])) / sum(increase(opaa_malware_scans_total{{{I}}}[15m])) > bool 0.5',
                       'max(opaa_malware_signature_age_seconds) > bool 259200',
                       f'sum(increase(opaa_rate_limit_rejected_total{{scope="global",{I}}}[15m])) > bool 0',
                       'max(ALERTS{alertstate="firing",component="security",severity!="info"}) or vector(0)')

# ------------------------------------------------------------------ display helpers
LOW = [{"type": "value", "options": {"-1": {"text": "wenig Verkehr", "color": GREY, "index": 0}}}]


def disp(expr):
    """Quotient for display: below the minimum volume it shows "wenig Verkehr" instead of disappearing."""
    return f"({expr}) or on() vector(-1)"


MIN = "suffix: min"
H = "suffix: h"
DAYS = "suffix: Tage"
EV_DISP = {"chain": EVIDENCE["chain"] + " / 60", "siem": EVIDENCE["siem"] + " / 60",
           "retention": EVIDENCE["retention"] + " / 86400", "horizon": EVIDENCE["horizon"] + " / 86400",
           "unsealed": EVIDENCE["unsealed"]}
DIRSYNC_MIN = f"({DIRSYNC_AGE}) / 60"
QUEUE_OLDEST_MIN = f"{QUEUE_OLDEST} / 60"

# ------------------------------------------------------------------ shared panels


def alerts_table(pos, title="Was feuert gerade? (aktive Alarme)"):
    sev_map = [{"type": "value", "options": {
        "critical": {"text": "sofort", "color": CRIT, "index": 0},
        "warning": {"text": "Ticket", "color": WARN, "index": 1},
        "info": {"text": "Info", "color": GREY, "index": 2}}}]
    return table(title,
                 "Aktive OPAA-Alarme aus Prometheus (ALERTS) mit Schweregrad und Zuständigkeit (component). "
                 "Klick auf den Alarmnamen öffnet das Runbook im Handbuch.",
                 [('label_join(ALERTS{alertstate="firing",alertname=~"Opaa.*"}, "betrifft", "", "connector", "role")', "")], pos,
                 [{"id": "labelsToFields", "options": {"mode": "columns"}},
                  {"id": "merge", "options": {}},
                  {"id": "organize", "options": {
                      "excludeByName": {"Time": True, "Value": True, "__name__": True, "alertstate": True,
                                        "connector": True, "role": True},
                      "indexByName": {"alertname": 0, "severity": 1, "component": 2},
                      "renameByName": {"alertname": "Alarm", "severity": "Schweregrad", "component": "Zuständig",
                                       "betrifft": "betrifft"}}}],
                 overrides=[ov("Schweregrad", mappings=sev_map, **{"custom.width": 90, "custom.cellOptions": {"type": "color-background", "mode": "basic"}}),
                            ov("Zuständig", **{"custom.width": 95}),
                            ov("Alarm", **{"custom.width": 250}, links=[{"title": "Runbook öffnen", "url": HANDBUCH + "#${__value.raw}", "targetBlank": True}])],
                 no_value="Keine aktiven Alarme")


def version_table(pos, title="Was hat sich geändert? Version je Pod"):
    return table(title,
                 "Laufende OPAA-Version und Startzeit je Backend-Pod. Unterschiedliche Versionen = Rollout läuft oder hängt "
                 "(Mischstand). Neustarts erscheinen zusätzlich als Markierung auf allen Zeitreihen.",
                 [(f'opaa_build_info{{{I}}}', ""), (f'process_start_time_seconds{{{I}}} * 1000', "")], pos,
                 [{"id": "merge", "options": {}},
                  {"id": "organize", "options": {
                      "excludeByName": {"Time": True, "Value #A": True, "__name__": True, "job": True, "application": True,
                                        "commit": True},
                      "indexByName": {"instance": 0, "version": 1, "commit": 2, "Value #B": 3},
                      "renameByName": {"instance": "Pod", "version": "Version", "commit": "Commit", "Value #B": "gestartet"}}}],
                 overrides=[ov("gestartet", unit="dateTimeAsLocalNoDateIfToday", **{"custom.width": 90}), ov("Pod", **{"custom.width": 250}),
                            ov("Version", **{"custom.cellOptions": {"type": "color-text"}}, color=fixed("blue"))])


def blocks_stat(pos, title="Wenn nicht: welcher Baustein?"):
    return status_stat(title,
                       "Je Baustein ein Zustand. Backend/Frontend: bereite Pods gegen Soll (Teilausfall = prüfen). Datenbank: "
                       "erreichbar, keine Verbindungs-Timeouts. Anmeldedienst: Anteil IdP-Fehler (ab 10 Anmeldungen). "
                       "Modellrollen aus opaa_model_role_available; nicht eingerichtete Rollen sind grau, nicht rot.",
                       BLOCK_ITEMS, pos, font={"titleSize": 13, "valueSize": 15})


def restart_annotation():
    return {"builtIn": 1, "datasource": {"type": "grafana", "uid": "-- Grafana --"}, "enable": True, "hide": True,
            "iconColor": "rgba(0, 211, 255, 1)", "name": "Annotations & Alerts", "type": "dashboard"}, {
        "datasource": DS, "enable": True, "iconColor": BLUE, "name": "Neustart / Versionswechsel",
        "expr": f'changes(process_start_time_seconds{{{I}}}[2m]) > 0', "step": "60s",
        "titleFormat": "Neustart {{instance}}", "textFormat": "Pod neu gestartet (Rollout oder Absturz)",
        "tagKeys": "instance", "useValueForTime": False}


def var_instance():
    return {"name": "instance", "label": "Pod", "type": "query", "datasource": DS,
            "query": {"query": "label_values(opaa_build_info, instance)", "refId": "v"},
            "definition": "label_values(opaa_build_info, instance)", "refresh": 2, "includeAll": True,
            "multi": True, "allValue": ".*", "current": {"text": "Alle", "value": "$__all"}, "sort": 1}


def var_connector():
    return {"name": "connector", "label": "Konnektortyp", "type": "query", "datasource": DS,
            "query": {"query": "label_values(opaa_indexing_runs_total, connector)", "refId": "v"},
            "definition": "label_values(opaa_indexing_runs_total, connector)", "refresh": 2, "includeAll": True,
            "multi": True, "allValue": ".*", "current": {"text": "Alle", "value": "$__all"}, "sort": 1}


def var_model():
    return {"name": "model", "label": "Modell", "type": "query", "datasource": DS,
            "query": {"query": "label_values(opaa_model_requests_total, model)", "refId": "v"},
            "definition": "label_values(opaa_model_requests_total, model)", "refresh": 2, "includeAll": True,
            "multi": True, "allValue": ".*", "current": {"text": "Alle", "value": "$__all"}, "sort": 1}


def dashboard(uid, title, panels, variables, desc, links=None, tags=None):
    a0, a1 = restart_annotation()
    return {"uid": uid, "title": title, "description": desc, "tags": ["opaa"] + (tags or []),
            "timezone": "Europe/Berlin", "editable": True, "graphTooltip": 1, "schemaVersion": 41,
            "time": {"from": "now-24h", "to": "now"}, "refresh": "1m", "fiscalYearStartMonth": 0,
            "annotations": {"list": [a0, a1]}, "links": links or [],
            "templating": {"list": variables}, "panels": panels, "version": 1}


def dlink(title, uid, icon="dashboard"):
    return {"title": title, "type": "link", "url": f"/d/{uid}?${{__url_time_range}}&${{instance:queryparam}}",
            "icon": icon, "targetBlank": False, "keepTime": True, "includeVars": False, "asDropdown": False, "tags": []}


def tile_link(title, uid):
    return [{"title": title, "url": f"/d/{uid}?${{__url_time_range}}&${{instance:queryparam}}"}]


PCT_OK = lambda warn, crit: thr(CRIT, (WARN, crit), (OK, warn))  # noqa: E731  (higher is better)
TTFT_THR = thr(OK, (WARN, 3), (CRIT, 10))
SEV_COLORS = [ov("Fehler", color=fixed("red")), ov("fehlgeschlagen", color=fixed("red")),
              ov("gedrosselt (429)", color=fixed("orange"))]

DESC_TTFT = ("Wartezeit vom Absenden einer Frage bis zum ersten sichtbaren Wort der Antwort, 95. Perzentil "
             "(95 % der Fragen waren schneller). Ziel: unter 3 s; über 10 s ist der Chat praktisch unbenutzbar.")
DESC_QPH = ("Gestellte Fragen pro Stunde über alle Kanäle, grob aufgelöst, als Kontext für Quoten "
            "(nachts wenig Verkehr = Quoten nicht aussagekräftig). Keine Aufteilung nach Kanal oder Person.")


# ================================================================== Variant A: traffic lights per building block

def variant_a():
    _id[0] = 0
    legend = "Grün: nichts zu tun · Gelb: Ticket, nächster Arbeitstag · Rot: sofort handeln · Grau: nicht eingerichtet"
    tiles = [
        ("Chat", CHAT_SCORE, "opaa-d-betrieb",
         "Können die Leute arbeiten? Erfolgsquote Chat (15 min, ab 8 Fragen) unter 99 % = prüfen, unter 95 % = gestört; "
         "Zeit bis zum ersten Wort p95 über 3 s / 10 s; Erfolgsquote Anmeldung unter 98 % / 90 %."),
        ("Suche & Quellen", SEARCH_SCORE, "opaa-d-wissen",
         "Gestört, wenn 80 % der Fragen keine Treffer finden (ab 30 Fragen in 30 min: Index weg, Vektorsuche ausgesetzt "
         "oder Rechtefehler) oder das Embedding-Modell ausfällt; prüfen, wenn das Rerank-Modell ausfällt."),
        ("KI-Modelle", MODEL_SCORE, "opaa-d-modelle",
         "Gestört, wenn die Rolle Chat oder Embedding nicht erreichbar ist; prüfen bei Ausfall einer anderen Rolle oder "
         "wenn mehr als 20 % der Chat-Aufrufe gedrosselt werden (429, ab 20 Aufrufen in 30 min)."),
        ("Indizierung", INDEX_SCORE, "opaa-a-indizierung",
         "Prüfen, wenn mindestens eine Bibliothek ihren Zeitplan verpasst hat (überfällig), mehr als 25 % der Läufe eines "
         "Konnektortyps in 6 h scheitern oder die Indizierungs- bzw. Texterkennungs-Warteschlange über ihrer Schwelle liegt."),
        ("Hintergrund", BACKGROUND_SCORE, "opaa-d-betrieb",
         "Hintergrundaufgaben: Scheduler-Jobs überfällig (3 × erwartetes Intervall), Mail-Ausgang älter als 30 min, "
         "Quellereignisse älter als 1 h, Verzeichnisabgleich seit über 45 min nicht angewendet oder Plan wartet auf Bestätigung, "
         "Invalidierungs-Bus getrennt."),
        ("Plattform", PLATFORM_SCORE, "opaa-d-betrieb",
         "Backend- und Frontend-Pods bereit gegen Soll, Datenbank, Anmeldedienst (IdP), Heap nach GC über 85 %, "
         "Versionsmischstand."),
        ("Nachweis", EVIDENCE_SCORE, "opaa-d-sicherheit",
         "Nachweispflichten: Prüfsummenkette (Rückstand über 6 h), Monatssiegel, SIEM-Rückstand (über 1 h), Löschlauf "
         "(über 35 Tage her), Protokollhorizont (unter 14 Tagen prüfen, unter 48 h gestört). Grün nur, wenn alles grün."),
        ("Alarme", ALERT_SCORE, "opaa-a-start",
         "Schwerster aktiver OPAA-Alarm: rot bei einem Alarm der Bereitschaft (critical), gelb bei Tickets (warning). "
         "Info-Alarme färben nicht. Liste rechts unten."),
    ]
    panels = [banner("OPAA · Lage", legend, gp(0, 0, 24, 2))]
    for i, (name, expr, uid, desc) in enumerate(tiles):
        p = status_stat(name, desc, [(expr, name)], gp(i * 3, 2, 3, 5), links=tile_link(f"Details: {name}", uid),
                        text_mode="value", font={"valueSize": 22})
        panels.append(p)
    panels += [
        ts("Fragen pro Stunde", DESC_QPH, [(f'sum(increase(opaa_query_count_total{{{I}}}[$__interval]))', "Fragen"),
                                           (f'sum(increase(opaa_query_count_total{{status="error",{I}}}[$__interval]))', "Fehler")],
           gp(0, 7, 8, 8), bars=True, interval="1h", overrides=[ov("Fragen", color=fixed("blue")), ov("Fehler", color=fixed("red"))]),
        ts("Zeit bis zum ersten Wort (p95)", DESC_TTFT,
           [(f'histogram_quantile(0.95, sum by (le) (rate(opaa_query_time_to_first_token_seconds_bucket{{{I}}}[15m])))', "p95"),
            (f'histogram_quantile(0.5, sum by (le) (rate(opaa_query_time_to_first_token_seconds_bucket{{{I}}}[15m])))', "Median")],
           gp(8, 7, 8, 8), unit="s", thresholds=TTFT_THR, thr_style="line+area", soft_max=6,
           overrides=[ov("p95", color=fixed("blue")), ov("Median", color=fixed("#8AB8FF"))], fill=0),
        alerts_table(gp(16, 7, 8, 8)),
        version_table(gp(0, 15, 12, 7)),
        stat("Über Nacht liegen geblieben?",
             "Bibliotheken, die ihren eigenen Zeitplan verpasst haben (Anzahl, ohne Namen; welche, zeigt die Systemverwaltung "
             "unter Laufstatus), überfällige Scheduler-Jobs, Warteschlangen über ihrer Schwelle und der älteste wartende Eintrag.",
             [(f'{OVERDUE_LIBS} or vector(0)', "Bibliotheken überfällig"), (JOBS_OVERDUE, "Jobs überfällig"),
              (QUEUES_LATE, "Warteschlangen im Verzug"), (QUEUE_OLDEST_MIN, "ältester Eintrag")],
             gp(12, 15, 12, 7), thresholds=thr(OK, (WARN, 1)), text_mode="value_and_name", font={"titleSize": 14, "valueSize": 40},
             overrides=[ov("ältester Eintrag", unit=MIN, thresholds=thr(OK, (WARN, 120), (CRIT, 360)), decimals=0)]),
    ]
    links = [dlink("Indizierung & Konnektoren", "opaa-a-indizierung"), dlink("Betrieb", "opaa-d-betrieb"),
             dlink("Modelle & Kosten", "opaa-d-modelle"), dlink("Sicherheit & Nachweis", "opaa-d-sicherheit")]
    return dashboard("opaa-a-start", "OPAA · A · Lage (Ampel)", panels, [var_instance(), {**var_connector(), "hide": 2}],
                     "Variante A: eine Ampel je Baustein, darunter wenige Kernkurven; Details in verlinkten Dashboards.",
                     links=links, tags=["variante-a"])


def variant_a_indexing():
    _id[0] = 100
    C = 'connector=~"$connector"'
    panels = [
        stat("Bibliotheken überfällig", "Anzahl Bibliotheken, deren letzter erfolgreicher Lauf älter ist als ihr eigener "
             "Zeitplan × Faktor (opaa_indexing_libraries_overdue). Bibliotheken ohne Zeitplan zählen nicht.",
             [(f'{OVERDUE_LIBS} or vector(0)', "")], gp(0, 0, 4, 3), thresholds=thr(OK, (WARN, 1))),
        stat("Bibliotheken mit Zeitplan", "Bestand je ausgewähltem Konnektortyp, nur Anzahl.",
             [(f'sum(max by (connector) (opaa_indexing_libraries_scheduled{{{C}}}))', "")], gp(4, 0, 4, 3), color_mode="none"),
        stat("Läufe gescheitert (6 h)", "Anteil gescheiterter Läufe in den letzten 6 h, schlechtester Konnektortyp, ab 4 Läufen. "
             "Einzelne kaputte Dateien zählen hier nicht – Läufe zeigen Quellen- und Zugangsprobleme.",
             [(RUN_FAIL_6H, "")], gp(8, 0, 4, 3), unit="percentunit", decimals=1, thresholds=thr(OK, (WARN, 0.25), (CRIT, 0.5))),
        stat("Dokumente verarbeitet", "Neu oder geändert indizierte Dokumente im gewählten Zeitraum.",
             [(f'sum(increase(opaa_indexing_documents_total{{result="processed",{C}}}[$__range]))', "")], gp(12, 0, 4, 3),
             color_mode="none", decimals=0, unit="locale"),
        stat("Ältester Eintrag Indizierung", "Wie lange der älteste Auftrag in der Indizierungs-Warteschlange schon wartet. "
             "Schwelle 2 h.", [('max(opaa_queue_oldest_age_seconds{queue="indexing"}) / 60', "")], gp(16, 0, 4, 3), unit=MIN,
             decimals=0, thresholds=thr(OK, (WARN, 120), (CRIT, 360))),
        stat("Ältester Eintrag Texterkennung", "Wie lange die älteste Seite auf OCR wartet. Schwelle 6 h.",
             [('max(opaa_queue_oldest_age_seconds{queue="ocr"}) / 60', "")], gp(20, 0, 4, 3), unit=MIN,
             decimals=0, thresholds=thr(OK, (WARN, 360), (CRIT, 720))),
        table("Je Konnektortyp (letzte 24 h)",
              "Nur Anzahlen je Konnektortyp, keine einzelnen Bibliotheken. Laufdauer als 95. Perzentil.",
              [(f'max by (connector) (opaa_indexing_libraries_overdue{{{C}}})', ""),
               (f'max by (connector) (opaa_indexing_libraries_scheduled{{{C}}})', ""),
               (f'sum by (connector) (increase(opaa_indexing_runs_total{{{C}}}[24h]))', ""),
               (f'sum by (connector) (increase(opaa_indexing_runs_total{{result="failed",{C}}}[24h]))', ""),
               (f'sum by (connector) (increase(opaa_indexing_documents_total{{result="processed",{C}}}[24h]))', ""),
               (f'histogram_quantile(0.95, sum by (connector, le) (rate(opaa_indexing_run_duration_seconds_bucket{{result="success",{C}}}[24h])))', "")],
              gp(0, 3, 16, 8),
              [{"id": "merge", "options": {}},
               {"id": "organize", "options": {"excludeByName": {"Time": True},
                                              "renameByName": {"connector": "Konnektortyp", "Value #A": "überfällig",
                                                               "Value #B": "mit Zeitplan", "Value #C": "Läufe",
                                                               "Value #D": "gescheitert", "Value #E": "Dokumente",
                                                               "Value #F": "Laufdauer p95"}}}],
              overrides=[ov("überfällig", thresholds=thr(OK, (WARN, 1)), **{"custom.cellOptions": {"type": "color-background", "mode": "basic"}}),
                         ov("Läufe", decimals=0), ov("gescheitert", decimals=0), ov("Dokumente", decimals=0),
                         ov("Laufdauer p95", unit="s", decimals=0)],
              sort=[{"displayName": "überfällig", "desc": True}]),
        bargauge("Gescheiterte Läufe nach Ursache (24 h)",
                 "Fehlerkategorie gescheiterter Läufe. auth = Zugang abgelaufen oder entzogen (Schlüsselspeicher prüfen), "
                 "source_unreachable = Quelle nicht erreichbar.",
                 [(f'sum by (failure_category) (increase(opaa_indexing_runs_total{{result="failed",{C}}}[24h]))', "{{failure_category}}")],
                 gp(16, 3, 8, 8), thresholds=thr(WARN)),
        ts("Verarbeitete Dokumente je Konnektortyp", "Neu oder geändert indizierte Dokumente pro Stunde.",
           [(f'sum by (connector) (increase(opaa_indexing_documents_total{{result="processed",{C}}}[$__interval]))', "{{connector}}")],
           gp(0, 11, 12, 7), bars=True, stack=True, interval="1h"),
        ts("Warteschlangen: ältester Eintrag", "Alter des ältesten wartenden Eintrags je Warteschlange. Gestrichelt: Schwelle "
           "Indizierung 2 h.", [('max by (queue) (opaa_queue_oldest_age_seconds{queue=~"indexing|ocr"}) / 60', "{{queue}}")],
           gp(12, 11, 12, 7), unit=MIN, thresholds=thr("transparent", ("orange", 120)), thr_style="dashed", soft_max=150),
        ts("Dauer je Verarbeitungsschritt (p95)", "95. Perzentil je Pipeline-Schritt: abrufen, zerlegen, Texterkennung, "
           "in Abschnitte teilen, Embedding, speichern. Zeigt den Engpass.",
           [(f'histogram_quantile(0.95, sum by (stage, le) (rate(opaa_indexing_stage_duration_seconds_bucket{{{I}}}[15m])))', "{{stage}}")],
           gp(0, 18, 12, 7), unit="s", fill=0),
        ts("Texterkennung: Seiten pro Stunde", "Per OCR erkannte Seiten pro Stunde.",
           [(f'sum(increase(opaa_ocr_pages_total{{{I}}}[$__interval]))', "Seiten")], gp(12, 18, 12, 7), bars=True,
           interval="1h", overrides=[ov("Seiten", color=fixed("blue"))]),
    ]
    return dashboard("opaa-a-indizierung", "OPAA · A · Indizierung & Konnektoren", panels, [var_instance(), var_connector()],
                     "Drilldown aus der Ampel „Indizierung“.", links=[dlink("Zurück zur Lage", "opaa-a-start", "arrow-left")],
                     tags=["variante-a"])


# ================================================================== Variant B: six questions

def variant_b():
    _id[0] = 200
    panels = []
    y = 0
    panels.append(row("1 · Können die Leute gerade arbeiten?", y)); y += 1
    panels += [
        stat("", "Erfolgsquote Chat und Anmeldung in den letzten 15 min (nur ab Mindestvolumen, nachts sonst „wenig Verkehr“) "
             "und Wartezeit bis zum ersten Wort (p95). Ziele: Chat ≥ 99 %, Anmeldung ≥ 98 %, erstes Wort < 3 s.",
             [(disp(CHAT_OK_15), "Chat-Fragen erfolgreich"), (disp(LOGIN_OK_15), "Anmeldungen erfolgreich"),
              (disp(TTFT_P95_15), "Erstes Wort nach (p95)")], gp(0, y, 14, 4), unit="percentunit", decimals=1,
             thresholds=PCT_OK(0.99, 0.95), text_mode="value_and_name", mappings=LOW, font={"titleSize": 16, "valueSize": 34},
             overrides=[ov("Anmeldungen erfolgreich", thresholds=PCT_OK(0.98, 0.9)),
                        ov("Erstes Wort nach (p95)", unit="s", thresholds=TTFT_THR)]),
        ts("Fragen pro Stunde", DESC_QPH, [(f'sum(increase(opaa_query_count_total{{{I}}}[$__interval]))', "Fragen")],
           gp(14, y, 10, 4), bars=True, interval="1h", legend="hidden", overrides=[ov("Fragen", color=fixed("blue"))]),
    ]
    y += 4
    panels.append(row("2 · Wenn nicht: welcher Baustein?", y)); y += 1
    panels.append(blocks_stat(gp(0, y, 24, 3), title="")); y += 3
    panels.append(row("3 · Ist über Nacht etwas liegen geblieben?", y)); y += 1
    panels.append(stat("", "Überfällige Bibliotheken je Konnektortyp (Bibliothek hat ihren eigenen Zeitplan verpasst; nur Anzahl), "
                       "Verzeichnisabgleich (zuletzt angewendet, Plan wartet auf Bestätigung der Systemverwaltung), "
                       "überfällige Scheduler-Jobs und Warteschlangen über ihrer Schwelle.",
                       [('max by (connector) (opaa_indexing_libraries_overdue) > 0', "überfällig: {{connector}}"),
                        ('(sum(max by (connector) (opaa_indexing_libraries_overdue)) == 0)', "Bibliotheken: alle pünktlich"),
                        (DIRSYNC_MIN, "Verzeichnisabgleich angewendet vor"), (DIRSYNC_PENDING, "Abgleichplan wartet"),
                        (JOBS_OVERDUE, "Jobs überfällig"), (QUEUES_LATE, "Warteschlangen im Verzug")],
                       gp(0, y, 24, 3), thresholds=thr(OK, (WARN, 1)), text_mode="value_and_name", font={"titleSize": 14, "valueSize": 24},
                       overrides=[ov("Verzeichnisabgleich angewendet vor", unit=MIN, decimals=0, thresholds=thr(OK, (WARN, 45), (CRIT, 1560))),
                                  ov("Abgleichplan wartet", mappings=[{"type": "value", "options": {"0": {"text": "nein", "index": 0}, "1": {"text": "ja", "index": 1}}}]),
                                  {"matcher": {"id": "byRegexp", "options": "überfällig: .*"},
                                   "properties": [{"id": "thresholds", "value": thr(WARN)}]}]))
    y += 3
    panels.append(row("4 · Sind die Nachweispflichten erfüllt?", y)); y += 1
    panels.append(evidence_stat(gp(0, y, 24, 3), "")); y += 3
    panels.append(row("5 · Was hat sich geändert? 6 · Was feuert gerade?", y)); y += 1
    panels += [version_table(gp(0, y, 9, 5), title="Version je Pod"), alerts_table(gp(9, y, 15, 5), title="Aktive Alarme")]
    y += 5
    cap = [
        ts("Speicher belegt", "Belegter Speicher (geteilt und privat) gegen Quote.",
           [(f'max by (kind) (opaa_storage_used_bytes{{{I}}})', "{{kind}}"), (f'max(opaa_storage_quota_bytes{{{I}}})', "Quote")],
           gp(0, y + 1, 8, 7), unit="bytes", overrides=[ov("Quote", color=fixed("red"), **{"custom.lineStyle": {"fill": "dash", "dash": [10, 10]}})]),
        ts("Heap nach GC", "Live-Daten nach Garbage Collection gegen Maximum; Schwelle 85 %.",
           [(f'jvm_gc_live_data_size_bytes{{{I}}} / jvm_gc_max_data_size_bytes{{{I}}}', "{{instance}}")],
           gp(8, y + 1, 8, 7), unit="percentunit", max_=1, thresholds=thr("transparent", ("orange", 0.85)), thr_style="dashed"),
        ts("Datenbank-Verbindungen", "Aktive und wartende Verbindungen im Pool.",
           [(f'sum(hikaricp_connections_active{{{I}}})', "aktiv"), (f'sum(hikaricp_connections_pending{{{I}}})', "wartend"),
            (f'sum(hikaricp_connections_max{{{I}}})', "Maximum")], gp(16, y + 1, 8, 7)),
    ]
    panels.append(row("Kapazität (Trend) – Kosten und Tokens unter „Modelle & Kosten“", y, collapsed=True, panels=cap))
    return dashboard("opaa-b-fragen", "OPAA · B · Sechs Fragen", panels, [var_instance(), {**var_connector(), "hide": 2}],
                     "Variante B: die Startseite beantwortet sechs Fragen der Bereitschaft in fester Reihenfolge.",
                     links=[dlink("Modelle & Kosten", "opaa-d-modelle"), dlink("Indizierung", "opaa-a-indizierung")],
                     tags=["variante-b"])


def evidence_stat(pos, title="Nachweis"):
    return stat(title, "Prüfsummenkette: Rückstand (Schwelle 6 h). Monate ohne Siegel. SIEM-Export: Rückstand (Schwelle 1 h). "
                "Letzter Löschlauf (Schwelle 35 Tage). Protokollhorizont: Zeit, bis das Protokoll nicht mehr schreiben kann "
                "(unter 14 Tagen prüfen, unter 48 h sofort).",
                [(EV_DISP["chain"], "Prüfsummenkette Rückstand"), (EV_DISP["unsealed"], "Monate ohne Siegel"),
                 (EV_DISP["siem"], "SIEM-Rückstand"), (EV_DISP["retention"], "Löschlauf vor"),
                 (EV_DISP["horizon"], "Protokollhorizont")], pos, unit=MIN, decimals=0, text_mode="value_and_name",
                thresholds=thr(OK, (WARN, 360)), font={"titleSize": 14, "valueSize": 24},
                overrides=[ov("Monate ohne Siegel", unit="short", thresholds=thr(OK, (WARN, 1))),
                           ov("SIEM-Rückstand", thresholds=thr(OK, (WARN, 60))),
                           ov("Löschlauf vor", unit=DAYS, thresholds=thr(OK, (WARN, 35))),
                           ov("Protokollhorizont", unit=DAYS, thresholds=thr(CRIT, (WARN, 2), (OK, 14)))])


# ================================================================== Variant C: SLO / error budget

SLOS = [
    ("Chat verfügbar", 0.995,
     f'sum(increase(opaa_query_count_total{{status="success",{I}}}[{{w}}])) / sum(increase(opaa_query_count_total{{{I}}}[{{w}}]))',
     "Anteil erfolgreich beantworteter Fragen (Serverfehler und Modellfehler zählen als schlecht). Ziel 99,5 % über 30 Tage."),
    ("Erstes Wort < 3 s", 0.95,
     f'sum(increase(opaa_query_time_to_first_token_seconds_bucket{{le="3.0",{I}}}[{{w}}])) / sum(increase(opaa_query_time_to_first_token_seconds_count{{{I}}}[{{w}}]))',
     "Anteil der Fragen, bei denen das erste Wort nach höchstens 3 s erscheint (entspricht p95 < 3 s). Ziel 95 %."),
    ("Bibliotheken aktuell", 0.99,
     '1 - sum(max by (connector) (avg_over_time(opaa_indexing_libraries_overdue[{w}]))) / sum(max by (connector) (avg_over_time(opaa_indexing_libraries_scheduled[{w}])))',
     "Anteil der Bibliotheken mit Zeitplan, die ihren Zeitplan einhalten (nicht überfällig). Ziel 99 %."),
]


def variant_c():
    _id[0] = 300
    panels = []
    for i, (name, target, sli, desc) in enumerate(SLOS):
        x = i * 8
        rng_sli = sli.replace("{w}", "$__range")
        h1 = sli.replace("{w}", "1h")
        h6 = sli.replace("{w}", "6h")
        budget = f'1 - (1 - ({rng_sli})) / {1 - target:.4f}'
        panels.append(stat(f"{name} · Ziel {target * 100:.1f} %".replace(".", ","),
                           desc + " Werte für den gewählten Zeitraum; im Betrieb über 30 Tage.",
                           [(rng_sli, "erreicht"), (budget, "Fehlerbudget übrig")], gp(x, 0, 5, 5), unit="percentunit",
                           decimals=2, text_mode="value_and_name",
                           thresholds=thr(CRIT, (WARN, target), (OK, target + (1 - target) * 0.25)),
                           overrides=[ov("Fehlerbudget übrig", decimals=0, thresholds=thr(CRIT, (WARN, 0), (OK, 0.25)))]))
        panels.append(stat("Burn-Rate", "Wie schnell das Fehlerbudget gerade verbraucht wird: 1 = genau im Plan, "
                           "14,4 über 1 h = Budget eines Monats in 2 Tagen weg (sofort), 6 über 6 h = Ticket.",
                           [(f'(1 - ({h1})) / {1 - target:.4f}', "1 h"), (f'(1 - ({h6})) / {1 - target:.4f}', "6 h")],
                           gp(x + 5, 0, 3, 5), unit="x", decimals=1, text_mode="value_and_name",
                           thresholds=thr(OK, (WARN, 1), (CRIT, 6))))
    burn = []
    for i, (name, target, sli, _) in enumerate(SLOS):
        b = f'(1 - ({sli.replace("{w}", "1h")})) / {1 - target:.4f}'
        if i < 2:
            b = with_min(b, f'sum(increase(opaa_query_count_total{{{I}}}[1h]))', 30)
        burn.append((b, name))
    panels += [
        ts("Burn-Rate je Ziel (1 h)", "Verbrauch des Fehlerbudgets über die Zeit. Gestrichelt: 1× (im Plan), 6× (Ticket), "
           "14,4× (sofort). Werte unter 1 sind gut. Chat-Ziele erst ab 30 Fragen pro Stunde.", burn, gp(0, 5, 16, 6), unit="x", decimals=1,
           thresholds=thr("transparent", ("green", 1), ("orange", 6), ("red", 14.4)), thr_style="dashed", fill=0, soft_max=8,
           overrides=[ov("Chat verfügbar", color=fixed("blue")), ov("Erstes Wort < 3 s", color=fixed("#8AB8FF")),
                      ov("Bibliotheken aktuell", color=fixed("purple"))]),
        alerts_table(gp(16, 5, 8, 6), title="Aktive Alarme"),
        row("Ursachen", 11),
        blocks_stat(gp(0, 12, 20, 3), title="Bausteine"),
        status_stat("Nachweis", "Prüfsummenkette, Siegel, SIEM-Rückstand, Löschlauf, Protokollhorizont – grün nur, wenn alles grün. "
                    "Einzelwerte im Bereich Sicherheit & Nachweis.", [(EVIDENCE_SCORE, "Nachweis")], gp(20, 12, 4, 3),
                    links=tile_link("Sicherheit & Nachweis", "opaa-d-sicherheit"), text_mode="value", font={"valueSize": 18}),
        ts("Gescheiterte Fragen nach Ursache", "Fehlgeschlagene Fragen, gedrosselte Modellaufrufe (429) und Zeitüberschreitungen "
           "pro Stunde.", [(f'sum(increase(opaa_query_count_total{{status="error",{I}}}[$__interval]))', "Fehler"),
                           (f'sum(increase(opaa_model_requests_total{{role="chat",result="throttled",{I}}}[$__interval]))', "gedrosselt (429)"),
                           (f'sum(increase(opaa_query_aborted_total{{cause="timeout",{I}}}[$__interval]))', "Zeitüberschreitung")],
           gp(0, 15, 8, 7), bars=True, interval="15m", overrides=SEV_COLORS + [ov("Zeitüberschreitung", color=fixed("purple"))]),
        ts("Erstes Wort: Median und p95", DESC_TTFT,
           [(f'histogram_quantile(0.95, sum by (le) (rate(opaa_query_time_to_first_token_seconds_bucket{{{I}}}[15m])))', "p95"),
            (f'histogram_quantile(0.5, sum by (le) (rate(opaa_query_time_to_first_token_seconds_bucket{{{I}}}[15m])))', "Median")],
           gp(8, 15, 8, 7), unit="s", thresholds=TTFT_THR, thr_style="line+area", soft_max=6, fill=0,
           overrides=[ov("p95", color=fixed("blue")), ov("Median", color=fixed("#8AB8FF"))]),
        bargauge("Überfällige Bibliotheken je Konnektortyp", "Anzahl Bibliotheken je Konnektortyp, die ihren Zeitplan "
                 "verpasst haben, gegen die Anzahl mit Zeitplan. Keine Namen.",
                 [('max by (connector) (opaa_indexing_libraries_overdue)', "{{connector}}")], gp(16, 15, 8, 7),
                 thresholds=thr(OK, (WARN, 1)), max_=3),
    ]
    return dashboard("opaa-c-slo", "OPAA · C · Ziele & Fehlerbudget", panels, [var_instance(), {**var_connector(), "hide": 2}],
                     "Variante C: drei Service-Ziele mit Fehlerbudget und Burn-Rate oben, Ursachen darunter.",
                     links=[dlink("Indizierung", "opaa-a-indizierung"), dlink("Modelle & Kosten", "opaa-d-modelle")],
                     tags=["variante-c"])


# ================================================================== Variant D: areas / audiences

AREAS = [
    ("Betrieb & Bereitschaft", "opaa-d-betrieb", worst(CHAT_SCORE, PLATFORM_SCORE, BACKGROUND_SCORE),
     "Für die Bereitschaft: läuft OPAA, sind alle Bausteine da, hängt etwas im Hintergrund?",
     [(disp(CHAT_OK_15), "Chat erfolgreich (15 min)"), (disp(TTFT_P95_15), "Erstes Wort (p95)")],
     [ov("Chat erfolgreich (15 min)", unit="percentunit", decimals=1, thresholds=PCT_OK(0.99, 0.95), mappings=LOW),
      ov("Erstes Wort (p95)", unit="s", decimals=1, thresholds=TTFT_THR, mappings=LOW)]),
    ("Wissensbestand & Bibliotheken", "opaa-d-wissen", INDEX_SCORE,
     "Für die Systemverwaltung und Fachbetreuung: sind die Bibliotheken aktuell, kommt alles an?",
     [(f'{OVERDUE_LIBS} or vector(0)', "Bibliotheken überfällig"), ('max(opaa_queue_oldest_age_seconds{queue=~"indexing|ocr"}) / 60', "ältester Auftrag")],
     [ov("Bibliotheken überfällig", thresholds=thr(OK, (WARN, 1))),
      ov("ältester Auftrag", unit=MIN, decimals=0, thresholds=thr(OK, (WARN, 120), (CRIT, 360)))]),
    ("KI-Modelle & Kosten", "opaa-d-modelle", MODEL_SCORE,
     "Für Modellbetrieb und Steuerung: sind die Modelle erreichbar, wie schnell, was kostet es? Kosten nur im Bereich.",
     [(f'count(opaa_model_role_available{{{I}}} == 1) / count(opaa_model_role_available{{{I}}})', "Rollen bereit"),
      (disp(with_min(f'sum(increase(opaa_model_requests_total{{role="chat",result="throttled",{I}}}[30m])) / {CHAT_THR_30}', CHAT_THR_30, 20)), "Chat gedrosselt (30 min)")],
     [ov("Rollen bereit", unit="percentunit", decimals=0, thresholds=PCT_OK(1, 0.5)),
      ov("Chat gedrosselt (30 min)", unit="percentunit", decimals=1, thresholds=thr(OK, (WARN, 0.2)), mappings=LOW)]),
    ("Sicherheit & Nachweis", "opaa-d-sicherheit", SECURITY_SCORE,
     "Für Informationssicherheit und Revision: Viren, Anmeldungen, Schutzgrenzen, Nachweispflichten.",
     [(f'sum(increase(opaa_malware_scans_total{{result="infected",{I}}}[24h]))', "Virenfunde (24 h)"),
      (EVIDENCE_SCORE, "Nachweispflichten")],
     [ov("Virenfunde (24 h)", decimals=0, thresholds=thr(OK, (GREY, 1))),
      ov("Nachweispflichten", mappings=STATUS_MAP, thresholds={"mode": "absolute", "steps": STATUS_THR})]),
]


def variant_d_start():
    _id[0] = 400
    panels = [banner("OPAA · Wegweiser", "Ein Bereich je Zuständigkeit – Klick auf eine Kachel öffnet den Bereich", gp(0, 0, 24, 2))]
    for i, (name, uid, score, desc, kpis, kov) in enumerate(AREAS):
        x = i * 6
        panels.append(status_stat(name, desc + " Die Farbe ist der schlechteste Zustand im Bereich.", [(score, name)],
                                  gp(x, 2, 6, 6), links=tile_link(f"Bereich öffnen: {name}", uid), text_mode="value",
                                  font={"valueSize": 30}))
        panels.append(stat("", desc, kpis, gp(x, 8, 6, 4), text_mode="value_and_name", overrides=kov,
                           links=tile_link(f"Bereich öffnen: {name}", uid), font={"titleSize": 12, "valueSize": 22}))
    panels += [alerts_table(gp(0, 12, 15, 9)), version_table(gp(15, 12, 9, 9))]
    links = [dlink(n, u) for n, u, *_ in AREAS]
    return dashboard("opaa-d-start", "OPAA · D · Wegweiser", panels, [var_instance(), {**var_connector(), "hide": 2}],
                     "Variante D: gemeinsame Startseite mit Status je Bereich, darunter je Bereich ein schlankes Dashboard.",
                     links=links, tags=["variante-d"])


def back_d():
    return [dlink("Wegweiser", "opaa-d-start", "arrow-left")] + [dlink(n, u) for n, u, *_ in AREAS]


def variant_d_betrieb():
    _id[0] = 500
    panels = [
        blocks_stat(gp(0, 0, 24, 3), title="Bausteine"),
        stat("Nutzerseite", "Erfolgsquoten der letzten 15 min (ab Mindestvolumen) und Wartezeit bis zum ersten Wort (p95).",
             [(disp(CHAT_OK_15), "Chat erfolgreich"), (disp(LOGIN_OK_15), "Anmeldung erfolgreich"), (disp(TTFT_P95_15), "Erstes Wort (p95)")],
             gp(0, 3, 12, 4), unit="percentunit", decimals=1, thresholds=PCT_OK(0.99, 0.95), text_mode="value_and_name",
             mappings=LOW, font={"titleSize": 14, "valueSize": 26}, overrides=[ov("Anmeldung erfolgreich", thresholds=PCT_OK(0.98, 0.9)),
                                                  ov("Erstes Wort (p95)", unit="s", thresholds=TTFT_THR)]),
        stat("Hintergrund", "Scheduler-Jobs überfällig (3 × erwartetes Intervall), Warteschlangen über ihrer jeweiligen Schwelle, "
             "Verzeichnisabgleich, Invalidierungs-Bus.",
             [(JOBS_OVERDUE, "Jobs überfällig"), (QUEUES_LATE, "Warteschlangen im Verzug"),
              (DIRSYNC_MIN, "Abgleich angewendet vor"), ('min(opaa_invalidation_bus_connected)', "Invalidierungs-Bus")],
             gp(12, 3, 12, 4), thresholds=thr(OK, (WARN, 1)), text_mode="value_and_name", font={"titleSize": 14, "valueSize": 26},
             overrides=[ov("Abgleich angewendet vor", unit=MIN, decimals=0, thresholds=thr(OK, (WARN, 45), (CRIT, 1560))),
                        ov("Invalidierungs-Bus", thresholds=thr(CRIT, (OK, 1)), mappings=[{"type": "value", "options": {
                            "1": {"text": "verbunden", "index": 0}, "0": {"text": "getrennt", "index": 1}}}])]),
        ts("Antwortzeit je Route (p95, ohne Chat-Stream)", "95. Perzentil der HTTP-Antwortzeit je Route. Der Chat-Stream "
           "(Antwort wird gestreamt) steht unter „Erstes Wort“.",
           [(f'histogram_quantile(0.95, sum by (uri, le) (rate(http_server_requests_seconds_bucket{{uri!="/api/v1/query/stream",{I}}}[5m])))', "{{uri}}")],
           gp(0, 7, 12, 7), unit="s", fill=0),
        ts("Serverfehler (5xx) je Pod", "HTTP-Antworten mit Status 5xx pro Minute.",
           [(f'sum by (instance) (rate(http_server_requests_seconds_count{{status=~"5..",{I}}}[5m])) * 60', "{{instance}}")],
           gp(12, 7, 12, 7), unit="short", decimals=1),
        ts("Heap nach GC", "Live-Daten nach Garbage Collection gegen Maximum; Schwelle 85 %.",
           [(f'jvm_gc_live_data_size_bytes{{{I}}} / jvm_gc_max_data_size_bytes{{{I}}}', "{{instance}}")],
           gp(0, 14, 8, 7), unit="percentunit", max_=1, thresholds=thr("transparent", ("orange", 0.85)), thr_style="dashed"),
        ts("Datenbank-Verbindungen", "Aktive und wartende Verbindungen im Pool, Verbindungs-Timeouts.",
           [(f'sum(hikaricp_connections_active{{{I}}})', "aktiv"), (f'sum(hikaricp_connections_pending{{{I}}})', "wartend"),
            (f'sum(increase(hikaricp_connections_timeout_total{{{I}}}[5m]))', "Timeouts")], gp(8, 14, 8, 7),
           overrides=[ov("Timeouts", color=fixed("red"))]),
        table("Hintergrundjobs", "Je Job: letzter Erfolg (über alle Pods, ShedLock), erwartetes Intervall, Fehler in 24 h.",
              [('(time() - max by (job) (opaa_scheduled_last_success_seconds)) / 60', ""),
               ('max by (job) (opaa_scheduled_expected_interval_seconds) / 60', ""),
               ('sum by (job) (increase(opaa_scheduled_failures_total[24h]))', "")], gp(16, 14, 8, 7),
              [{"id": "merge", "options": {}},
               {"id": "organize", "options": {"excludeByName": {"Time": True}, "renameByName": {
                   "job": "Job", "Value #A": "zuletzt vor", "Value #B": "Intervall", "Value #C": "Fehler 24 h"}}}],
              overrides=[ov("zuletzt vor", unit=MIN, decimals=0, **{"custom.width": 85}), ov("Intervall", unit=MIN, decimals=0, **{"custom.width": 80}),
                         ov("Fehler 24 h", decimals=0, thresholds=thr(OK, (WARN, 1)),
                            **{"custom.cellOptions": {"type": "color-text"}})]),
    ]
    return dashboard("opaa-d-betrieb", "OPAA · D · Betrieb & Bereitschaft", panels, [var_instance()],
                     "Bereich Betrieb: Bausteine, Nutzerseite, Hintergrund, Plattform.", links=back_d(), tags=["variante-d"])


def variant_d_wissen():
    _id[0] = 600
    C = 'connector=~"$connector"'
    panels = [
        stat("Bibliotheken mit Zeitplan", "Anzahl Bibliotheken mit Zeitplan (ausgewählte Konnektortypen).",
             [(f'sum(max by (connector) (opaa_indexing_libraries_scheduled{{{C}}}))', "")], gp(0, 0, 4, 4), color_mode="none"),
        stat("davon überfällig", "Bibliotheken, die ihren eigenen Zeitplan verpasst haben. Welche, zeigt die Systemverwaltung "
             "unter Laufstatus.", [(f'{OVERDUE_LIBS} or vector(0)', "")], gp(4, 0, 4, 4), thresholds=thr(OK, (WARN, 1))),
        stat("Dokumente neu oder geändert", "Im gewählten Zeitraum indiziert.",
             [(f'sum(increase(opaa_indexing_documents_total{{result="processed",{C}}}[$__range]))', "")], gp(8, 0, 4, 4),
             color_mode="none", decimals=0, unit="locale"),
        stat("Nicht verarbeitbar", "Dokumente, die im Zeitraum nicht verarbeitet werden konnten (z. B. defekte Datei, "
             "unbekanntes Format). Einzelne Fälle sind normal.",
             [(f'sum(increase(opaa_indexing_documents_total{{result="failed",{C}}}[$__range]))', "")], gp(12, 0, 4, 4),
             color_mode="none", decimals=0, unit="locale"),
        stat("Seiten per Texterkennung", "Im Zeitraum per OCR erkannte Seiten.",
             [(f'sum(increase(opaa_ocr_pages_total{{{I}}}[$__range]))', "")], gp(16, 0, 4, 4), color_mode="none", decimals=0, unit="locale"),
        stat("Speicher belegt", "Belegter Speicher gegen Quote.",
             [('sum(max by (kind) (opaa_storage_used_bytes)) / max(opaa_storage_quota_bytes)', "")], gp(20, 0, 4, 4),
             unit="percentunit", decimals=0, thresholds=thr(OK, (WARN, 0.8), (CRIT, 0.95))),
        bargauge("Überfällige Bibliotheken je Konnektortyp", "Nur Anzahl je Konnektortyp.",
                 [(f'max by (connector) (opaa_indexing_libraries_overdue{{{C}}})', "{{connector}}")], gp(0, 4, 8, 9),
                 thresholds=thr(OK, (WARN, 1)), max_=3),
        ts("Neue und geänderte Dokumente je Konnektortyp", "Pro Stunde.",
           [(f'sum by (connector) (increase(opaa_indexing_documents_total{{result="processed",{C}}}[$__interval]))', "{{connector}}")],
           gp(8, 4, 16, 9), bars=True, stack=True, interval="1h"),
        ts("Rückstand: ältester Auftrag", "Wie lange der älteste Auftrag in Indizierung und Texterkennung wartet. "
           "Schwellen 2 h bzw. 6 h.", [('max by (queue) (opaa_queue_oldest_age_seconds{queue=~"indexing|ocr"}) / 60', "{{queue}}")],
           gp(0, 13, 8, 8), unit=MIN),
        ts("Gescheiterte Läufe je Konnektortyp", "Pro Stunde. Dauerhaft gescheiterte Läufe deuten auf abgelaufene Zugänge.",
           [(f'sum by (connector) (increase(opaa_indexing_runs_total{{result="failed",{C}}}[$__interval]))', "{{connector}}")],
           gp(8, 13, 8, 8), bars=True, stack=True, interval="1h"),
        ts("Speicher gegen Quote", "Belegter Speicher (geteilt, privat) und Quote.",
           [('max by (kind) (opaa_storage_used_bytes)', "{{kind}}"), ('max(opaa_storage_quota_bytes)', "Quote")],
           gp(16, 13, 8, 8), unit="bytes", stack=False,
           overrides=[ov("Quote", color=fixed("red"), **{"custom.lineStyle": {"fill": "dash", "dash": [10, 10]}})]),
    ]
    return dashboard("opaa-d-wissen", "OPAA · D · Wissensbestand & Bibliotheken", panels, [var_instance(), var_connector()],
                     "Bereich Wissensbestand: Aktualität, Zufluss, Rückstand, Speicher.", links=back_d(), tags=["variante-d"])


def variant_d_modelle():
    _id[0] = 700
    M = 'model=~"$model"'
    role_items = [(role_score(r, lvl), name) for r, lvl, name in ROLES]
    panels = [
        status_stat("Modellrollen", "Erreichbarkeit je Rolle (opaa_model_role_available). Nicht eingerichtete Rollen grau.",
                    role_items, gp(0, 0, 12, 4), font={"titleSize": 13, "valueSize": 16}),
        stat("Steuerung (gewählter Zeitraum)", "Abgeleitete Kosten aus Tokens × Preis der Modellkonfiguration "
             "(opaa_model_cost_euros_total), Tokens aller Zwecke, Hochrechnung auf 30 Tage, gedrosselte Aufrufe.",
             [(f'sum(increase(opaa_model_cost_euros_total{{{M},{I}}}[$__range]))', "Kosten"),
              (f'sum(increase(opaa_model_tokens_total{{{M},{I}}}[$__range])) / 1e6', "Tokens"),
              (f'sum(increase(opaa_model_cost_euros_total{{{M},{I}}}[$__range])) / ($__range_s / 86400) * 30', "Hochrechnung 30 Tage"),
              (f'sum(increase(opaa_model_requests_total{{result="throttled",{M},{I}}}[$__range]))', "gedrosselt")],
             gp(12, 0, 12, 4), color_mode="none", text_mode="value_and_name",
             overrides=[ov("Kosten", unit="suffix: €", decimals=2), ov("Hochrechnung 30 Tage", unit="suffix: €", decimals=0),
                        ov("Tokens", unit="suffix: Mio.", decimals=1), ov("gedrosselt", decimals=0)]),
        ts("Modell-Antwortzeit (p95) je Modell", "95. Perzentil der Aufrufdauer je Modell (gen_ai_client_operation_seconds, "
           "nur Anzeige, keine Ampel).",
           [(f'histogram_quantile(0.95, sum by (gen_ai_request_model, le) (rate(gen_ai_client_operation_seconds_bucket{{error_type="none",gen_ai_request_model=~"$model",{I}}}[10m])))', "{{gen_ai_request_model}}")],
           gp(0, 4, 12, 8), unit="s", fill=0),
        ts("Fehlgeschlagene Modellaufrufe nach Ursache", "Ausgehende Modellaufrufe (Chat, Embedding, Rerank, Texterkennung, "
           "Transkription), die nicht erfolgreich waren, pro Stunde nach Ursache.",
           [(f'sum by (result) (increase(opaa_model_requests_total{{result!="success",{M},{I}}}[$__interval]))', "{{result}}")],
           gp(12, 4, 12, 8), stack=True, bars=True, interval="15m",
           overrides=[ov("success", color=fixed("green"), displayName="erfolgreich"),
                      ov("throttled", color=fixed("orange"), displayName="gedrosselt (429)"),
                      ov("timeout", color=fixed("purple"), displayName="Zeitüberschreitung"),
                      ov("5xx", color=fixed("red"), displayName="Serverfehler")]),
        ts("Tokens pro Stunde nach Zweck", "Antwort, Zerlegung, Titel, Notiz, Embedding.",
           [(f'sum by (purpose) (increase(opaa_model_tokens_total{{{M},{I}}}[$__interval]))', "{{purpose}}")],
           gp(0, 12, 12, 9), bars=True, stack=True, interval="1h"),
        ts("Kosten pro Stunde nach Modell", "Abgeleitete Kosten in Euro.",
           [(f'sum by (model) (increase(opaa_model_cost_euros_total{{{M},{I}}}[$__interval]))', "{{model}}")],
           gp(12, 12, 12, 9), bars=True, stack=True, interval="1h", unit="currencyEUR", decimals=2),
    ]
    return dashboard("opaa-d-modelle", "OPAA · D · KI-Modelle & Kosten", panels, [var_instance(), var_model()],
                     "Bereich Modelle: Erreichbarkeit, Antwortzeit, Drosselung, Tokens, Kosten.", links=back_d(), tags=["variante-d"])


def variant_d_sicherheit():
    _id[0] = 800
    panels = [
        evidence_stat(gp(0, 0, 24, 4), "Nachweispflichten"),
        stat("Schutzfunktionen (24 h)", "Virenfunde, gescheiterte Virenprüfungen, Alter der Virensignaturen (Schwelle 3 Tage), "
             "Abweisungen durch das globale Rate-Limit, Kontosperrungen, Abflussalarme externer Zugänge.",
             [(f'sum(increase(opaa_malware_scans_total{{result="infected",{I}}}[24h]))', "Virenfunde"),
              (f'sum(increase(opaa_malware_scans_total{{result="failed",{I}}}[24h]))', "Prüfung gescheitert"),
              ('max(opaa_malware_signature_age_seconds) / 3600', "Signaturen alt"),
              (f'sum(increase(opaa_rate_limit_rejected_total{{scope="global",{I}}}[24h]))', "Rate-Limit global"),
              (f'sum(increase(opaa_auth_local_account_lockout_total{{{I}}}[24h]))', "Kontosperrungen"),
              (f'sum(increase(opaa_external_access_alarms_total{{{I}}}[24h]))', "Abflussalarme")],
             gp(0, 4, 24, 4), decimals=0, thresholds=thr(OK, (WARN, 1)), text_mode="value_and_name",
             overrides=[ov("Signaturen alt", unit=H, thresholds=thr(OK, (WARN, 72))),
                        ov("Virenfunde", thresholds=thr(OK, (GREY, 1))), ov("Kontosperrungen", thresholds=thr(OK, (GREY, 1)))]),
        ts("Anmeldungen pro Stunde", "Erfolgreiche und fehlgeschlagene Anmeldungen (lokal und OIDC), IdP-Fehler.",
           [(f'sum by (result) (increase(opaa_auth_logins_total{{{I}}}[$__interval]))', "{{result}}")],
           gp(0, 8, 12, 7), bars=True, stack=True, interval="15m",
           overrides=[ov("success", color=fixed("green"), displayName="erfolgreich"),
                      ov("failure", color=fixed("orange"), displayName="fehlgeschlagen"),
                      ov("idp_error", color=fixed("red"), displayName="IdP-Fehler")]),
        ts("Abgewiesen durch Rate-Limit", "Abweisungen je Grenze und Geltungsbereich pro Stunde.",
           [(f'sum by (limit, scope) (increase(opaa_rate_limit_rejected_total{{{I}}}[$__interval]))', "{{limit}} ({{scope}})")],
           gp(12, 8, 12, 7), bars=True, stack=True, interval="15m"),
        ts("Virenprüfung", "Dauer der Prüfung (p95) und Prüfungen pro Minute.",
           [(f'histogram_quantile(0.95, sum by (le) (rate(opaa_malware_scan_duration_seconds_bucket{{{I}}}[10m])))', "Dauer p95"),
            (f'sum(rate(opaa_malware_scans_total{{{I}}}[10m])) * 60', "Prüfungen/min")], gp(0, 15, 12, 7),
           overrides=[ov("Dauer p95", unit="s", **{"custom.axisPlacement": "left"}),
                      ov("Prüfungen/min", **{"custom.axisPlacement": "right"}, color=fixed("#8AB8FF"))]),
        ts("SIEM-Rückstand und Prüfsummenkette", "Rückstand des SIEM-Exports und der Prüfsummenkette. Schwellen 1 h bzw. 6 h.",
           [('max(opaa_siem_export_lag_seconds) / 60', "SIEM-Export"), ('max(opaa_audit_chain_lag_seconds) / 60', "Prüfsummenkette")],
           gp(12, 15, 12, 7), unit=MIN, decimals=1),
    ]
    return dashboard("opaa-d-sicherheit", "OPAA · D · Sicherheit & Nachweis", panels, [var_instance()],
                     "Bereich Informationssicherheit und Revision.", links=back_d(), tags=["variante-d"])


def main(out):
    os.makedirs(out, exist_ok=True)
    for fn, name in ((variant_a, "A-start"), (variant_a_indexing, "A-drilldown-indizierung"), (variant_b, "B-sechs-fragen"),
                     (variant_c, "C-ziele-fehlerbudget"), (variant_d_start, "D-start"), (variant_d_betrieb, "D-bereich-betrieb"),
                     (variant_d_wissen, "D-bereich-wissen"), (variant_d_modelle, "D-bereich-modelle"),
                     (variant_d_sicherheit, "D-bereich-sicherheit")):
        with open(os.path.join(out, f"{name}.json"), "w") as f:
            json.dump(fn(), f, indent=2, ensure_ascii=False)
    print("written to", out)


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "json")
