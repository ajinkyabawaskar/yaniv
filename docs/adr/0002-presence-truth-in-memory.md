# Presence is authoritative in memory; Redis holds a projection

Game state is durable data the process happens to be holding, so Redis is correctly its source of
truth. Sessions are not that kind of thing: a session is a live connection held by *this* process,
and no store can know about it more accurately than the process itself.

So the Presence module keeps sessions in memory and treats Redis as a projection, written
best-effort for cross-user reads — the friends list and invites, which ask about players who are not
in your game. A read for a user we hold no session for returns *unknown*, never *offline*.

The rejected alternative was making Redis authoritative, on the general principle of not trusting
in-memory state. It fails in the one direction that matters: after a restart every socket is dead,
memory correctly reports nobody present, while Redis would still list sessions that no longer exist
and keep claiming those players are here. Stale-*present* is exactly the failure that lets the server
play a turn on behalf of someone who is sitting there watching. Clients reconnect within about three
seconds and re-announce themselves, so the cost of forgetting on restart is small and self-correcting.

This holds because the application deploys as a single instance, and cannot currently be otherwise:
the STOMP broker is in-memory with no relay, so a user destination cannot reach a session on another
node. If that changes, this decision must be revisited.
