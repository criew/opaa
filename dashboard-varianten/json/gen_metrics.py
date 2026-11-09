#!/usr/bin/env python3
"""Generate 32 h of plausible OPAA metrics as OpenMetrics text for `promtool tsdb create-blocks-from openmetrics`.

Metric names follow the target catalogue (Betriebsmetriken-Analyse 2026-11-09, sections 3 and 4) in
Prometheus notation (`opaa_*`, `gen_ai_*`, `http_server_requests_*`, JVM, Hikari). The data is synthetic.

Scenario (local time Europe/Berlin, UTC+1, Monday 2026-11-09):
  * working-day load 08:00-17:00 with a lunch dip, quiet night
  * 03:12 a burst of failed local logins (brute force) with account lockouts
  * 10:30 full run of the SMB connector (2,400 documents) -> OCR and indexing backlog for ~1 h
  * 11:42 one upload flagged as infected by the virus scan
  * 14:10-14:40 the model provider throttles the chat models (HTTP 429): TTFT and error rate rise
  * SharePoint connector: every run fails with an expired credential; last success 30 h before window end

Usage: python3 gen_metrics.py OUTPUT.om
"""
import math
import sys

import numpy as np

STEP = 60
END = 1794254400            # 2026-11-09T20:00:00Z = 21:00 Berlin
START = END - 32 * 3600     # lead-in so a 24 h window ending 14:30 local plus rate() windows is covered
TS = np.arange(START, END + 1, STEP)
N = len(TS)
LOCAL_H = ((TS + 3600) % 86400) / 3600.0   # Berlin hour of day (CET, UTC+1)
rng = np.random.default_rng(20261109)

JOB = "opaa-backend"
PODS = ["opaa-backend-6f8c9d7b5-k2x4p", "opaa-backend-6f8c9d7b5-q7m9t"]
POD_SHARE = [0.52, 0.48]

series = {}  # name -> list of (labels dict, values array)


def add(name, labels, values):
    """Add a series; a second add with identical labels is summed into the first (aggregation)."""
    values = np.asarray(values, dtype=float)
    for i, (lab, v) in enumerate(series.setdefault(name, [])):
        if lab == labels:
            series[name][i] = (lab, v + values)
            return
    series[name].append((labels, values))


def stable_hash(s):
    return sum(ord(ch) * (i + 1) for i, ch in enumerate(s))


def base(pod):
    return {"job": JOB, "instance": pod, "application": "opaa"}


def sig(x):
    return 1 / (1 + np.exp(-x))


def window(h0, h1):
    """1.0 between local hours h0 and h1 (same day), else 0."""
    return ((LOCAL_H >= h0) & (LOCAL_H < h1)).astype(float)


# Working-day load factor 0..1
LOAD = (0.03 + 0.97 * sig((LOCAL_H - 7.7) * 3.2) * sig((17.2 - LOCAL_H) * 2.4)
        * (1 - 0.35 * np.exp(-(((LOCAL_H - 12.4) / 0.45) ** 2)))
        * (1 + 0.08 * np.sin(LOCAL_H * 2.1)))
LOAD = np.clip(LOAD * (1 + rng.normal(0, 0.06, N)), 0.01, None)
# The lead-in (Sunday evening) and Sunday hours are quiet
DAY = (TS >= END - 24 * 3600 + 3 * 3600)  # from 00:00 Monday local
LOAD = np.where(DAY, LOAD, 0.03 + 0.01 * rng.random(N))

INCIDENT = window(14 + 10 / 60, 14 + 40 / 60)
INCIDENT_RAMP = np.convolve(INCIDENT, np.ones(4) / 4, mode="same")
SMB_FULL = window(10.5, 11.6)

_erf = np.vectorize(math.erf)


def lognorm_cdf(x, median, sigma):
    return 0.5 * (1 + _erf((np.log(x) - np.log(median)) / (sigma * math.sqrt(2))))


def poisson(lam):
    return rng.poisson(np.clip(lam, 0, None)).astype(float)


def counter(name, labels, incr):
    add(name, labels, np.cumsum(incr))


def histogram(name, labels, n, median, sigma, buckets):
    """Classic histogram from per-step observation counts n and a log-normal latency per step."""
    median = np.broadcast_to(np.asarray(median, dtype=float), n.shape)
    for le in buckets:
        frac = lognorm_cdf(le, median, sigma)
        add(f"{name}_bucket", {**labels, "le": fmt_le(le)}, np.cumsum(n * frac))
    add(f"{name}_bucket", {**labels, "le": "+Inf"}, np.cumsum(n))
    add(f"{name}_count", labels, np.cumsum(n))
    add(f"{name}_sum", labels, np.cumsum(n * median * math.exp(sigma ** 2 / 2)))


