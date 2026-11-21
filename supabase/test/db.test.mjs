// SQL tests for the Lia database, run on PGlite (in-memory Postgres). `npm test`.
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import { PGlite } from '@electric-sql/pglite';

const migrationsDir = path.join(path.dirname(fileURLToPath(import.meta.url)), '..', 'migrations');

// Mimics what Supabase provides before our migrations run: roles, auth.jwt(), default grants.
const SUPABASE_PRELUDE = `
  create role anon nologin;
  create role authenticated nologin;
  create role service_role nologin bypassrls;
  create schema auth;
  create function auth.jwt() returns jsonb language sql stable as $$
    select coalesce(nullif(current_setting('request.jwt.claims', true), ''), '{}')::jsonb
  $$;
  grant usage on schema auth, public to anon, authenticated, service_role;
  grant execute on function auth.jwt() to anon, authenticated, service_role;
  alter default privileges in schema public grant all on tables to anon, authenticated, service_role;
  alter default privileges in schema public grant all on sequences to anon, authenticated, service_role;
  alter default privileges in schema public grant execute on functions to anon, authenticated, service_role;
`;

const WA = 'PN1';
const T0 = new Date('2026-11-21T20:00:00Z');
const at = (seconds) => new Date(T0.getTime() + seconds * 1000).toISOString();

async function freshDb() {
  const db = new PGlite();
  await db.exec(SUPABASE_PRELUDE);
  for (const file of readdirSync(migrationsDir).filter((f) => f.endsWith('.sql')).sort()) {
    await db.exec(readFileSync(path.join(migrationsDir, file), 'utf8'));
  }
  const seller = await one(db, `insert into sellers (clerk_user_id, whatsapp_number, wa_phone_number_id, name)
    values ('user_1', '+59170000000', $1, 'Tienda') returning id`, [WA]);
  await db.query(`insert into live_sessions (seller_id) values ($1)`, [seller.id]);
  return { db, sellerId: seller.id };
}

async function one(db, sql, params = []) {
  return (await db.query(sql, params)).rows[0];
}

async function addGarment(db, sellerId, code, { stock = 1, price = 150 } = {}) {
  return one(db, `insert into garments (seller_id, code, name, price, stock) values ($1, $2, 'Vestido', $3, $4) returning *`,
    [sellerId, code, price, stock]);
}

let msg = 0;
async function rpc(db, fn, args) {
  const keys = Object.keys(args);
  const sql = `select ${fn}(${keys.map((k, i) => `${k} => $${i + 1}`).join(', ')}) as r`;
  return (await one(db, sql, keys.map((k) => args[k]))).r;
}

const request = (db, phone, code, ts, now = ts) => rpc(db, 'lia_request_garment', {
  p_wa_phone_number_id: WA, p_customer_phone: phone, p_customer_name: phone, p_code: code,
  p_wa_message_id: `wamid.${++msg}`, p_message_ts: ts, p_now: now,
});
const confirm = (db, phone, now) => rpc(db, 'lia_confirm_request', {
  p_wa_phone_number_id: WA, p_customer_phone: phone, p_wa_message_id: `wamid.${++msg}`, p_message_ts: now, p_now: now,
});
const expire = (db, now) => rpc(db, 'lia_expire_reservations', { p_now: now });
const pay = (db, token, now, provider = 'lia_pay_mock', amount = 150) => rpc(db, 'lia_confirm_payment', {
  p_token: token, p_amount: amount, p_paid_at: now, p_provider: provider, p_now: now,
});

async function qrToken(db, reservationId) {
  return (await one(db, `select token from payment_qrs where reservation_id = $1`, [reservationId])).token;
}

async function notifications(db, kind) {
  return (await db.query(`select * from notifications where kind = $1 order by created_at`, [kind])).rows;
}

