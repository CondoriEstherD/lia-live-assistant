const test = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const path = require('node:path');

const WORKFLOWS_DIR = path.join(__dirname, '..', 'workflows');
const EXPECTED = [
  'lia-customer-status', 'lia-dispatch', 'lia-expiry', 'lia-inbound',
  'lia-pay', 'lia-request-garment', 'lia-search-catalog',
];
const TRIGGER_TYPES = /(webhook|scheduleTrigger|executeWorkflowTrigger|Trigger)$/i;

const deploy = () => import('../scripts/deploy.mjs');

test('include expansion drops require/module.exports lines', async () => {
  const { loadInclude } = await deploy();
  const code = loadInclude('lia-pay-page.js');
  assert.doesNotMatch(code, /require\(/);
  assert.doesNotMatch(code, /module\.exports/);
  assert.match(code, /function buildPayPage/);
});

test('render replaces placeholders, workflow ids and unknown credentials', async () => {
  const { renderWorkflow } = await deploy();
  const wf = {
    name: 'demo',
    nodes: [
      { name: 'Config', type: 'n8n-nodes-base.set', parameters: { value: '__SUPABASE_URL__' } },
      { name: 'Call', type: 'n8n-nodes-base.executeWorkflow', parameters: { workflowId: '__WF:lia-dispatch__' } },
      {
        name: 'RPC', type: 'n8n-nodes-base.httpRequest', parameters: {},
        credentials: { supabaseApi: { id: '__CRED:Supabase Lia__', name: 'Supabase Lia' } },
      },
      { name: 'Code', type: 'n8n-nodes-base.code', parameters: { jsCode: '//@include whatsapp-inbound.js\nreturn [];' } },
    ],
    connections: {},
  };

  const { workflow, warnings } = renderWorkflow(wf, {
    vars: { SUPABASE_URL: 'https://x.supabase.co' }, ids: { 'lia-dispatch': 'abc123' }, credentials: {},
  });

  assert.equal(workflow.nodes[0].parameters.value, 'https://x.supabase.co');
  assert.equal(workflow.nodes[1].parameters.workflowId, 'abc123');
  assert.equal(workflow.nodes[2].credentials, undefined);
  assert.match(workflow.nodes[3].parameters.jsCode, /function classifyMessage/);
  assert.ok(warnings.some((w) => w.includes('Supabase Lia')));
});

const files = fs.existsSync(WORKFLOWS_DIR)
  ? fs.readdirSync(WORKFLOWS_DIR).filter((f) => f.endsWith('.json'))
  : [];

test('all expected workflows exist', () => {
  assert.deepEqual(files.map((f) => f.replace('.json', '')).sort(), EXPECTED);
});

for (const file of files) {
  test(`${file}: valid structure`, async () => {
    const wf = JSON.parse(fs.readFileSync(path.join(WORKFLOWS_DIR, file), 'utf8'));
    assert.equal(wf.name, file.replace('.json', ''));
    for (const field of ['id', 'active', 'tags', 'pinData']) assert.equal(wf[field], undefined, `top-level ${field}`);

    const names = wf.nodes.map((n) => n.name);
    assert.equal(new Set(names).size, names.length, 'node names must be unique');

    const targets = new Set();
    for (const [source, outputs] of Object.entries(wf.connections ?? {})) {
      assert.ok(names.includes(source), `connection source "${source}" exists`);
      for (const branches of Object.values(outputs)) {
        for (const branch of branches ?? []) {
          for (const link of branch ?? []) {
            assert.ok(names.includes(link.node), `connection target "${link.node}" exists`);
            targets.add(link.node);
          }
        }
      }
    }

    for (const node of wf.nodes) {
      const isTrigger = TRIGGER_TYPES.test(node.type);
      const isSubNode = node.type.startsWith('@n8n/n8n-nodes-langchain.') && !node.type.endsWith('.agent');
      if (!isTrigger && !isSubNode) assert.ok(targets.has(node.name), `node "${node.name}" is reachable`);
    }

    const text = JSON.stringify(wf);
    assert.doesNotMatch(text, /eyJ[\w-]{20,}/, 'no JWT-looking secrets');
    assert.doesNotMatch(text, /sk-ant-/, 'no Anthropic keys');
  });

  test(`${file}: Code nodes compile after includes`, async () => {
    const { renderWorkflow } = await deploy();
    const wf = JSON.parse(fs.readFileSync(path.join(WORKFLOWS_DIR, file), 'utf8'));
    const { workflow } = renderWorkflow(wf, { vars: { SUPABASE_URL: 'https://x.supabase.co' } });
    for (const node of workflow.nodes.filter((n) => typeof n.parameters?.jsCode === 'string')) {
      assert.doesNotThrow(
        () => new vm.Script(`(async function () {\n${node.parameters.jsCode}\n})`),
        `Code node "${node.name}" has valid JavaScript`,
      );
    }
  });
}
