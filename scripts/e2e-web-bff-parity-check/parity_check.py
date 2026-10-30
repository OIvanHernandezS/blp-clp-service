"""Data parity between web-bff and ms-clp-blp for a list of BLP/CLP pages.

For every page:  web-bff GET  ==  ms-clp-blp GET  +  the lazy POSTs the frontend would make.

Only data is compared. What differs by construction is excluded: the lazy-loading envelope of
ms-clp-blp's dynamic blocks and web-bff's ``globalData`` (ADR-002). ``null`` and an absent key are
treated as equal (web-bff is JavaScript and emits ``null`` where ms-clp-blp omits the field).

Called by e2e-test.sh; stdlib only. Credentials are read from the env file and never printed.
"""
import argparse
import base64
import collections
import csv
import json
import os
import re
import statistics
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor

LAZY_ENVELOPE = {"status", "loadStrategy", "ms_params", "url", "httpMethod", "resolutionPath", "reason",
                 "featureFlagId"}
TOP_LEVEL_IGNORED = {"globalData"}
MISSING = object()


def load_env(path):
    """KEY=VALUE lines, one surrounding pair of quotes stripped (what `export $(... | xargs)` does)."""
    env = {}
    for line in open(path, encoding="utf-8"):
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        if len(value) >= 2 and value[0] == value[-1] and value[0] in "'\"":
            value = value[1:-1]
        env[key.strip()] = value
    return env


class Http:
    def __init__(self, brand, timeout):
        self.headers = {"x-brand-id": brand, "x-channel": "WEB", "x-is-logged-in": "false",
                        "Accept": "application/json"}
        self.timeout = timeout

    def call(self, url, extra=None, body=None):
        url = urllib.parse.quote(url, safe=":/?&=%,")
        data = json.dumps(body).encode() if body is not None else None
        headers = {**self.headers, **(extra or {})}
        if data is not None:
            headers["Content-Type"] = "application/json"
        request = urllib.request.Request(url, data=data, headers=headers,
                                         method="POST" if data is not None else "GET")
        started = time.time()
        try:
            with urllib.request.urlopen(request, timeout=self.timeout) as response:
                raw, code = response.read(), response.status
        except urllib.error.HTTPError as e:
            raw, code = e.read(), e.code
        except Exception as e:  # timeout, refused connection, TLS…
            return f"ERR {type(e).__name__}", None, int((time.time() - started) * 1000)
        try:
            payload = json.loads(raw)
        except ValueError:
            payload = None
        return code, payload, int((time.time() - started) * 1000)


def dynamic_blocks(node):
    """Every dict carrying ms-clp-blp's lazy envelope (a products_list placeholder), at any depth."""
    if isinstance(node, dict):
        if "ms_params" in node and "status" in node:
            yield node
        for value in node.values():
            yield from dynamic_blocks(value)
    elif isinstance(node, list):
        for value in node:
            yield from dynamic_blocks(value)


def resolve_lazy(page, ms_url, auth, http):
    """Make the follow-up calls the frontend would make and fold their content into the page."""
    pending = [b for b in dynamic_blocks(page)
               if b.get("loadStrategy") != "EAGER" or b.get("status") != "AVAILABLE"]
    by_url = collections.defaultdict(list)
    for block in pending:
        by_url[block.get("url")].append(block)
    calls = []
    for url, blocks in by_url.items():
        code, payload, elapsed = http.call(ms_url + url, {"Authorization": auth}, [b["ms_params"] for b in blocks])
        items = {i.get("blockId"): i for i in (payload or {}).get("blocks", [])} if isinstance(payload, dict) else {}
        calls.append({"url": url, "blocks": len(blocks), "code": code, "ms": elapsed,
                      "statuses": dict(collections.Counter(i.get("status") for i in items.values())),
                      "reasons": dict(collections.Counter(i.get("reason") for i in items.values() if i.get("reason")))})
        for block in blocks:
            item = items.get(block["ms_params"].get("uid"))
            if item and item.get("status") == "AVAILABLE":
                block.update({k: v for k, v in (item.get("content") or {}).items() if k != "blockId"})
            block["_lazy_status"] = item.get("status") if item else "NO_ANSWER"
            block["_lazy_reason"] = item.get("reason") if item else None
    return calls


def strip(node, top=False):
    if isinstance(node, dict):
        return {k: strip(v) for k, v in node.items()
                if k not in LAZY_ENVELOPE and not k.startswith("_lazy") and not (top and k in TOP_LEVEL_IGNORED)}
    if isinstance(node, list):
        return [strip(v) for v in node]
    return node