test('code -> photo -> SI gives the free unit an active reservation and a 5-minute QR', async () => {
  const { db, sellerId } = await freshDb();
  await addGarment(db, sellerId, 'A12');

  const req = await request(db, '591700001', 'a12', at(0));
  assert.equal(req.status, 'pending_confirmation');
  assert.equal(req.garment.code, 'A12');
  assert.equal(Number(req.garment.price), 150);

  const conf = await confirm(db, '591700001', at(10));
  assert.equal(conf.status, 'active');
  assert.equal(new Date(conf.expires_at).toISOString(), at(310));

  const [qr] = await notifications(db, 'reservation_qr');
  assert.ok(qr.payload.qr_token.length >= 32);
  const row = await one(db, `select qr_expires_at from payment_qrs where token = $1`, [qr.payload.qr_token]);
  assert.equal(new Date(row.qr_expires_at).toISOString(), at(310)); // R4: qr_expires_at = expires_at
});

test('R1 + R3: queue follows WhatsApp message time and the next QR waits for the previous one', async () => {
  const { db, sellerId } = await freshDb();
  await addGarment(db, sellerId, 'A12');

  await request(db, 'ana', 'A12', at(0));
  await request(db, 'bea', 'A12', at(20), at(21));
  await request(db, 'cami', 'A12', at(5), at(22)); // wrote before bea, arrived later
  assert.equal((await confirm(db, 'ana', at(30))).status, 'active');
  const bea = await confirm(db, 'bea', at(31));
  assert.equal(bea.status, 'queued');
  const cami = await confirm(db, 'cami', at(32));
  assert.equal(cami.status, 'queued');
  assert.equal(cami.position, 1); // R1: cami's message is older
  assert.equal((await notifications(db, 'reservation_qr')).length, 1); // R3

  const result = await expire(db, at(30 + 301));
  assert.equal(result.expired, 1);
  assert.equal(result.promoted, 1);
  const qrs = await notifications(db, 'reservation_qr');
  assert.equal(qrs.length, 2);
  assert.equal(qrs[1].payload.reservation_id, cami.reservation_id);
  assert.equal((await notifications(db, 'qr_expired')).length, 1);
});

test('R4: an expired QR cannot be paid with Lia Pay and records no payment', async () => {
  const { db, sellerId } = await freshDb();
  await addGarment(db, sellerId, 'A12');
  await request(db, 'ana', 'A12', at(0));
  const conf = await confirm(db, 'ana', at(10));
  const token = await qrToken(db, conf.reservation_id);

  const page = await rpc(db, 'lia_get_payment_qr', { p_token: token, p_now: at(311) });
  assert.equal(page.status, 'expired'); // even before the cron runs
  assert.equal((await pay(db, token, at(311))).status, 'expired');
  assert.equal((await one(db, `select count(*)::int as n from payments`)).n, 0);
});

test('payment in time sells the unit once, frees nobody else and notifies buyer, seller and queue', async () => {
  const { db, sellerId } = await freshDb();
  const g = await addGarment(db, sellerId, 'A12');
  await request(db, 'ana', 'A12', at(0));
  const conf = await confirm(db, 'ana', at(10));
  await request(db, 'bea', 'A12', at(20));
  await confirm(db, 'bea', at(25));
  const token = await qrToken(db, conf.reservation_id);

  const page = await rpc(db, 'lia_get_payment_qr', { p_token: token, p_now: at(100) });
  assert.equal(page.status, 'active');
  assert.equal(page.seconds_left, 210);

  assert.equal((await pay(db, token, at(100))).status, 'paid');
  assert.equal((await pay(db, token, at(101))).status, 'already_paid'); // no double payment

  const garment = await one(db, `select stock, status from garments where id = $1`, [g.id]);
  assert.deepEqual(garment, { stock: 0, status: 'sold' });
  assert.equal((await one(db, `select count(*)::int as n from sales where channel = 'whatsapp'`)).n, 1);
  assert.equal((await notifications(db, 'payment_confirmed')).length, 1);
  assert.equal((await notifications(db, 'garment_sold')).length, 1);
  assert.equal((await notifications(db, 'sold_out')).length, 1); // bea, who was waiting
});

