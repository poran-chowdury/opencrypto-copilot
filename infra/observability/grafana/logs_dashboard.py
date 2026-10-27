"""Two log dashboards, one per question you ask while investigating:

  "OpenCrypto logs"   (find)     Is something wrong? When, where, which requests/errors?
                                  1. stat tiles: errors, warnings, failed requests, p95 latency
                                  2. volume by level, errors/warnings by service
                                  3. tables of errors and failed/slow requests (click a correlation id)
                                  4. recent log stream
  "Request trace"     (explain)  What happened to this one request, across every service?
                                  list of recent requests (pick one, or paste a correlation id),
                                  then summary tiles + the request's trace and timeline, oldest first

Loki labels (indexed, few values): service, level.
Structured metadata (per line, not indexed): correlation_id, logger, thread.
JSON fields parsed at query time: message, error.stack_trace, http.*, url.path, duration_ms.
"""

LOKI = {"type": "loki", "uid": "loki"}
TEMPO = {"type": "tempo", "uid": "tempo"}

NOISE = '!~ "${noise:raw}"'
TEXT = '|~ "(?i)$search"'
SELECTOR = '{service=~"$service", level=~"$level"} ' + NOISE + ' ' + TEXT
ONE_REQUEST = '{service=~".+"} | correlation_id="$correlation_id"'

# Gateway access lines = one per external request, with structured fields.
ACCESS = '{service="api-gateway"} | logger="http.access"'
ACCESS_FIELDS = ('| json method="[\\"http.request.method\\"]", path="[\\"url.path\\"]", '
                 'status="[\\"http.response.status_code\\"]", duration="duration_ms"'
                 ' | status != ""')  # skip lines without these fields (e.g. logged before they existed)

# "swap" instead of "swap-service", padded so messages line up.
LINE = (' | json message="message", stack="[\\"error.stack_trace\\"]"'
        ' | line_format "{{ .service | trimSuffix \\"-service\\" | printf \\"%-13s\\" }} {{ .message }}'
        '{{ if .stack }}\\n{{ .stack }}{{ end }}"')

LEVEL_COLORS = {"ERROR": "red", "WARN": "orange", "INFO": "blue", "DEBUG": "text", "TRACE": "text"}
BARS = {"drawStyle": "bars", "fillOpacity": 80, "lineWidth": 1, "stacking": {"mode": "normal"}}

# Clicking a correlation id opens the request trace, keeping the time range.
TRACE_LINK = [{"title": "Trace this request", "targetBlank": False,
               "url": "/d/opencrypto-request?var-correlation_id=${__value.raw}&${__url_time_range}"}]


def _target(expr, kind="range", legend=None, max_lines=None):
    t = {"refId": "A", "datasource": LOKI, "expr": expr, "queryType": kind}
    if legend:
        t["legendFormat"] = legend
    if max_lines:
        t["maxLines"] = max_lines
    return t


def _stat(pid, x, title, expr, desc, unit="short", steps=((None, "green"),), w=6, y=0):
    return {"id": pid, "type": "stat", "title": title, "description": desc, "datasource": LOKI,
            "gridPos": {"h": 4, "w": w, "x": x, "y": y},
            "fieldConfig": {"defaults": {"unit": unit, "decimals": 0, "noValue": "–", "thresholds": {
                "mode": "absolute", "steps": [{"value": v, "color": c} for v, c in steps]}}, "overrides": []},
            "options": {"colorMode": "value", "graphMode": "none", "textMode": "value",
                        "reduceOptions": {"calcs": ["lastNotNull"]}},
            "targets": [_target(expr, "instant")]}


def _by_name(field, *props):
    return {"matcher": {"id": "byName", "options": field}, "properties": [{"id": k, "value": v} for k, v in props]}


def _colored_text(field, steps, width):
    return _by_name(field, ("custom.cellOptions", {"type": "color-text"}), ("custom.width", width),
                    ("thresholds", {"mode": "absolute", "steps": [{"value": v, "color": c} for v, c in steps]}))


TIME_COL = _by_name("Time", ("unit", "time:HH:mm:ss.SSS"), ("custom.width", 120))
CID_COL = _by_name("correlation_id", ("links", TRACE_LINK), ("custom.width", 310))
LEVEL_COL = _by_name("level", ("custom.width", 80), ("custom.cellOptions", {"type": "color-text"}),
                     ("mappings", [{"type": "value", "options": {"ERROR": {"color": "red", "index": 0},
                                                                   "WARN": {"color": "orange", "index": 1}}}]))


