#!/usr/bin/env node
// Deploys the Lia workflows in n8n/workflows/*.json to the team's n8n instance through the public API.
//
//   node n8n/scripts/deploy.mjs             create or update every workflow (matched by name)
//   node n8n/scripts/deploy.mjs --dry-run   only render to n8n/dist/ (for manual import), no network
//
// API key: env N8N_API_KEY, or a file ~/.lia-n8n-key containing only the key. Never commit it.
// Optional env: N8N_URL (default https://marilyncd12.app.n8n.cloud), SUPABASE_URL,
// WA_VERIFY_TOKEN, WA_GRAPH_VERSION (default v21.0).

import { readFileSync, readdirSync, writeFileSync, mkdirSync, existsSync } from 'node:fs';
import { homedir } from 'node:os';
import { join, dirname, basename } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
export const WORKFLOWS_DIR = join(ROOT, 'workflows');
export const CODE_DIR = join(ROOT, 'code');
const DIST_DIR = join(ROOT, 'dist');
export const DEFAULT_N8N_URL = 'https://marilyncd12.app.n8n.cloud';

/** Contents of n8n/code/<file> without CommonJS require/exports lines. */
export function loadInclude(file, codeDir = CODE_DIR) {
  const source = readFileSync(join(codeDir, file), 'utf8');
  const lines = source.split(/\r?\n/);
  const out = [];
  let skippingExports = false;
  for (const line of lines) {
    if (/^\s*const\s+.*=\s*require\(.*\);?\s*$/.test(line)) continue;
    if (/^\s*module\.exports\s*=/.test(line)) {
      skippingExports = !line.includes('};') && !/=\s*\w+;?\s*$/.test(line);
      continue;
    }
    if (skippingExports) {
      if (line.includes('};')) skippingExports = false;
      continue;
    }
    out.push(line);
  }
  return out.join('\n');
}

export function expandIncludes(jsCode, codeDir = CODE_DIR) {
  return jsCode.replace(/^[ \t]*\/\/@include[ \t]+([\w.-]+\.js)[ \t]*$/gm, (_, file) => loadInclude(file, codeDir));
}

/**
 * Renders a workflow file: expands //@include lines in Code nodes and replaces placeholders.
 * `ids` maps workflow name -> id; `credentials` maps credential name -> id (missing ones are removed).
 */
export function renderWorkflow(workflow, { vars = {}, ids = {}, credentials = null, codeDir = CODE_DIR } = {}) {
  const wf = structuredClone(workflow);
  const warnings = [];

  for (const node of wf.nodes ?? []) {
    if (typeof node.parameters?.jsCode === 'string') {
      node.parameters.jsCode = expandIncludes(node.parameters.jsCode, codeDir);
    }
    if (node.credentials) {
      for (const [type, ref] of Object.entries(node.credentials)) {
        const match = /^__CRED:(.+)__$/.exec(ref?.id ?? '');
        if (!match) continue;
        const id = credentials?.[match[1]];
        if (id) {
          node.credentials[type] = { id, name: match[1] };
        } else {
          delete node.credentials[type];
          warnings.push(`${wf.name}/${node.name}: select credential "${match[1]}" in the n8n UI`);
        }
      }
      if (Object.keys(node.credentials).length === 0) delete node.credentials;
    }
  }

  let text = JSON.stringify(wf);
  text = text.replace(/__WF:([\w-]+)__/g, (all, name) => {
    if (ids[name]) return ids[name];
    warnings.push(`${wf.name}: workflow "${name}" has no id yet`);
    return all;
  });
  for (const [key, value] of Object.entries(vars)) {
    if (value) text = text.split(`__${key}__`).join(JSON.stringify(String(value)).slice(1, -1));
  }
  const left = [...new Set(text.match(/__(SUPABASE_URL|WA_GRAPH_VERSION|WA_VERIFY_TOKEN|N8N_BASE_URL)__/g) ?? [])];
  for (const placeholder of left) warnings.push(`${wf.name}: ${placeholder} not set`);

  return { workflow: JSON.parse(text), warnings };
}

export function loadWorkflows(dir = WORKFLOWS_DIR) {
  return readdirSync(dir)
    .filter((f) => f.endsWith('.json'))
    .sort()
    .map((f) => ({ file: f, workflow: JSON.parse(readFileSync(join(dir, f), 'utf8')) }));
}

