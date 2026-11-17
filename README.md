# Groundskeeper

A client-side Fabric mod for Minecraft 1.21.11 that turns the grass in a circle into mycelium, using as little
mycelium as possible. Hills and mountains are fine.

By **PlayWit Creations**.

## Why it needs so little mycelium
It uses the game's own rules (checked against the 1.21.11 code):

- Grass and mycelium spread **only onto plain dirt**, at most 1 block sideways, **1 block up or 3 down**. They never
  take over each other, farmland or path.
- A **hoe** turns grass into farmland. Dry farmland with nothing planted turns back into **dirt** on its own in about
  a minute (unless there's water within 4 blocks). Tilling makes dirt for free, with no drops.
- A **shovel** turns grass into **dirt path**, which nothing spreads onto.

So it:
1. Makes the outer ring of the circle **dirt path** (free). Grass outside is then at least 2 blocks from any dirt
   inside, and grass only reaches 1 block, so it can never get back in.
2. **Tills** all the grass inside (free). It turns to dirt by itself.
3. Puts down mycelium only as **seeds**: one every 5 blocks (about 4% of the area). On slopes it also works out where
   no seed could reach. Mycelium climbs only 1 block, so the top of a 2-block step or a lone spike gets its own seed,
   placed on top, because mycelium spreads 3 blocks down. Grass near water gets a seed too, because farmland there
   stays wet and never turns back into dirt.
4. Waits nearby, checks again every 30 seconds, and fixes anything that went green again, until the circle is all
   mycelium.

A radius-16 circle has about 800 grass blocks and needs about 30 to 40 mycelium. A shulker box of mycelium (1,728)
covers a circle of roughly radius 100 or more.

**Timing:** the spreading happens by itself, but only in daylight and only while you're nearby (within simulation
distance). With the default spacing, most of it is mycelium after about 13 minutes of daylight and all of it after
about 30 to 35. At night it pauses.

## Use
Carry a **hoe** (any) and a **shovel** (for the border; also makes breaking faster), plus your mycelium. With a silk touch and a normal or fortune shovel it uses the non-silk one on grass it swaps for dirt, so that grass drops dirt. Grass by water is swapped for dirt from your inventory, or mycelium if you have none, so a stack of dirt saves a little mycelium. A **shulker box
of mycelium** in your inventory is fine: when the loose mycelium runs out, it places the box, empties it, breaks it and
picks it back up.

Stand in the middle of where you want it and:
- **Numpad 1** starts (or carries on after a pause). With no circle picked it makes one around you, at the panel's
  radius (16 to start with). To see the plan first: `/gk circle <radius>`, then Numpad 1.
- **Numpad 2** pauses; press it again (or Numpad 1) to carry on.
- **Numpad 3** stops.

