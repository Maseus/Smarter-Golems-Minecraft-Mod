# Smarter Golems — Plan

**Target:** Minecraft 26.3 · Fabric Loader 0.20.1 · Fabric API 0.165.2+26.3 · Java 25 · Mojang official names (26.x is unobfuscated)

## Core idea
Copper golems are built the vanilla way (carved pumpkin on a copper block).
**Sneak + right-click** a copper golem opens a task GUI. A normal right-click keeps vanilla behavior.

## Task: Farming (first feature)
- Farm area: 15x15 by default (`farmRadius` in `config/smartergolems.json`, 7 = 15x15), centered on the block where the golem stood when the task was assigned (the "work anchor").
- Loop: find a fully grown crop in the area → walk to it → break it → replant from its own harvest → when holding items, walk to the nearest chest and deposit → repeat.
- Crops supported first: wheat, carrots, potatoes, beetroot (anything extending `CropBlock`).
- Deposit target: nearest chest/trapped chest/copper chest within the area or up to 4 blocks outside it. If there's none, the golem keeps holding items, and drops them when full.
- Assigning "No task" restores the vanilla copper golem behavior.

## Task: Woodcutting
- Finds the nearest tree in the area, chops for ~3s, then fells the whole tree (logs + natural leaves) into its bag.
- Replants a matching sapling on the stump (oak_log → oak_sapling, else any sapling); keeps up to 16 saplings per kind for this.
- Never cuts builds: a "tree" must have natural (non-persistent) leaves, no stripped logs, and a base of at most a 2x2 trunk on dirt/grass.

## Oxidation
- Work speed (walking + work durations) scales with oxidation: unaffected 100%, exposed 85%, weathered 70%, oxidized 55%.
- Waxing stops oxidation, so a waxed golem keeps its current speed.

## Milestones
1. **Project skeleton**: builds and launches with `./gradlew runClient`. ✅
2. **Interaction hook**: mixin on the copper golem's `mobInteract` that catches sneak + right-click (server side) and opens the menu. ✅ (sneak + empty main hand)
3. **Task data**: a persistent per-golem component (task type + anchor pos), saved with the entity and kept after reload. ✅ (Fabric Data Attachment API)
4. **Task GUI**: a screen with "Farming" and "No task"; selection sent to the server with a custom payload (server re-checks golem + reach). ✅
5. **Farming AI**: replaces the golem's vanilla brain while a task is active; harvest → replant → plant empty farmland → deposit. Carried items live in a saved 9-slot bag. Covered by game tests (run on every `./gradlew build`). ✅
6. **Polish**: particle outline of the work area while the GUI is open (copper = current, pale = preview), config file (`farmRadius`, default 7 → 15x15), planting / deposit sounds, sparkle on task assignment. ✅
7. **Woodcutting + oxidation slowdown**: shared `TaskWorker` base for all tasks; game tests cover felling/replanting and that pillars and log walls are never cut.

## Open questions (later)
- Future tasks: e.g. sorting (vanilla-like), guarding, mining.
