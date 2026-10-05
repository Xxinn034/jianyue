"""
Analyse the user's 阅读 book-source collections.

Purpose: with ~4500 sources, the deciding question is not "which rules are supported" but
"which TARGET SITES can an API-1 device reach at all". API 1 tops out at TLS 1.0, so a
source whose site needs TLS 1.2+ can never work no matter how its rules are written.

This script does the part that needs no network: group sources by host, count them, and
record which rule constructs are used, so the on-device probe can be pointed at a deduped
list of hosts instead of thousands of individual URLs.

Usage: python analyze_sources.py <out-dir> <file> [<file> ...]
"""
import json
import os
import re
import sys
from collections import Counter, defaultdict

# The Windows console here is GBK, and book-source names contain emoji and other
# characters it cannot encode. Without this, printing a source name crashes the whole
# analysis. Files are still written as UTF-8; only console output is made lossy.
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


def host_of(url):
    if not url:
        return ""
    m = re.match(r'^\s*(https?)://([^/]+)', url)
    if m:
        return m.group(2).lower()
    m = re.match(r'^\s*//([^/]+)', url)
    if m:
        return m.group(1).lower()
    return ""


def scheme_of(url):
    m = re.match(r'^\s*(https?)://', url or "")
    return m.group(1).lower() if m else ""


def load(path):
    """Load a source file; returns (sources, parse_note)."""
    with open(path, "r", encoding="utf-8", errors="replace") as f:
        raw = f.read()
    raw = raw.strip()
    try:
        data = json.loads(raw)
    except Exception as e:
        return [], "JSON parse failed: %s" % e
    if isinstance(data, dict):
        data = [data]
    if not isinstance(data, list):
        return [], "top level is %s, not a list" % type(data).__name__
    return data, ""


# Rule constructs that this engine does NOT implement. Kept in sync with
# SourceCheckActivity.unsupportedReason so the host analysis and the on-device report agree.
UNSUPPORTED_PATTERNS = [
    (r'following-sibling|preceding-sibling|ancestor::|descendant-or-self',
     "XPath axis"),
    (r'position\(\)|last\(\)|normalize-space|substring\(|concat\(',
     "XPath function"),
    (r'\?\(', "JSONPath filter"),
    (r'\.length\(\)', "JSONPath function"),
    (r':eq\(|:gt\(|:lt\(|:contains\(|:not\(', "CSS pseudo-class"),
    (r'\bjava\.|Packages\.|importClass|importPackage', "JS Java access"),
    (r'\bcookie\b|\bcache\.', "JS host object"),
]

RULE_KEYS = [
    ("ruleSearch", ["bookList", "name", "author", "kind", "intro", "wordCount",
                    "lastChapter", "coverUrl", "bookUrl"]),
    ("ruleBookInfo", ["name", "author", "kind", "intro", "wordCount", "lastChapter",
                      "coverUrl", "tocUrl"]),
    ("ruleToc", ["chapterList", "chapterName", "chapterUrl", "isVip", "tocUrl"]),
    ("ruleContent", ["content", "nextContentUrl"]),
    ("ruleExplore", ["bookList", "name", "author", "bookUrl"]),
]