def _table(pid, y, title, desc, expr, columns, overrides, h=8):
    """A table built from log lines: Loki returns labels/fields per line; we pick, order and format columns."""
    return {"id": pid, "type": "table", "title": title, "description": desc, "datasource": LOKI,
            "gridPos": {"h": h, "w": 24, "x": 0, "y": y},
            "targets": [_target(expr, max_lines=200)],
            "transformations": [
                {"id": "extractFields", "options": {"source": "labels", "format": "auto", "replace": True, "keepTime": True}},
                {"id": "organize", "options": {"includeByName": {c: True for c in columns},
                                               "indexByName": {c: i for i, c in enumerate(columns)}}},
            ],
            "fieldConfig": {"defaults": {"custom": {"filterable": True, "cellOptions": {"type": "auto"}}},
                            "overrides": [TIME_COL, *overrides]},
            "options": {"showHeader": True, "cellHeight": "sm", "sortBy": [{"displayName": "Time", "desc": True}]}}


def _variables(*names):
    all_vars = {
        "service": {"name": "service", "label": "Service", "type": "query", "datasource": LOKI,
                    "query": {"type": 1, "label": "service", "stream": "", "refId": "LokiVariableQueryEditor-VariableQuery"},
                    "definition": "label_values(service)", "includeAll": True, "multi": True, "allValue": ".+",
                    "current": {"text": "All", "value": "$__all"}, "refresh": 2, "sort": 1},
        "level": {"name": "level", "label": "Level", "type": "custom", "query": "ERROR,WARN,INFO,DEBUG",
                  "includeAll": True, "multi": True, "allValue": ".+", "current": {"text": "All", "value": "$__all"}},
        "search": {"name": "search", "label": "Text contains", "type": "textbox", "query": "",
                   "current": {"text": "", "value": ""}},
        # Health checks from the Status page and startup polling are frequent and rarely interesting.
        "noise": {"name": "noise", "label": "Health checks", "type": "custom",
                  "query": "Hidden : /api/v1/system/[a-z]+/health,Shown : x^",
                  "options": [{"text": "Hidden", "value": "/api/v1/system/[a-z]+/health", "selected": True},
                              {"text": "Shown", "value": "x^", "selected": False}],
                  "current": {"text": "Hidden", "value": "/api/v1/system/[a-z]+/health"}},
        "correlation_id": {"name": "correlation_id", "label": "Correlation id", "type": "textbox", "query": "",
                           "current": {"text": "", "value": ""}},
        # Filters for the request list. Each value is a TraceQL condition added to the gateway span filter.
        "outcome": {"name": "outcome", "label": "Requests", "type": "custom",
                    "query": ",".join(f"{t} : {v}" for t, v in OUTCOMES),
                    "options": [{"text": t, "value": v, "selected": i == 0} for i, (t, v) in enumerate(OUTCOMES)],
                    "current": {"text": OUTCOMES[0][0], "value": OUTCOMES[0][1]}},
        "path": {"name": "path", "label": "Path contains", "type": "textbox", "query": "",
                 "current": {"text": "", "value": ""}},
    }
    return {"list": [all_vars[n] for n in names]}


# "All" needs a condition too (TraceQL has no empty one); every span has a duration >= 0.
OUTCOMES = (("All", "&& duration >= 0ms"), ("Failed (4xx/5xx)", '&& span.status =~ "[45].."'),
            ("Server errors (5xx)", '&& span.status =~ "5.."'), ("Slow response (> 1 s)", "&& duration > 1s"))

# One row per request: its gateway span. The gateway names it after the route ("http post /api/v1/swaps"),
# ids templated, and drops health checks, so the list reads like an access log of traces.
REQUESTS = ('{ resource.service.name = "api-gateway" && kind = server && name =~ "(?i).*$path.*" ${outcome:raw} }'
            ' | select(span.status)')


