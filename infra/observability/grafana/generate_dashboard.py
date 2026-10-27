#!/usr/bin/env python3
"""Generates dashboards/opencrypto-overview.json (metrics), opencrypto-logs.json and opencrypto-request.json (logs).
Edit PANELS here or logs_dashboard.py, then re-run:
    python infra/observability/grafana/generate_dashboard.py
Dashboards-as-code: the JSON is reviewable in git instead of being clicked together by hand."""
import json
import pathlib

DS = {"type": "prometheus", "uid": "prometheus"}
TEMPO = {"type": "tempo", "uid": "tempo"}
LOKI = {"type": "loki", "uid": "loki"}
LINKS = [{"title": "Metrics", "type": "link", "url": "/d/opencrypto-overview", "icon": "dashboard"},
         {"title": "Logs", "type": "link", "url": "/d/opencrypto-logs", "icon": "doc"},
         {"title": "Request trace", "type": "link", "url": "/d/opencrypto-request", "icon": "bolt"}]

# (row title, [(panel title, type, unit, [(legend, expr)], description)])
PANELS = [
    ("Traffic (RED: rate, errors, duration)", [
        ("Requests / s by service", "timeseries", "reqps",
         [("{{application}}", 'sum by (application) (rate(http_server_requests_seconds_count{uri!~"/actuator.*"}[1m]))')],
         "Rate of HTTP requests handled by each service."),
        ("5xx errors / s", "timeseries", "reqps",
         [("{{application}}", 'sum by (application) (rate(http_server_requests_seconds_count{status=~"5..",uri!~"/actuator.*"}[1m]))')],
         "Server errors. 4xx (validation, insufficient funds) are expected business outcomes and excluded."),
        ("p95 latency", "timeseries", "s",
         [("{{application}}", 'histogram_quantile(0.95, sum by (le, application) (rate(http_server_requests_seconds_bucket{uri!~"/actuator.*"}[5m])))')],
         "95th percentile request latency per service."),
        ("Rate-limited requests / s", "timeseries", "reqps",
         [("{{policy}}", "sum by (policy) (rate(gateway_ratelimit_rejected_total[1m]))")],
         "429 responses from the gateway token bucket, split by read/write policy."),
    ]),
    ("Business", [
        ("Swaps (15 min)", "timeseries", "short",
         [("{{result}}", "sum by (result) (increase(swap_orders_total[15m]))")],
         "Accepted, completed and failed swaps."),
        ("Swap failures by reason (1 h)", "bargauge", "short",
         [("{{reason}}", 'sum by (reason) (increase(swap_orders_total{result="failed"}[1h]))')],
         "INSUFFICIENT_FUNDS and PRICE_MOVED are normal; TIMEOUT and MARKET_UNAVAILABLE point at infrastructure."),
        ("Ledger entries / min", "timeseries", "short",
         [("{{result}}", "sum by (result) (rate(ledger_entries_total[5m])) * 60")],
         "Journal entries posted vs idempotent replays."),
        ("Reconciliation problems", "stat", "short",
         [("mismatches", "max(ledger_reconciliation_mismatches)")],
         "Must be 0. Anything else means the ledger invariants were violated."),
    ]),
    ("Reliability", [
        ("Outbox backlog", "timeseries", "short",
         [("{{application}}", "max by (application) (outbox_pending)")],
         "Events committed to the database but not yet confirmed by RabbitMQ."),
        ("Queue depth (incl. DLQ)", "timeseries", "short",
         [("{{queue}}", 'sum by (queue) (rabbitmq_queue_messages{queue=~"wallet.*|swap.*|txn.*"})')],
         "Backlog per queue. A non-zero *.dlq means a message exhausted its retries."),
        ("Circuit breakers open", "timeseries", "short",
         [("{{application}} {{name}}", 'max by (application, name) (resilience4j_circuitbreaker_state{state="open"})')],
         "1 = open: calls to that dependency are short-circuited."),
        ("Market data age", "stat", "s",
         [("age", "max(market_price_age_seconds)")],
         "Seconds since prices were last ingested (CoinGecko sync every 60 s)."),
        ("Price cache hit ratio", "stat", "percentunit",
         [("hit ratio", 'sum(rate(price_cache_requests_total{result="hit"}[5m])) / sum(rate(price_cache_requests_total[5m]))')],
         "Share of price reads served from Redis instead of Postgres."),
        ("Swap timeouts (1 h)", "stat", "short",
         [("timeouts", "sum(increase(swap_timeouts_total[1h]))")],
         "Swaps that needed recovery because the wallet did not answer in time."),
    ]),
    ("JVM", [
        ("Heap used", "timeseries", "bytes",
         [("{{application}}", 'sum by (application) (jvm_memory_used_bytes{area="heap"})')], ""),
        ("Live threads", "timeseries", "short",
         [("{{application}}", "sum by (application) (jvm_threads_live_threads)")], ""),
    ]),
]


