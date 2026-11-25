# 虚构示例：本地今日任务工作台

虚构输入：一位用户每天只想记录和完成三件事，电脑和手机浏览器都能打开同一份独立页面；目前没有日历账号、数据库或云连接。输出是一份可保存为 `today.html` 的完整文件：新增、编辑、完成、删除、重载保留、导出 JSON、导入并确认覆盖。这里的“保留”只指同一站点同一浏览器的 localStorage；把文件在另一设备打开不会自动同步。导出文件可能含私人任务，应由用户自行保管。单份存档上限 500 条；损坏存档会阻止自动覆盖，并提供原始内容导出与确认重建入口。

以下页面使用原生浏览器 API，没有外部依赖。文件方案适合小型个人任务，不提供多设备并发、用户账户或云备份。真正交付给用户时，应按其工程入口放置并实际打开验证。

~~~html
<!doctype html>
<html lang="zh-CN">
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>今日任务</title>
<style>
  :root { font: 16px/1.5 system-ui, sans-serif; color: #202b2a; background: #f5f7f4; }
  body { margin: 0; padding: 1rem; }
  main { max-width: 46rem; margin: auto; }
  h1 { margin-bottom: .25rem; }
  p { margin-top: .25rem; }
  form, li, .tools { display: flex; gap: .65rem; flex-wrap: wrap; align-items: center; }
  input[type=text] { flex: 1 1 16rem; min-width: 0; padding: .65rem; font: inherit; }
  button, input[type=file] { font: inherit; }
  button { padding: .55rem .75rem; border: 1px solid #425f54; border-radius: .5rem; background: white; color: inherit; cursor: pointer; }
  button:focus-visible, input:focus-visible { outline: 3px solid #a45f38; outline-offset: 2px; }
  ul { list-style: none; padding: 0; }
  li { background: white; padding: .8rem; margin: .5rem 0; border-radius: .6rem; }
  li span { flex: 1 1 12rem; overflow-wrap: anywhere; }
  .done { text-decoration: line-through; }
  .tools { margin-top: 1rem; }
  .error { color: #9b2f21; }
  @media (max-width: 34rem) { form button, .tools button { flex: 1 1 100%; } }
</style>
<main>
  <h1>今日任务</h1>
  <p>任务保存在此浏览器。导出的文件请自行妥善保管。</p>
  <form id="form">
    <label for="title">任务</label>
    <input id="title" type="text" maxlength="120" required>
    <button id="save" type="submit">新增任务</button>
    <button id="cancel" type="button" hidden>取消编辑</button>
  </form>
  <p id="status" role="status" aria-live="polite"></p>
  <p id="count"></p>
  <p id="empty">还没有任务。输入一件事开始。</p>
  <ul id="list"></ul>
  <div class="tools">
    <button id="export" type="button">导出备份</button>
    <label for="import">恢复备份</label>
    <input id="import" type="file" accept="application/json,.json">
  </div>
  <div id="recovery" class="tools" hidden>
    <button id="raw" type="button">导出原始损坏存档</button>
    <button id="reset" type="button">确认重建空工作台</button>
  </div>
</main>
<script>
  const key = 'today-tasks-v1';
  const $ = id => document.getElementById(id);
  let tasks = [];
  let editing = null;
  let counter = 0;
  let blocked = false;
  let corruptRaw = null;
  function validBackup(value) {
    if (!value || value.version !== 1 || !Array.isArray(value.tasks) || value.tasks.length > 500) return false;
    const ids = new Set();
    return value.tasks.every(t => {
      if (!t || typeof t.id !== 'string' || !t.id || ids.has(t.id) ||
          typeof t.title !== 'string' || !t.title.trim() || t.title.length > 120 ||
          typeof t.done !== 'boolean') return false;
      ids.add(t.id);
      return true;
    });
  }
  function notice(message, error = false) {
    $('status').textContent = message;
    $('status').className = error ? 'error' : '';
  }
  function persist(next, recoveryConfirmed = false) {
    if (next.length > 500) { notice('最多保存 500 件任务；请先整理或导出。', true); return false; }
    if (blocked && !recoveryConfirmed) { notice('原存档无法读取。请先导出原始内容，再确认恢复或重建；当前输入仍在。', true); return false; }
    try { localStorage.setItem(key, JSON.stringify({version: 1, tasks: next})); }
    catch { notice('保存失败，当前输入仍在。请检查浏览器存储空间。', true); return false; }
    tasks = next;
    blocked = false; corruptRaw = null;
    $('recovery').hidden = true;
    $('export').disabled = false;
    render();
    notice('已保存在此浏览器。');
    return true;
  }
  function newId() {
    let id;
    do { id = `${Date.now()}-${counter++}`; } while (tasks.some(t => t.id === id));
    return id;
  }
  function render() {
    $('list').replaceChildren();
    $('empty').hidden = tasks.length > 0;
    $('empty').textContent = blocked ? '原存档需先恢复或确认重建。' : '还没有任务。输入一件事开始。';
    $('count').textContent = `${tasks.filter(t => !t.done).length} 件未完成，共 ${tasks.length} 件`;
    for (const task of tasks) {
      const li = document.createElement('li');
      li.dataset.id = task.id;
      const label = document.createElement('span');
      label.textContent = task.title;
      if (task.done) label.className = 'done';
      const toggle = document.createElement('button');
      toggle.type = 'button'; toggle.textContent = task.done ? '恢复' : '完成';
      toggle.setAttribute('aria-label', `${toggle.textContent}：${task.title}`);
      toggle.onclick = () => {
        if (persist(tasks.map(t => t.id === task.id ? {...t, done: !t.done} : t))) {
          [...$('list').children].find(row => row.dataset.id === task.id)?.querySelector('button')?.focus();
        }
      };
      const edit = document.createElement('button');
      edit.type = 'button'; edit.textContent = '编辑';
      edit.setAttribute('aria-label', `编辑：${task.title}`);
      edit.onclick = () => { editing = task.id; $('title').value = task.title; $('save').textContent = '保存修改'; $('cancel').hidden = false; $('title').focus(); };
      const remove = document.createElement('button');
      remove.type = 'button'; remove.textContent = '删除';
      remove.setAttribute('aria-label', `删除：${task.title}`);
      remove.onclick = () => {
        if (confirm(`删除“${task.title}”？`) && persist(tasks.filter(t => t.id !== task.id))) {
          if (editing === task.id) {
            editing = null; $('title').value = ''; $('save').textContent = '新增任务'; $('cancel').hidden = true;
          }
          $('title').focus();
        }
      };
      li.append(label, toggle, edit, remove);
      $('list').append(li);
    }
  }
  $('form').onsubmit = event => {
    event.preventDefault();
    const title = $('title').value.trim();
    if (!title || title.length > 120) { notice('请输入 1 至 120 个字符的任务。', true); return; }
    if (editing && !tasks.some(t => t.id === editing)) { notice('正在编辑的任务已不存在，草稿仍在；请取消编辑后重新新增。', true); return; }
    if (!editing && tasks.length >= 500) { notice('最多保存 500 件任务；请先整理或导出。', true); return; }
    const next = editing ? tasks.map(t => t.id === editing ? {...t, title} : t)
                         : [...tasks, {id: newId(), title, done: false}];
    if (persist(next)) { $('title').value = ''; editing = null; $('save').textContent = '新增任务'; $('cancel').hidden = true; $('title').focus(); }
  };
  $('cancel').onclick = () => { editing = null; $('title').value = ''; $('save').textContent = '新增任务'; $('cancel').hidden = true; $('title').focus(); };
  $('export').onclick = () => {
    const blob = new Blob([JSON.stringify({version: 1, tasks}, null, 2)], {type: 'application/json'});
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a'); a.href = url; a.download = 'today-tasks.json'; a.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
    notice('已导出备份，请妥善保管文件。');
  };
  $('import').onchange = async event => {
    const file = event.target.files[0];
    if (!file) return;
    try {
      if (file.size > 1024 * 1024) throw new Error('file too large');
      const incoming = JSON.parse(await file.text());
      if (!validBackup(incoming)) throw new Error('invalid backup');
      const warning = blocked ? '原存档无法读取，请先导出原始内容。' : '';
      if (confirm(`${warning}将用备份中的 ${incoming.tasks.length} 件任务覆盖当前 ${tasks.length} 件任务，继续吗？`)) {
        const normalized = incoming.tasks.map(({id, title, done}) => ({id, title: title.trim(), done}));
        if (persist(normalized, true)) {
          editing = null; $('title').value = ''; $('save').textContent = '新增任务'; $('cancel').hidden = true;
        }
      }
    } catch { notice('备份格式、版本或大小不符合要求，现有任务未改动。', true); }
    event.target.value = '';
  };
  $('raw').onclick = () => {
    if (corruptRaw === null) return;
    const url = URL.createObjectURL(new Blob([corruptRaw], {type: 'text/plain'}));
    const a = document.createElement('a'); a.href = url; a.download = 'today-tasks-original.txt'; a.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
    notice('已导出原始内容；请妥善保管，文件可能含私人任务。');
  };
  $('reset').onclick = () => {
    if (confirm('将用空工作台覆盖原存档。请先导出原始内容；确定继续吗？')) persist([], true);
  };
  let rawAtStart = null;
  try {
    rawAtStart = localStorage.getItem(key);
    if (rawAtStart !== null) {
      const saved = JSON.parse(rawAtStart);
      if (!validBackup(saved)) throw new Error('invalid saved data');
      tasks = saved.tasks.map(({id, title, done}) => ({id, title: title.trim(), done}));
    }
  } catch {
    blocked = true; corruptRaw = rawAtStart;
    $('recovery').hidden = false; $('raw').hidden = corruptRaw === null; $('export').disabled = true;
    notice('原存档无法读取，尚未覆盖。请先导出原始内容，再导入有效备份或确认重建。', true);
  }
  render();
</script>
</html>
~~~

可核验的虚构操作与预期输出：初始显示“还没有任务”；输入“整理访谈笔记”并提交后统计为 1 件未完成；编辑为“整理三份访谈笔记”后重载仍显示新标题；点“完成”后统计为 0 件未完成；导出文件应含 `version: 1` 和对应任务的 `done: true`。导入损坏 JSON 或含重复 ID 的备份时，状态显示错误且旧任务保持不变。编辑一条任务时删除它，应安全退出编辑；达到 500 条后新增应被拒绝且重载仍可读。启动时遇到损坏存档，应能下载原始字节，不允许普通新增覆盖，导入有效备份或确认重建后才解除阻断。实际浏览器结果以本次执行记录为准；上述路径不能用静态阅读代替。
