import { test } from 'node:test';
import assert from 'node:assert/strict';
import { execFile } from 'node:child_process';
import { createHash } from 'node:crypto';
import { existsSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, statSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

// deploy/release/assemble.sh on a small flat download laid out like the model team's.
const SCRIPT = join(dirname(fileURLToPath(import.meta.url)), '..', '..', 'deploy', 'release', 'assemble.sh');

const sha256 = (b) => createHash('sha256').update(b).digest('hex');

function run(args) {
  return new Promise((resolve) => {
    execFile('bash', [SCRIPT, ...args], { timeout: 60_000 }, (err, stdout, stderr) =>
      resolve({ code: err ? err.code : 0, stdout, stderr }));
  });
}

/** A flat download: top-level files, models/ and reports/ entries all at the top, .done; plus a previous stage folder. */
function download({ manifestEdit = (rows) => rows, done = true, omit = [] } = {}) {
  const root = mkdtempSync(join(tmpdir(), 'assemble-'));
  const dl = join(root, 'dl');
  mkdirSync(dl);
  const files = {
    'aoi_list.parquet': 'PAR1 aoi list',
    'fire_events.parquet': 'PAR1 events',
    'models/m2v_meta.json': '{"method_version": "v1-m2v-2026-11-14"}',
    'models/grid_occurrence_meta.json': '{"model": "grid"}',
    'reports/occurrence_metrics.csv': 'a,b\n1,2\n',
  };
  const rows = Object.entries(files).map(([f, body]) => [f, f.endsWith('.parquet') ? '1' : '', String(Buffer.byteLength(body)), sha256(body)]);
  for (const [f, body] of Object.entries(files)) {
    if (!omit.includes(f)) {
      writeFileSync(join(dl, f.split('/').pop()), body);
    }
  }
  writeFileSync(join(dl, 'release_manifest.csv'), ['file,rows,bytes,sha256', ...manifestEdit(rows).map((r) => r.join(','))].join('\n') + '\n');
  writeFileSync(join(dl, 'dl.log'), 'rc=0\n');
  if (done) {
    writeFileSync(join(dl, '.done'), '');
  }
  const prev = join(root, 'prev');
  for (const d of ['harmonized_hotspots/reference/2025', 'harmonized_hotspots/other', 'harmonized_hotspots_static_flags']) {
    mkdirSync(join(prev, d), { recursive: true });
  }
  writeFileSync(join(prev, 'harmonized_hotspots/reference/2025/part-0.parquet'), 'PAR1 points');
  writeFileSync(join(prev, 'harmonized_hotspots_static_flags/2025.parquet'), 'PAR1 flags');
  return { root, dl, prev, files };
}

test('links the manifest layout, a top-level m2v_meta.json and the hotspot trees; SHA256SUMS over the top level', async () => {
  const { root, dl, prev, files } = download();
  const stage = join(root, 'stage');
  const r = await run(['--hotspots-from', prev, dl, stage]);
  assert.equal(r.code, 0, r.stderr);
  for (const f of Object.keys(files)) {
    assert.equal(statSync(join(stage, f)).ino, statSync(join(dl, f.split('/').pop())).ino, `${f} is a hardlink of the download`);
  }
  assert.equal(statSync(join(stage, 'm2v_meta.json')).ino, statSync(join(dl, 'm2v_meta.json')).ino);
  assert.equal(statSync(join(stage, 'release_manifest.csv')).ino, statSync(join(dl, 'release_manifest.csv')).ino);
  assert.ok(existsSync(join(stage, 'harmonized_hotspots/reference/2025/part-0.parquet')));
  assert.ok(existsSync(join(stage, 'harmonized_hotspots_static_flags/2025.parquet')));
  assert.ok(!existsSync(join(stage, 'dl.log')) && !existsSync(join(stage, '.done')) && !existsSync(join(stage + '.tmp')));
  const top = readdirSync(stage).filter((f) => statSync(join(stage, f)).isFile() && f !== 'SHA256SUMS')
    .sort((a, b) => (Buffer.from(a) < Buffer.from(b) ? -1 : 1));
  assert.deepEqual(top, ['aoi_list.parquet', 'fire_events.parquet', 'm2v_meta.json', 'release_manifest.csv']);
  const expected = top.map((f) => `${sha256(readFileSync(join(stage, f)))}  ${f}\n`).join('');
  assert.equal(readFileSync(join(stage, 'SHA256SUMS'), 'utf8'), expected);
  assert.match(r.stdout, new RegExp(`manifest_sha256=${sha256(expected)}`));
  assert.match(r.stdout, /top-level copies m2v_meta\.json \(= models\/m2v_meta\.json\)/);
});

test('refuses an existing target and leaves it untouched', async () => {
  const { root, dl } = download();
  const stage = join(root, 'stage');
  mkdirSync(stage);
  writeFileSync(join(stage, 'keep'), 'x');
  const r = await run([dl, stage]);
  assert.notEqual(r.code, 0);
  assert.match(r.stderr, /already exists/);
  assert.deepEqual(readdirSync(stage), ['keep']);
});

test('a byte count or sha256 that differs from the manifest fails and leaves no stage folder', async () => {
  for (const edit of [
    (rows) => rows.map((r) => (r[0] === 'reports/occurrence_metrics.csv' ? [r[0], r[1], '999', r[3]] : r)),
    (rows) => rows.map((r) => (r[0] === 'aoi_list.parquet' ? [r[0], r[1], r[2], '0'.repeat(64)] : r)),
    (rows) => rows.map((r) => (r[0] === 'models/m2v_meta.json' ? [r[0], r[1], r[2], 'f'.repeat(64)] : r)),
  ]) {
    const { root, dl } = download({ manifestEdit: edit });
    const stage = join(root, 'stage');
    const r = await run([dl, stage]);
    assert.notEqual(r.code, 0, r.stdout);
    assert.match(r.stderr, /manifest/);
    assert.ok(!existsSync(stage) && !existsSync(stage + '.tmp'));
  }
});

test('an entry missing from the download is taken from --extra-from, else it fails', async () => {
  const { root, dl } = download({ omit: ['reports/occurrence_metrics.csv'] });
  const r = await run([dl, join(root, 'stage')]);
  assert.notEqual(r.code, 0);
  assert.match(r.stderr, /reports\/occurrence_metrics\.csv is listed but not in the download/);
  const extra = join(root, 'add');
  mkdirSync(extra);
  writeFileSync(join(extra, 'occurrence_metrics.csv'), 'a,b\n1,2\n');
  const ok = await run(['--extra-from', extra, dl, join(root, 'stage')]);
  assert.equal(ok.code, 0, ok.stderr);
  assert.equal(statSync(join(root, 'stage/reports/occurrence_metrics.csv')).ino, statSync(join(extra, 'occurrence_metrics.csv')).ino);
  assert.match(ok.stdout, /from --extra-from 1/);
});

test('an unfinished download (no .done) is refused', async () => {
  const { root, dl } = download({ done: false });
  const r = await run([dl, join(root, 'stage')]);
  assert.notEqual(r.code, 0);
  assert.match(r.stderr, /\.done/);
});
