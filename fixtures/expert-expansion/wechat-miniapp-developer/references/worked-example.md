# 虚构微型示例：搜索结果竞态

这是**虚构的纯 JavaScript 模型**，用于说明请求先后次序和页面状态，不是用户项目代码、微信开发者工具记录或真机验收。示例的 request(query) 是外部注入、返回 Promise<结果数组> 的适配函数；它**不是**原生 wx.request。真实项目若用 wx.request，需按所用官方文档和本项目封装适配回调与 RequestTask，分别检查 HTTP statusCode 和后端业务状态，再让适配函数返回或拒绝 Promise。

## 明确的行为契约

- 状态包含 query、items、status、error；status 只取 idle、loading、success、empty、error。
- 每次搜索生成新序号。只有当前序号的响应可以写状态；较旧的成功和失败都被忽略。
- 空关键词立即清空 items/error，置 idle，并使先前请求过期。
- 当前请求失败时置 error，保留 query；retry 只针对当前失败的 query 发新请求。
- 页面离开时调用 dispose，使晚到的响应不再通知页面。序号方案**不取消网络请求**；需要真实取消时还需核对项目所用 API 和生命周期。

以下代码可保存为 search-race-example.cjs，用 node search-race-example.cjs 独立运行。即使独立示例通过，也不能报告“用户项目测试通过”或“微信端已验证”。

~~~js
const assert = require("node:assert/strict");

function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((ok, fail) => {
    resolve = ok;
    reject = fail;
  });
  return { promise, resolve, reject };
}

function createSearchController(request, onState = () => {}) {
  let sequence = 0;
  let disposed = false;
  let state = { query: "", items: [], status: "idle", error: null };

  function snapshot() {
    return { ...state, items: [...state.items] };
  }

  function update(patch) {
    if (disposed) return;
    state = { ...state, ...patch };
    onState(snapshot());
  }

  async function search(input) {
    if (disposed) return;
    const query = String(input ?? "").trim();
    const current = ++sequence;

    if (!query) {
      update({ query: "", items: [], status: "idle", error: null });
      return;
    }

    update({ query, items: [], status: "loading", error: null });
    try {
      const items = await request(query);
      if (disposed || current !== sequence) return;
      if (!Array.isArray(items)) throw new TypeError("Expected an array");
      update({
        items: [...items],
        status: items.length ? "success" : "empty",
        error: null,
      });
    } catch (_error) {
      if (disposed || current !== sequence) return;
      update({ items: [], status: "error", error: "加载失败，请重试" });
    }
  }

  function retry() {
    if (disposed || state.status !== "error" || !state.query) {
      return Promise.resolve();
    }
    return search(state.query);
  }

  function dispose() {
    disposed = true;
    sequence++;
  }

  return { search, retry, dispose, getState: snapshot };
}

async function main() {
  const requests = [];
  const observed = [];
  const model = createSearchController(query => {
    const job = deferred();
    requests.push({ query, ...job });
    return job.promise;
  }, state => observed.push(state));

  const oldSearch = model.search("a");
  const oldJob = requests.at(-1);
  const newSearch = model.search("ab");
  const newJob = requests.at(-1);
  newJob.resolve(["new"]);
  await newSearch;
  oldJob.resolve(["old"]);
  await oldSearch;
  assert.deepEqual(model.getState().items, ["new"]);
  assert.equal(model.getState().status, "success");

  const beforeClear = model.search("abc");
  const beforeClearJob = requests.at(-1);
  await model.search("");
  assert.deepEqual(model.getState(), {
    query: "", items: [], status: "idle", error: null,
  });
  beforeClearJob.resolve(["stale"]);
  await beforeClear;
  assert.deepEqual(model.getState().items, []);

  const failedSearch = model.search("ab");
  const failedJob = requests.at(-1);
  failedJob.reject(new Error("offline"));
  await failedSearch;
  assert.equal(model.getState().status, "error");
  assert.equal(model.getState().query, "ab");
  const retrySearch = model.retry();
  const retryJob = requests.at(-1);
  assert.equal(retryJob.query, "ab");
  retryJob.resolve(["fresh"]);
  await retrySearch;
  assert.deepEqual(model.getState().items, ["fresh"]);

  const staleFailure = model.search("x");
  const staleFailureJob = requests.at(-1);
  const latestSearch = model.search("xy");
  const latestJob = requests.at(-1);
  latestJob.resolve(["xy"]);
  await latestSearch;
  staleFailureJob.reject(new Error("late failure"));
  await staleFailure;
  assert.deepEqual(model.getState().items, ["xy"]);
  assert.equal(model.getState().status, "success");

  const emptySearch = model.search("none");
  requests.at(-1).resolve([]);
  await emptySearch;
  assert.equal(model.getState().status, "empty");

  const leavingSearch = model.search("later");
  const leavingJob = requests.at(-1);
  const updatesBeforeDispose = observed.length;
  model.dispose();
  leavingJob.resolve(["too late"]);
  await leavingSearch;
  assert.equal(observed.length, updatesBeforeDispose);
  console.log("Standalone search model checks passed");
}

main().catch(error => {
  console.error(error);
  process.exitCode = 1;
});
~~~

真实页面接入时，应让状态通知进入项目现有的页面数据更新机制，并在实际页面卸载时调用 dispose；这些集成步骤要查看项目代码及开发者工具结果，不能从以上纯 JS 断言推断已完成。若需要取消网络连接，再按项目版本核对 RequestTask 的可用能力并另测。
