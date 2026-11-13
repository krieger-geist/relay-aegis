'use strict';

const http = require('node:http');
const { randomUUID } = require('node:crypto');
const port = Number(process.env.PORT || 3000);
const startedAt = Date.now();
// Simulated failure for runbook demos: POST /chaos/pool-exhausted makes every later request fail the way an
// application with a leaked database connection pool does, until the process restarts.
let poolExhausted = false;

// A tiny in-memory orders list for the user journey demo. POST /chaos/corrupt-record stores one order the list page
// cannot read, so GET /orders fails for everyone while /health stays green. POST /relay/quarantine is the
// application's own hook: it moves one order aside (kept, never deleted) so a person can repair it later.
const quarantineToken = process.env.RELAY_QUARANTINE_TOKEN || '';
let orders = [
  { id: 'order-1001', customer: 'Asha', total: 1299, items: [{ sku: 'KB-01', qty: 1 }] },
  { id: 'order-1002', customer: 'Ravi', total: 450, items: [{ sku: 'MS-02', qty: 2 }] },
];
const quarantined = [];

function renderOrder(order) {
  if (!Array.isArray(order.items) || typeof order.total !== 'number') {
    const err = new Error(`Order ${order.id} is malformed: items=${JSON.stringify(order.items)} total=${JSON.stringify(order.total)}`);
    err.recordId = order.id;
    throw err;
  }
  return { id: order.id, customer: order.customer, total: order.total, itemCount: order.items.reduce((n, i) => n + i.qty, 0) };
}

function readJson(req, limit = 4096) {
  return new Promise((resolve, reject) => {
    let body = '';
    req.on('data', (chunk) => {
      body += chunk;
      if (body.length > limit) { reject(new Error('body too large')); req.destroy(); }
    });
    req.on('end', () => { try { resolve(body ? JSON.parse(body) : {}); } catch (e) { reject(e); } });
    req.on('error', reject);
  });
}

const server = http.createServer((req, res) => {
  const url = new URL(req.url || '/', `http://${req.headers.host || 'localhost'}`);
  res.setHeader('content-type', 'application/json; charset=utf-8');
  res.setHeader('cache-control', 'no-store');

  if (req.method === 'POST' && url.pathname === '/chaos/pool-exhausted') {
    poolExhausted = true;
    console.log('chaos: connection pool exhaustion simulated until restart');
    res.writeHead(202);
    res.end(JSON.stringify({ chaos: 'pool-exhausted', until: 'restart' }));
    return;
  }
  if (poolExhausted) {
    console.error('HikariPool-1 - Connection is not available, request timed out after 30000ms (total=10, active=10, idle=0, waiting=12)');
    res.writeHead(503);
    res.end(JSON.stringify({ status: 'DOWN', error: 'Database connection pool exhausted' }));
    return;
  }
  if (req.method === 'POST' && url.pathname === '/chaos/corrupt-record') {
    const id = `order-${1000 + orders.length + quarantined.length + 1}`;
    orders.push({ id, customer: 'Imported', total: 'NaN', items: null });
    console.log(`chaos: stored malformed order ${id}; GET /orders will fail until it is quarantined`);
    res.writeHead(202);
    res.end(JSON.stringify({ chaos: 'corrupt-record', recordId: id }));
    return;
  }
  if (req.method === 'GET' && url.pathname === '/orders') {
    try {
      const list = orders.map(renderOrder);
      res.writeHead(200);
      res.end(JSON.stringify({ orders: list }));
    } catch (err) {
      console.error(`TypeError while listing orders: ${err.message}`);
      res.writeHead(500);
      res.end(JSON.stringify({ error: 'Could not list orders: one order is malformed', recordId: err.recordId }));
    }
    return;
  }
  if (url.pathname === '/relay/quarantine') {
    if (req.method === 'GET') {
      res.writeHead(200);
      res.end(JSON.stringify({ quarantined }));
      return;
    }
    if (req.method !== 'POST') { res.writeHead(405); res.end(JSON.stringify({ error: 'Use POST' })); return; }
    if (quarantineToken && req.headers['x-relay-token'] !== quarantineToken) {
      res.writeHead(401); res.end(JSON.stringify({ error: 'Missing or wrong X-Relay-Token' })); return;
    }
    readJson(req).then((body) => {
      const index = orders.findIndex((o) => o.id === body.recordId);
      if (index < 0) {
        const already = quarantined.some((q) => q.order.id === body.recordId);
        res.writeHead(already ? 200 : 404);
        res.end(JSON.stringify(already ? { recordId: body.recordId, status: 'already quarantined' } : { error: 'No such order' }));
        return;
      }
      const [order] = orders.splice(index, 1);
      quarantined.push({ order, incidentId: body.incidentId || null, at: new Date().toISOString() });
      console.log(`quarantine: moved order ${order.id} aside (incident ${body.incidentId || 'n/a'}); it is kept for repair`);
      res.writeHead(200);
      res.end(JSON.stringify({ recordId: order.id, status: 'quarantined' }));
    }).catch(() => { res.writeHead(400); res.end(JSON.stringify({ error: 'Send JSON {"recordId": "..."}' })); });
    return;
  }
  if (url.pathname === '/health' || url.pathname === '/actuator/health') {
    res.writeHead(200);
    res.end(JSON.stringify({ status: 'UP', service: 'relay-aegis-test-target', requestId: randomUUID(), uptimeSeconds: Math.floor((Date.now() - startedAt) / 1000) }));
    return;
  }
  if (url.pathname === '/') {
    res.writeHead(200);
    res.end(JSON.stringify({ service: 'relay-aegis-test-target', message: 'Dedicated test target. Do not use for production.', health: '/health', orders: '/orders' }));
    return;
  }
  res.writeHead(404);
  res.end(JSON.stringify({ error: 'Not found' }));
});

server.listen(port, '0.0.0.0', () => console.log(`relay-aegis-test-target listening on ${port}`));

function shutdown(signal) {
  console.log(`${signal} received; shutting down test target`);
  server.close(() => process.exit(0));
  setTimeout(() => process.exit(1), 3000).unref();
}
process.on('SIGTERM', () => shutdown('SIGTERM'));
process.on('SIGINT', () => shutdown('SIGINT'));
