import http from 'node:http';

// Authentication is injected by the fixture, not a production identity provider.
export function createTaskServer({ resolveUser, tasks }) {
  return http.createServer(async (req, res) => {
    const send = (status, value) => { res.writeHead(status, { 'Content-Type': 'application/json' }); res.end(JSON.stringify(value)); };
    const user = resolveUser(req);
    if (!user) return send(401, { error: 'unauthorized' });
    if (req.url === '/api/tasks' && req.method === 'GET')
      return send(200, [...tasks.values()].filter(task => task.owner === user));

    const isCreate = req.url === '/api/tasks' && req.method === 'POST';
    const match = req.method === 'PATCH' && req.url.match(/^\/api\/tasks\/([^/]+)$/);
    if (!isCreate && !match) return send(404, { error: 'not_found' });

    const task = match && tasks.get(match[1]);
    if (match && (!task || task.owner !== user)) return send(404, { error: 'not_found' });

    let text = '';
    for await (const chunk of req) text += chunk;
    let body;
    try { body = text ? JSON.parse(text) : {}; } catch { return send(400, { error: 'invalid_json' }); }
    if (!body || typeof body !== 'object' || Array.isArray(body))
      return send(400, { error: 'invalid_body' });

    if (isCreate) {
      const title = typeof body.title === 'string' ? body.title.trim() : '';
      if (title.length < 1 || title.length > 80) return send(400, { error: 'invalid_title' });
      let id = 1;
      while (tasks.has(String(id))) id += 1;
      const created = { id: String(id), owner: user, title, done: false };
      tasks.set(created.id, created); return send(201, created);
    }
    if (typeof body.done !== 'boolean') return send(400, { error: 'invalid_done' });
    task.done = body.done; return send(200, task);
  });
}