test('wrong amount is rejected; a late payment from a real provider is kept for the seller (R5)', async () => {
  const { db, sellerId } = await freshDb();
  await addGarment(db, sellerId, 'A12');
  await request(db, 'ana', 'A12', at(0));
  const conf = await confirm(db, 'ana', at(10));
  const token = await qrToken(db, conf.reservation_id);

  assert.equal((await pay(db, token, at(20), 'lia_pay_mock', 100)).status, 'amount_mismatch');
  await expire(db, at(400));
  assert.equal((await pay(db, token, at(400), 'bank_x')).status, 'late');
  assert.equal((await notifications(db, 'late_payment')).length, 1);
});

test('webhook retries, unknown codes, no live and unknown sellers', async () => {
  const { db, sellerId } = await freshDb();
  await addGarment(db, sellerId, 'A12');
  const args = {
    p_wa_phone_number_id: WA, p_customer_phone: 'ana', p_customer_name: 'Ana', p_code: 'A12',
    p_wa_message_id: 'wamid.same', p_message_ts: at(0), p_now: at(0),
  };
  assert.equal((await rpc(db, 'lia_request_garment', args)).status, 'pending_confirmation');
  assert.equal((await rpc(db, 'lia_request_garment', args)).status, 'duplicate');
  assert.equal((await request(db, 'ana', 'Z99', at(1))).status, 'not_found');
  assert.equal((await rpc(db, 'lia_request_garment', { ...args, p_wa_phone_number_id: 'other', p_wa_message_id: 'x' })).status,
    'unknown_seller');
  await db.query(`update live_sessions set status = 'ended'`);
  assert.equal((await request(db, 'ana', 'A12', at(2))).status, 'no_live');
});

test('confirmation window, reminder and cancel', async () => {
  const { db, sellerId } = await freshDb();
  await addGarment(db, sellerId, 'A12');
  await addGarment(db, sellerId, 'B01');

  await request(db, 'ana', 'A12', at(0));
  assert.equal((await confirm(db, 'ana', at(121))).status, 'confirmation_expired');

  await request(db, 'bea', 'B01', at(0));
  const cancel = await rpc(db, 'lia_cancel_request', {
    p_wa_phone_number_id: WA, p_customer_phone: 'bea', p_wa_message_id: 'wamid.no', p_message_ts: at(5), p_now: at(5),
  });
  assert.equal(cancel.status, 'cancelled');

  await request(db, 'cami', 'B01', at(10));
  await confirm(db, 'cami', at(10));
  assert.equal((await expire(db, at(250))).reminders, 1);
  assert.equal((await expire(db, at(260))).reminders, 0);
});

test('notifications are claimed once and come back after 2 minutes if unsent', async () => {
  const { db, sellerId } = await freshDb();
  await addGarment(db, sellerId, 'A12');
  await request(db, 'ana', 'A12', at(0));
  await confirm(db, 'ana', at(10));

  const claim = async (now) => (await db.query(`select * from lia_claim_notifications(20, $1)`, [now])).rows;
  const first = await claim(at(11));
  assert.equal(first.length, 1);
  assert.equal(first[0].kind, 'reservation_qr');
  assert.equal(first[0].customer_phone, 'ana');
  assert.equal(first[0].wa_phone_number_id, WA);
  assert.equal((await claim(at(12))).length, 0);
  assert.equal((await claim(at(11 + 121))).length, 1);
  await rpc(db, 'lia_mark_notification_sent', { p_id: first[0].id, p_now: at(200) });
  assert.equal((await claim(at(400))).length, 0);
});

