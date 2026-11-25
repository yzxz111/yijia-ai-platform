# Isolated local task desk repair

Only edit app.js. Keep index.html and acceptance.test.cjs byte-identical, especially
the existing user notice. No libraries, network calls, commits, publication or
background sync. You may also write trial-response.md and trial-result.json.

Required local behavior: add trimmed nonempty tasks (max 120 characters); toggle
and delete by stable id; save to localStorage key task-desk-v1 as an array; reload
must restore tasks. Display user data as text. Export a downloadable UTF-8 JSON
object {version:1,tasks:[{id,title,done}]}. Import validates the whole schema,
unique nonempty string ids, nonempty bounded titles and boolean done before replacing
anything. Reject malformed input without losing current data. Corrupt stored data
must be preserved and reported, not silently cleared. If saving is blocked, keep
the in-memory view usable and show a clear storage warning. Do not claim saved.
Warn that export is needed; no false cloud backup. Keep the existing accessible
native controls and 390px layout. Run node --test acceptance.test.cjs.
