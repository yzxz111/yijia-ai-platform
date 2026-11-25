export function createSearchController({ request, publish }) {
  let generation = 0;
  let disposed = false;

  function search(input) {
    if (disposed) return;

    const query = String(input ?? '').trim();
    const current = ++generation;
    if (!query) {
      publish({ query: '', items: [], status: 'idle', loading: false, error: '' });
      return;
    }

    const isActive = () => !disposed && current === generation;
    let settled = false;
    const finish = (patch) => {
      if (!isActive() || settled) return;
      settled = true;
      publish({ ...patch, loading: false });
    };

    publish({ query, items: [], status: 'loading', loading: true, error: '' });
    if (!isActive()) return;

    try {
      request({
        url: 'https://example.invalid/search',
        data: { q: query },
        success(response) {
          if (!isActive() || settled) return;
          const body = response?.data;
          const statusCode = response?.statusCode;
          if (!Number.isInteger(statusCode) || statusCode < 200 || statusCode >= 300 ||
              body?.ok !== true || !Array.isArray(body.items)) {
            finish({ items: [], status: 'error', error: 'Request failed' });
            return;
          }
          finish({ items: body.items, status: body.items.length ? 'ready' : 'empty', error: '' });
        },
        fail() {
          finish({ items: [], status: 'error', error: 'Request failed' });
        },
        complete() {
          finish({ items: [], status: 'error', error: 'Request failed' });
        },
      });
    } catch {
      finish({ items: [], status: 'error', error: 'Request failed' });
    }
  }

  function dispose() {
    disposed = true;
    generation++;
  }

  return { search, dispose };
}
