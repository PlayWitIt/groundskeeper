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
| `/gk swap <from> <to>` / `off` | any block to any other block (exposed tops only), e.g. `/gk swap sand red_sand` |

## Good to know
- Mycelium doesn't stop mobs spawning (only the mushroom biome does).
- Seeds leave a dirt drop each (the broken grass). It's thrown out only if the inventory is full when it needs to pick
  the shulker box back up.
- It won't use a tool with 2 or fewer uses left.
- Check the server's rules on automation before using it on a server.

## Developing
`./gradlew build` builds the jar. `./gradlew runSelfTest` builds a mini mountain in a test world (a plateau, a spike,
an overhang, a pond, a sapling and glass on grass), runs the job in survival, then speeds up random ticks 100x and
checks the circle ends up all mycelium with no grass getting in.
