"""
Definitive analysis of the user's 阅读 book-source collections.

Answers three questions per source:
  1. can this ENGINE read it?      (type must be text, rules must use supported syntax)
  2. can this DEVICE reach it?     (host must be in the probed reachable set)
  3. therefore, is it usable?

Rule classification mirrors SourceCheckActivity on the device, so host-side and on-device
verdicts agree. This runs host-side because the full collection (~4500 sources, 17 MB) is
too large for an API-1 device to parse in one piece.

Usage: python report_sources.py <analysis-dir> <reachable-hosts.txt> <file> [<file> ...]
"""
import json
import os
import re
import sys
from collections import Counter, defaultdict

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


# ---------------------------------------------------------------- rule checking

# Constructs this engine does not implement. Keep in sync with
# SourceCheckActivity.unsupportedReason.
UNSUPPORTED = [
    (r'following-sibling|preceding-sibling|ancestor::|descendant-or-self', "XPath 轴"),
    (r'position\(\)|last\(\)|normalize-space|substring\(|concat\(', "XPath 函数"),
    (r'\?\(', "JSONPath 过滤"),
    (r'\.length\(\)', "JSONPath 函数"),
    (r':eq\(|:gt\(|:lt\(|:contains\(|:not\(', "CSS 伪类"),
    (r'\bjava\.|Packages\.|importClass|importPackage', "JS 访问 Java 类"),
    (r'\bcookie\b|\bcache\.', "JS 宿主对象"),
]

# Fields that carry a rule, per rule group. Mirrors Rules.rulesToJson / SourceCheckActivity.
RULE_FIELDS = {
    "ruleSearch": ["bookList", "name", "author", "kind", "intro", "wordCount",
                   "lastChapter", "coverUrl", "bookUrl"],
    "ruleBookInfo": ["name", "author", "kind", "intro", "wordCount", "lastChapter",
                     "coverUrl", "tocUrl"],
    "ruleToc": ["chapterList", "chapterName", "chapterUrl", "isVip", "tocUrl"],
    "ruleContent": ["content", "nextContentUrl"],
    "ruleExplore": ["bookList", "name", "author", "bookUrl"],
}


def strip_replace(rule):
    """Remove a trailing ##regex##replacement, as Rules.stripReplace does."""
    for sep in ("##", "#@#", "#&#", "$$"):
        i = rule.find(sep, 1)
        if i > 0:
            return rule[:i]
    return rule


def unsupported_reasons(rule):
    """Return the list of unsupported construct labels found in one rule."""
    body = strip_replace(rule)
    out = []
    for pat, label in UNSUPPORTED:
        if re.search(pat, body):
            out.append(label)
    return out


def source_type(s):
    """Read bookSourceType, which is a string in exports and an int in the database."""
    t = s.get("bookSourceType", 0)
    if isinstance(t, str):
        v = t.strip().lower()
        if not v:
            return 0
        if "audio" in v or "听" in v:
            return 1
        if "image" in v or "图片" in v:
            return 2
        if "file" in v or "文件" in v:
            return 3
        return 0
    if isinstance(t, (int, float)):
        return int(t)
    return 0


TYPE_NAME = {0: "文本", 1: "音频", 2: "图片", 3: "文件"}


def host_of(url):
    """
    Extract the host from a URL, lowercased, with any port stripped.

    Accepts a bare hostname too ("example.com"), because the reachable-host file is written
    as plain hosts while book sources carry full URLs. Getting this wrong made the whole
    intersection collapse to zero.

    NOTE the character class: it must exclude only "/", not ":". Using [^/:]+ stops at the
    colon of "https:" and makes every host compare as empty.
    """
    s = (url or "").strip().lstrip("\ufeff")
    if not s:
        return ""
    m = re.match(r'^\s*https?://([^/]+)', s)
    h = m.group(1) if m else s.split('/')[0]
    c = h.find(':')
    if c >= 0:
        h = h[:c]
    return h.lower()


def analyse(s):
    """Return a dict describing one source's usability."""
    url = (s.get("bookSourceUrl") or "").strip()
    stype = source_type(s)
    bad = []
    rule_total = 0
    for group, fields in RULE_FIELDS.items():
        g = s.get(group)
        if not isinstance(g, dict):
            continue
        for f in fields:
            r = g.get(f)
            if isinstance(r, str) and r.strip():
                rule_total += 1
                bad.extend(unsupported_reasons(r))
    for fld in ("searchUrl", "exploreUrl"):
        v = s.get(fld)
        if isinstance(v, str) and v.strip():
            bad.extend(unsupported_reasons(v))

    # A source with no searchUrl and no exploreUrl cannot be discovered at all.
    has_entry = bool((s.get("searchUrl") or "").strip() or (s.get("exploreUrl") or "").strip())
    has_toc = isinstance(s.get("ruleToc"), dict) and bool(
        (s["ruleToc"].get("chapterList") or "").strip())
    has_content = isinstance(s.get("ruleContent"), dict) and bool(
        (s["ruleContent"].get("content") or "").strip())

    return {
        "name": s.get("bookSourceName", "?"),
        "url": url,
        "host": host_of(url),
        "type": stype,
        "rules": rule_total,
        "bad": bad,
        "has_entry": has_entry,
        "has_toc": has_toc,
        "has_content": has_content,
    }


