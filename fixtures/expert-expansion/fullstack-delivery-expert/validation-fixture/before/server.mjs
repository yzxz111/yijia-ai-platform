import http from 'node:http';

// Authentication is injected by the fixture, not a production identity provider.
export function createTaskServer({ resolveUser, tasks }) {
  return http.createServer(async (req, res) => {
    const send = (status, value) => { res.writeHead(status, { 'Content-Type': 'application/json' }); res.end(JSON.stringify(value)); };
    const user = resolveUser(req);
    if (req.url === '/api/tasks' && req.method === 'GET') return send(200, [...tasks.values()]);
    let text = '';
    for await (const chunk of req) text += chunk;
    let body;
    try { body = text ? JSON.parse(text) : {}; } catch { return send(500, { error: 'parse_failed' }); }
    if (req.url === '/api/tasks' && req.method === 'POST') {
      const task = { id: String(tasks.size + 1), owner: user, title: body.title, done: false };
      tasks.set(task.id, task); return send(201, task);
    }
    const match = req.url.match(/^\/api\/tasks\/([^/]+)$/);
    if (match && req.method === 'PATCH') {
      const task = tasks.get(match[1]);
      if (!task) return send(404, { error: 'not_found' });
      task.done = body.done; return send(200, task);
    }
    return send(404, { error: 'not_found' });
  });
}