`/gk panel` opens the panel: radius slider, *Set circle around me*, Start/Stop, settings, and the plan (seeds, what you
have, what's missing). In the world you see the ring, **purple** dots where seeds go and **red** dots on grass it can't
get at. The keys can be changed in Controls, where there are also two unbound ones: the panel, and walking the circle
out (press it in the middle, walk to the edge, press it again).

**When you're hit** it lets go of the keys, the camera and the block it's breaking, so your other mods (kill aura) can
fight. It carries on 3 seconds after the last hit, once no hostile mob or other player is within 6 blocks.

On low health it holds off the same way until you're back above it. It stops when you die or disconnect, and waits while you have a screen open.

## Big areas (0.3.0)
Any size and shape, up to 16,000 × 16,000 blocks, worked a tile at a time over as many sessions and days as it takes.

- **Make the area:** `/gk area map` draws it on Base Insurance's map (needs Base Insurance 1.19.0 or later): rectangles, circles, polygons and brush
  strokes, added or cut out, with the size as you draw and the tiles' progress shown in purple;
  or build it from shapes: `/gk area add rect <x1> <z1> <x2> <z2>`, `add circle <r>`, `add ellipse <rx> <rz>`,
  `add square <half>` (around you), and the same with `cut` for holes (your base, a farm). `/gk area undo`, `/gk area clear`.
  `/gk area` shows its size, the tiles, and what it will take (mycelium, hoe uses, hours).
- **Start it:** Numpad 1 (with no circle picked) or `/gk area start`. Progress is saved per server and dimension in
  `config/groundskeeper/areas/`, so it picks up where it left off.
- **How it goes:** tiles of 64 × 64 (`/gk area tile <chunks>`; keep them within your simulation distance) in rows,
  back and forth. Each tile is tilled and seeded the usual way. Where a finished tile meets one still to do, its edge
  gets a temporary dirt path (a *seam*) so grass next door can't creep in; when the next tile is worked, the hoe turns
  the seam back into farmland and it becomes dirt like the rest. A tile that's had 15 minutes of daylight near you gets
  checked and fixed; once every tile is worked, it stands in each unfinished one until it's all mycelium.
- **Getting about:** its own walker for short hops; long trips go with Baritone if installed (as a Naughty or Nice trip
  if that's installed too).

## Staying alive
While a job runs it looks after you, without a bed:
- **At night** (and in thunderstorms), `/gk night bunker` (default): it digs a hole three deep, puts the block back
  on top as a roof (that also keeps phantoms away), and in the morning climbs out, filling the hole behind it. It picks
  a spot with solid walls, a safe floor and no water, sand or caves nearby. `zone`: walks to your safety zone
  (`/gk zone set` where you stand, `/gk zone distance <n>`, default 160) and digs in if that's too far.
  `work`: carries on. `stop`: stops.
- **Danger, any time:** it hides in a bunker from a warden, a crowd of monsters, or players: `/gk players naughty`
  (default: your Naughty and Look Into lists in Naughty or Nice), `strangers` (anyone not a friend or on your Nice
  list) or `off`. It runs from a lit creeper. Being hit still hands over to your kill aura as before.
- **Eats** when hungry (`/gk eat`), good food first and never rotten flesh, spider eyes, raw chicken, chorus fruit or
  golden apples; looks at the sky while eating so it doesn't plant carrots in the farmland. **Totem** into an empty off
  hand (`/gk totem`). Warns when armour is nearly worn out.

The panel has buttons for all of this.

## Settings
| | |
|---|---|
| `/gk circle <radius>` | circle around where you stand |
| `/gk start` / `pause` / `stop` / `panel` / `cancel` / `status` | |
| `/gk blocked` | grass it can't get at (a sapling, glass or a slab on top) |
| `/gk skipped` | blocks it gave up on, and why |
| `/gk spacing <2-12>` | blocks between seeds (default 5; smaller is faster but uses more) |
| `/gk border <path\|mycelium\|none>` | the outer ring (default path) |
| `/gk speed <1-20>` | block changes a second (default 10) |
| `/gk flowers <true\|false>` | break flowers on grass (default true) |
| `/gk watch <true\|false>` | stay and watch it spread afterwards (default true) |
| `/gk minhealth <n>` | hold off at or below this health (default 10, 5 hearts) |
| `/gk tools <on\|off>` / `reserve <2-500>` | tool guard: put tools aside before they break, to mend (default on, 10 uses) |
| `/gk swap <from> <to>` / `off` | any block to any other block (exposed tops only), e.g. `/gk swap sand red_sand` |

## Good to know
- Mycelium doesn't stop mobs spawning (only the mushroom biome does).
- It swims: out of ponds, up waterfalls, and around running water when there's a dry way. Grass under running water
  can't be reached (it shows in `/gk blocked`), so it seeds around it instead.
- Seeds leave a dirt drop each (the broken grass). It's thrown out only if the inventory is full when it needs to pick
  the shulker box back up.
- **Tool guard** (on by default): it puts a hoe, shovel or pickaxe aside when it's down to 10 uses, so you can mend it
  instead of losing it. It moves on to another one if you carry one, else it stops and tells you which tool needs
  mending. `/gk tools reserve <n>` keeps more or fewer uses back; `/gk tools off` uses tools until they break.
- Check the server's rules on automation before using it on a server.

## Developing
`./gradlew build` builds the jar. `./gradlew runSelfTest` builds a mini mountain in a test world (a plateau, a spike,
an overhang, a pond, a sapling and glass on grass), runs the job in survival, then speeds up random ticks 100x and
checks the circle ends up all mycelium with no grass getting in. The map also has a waterfall and a 2-deep pond, and it
checks the walker doesn't get stuck. It also hits the player and pauses the job, to check it lets go and carries on.
Then the tool guard: a nearly worn hoe is refused, and a job stops with the hoe kept at exactly 10 uses.

`./gradlew runAreaTest` does the big-area side for real on a flat world: a 48 × 32 area with a cut-out, in 16-block
tiles; seams between worked and unworked tiles; progress read back from the file as after a restart; eating and the
totem; digging in at night and climbing out in the morning; hiding from a warden; running from a lit creeper; then
every tile all mycelium, the cut-out untouched and no seams left.

The plain-Java parts (area shapes, tiles, seams, day and night, threats, bunker spots) are checked without the game as
part of `./gradlew build` (CoreCheck, 51 checks).