test('R6: a buyer cannot hold more than the seller limit', async () => {
  const { db, sellerId } = await freshDb();
  for (const code of ['A1', 'A2', 'A3']) await addGarment(db, sellerId, code);
  for (const [i, code] of ['A1', 'A2'].entries()) {
    await request(db, 'ana', code, at(i * 10));
    assert.equal((await confirm(db, 'ana', at(i * 10 + 1))).status, 'active');
  }
  await request(db, 'ana', 'A3', at(30));
  const third = await confirm(db, 'ana', at(31));
  assert.deepEqual(third, { status: 'limit_reached', limit: 2 });
});

test('agent tools only read the seller catalog and the buyer orders', async () => {
  const { db, sellerId } = await freshDb();
  await addGarment(db, sellerId, 'A12');
  await addGarment(db, sellerId, 'B01', { stock: 0 });
  const found = await rpc(db, 'lia_search_catalog', { p_wa_phone_number_id: WA, p_query: 'vestido', p_limit: 5 });
  assert.deepEqual(found.items.map((g) => g.code), ['A12']);

  await request(db, 'ana', 'A12', at(0));
  const status = await rpc(db, 'lia_customer_status', { p_wa_phone_number_id: WA, p_customer_phone: 'ana' });
  assert.equal(status.items[0].status, 'pending_confirmation');
});

async function asSeller(db, clerkUserId, fn) {
  await db.exec(`set request.jwt.claims = '${JSON.stringify({ sub: clerkUserId, role: 'authenticated' })}'`);
  await db.exec(`set role authenticated`);
  try {
    return await fn();
  } finally {
    await db.exec(`reset role; reset request.jwt.claims`);
  }
}

test('manual sale cannot take a unit held by an active WhatsApp reservation; void restores stock', async () => {
  const { db, sellerId } = await freshDb();
  const g = await addGarment(db, sellerId, 'A12', { stock: 2 });
  await request(db, 'ana', 'A12', at(0));
  await confirm(db, 'ana', at(1));

  await asSeller(db, 'user_1', async () => {
    const tooMany = await rpc(db, 'record_manual_sale', { p_garment_id: g.id, p_quantity: 2 });
    assert.deepEqual(tooMany, { status: 'not_enough_available', available: 1 });
    const sale = await rpc(db, 'record_manual_sale', { p_garment_id: g.id, p_quantity: 1 });
    assert.equal(sale.status, 'sold');
    assert.equal((await rpc(db, 'void_sale', { p_sale_id: sale.sale_id })).status, 'voided');
  });
  assert.equal((await one(db, `select stock from garments where id = $1`, [g.id])).stock, 2);
});

test('security: the app sees only its own data and nobody outside n8n can call payment functions', async () => {
  const { db, sellerId } = await freshDb();
  await addGarment(db, sellerId, 'A12');
  const other = await one(db, `insert into sellers (clerk_user_id, whatsapp_number) values ('user_2', '+591') returning id`);
  await addGarment(db, other.id, 'Z01');

  const codes = await asSeller(db, 'user_1', async () =>
    (await db.query(`select code from garments order by code`)).rows.map((r) => r.code));
  assert.deepEqual(codes, ['A12']);

  for (const role of ['anon', 'authenticated']) {
    await db.exec(`set role ${role}`);
    await assert.rejects(db.query(`select lia_confirm_payment('t', 1, now())`), /permission denied/);
    await assert.rejects(db.query(`select * from lia_claim_notifications(1)`), /permission denied/);
    await db.exec(`reset role`);
  }
  await db.exec(`set role anon`);
  await assert.rejects(db.query(`select * from garments`), /permission denied/);
  await db.exec(`reset role`);

  await asSeller(db, 'user_1', async () => {
    await assert.rejects(db.query(`update reservations set status = 'paid'`), /permission denied/);
  });
});