GENERIC_B = [0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10, 30, 60, 120, 300, 600, 1800, 3600]


def summary_pair(name, labels, n, mean, sigma=0.5):
    """Latencies (`*_seconds`) become histograms so dashboards can use percentiles; other sizes stay sum/count."""
    n = np.asarray(n, dtype=float) * np.ones(N)
    if name.endswith("_seconds") and "audio" not in name:
        median = np.maximum(np.asarray(mean, dtype=float) * np.ones(N), 1e-3) / math.exp(sigma ** 2 / 2)
        histogram(name, labels, n, median, sigma, GENERIC_B)
        return
    add(f"{name}_count", labels, np.cumsum(n))
    add(f"{name}_sum", labels, np.cumsum(n * mean))


def fmt_le(le):
    return repr(float(le))


def gauge(name, labels, values):
    add(name, labels, values)


# ---------------------------------------------------------------- Chat / RAG
QUERY_PEAK = 0.16  # queries per second over all pods at peak (~9.6 per minute)
MODES = {"rag": 0.80, "no-search": 0.12, "agent": 0.08}
SOURCES = {"ui": 0.85, "mcp": 0.10, "api-token": 0.05}
ERR = 0.0008 + 0.045 * INCIDENT_RAMP
TTFT_B = [0.25, 0.5, 1, 1.5, 2, 3, 5, 8, 15, 30]
DUR_B = [1, 2, 3, 5, 8, 10, 15, 20, 30, 45, 60, 120]
MODEL_B = [0.1, 0.25, 0.5, 1, 2.5, 5, 10, 20, 30, 60]
HTTP_B = [0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10, 30, 60]

total_q = np.zeros(N)
rag_q = np.zeros(N)
for pod, share in zip(PODS, POD_SHARE):
    b = base(pod)
    pod_q = np.zeros(N)
    for mode, mshare in MODES.items():
        mode_q = np.zeros(N)
        for src, sshare in SOURCES.items():
            lam = QUERY_PEAK * LOAD * share * mshare * sshare * STEP
            n = poisson(lam)
            err = rng.binomial(n.astype(int), np.clip(ERR, 0, 1)).astype(float)
            counter("opaa_query_count_total", {**b, "mode": mode, "source": src, "status": "success"}, n - err)
            counter("opaa_query_count_total", {**b, "mode": mode, "source": src, "status": "error"}, err)
            mode_q += n
        med = {"rag": 7.0, "no-search": 4.0, "agent": 17.0}[mode] * (1 + 0.15 * LOAD) * (1 + 1.1 * INCIDENT_RAMP)
        histogram("opaa_query_duration_seconds", {**b, "mode": mode}, mode_q, med, 0.5, DUR_B)
        pod_q += mode_q
        if mode == "rag":
            rag_q += mode_q
    ttft_med = 1.0 * (1 + 0.2 * LOAD) * (1 + 2.8 * INCIDENT_RAMP)
    histogram("opaa_query_time_to_first_token_seconds", b, pod_q, ttft_med, 0.42, TTFT_B)
    for stage, mean in {"decompose": 0.6, "embed_query": 0.15, "retrieve": 0.35, "rerank": 0.5,
                        "generate": 5.4, "persist": 0.05}.items():
        m = mean * (1 + 0.15 * LOAD)
        if stage == "generate":
            m = m * (1 + 1.4 * INCIDENT_RAMP)
        summary_pair("opaa_query_stage_duration_seconds", {**b, "stage": stage}, pod_q, m)
    total_q += pod_q

for pod, share in zip(PODS, POD_SHARE):
    b = base(pod)
    rq = rag_q * share
    counter("opaa_retrieval_empty_total", b, poisson(rq * (0.022 + 0.012 * LOAD)))
    for level, f in {"supported": 0.72, "partial": 0.20, "unsupported": 0.08}.items():
        counter("opaa_query_evidence_total", {**b, "level": level}, poisson(rq * f))
    counter("opaa_query_aborted_total", {**b, "cause": "client_cancel"}, poisson(total_q * share * 0.02))
    counter("opaa_query_aborted_total", {**b, "cause": "timeout"}, poisson(total_q * share * (0.001 + 0.03 * INCIDENT_RAMP)))