def _request_list(y):
    """Recent requests from Tempo, newest first. Clicking a trace id loads it into the panels below."""
    pick = [{"title": "Show this request below", "targetBlank": False,
             "url": "/d/opencrypto-request?var-correlation_id=${__value.raw}&${outcome:queryparam}&${path:queryparam}"
                    "&${__url_time_range}"}]
    return {"id": 7, "type": "table", "title": "Recent requests",
            "description": "One row per request through the gateway (health checks excluded), newest first. "
                           "Click a trace id and choose \"Show this request below\". Expand a row for the HTTP status. "
                           "Trace duration includes asynchronous work (saga steps) after the response.",
            "datasource": TEMPO, "gridPos": {"h": 10, "w": 24, "x": 0, "y": y},
            "targets": [{"refId": "A", "datasource": TEMPO, "queryType": "traceql", "query": REQUESTS,
                         "tableType": "traces", "limit": 100, "spss": 1}],
            "fieldConfig": {"defaults": {"custom": {"filterable": True}}, "overrides": [
                _by_name("traceID", ("displayName", "Trace id"), ("links", pick), ("custom.width", 300)),
                _by_name("startTime", ("displayName", "Time"), ("custom.width", 190)),
                _by_name("traceName", ("displayName", "Request")),
                _by_name("traceDuration", ("displayName", "Trace duration"), ("unit", "ms"), ("custom.width", 140)),
                _by_name("traceService", ("custom.hidden", True)),
            ]},
            "options": {"showHeader": True, "cellHeight": "sm", "sortBy": [{"displayName": "Time", "desc": True}]}}


def build_logs():
    level_series = [_by_name(lvl, ("color", {"mode": "fixed", "fixedColor": c})) for lvl, c in LEVEL_COLORS.items()]
    panels = [
        # 1. Is something wrong?
        _stat(1, 0, "Errors", f'sum(count_over_time({{service=~"$service", level="ERROR"}} {NOISE} [$__range])) or vector(0)',
              "ERROR lines in the selected time range. Should be 0; anything else deserves a look.",
              steps=((None, "green"), (1, "red"))),
        _stat(2, 6, "Warnings", f'sum(count_over_time({{service=~"$service", level="WARN"}} {NOISE} [$__range])) or vector(0)',
              "WARN lines: failed swaps, 5xx responses, retries. Some are normal business outcomes.",
              steps=((None, "green"), (1, "orange"))),
        _stat(3, 12, "Failed requests (4xx/5xx)",
              f'sum(count_over_time({ACCESS} {NOISE} {ACCESS_FIELDS} | status >= 400 [$__range])) or vector(0)',
              "External requests answered with an error status, counted at the gateway. 4xx are often user mistakes.",
              steps=((None, "green"), (1, "orange"))),
        _stat(4, 18, "p95 request time",
              f'max(quantile_over_time(0.95, {ACCESS} {NOISE} {ACCESS_FIELDS} | unwrap duration | __error__="" [$__range]) by (service))',
              "95% of external requests finished faster than this (measured at the gateway).", unit="ms",
              steps=((None, "green"), (1000, "orange"), (3000, "red"))),

        # 2. When, and where?
        {"id": 5, "type": "timeseries", "title": "Log volume by level", "datasource": LOKI,
         "description": "Lines per interval matching the filters. A spike of red/orange is where to look first.",
         "gridPos": {"h": 7, "w": 12, "x": 0, "y": 4},
         "fieldConfig": {"defaults": {"unit": "short", "custom": BARS}, "overrides": level_series},
         "options": {"legend": {"displayMode": "list", "placement": "bottom"}, "tooltip": {"mode": "multi"}},
         "targets": [_target(f"sum by (level) (count_over_time({SELECTOR} [$__interval]))", legend="{{level}}")]},
        {"id": 6, "type": "timeseries", "title": "Errors and warnings by service", "datasource": LOKI,
         "description": "ERROR + WARN lines per interval and service. Legend shows totals.",
         "gridPos": {"h": 7, "w": 12, "x": 12, "y": 4},
         "fieldConfig": {"defaults": {"unit": "short", "custom": BARS}, "overrides": []},
         "options": {"legend": {"displayMode": "table", "placement": "right", "calcs": ["sum"]}, "tooltip": {"mode": "multi"}},
         "targets": [_target(f'sum by (service) (count_over_time({{service=~"$service", level=~"ERROR|WARN"}} {NOISE} [$__interval]))',
                             legend="{{service}}")]},

        # 3. Which errors / requests? Click a correlation id to trace it.
        _table(7, 11, "Errors and warnings",
               "Newest first. Click a correlation id to see everything that request did, in every service.",
               f'{{service=~"$service", level=~"ERROR|WARN"}} {NOISE} {TEXT} | json message="message"',
               ["Time", "level", "service", "message", "correlation_id"],
               [CID_COL, LEVEL_COL, _by_name("service", ("custom.width", 150))]),
        _table(8, 19, "Failed or slow requests",
               "External requests (at the gateway) that returned 4xx/5xx or took over 1 s. Click a correlation id to trace it.",
               f"{ACCESS} {NOISE} {ACCESS_FIELDS} | status >= 400 or duration >= 1000",
               ["Time", "method", "path", "status", "duration", "correlation_id"],
               [CID_COL, _by_name("method", ("custom.width", 80)),
                _colored_text("status", ((None, "text"), (400, "orange"), (500, "red")), 80),
                _colored_text("duration", ((None, "text"), (1000, "orange"), (3000, "red")), 100),
                _by_name("duration", ("unit", "ms"))]),

        # 4. Everything else, newest first.
        {"id": 9, "type": "logs", "title": "Recent logs",
         "description": "All lines matching the filters, newest first. Expand a line for its fields; "
                        "correlation_id links to the request trace.",
         "datasource": LOKI, "gridPos": {"h": 16, "w": 24, "x": 0, "y": 27},
         "options": {"showTime": True, "sortOrder": "Descending", "wrapLogMessage": True, "enableLogDetails": True,
                     "showLabels": False, "showCommonLabels": False, "dedupStrategy": "none", "prettifyLogMessage": False},
         "targets": [_target(SELECTOR + LINE, max_lines=500)]},
    ]
    return {
        "uid": "opencrypto-logs", "title": "OpenCrypto logs", "tags": ["opencrypto", "logs"],
        "timezone": "browser", "schemaVersion": 39, "version": 3, "refresh": "10s",
        "time": {"from": "now-30m", "to": "now"}, "panels": panels,
        "templating": _variables("service", "level", "search", "noise"),
    }