def diff(bff, ms, path, out):
    if isinstance(bff, dict) and isinstance(ms, dict):
        for key in set(bff) | set(ms):
            a, b = bff.get(key, MISSING), ms.get(key, MISSING)
            if a in (MISSING, None) and b in (MISSING, None):
                continue  # null ≡ absent
            if b is MISSING:
                out.append(("only_bff", f"{path}.{key}"))
            elif a is MISSING:
                out.append(("only_ms", f"{path}.{key}"))
            else:
                diff(a, b, f"{path}.{key}", out)
    elif isinstance(bff, list) and isinstance(ms, list):
        if len(bff) != len(ms):
            out.append(("length", f"{path}[]"))
        for i, (a, b) in enumerate(zip(bff, ms)):
            diff(a, b, f"{path}[{i}]", out)
    elif bff != ms:
        out.append(("value", path))


def collapse(path):
    return re.sub(r"\[\d+\]", "[]", path)


def carousels(page):
    found = {}

    def walk(node):
        if isinstance(node, dict):
            if node.get("_content_type_uid") in ("products_list", "product_list"):
                uid = node.get("uid") or (node.get("_metadata") or {}).get("uid")
                found[uid] = {"src": node.get("source_of_data"),
                              "ids": [str(p.get("productId")) for p in node.get("products") or [] if isinstance(p, dict)],
                              "lazy": node.get("_lazy_status"), "reason": node.get("_lazy_reason")}
            for value in node.values():
                walk(value)
        elif isinstance(node, list):
            for value in node:
                walk(value)

    walk(page)
    return found


def compare(row, args, auth, http):
    path = row["path"].strip().strip("/")
    kind = "blp" if "BLP" in row["content_type"].upper() else "clp"
    category = path.rsplit("/", 1)[-1]
    bff_code, bff, bff_ms = http.call(f"{args.bff_url}/content/page-{kind}/{args.locale}/{path}")
    ms_code, ms, ms_ms = http.call(f"{args.ms_url}/{kind}/{args.locale}/{path}", {"Authorization": auth})
    result = {"kind": kind, "category": category, "path": path, "bff_code": bff_code, "ms_code": ms_code,
              "bff_ms": bff_ms, "ms_first_ms": ms_ms, "ms_total_ms": ms_ms, "lazy_calls": [], "diffs": [],
              "carousels": []}
    if bff_code != 200 or ms_code != 200 or not isinstance(bff, dict) or not isinstance(ms, dict):
        if bff_code != ms_code:
            result["diffs"].append(("status", f"bff={bff_code} ms={ms_code}"))
        return result
    result["lazy_calls"] = resolve_lazy(ms, args.ms_url, auth, http)
    result["ms_total_ms"] = ms_ms + sum(c["ms"] for c in result["lazy_calls"])
    safe = re.sub(r"[^A-Za-z0-9_.-]", "_", category)
    json.dump(bff, open(f"{args.out}/raw/{kind}_{safe}_bff.json", "w"), ensure_ascii=False)
    json.dump(ms, open(f"{args.out}/raw/{kind}_{safe}_ms_assembled.json", "w"), ensure_ascii=False)
    ms_carousels = carousels(ms)
    for uid, c in carousels(bff).items():
        m = ms_carousels.get(uid, {"ids": [], "lazy": "MISSING_BLOCK", "reason": None})
        result["carousels"].append({"uid": uid, "src": c["src"], "bff": len(c["ids"]), "ms": len(m["ids"]),
                                    "same_order": c["ids"] == m["ids"], "lazy": m.get("lazy"),
                                    "reason": m.get("reason")})
    raw = []
    diff(strip(bff, True), strip(ms, True), "$", raw)
    result["diffs"] = [(kind_, collapse(p)) for kind_, p in raw]
    return result


def percentile(values, q):
    values = sorted(values)
    return values[max(0, int(round(q * len(values))) - 1)] if values else 0


