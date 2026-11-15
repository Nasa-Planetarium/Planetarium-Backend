#!/bin/bash
# Assemble a stage folder from the model team's release download, by hardlinks (the download is never written).
#
# deploy/release/assemble.sh [--hotspots-from <dir>] [--extra-from <dir>] <download_dir> <stage_dir>
#
# The download is flat and has no SHA256SUMS; the backend stages a folder laid out as release_manifest.csv lists it and
# identifies it by SHA256SUMS (data_version = <release_key>#<sha256(SHA256SUMS)[0:12]>, activate --expect-sha256). So:
# 1. every release_manifest.csv entry is linked at its listed path (top level, models/, reports/), plus the manifest;
# an entry missing from the download is taken from --extra-from (files the model team re-published separately);
# 2. each file a loader reads at the top of the folder (LOADER_TOP_LEVEL) that the manifest lists only under a
# subdirectory is linked at the top as well (today m2v_meta.json; the backend checks that copy against the listed
# entry by base name);
# 3. the hotspot trees (harmonized_hotspots/, harmonized_hotspots_static_flags/) are linked from --hotspots-from
# (the previous stage folder; the download ships them only as tar files);
# 4. SHA256SUMS = sha256sum of every top-level file, LC_ALL=C order;
# 5. every SHA256SUMS line, every manifest bytes value and the sha256 of every subdirectory entry (not in SHA256SUMS)
# are checked against the manifest; then the folder is moved into place. An existing <stage_dir> is refused.
# Source and stage must be on one filesystem (hardlinks). Needs bash, GNU coreutils/findutils and python3.
# docs/INGEST.md "Assembling a v1.2 stage folder"; tools/test/assemble.test.mjs.
set -Eeuo pipefail

LOADER_TOP_LEVEL=(m2v_meta.json)
HOTSPOT_TREES=(harmonized_hotspots harmonized_hotspots_static_flags)

usage() {
    echo "usage: $0 [--hotspots-from <dir>] [--extra-from <dir>] <download_dir> <stage_dir>" >&2
    exit 2
}

HOTSPOTS=""
EXTRA=""
ARGS=()
while [ $# -gt 0 ]; do
    case "$1" in
        --hotspots-from) [ $# -ge 2 ] || usage; HOTSPOTS="$2"; shift 2 ;;
        --hotspots-from=*) HOTSPOTS="${1#*=}"; shift ;;
        --extra-from) [ $# -ge 2 ] || usage; EXTRA="$2"; shift 2 ;;
        --extra-from=*) EXTRA="${1#*=}"; shift ;;
        -h|--help) usage ;;
        -*) echo "unknown option $1" >&2; usage ;;
        *) ARGS+=("$1"); shift ;;
    esac
