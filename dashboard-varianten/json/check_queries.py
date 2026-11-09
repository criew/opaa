import json, glob, sys, urllib.request, urllib.parse
T = sys.argv[2] if len(sys.argv) > 2 else "1794254400"
def walk(panels):
    for p in panels:
        yield p
        yield from walk(p.get("panels", []))
for f in sorted(glob.glob(sys.argv[1] + "/*.json")):
    d = json.load(open(f))
    for p in walk(d["panels"]):
        for t in p.get("targets", []):
            e = t["expr"]
            for a, b in (("$instance", ".*"), ("$connector", ".*"), ("$model", ".*"), ("$__range_s", "86400"),
                         ("$__range", "24h"), ("$__interval", "1h")):
                e = e.replace(a, b)
            q = urllib.parse.urlencode({"query": e, "time": T})
            r = json.load(urllib.request.urlopen("http://localhost:9090/api/v1/query?" + q))
            res = r.get("data", {}).get("result")
            if r["status"] != "success":
                print("ERR", f.split("/")[-1], p.get("title"), t.get("legendFormat"), r.get("error"))
            else:
                vals = [(x["metric"].get("k") or x["metric"].get("connector") or x["metric"].get("job") or "", x["value"][1]) for x in res][:4] if isinstance(res, list) else res
                print(f.split("/")[-1][:12], "|", (p.get("title") or "")[:28], "|", t.get("legendFormat")[:24], "|", vals)
