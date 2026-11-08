#!/usr/bin/env python3
"""The community mod catalogue: catalog/<id>.json, one per mod, added by pull request.
Each entry points at the author's own signed release zip (pinned by SHA-256) and repo;
the author's key goes on HMG-API's trusted author list (maps/authors.json) with it, so
owners install the mod without the unknown-author warning once a HMG-API release
carries the list. The wiki's Community mods page is made from the entries.

  tools/catalog.py add <zip> --download <url> [--repo <url>]
        write catalog/<id>.json from a signed package (tools/mkmodpkg.sh) and list its
        author key in maps/authors.json; an update keeps the entry's author key
  tools/catalog.py check [--download] [--base <git ref>]
        every entry well-formed and its key listed; --download fetches each zip, checks
        its SHA-256 and signature and that it is the mod and version the entry says;
        --base refuses an entry whose author key changed, a version that went back, and
        changes to authors who aren't the ones the changed entries name
  tools/catalog.py fetch <dir> <id>...
        each entry's download into <dir>/<id>-<version>.zip, after the same checks as
        check --download (the release bundles the mods tools/release-mods.txt names so)
  tools/catalog.py wiki <out.md>
        the Community mods page
"""
import argparse, hashlib, json, os, re, shutil, subprocess, sys, tempfile, urllib.request, zipfile

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CATALOG = os.path.join(REPO, 'catalog')
AUTHORS = os.path.join(REPO, 'maps', 'authors.json')
MAX_FILES, MAX_BYTES = 2000, 64 << 20   # the engine's limits (Packages.java)

ID = re.compile(r'^[a-z0-9][a-z0-9-]{0,63}$')
UUID = re.compile(r'^[0-9a-f]{8}-[0-9a-f]{4}-8[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$')
VERSION = re.compile(r'^\d+\.\d+\.\d+([-+][0-9A-Za-z.-]+)?$')
HTTPS = re.compile(r'^https://[A-Za-z0-9.-]+(:\d+)?/[^\s<>()\[\]"\']*$')
FIELDS = {'id', 'name', 'description', 'authors', 'license', 'uuid', 'version', 'repo', 'download', 'sha256', 'system', 'hmgapi'}
NEEDED = ['id', 'name', 'description', 'authors', 'uuid', 'version', 'repo', 'download', 'sha256']


def die(msg):
    print(msg, file=sys.stderr)
    sys.exit(1)


def load(path):
    with open(path) as f:
        return json.load(f)


def save(path, obj):
    with open(path, 'w') as f:
        json.dump(obj, f, indent=2, ensure_ascii=False)
        f.write('\n')


def vkey(v):
    """a version for ordering: numbers, then a release above its pre-releases"""
    m = re.match(r'^(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z.-]+))?', v)
    if not m:
        return (0, 0, 0, 0, '')
    return (int(m[1]), int(m[2]), int(m[3]), 0 if m[4] else 1, m[4] or '')


def unpack(zpath, dest):
    """the package's files under dest (refusing paths out of it, links and oversize),
    then its mod folder: dest itself or its one subfolder, as the engine takes it"""
    with zipfile.ZipFile(zpath) as z:
        infos = [i for i in z.infolist() if not i.is_dir()]
        if len(infos) > MAX_FILES or sum(i.file_size for i in infos) > MAX_BYTES:
            die(f'{zpath}: more than {MAX_FILES} files or {MAX_BYTES >> 20} MB')
        for i in infos:
            n = i.filename
            if n.startswith('/') or '\\' in n or '..' in n.split('/') or (i.external_attr >> 16) & 0o170000 == 0o120000:
                die(f'{zpath}: refused entry {n!r}')
            out = os.path.join(dest, n)
            os.makedirs(os.path.dirname(out), exist_ok=True)
            with z.open(i) as src, open(out, 'wb') as dst:
                shutil.copyfileobj(src, dst)
    if os.path.isfile(os.path.join(dest, 'mod.json')):
        return dest
    subs = [d for d in os.listdir(dest) if os.path.isdir(os.path.join(dest, d))]
    if len(subs) == 1 and os.path.isfile(os.path.join(dest, subs[0], 'mod.json')):
        return os.path.join(dest, subs[0])
    die(f'{zpath}: no mod.json')


