#!/usr/bin/env python3
"""A mod's "hmgapi" floor and hook points, against the HMG-API it's built with
(docs/mod-repos.md section 4). In the mod SDK it's bin/check-points.py; the mod
workflow runs it on every mod before building.

    check-points.py <mod dir>... [--repo Open-HMG/HMG-API]

For each mod: the floor (the lower bound of "hmgapi": ">=1.0.2", "^1.0.2", "~1.0.2",
"=1.0.2" or "1.0.2") is at or below this SDK's VERSION, and every point in
hooks.points is in the floor release's list of points. That list is the release's SDK
points.txt (its hmgapi-mod-sdk zip, checked against the release's SHA256SUMS); a
release before 1.0.3 has no SDK, so it's the core/hookpoints/*.json at its tag.
HMGAPI_FLOOR_POINTS=<file> uses that list instead of fetching one (tests, offline).
"""
import glob, hashlib, io, json, os, re, subprocess, sys, tempfile, urllib.request, zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
FIRST_SDK = (1, 0, 3, "")


def ver(s):
    m = re.fullmatch(r"v?(\d+)(?:\.(\d+))?(?:\.(\d+))?(?:-([0-9A-Za-z.-]+))?", s.strip())
    if not m:
        raise ValueError("not a version: %r" % s)
    return (int(m.group(1)), int(m.group(2) or 0), int(m.group(3) or 0), m.group(4) or "")


def key(v):   # "-tag" sorts before the plain release, as hmgapi.engine.Version
    return v[:3] + ((0, v[3]) if v[3] else (1, ""))


def text(v):
    return "%d.%d.%d" % v[:3] + ("-" + v[3] if v[3] else "")


def floor(rng):
    """the lowest release a "hmgapi" range takes, or None when it has no lower bound"""
    for part in (rng or "").split():
        m = re.fullmatch(r"(>=|\^|~|=)?(v?\d[0-9A-Za-z.-]*)", part)
        if m:
            return ver(m.group(2))
    return None


def sdk_version():
    f = os.path.join(HERE, "..", "VERSION")
    if os.path.isfile(f):
        return ver(open(f).read())
    src = open(os.path.join(HERE, "..", "core/runtime/java/hmgapi/engine/Engine.java")).read()
    return ver(re.search(r'HMGAPI_VERSION = "(.*?)";', src).group(1))


def points_of(cat_files):
    return {p["id"] for f in cat_files for p in json.load(open(f))["points"]}


def fetch_points(v, repo):
    if os.environ.get("HMGAPI_FLOOR_POINTS"):
        return set(open(os.environ["HMGAPI_FLOOR_POINTS"]).read().split())
    tag = "v" + text(v)
    if key(v) < key(FIRST_SDK):   # no SDK in that release: its catalogue, at its tag
        with tempfile.TemporaryDirectory() as d:
            subprocess.run(["git", "clone", "-q", "--depth", "1", "--branch", tag, "https://github.com/%s" % repo, d], check=True)
            return points_of(sorted(glob.glob(os.path.join(d, "core/hookpoints/*.json"))))
    base = "https://github.com/%s/releases/download/%s/" % (repo, tag)
    name = "hmgapi-mod-sdk-%s.zip" % text(v)
    sums = urllib.request.urlopen(base + "SHA256SUMS").read().decode()
    want = [l.split()[0] for l in sums.splitlines() if l.split()[1:] == [name]]
    z = urllib.request.urlopen(base + name).read()
    if not want or hashlib.sha256(z).hexdigest() != want[0]:
        sys.exit("%s: sha256 isn't the one in %s's SHA256SUMS" % (name, tag))
    return set(zipfile.ZipFile(io.BytesIO(z)).read("points.txt").decode().split())


def main(argv):
    repo = "Open-HMG/HMG-API"
    if "--repo" in argv:
        i = argv.index("--repo")
        repo = argv[i + 1]
        argv = argv[:i] + argv[i + 2:]
    if not argv:
        sys.exit(__doc__)
    sdk = sdk_version()
    cache, bad = {}, 0
    for d in argv:
        m = json.load(open(os.path.join(d, "mod.json")))
        mid, rng = m.get("id", d), m.get("hmgapi", "")
        points = sorted(((m.get("hooks") or {}).get("points") or {}))
        try:
            f = floor(rng)
        except ValueError as e:
            print("FAIL  %s: \"hmgapi\": %s" % (mid, e)); bad = 1; continue
        if f is not None and key(f) > key(sdk):
            print("FAIL  %s: \"hmgapi\": %r needs HMG-API %s, but the SDK is %s (pin a newer SDK in hmgapi-sdk.json)"
                  % (mid, rng, text(f), text(sdk)))
            bad = 1; continue
        if not points:
            print("ok    %s: \"hmgapi\" %r, SDK %s, no hook points" % (mid, rng, text(sdk))); continue
        if f is None:
            print("FAIL  %s: uses hook points, so \"hmgapi\" needs a floor (\">=1.0.2\"), not %r" % (mid, rng))
            bad = 1; continue
        if text(f) not in cache:
            cache[text(f)] = fetch_points(f, repo)
        missing = [p for p in points if p not in cache[text(f)]]
        if missing:
            print("FAIL  %s: not in HMG-API %s's hook points: %s (raise \"hmgapi\" to the release that has them)"
                  % (mid, text(f), ", ".join(missing)))
            bad = 1; continue
        print("ok    %s: \"hmgapi\" %r, SDK %s, %d hook points all in %s" % (mid, rng, text(sdk), len(points), text(f)))
    return bad


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
