<!--
Thanks for the pull request. Keep it focused on one change; two unrelated fixes
are easier to review and release as two PRs.

CI builds the mod and comments a download link on this PR after each push.
-->

## What does this change?

<!-- A sentence or two. Link the issue it closes, if there is one: "Closes #123". -->

## Why?

<!-- The problem it solves, or the behaviour it changes and the reason. -->

## How was it tested?

<!--
Pick what applies and say what you actually did:
- ran `./gradlew build` (compiles, runs the unit tests)
- ran `./gradlew runGametest` (needed for anything that moves or saves items)
- ran it in-game with `./gradlew runClient` and tried ...
- tested on a dedicated server
-->

## Checklist

- [ ] `./gradlew build` passes locally
- [ ] `./gradlew runGametest` passes if this touches how items move or persist
- [ ] New behaviour has a test, or the PR says why it cannot
- [ ] Any new block, item, or recipe has its lang entry, model, and loot table
- [ ] The README is updated if a player- or developer-facing behaviour changed
- [ ] I am happy for this to be released under the repository's MIT license