# ---------------------------------------------------------------- HTTP golden signals
URIS = {  # uri: (share of calls, median seconds)
    "/api/v1/query/stream": (0.10, 7.0),
    "/api/v1/conversations": (0.35, 0.06),
    "/api/v1/search": (0.15, 0.25),
    "/api/v1/libraries/{id}/documents": (0.30, 0.09),
    "/mcp": (0.10, 0.6),
}
for pod, share in zip(PODS, POD_SHARE):
    b = base(pod)
    for uri, (ushare, med) in URIS.items():
        n_all = poisson((2.2 * LOAD + 0.02) * share * ushare * STEP)
        p500 = 0.0005 + (0.07 * INCIDENT_RAMP if uri == "/api/v1/query/stream" else 0)
        p403 = 0.006
        n5 = rng.binomial(n_all.astype(int), np.clip(p500, 0, 1)).astype(float)
        n4 = rng.binomial((n_all - n5).astype(int), p403).astype(float)
        n2 = n_all - n5 - n4
        m = med * (1 + 0.2 * LOAD) * (1 + (1.2 * INCIDENT_RAMP if uri == "/api/v1/query/stream" else 0))
        for status, outcome, n in (("200", "SUCCESS", n2), ("403", "CLIENT_ERROR", n4), ("500", "SERVER_ERROR", n5)):
            histogram("http_server_requests_seconds",
                      {**b, "uri": uri, "method": "GET" if uri != "/api/v1/query/stream" else "POST",
                       "status": status, "outcome": outcome}, n, m, 0.6, HTTP_B)

# ---------------------------------------------------------------- Models
CHAT_LARGE, CHAT_SMALL, EMB, RERANK, ASR, OCR, VISION = (
    "mistral-large-2411", "mistral-small-3.2", "bge-m3", "bge-reranker-v2-m3", "whisper-large-v3",
    "docling-serve", "pixtral-12b")
PRICE = {CHAT_LARGE: (1.8, 5.4), CHAT_SMALL: (0.09, 0.27), EMB: (0.02, 0.0), VISION: (0.15, 0.15)}  # EUR / 1M tokens

indexing_docs_total = np.zeros(N)  # filled below, used for embedding volume
throttle_frac = 0.31 * INCIDENT_RAMP

# answer generation + helper calls
calls = {
    ("answer", CHAT_LARGE): (1.0, 6200, 420),
    ("decompose", CHAT_SMALL): (1.0, 900, 120),
    ("title", CHAT_SMALL): (0.25, 600, 15),
    ("note", CHAT_SMALL): (0.3, 1500, 90),
}
for pod, share in zip(PODS, POD_SHARE):
    b = base(pod)
    q = total_q * share
    for (purpose, model), (per_q, tin, tout) in calls.items():
        n = poisson(q * per_q)
        thr = rng.binomial(n.astype(int), np.clip(throttle_frac, 0, 1)).astype(float)
        to = rng.binomial((n - thr).astype(int), 0.002).astype(float)
        ok = n - thr - to
        med = (5.2 if model == CHAT_LARGE else 0.7) * (1 + 0.15 * LOAD) * (1 + (1.3 * INCIDENT_RAMP if model == CHAT_LARGE else 0))
        lab = {**b, "gen_ai_operation_name": "chat", "gen_ai_system": "openai", "gen_ai_request_model": model}
        histogram("gen_ai_client_operation_seconds", {**lab, "error_type": "none"}, ok, med, 0.45, MODEL_B)
        histogram("gen_ai_client_operation_seconds", {**lab, "error_type": "429"}, thr, 0.4, 0.3, MODEL_B)
        histogram("gen_ai_client_operation_seconds", {**lab, "error_type": "timeout"}, to, 60, 0.05, MODEL_B)
        tin_n = ok * tin * (1 + rng.normal(0, 0.05, N))
        tout_n = ok * tout * (1 + rng.normal(0, 0.05, N))
        counter("opaa_model_tokens_total", {**b, "purpose": purpose, "token_type": "input", "model": model}, tin_n.clip(0))
        counter("opaa_model_tokens_total", {**b, "purpose": purpose, "token_type": "output", "model": model}, tout_n.clip(0))
        counter("opaa_model_cost_euros_total", {**b, "purpose": purpose, "model": model},
                (tin_n * PRICE[model][0] + tout_n * PRICE[model][1]).clip(0) / 1e6)
        add_role = "chat"
        counter("opaa_model_requests_total", {**b, "role": add_role, "model": model, "result": "success"}, ok)
        counter("opaa_model_requests_total", {**b, "role": add_role, "model": model, "result": "throttled"}, thr)
        counter("opaa_model_requests_total", {**b, "role": add_role, "model": model, "result": "timeout"}, to)
        summary_pair("opaa_model_duration_seconds", {**b, "role": add_role, "model": model}, ok, med * 1.1)
        counter("opaa_model_throttled_total", {**b, "role": "chat"}, thr)

