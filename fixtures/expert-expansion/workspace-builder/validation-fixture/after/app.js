const KEY = 'task-desk-v1';
const MAX_TASKS = 500;
const MAX_FILE_BYTES = 1024 * 1024;
const list = document.querySelector('#tasks');
const titleInput = document.querySelector('#title');
const status = document.querySelector('#status');
const empty = document.querySelector('#empty');
const importInput = document.querySelector('#import');

let tasks = [];
let nextId = 0;
let corruptStorage = false;
let corruptRaw = null;
let unsaved = false;

function report(message) {
  status.textContent = unsaved && !message.includes('未保存')
    ? `${message} 当前修改未保存，请导出备份。`
    : message;
}

function validTasks(value) {
  if (!Array.isArray(value) || value.length > MAX_TASKS) return false;
  const ids = new Set();
  return value.every(task => {
    if (!task || typeof task !== 'object' || Array.isArray(task) ||
        typeof task.id !== 'string' || !task.id.trim() || ids.has(task.id) ||
        typeof task.title !== 'string' || !task.title.trim() || task.title.length > 120 ||
        typeof task.done !== 'boolean') return false;
    ids.add(task.id);
    return true;
  });
}

function normalizedTasks(value) {
  return value.map(({ id, title, done }) => ({ id, title: title.trim(), done }));
}

function save(allowReplaceCorrupt = false) {
  if (corruptStorage && !allowReplaceCorrupt) {
    unsaved = true;
    report('原存档损坏，已保留原始内容；当前修改未保存。请导出备份，再导入有效文件恢复。');
    return false;
  }
  try {
    localStorage.setItem(KEY, JSON.stringify(tasks));
    corruptStorage = false;
    corruptRaw = null;
    unsaved = false;
    document.querySelector('#export-raw')?.remove();
    report('已保存在此浏览器。请定期导出备份；这里没有云同步。');
    return true;
  } catch {
    unsaved = true;
    report('保存失败：当前任务仍在本页内存中，但未保存。请立即导出备份或检查浏览器存储。');
    return false;
  }
}

function newId() {
  let id;
  do { id = `${Date.now()}-${nextId++}`; }
  while (tasks.some(task => task.id === id));
  return id;
}

function render() {
  list.replaceChildren();
  empty.hidden = tasks.length > 0;
  for (const task of tasks) {
    const row = document.createElement('li');
    row.dataset.id = task.id;

    const checkbox = document.createElement('input');
    checkbox.type = 'checkbox';
    checkbox.checked = task.done;
    checkbox.setAttribute('aria-label', `完成：${task.title}`);
    checkbox.addEventListener('change', () => {
      tasks = tasks.map(item => item.id === task.id ? { ...item, done: checkbox.checked } : item);
      render();
      save();
      [...list.children].find(item => item.dataset.id === task.id)?.querySelector('input')?.focus();
    });

    const label = document.createElement('span');
    label.textContent = task.title;
    if (task.done) label.style.textDecoration = 'line-through';

    const remove = document.createElement('button');
    remove.type = 'button';
    remove.textContent = '删除';
    remove.setAttribute('aria-label', `删除：${task.title}`);
    remove.addEventListener('click', () => {
      if (!window.confirm(`删除“${task.title}”？`)) return;
      tasks = tasks.filter(item => item.id !== task.id);
      render();
      save();
      titleInput.focus();
    });

    row.append(checkbox, label, remove);
    list.append(row);
  }
}

function download(name, content, type) {
  const url = URL.createObjectURL(new Blob([content], { type }));
  const link = document.createElement('a');
  link.href = url;
  link.download = name;
  link.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

function showCorruptRecovery() {
  if (corruptRaw === null || document.querySelector('#export-raw')) return;
  const rawButton = document.createElement('button');
  rawButton.id = 'export-raw';
  rawButton.type = 'button';
  rawButton.textContent = '导出原始损坏存档';
  rawButton.addEventListener('click', () => {
    download('task-desk-original.txt', corruptRaw, 'text/plain;charset=utf-8');
    report('原始损坏存档已导出，请妥善保管；当前任务仍未保存。');
  });
  status.after(rawButton);
}

document.querySelector('#add-form').addEventListener('submit', event => {
  event.preventDefault();
  const title = titleInput.value.trim();
  if (!title || title.length > 120) {
    report('请输入 1 至 120 个字符的任务。');
    return;
  }
  if (tasks.length >= MAX_TASKS) {
    report('任务已达 500 条，请先整理或导出。');
    return;
  }
  tasks = [...tasks, { id: newId(), title, done: false }];
  titleInput.value = '';
  render();
  save();
});

document.querySelector('#export').addEventListener('click', () => {
  const backup = { version: 1, tasks: normalizedTasks(tasks) };
  download('task-desk-backup.json', JSON.stringify(backup, null, 2), 'application/json;charset=utf-8');
  report('已导出当前任务备份，请自行保管文件；这里没有云备份。');
});

importInput.addEventListener('change', async event => {
  const file = event.target.files?.[0];
  if (!file) return;
  try {
    if (file.size > MAX_FILE_BYTES) throw new Error('too_large');
    const backup = JSON.parse(await file.text());
    if (!backup || backup.version !== 1 || !validTasks(backup.tasks)) throw new Error('invalid_backup');
    if (!window.confirm(`将用备份中的 ${backup.tasks.length} 条任务覆盖当前 ${tasks.length} 条任务，继续吗？`)) {
      report('已取消导入，当前任务未改动。');
      return;
    }
    tasks = normalizedTasks(backup.tasks);
    render();
    save(true);
  } catch {
    report('导入失败：文件格式、版本、任务字段或大小无效；当前任务和存档未改动。');
  } finally {
    importInput.value = '';
  }
});

try {
  const raw = localStorage.getItem(KEY);
  if (raw !== null) {
    const stored = JSON.parse(raw);
    if (!validTasks(stored)) throw new Error('invalid_storage');
    tasks = normalizedTasks(stored);
  }
} catch {
  corruptStorage = true;
  try { corruptRaw = localStorage.getItem(KEY); } catch { /* Storage access is unavailable. */ }
  report('原存档无法读取，已保留原始内容。当前修改不会覆盖它；请导出原始内容并导入有效备份。');
  showCorruptRecovery();
}
render();