def build_request_trace():
    gateway = f"{ACCESS} | correlation_id=\"$correlation_id\" {ACCESS_FIELDS}"
    panels = [
        _request_list(0),
        _stat(1, 0, "HTTP status", f'max(max_over_time({gateway} | unwrap status | __error__="" [$__range]))',
              "What the caller got back from the gateway. 202 means accepted: the rest happened asynchronously below.",
              steps=((None, "green"), (400, "orange"), (500, "red")), y=10),
        _stat(2, 6, "Response time", f'max(max_over_time({gateway} | unwrap duration | __error__="" [$__range]))',
              "Time until the HTTP response. Asynchronous work (saga steps) can continue after this.", unit="ms",
              steps=((None, "green"), (1000, "orange"), (3000, "red")), y=10),
        _stat(3, 12, "Services involved",
              f"count(sum by (service) (count_over_time({ONE_REQUEST} [$__range])))",
              "Distinct services that logged anything for this request, including through RabbitMQ.",
              steps=((None, "blue"),), y=10),
        _stat(4, 18, "Warnings and errors",
              f'sum(count_over_time({{service=~".+", level=~"ERROR|WARN"}} | correlation_id="$correlation_id" [$__range])) or vector(0)',
              "WARN/ERROR lines for this request. Highlighted in orange/red in the timeline.",
              steps=((None, "green"), (1, "orange")), y=10),
        # The correlation id IS the W3C trace id (set by the gateway), so it opens the trace directly.
        {"id": 6, "type": "traces", "title": "Distributed trace (where the time went)",
         "description": "OpenTelemetry spans from Tempo: one bar per operation (HTTP calls, RabbitMQ publish/receive), "
                        "nested by caller. Gaps between bars are queue time. Click a span for its attributes and logs.",
         "datasource": TEMPO, "gridPos": {"h": 16, "w": 24, "x": 0, "y": 14},
         "targets": [{"refId": "A", "datasource": TEMPO, "queryType": "traceql", "query": "$correlation_id"}]},
        {"id": 5, "type": "logs", "title": "Log lines of request $correlation_id",
         "description": "Every line this request produced, in every service, oldest first. Read top to bottom: "
                        "HTTP in, messages queued and received, state changes, the outcome.",
         "datasource": LOKI, "gridPos": {"h": 16, "w": 24, "x": 0, "y": 30},
         "options": {"showTime": True, "sortOrder": "Ascending", "wrapLogMessage": True, "enableLogDetails": True,
                     "showLabels": False, "showCommonLabels": False, "dedupStrategy": "none", "prettifyLogMessage": False},
         "targets": [_target(ONE_REQUEST + LINE, max_lines=1000)]},
    ]
    return {
        "uid": "opencrypto-request", "title": "Request trace", "tags": ["opencrypto", "logs"],
        "description": "Pick a request from the list, or paste a correlation id (from an error toast, "
                       "X-Correlation-Id header, swap stateHistory or the OpenCrypto logs tables), to see it end to end.",
        "timezone": "browser", "schemaVersion": 39, "version": 1, "refresh": "",
        "time": {"from": "now-6h", "to": "now"}, "panels": panels,
        "templating": _variables("outcome", "path", "correlation_id"),
    }
