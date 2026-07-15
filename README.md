# Opus Session Logger

A read-only RuneLite plugin that logs each play session — exact login and logout
timestamps, world, and a full per-skill XP snapshot at both ends — as one JSON
line per event to a local file.

## What it writes

`~/.runelite/opus-session-logger/sessions.jsonl` (append-only, one event per line):

```json
{"event":"login","session_uuid":"…","ts_ms":1752604800000,"player":"…","world":416,"skills":{"Attack":{"xp":951218,"level":72},"…":{}}}
{"event":"logout","reason":"login_screen","session_uuid":"…","ts_ms":1752608400000,"player":"…","world":416,"skills":{"…":{}}}
```

Session XP = logout snapshot − login snapshot, per skill — exact, with no polling
gaps. Pair events by `session_uuid`; a `login` with no matching `logout` means the
client crashed mid-session. World hops and brief connection losses do not split a
session; only reaching the login screen ends one.

## What it does NOT do

- No automation, input, or menu changes — it only observes your own visible state
- No network activity of any kind — data never leaves your machine
- No reflection, no subprocesses, no external dependencies

## License

BSD 2-Clause — see [LICENSE](LICENSE).
