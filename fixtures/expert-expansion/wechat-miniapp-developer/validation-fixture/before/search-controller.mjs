// Deliberately incomplete implementation for an isolated repair exercise.
export function createSearchController({ request, publish }) {
  function search(input) {
    const query = String(input ?? '').trim();
    publish({ query, items: [], status: 'loading', loading: true, error: '' });
    request({
      url: 'https://example.invalid/search',
      data: { q: query },
      success(response) {
        const items = response.data.items;
        publish({ items, status: items.length ? 'ready' : 'empty', error: '' });
      },
      fail() {
        publish({ items: [], status: 'error', error: 'Request failed' });
      },
      complete() {
        publish({ loading: false });
      },
    });
  }
  function dispose() {}
  return { search, dispose };
}