def main():
    if len(sys.argv) < 4:
        print(__doc__)
        return
    out_dir = sys.argv[1]
    reachable_file = sys.argv[2]
    files = sys.argv[3:]
    os.makedirs(out_dir, exist_ok=True)

    reachable = set()
    with open(reachable_file, "r", encoding="utf-8", errors="replace") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            reachable.add(host_of(line))

    srcs = []
    for p in files:
        with open(p, "r", encoding="utf-8", errors="replace") as f:
            d = json.load(f)
        if isinstance(d, dict):
            d = [d]
        srcs.extend([x for x in d if isinstance(x, dict)])

    infos = [analyse(s) for s in srcs]

    print("=" * 78)
    print("总计 %d 个书源" % len(infos))
    print("=" * 78)
    print()

    # 1) engine compatibility
    by_type = Counter(i["type"] for i in infos)
    print("-- 1. 类型（本引擎只读文本源）--")
    for t, c in by_type.most_common():
        print("   %-6s %5d" % (TYPE_NAME.get(t, str(t)), c))
    text_srcs = [i for i in infos if i["type"] == 0]
    print("   文本源合计: %d" % len(text_srcs))
    print()

    no_rule = [i for i in text_srcs if i["rules"] == 0]
    bad_rule = [i for i in text_srcs if i["bad"]]
    clean = [i for i in text_srcs if not i["bad"] and i["rules"] > 0]
    print("-- 2. 规则语法 --")
    print("   规则全部支持        : %5d" % len(clean))
    print("   含不支持的语法      : %5d" % len(bad_rule))
    print("   未配置任何规则      : %5d" % len(no_rule))
    bad_kind = Counter()
    for i in bad_rule:
        for b in set(i["bad"]):
            bad_kind[b] += 1
    for k, c in bad_kind.most_common():
        print("      %-18s %5d" % (k, c))
    print()

    complete = [i for i in clean if i["has_entry"] and i["has_toc"] and i["has_content"]]
    print("   其中结构完整（有入口+目录+正文）: %d" % len(complete))
    print()

    # 2) reachability
    reach_hosts = set(i["host"] for i in infos) & reachable
    print("-- 3. 站点可达性（实测 %d 个域名）--" % len(reachable))
    print("   书源涉及域名总数    : %5d" % len(set(i["host"] for i in infos if i["host"])))
    print("   其中实测可达        : %5d" % len(reach_hosts))
    print()

    usable = [i for i in complete if i["host"] in reachable]
    print("-- 4. 结论：既语法支持又站点可达 --")
    print("   可用书源: %d / %d  (%.1f%%)" % (
        len(usable), len(infos), 100.0 * len(usable) / max(1, len(infos))))
    print()

    # 3) the usable ones, grouped by host
    by_host = defaultdict(list)
    for i in usable:
        by_host[i["host"]].append(i)
    path = os.path.join(out_dir, "usable-sources.txt")
    with open(path, "w", encoding="utf-8") as f:
        f.write("# 既通过语法检查、站点又实测可达的书源\n")
        f.write("# 共 %d 个，分布在 %d 个域名\n\n" % (len(usable), len(by_host)))
        for h in sorted(by_host, key=lambda k: -len(by_host[k])):
            f.write("### %s  (%d)\n" % (h, len(by_host[h])))
            for i in by_host[h]:
                f.write("    %-34s %s\n" % (i["name"][:34], i["url"]))
            f.write("\n")
    print("wrote %s" % path)
    print()
    print("-- 可用书源最多的域名（前 30）--")
    for h in sorted(by_host, key=lambda k: -len(by_host[k]))[:30]:
        print("   %-38s %3d" % (h, len(by_host[h])))

    # 4) near misses: reachable host but unsupported syntax, worth fixing by hand
    near = [i for i in text_srcs if i["host"] in reachable and i["bad"]]
    path2 = os.path.join(out_dir, "fixable-sources.txt")
    with open(path2, "w", encoding="utf-8") as f:
        f.write("# 站点可达、但规则用了未支持语法的书源（改规则后可用）\n")
        f.write("# 共 %d 个\n\n" % len(near))
        for i in sorted(near, key=lambda x: x["host"]):
            f.write("%s  %s\n" % (i["name"], i["url"]))
            for b in sorted(set(i["bad"])):
                f.write("    %s\n" % b)
    print()
    print("wrote %s (%d 个，站点可达但需改规则)" % (path2, len(near)))


if __name__ == "__main__":
    main()
