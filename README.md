# Phone Assistant

Android app where Claude controls your phone through an Accessibility Service. Give a task by text or voice (e.g. "open Snapchat, open Anna's snap and send one back").

## Install
Download `phone-assistant.apk` from the latest [Release](../../releases/latest) and install it (allow unknown sources). Android 11+.

## Setup
1. Open the app and follow the setup card. Android blocks sideloaded apps from accessibility ("not safe") until you open App info → ⋮ → **Allow restricted settings**, then turn on *Phone Assistant* under Accessibility.
2. Paste your Claude API key (stored only on the device). Default model: `claude-haiku-5-5`.
   No API key? Tap the provider button to switch to **Hearth**: enter your [Hearth](https://github.com/veelvoer/hearth) server URL and a Hearth API key (model `haiku`, `sonnet` or `opus`). Hearth mode is text-only (no screenshots) and each step is slower, since Hearth runs Claude Code per request.
3. Type or speak a task → **Run**. Tap the red overlay to stop.

## How it works (efficiency)
- Sends a compact text list of on-screen elements instead of screenshots; screenshots only on demand.
- Old observations are dropped from history; system prompt/tools use prompt caching.
- Multiple actions per model turn when they don't depend on screen changes.

The key is sent only to api.anthropic.com. The service can see and act on everything on screen: use at your own risk.

## Voice (Nothing Essential Key)
Press and hold the Essential Key, say the task, and it runs. Needs the microphone permission (asked on first open). The key's other actions (e.g. double press = capture) are left alone.

## Updates
The app checks GitHub releases and shows an **Update available** card; tap it to download and install. Releases are signed with a fixed key, so updates install over the old version (v1.1.0 and older must be uninstalled once).
