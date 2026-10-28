# Creator HQ Recording

Made by NotHeartYT.

A Paper plugin for Minecraft recording servers. Use `/creator` or `/smp`.

## Commands

- `advancement <player|@a> <true|false>`, `advancementall <true|false>`
- `joinmessages`, `leavemessages`, `deathmessages`, `keepinventory`, `feedback` (`<true|false>`)
- `invulnerable <player> <true|false>`, `invulnerableall <true|false>`
- `immortal <player> <true|false>` (never dies, stays at half a heart)
- `feed [player]`, `heal [player]`
- `nickname <player> <name|reset>`
- `mute <player> <duration> <reason>`, `unmute <player>`, `muteall <true|false>`, `unmuteall`
- `ban <player> <duration> <reason>`, `unban <player>`
- `freeze <player>`, `unfreeze <player>`, `freezeall <true|false>`, `unfreezeall`
- `jail set`, `jail <player>`, `unjail <player>`
- `vanish [player]`
- `clearitems <player|@a>`, `removeitem [player|@a] [material]`
- `tp <player> [player2]`, `tplook <player>`
- `kit save|give|delete|list`
- `give <player> <material> [amount]`
- `give kit <sourcePlayer>` (copies the shulker box in the source player's main hand to everyone online)
- `reload`

Durations: `10s`, `10m`, `1h`, `1d`, `7d`, `permanent`.

All commands need OP or the matching `creatorhq.*` permission.

## Build

Push to GitHub, open the Actions tab, and download the jar from Artifacts.
Put `CreatorHQRecording.jar` in the server's `plugins` folder.
