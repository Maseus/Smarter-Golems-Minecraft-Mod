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

## Task: Custom (taught by example) — break rules done (10a), place/use planned (10b)
- **Teach** button in the task menu puts the player in recording mode for that golem (action-bar counter, ~2 min timeout, sneak + right-click the golem to finish). Recorded spots get particle markers. Max ~16 rules, all within the golem's area.
- Each recorded action becomes a **rule**: *when the block at P is X, do A*. A is one of:
  - **Break** P (cobble/stone/basalt generators, clay, concrete, amethyst…).
  - **Place** item I at P (condition: P is air/replaceable), e.g. dirt.
  - **Use** item I on P, e.g. water bottle on dirt → mud, bucket on a water source.
- Recording works by diffing the block before/after the player's break / place / use (Fabric `PlayerBlockBreakEvents.AFTER`, plus a hook around item use on blocks/fluids), so it doesn't need per-item code.
- Golem loop: find the first rule whose condition matches the world and whose item is available (bag or supply chest) → do it. No rule matches → wait (e.g. mud drying into clay over pointed dripstone). Stateless, so it survives reloads.
- Supplies: items used by rules are fetched from the supply chest (keep up to 16 each); leftovers (empty bottles/buckets) and drops are deposited. Recording "fill bottle at water source" makes the golem refill its own bottles.
- Tools: takes the correct tool for each recorded break (pickaxe, shovel, axe…); drops follow vanilla tool rules; tools wear out.
- Safety: only touches recorded spots; never breaks unbreakable blocks or block entities (chests, spawners…).
- Example (clay farm): place dirt on the block above pointed dripstone → use water bottle (mud) → wait → break clay.

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
10. **Custom task**: (a) Teach mode + break rules (cobblestone generator) ✅ (3 game tests); (b) place/use rules and item supplies (clay farm).

## Open questions (later)
- Future tasks: e.g. animal keeping, sorting with item-frame filters, guarding, mining.
