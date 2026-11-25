export async function updateTask(baseUrl, id, done, token) {
  const response = await fetch(`${baseUrl}/api/tasks/${encodeURIComponent(id)}`, {
    method: 'PATCH', headers: { 'Content-Type': 'application/json', Authorization: token }, body: JSON.stringify({ done }),
  });
  return response.json();
}
