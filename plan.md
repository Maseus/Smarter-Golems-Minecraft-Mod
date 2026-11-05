# Smarter Golems — Plan

**Target:** Minecraft 26.3 · Fabric Loader 0.20.1 · Fabric API 0.165.2+26.3 · Java 25 · Mojang official names (26.x is unobfuscated)

## Core idea
Copper golems are built the vanilla way (carved pumpkin on a copper block).
**Sneak + right-click** a copper golem opens a task GUI. A normal right-click keeps vanilla behavior.

## Task: Farming (first feature)
- Farm area: 9x9 by default, the same reach as water hydrating farmland (`farmingRadius` in `config/smartergolems.json`). It's centered on the nearest chest when assigned (e.g. a waterlogged chest in the middle of the farm), else on the golem. Woodcutting and collecting use `areaRadius` (default 7 = 15x15) centered on the golem.
- Loop: find a fully grown crop in the area → walk to it → break it → replant from its own harvest → when holding items, walk to the nearest chest and deposit → repeat.
- Crops: wheat, carrots, potatoes, beetroot (any `CropBlock`), nether wart, sweet berries (picked, bush stays), cocoa, pumpkins/melons grown from a stem (decorative ones are left alone), sugar cane/cactus/bamboo (base block kept).
- Plants carried seeds on empty farmland and nether wart on empty soul sand; keeps 16 of each seed.
- With a hoe: tills dirt/grass next to existing farmland within 4 blocks of water, one block per seed it carries.
- With bone meal: fertilizes growing crops, stems, berries and cocoa.
- Deposit target: nearest chest/trapped chest/copper chest within the area or up to 4 blocks outside it. If there's none, the golem keeps holding items, and drops them when full.
- Assigning "No task" restores the vanilla copper golem behavior.

## Task: Woodcutting
- Finds the nearest tree in the area, chops for ~3s, then fells the whole tree (logs + natural leaves) into its bag.
- Replants a matching sapling on the stump (oak_log → oak_sapling, else any sapling); keeps up to 16 saplings per kind for this.
- Leaves builds alone: a "tree" must have natural (non-persistent) leaves, no stripped logs, and a base of at most a 2x2 trunk on dirt/grass.
- Logs touching a tree count as part of it (documented limitation; keep builds out of the area).
- Felling removes only the leaves that would decay (vanilla rule: more than 6 leaf-steps from any log), so neighboring trees keep their canopies.
- Pushes through natural leaves blocking its way (breaks them like a ravager, keeps the drops); player-placed leaves are never broken.

## Task: Collecting
- Picks up dropped items lying in the work area (e.g. under a mob farm) and deposits them into a chest.

## Supplies, status and ownership
- Supply chest: when visiting a chest the golem takes a tool it can use (hoe for farming, axe for woodcutting; better axes chop faster) and up to 16 bone meal. It also makes a trip just for supplies when idle. Tools wear out; take one back with a normal right-click.
- Task screen shows the tool and the 9-slot bag, with a "Take items" button.
- Looking at a working golem shows "Task · Activity" in its name tag (synced status attachment).
- Owner lock: the player who assigns a task owns it; others (except operators) can look but not change the task or take items.

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
7. **Woodcutting + oxidation slowdown**: shared `TaskWorker` base for all tasks; game tests cover felling/replanting and that pillars and log walls are never cut. ✅
8. **Leaves**: push through natural leaves. ✅
9. **Bag GUI, more crops, tools, bone meal, collecting, status display, owner lock**: covered by game tests (16 total).

## Open questions (later)
- Future tasks: e.g. animal keeping, sorting with item-frame filters, guarding, mining.
