#!/usr/bin/env bash
# retry.sh COMMAND...: run COMMAND, and on failure run it again after 15, 30,
# 60 and 120 s (five tries, about four minutes of waiting). For git fetches
# that fail on a host's bad minute, not on a bad ref: a ref that does not exist
# fails all five tries the same way.
set -uo pipefail

# RETRY_DELAYS overrides the waits (tests).
read -ra delays <<< "${RETRY_DELAYS:-15 30 60 120}"
for attempt in 1 2 3 4 5; do
  "$@" && exit 0
  status=$?
  if [ "$attempt" -eq 5 ]; then
    echo "::error::$* failed $attempt times (exit $status)"
    exit "$status"
  fi
  delay=${delays[$((attempt - 1))]}
  echo "::warning::$* failed (exit $status, try $attempt of 5); retrying in ${delay}s"
  sleep "$delay"
done