# token usage as Spring AI reports it (sum over purposes)
for pod in PODS:
    for model in (CHAT_LARGE, CHAT_SMALL):
        for tt in ("input", "output"):
            vals = sum(v for (lab, v) in series["opaa_model_tokens_total"]
                       if lab["instance"] == pod and lab["model"] == model and lab["token_type"] == tt)
            add("gen_ai_client_token_usage_total", {**base(pod), "gen_ai_request_model": model, "gen_ai_token_type": tt}, vals)

# ---------------------------------------------------------------- Indexing & connectors
CONNECTORS = {  # name: (interval minutes, mean changed docs per tick at full load, mean run seconds, scheduled libraries)
    "confluence": (30, 38, 140, 41),
    "sharepoint": (30, 11, 60, 6),
    "smb": (60, 22, 210, 12),
    "nextcloud": (30, 14, 90, 5),
    "filesystem": (15, 6, 35, 3),
    "rss_feed": (60, 9, 20, 8),
}
SHAREPOINT_LAST_OK = END - 30 * 3600
idx_active = np.zeros(N)
idx_queue = np.zeros(N)
ocr_docs = np.zeros(N)
for c, (interval, docs, dur, libs) in CONNECTORS.items():
    tick = (((TS - START) // STEP) % interval == (stable_hash(c) % interval)).astype(float)
    run_steps = tick * libs
    if c == "sharepoint":
        # one of six SharePoint libraries fails with an expired credential since 30 h
        fail_runs = tick * 1.0
        ok_runs = run_steps - fail_runs
        last = np.full(N, SHAREPOINT_LAST_OK, dtype=float)
    else:
        fail_runs = tick * rng.binomial(libs, 0.01, N)
        ok_runs = run_steps - fail_runs
        last_ok_ts = np.where(ok_runs > 0, TS, np.nan)
        last_ok_ts[0] = START - 600
        last = np.fmax.accumulate(np.nan_to_num(last_ok_ts, nan=0))
    lam_docs = tick * docs * (0.25 + LOAD) * (ok_runs / np.maximum(run_steps, 1))
    n_docs = poisson(lam_docs)
    if c == "smb":
        full_start = int(np.argmax(SMB_FULL))
        ok_runs[full_start] += 1
        n_docs += SMB_FULL * (2400 / SMB_FULL.sum()) * (1 + rng.normal(0, 0.1, N))
    failed_docs = poisson(n_docs * 0.012)
    skipped = poisson(n_docs * 0.05)
    indexing_docs_total += n_docs
    if c in ("smb", "filesystem", "upload"):
        ocr_docs += n_docs * 0.18
    for pod, share in zip(PODS, POD_SHARE):
        b = base(pod)
        cl = {**b, "connector": c}
        counter("opaa_indexing_documents_total", {**cl, "result": "processed"}, (n_docs - failed_docs).clip(0) * share)
        counter("opaa_indexing_documents_total", {**cl, "result": "failed"}, failed_docs * share)
        counter("opaa_indexing_documents_total", {**cl, "result": "skipped"}, skipped * share)
        counter("opaa_indexing_runs_total", {**cl, "mode": "delta", "result": "success", "failure_category": "none"}, ok_runs * share)
        counter("opaa_indexing_runs_total", {**cl, "mode": "delta", "result": "failed",
                                             "failure_category": "auth" if c == "sharepoint" else "source_unreachable"}, fail_runs * share)
        summary_pair("opaa_indexing_run_duration_seconds", {**cl, "result": "success"}, ok_runs * share,
                     dur * (1 + 0.5 * LOAD) + SMB_FULL * (3900 if c == "smb" else 0))
        summary_pair("opaa_indexing_run_duration_seconds", {**cl, "result": "failed"}, fail_runs * share, 12)
        gauge("opaa_indexing_run_last_success_seconds", cl, last)
        gauge("opaa_indexing_libraries_scheduled", cl, np.full(N, float(libs)))
        gauge("opaa_indexing_libraries_overdue", cl, np.full(N, 1.0 if c == "sharepoint" else 0.0))
        counter("opaa_indexing_bytes_total", cl, n_docs * share * 420_000)
        counter("opaa_connector_requests_total", {**cl, "result": "success"}, (ok_runs * 6 + n_docs * 1.2) * share)
        counter("opaa_connector_requests_total", {**cl, "result": "auth_error"}, fail_runs * share * (1 if c == "sharepoint" else 0))
    idx_active += np.convolve(tick, np.ones(max(1, dur // STEP + 1)), "same").clip(0, 1)

# uploads (event driven)
up_docs = poisson(0.035 * LOAD * STEP)
indexing_docs_total += up_docs
ocr_docs += up_docs * 0.25
for pod, share in zip(PODS, POD_SHARE):
    b = base(pod)
    cl = {**b, "connector": "upload"}
    fd = poisson(up_docs * share * 0.02)
    counter("opaa_indexing_documents_total", {**cl, "result": "processed"}, (up_docs * share - fd).clip(0))
    counter("opaa_indexing_documents_total", {**cl, "result": "failed"}, fd)
    counter("opaa_indexing_documents_total", {**cl, "result": "skipped"}, np.zeros(N))
    counter("opaa_indexing_runs_total", {**cl, "mode": "delta", "result": "success", "failure_category": "none"}, up_docs * share)
    counter("opaa_indexing_runs_total", {**cl, "mode": "delta", "result": "failed", "failure_category": "source_unreachable"}, np.zeros(N))
    summary_pair("opaa_indexing_run_duration_seconds", {**cl, "result": "success"}, up_docs * share, 9 * (1 + LOAD))
    summary_pair("opaa_indexing_run_duration_seconds", {**cl, "result": "failed"}, np.zeros(N), 0)
    last_up = np.fmax.accumulate(np.where(up_docs > 0, TS, START - 900).astype(float))
    gauge("opaa_indexing_run_last_success_seconds", cl, last_up)
    counter("opaa_indexing_bytes_total", cl, up_docs * share * 1_100_000)

# backlog after the SMB full run
backlog = np.zeros(N)
ocr_backlog = np.zeros(N)
for i in range(1, N):
    backlog[i] = max(0, backlog[i - 1] + SMB_FULL[i] * 36 - 30 + rng.normal(0, 1)) if SMB_FULL[i] or backlog[i - 1] > 0 else 0
    ocr_backlog[i] = max(0, ocr_backlog[i - 1] + SMB_FULL[i] * 7.2 - 4.5 + rng.normal(0, 0.5)) if SMB_FULL[i] or ocr_backlog[i - 1] > 0 else 0
backlog += poisson(2 * LOAD)
ocr_backlog += poisson(1.2 * LOAD)
idx_active = np.clip(idx_active + (backlog > 5) * 2 + poisson(0.6 * LOAD), 0, 4)
idx_queue = np.clip(np.round(backlog / 60), 0, None) + poisson(0.3 * LOAD)

def oldest_age(depth, per_item):
    return np.where(depth > 0, depth * per_item + rng.uniform(5, 25, N), 0)

for pod, share in zip(PODS, POD_SHARE):
    b = base(pod)
    gauge("opaa_indexing_runs_active", b, np.round(idx_active * share + 0.2))
    gauge("opaa_indexing_runs_queued", b, np.round(idx_queue * share))
    for stage, mean in {"fetch": 0.8, "parse": 1.4, "ocr": 9.0, "chunk": 0.2, "embed": 2.1, "store": 0.3}.items():
        n = indexing_docs_total * share * (0.2 if stage == "ocr" else 1)
        summary_pair("opaa_indexing_stage_duration_seconds", {**b, "stage": stage}, n,
                     mean * (1 + (0.8 * SMB_FULL if stage in ("ocr", "embed") else 0)))
    counter("opaa_indexing_chunks_total", b, indexing_docs_total * share * 24)
    # OCR
    pages = poisson(ocr_docs * share * 6)
    counter("opaa_ocr_documents_total", {**b, "result": "success", "connection_type": "docling_serve"}, ocr_docs * share)
    counter("opaa_ocr_documents_total", {**b, "result": "failed", "connection_type": "docling_serve"}, poisson(ocr_docs * share * 0.01))
    counter("opaa_ocr_pages_total", {**b, "result": "success"}, pages)
    summary_pair("opaa_ocr_duration_seconds", {**b, "result": "success", "connection_type": "docling_serve"}, ocr_docs * share, 14 * (1 + 0.6 * SMB_FULL))
    # Embedding role (indexing + query embeddings)
    emb_calls = indexing_docs_total * share * 2 + total_q * share
    counter("opaa_model_requests_total", {**b, "role": "embedding", "model": EMB, "result": "success"}, emb_calls)
    counter("opaa_model_requests_total", {**b, "role": "embedding", "model": EMB, "result": "throttled"}, np.zeros(N))
    summary_pair("opaa_model_duration_seconds", {**b, "role": "embedding", "model": EMB}, emb_calls, 0.35)
    emb_tok = indexing_docs_total * share * 24 * 380 + total_q * share * 40
    counter("opaa_model_tokens_total", {**b, "purpose": "embedding", "token_type": "input", "model": EMB}, emb_tok)
    counter("opaa_model_cost_euros_total", {**b, "purpose": "embedding", "model": EMB}, emb_tok * PRICE[EMB][0] / 1e6)
    lab = {**b, "gen_ai_operation_name": "embeddings", "gen_ai_system": "openai", "gen_ai_request_model": EMB,
           "error_type": "none"}
    histogram("gen_ai_client_operation_seconds", lab, emb_calls, 0.3 * (1 + 0.4 * SMB_FULL), 0.4, MODEL_B)
    # Rerank, transcription, OCR client, vision
    for role, model, n, med in (("rerank", RERANK, rag_q * share, 0.45), ("ocr", OCR, ocr_docs * share, 13.0),
                                ("transcription", ASR, poisson(0.0012 * LOAD * STEP * share), 48.0)):
        err = n * 0.003
        counter("opaa_model_requests_total", {**b, "role": role, "model": model, "result": "success"}, n - err)
        counter("opaa_model_requests_total", {**b, "role": role, "model": model, "result": "5xx"}, err)
        counter("opaa_model_requests_total", {**b, "role": role, "model": model, "result": "throttled"}, np.zeros(N))
        summary_pair("opaa_model_duration_seconds", {**b, "role": role, "model": model}, n, med * (1 + 0.2 * LOAD))
        if role == "transcription":
            summary_pair("opaa_transcription_audio_duration_seconds", {**b, "result": "success"}, n, 540)
        if role == "vision":
            vt = n * 1800
            counter("opaa_model_tokens_total", {**b, "purpose": "metadata", "token_type": "input", "model": VISION}, vt)
            counter("opaa_model_cost_euros_total", {**b, "purpose": "metadata", "model": VISION}, vt * PRICE[VISION][0] / 1e6)
    for role in ("embedding", "rerank", "ocr", "transcription"):
        counter("opaa_model_throttled_total", {**b, "role": role}, np.zeros(N))
    for role in ("chat", "embedding", "rerank", "ocr", "transcription"):  # vision NOT_CONFIGURED -> no series
        avail = np.ones(N)
        if role == "transcription":
            avail = 1 - window(2.0, 2.35)  # nightly restart of the ASR service
        gauge("opaa_model_role_available", {**b, "role": role}, avail)

# ---------------------------------------------------------------- Queues, scheduler, mail
mail_depth = poisson(0.6 * LOAD)
queues = {
    "indexing": backlog,
    "ocr": ocr_backlog,
    "mail_outbox": mail_depth,
    "source_event_batches": poisson(1.5 * LOAD),
}
per_item = {"indexing": 2.0, "ocr": 9.0, "mail_outbox": 15, "source_event_batches": 4, "directory_sync_pending_plans": 0}
JOBS = {"directory-sync": 900, "mail-outbox-dispatch": 60, "model-role-probe": 300, "audit-retention": 86400,
        "siem-export": 60, "upload-cleanup": 3600, "audit-chain-seal": 3600, "connector-scheduler": 60}
for pod in PODS:
    b = base(pod)
    for qn, d in queues.items():
        gauge("opaa_queue_depth", {**b, "queue": qn}, d)
        gauge("opaa_queue_oldest_age_seconds", {**b, "queue": qn}, oldest_age(d, per_item[qn]))
    for job, interval in JOBS.items():
        offs = (stable_hash(job) % 37) * 13
        last = ((TS - offs) // interval) * interval + offs
        if job == "audit-retention":
            run = END - 19 * 3600  # 02:00 local on Monday
            last = np.where(TS >= run, run, run - 86400).astype(float)
        # ShedLock: a job runs on one pod only; the other pod's series stands still (use max by job)
        if job not in ("audit-retention",) and pod == PODS[1] and stable_hash(job) % 2:
            last = np.full(N, float(START - 3600))
        gauge("opaa_scheduled_last_success_seconds", {**b, "job": job}, last)
        gauge("opaa_scheduled_expected_interval_seconds", {**b, "job": job}, np.full(N, float(interval)))
        counter("opaa_scheduled_failures_total", {**b, "job": job},
                (rng.random(N) < (0.0002 if job != "directory-sync" else 0.0005)).astype(float))
for pod, share in zip(PODS, POD_SHARE):
    b = base(pod)
    applied = ((TS - 300) // 900) * 900 + 300
    gauge("opaa_directory_sync_last_applied_seconds", {**b, "provider": "ldap"}, applied.astype(float))
    gauge("opaa_directory_sync_plan_pending", {**b, "provider": "ldap"}, np.zeros(N))
    counter("opaa_mail_sent_total", b, poisson(0.02 * LOAD * STEP * share))
    counter("opaa_mail_failed_total", b, np.zeros(N))

# ---------------------------------------------------------------- Security
brute = window(3.2, 3.3)
for pod, share in zip(PODS, POD_SHARE):
    b = base(pod)
    logins = 0.03 * LOAD * STEP * share
    counter("opaa_auth_logins_total", {**b, "method": "oidc", "result": "success"}, poisson(logins * 0.94))
    counter("opaa_auth_logins_total", {**b, "method": "oidc", "result": "failure"}, poisson(logins * 0.01))
    counter("opaa_auth_logins_total", {**b, "method": "local", "result": "success"}, poisson(logins * 0.05))
    counter("opaa_auth_logins_total", {**b, "method": "local", "result": "failure"}, poisson(logins * 0.004 + brute * 7 * share))
    counter("opaa_auth_logins_total", {**b, "method": "oidc", "result": "idp_error"}, np.zeros(N))
    counter("opaa_auth_local_account_lockout_total", b, (brute * (rng.random(N) < 0.5)).astype(float) * share)
    counter("opaa_rate_limit_rejected_total", {**b, "limit": "query", "scope": "user"}, poisson(0.004 * LOAD * STEP * share))
    counter("opaa_rate_limit_rejected_total", {**b, "limit": "login", "scope": "user"}, poisson(brute * 4 * share))
    counter("opaa_rate_limit_rejected_total", {**b, "limit": "query", "scope": "global"}, np.zeros(N))
    scans = (indexing_docs_total + up_docs) * share
    infected = (np.abs(LOCAL_H - 11.70) < 0.009).astype(float) * share
    counter("opaa_malware_scans_total", {**b, "result": "clean"}, scans)
    counter("opaa_malware_scans_total", {**b, "result": "infected"}, infected)
    counter("opaa_malware_scans_total", {**b, "result": "failed"}, np.zeros(N))
    summary_pair("opaa_malware_scan_duration_seconds", b, scans, 0.42 * (1 + 0.3 * SMB_FULL))
    gauge("opaa_malware_signature_age_seconds", b, (TS - 1200) % 7200 + 600)
    gauge("opaa_audit_chain_lag_seconds", b, 40 + 300 * LOAD * rng.random(N))
    gauge("opaa_audit_chain_unsealed_months", b, np.zeros(N))
    gauge("opaa_audit_retention_last_run_age_seconds", b, (TS - 1793502000).astype(float))  # last run 01.11. 03:00
    gauge("opaa_audit_log_horizon_seconds", b, 142 * 86400 - (TS - START).astype(float))
    counter("opaa_siem_export_total", {**b, "result": "success"}, poisson(0.4 * LOAD * STEP * share + 1))
    counter("opaa_siem_export_total", {**b, "result": "failed"}, np.zeros(N))
    gauge("opaa_siem_export_lag_seconds", b, 20 + 40 * LOAD + rng.uniform(0, 15, N))
    counter("opaa_external_access_alarms_total", b, np.zeros(N))

# ---------------------------------------------------------------- Platform
heap_max = 4 * 1024 ** 3
for k, (pod, share) in enumerate(zip(PODS, POD_SHARE)):
    b = base(pod)
    gauge("up", b, np.ones(N))
    restart = END - (21 * 3600 - (6 * 3600 + 40 * 60 + k * 240))  # 06:40 / 06:44 local
    new = TS >= restart
    gauge("opaa_build_info", {**b, "version": "1.14.1", "commit": "3be08d2"}, np.where(new, np.nan, 1.0))
    gauge("opaa_build_info", {**b, "version": "1.14.2", "commit": "9c41e7a"}, np.where(new, 1.0, np.nan))
    gauge("process_start_time_seconds", b, np.where(new, float(restart), float(START - 6 * 86400)))
    live = (0.55 + 0.25 * LOAD) * 1024 ** 3
    gauge("jvm_gc_live_data_size_bytes", b, live * (1 + rng.normal(0, 0.02, N)))
    gauge("jvm_gc_max_data_size_bytes", b, np.full(N, float(heap_max)))
    counter("hikaricp_connections_timeout_total", {**b, "pool": "HikariPool-1"}, np.zeros(N))
    gauge("opaa_instances_active", b, np.full(N, 2.0))
    gauge("opaa_invalidation_bus_connected", b, np.ones(N))
    heap = (0.9 + 1.0 * LOAD + 0.6 * ((TS + k * 400) % 600) / 600) * 1024 ** 3
    gauge("jvm_memory_used_bytes", {**b, "area": "heap", "id": "G1 Old Gen"}, heap)
    gauge("jvm_memory_max_bytes", {**b, "area": "heap", "id": "G1 Old Gen"}, np.full(N, heap_max))
    gauge("process_cpu_usage", b, np.clip(0.04 + 0.42 * LOAD * share * 2 + 0.25 * SMB_FULL + rng.normal(0, 0.03, N), 0.01, 1))
    act = np.clip(np.round(2 + 9 * LOAD + 3 * SMB_FULL + rng.normal(0, 1, N)), 0, 20)
    gauge("hikaricp_connections_active", {**b, "pool": "HikariPool-1"}, act)
    gauge("hikaricp_connections_max", {**b, "pool": "HikariPool-1"}, np.full(N, 20.0))
    gauge("hikaricp_connections_pending", {**b, "pool": "HikariPool-1"}, np.where(act >= 15, rng.integers(0, 3, N), 0))
    counter("logback_events_total", {**b, "level": "error"}, poisson(0.002 * STEP * LOAD + INCIDENT_RAMP * 2.5))
    counter("logback_events_total", {**b, "level": "warn"}, poisson(0.02 * STEP * LOAD + 0.02 + INCIDENT_RAMP * 6))
    for g in ("chat-model", "embedding-model", "vector-store", "transcription-model", "upload-store", "mail"):
        st = np.ones(N)
        if g == "transcription-model":
            st = 1 - window(2.0, 2.35)
        gauge("opaa_health_group_status", {**b, "group": g}, st)
    gauge("opaa_storage_used_bytes", {**b, "kind": "shared"}, 1.62e12 + np.cumsum(indexing_docs_total) * 420_000)
    gauge("opaa_storage_used_bytes", {**b, "kind": "private"}, 2.1e11 + np.cumsum(up_docs) * 1.1e6)
    gauge("opaa_storage_quota_bytes", b, np.full(N, 3e12))

KS = {"job": "kube-state-metrics", "namespace": "opaa"}
for dep, spec in (("opaa-backend", 2), ("opaa-frontend", 2)):
    gauge("kube_deployment_spec_replicas", {**KS, "deployment": dep}, np.full(N, float(spec)))
    avail = np.full(N, float(spec))
    if dep == "opaa-backend":
        for k in range(2):
            r = END - (21 * 3600 - (6 * 3600 + 40 * 60 + k * 240))
            avail -= ((TS >= r) & (TS < r + 120)).astype(float)
    gauge("kube_deployment_status_replicas_available", {**KS, "deployment": dep}, avail)
gauge("pg_up", {"job": "postgres-exporter", "instance": "opaa-postgresql-0"}, np.ones(N))

# Prometheus ALERTS (as the planned PrometheusRule would produce them)
def alert(name, sev, comp, active, extra=None):
    gauge("ALERTS", {"alertname": name, "alertstate": "firing", "severity": sev, "component": comp, **(extra or {})},
          np.where(active > 0, 1.0, np.nan))

alert("OpaaConnectorLibrariesOverdue", "warning", "indexing", np.ones(N), {"connector": "sharepoint"})
alert("OpaaModelThrottling", "warning", "model", window(14 + 22 / 60, 14 + 47 / 60), {"role": "chat"})
alert("OpaaMalwareFound", "info", "security", window(11.70, 11.95))
alert("OpaaModelRoleDown", "warning", "model", window(2.17, 2.36), {"role": "transcription"})
alert("OpaaLoginFailuresSpike", "warning", "security", window(3.23, 3.45))


def esc(v):
    return str(v).replace("\\", "\\\\").replace('"', '\\"')


def main(path):
    with open(path, "w") as f:
        for name in sorted(series):
            f.write(f"# TYPE {name} unknown\n")
            for labels, vals in series[name]:
                ls = ",".join(f'{k}="{esc(v)}"' for k, v in sorted(labels.items()))
                prefix = f"{name}{{{ls}}} "
                for t, v in zip(TS, vals):
                    if not math.isnan(v):
                        f.write(f"{prefix}{v:.12g} {int(t)}\n")
        f.write("# EOF\n")
    print(f"{sum(len(v) for v in series.values())} series, {N} samples each -> {path}")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "metrics.om")