def report(results, args):
    compared = [r for r in results if r["bff_code"] == 200 and r["ms_code"] == 200]
    clean = [r for r in results if not r["diffs"]]
    lazy = [c for r in compared for c in r["lazy_calls"]]
    cars = [c for r in compared for c in r["carousels"]]
    pages_by_diff, occurrences = collections.Counter(), collections.Counter()
    for r in results:
        for d in set(map(tuple, r["diffs"])):
            pages_by_diff[d] += 1
        for d in map(tuple, r["diffs"]):
            occurrences[d] += 1
    lines = [f"# web-bff parity — {args.env}", "",
             f"- web-bff: `{args.bff_url}` · ms-clp-blp: `{args.ms_url}` · brand `{args.brand}` · {len(results)} pages", "",
             "| | |", "|---|---|",
             f"| Pages with identical data | **{len(clean)} / {len(results)}** |",
             f"| Both answered 200 | {len(compared)} / {len(results)} |",
             f"| Carousels with the same products in the same order | {sum(c['same_order'] for c in cars)} / {len(cars)} |",
             f"| Lazy POSTs | {len(lazy)} — HTTP {dict(collections.Counter(c['code'] for c in lazy))} |",
             f"| Lazy block statuses | {dict(sum((collections.Counter(c['statuses']) for c in lazy), collections.Counter()))} |",
             f"| Lazy drop reasons | {dict(sum((collections.Counter(c['reasons']) for c in lazy), collections.Counter())) or '—'} |"]
    if compared:
        lines.append(f"| Latency p50 / p95 | web-bff {int(statistics.median([r['bff_ms'] for r in compared]))} / "
                     f"{percentile([r['bff_ms'] for r in compared], .95)} ms · ms-clp-blp GET+lazy "
                     f"{int(statistics.median([r['ms_total_ms'] for r in compared]))} / "
                     f"{percentile([r['ms_total_ms'] for r in compared], .95)} ms |")
    status = collections.Counter((r["bff_code"], r["ms_code"]) for r in results if (r["bff_code"], r["ms_code"]) != (200, 200))
    if status:
        lines += ["", "## Status mismatches", "", "| web-bff | ms-clp-blp | Pages |", "|---|---|---|"]
        lines += [f"| {b} | {m} | {', '.join(r['path'] for r in results if (r['bff_code'], r['ms_code']) == (b, m))} |"
                  for (b, m), _ in status.most_common()]
    broken = collections.Counter((c["src"], c["lazy"], c["reason"]) for c in cars if not c["same_order"])
    if broken:
        lines += ["", "## Carousels that differ", "", "| source_of_data | ms-clp-blp status | reason | count |",
                  "|---|---|---|---|"]
        lines += [f"| {s} | {l} | {r} | {n} |" for (s, l, r), n in broken.most_common()]
    if pages_by_diff:
        lines += ["", "## Data differences", "",
                  "`only_bff` = missing in ms-clp-blp · `only_ms` = extra in ms-clp-blp · `value`/`length` = differs", "",
                  "| Pages | Occurrences | Kind | Path |", "|---|---|---|---|"]
        lines += [f"| {n} | {occurrences[d]} | {d[0]} | `{d[1]}` |" for d, n in pages_by_diff.most_common()]
    lines += ["", "## Pages", "", "| Page | web-bff | ms-clp-blp | Differences |", "|---|---|---|---|"]
    for r in results:
        kinds = collections.Counter(k for k, _ in r["diffs"])
        lines.append(f"| `{r['kind']}/{r['path']}` | {r['bff_code']} | {r['ms_code']} | "
                     f"{', '.join(f'{k}×{n}' for k, n in kinds.items()) or 'none'} |")
    open(f"{args.out}/report.md", "w").write("\n".join(lines) + "\n")
    return len(clean), len(results)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--env", required=True)
    parser.add_argument("--env-file", required=True)
    parser.add_argument("--csv", required=True)
    parser.add_argument("--bff-url", required=True)
    parser.add_argument("--ms-url", required=True)
    parser.add_argument("--out", required=True)
    parser.add_argument("--brand", default="LP")
    parser.add_argument("--locale", default="es-mx")
    parser.add_argument("--workers", type=int, default=4)
    parser.add_argument("--timeout", type=int, default=40)
    args = parser.parse_args()
    args.bff_url, args.ms_url = args.bff_url.rstrip("/"), args.ms_url.rstrip("/")

    env = load_env(args.env_file)
    user, password = env.get("MS_CLP_BLP_SECURITY_USERNAME"), env.get("MS_CLP_BLP_SECURITY_PASSWORD")
    if not user or not password:
        sys.exit(f"MS_CLP_BLP_SECURITY_USERNAME / _PASSWORD missing in {args.env_file}")
    auth = "Basic " + base64.b64encode(f"{user}:{password}".encode()).decode()

    rows = [r for r in csv.DictReader(open(args.csv, encoding="utf-8")) if (r.get("path") or "").strip()]
    if not rows:
        sys.exit(f"No rows with a path in {args.csv}")
    os.makedirs(f"{args.out}/raw", exist_ok=True)
    http = Http(args.brand, args.timeout)
    with ThreadPoolExecutor(max_workers=args.workers) as pool:
        results = list(pool.map(lambda row: compare(row, args, auth, http), rows))
    json.dump(results, open(f"{args.out}/results.json", "w"), indent=1, ensure_ascii=False)
    clean, total = report(results, args)
    print(f"{clean}/{total} pages with identical data")
    sys.exit(0 if clean == total else 1)


if __name__ == "__main__":
    main()
