// Incomplete local prototype: repair persistence, interaction and backup handling.
const KEY = 'task-desk-v1';
let tasks = JSON.parse(localStorage.getItem(KEY) || '[]');
function render() {
  document.querySelector('#tasks').innerHTML = tasks.map(t => `<li><input type="checkbox"><span>${t.title}</span><button>删除</button></li>`).join('');
  document.querySelector('#empty').hidden = tasks.length > 0;
}
document.querySelector('#add-form').addEventListener('submit', event => {
  event.preventDefault();
  tasks.push({ id: String(Date.now()), title: document.querySelector('#title').value, done: false });
  document.querySelector('#title').value = '';
  render();
});
render();
