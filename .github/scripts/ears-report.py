#!/usr/bin/env python3
"""EARS report (P1.35): which tests prove which criteria, and how they did in one commit's runs.

Usage: ears-report.py --src <repo> --results <dir> --out <dir> [--commit SHA] [--run NAME=ID ...]

--results holds the downloaded artifacts: `unit-test-results` from CI and `androidTest-<module>`
from Instrumented, each in its own folder. Writes <out>/ears-report.json, <out>/ears-report.md and
copies each test's evidenceShot() files to <out>/shots/<class>#<method>/.

Tags come from the test sources: `@Ears("US-17.2", "NF-6")` on a test function, or on a class for
all of its tests. Results come from the JUnit XML files. A tagged test with no result is "not run".
"""
import argparse
import json
import os
import re
import shutil
import xml.etree.ElementTree as ET

TOKENS = re.compile(
    r'@Ears\s*\(([^)]*)\)'                              # 1: ids
    r'|\bpackage\s+([\w.]+)'                            # 2: package
    r'|\b(?:class|object)\s+(\w+)'                      # 3: class
    r'|\bfun\s+(?:`([^`]+)`|(\w+))\s*\('                # 4, 5: function
)
IDS = re.compile(r'"([^"]+)"')


def module_of(path):
    """core/engine/src/test/... -> ("core-engine", "unit")."""
    parts = path.split(os.sep)
    i = parts.index("src")
    kind = "device" if parts[i + 1] == "androidTest" else "unit"
    return "-".join(parts[:i]), kind


def scan_sources(src):
    """{"pkg.Class#method": {"ids": [...], "module": m, "kind": k}} and class-level tags."""
    tests, class_tags = {}, {}
    for root, dirs, files in os.walk(src):
        dirs[:] = [d for d in dirs if d not in ("build", ".git", ".gradle", ".claude")]
        rel = os.path.relpath(root, src)
        if f"{os.sep}src{os.sep}test" not in os.sep + rel and f"{os.sep}src{os.sep}androidTest" not in os.sep + rel:
            continue
        for name in files:
            if not name.endswith(".kt"):
                continue
            path = os.path.join(root, name)
            text = open(path, encoding="utf-8", errors="replace").read()
            module, kind = module_of(os.path.relpath(path, src))
            package, cls, pending = "", None, []
            for m in TOKENS.finditer(text):
                if m.group(1) is not None:
                    pending += IDS.findall(m.group(1))
                elif m.group(2):
                    package = m.group(2)
                elif m.group(3):
                    cls = f"{package}.{m.group(3)}" if package else m.group(3)
                    if pending:
                        class_tags[cls] = {"ids": pending, "module": module, "kind": kind}
                    pending = []
                else:
                    fun = m.group(4) or m.group(5)
                    if pending and cls:
                        tests[f"{cls}#{fun}"] = {"ids": pending, "module": module, "kind": kind}
                    pending = []
    return tests, class_tags


def read_results(results):
    """{"pkg.Class#method": {"status", "message", "time"}} from every JUnit XML under results."""
    out = {}
    for root, _, files in os.walk(results):
        for name in files:
            if not (name.startswith("TEST-") and name.endswith(".xml")):
                continue
            try:
                tree = ET.parse(os.path.join(root, name))
            except ET.ParseError:
                continue
            for case in tree.iter("testcase"):
                key = f"{case.get('classname')}#{case.get('name')}"
                status, message = "pass", ""
                for tag in ("failure", "error"):
                    el = case.find(tag)
                    if el is not None:
                        status = "fail"
                        lines = (el.get("message") or el.text or "").strip().splitlines()
                        message = lines[0][:300] if lines else ""
                if case.find("skipped") is not None and status == "pass":
                    status = "skipped"
                # A test that ran twice (retries, two devices) counts as failed if any run failed.
                if out.get(key, {}).get("status") == "fail":
                    continue
                out[key] = {"status": status, "message": message, "time": float(case.get("time") or 0)}
    return out


def find_shots(results, out):
    """Copies <...>/evidence/<class>#<method>/*.png into out/shots and maps test -> paths."""
    shots = {}
    for root, _, files in os.walk(results):
        parent, test = os.path.split(root)
        if os.path.basename(parent) != "evidence" or "#" not in test:
            continue
        for name in sorted(files):
            if not name.endswith(".png"):
                continue
            dest = os.path.join(out, "shots", test)
            os.makedirs(dest, exist_ok=True)
            shutil.copy2(os.path.join(root, name), os.path.join(dest, name))
            shots.setdefault(test, []).append(f"shots/{test}/{name}")
    return shots


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--src", required=True)
    ap.add_argument("--results", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--commit", default="")
    ap.add_argument("--run", action="append", default=[], help="NAME=ID[:conclusion]")
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)

    tests, class_tags = scan_sources(a.src)
    results = read_results(a.results)
    shots = find_shots(a.results, a.out)

    # Class-level tags reach every test of that class that has a result.
    for key in results:
        cls = key.split("#")[0]
        if cls in class_tags:
            t = tests.setdefault(key, {"ids": [], **{k: v for k, v in class_tags[cls].items() if k != "ids"}})
            t["ids"] = sorted(set(t["ids"]) | set(class_tags[cls]["ids"]))

    rows = []
    for key, t in sorted(tests.items()):
        r = results.get(key, {"status": "not run", "message": "", "time": 0})
        for i in t["ids"]:
            rows.append({"id": i, "test": key, "module": t["module"], "kind": t["kind"], "status": r["status"],
                         "message": r["message"], "seconds": r["time"], "screenshots": shots.get(key, [])})
    rows.sort(key=lambda r: (id_key(r["id"]), r["test"]))
    runs = {}
    for spec in a.run:
        name, _, rest = spec.partition("=")
        run_id, _, conclusion = rest.partition(":")
        runs[name] = {"id": run_id, "conclusion": conclusion}
    report = {"commit": a.commit, "runs": runs, "rows": rows,
              "summary": {s: sum(1 for r in rows if r["status"] == s) for s in ("pass", "fail", "skipped", "not run")}}
    json.dump(report, open(os.path.join(a.out, "ears-report.json"), "w"), indent=1)

    with open(os.path.join(a.out, "ears-report.md"), "w") as f:
        f.write(f"# EARS report, commit {a.commit[:10]}\n\n")
        for name, r in runs.items():
            f.write(f"- {name}: run {r['id']} ({r['conclusion'] or 'not finished'})\n")
        s = report["summary"]
        f.write(f"\n{len(rows)} tagged results: {s['pass']} pass, {s['fail']} fail, {s['skipped']} skipped, {s['not run']} not run.\n\n")
        f.write("| Id | Status | Test | Module | Shots | Message |\n|---|---|---|---|---|---|\n")
        for r in rows:
            cls, _, fun = r["test"].rpartition("#")
            test = f"{cls.rsplit('.', 1)[-1]}#{fun}"
            msg = r["message"].replace("|", "\\|")[:120]
            f.write(f"| {r['id']} | {r['status']} | `{test}` | {r['module']} ({r['kind']}) | {len(r['screenshots'])} | {msg} |\n")
    print(f"{len(rows)} rows, {report['summary']}")


def id_key(i):
    m = re.match(r"([A-Z]+)-(\d+)(?:\.(\d+))?", i)
    return (m.group(1), int(m.group(2)), int(m.group(3) or 0)) if m else (i, 0, 0)


if __name__ == "__main__":
    main()
