export async function updateTask(baseUrl, id, done, token) {
  const response = await fetch(`${baseUrl}/api/tasks/${encodeURIComponent(id)}`, {
    method: 'PATCH', headers: { 'Content-Type': 'application/json', Authorization: token }, body: JSON.stringify({ done }),
  });
  const value = await response.json();
  if (!response.ok) {
    const error = new Error(typeof value?.error === 'string' ? value.error : `HTTP ${response.status}`);
    error.status = response.status;
    throw error;
  }
  return value;
}
