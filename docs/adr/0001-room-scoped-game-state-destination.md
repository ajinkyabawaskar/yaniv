# Game state is addressed per room, not per user

A player can be in several games at once — nothing prevents it, and a room *is* a game, so
"the player's room" has no single answer. Game state was published to the user destination
`/user/queue/game-state`, which Spring fans out to every session that player has open, and the
client never checked the payload's game id against the room it was showing. Two tabs in different
games therefore overwrote each other's view.

We publish to `/user/queue/room/{roomId}/game-state` instead. It remains a user destination, so
each player still receives only their own hand — opponents' cards never reach a shared topic — but a
message for one game can no longer be delivered into another.

The decision also supplies **room attachment**: because a session subscribes to a specific game's
destination, subscribing announces which game that session is watching and unsubscribing announces
that it has left. That is what lets the server tell a closed spare tab from a player who has
actually gone, without inventing a separate "I am here" message.
