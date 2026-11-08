#!/usr/bin/env python3
"""Values for HMG-API's NEMESIS descriptors (04-Packages.md §2).

  tools/nemesis-meta.py depends <package id> [needs] [--mod <mod.json>]
      depends=: the needs (e.g. "hmgapi@>=1.0") then "!<id>" for each package that
      install/nemesis/conflicts.txt (or $HMGAPI_NEMESIS_CONFLICTS) lists for this one,
      then each one in the mod's "nemesis_conflicts". In the mod SDK there's no
      conflicts.txt: only the mod's list.
"""
import json, os, re, sys

ID = re.compile(r"[A-Za-z0-9_][A-Za-z0-9_.-]{0,63}")


def conflicts(pkg, mod=None):
    path = os.environ.get("HMGAPI_NEMESIS_CONFLICTS") or "install/nemesis/conflicts.txt"
    out = []
    lines = open(path) if os.environ.get("HMGAPI_NEMESIS_CONFLICTS") or os.path.isfile(path) else []
    for n, line in enumerate(lines, 1):
        line = line.split("#", 1)[0].split()
        if not line:
            continue
        if len(line) != 2:
            sys.exit("%s:%d: wanted <package id> <ids>" % (path, n))
        ids = line[1].split(",")
        for i in [line[0]] + ids:
            if not ID.fullmatch(i) or ".." in i:
                sys.exit("%s:%d: %r isn't a NEMESIS package id" % (path, n, i))
        if line[0] == pkg:
            out += [i for i in ids if i not in out]
    if mod:
        ids = json.load(open(mod)).get("nemesis_conflicts", [])
        if not isinstance(ids, list):
            sys.exit('%s: "nemesis_conflicts" is a list of NEMESIS package ids' % mod)
        for i in ids:
            if not isinstance(i, str) or not ID.fullmatch(i) or ".." in i:
                sys.exit('%s: "nemesis_conflicts": %r isn\'t a NEMESIS package id' % (mod, i))
            if i not in out:
                out.append(i)
    if pkg in out:
        sys.exit("%s: %s can't refuse itself" % (mod or path, pkg))
    return out


def depends(pkg, needs="", mod=None):
    items = [x for x in needs.split(",") if x]
    need_ids = {x.split("@")[0] for x in items}
    refused = conflicts(pkg, mod)
    bad = need_ids & set(refused)
    if bad:
        sys.exit("%s both needs and refuses %s" % (pkg, ", ".join(sorted(bad))))
    d = ",".join(items + ["!" + i for i in refused])
    if len(d) > 511:   # native/meta.c: depends[512]
        sys.exit("depends= is %d characters, NEMESIS takes at most 511" % len(d))
    return d



if __name__ == "__main__":
    a = sys.argv[1:]
    mod = None
    if len(a) >= 2 and a[-2] == "--mod":
        mod, a = a[-1], a[:-2]
    if a[:1] == ["depends"] and len(a) in (2, 3):
        print(depends(a[1], a[2] if len(a) == 3 else "", mod))
    else:
        sys.exit(__doc__)
