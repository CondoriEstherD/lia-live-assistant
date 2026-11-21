# Workflow file conventions (n8n 2.48.x, n8n Cloud `marilyncd12.app.n8n.cloud`)

Every file in this folder is one n8n workflow JSON, deployable with `node n8n/scripts/deploy.mjs`.
The deploy script fills the placeholders below, so workflow files never contain secrets or instance IDs.

## File shape

```json
{
  "name": "lia-inbound",
  "nodes": [ { "id": "<uuid>", "name": "...", "type": "...", "typeVersion": 1, "position": [x, y], "parameters": {} } ],
  "connections": { "<source node name>": { "main": [ [ { "node": "<target name>", "type": "main", "index": 0 } ] ] } },
  "settings": { "executionOrder": "v1", "timezone": "America/La_Paz" }
}
```

- `name` equals the file name without `.json`.
- Node names are unique, short, English, and describe the step (`Parse message`, `RPC confirm request`).
- No `id`, `active`, `tags`, `pinData` or `meta` at the top level (the public API rejects read-only fields).

## Placeholders (replaced by the deploy script)

| Placeholder | Replaced with |
|---|---|
| `__SUPABASE_URL__` | env `SUPABASE_URL` (e.g. `https://xyz.supabase.co`) |
| `__WA_GRAPH_VERSION__` | env `WA_GRAPH_VERSION` (default `v21.0`, check Meta's current version) |
| `__WA_VERIFY_TOKEN__` | env `WA_VERIFY_TOKEN` |
| `__N8N_BASE_URL__` | `https://marilyncd12.app.n8n.cloud` |
| `__WF:<workflow-name>__` | id of that workflow after deployment (used in Execute Workflow / workflow tool nodes) |
| `//@include <file>.js` (a full line inside a Code node `jsCode`) | contents of `n8n/code/<file>.js` without `require(...)` and `module.exports` lines |

n8n Cloud Starter has no `$vars`, so configuration lives in a first `Set` node named `Config`
(fields `supabaseUrl`, `waGraphVersion`, `waVerifyToken`, `payBaseUrl` = `__N8N_BASE_URL__/webhook/lia-pay`) filled with the placeholders.
Later nodes read it with `$('Config').first().json.<field>`.

## Credentials (created by hand in the n8n UI, referenced by name)

| Credential name | n8n credential type | Used by |
|---|---|---|
| `Supabase Lia` | `supabaseApi` (host + service role key) | every RPC call |
| `WhatsApp Lia` | `whatsAppApi` (access token + business account id) | every WhatsApp send |
| `Anthropic Lia` | `anthropicApi` | AI Agent chat model |

In JSON: `"credentials": { "supabaseApi": { "id": "__CRED:Supabase Lia__", "name": "Supabase Lia" } }`.
The deploy script resolves the id by name, or removes the block so the user selects it in the UI.

## Calling Supabase RPC

HTTP Request node: `POST {{ $('Config').first().json.supabaseUrl }}/rest/v1/rpc/<function>`,
authentication `predefinedCredentialType`, `nodeCredentialType: "supabaseApi"`, JSON body with the
`p_...` parameters from `n8n/CONTRACT.md`. Function names and parameters must match `CONTRACT.md` exactly.

## Sending WhatsApp messages

HTTP Request node: `POST https://graph.facebook.com/{{ $('Config').first().json.waGraphVersion }}/{{ phone_number_id }}/messages`,
authentication `predefinedCredentialType`, `nodeCredentialType: "whatsAppApi"`, JSON body = the object built by
`n8n/code/lia-messages.js` (never rebuild message text in nodes; texts live only in `lia-messages.js`).

## Code nodes

Code node (`n8n-nodes-base.code`) with `jsCode` that starts with the needed `//@include` lines and then the glue
code. Do not copy function bodies by hand.

## Workflow names and ids

`lia-inbound`, `lia-request-garment`, `lia-dispatch`, `lia-expiry`, `lia-pay`, `lia-search-catalog`, `lia-customer-status`.