_engine = None


def verify(moddir, uuid=None):
    """hmgapi.engine.Signing verify, the same code the head unit runs (compiled once per
    run; HMGAPI_ENGINE_CLASSES: already compiled from this repo's engine, as the tests do)"""
    global _engine
    given = os.environ.get('HMGAPI_ENGINE_CLASSES', '')
    if _engine is None and given and os.path.isdir(os.path.join(given, 'hmgapi', 'engine')):
        _engine = given
    if _engine is None:
        _engine = tempfile.mkdtemp(prefix='catalog-engine-')
        src = [os.path.join(REPO, 'core/runtime/java/hmgapi/engine', f)
               for f in os.listdir(os.path.join(REPO, 'core/runtime/java/hmgapi/engine')) if f.endswith('.java')]
        subprocess.run(['javac', '--release', '8', '-Xlint:-options', '-nowarn', '-d', _engine] + src,
                       check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    r = subprocess.run(['java', '-cp', _engine, 'hmgapi.engine.Signing', 'verify', moddir] + ([uuid] if uuid else []),
                       capture_output=True, text=True)
    return r.returncode == 0, (r.stdout + r.stderr).strip()


def package(zpath):
    """mod.json, the signed manifest and the SHA-256 of a package, after its signature checks"""
    work = tempfile.mkdtemp(prefix='catalog-pkg-')
    try:
        d = unpack(zpath, work)
        ok, out = verify(d)
        if not ok:
            die(f'{zpath}: {out}')
        mod = load(os.path.join(d, 'mod.json'))
        man = load(os.path.join(d, 'META-HMGAPI', 'manifest.json'))
    finally:
        shutil.rmtree(work, ignore_errors=True)
    h = hashlib.sha256()
    with open(zpath, 'rb') as f:
        for b in iter(lambda: f.read(1 << 20), b''):
            h.update(b)
    return mod, man, h.hexdigest()


def fetch(url, dest):
    req = urllib.request.Request(url, headers={'User-Agent': 'HMG-API catalog check'})
    with urllib.request.urlopen(req, timeout=60) as r, open(dest, 'wb') as f:
        n = 0
        for b in iter(lambda: r.read(1 << 20), b''):
            n += len(b)
            if n > MAX_BYTES:
                die(f'{url}: bigger than {MAX_BYTES >> 20} MB')
            f.write(b)


def entries():
    if not os.path.isdir(CATALOG):
        return {}
    return {f[:-5]: load(os.path.join(CATALOG, f)) for f in sorted(os.listdir(CATALOG)) if f.endswith('.json')}


# ---- add


def cmd_add(a):
    mod, man, sha = package(a.zip)
    mid, ver, uuid = man['id'], man['version'], man['uuid'].lower()
    if mod.get('id') != mid or mod.get('version') != ver:
        die(f'{a.zip}: mod.json says {mod.get("id")} {mod.get("version")}, the signature {mid} {ver}')
    repo = a.repo or mod.get('repo')
    if not repo:
        die('no repo: give --repo <url> (or put "repo" in mod.json)')
    authors = mod.get('authors') or []
    if not authors:
        die('mod.json has no "authors"')
    path = os.path.join(CATALOG, mid + '.json')
    if os.path.exists(path):
        old = load(path)
        if old.get('uuid', '').lower() != uuid:
            die(f'{mid} is in the catalogue under author key {old.get("uuid")}; this package is signed with {uuid}. '
                'Only the same key updates a mod (owners would have to remove it first): pick a new id, or sign with the original key.')
        if vkey(ver) < vkey(old.get('version', '0.0.0')):
            die(f'{mid} {ver} is older than the catalogue\'s {old.get("version")}')
    e = {'id': mid, 'name': mod.get('name', mid), 'description': mod.get('description', ''), 'authors': authors,
         'license': mod.get('license', ''), 'uuid': uuid, 'version': ver, 'repo': repo, 'download': a.download, 'sha256': sha}
    if mod.get('hmgapi'):
        e['hmgapi'] = mod['hmgapi']
    if mod.get('system'):
        e['system'] = mod['system']
    if not e['license']:
        del e['license']
    os.makedirs(CATALOG, exist_ok=True)
    save(path, e)
    au = load(AUTHORS)
    listed = au['authors'].get(uuid)
    if listed is None:
        au['authors'][uuid] = {'name': authors[0], 'repos': [repo]}
    else:
        listed.setdefault('repos', [])
        if not any(repo == r or repo.startswith(r.rstrip('/') + '/') for r in listed['repos']):
            listed['repos'].append(repo)
    save(AUTHORS, au)
    print(f'catalog/{mid}.json: {mid} {ver}, author key {uuid}' + ('' if listed else f' (added to maps/authors.json as {authors[0]})'))
    problems = check_all(entries(), au)
    if problems:
        die('\n'.join(problems))


# ---- check


def check_entry(name, e, authors):
    p = []
    say = lambda m: p.append(f'catalog/{name}.json: {m}')
    if not isinstance(e, dict):
        return [f'catalog/{name}.json: not an object']
    for k in NEEDED:
        if not e.get(k):
            say(f'no "{k}"')
    for k in e:
        if k not in FIELDS:
            say(f'unknown field "{k}"')
    if e.get('id') != name:
        say(f'"id" {e.get("id")!r} isn\'t the file name')
    if not ID.match(name):
        say('the id is lowercase letters, digits and dashes (64 at most)')
    if e.get('uuid') and not UUID.match(str(e['uuid'])):
        say(f'"uuid" {e["uuid"]!r} isn\'t an author key UUID (lowercase)')
    elif e.get('uuid') and e['uuid'] not in authors:
        say(f'author key {e["uuid"]} isn\'t in maps/authors.json')
    if e.get('version') and not VERSION.match(str(e['version'])):
        say(f'"version" {e["version"]!r} isn\'t x.y.z')
    for k in ('repo', 'download'):
        if e.get(k) and not HTTPS.match(str(e[k])):
            say(f'"{k}" isn\'t an https:// link')
    if e.get('sha256') and not re.match(r'^[0-9a-f]{64}$', str(e['sha256'])):
        say('"sha256" isn\'t 64 lowercase hex digits')
    if not isinstance(e.get('authors', []), list) or not all(isinstance(x, str) and x for x in e.get('authors', [])):
        say('"authors" is a list of names')
    for k in ('name', 'description', 'license'):
        if k in e and (not isinstance(e[k], str) or '\n' in e[k]):
            say(f'"{k}" is one line of text')
    return p


def check_all(es, au):
    authors = au.get('authors', {})
    p = []
    for uuid, a in authors.items():
        if not UUID.match(uuid):
            p.append(f'maps/authors.json: {uuid!r} isn\'t an author key UUID (lowercase)')
        if not isinstance(a, dict) or not a.get('name'):
            p.append(f'maps/authors.json: {uuid} has no "name"')
        for r in (a.get('repos') or []) if isinstance(a, dict) else []:
            if not HTTPS.match(str(r)):
                p.append(f'maps/authors.json: {uuid}: {r!r} isn\'t an https:// link')
    for name, e in es.items():
        p += check_entry(name, e, authors)
    return p


def git_show(ref, path):
    r = subprocess.run(['git', '-C', REPO, 'show', f'{ref}:{path}'], capture_output=True, text=True)
    return json.loads(r.stdout) if r.returncode == 0 else None


def check_base(es, au, ref):
    p = []
    changed = set()
    for name, e in es.items():
        old = git_show(ref, f'catalog/{name}.json')
        if old == e:
            continue
        changed.add(str(e.get('uuid', '')).lower())
        if old is None:
            continue
        if str(old.get('uuid', '')).lower() != str(e.get('uuid', '')).lower():
            p.append(f'catalog/{name}.json: the author key changed ({old.get("uuid")} to {e.get("uuid")}); only the same key updates a mod')
        if vkey(str(e.get('version', ''))) < vkey(str(old.get('version', ''))):
            p.append(f'catalog/{name}.json: version went back ({old.get("version")} to {e.get("version")})')
    r = subprocess.run(['git', '-C', REPO, 'ls-tree', '--name-only', ref, 'catalog/'], capture_output=True, text=True)
    for f in r.stdout.split():
        n = os.path.basename(f)[:-5]
        if f.endswith('.json') and n not in es:
            changed.add(str((git_show(ref, f) or {}).get('uuid', '')).lower())   # a removal: its author's to make
    old_au = (git_show(ref, 'maps/authors.json') or {}).get('authors', {})
    new_au = au.get('authors', {})
    for uuid in set(old_au) | set(new_au):
        if old_au.get(uuid) != new_au.get(uuid) and uuid not in changed:
            p.append(f'maps/authors.json: {uuid} changed, but no catalogue entry of that author key did '
                     '(a submission changes only its own author; the maintainer edits the rest)')
    return p, changed


def cmd_check(a):
    es, au = entries(), load(AUTHORS)
    p = check_all(es, au)
    if a.base:
        bp, changed = check_base(es, au, a.base)
        p += bp
    if a.download and not p:
        todo = [n for n, e in es.items() if not a.base or git_show(a.base, f'catalog/{n}.json') != e]
        work = tempfile.mkdtemp(prefix='catalog-dl-')
        try:
            for n in todo:
                z, why = download(n, es[n], work)
                if why:
                    p.append(why)
        finally:
            shutil.rmtree(work, ignore_errors=True)
    for x in p:
        print('FAIL  ' + x)
    if p:
        sys.exit(1)
    print(f'ok    {len(es)} catalogue entries, {len(au.get("authors", {}))} authors')


def download(n, e, work):
    """an entry's download in work/: its SHA-256, signature (by the entry's author key), id
    and version checked. The zip's path, or None and why not."""
    z = os.path.join(work, n + '.zip')
    try:
        fetch(e['download'], z)
    except Exception as x:
        return None, f'catalog/{n}.json: can\'t download {e["download"]}: {x}'
    with open(z, 'rb') as f:
        sha = hashlib.sha256(f.read()).hexdigest()
    if sha != e['sha256']:
        return None, f'catalog/{n}.json: the download\'s SHA-256 is {sha}, the entry says {e["sha256"]}'
    d = unpack(z, os.path.join(work, n))
    ok, out = verify(d, e['uuid'])
    if not ok:
        return None, f'catalog/{n}.json: {out}'
    man = load(os.path.join(d, 'META-HMGAPI', 'manifest.json'))
    if (man['id'], man['version']) != (e['id'], e['version']):
        return None, f'catalog/{n}.json: the download is {man["id"]} {man["version"]}, the entry says {e["id"]} {e["version"]}'
    print(f'ok    {n} {e["version"]}: downloaded, SHA-256 and signature match (author key {e["uuid"]})')
    return z, None


# ---- fetch


def cmd_fetch(a):
    es, authors = entries(), load(AUTHORS).get('authors', {})
    p = [f'{n}: not in the catalogue (no catalog/{n}.json)' for n in a.ids if n not in es]
    for n in a.ids:
        if n in es:
            p += check_entry(n, es[n], authors)
    if not p:
        os.makedirs(a.dir, exist_ok=True)
        work = tempfile.mkdtemp(prefix='catalog-fetch-')
        try:
            for n in a.ids:
                z, why = download(n, es[n], work)
                if why:
                    p.append(why)
                else:
                    shutil.copyfile(z, os.path.join(a.dir, f'{n}-{es[n]["version"]}.zip'))
        finally:
            shutil.rmtree(work, ignore_errors=True)
    for x in p:
        print('FAIL  ' + x)
    if p:
        sys.exit(1)


# ---- wiki


def md(s):
    """text for a Markdown table cell or line: no markup, links or HTML from an entry"""
    return re.sub(r'([\\`*_{}\[\]<>()#+!|~])', r'\\\1', str(s))


def cars(system):
    """mod.json "system" in words"""
    if not system:
        return 'any car HMG-API supports'
    out = []
    for s in system if isinstance(system, list) else [system]:
        bits = []
        for k in ('model', 'region', 'firmware', 'platform'):
            v = s.get(k)
            if v:
                bits.append((', '.join(map(str, v)) if isinstance(v, list) else str(v)) + (' head units' if k == 'platform' else ''))
        if s.get('features'):
            bits.append('needs ' + ', '.join(s['features']))
        out.append(' · '.join(bits))
    return '; '.join(out)


def cmd_wiki(a):
    es, au = entries(), load(AUTHORS).get('authors', {})
    L = ['# Community mods', '',
         'Mods by HMG-API\'s community, each from its author\'s own repository and releases.',
         'Every download here was checked when it was added: its SHA-256, and that it is',
         'signed by the author key shown. Install them as in',
         '[Installing and removing mods](Installing-and-removing-mods).', '',
         'A mod\'s code runs inside the head unit\'s apps. Being listed means the package is',
         'whole and signed by its author, not that HMG-API reviewed or tested it on your car:',
         'check its repository and the cars it lists.', '',
         'Author keys listed here install without the unknown-author warning once your',
         'HMG-API release carries them (the list ships with each release).', '']
    if not es:
        L += ['No mods yet.', '']
    for n, e in sorted(es.items(), key=lambda kv: kv[1].get('name', kv[0]).lower()):
        L += [f'## {md(e["name"])}', '', md(e.get('description', '')), '',
              '| | |', '|---|---|',
              f'| By | {md(", ".join(e.get("authors", [])))} |',
              f'| Version | {md(e["version"])} |',
              f'| Cars | {md(cars(e.get("system")))} |']
        if e.get('hmgapi'):
            L.append(f'| HMG-API | {md(e["hmgapi"])} |')
        if e.get('license'):
            L.append(f'| License | {md(e["license"])} |')
        L += [f'| Repository | <{e["repo"]}> |',
              f'| Download | [{md(e["id"])}-{md(e["version"])}.zip](<{e["download"]}>) |',
              f'| SHA-256 | `{e["sha256"]}` |',
              f'| Author key | `{e["uuid"]}` |', '']
    L += ['## Adding your mod', '',
          'See [Making mods](Making-mods#listing-your-mod): one pull request adds your mod',
          'here and your author key to the trusted list.', '']
    with open(a.out, 'w') as f:
        f.write('\n'.join(L))
    print(f'{a.out}: {len(es)} mods')


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest='cmd', required=True)
    s = sub.add_parser('add')
    s.add_argument('zip')
    s.add_argument('--download', required=True, help='https link to this exact zip (e.g. a GitHub release asset)')
    s.add_argument('--repo', help='https link to the mod\'s source (default: mod.json "repo")')
    s = sub.add_parser('check')
    s.add_argument('--download', action='store_true')
    s.add_argument('--base')
    s = sub.add_parser('fetch')
    s.add_argument('dir')
    s.add_argument('ids', nargs='+')
    s = sub.add_parser('wiki')
    s.add_argument('out')
    a = ap.parse_args()
    if a.cmd == 'add' and not HTTPS.match(a.download):
        die('--download is an https:// link')
    if a.cmd == 'add' and a.repo and not HTTPS.match(a.repo):
        die('--repo is an https:// link')
    {'add': cmd_add, 'check': cmd_check, 'fetch': cmd_fetch, 'wiki': cmd_wiki}[a.cmd](a)
    if _engine and _engine != os.environ.get('HMGAPI_ENGINE_CLASSES'):   # ours, not the caller's
        shutil.rmtree(_engine, ignore_errors=True)


if __name__ == '__main__':
    main()
