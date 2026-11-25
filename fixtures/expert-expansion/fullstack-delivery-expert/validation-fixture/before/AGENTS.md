# Isolated vertical-slice repair

Only edit server.mjs and client.mjs. Keep acceptance.test.mjs and USER-NOTES.md
byte-identical. Use the existing Node HTTP interface and in-memory store.
No framework migration, external network, dependencies, database or deployment.
You may also write trial-response.md and trial-result.json.

resolveUser(req) is injected and returns an owner string or null. Reject missing
identity with 401. GET lists only the caller's tasks. POST trims a string title,
requires 1-80 characters, and creates an id that does not collide. PATCH allows
only the owner; return 404 for missing or foreign records. done must be boolean.
Malformed JSON must return 400 without crashing or changing data. All responses
are JSON. The browser client updateTask must reject HTTP errors (with error.status)
instead of presenting them as successful updates. Preserve exported signatures.
Run node --test acceptance.test.mjs. Local HTTP tests are real network loopback,
but authentication and storage are fixtures, not production integration.