def build():
    panels, y, pid = [], 0, 1
    for row_title, row_panels in PANELS:
        panels.append({"type": "row", "title": row_title, "id": pid, "collapsed": False,
                       "gridPos": {"h": 1, "w": 24, "x": 0, "y": y}, "panels": []})
        pid += 1
        y += 1
        width = 24 // min(len(row_panels), 4)
        for i, (title, kind, unit, targets, desc) in enumerate(row_panels):
            x = (i % 4) * width
            if i and i % 4 == 0:
                y += 8
            panel = {
                "id": pid, "type": kind, "title": title, "description": desc, "datasource": DS,
                "gridPos": {"h": 8, "w": width, "x": x, "y": y},
                "fieldConfig": {"defaults": {"unit": unit}, "overrides": []},
                "options": {"legend": {"displayMode": "list", "placement": "bottom"}} if kind == "timeseries" else {"reduceOptions": {"calcs": ["lastNotNull"]}},
                "targets": [{"refId": chr(65 + j), "datasource": DS, "expr": expr, "legendFormat": legend} for j, (legend, expr) in enumerate(targets)],
            }
            if title == "Reconciliation problems":
                panel["fieldConfig"]["defaults"]["thresholds"] = {"mode": "absolute", "steps": [{"color": "green", "value": None}, {"color": "red", "value": 1}]}
            if title == "p95 latency":
                # exemplars: dots on the chart that each carry a trace id; click one to open that slow request's trace
                for t in panel["targets"]:
                    t["exemplar"] = True
                panel["description"] += " Dots are exemplars: click one to open a real request's trace in Tempo."
            pid += 1
            panels.append(panel)
        y += 8
    # Built by Tempo from the spans themselves: who calls whom, with rate, errors and latency per edge.
    panels.append({"type": "row", "title": "Service map (from traces)", "id": pid, "collapsed": False,
                   "gridPos": {"h": 1, "w": 24, "x": 0, "y": y}, "panels": []})
    panels.append({"id": pid + 1, "type": "nodeGraph", "title": "Service map",
                   "description": "Generated from distributed traces (Tempo service graphs). Each arrow is a real call path: "
                                  "HTTP between services and messages through RabbitMQ. Click a node for its metrics and traces.",
                   "datasource": TEMPO, "gridPos": {"h": 14, "w": 24, "x": 0, "y": y + 1},
                   "targets": [{"refId": "A", "datasource": TEMPO, "queryType": "serviceMap"}]})
    return {
        "uid": "opencrypto-overview", "title": "OpenCrypto overview", "tags": ["opencrypto"],
        "timezone": "browser", "schemaVersion": 39, "version": 1, "refresh": "10s",
        "time": {"from": "now-30m", "to": "now"}, "panels": panels, "links": LINKS,
    }


def with_links(dashboard):
    dashboard["links"] = LINKS
    return dashboard


if __name__ == "__main__":
    from logs_dashboard import build_logs, build_request_trace
    for name, dashboard in [("opencrypto-overview.json", build()), ("opencrypto-logs.json", with_links(build_logs())),
                            ("opencrypto-request.json", with_links(build_request_trace()))]:
        out = pathlib.Path(__file__).parent / "dashboards" / name
        out.write_text(json.dumps(dashboard, indent=2) + "\n", encoding="utf-8")
        print(f"wrote {out}")
