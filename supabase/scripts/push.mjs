#!/usr/bin/env node
// Applies supabase/migrations/*.sql to a Supabase project through the Management API.
//
//   node supabase/scripts/push.mjs            apply pending migrations
//   node supabase/scripts/push.mjs --dry-run  only show what would be applied
//
// Needs in .env (repo root) or in the environment:
//   SUPABASE_ACCESS_TOKEN  personal access token (https://supabase.com/dashboard/account/tokens)
//   SUPABASE_PROJECT_REF   project ref (the "xyz" in https://xyz.supabase.co), or SUPABASE_URL
//
// Safety: each migration runs in one transaction together with its record in
// lia_schema_migrations, so it is applied completely or not at all. Already applied
// migrations are skipped; if one of them was edited afterwards the script stops.

import { readFileSync, readdirSync, existsSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const here = path.dirname(fileURLToPath(import.meta.url));
const repoRoot = path.resolve(here, '..', '..');
const migrationsDir = path.resolve(here, '..', 'migrations');
const dryRun = process.argv.includes('--dry-run');

const envFile = path.join(repoRoot, '.env');
if (existsSync(envFile)) process.loadEnvFile(envFile);

function fail(message) {
  console.error(`\n✖ ${message}\n`);
  process.exit(1);
}

const token = process.env.SUPABASE_ACCESS_TOKEN?.trim();
const ref = (process.env.SUPABASE_PROJECT_REF ?? process.env.SUPABASE_URL?.match(/https:\/\/([a-z0-9]+)\.supabase\.co/)?.[1])?.trim();
if (!token) fail('Missing SUPABASE_ACCESS_TOKEN (create one at https://supabase.com/dashboard/account/tokens and put it in .env).');
if (!ref) fail('Missing SUPABASE_PROJECT_REF or SUPABASE_URL in .env.');

async function query(sql, { readOnly = false } = {}) {
  const response = await fetch(`https://api.supabase.com/v1/projects/${ref}/database/query`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({ query: sql, read_only: readOnly }),
  });
  const text = await response.text();
  if (!response.ok) {
    throw new Error(`HTTP ${response.status}: ${text}`);
  }
  return text ? JSON.parse(text) : [];
}

const files = readdirSync(migrationsDir).filter((f) => f.endsWith('.sql')).sort();
for (const file of files) {
  if (!/^\d+_[a-z0-9_]+\.sql$/.test(file)) fail(`Unexpected migration file name: ${file}`);
}
const migrations = files.map((file) => {
  const sql = readFileSync(path.join(migrationsDir, file), 'utf8');
  return { file, sql, checksum: createHash('sha256').update(sql).digest('hex') };
});

console.log(`Supabase project: ${ref}`);
console.log(`Migrations in repo: ${migrations.length}`);

let tracked;
try {
  [tracked] = await query(
    `select to_regclass('public.lia_schema_migrations') is not null as tracked,
            to_regclass('public.garments') is not null as has_schema`,
    { readOnly: true },
  );
} catch (error) {
  fail(`Cannot reach the project. Check the token and the project ref.\n  ${error.message}`);
}

if (!tracked.tracked && tracked.has_schema) {
  fail('The database already has Lia tables that were not created by this script.\n' +
    '  Nothing was changed. Ask the team before touching it (a reset would delete data).');
}

const applied = new Map();
if (tracked.tracked) {
  const rows = await query(`select name, checksum from public.lia_schema_migrations`, { readOnly: true });
  for (const row of rows) applied.set(row.name, row.checksum);
}

for (const [name, checksum] of applied) {
  const local = migrations.find((m) => m.file === name);
  if (local && local.checksum !== checksum) {
    fail(`${name} was already applied but the file changed afterwards.\n` +
      '  Never edit an applied migration: add a new file with the change.');
  }
}

const pending = migrations.filter((m) => !applied.has(m.file));
if (pending.length === 0) {
  console.log('✔ Database is up to date. Nothing to apply.');
  process.exit(0);
}
console.log(`Pending: ${pending.map((m) => m.file).join(', ')}`);
if (dryRun) {
  console.log('(dry run: nothing applied)');
  process.exit(0);
}

if (!tracked.tracked) {
  await query(`
    create table if not exists public.lia_schema_migrations (
      name text primary key,
      checksum text not null,
      applied_at timestamptz not null default now()
    );
    alter table public.lia_schema_migrations enable row level security;
    revoke all on public.lia_schema_migrations from anon, authenticated;
  `);
}

for (const m of pending) {
  process.stdout.write(`→ ${m.file} ... `);
  try {
    await query(`begin;\n${m.sql}\n;insert into public.lia_schema_migrations (name, checksum) ` +
      `values ('${m.file}', '${m.checksum}');\ncommit;`);
    console.log('ok');
  } catch (error) {
    console.log('FAILED');
    fail(`${m.file} was not applied (the transaction was rolled back).\n  ${error.message}`);
  }
}

const [check] = await query(
  `select count(*)::int as functions from pg_proc p join pg_namespace n on n.oid = p.pronamespace
   where n.nspname = 'public' and p.proname like 'lia\\_%'`,
  { readOnly: true },
);
console.log(`✔ Done. Lia functions in the database: ${check.functions}`);
