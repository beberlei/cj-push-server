# cj-push-server

A PubSubHubBub Hub Server written in Clojure

The server will allow to subscribe to any feed and periodically polls
feeds that do not support PUSH, turning new items into push messages
for all subscribers.

## Features

* Subscription to Topics (hub[mode]=subscribe)
* Unsubscription from topics (hub[mode]=unsubscribe)
* Publish to topics (hub[mode]=publish)
* Fetch feeds that are outstanding, and publish them to all subscribers (no diff or change check yet)

## Todos

* Compute last change date with date or content fingerprint
* Traverse RSS/Atom feeds and remove "old" items
* Implement optional bits of subscribe mode

## Usage

None yet

## License

Copyright (C) 2012 Benjamin Eberlei

Distributed under the MIT license.
