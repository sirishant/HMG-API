#!/usr/bin/env python3
"""check-mod-points.py <mod dir>...: every hook point a mod's code names exists in the catalogue,
and every point it hooks (ModContext.hook / Points.hook) is declared in its mod.json. A typo or a
renamed point otherwise only shows on the car, as a mod that stops loading in one app."""
import glob, json, os, re, sys
REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
cat = set()
for f in glob.glob(os.path.join(REPO, 'core/hookpoints/*.json')):
    cat |= {p['id'] for p in json.load(open(f))['points']}
apps = {json.load(open(f))['app'] for f in glob.glob(os.path.join(REPO, 'core/hookpoints/*.json'))}
ID = re.compile(r'"((?:%s)\.[a-z0-9_]+(?:\.[a-z0-9_]+)*)"' % '|'.join(sorted(apps)))
HOOK = re.compile(r'\b(?:mod|ctx|m)\.hook\(\s*[^,]*,\s*(?:"([^"]+)"|([A-Z_]+)(?:\s*\+\s*"([^"]+)")?)')
bad = 0
for d in sys.argv[1:]:
    mj = json.load(open(os.path.join(d, 'mod.json')))
    declared = set((mj.get('hooks') or {}).get('points') or {})
    for p in declared:
        if p not in cat: print('FAIL %s: mod.json declares %s, not in the catalogue' % (d, p)); bad += 1
    for src in glob.glob(os.path.join(d, 'src/**/*.java'), recursive=True):
        text = open(src).read()
        consts = dict(re.findall(r'static final String ([A-Z_]+)\s*=\s*"([^"]+)"', text))
        for m in ID.finditer(text):
            pid = m.group(1)
            if pid not in cat and not any(pid.startswith(c) for c in ('framework.navigation_cluster.updateTBT',)):
                print('FAIL %s: names %s, not in the catalogue' % (os.path.relpath(src, d), pid)); bad += 1
        for m in HOOK.finditer(text):
            lit, const, suffix = m.groups()
            pid = lit if lit else (consts.get(const, '') + (suffix or '')) if const else None
            if not pid or '.' not in pid or pid.endswith('.'): continue   # a name built at run time: not checked here
            if pid not in declared:
                print('FAIL %s: hooks %s, not declared in mod.json' % (os.path.relpath(src, d), pid)); bad += 1
print(('ok    hook point names in %d mod(s)' % len(sys.argv[1:])) if not bad else 'FAILED: %d' % bad)
sys.exit(1 if bad else 0)
