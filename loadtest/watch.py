"""Watch the system from the inside while k6 pushes from the outside (standard library only).

    python loadtest/watch.py                 # every 10 s until Ctrl+C
    python loadtest/watch.py --csv run.csv   # also keep the samples

k6 tells you WHAT the user felt (latency, errors). These numbers tell you WHERE it came from:
a queue that only grows means its consumer is the bottleneck; pending connections mean the pool
is too small (or held too long); outbox_pending growing means the publisher can't keep up.
"""
import argparse, csv, json, sys, time, urllib.parse, urllib.request

PROM = "http://localhost:9090/api/v1/query"
QUEUES = ["wallet.commands", "swap.wallet-events", "txn.activity"]
SHORT = {"swap-service": "swap", "wallet-service": "wallet", "transaction-service": "txn", "asset-service": "asset",
         "api-gateway": "gw"}

QUERIES = {
    "done/s": 'sum(rate(swap_orders_total{result="completed"}[30s]))',
    "accepted/s": 'sum(rate(swap_orders_total{result="accepted"}[30s]))',
    **{f"q:{q}": f'sum(rabbitmq_queue_messages{{queue="{q}"}})' for q in QUEUES},
    **{f"util:{q}": f'sum(rabbitmq_queue_consumer_utilisation{{queue="{q}"}})' for q in QUEUES},
    **{f"outbox:{s}": f'sum(outbox_pending{{application="{s}-service"}})' for s in ["swap", "wallet", "transaction"]},
    **{f"poolwait:{s}": f'sum(hikaricp_connections_pending{{application="{s}-service"}})' for s in ["swap", "wallet", "transaction", "asset"]},
    **{f"cpu:{s}": f'sum(process_cpu_usage{{application="{s}"}})' for s in ["api-gateway", "swap-service", "wallet-service", "transaction-service", "asset-service"]},
    "cpu:machine": "max(system_cpu_usage)",
}

COLUMNS = [("done/s", "done/s", "{:6.1f}"), ("accepted/s", "in/s", "{:5.1f}"),
           ("q:wallet.commands", "Q wallet", "{:8.0f}"), ("q:swap.wallet-events", "Q swap", "{:6.0f}"), ("q:txn.activity", "Q txn", "{:5.0f}"),
           ("util:wallet.commands", "util wallet", "{:11.0%}"),
           ("outbox:swap", "outbox swap", "{:11.0f}"), ("outbox:wallet", "outbox wallet", "{:13.0f}"),
           ("poolwait:wallet", "poolwait wallet", "{:15.0f}"), ("poolwait:swap", "poolwait swap", "{:13.0f}"),
           ("cpu:wallet-service", "cpu wallet", "{:10.0%}"), ("cpu:swap-service", "cpu swap", "{:8.0%}"),
           ("cpu:api-gateway", "cpu gw", "{:6.0%}"), ("cpu:machine", "cpu machine", "{:11.0%}")]


def query(expr):
    url = PROM + "?" + urllib.parse.urlencode({"query": expr})
    result = json.load(urllib.request.urlopen(url, timeout=5))["data"]["result"]
    return float(result[0]["value"][1]) if result else float("nan")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--every", type=float, default=10)
    parser.add_argument("--csv")
    parser.add_argument("--samples", type=int, default=0, help="stop after N samples (0 = until Ctrl+C)")
    args = parser.parse_args()
    out = csv.writer(open(args.csv, "w", newline="")) if args.csv else None
    if out:
        out.writerow(["time", *QUERIES])
    header = "time      " + " ".join(h for _, h, _ in COLUMNS)
    print(header)
    n = 0
    while True:
        values = {k: query(v) for k, v in QUERIES.items()}
        stamp = time.strftime("%H:%M:%S")
        print(stamp + "  " + " ".join(f.format(values[k]).rjust(len(h)) for k, h, f in COLUMNS), flush=True)
        if out:
            out.writerow([stamp, *[f"{values[k]:.4f}" for k in QUERIES]])
        n += 1
        if args.samples and n >= args.samples:
            return
        time.sleep(args.every)


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        sys.exit(0)