function readApiKey() {
  if (process.env.N8N_API_KEY) return process.env.N8N_API_KEY.trim();
  const file = join(homedir(), '.lia-n8n-key');
  if (existsSync(file)) return readFileSync(file, 'utf8').trim();
  return null;
}

async function api(baseUrl, key, method, path, body) {
  const res = await fetch(`${baseUrl}/api/v1${path}`, {
    method,
    headers: { 'X-N8N-API-KEY': key, 'Content-Type': 'application/json', Accept: 'application/json' },
    body: body ? JSON.stringify(body) : undefined,
  });
  const text = await res.text();
  if (!res.ok) throw new Error(`${method} ${path} -> ${res.status}: ${text.slice(0, 500)}`);
  return text ? JSON.parse(text) : null;
}

async function listAll(baseUrl, key, path) {
  const items = [];
  let cursor = '';
  do {
    const sep = path.includes('?') ? '&' : '?';
    const page = await api(baseUrl, key, 'GET', `${path}${sep}limit=100${cursor ? `&cursor=${cursor}` : ''}`);
    items.push(...(page?.data ?? []));
    cursor = page?.nextCursor ?? '';
  } while (cursor);
  return items;
}

function payload(wf) {
  return { name: wf.name, nodes: wf.nodes, connections: wf.connections, settings: wf.settings ?? {} };
}

async function main() {
  const dryRun = process.argv.includes('--dry-run');
  const baseUrl = (process.env.N8N_URL || DEFAULT_N8N_URL).replace(/\/$/, '');
  const vars = {
    SUPABASE_URL: process.env.SUPABASE_URL,
    WA_GRAPH_VERSION: process.env.WA_GRAPH_VERSION || 'v21.0',
    WA_VERIFY_TOKEN: process.env.WA_VERIFY_TOKEN,
    N8N_BASE_URL: baseUrl,
  };
  const files = loadWorkflows();
  const warnings = [];

  if (dryRun) {
    mkdirSync(DIST_DIR, { recursive: true });
    for (const { file, workflow } of files) {
      const rendered = renderWorkflow(workflow, { vars });
      warnings.push(...rendered.warnings);
      writeFileSync(join(DIST_DIR, file), JSON.stringify(rendered.workflow, null, 2));
    }
    console.log(`Rendered ${files.length} workflows to n8n/dist/`);
  } else {
    const key = readApiKey();
    if (!key) {
      console.error('Missing API key: set N8N_API_KEY or create ~/.lia-n8n-key');
      process.exit(1);
    }

    let credentials = null;
    try {
      const creds = await listAll(baseUrl, key, '/credentials');
      credentials = Object.fromEntries(creds.map((c) => [c.name, c.id]));
    } catch (err) {
      warnings.push(`Could not list credentials (${err.message.split(':')[0]}); select them in the UI`);
    }

    const existing = await listAll(baseUrl, key, '/workflows');
    const ids = Object.fromEntries(existing.map((w) => [w.name, w.id]));

    // Pass 1: make sure every workflow exists so cross references get real ids.
    for (const { workflow } of files) {
      if (ids[workflow.name]) continue;
      const created = await api(baseUrl, key, 'POST', '/workflows', {
        name: workflow.name, nodes: [], connections: {}, settings: { executionOrder: 'v1' },
      });
      ids[workflow.name] = created.id;
      console.log(`created ${workflow.name} (${created.id})`);
    }

    // Pass 2: upload the full rendered definitions.
    for (const { workflow } of files) {
      const rendered = renderWorkflow(workflow, { vars, ids, credentials });
      warnings.push(...rendered.warnings);
      await api(baseUrl, key, 'PUT', `/workflows/${ids[workflow.name]}`, payload(rendered.workflow));
      console.log(`updated ${workflow.name} -> ${baseUrl}/workflow/${ids[workflow.name]}`);
    }
  }

  if (warnings.length) {
    console.log('\nPending:');
    for (const w of [...new Set(warnings)]) console.log(`  - ${w}`);
  }
}

if (process.argv[1] && basename(process.argv[1]) === 'deploy.mjs') {
  main().catch((err) => {
    console.error(err.message);
    process.exit(1);
  });
}
