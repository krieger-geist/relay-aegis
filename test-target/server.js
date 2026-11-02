'use strict';

const http = require('node:http');
const { randomUUID } = require('node:crypto');
const port = Number(process.env.PORT || 3000);
const startedAt = Date.now();
// Simulated failure for runbook demos: POST /chaos/pool-exhausted makes every later request fail the way an
// application with a leaked database connection pool does, until the process restarts.
let poolExhausted = false;

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
  if (url.pathname === '/health' || url.pathname === '/actuator/health') {
    res.writeHead(200);
    res.end(JSON.stringify({ status: 'UP', service: 'relay-aegis-test-target', requestId: randomUUID(), uptimeSeconds: Math.floor((Date.now() - startedAt) / 1000) }));
    return;
  }
  if (url.pathname === '/') {
    res.writeHead(200);
    res.end(JSON.stringify({ service: 'relay-aegis-test-target', message: 'Dedicated test target. Do not use for production.', health: '/health' }));
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
