# Assistant for the computer (Linux · Windows)

Claude controls your computer the same way the phone app controls your phone: you press a key, talk (or type), and it gets it done. It plans with a goal and a to-do list, runs shell commands, opens apps and links, reads and writes files, and uses screenshots + mouse/keyboard for anything graphical. A glowing edge, a small card with voice bars and highlights show what it is doing.

- **Linux**: Hyprland (Wayland), other Wayland compositors, X11. Tested on Arch + Hyprland 0.57 (Lua config), AZERTY keyboard.
- **Windows 10/11**: built and packaged by CI, **not yet tested on a real Windows machine** — please report problems.
- macOS: not supported yet.

## Install
Download from [Releases](../../releases) (tag `desktop-v…`): `Assistant-x.y.z.AppImage` (Linux), `Assistant.Setup.x.y.z.exe` or the portable `Assistant.x.y.z.exe` (Windows). Open **Settings**, paste your Claude API key (or use a Hearth server). Credits and costs are tracked exactly like on the phone.

### Linux requirements
| What | Needed for | Arch | Debian/Ubuntu |
|---|---|---|---|
| `grim` | screenshots on Wayland | `pacman -S grim` | `apt install grim` |
| `wl-clipboard` | typing text (paste) on Wayland | `pacman -S wl-clipboard` | `apt install wl-clipboard` |
| `python3` + writable `/dev/uinput` | mouse + keyboard (all compositors) | ACL usually set by systemd | see below |
| `maim` / `xdotool` | screenshots / input on X11 (optional) | `pacman -S maim xdotool` | `apt install maim xdotool` |
| `piper` or `espeak-ng` | spoken answers | `pacman -S espeak-ng` | `apt install espeak-ng` |

If `/dev/uinput` is not writable for your user: `echo uinput | sudo tee /etc/modules-load.d/uinput.conf`, then `echo 'KERNEL=="uinput", GROUP="input", MODE="0660", OPTIONS+="static_node=uinput"' | sudo tee /etc/udev/rules.d/99-uinput.rules`, add yourself to the `input` group and log in again. The app shows what is missing at the top of the Home screen.

### Hyprland
Wayland has no global hotkeys for apps, so on Hyprland the app binds your shortcut through `hyprctl` itself: record it under Settings → Voice → Shortcut (press the keys like in any app) and it is re-applied every time the app starts. For a permanent config entry Settings → System shows the exact lines:
```lua
-- hyprland.lua (Hyprland >= 0.55, Lua config)
hl.bind("SUPER + A",         hl.dsp.exec_cmd('/path/to/Assistant.AppImage --toggle'))   -- talk
hl.bind("SUPER + SHIFT + A", hl.dsp.exec_cmd('/path/to/Assistant.AppImage --ask'))      -- type
```
```ini
# hyprland.conf (older)
bind = SUPER, A, exec, /path/to/Assistant.AppImage --toggle
```
The overlay windows get float/pin rules automatically at startup (via `hyprctl eval`), your config is not modified. The accent colour is taken from your `general.col.active_border`; override it in Settings → Look.

### Windows
The default hotkey is `Ctrl+Alt+Space` (configurable). Speech output uses the installed Windows voices.

## Using it
- `Assistant --toggle` (or the hotkey): starts/stops a **voice session**. It keeps listening; every sentence is a command; say "stop"/"stoppen"/"klaar" to end. New sentences while it works are added to the task. Questions it asks you are spoken and you answer by voice.
- `--ask`: small prompt box. `--run "text"`: run a task. `--stop`, `--show`.
- Speech recognition is **local** (Whisper via onnxruntime): the model (~250 MB for *Small*, ~80 MB for *Base*) downloads once on first use. Names from your Memory are used to correct what it heard.
- Cheap first: shell, files, links and app launching are used before clicking. Destructive shell commands (`rm -r`, `sudo`, uninstall…) need your OK. Max cost per task is capped.
- Zero-token shortcuts: "open <app>", "onthoud dat …", "vergeet …".

## Develop
```
npm install --ignore-scripts && node node_modules/electron/install.js
npm start            # electron . --no-sandbox
npm test             # agent loop with a mock API
node test/platform-smoke.js   # what works on this machine
```
`--selftest [--selftest-input] [--selftest-stt]` exercise the overlay, mouse/keyboard and speech engine without the AI. (If your shell exports `ELECTRON_RUN_AS_NODE`, unset it.)
