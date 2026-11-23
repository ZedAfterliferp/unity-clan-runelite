# Unity Clan — RuneLite Plugin

Unity Clan is a RuneLite sidebar plugin built for the Unity 07 Old School RuneScape clan.

## Features

- Clan dashboard and member information.
- Clan activity, events, and Wise Old Man statistics.
- Random Trivia: a shared 60-second multiple-choice event with a 30-minute cooldown and a top-five leaderboard. Starting events is limited in the client to selected clan ranks.
- Discord button opens https://discord.gg/unity-07 in your browser.

The plugin is clan-specific; some screens may not be useful to players outside Unity 07.

## External services and privacy

The plugin communicates over HTTPS with `unity-clan-admin-portal.vercel.app` for Random Trivia and other clan services, and uses Wise Old Man and TempleOSRS endpoints for relevant statistics. Random Trivia submits the in-game character name and the selected answer to the clan backend; starting a trivia event also submits the character name and client-reported rank. Names and ranks are not independently authenticated by the backend. Do not use this casual trivia game for prizes or privileged operations.

The Discord button opens an external invite URL; it does not link accounts.

## Development

Requires Java 11+ and the included Gradle wrapper. Build with `./gradlew build` (Windows: `gradlew.bat build`). Run locally with `./gradlew run` (Windows: `gradlew.bat run`). The `run` task uses a development-only test launcher.

## Support

Report bugs at https://github.com/ZedAfterliferp/unity-clan-runelite/issues.

## Disclaimer

Community-created RuneLite plugin. Not affiliated with Jagex or RuneLite.
