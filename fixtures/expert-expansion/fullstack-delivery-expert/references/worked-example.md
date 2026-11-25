# 虚构示例：为两个用户保存各自的待办

这是独立可运行的标准库模型，不是用户工程或已部署服务。虚构输入：一个已有登录态的应用要加“我的待办”。用户在页面输入标题并保存，重载后能再次看见；未登录不能读写；另一用户不能看见本人的记录。现有工程的路由和认证中间件未知，所以示例把由**可信服务端认证层**给出的 `principal` 作为函数参数；不能从浏览器自报的用户 ID 取值。

## 契约与界面结果

| 操作 | 输入 | 成功 | 失败 |
| --- | --- | --- | --- |
| 新增 | `POST /tasks`，`title` 为非空字符串 | `201` 和 `{id,title}` | 未登录 `401`；无效标题 `422` |
| 列表 | `GET /tasks` | `200` 和当前用户的 `items`，可为空 | 未登录 `401` |

页面状态：初次加载显示等待；`items=[]` 显示“暂无待办”；提交中禁用重复提交；`422` 保留输入并提示修正，`401` 引导重新登录，网络错误保留输入并允许重试。服务端返回成功后才把新项加入列表；若请求超时导致结果未知，真实项目需依据既有幂等机制确认是否写入，不能盲目无限重试。示例只覆盖标题与用户隔离，没有订单级幂等要求。

## 可运行的服务端规则和存储检查

保存为 `tasks-slice.py`，运行 `python tasks-slice.py`。SQLite 临时数据库只用于本样例验证；`CREATE TABLE IF NOT EXISTS` 是此新表的演示迁移，不能直接替代用户项目的迁移工具。函数返回的状态和 JSON 形状表示 API 层契约，尚未绑定真实 HTTP 路由。

~~~python
import sqlite3
import tempfile
from contextlib import closing
from pathlib import Path


def migrate(db):
    db.execute("""CREATE TABLE IF NOT EXISTS tasks (
        id INTEGER PRIMARY KEY,
        owner_id TEXT NOT NULL,
        title TEXT NOT NULL CHECK(length(title) BETWEEN 1 AND 120)
    )""")
    db.execute("CREATE INDEX IF NOT EXISTS tasks_owner_id ON tasks(owner_id, id)")
    db.commit()


def create_task(db, principal, title):
    if principal is None:
        return 401, {"error": "login_required"}
    if not isinstance(title, str) or not 1 <= len(title.strip()) <= 120:
        return 422, {"error": "invalid_title"}
    title = title.strip()
    with db:
        cursor = db.execute(
            "INSERT INTO tasks(owner_id, title) VALUES (?, ?)", (principal, title)
        )
    return 201, {"id": cursor.lastrowid, "title": title}


def list_tasks(db, principal):
    if principal is None:
        return 401, {"error": "login_required"}
    rows = db.execute(
        "SELECT id, title FROM tasks WHERE owner_id = ? ORDER BY id", (principal,)
    ).fetchall()
    return 200, {"items": [{"id": row[0], "title": row[1]} for row in rows]}


with tempfile.TemporaryDirectory() as folder:
    path = Path(folder) / "tasks.sqlite"
    with closing(sqlite3.connect(path)) as db:
        migrate(db)
        assert create_task(db, None, "Buy milk")[0] == 401
        assert create_task(db, "alice", "   ")[0] == 422
        status, saved = create_task(db, "alice", "  Buy milk  ")
        assert status == 201 and saved["title"] == "Buy milk"
        assert list_tasks(db, "bob") == (200, {"items": []})
    with closing(sqlite3.connect(path)) as reopened:
        migrate(reopened)
        assert list_tasks(reopened, "alice") == (200, {"items": [saved]})
        assert list_tasks(reopened, None)[0] == 401
    print("Task slice model passed: auth, validation, isolation, reopen")
~~~

预期输出：`Task slice model passed: auth, validation, isolation, reopen`。`reopen` 检查的是本地持久化，不证明前端或真实 HTTP 已联通。真实工程应将两条操作接入已有路由和认证层，用项目迁移工具执行相应建表，再以实际测试账号和测试库发送 HTTP 请求。

前端接入的关键分支可写为下列伪代码；须按现有框架和请求封装改写，不在本样例中假称浏览器已运行：

~~~text
提交标题 → 显示提交中并锁定本次提交
201 → 清空输入，刷新当前用户列表并显示成功
422 → 保留输入，提示标题必填且不超限
401 → 保留输入，引导重新登录
网络失败/5xx → 保留输入，提示稍后重试并解除提交锁
重新进入页面 → GET /tasks；空数组显示“暂无待办”
~~~

可检查的结果是：样例程序能阻止未登录写入、拒绝空标题、让 Bob 看不到 Alice 数据，并在重新打开 SQLite 后仍读到 Alice 的记录。真实前端状态、HTTP 契约、旧客户端兼容、测试数据库迁移和生产部署分别待项目环境验证。