done
[ ${#ARGS[@]} -eq 2 ] || usage
S="${ARGS[0]%/}"
T="${ARGS[1]%/}"
TMP="$T.tmp"

fail() {
    echo "assemble: $*" >&2
    exit 1
}

[ -d "$S" ] || fail "download folder $S does not exist"
[ -f "$S/release_manifest.csv" ] || fail "$S has no release_manifest.csv"
[ -e "$S/.done" ] || fail "$S has no .done (download not finished)"
[ ! -e "$T" ] || fail "$T already exists; refusing to touch it"
[ ! -e "$TMP" ] || fail "$TMP already exists (an earlier run?); remove it by hand"
[ -d "$(dirname "$T")" ] || fail "parent of $T does not exist"
[ -z "$EXTRA" ] || [ -d "$EXTRA" ] || fail "--extra-from $EXTRA does not exist"
if [ -n "$HOTSPOTS" ]; then
    for d in "${HOTSPOT_TREES[@]}"; do
        [ -d "$HOTSPOTS/$d" ] || fail "--hotspots-from $HOTSPOTS has no $d/"
    done
fi

echo "start $(date -u +%FT%TZ)"
mkdir "$TMP"
trap 'echo "assemble: failed; removing $TMP" >&2; rm -rf -- "$TMP"' ERR

python3 -I -B - "$S" "$TMP" "$EXTRA" "${LOADER_TOP_LEVEL[@]}" <<'PY'
import csv, os, sys
S, T, X, top_level = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4:]
with open(os.path.join(S, "release_manifest.csv"), newline="") as fh:
    rows = list(csv.DictReader(fh))
files = [r["file"] for r in rows]
if not rows or any(not f or f.startswith("/") or ".." in f.split("/") for f in files) or len(set(files)) != len(files):
    sys.exit("assemble: release_manifest.csv lists an empty, absolute, '..' or repeated path")

def source(f):
    """Entry f: the download's copy at its listed path, else flat by base name; then the same in --extra-from."""
    for d in [S] + ([X] if X else []):
        for p in (os.path.join(d, f), os.path.join(d, os.path.basename(f))):
            if os.path.isfile(p):
                if d == X:
                    taken.append(f)
                return p
    sys.exit("assemble: %s is listed but not in the download%s" % (f, " nor in --extra-from" if X else ""))

taken = []

for f in files:
    os.makedirs(os.path.dirname(os.path.join(T, f)), exist_ok=True)
    os.link(source(f), os.path.join(T, f))
os.link(os.path.join(S, "release_manifest.csv"), os.path.join(T, "release_manifest.csv"))
extra = []
for name in top_level:
    nested = [f for f in files if "/" in f and os.path.basename(f) == name]
    if name not in files and len(nested) == 1:
        os.link(os.path.join(T, nested[0]), os.path.join(T, name))
        extra.append("%s (= %s)" % (name, nested[0]))
print("linked manifest files", len(files), "| from --extra-from", len(taken), "| top-level copies", ", ".join(extra) or "none")
PY

if [ -n "$HOTSPOTS" ]; then
    for d in "${HOTSPOT_TREES[@]}"; do
        cp -al -- "$HOTSPOTS/$d" "$TMP/$d"
        echo "$d: $(find "$TMP/$d" -type f | wc -l) files"
        find "$TMP/$d" -mindepth 1 -maxdepth 1 -type d -printf '%f\n' | LC_ALL=C sort | while read -r sub; do
            echo "  $d/$sub: $(ls "$TMP/$d/$sub" | wc -l) entries"
        done
    done
else
    echo "no --hotspots-from: the stage folder has no hotspot trees"
fi

(cd "$TMP" && find . -maxdepth 1 -type f ! -name SHA256SUMS -printf '%f\n' | LC_ALL=C sort | xargs -d '\n' sha256sum > SHA256SUMS)

python3 -I -B - "$TMP" <<'PY'
import csv, hashlib, os, sys
T = sys.argv[1]
with open(os.path.join(T, "release_manifest.csv"), newline="") as fh:
    m = {r["file"]: r for r in csv.DictReader(fh)}

def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for block in iter(lambda: fh.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()

bad = []
n = 0
for line in open(os.path.join(T, "SHA256SUMS")):
    digest, name = line.rstrip("\n").split("  ", 1)
    n += 1
    if name == "release_manifest.csv":
        continue
    entry = m.get(name)
    if entry is None:
        nested = [r for f, r in m.items() if "/" in f and os.path.basename(f) == name]
        entry = nested[0] if len(nested) == 1 else None
    if entry is None:
        bad.append("%s: in SHA256SUMS, not in the manifest" % name)
    elif entry["sha256"].lower() != digest.lower():
        bad.append("%s: sha256 %s != manifest %s (%s)" % (name, digest, entry["sha256"], entry["file"]))
for f, r in m.items():
    size = os.path.getsize(os.path.join(T, f))
    if size != int(r["bytes"]):
        bad.append("%s: %d bytes != manifest %s" % (f, size, r["bytes"]))
    elif "/" in f and sha256(os.path.join(T, f)) != r["sha256"].lower():
        bad.append("%s: sha256 != manifest %s" % (f, r["sha256"]))
print("SHA256SUMS lines", n, "| manifest entries", len(m), "| mismatches", len(bad))
for b in bad:
    print("  " + b, file=sys.stderr)
sys.exit(1 if bad else 0)
PY

trap - ERR
mv -- "$TMP" "$T"
echo "ASSEMBLED $T manifest_sha256=$(sha256sum "$T/SHA256SUMS" | cut -d' ' -f1) end $(date -u +%FT%TZ)"