test('end live releases waiting buyers but keeps the buyer who already holds a QR', async () => {
  const { db, sellerId } = await freshDb();
  await addGarment(db, sellerId, 'A12');
  await request(db, 'ana', 'A12', at(0));
  await confirm(db, 'ana', at(1));
  await request(db, 'bea', 'A12', at(2));
  await confirm(db, 'bea', at(3));

  const ended = await asSeller(db, 'user_1', () => rpc(db, 'end_live', {}));
  assert.equal(ended.released, 1);
  const statuses = (await db.query(`select status from reservations order by message_ts`)).rows.map((r) => r.status);
  assert.deepEqual(statuses, ['active', 'cancelled']);
  assert.equal((await notifications(db, 'live_ended')).length, 1);
});

test('security: the app edits garment details but stock and status only through set_garment_stock', async () => {
  const { db, sellerId } = await freshDb();
  const g = await addGarment(db, sellerId, 'A1', { stock: 2 });
  await request(db, 'ana', 'A1', at(0));
  await confirm(db, 'ana', at(1));
  await request(db, 'bea', 'A1', at(2));
  await confirm(db, 'bea', at(3));
  await request(db, 'cami', 'A1', at(4));
  await confirm(db, 'cami', at(5));

  await asSeller(db, 'user_1', async () => {
    await db.query(`update garments set name = 'Vestido azul', price = 160 where id = $1`, [g.id]);
    await assert.rejects(db.query(`update garments set stock = 1 where id = $1`, [g.id]), /permission denied/);
    await assert.rejects(db.query(`update garments set status = 'sold' where id = $1`, [g.id]), /permission denied/);
    await assert.rejects(db.query(`delete from garments where id = $1`, [g.id]), /permission denied/);
    const low = await rpc(db, 'set_garment_stock', { p_garment_id: g.id, p_stock: 1 });
    assert.deepEqual(low, { status: 'below_reserved', reserved: 2 });
    const up = await rpc(db, 'set_garment_stock', { p_garment_id: g.id, p_stock: 3 });
    assert.equal(up.status, 'updated');
  });
  // The extra unit goes to cami, who was waiting.
  assert.equal((await notifications(db, 'reservation_qr')).length, 3);
});

test('security: a Clerk user cannot claim a WhatsApp number or another account', async () => {
  const { db } = await freshDb();
  await asSeller(db, 'attacker', async () => {
    await assert.rejects(db.query(
      `insert into sellers (clerk_user_id, whatsapp_number, wa_phone_number_id) values ('attacker', '+591', 'PN_X')`),
      /permission denied/);
    await db.query(`insert into sellers (clerk_user_id, whatsapp_number) values ('attacker', '+591')`);
    await assert.rejects(db.query(`update sellers set wa_phone_number_id = 'PN_X'`), /permission denied/);
    await assert.rejects(db.query(`insert into sellers (clerk_user_id, whatsapp_number) values ('user_1', '+591')`),
      /row-level security/);
  });
});

test('the buyer has 1 minute to confirm the photo; the QR is sent only after confirming', async () => {
  const { db, sellerId } = await freshDb();
  await addGarment(db, sellerId, 'A12');
  await addGarment(db, sellerId, 'B01');

  const pending = await request(db, 'ana', 'A12', at(0));
  assert.equal(pending.status, 'pending_confirmation');
  assert.equal(new Date(pending.confirm_deadline).toISOString(), at(60));
  assert.equal((await one(db, `select count(*)::int as n from payment_qrs`)).n, 0, 'no QR before confirming');

  assert.equal((await confirm(db, 'ana', at(59))).status, 'active');
  assert.equal((await one(db, `select count(*)::int as n from payment_qrs`)).n, 1, 'QR only after confirming');

  await request(db, 'bea', 'B01', at(0));
  assert.equal((await confirm(db, 'bea', at(61))).status, 'confirmation_expired');
  assert.equal((await one(db, `select count(*)::int as n from payment_qrs`)).n, 1, 'late confirmation gets no QR');
});
