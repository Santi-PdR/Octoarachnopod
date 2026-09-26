# Octoarachnopod — Forge 1.20.1

Forge port of ArachnoMod 1.8.4. The user has confirmed permission from the rights holder to make and publish this port.

The port retains attribution to iR3DN4X and TheCymaera. The source JAR is the behavior reference; implementation is being adapted to Forge 1.20.1 rather than NeoForge 26.1.2.

## Server-side AI control

The spider AI runs on the server. Server console, RCON, or an operator with permission level 2 can read or change its settings live, without restarting the world. For example:

```text
/arachnomod config chaseDistance get
/arachnomod config chaseDistance set 400
/arachnomod config enableWandering set false
```

`chaseDistance` defaults to 400 blocks for every untamed ArachnoMod variant. Target acquisition does not require line of sight. Other supported settings are listed by the `/arachnomod config` command tree.