def main():
    out_dir = sys.argv[1]
    files = sys.argv[2:]
    os.makedirs(out_dir, exist_ok=True)

    all_sources = []
    per_file = []
    for path in files:
        srcs, note = load(path)
        per_file.append((os.path.basename(path), len(srcs), note))
        for s in srcs:
            if isinstance(s, dict):
                s["__file"] = os.path.basename(path)
                all_sources.append(s)

    print("=" * 72)
    print("files")
    print("=" * 72)
    for name, n, note in per_file:
        print("  %-46s %5d sources  %s" % (name, n, note))
    print("  TOTAL: %d sources" % len(all_sources))
    print()

    # ---- host grouping ----
    host_count = Counter()
    host_scheme = {}
    host_sample = {}
    for s in all_sources:
        u = (s.get("bookSourceUrl") or "").strip()
        h = host_of(u)
        if not h:
            host_count["<no url>"] += 1
            continue
        host_count[h] += 1
        host_scheme.setdefault(h, scheme_of(u))
        host_sample.setdefault(h, s.get("bookSourceName", ""))

    print("=" * 72)
    print("unique hosts: %d   (from %d sources)" % (len(host_count), len(all_sources)))
    print("=" * 72)
    for h, c in host_count.most_common(40):
        print("  %-42s %4d  %-6s %s" % (h, c, host_scheme.get(h, ""), host_sample.get(h, "")))
    if len(host_count) > 40:
        print("  ... and %d more hosts" % (len(host_count) - 40))
    print()

    # ---- source types: this engine only reads text ----
    type_count = Counter()
    for s in all_sources:
        t = s.get("bookSourceType", 0)
        if isinstance(t, str):
            type_count[t.lower()] += 1
        else:
            type_count[{0: "text", 1: "audio", 2: "image", 3: "file"}.get(t, str(t))] += 1
    print("=" * 72)
    print("source types")
    print("=" * 72)
    for t, c in type_count.most_common():
        print("  %-12s %5d" % (t, c))
    print()

    # ---- rule constructs that this engine cannot handle ----
    print("=" * 72)
    print("unsupported rule constructs (host-side estimate)")
    print("=" * 72)
    bad_per_source = defaultdict(list)
    for s in all_sources:
        name = s.get("bookSourceName", "?")
        for group, keys in RULE_KEYS:
            g = s.get(group)
            if not isinstance(g, dict):
                continue
            for k in keys:
                rule = g.get(k)
                if not isinstance(rule, str) or not rule.strip():
                    continue
                for pat, label in UNSUPPORTED_PATTERNS:
                    if re.search(pat, rule):
                        bad_per_source[(name, s.get("bookSourceUrl", ""))].append(
                            "%s.%s: %s" % (group, k, label))
        # searchUrl / exploreUrl can carry JS too
        for fld in ("searchUrl", "exploreUrl"):
            v = s.get(fld)
            if isinstance(v, str) and v.strip():
                for pat, label in UNSUPPORTED_PATTERNS:
                    if re.search(pat, v):
                        bad_per_source[(name, s.get("bookSourceUrl", ""))].append(
                            "%s: %s" % (fld, label))

    kind_counter = Counter()
    for k, v in bad_per_source.items():
        for item in v:
            kind_counter[item.split(": ")[-1]] += 1

    print("  affected sources: %d / %d (%.0f%%)" % (
        len(bad_per_source), len(all_sources),
        100.0 * len(bad_per_source) / max(1, len(all_sources))))
    for k, c in kind_counter.most_common():
        print("    %-24s %5d occurrences" % (k, c))
    print()

    # ---- write outputs ----
    hosts_path = os.path.join(out_dir, "hosts.txt")
    with open(hosts_path, "w", encoding="utf-8") as f:
        f.write("# unique hosts extracted from the user's book sources\n")
        f.write("# %d hosts, %d sources total\n\n" % (len(host_count), len(all_sources)))
        for h, c in host_count.most_common():
            if h == "<no url>":
                continue
            sch = host_scheme.get(h, "http")
            f.write("%s://%s/\n" % (sch, h))
    print("wrote %s (%d hosts)" % (hosts_path, len(host_count) - (1 if "<no url>" in host_count else 0)))

    # per-host report, so a reachable host can be traced back to its sources
    byhost_path = os.path.join(out_dir, "by-host.txt")
    with open(byhost_path, "w", encoding="utf-8") as f:
        for h, c in host_count.most_common():
            f.write("### %s  (%d sources, %s)\n" % (h, c, host_scheme.get(h, "")))
            for s in all_sources:
                if host_of((s.get("bookSourceUrl") or "")) == h:
                    f.write("    %-30s %s\n" % (
                        s.get("bookSourceName", "?"), s.get("bookSourceUrl", "")))
            f.write("\n")
    print("wrote %s" % byhost_path)

    bad_path = os.path.join(out_dir, "unsupported-rules.txt")
    with open(bad_path, "w", encoding="utf-8") as f:
        for (name, url), items in sorted(bad_per_source.items()):
            f.write("%s  %s\n" % (name, url))
            for it in items:
                f.write("    %s\n" % it)
    print("wrote %s (%d sources with unsupported constructs)" % (bad_path, len(bad_per_source)))


if __name__ == "__main__":
    main()
