# Superb Enemies

Enemy and boss addons for [Superb Warfare](https://github.com/Mercurows/SuperbWarfare) on Minecraft 1.20.1 and Forge 47.

<table>
<tr>
  <th>Folder</th>
  <th>Purpose</th>
</tr>
<tr>
  <td><code>SuperbEnemies/</code></td>
  <td>Boss and enemy addon.</td>
</tr>
<tr>
  <td><code>SuperbWarfare/</code>, <code>SimpleEnemies/</code></td>
  <td>Read-only reference copies.</td>
</tr>
</table>

## What's New?

<h1>TANKS!</h1>

## Features

- A-10 Warthog boss with cannon, rockets, bombs, missiles, flares and multiple attack runs.
- AC-130H boss with 25mm, 40mm and 105mm guns, orbit attacks and multi-target fire.
- LAV-25 boss: an amphibious APC with a 25mm cannon, coaxial machine gun and wire-guided missile, driving on terrain-aware routes.
- M1A2 MBT boss: a tracked main battle tank with a 120mm cannon, coaxial machine gun and a .50 cal on the weapon station.
- M2 Bradley boss: a tracked, amphibious fighting vehicle with a 25mm cannon that elevates to 60 degrees, a coaxial machine gun and wire-guided TOW missiles. A better LAV-25.
- ZTZ-99A MBT boss: a tracked main battle tank that fights like the M1A2 with the same guns, built on Superb Warfare's `ztz_99a`.
- Teammates: every boss has a Teammates table of the other boss types it never targets (only its own type by default). Teammate tanks drive in a single-file convoy, fan out into a wedge when the leader has a target, call out where the enemy is when one of them is shot, and hold fire while a teammate is in the line of fire.
- Smoke screens: the tank bosses use them to cover flanks and break line of sight, and smoke now blinds bosses with DYNAMIC targeting (planes and tanks).
- Player Dummy for testing boss behavior.
- Dummy Soldier with Igla MANPADS, missile guidance, cover behavior and resupply.
- Drone: a fast, fragile kamikaze drone that carries a mortar shell, chases players, tells the other drones where its target is and dodges bullets. The tank bosses shoot at it.
- Optional client hotbar counters for loaded player dummies, dummy soldiers and drones.
- Configurable targeting, weapons, movement, ammunition, flares and resupply.
- AI thinking debug output: every boss, drone, soldier and dummy can print what it is doing and why (state changes, decisions, target picks, shots, routes) to the console and to a small on-screen log box, switched on from the **Debug** category of the config screen.
- Weighted, cover-aware boss targeting: bosses lose sight of targets under overhead cover and attack their last known position. After line of sight breaks, a boss keeps tracking where the target really is for `sightLossGraceSeconds` (default 5) before it falls back to the last known spot, so it seems to know the target's general location.
- Elevation-aware tanks: a tank whose gun cannot depress or elevate onto its target drives to a stand-off spot it can fire from. A tank stuck on a rooftop or ledge drives off the edge (up to 6 blocks) or is set down on the ground below. Firing, cover, patrol and flank spots never pick rooftops.
- Steadier tank decisions: tanks re-decide their fighting move every half second and stick to a choice for `decisionCommitSeconds` (default 2) unless something urgent happens, so they no longer flip-flop between plans.
- Vision debug: with AI thinking on, every boss draws its sight line in the world (green = seen, yellow = tracking during the grace, red = last known spot) with a label naming its target and the target's category, and tanks show their sight cone. The on-screen log box can be moved and resized from the config screen.
- In-game config screen with a scrollable sidebar (collapsible groups: Aircraft, Armour, Dummies, Drones, each listing its mobs, so new mobs fit without crowding the screen), a search box that looks through every mob's options at once, sliders, validation, reset controls and unsaved-change tracking (`*` / `!` markers show on the pages and on their group).
- Config screen hotkey: press **K** in-game (rebindable under Controls > Superb Enemies) to open the config screen without the Mods menu; press it again to close it (not while typing in the search box). Like the Mods screen button it edits the local config: on a dedicated server the server's own file is what counts.

## Bosses

### A-10 Warthog

- Hunts players, mobs, hostile mobs or other aircraft according to its target weights.
- Uses gun, rocket, bomb, missile, combination and taunt attacks.
- Circles the spot where its last target was seen (for example where it died) until a new target appears. When a target is lost without dying (it left the aggro range, or went invalid), the plane keeps attacking its last known position for `lastKnownStrikeSeconds` first.
- Avoids terrain, reacts to homing missiles and supports weighted flare maneuvers.
- Treats terrain beyond the nearest player's render distance as flat, so a short render distance (for example with Distant Horizons) does not stop it from attacking.
- Has health phases, a boss bar, crash behavior, loot and experience rewards.
- With `unstuckTeleport` on (default), a plane wedged in terrain or a building (it stayed within 6 blocks of one spot for `unstuckSeconds`) is teleported up into open air and flies on; both planes have it.
- Supports finite ammunition and saved resupply trips. A replacement returns to the area of the fight, not to the spawn point, and keeps circling it. With `limitedAmmo` on and some ammunition or flares used, an idle boss (no target for `idleResupplySeconds`) may also leave to rearm (`idleResupplyChance`, rolled every 10 seconds).

### AC-130H Angel of Death

- Holds a high left-hand orbit instead of diving.
- Fires the 25mm cannon, 40mm Bofors and 105mm howitzer independently.
- Supports normal, split-fire and focus-fire patterns.
- Can attack players, mobs, hostile mobs or other aircraft according to its target weights.
- Automatically switches to piercing rounds when a block is between a gun and its target.
- Supports missile evasion, taunt flares, finite ammunition and resupply. The replacement returns to the area of the fight, and an idle boss with a used load may leave to rearm like the A-10 (`idleResupplySeconds`, `idleResupplyChance`).

### LAV-25

- A wheeled, amphibious armoured car built on Superb Warfare's `lav_25`. Boss title: The Dune Stalker.
- **Weapons:** 25mm cannon (`cannonShell`: `AUTO`, `AP` or `HE`), coaxial machine gun and a wire-guided missile. The boss steers every missile it has in the air onto the target it was fired at until it lands (it leads a moving target, and flies to the last seen spot if that target is hidden or gone), and the cannon keeps firing while it flies. A cooldown (`missileCooldownSeconds`) sits between shots, so vehicles get a missile whenever one is loaded.
  - `AUTO` fires armour-piercing shells at vehicles, hidden targets and targets behind cover, and high explosive shells at targets in the open. AP pierces a few blocks, HE explodes on the first block. The switch to AP is immediate when a vehicle becomes the target; the switch back to HE waits for the gun to be empty, reloading or idle.
  - The machine gun is used against soft targets in the open within 80 blocks. The missile is used against vehicles, and now and then against distant soft targets.
- **Ground AI and pathfinding:** plans routes over the terrain on a coarse grid (an A* search shared across all tank bosses so it stays cheap). It avoids cliffs, steep slopes, lava, fire, magma, powder snow, cactus, dragon's teeth and tree trunks (under a canopy every column of a cell is checked, plus a margin for the M1A2's wide hull; leaf blocks at ground level also block the LAV-25), prefers open ground to trees and never loads chunks: an unloaded area counts as impassable. If the target is unreachable it drives to the closest reachable point and fires from there. When it gets stuck it reverses out and replans, and it withdraws after three failed recoveries.
  - **Block awareness:** the coarse grid can miss a pillar, a barricade or a one block wall inside a cell, so the tank also looks at the real blocks. Every route is checked with a block-exact sweep of the hull's footprint (a step the hull cannot make marks its cell as blocked and the route is planned again), and while driving the tank sweeps the next 8-22 blocks ahead of its nose and swerves to the nearest clear heading (up to 100 degrees off, no sprinting while it swerves). A tank boxed in turns on the spot (tracked) or backs off, tells its route planner, and plans round the block; a cell that stuck the hull stays blocked for 60 seconds. A tank that fails to free itself three times in a row (flipped or wedged) is set down upright on the nearest free ground within 48 blocks and drives on; it only leaves and comes back after `resupplySeconds` when no such spot exists. Overpasses with 3 blocks of headroom are not walls, a ledge up to the hull's climb height (1.5 for the LAV-25, 2.25 for the M1A2, ZTZ-99A and Bradley) is not either.
- **Water:** the hull floats. With `crossWater` on (default) it swims across lakes and rivers when that is cheaper than going around; with it off it drives around deep water and leaves any water it ends up in by the nearest bank.
- **Behaviour:** patrols within `leashRange` of where it spawned, then hunts. It holds `engageRange` from its target: it closes in once the target is more than 1.3 ranges away and keeps closing until it is inside 1 range, backs away when closer than 0.45 ranges (only onto ground it can drive on) and otherwise sits still, turning its hull to the threat only when the target is well off its nose (the turret does the aiming). Patrol pauses and flank rolls are longer than before so it does not keep roaming. When it is hit hard (`pullBackDamagePercent`) it pulls back to cover, see **Pull-back retreat** under [Tank bosses](#tank-bosses). The missile goes at vehicles whenever it is loaded and the cooldown has passed, and at players and mobs on most cooldown cycles (80%).
- **Targeting, smoke and flanking:** see [Tank bosses](#tank-bosses) below.
- **No taunt:** the tank bosses have no taunt attack and no `tauntEnabled` option.
- **Resupply:** with `limitedAmmo` on, an empty gun sends it driving away. It withdraws once it is unseen or far away and returns after `resupplySeconds` on the ground near the fight with the same health and a full load.
- Has a boss bar, loot and experience rewards. The tank fights to its last point of health (there is no knocked-out phase at 10%); when it is destroyed the wreck explodes and pays out immediately, and the boss bar empties at 0.

### M1A2 MBT

- A tracked main battle tank built on Superb Warfare's `m_1a_2`. Boss title: Iron Colossus.
- **Weapons:** 120mm main cannon (`cannonShell`: `AUTO`, `AP` or `HE`, same rule as the LAV-25), coaxial machine gun and a commander-operated .50 cal on the weapon station.
  - The .50 cal picks its own secondary target: aircraft first, then the nearest soft target within 70 blocks, otherwise the main target. It engages within 100 blocks, and looks for its own targets within 50 blocks. The main cannon reaches 300 blocks (the coaxial machine gun only 45), and the M1A2 and ZTZ-99A see 256 blocks and hold 110 by default.
  - The main cannon can only elevate 20 degrees, so aircraft are only engaged inside a gun's elevation.
- **Ground AI and pathfinding:** same planner and driver as the LAV-25. The tracks pivot in place, climb steeper ground (2.25 block steps against the LAV-25's 1.5) and crush leaves, so bushes cost far less than for the LAV-25. It is slower than the stock M1A2 (forward speed rate 0.6 instead of 0.78).
- **Water:** the hull sinks. The planner treats any water as impassable (a cell with water in any of its sampled columns counts) and goes around lakes and rivers. If the target is across water it drives to the nearest shore and fires from there, it will not reverse into water while backing off, and it leaves water by the nearest bank if it ends up in it.
- **Peek and shoot** (`useCover`): instead of driving at its target it finds cover around it (see **Cover awareness** under [Tank bosses](#tank-bosses)), steps out to a firing spot beside it, fires one main cannon round, drops back behind the cover while the gun reloads, and repeats once the gun is loaded. The cannon stays quiet while it hides (no wasted rounds into the wall), the turret stays laid on the last seen spot, and it does not drop into a search while it hides. A pair of spots is dropped when the cover stops hiding it, the line stays blocked at the firing spot, it gets stuck or the target leaves the engage band.
- **Shoot and scoot (fallback):** with no cover about, or `useCover` off, it picks a firing spot: passable, dry, a clear line from the turret to the target, inside the engage band and preferably on higher ground than the target. After every main cannon round it moves to another spot a short hop away and stays in that area. It picks a new spot as well when the target leaves the engage band or the line stays blocked. With no spot found it closes in on the target.
- Targeting methods, smoke, flanking, resupply and no-taunt are the same as for the LAV-25.

### M2 Bradley

- A tracked, amphibious fighting vehicle built on Superb Warfare's `bradley`: a better LAV-25. Boss title: The Steel Hornet. Boss bar colour white.
- **Weapons:** 25mm cannon (`cannonShell`: `AUTO`, `AP` or `HE`, same rule as the LAV-25), coaxial machine gun and wire-guided TOW missiles. It reuses the LAV-25's gunnery: the boss steers each missile onto the target itself, a cooldown (`missileCooldownSeconds`) sits between shots, and the cannon keeps firing while the missile flies.
- **Compared with the LAV-25:**
  - **Tracked hull:** the tracks pivot in place and climb 2.25 block steps (the LAV-25 climbs 1.5). It is still amphibious: the hull floats and `crossWater` works exactly as for the LAV-25.
  - **Longer reach:** the cannon engages out to 170 blocks and the missile to 200 (LAV-25: 150 and 170).
  - **Higher elevation:** the gun elevates to 60 degrees (LAV-25: 27 degrees), so it can engage aircraft much higher. Aircraft are still only engaged inside the elevation of the gun.
  - **Tougher defaults:** `maxHealth` 2200 (LAV-25: 1500), `xpReward` 800 (600), `missileCooldownSeconds` 6 (8), `wireMissileRounds` 6 (4), `cannon25mmRounds` 400 (300) and `engageRange` 70 (60). Its loot is the LAV-25's table x1.5.
- Ground AI, pathfinding, water handling, targeting methods, smoke, flanking, resupply and no-taunt are the same as for the LAV-25 (see [Tank bosses](#tank-bosses)).

### ZTZ-99A MBT

- A tracked main battle tank built on Superb Warfare's `ztz_99a`. Boss title: The Jade Dragon. Boss bar colour red.
- Behaves exactly like the M1A2 boss, with the same config defaults and the same loot table: the same peek and shoot behaviour (steps out of cover for one main cannon round at a time; shoot and scoot from a firing spot is the fallback with no cover about or `useCover` off), with a main gun that depresses 6 degrees instead of the M1A2's 9, the same guns (125mm main cannon `AUTO`, `AP` or `HE`, coaxial machine gun and the .50 cal on the weapon station operated by the commander), tracks that pivot in place, a hull that sinks (water is impassable) and leaves that get crushed.
- Slower than the stock tank (forward speed rate 0.6 and backward speed rate 0.4).
- Targeting methods, smoke, flanking, resupply and no-taunt are the same as for the other tank bosses.

### Tank bosses

All four tank bosses (LAV-25, M1A2, M2 Bradley and ZTZ-99A) share their AI. Their options live in `superbenemies-lav25-common.toml`, `superbenemies-m1a2-common.toml`, `superbenemies-bradley-common.toml` and `superbenemies-ztz99a-common.toml`. The M2 Bradley reuses the LAV-25's gunnery and the ZTZ-99A the M1A2's.

- **States:** patrol, investigate a noise, search the last seen area, engage, flank, evade, pull back to cover, retreat to resupply, wrecked.
- **STATIC targeting** (`targetingMethod`): inside `aggroRange` the boss always knows where every target is, never searches or investigates, and ignores smoke.
- **DYNAMIC targeting** (default): the boss only targets what it has a clear line of sight to within `sightRange` (blocks and smoke block it, glass and leaves do not).
  - **Scouting:** when it loses its target it shells the spot where it was last seen, then sweeps a ring of waypoints around that spot (24 to 48 blocks, widening up to 96) until `searchSeconds` run out, and then goes back to patrolling.
  - **Hearing:** noises within `hearingRange` make it drive over to investigate: gunshots, explosions, moving engines, footsteps and breaking or placing blocks. Loud noises carry the full range, quiet ones a fraction. Sneaking players make no footstep noise. Noises from the boss itself and its crew are ignored.
  - **Pinpointing gunfire:** the cannon fire (shells, missiles, rockets) and the machine gun fire of other vehicles, tanks and aircraft included, is heard from as far as Superb Warfare's own sound reaches (the weapon's `SoundRadius`, 16 blocks per point: 512 blocks for a 120mm cannon, 192 for a coaxial machine gun), times `cannonHearingPercent` or `machineGunHearingPercent` (default 50, 0 = deaf to it). The tank places the shooter within `hearingScatterPercent` of the distance (default 5), swings the turret toward the shot and, when the shooter is a valid target for it, takes it on as its target at that spot: it shells the place and hunts the shooter like one that dropped out of sight. A shooter it cannot take on (a target weight of 0, an aircraft out of reach) is only investigated. Fire of a teammate is not heard.
  - **Smoke as a clue:** a smoke cloud (a tank's smoke launcher, a smoke grenade) tells it that a tank is about. A tank with nothing to do (no target, not in a formation, not answering a teammate's call) that sees a fresh cloud within `smokeScoutRange` (default 192, 0 = ignores smoke) and has a clear line to it over the blocks drives there and sweeps the area like it does for a noise. Each cloud is scouted once; a tank's own smoke is no clue, a teammate's is.
- **Smoke:** the boss fires its smoke launchers toward its turret direction when hit (`smokeWhenHitChance`), against an incoming guided missile (`smokeAgainstMissiles`), against an aircraft its guns cannot elevate to (`smokeAgainstAir`) and to cover a flank. One salvo per 10 seconds, except for the missile reaction. With `limitedAmmo` on, the launchers hold `smokeCharges` salvos until resupplied.
- **Missile shoot-down** (`shootDownMissiles`, `shootDownFailPercent`): paired with the smoke against an incoming guided missile. Once the tank has noticed a missile that is tracking it, the turret and the weapon station swing onto it and every enabled machine gun with rounds left fires at it (the cannon stays quiet; a barrel held on the tank's own wire-guided missile is never taken over). Each engaged missile is rolled once: `shootDownFailPercent` percent (default 35) of them get through, the rest are destroyed shortly after the first round leaves the barrel. A missile closer than 16 blocks is not engaged, and one that comes that close before the rounds land gets through. Works independently of `smokeEnabled`; smoke that lures the missile away ends the engagement.
- **Flanking** (`flankingEnabled`, `flankChance`): while fighting, every 6 to 10 seconds there is a chance that it pops smoke and sprints to a point 60 to 110 degrees off the line to its target, at `engageRange`.
- **Strafing and hull-down (LAV-25 and M2 Bradley):** instead of standing still and shooting, they drive in legs along the engage ring around the target (25 to 50 degrees of arc per leg, keeping a direction for a few legs and then often reversing), the turret firing all the while. The legs run into one another: the next leg is chosen as soon as the tank is within about 8 blocks of the end of the current one, so it never brakes to plan, and while the route to a new point is still being searched it drives straight at it. They do not hold a formation slot or stop for the formation's halt on contact, and when they cannot back off from a close target they keep circling instead of standing still. Every few legs, or when the line of fire stays blocked, they take a hull-down leg: they drive to a spot where terrain or a structure covers the hull but the turret still sees the target and the gun can be laid on it, and park there for a second or two, rocking back out of sight and forward again once, and then strafe again.
- **Cover awareness** (`useCover`, default on): while fighting, each tank scans the ground within 6 to 40 blocks (about 48 columns per tick, nearest first, never loading chunks) for walls, buildings and ridges that block sight, and works out for each one a hide spot behind it (out of the target's sight, body and turret) and peek spots next to the hide spot (straight toward the target, for a crest or a wall top, and around a corner). A peek spot only counts when it is passable, in the engage band, a short drive from the hide spot, the turret sees the target from it and the main gun's elevation and depression limits (M1A2 +20/-9, ZTZ-99A +20/-6, LAV-25 and Bradley +27 or +60/-10) can reach the target from it, so a tank never picks a hill from which it cannot lay the gun on a target in the valley. Spots are scored for full cover, a body that stays hidden at the peek spot (hull-down), high ground over the target, the ideal range, a short drive and a gun not at the end of its travel. The results are refreshed when the target moves 8 blocks, the tank 12 or every 3 seconds, a few columns per tick, so the cost stays small with several tanks. The light tanks use it for their hull-down and pull-back spots, the heavy tanks for their peek and shoot cycle. With nothing found (open ground) they fall back to random spots (light tanks) or shoot and scoot (heavy tanks). Off, they always use the random spots and shoot and scoot.
- **Pull-back retreat** (`pullBackDamagePercent`, default 10, 0 = off): every tank boss, in any state except evading, retreating to resupply or knocked out. When it loses `pullBackDamagePercent` percent of its maximum health within 3 seconds (friendly fire does not count) it pops smoke, sprints to cover, turns its front to the attacker and holds there for 4 seconds with its guns still working, then carries on with what it was doing (a cannon peek cycle, a strafe, a patrol). What it hides from decides the shelter: from a tank, soldier or anything else on the ground it looks for a wall, building or ridge that hides the whole hull from the attacker (the cover scan first, random spots when the scan has nothing); from an aircraft its guns cannot elevate to it looks for a spot where terrain or a structure is between the hull and the aircraft and, with none, breaks away under smoke instead (a roof does not count as shelter: the ground model treats one as ground, so a tank never plans a route under it). An aircraft that hurts it without reaching the damage threshold still gets the older reaction (`smokeAgainstAir`): smoke and a hard turn across its bearing. A run is started at most once every 8 seconds, tried again after 2 seconds when no cover is found, and given up after 20 seconds. This replaces the cover dash the LAV-25 and Bradley made after every hit.
- **Wall breaching** (`wallBreaching`, default on, needs `blockDamage`): the route planner only routes round walls. When the way round is a long detour (at least 40 blocks and twice the straight distance), ends short of the goal or does not exist, and the tank has no target in sight, it sweeps the straight line to its goal with the hull footprint. If the first blocked column is solid and breakable (not bedrock, a container, lava or cactus; nothing tougher than stone or brick class) and the hull can enter the line again within 6 blocks behind it, the tank stands off 11 to 34 blocks (backing up if too close), lays the main gun alone on the wall and fires, looks at the wall again after each volley (retargeting deeper or to the side while the hole is narrower than the hull), then drives through and plans again from the far side. It gives up for a minute after 8 shells (60 rounds for an automatic cannon), after 75 seconds, or when it has no room to back off, and keeps 3 rounds in reserve with `limitedAmmo`.
${BULLET}
- **Peek and shoot (M1A2, ZTZ-99A):** with cover about, the tank hides behind its hide spot with its hull to the target while the main gun reloads (the turret stays laid, the cannon stays quiet and the machine guns still work), steps out to the peek spot once the gun is loaded (or after at most 5 seconds, less than `breakDetectionSeconds` allows), fires one main cannon round, and goes straight back, reversing when the hide spot is close behind so its front armour stays to the enemy. It does not drop into a search while it hides on purpose.
- **Sight cone** (`sightConeDegrees`, `sightConeRangeMultiplier`; DYNAMIC): along the barrel, inside a cone `sightConeDegrees` wide, the boss sees `sightConeRangeMultiplier` times as far as `sightRange`, so a tank looking toward a distant sniper can find them. 0 degrees turns the cone off.
- **Return fire** (`returnFireEnabled`, `returnFireSeconds`): when shot by something it cannot see (small arms, a sniper) the tank turns its turret toward the shooter and suppresses it with machine guns and high explosive for that many seconds. When it knows who shot it, the spot is the shooter's real position (within about 1.5 blocks) and follows it while the hits go on; a shot with no known shooter is located by the damage source, with some error. It stops as soon as it sees a target. The teammates that hear the team call shoot at the same spot (see Team call). 0 turns it off.
- **Team call:** a tank that is under attack (hit by anything that is not a teammate, for the next 5 seconds) reports where the enemy is, twice a second, to every tank that lists it as a teammate within 400 blocks. Those tanks take the enemy as their target even from beyond their aggro range, shell the reported spot while it is out of sight (they keep it for 10 seconds without sight, whatever `breakDetectionSeconds` says), and, while they see no target of their own, drive to a spot beside the attacked tank and face the enemy, side by side in a loose line a `formationSpacing` apart (at least 12 blocks), so the teammates end up close together. A hit sends the call out at once. When the attacked tank shoots back (return fire), the call carries that exact spot, and the teammates that see no target of their own shoot at it too, for as long as the call is refreshed, so the whole group puts its shells on one place. A hit with no known source (an explosion) still calls the teammates over, facing the way the attacked tank faces, but they do not shoot at a guess. A tank that already sees a target of its own keeps it. A spot that cannot be reached for 5 seconds is dropped for 20.
- **Spotted icon** (`showSpottedIcon`, Player page of the config screen): while a tank boss within 256 blocks has you in sight as its target, a thin ring appears around the middle of your view, like the detection ring of a stealth game. For each such tank an arc on the ring and an eye just outside it pivot round the ring to the tank's bearing from where you look (up = dead ahead, right = to your right, down = behind), and swing to the top as you turn toward it. The closer the tank, the more opaque and wider its arc and eye (faint at 256 blocks, solid beside you). The mark stays for a third of a second after the tank loses sight. Client side only.
- **Ridden targets:** a player riding a vehicle is seen through the vehicle (centre, top and corners of its hitbox), so a tank that has the vehicle in plain view no longer loses the rider.
- **Idle resupply** (`idleResupplySeconds`, `idleResupplyChance`; with `limitedAmmo`): a tank that has patrolled for `idleResupplySeconds` without a target and has used ammunition or smoke may retreat to rearm, like an empty one.
- **Targets** are chosen by the five target weights, including `tankWeight` (default 4): manned tanks, APCs, anti-air vehicles and the other tank bosses. Tank bosses of the same type are teammates by default and never target each other; different tank types fight each other unless they list each other in their Teammates table. Aircraft are only candidates while some enabled gun can elevate to them.
- **Formations** (`formationEnabled`, `formationSpacing`): tanks that list each other as teammates and are within 64 blocks of each other drive as a group. The tank with the lowest entity id leads (it drives as usual) and up to five others follow:
  - **Spacing:** every tank boss, in a formation or not, keeps 3 blocks between its hull and every other tank boss's hull. Hulls are measured by their real length and width, turned the way each tank faces (an M1A2 or ZTZ-99A is about 10 blocks long, so nose to tail needs about 13 blocks between centres, side by side about 8). The tank with the higher entity id gives way (it reverses or drives clear, at most 100 ticks per episode; when the hulls actually touch it gives way in any state, even mid-flank or mid-evade, and without waiting for the cooldown), routes bend around tanks in the way, and patrol, search, flank, cover, peek and sniping points are not picked next to another tank or its destination. Followers hold their slot with a hold radius of 3 blocks and resume beyond 5, wheeled hulls only turn when well off the heading, and a follower that cannot reach its slot for 100 ticks leaves the formation for 10 seconds.
  - **Column (convoy):** while the leader has no target the followers line up `---` in single file behind the leader, each `formationSpacing` blocks further back, along the path the leader drove. This is the shape of a group that is travelling or waiting, on open ground as well as in a ravine, a road or a tree line. A follower drives along the leader's trail (it aims at the trail about 9 blocks ahead of itself, so it takes the same corners and gaps) instead of planning a route to its slot, and only falls back to the route planner when that path is blocked or it is more than 30 blocks off it. The spacing is never less than the two longest hulls of the group plus 5 blocks (about 15 for two M1A2s), whatever `formationSpacing` says, and the leader waits for a follower that is more than 2.5 spacings behind. Tanks of one group no longer swerve round each other while they hold their slots. The slots of a column that has only just set off (a trail shorter than the column) lie on a straight line carried on behind the oldest part of the trail, so they do not swing about while the leader turns. The tank nearest the leader holds slot 1, the next one slot 2, and so on (a scattered group forms up without tanks crossing each other), and the leader picks its patrol goals within about 55 degrees of the way it faces (wider if nothing fits) so the column does not turn back through itself.
  - **Wedge:** only while the leader has a target, the followers hold the arms of a `<` behind the leader, pointing at that target. Every slot, and the ground between it and the leader, must be passable; otherwise the group stays in a column. The group goes back to a column when the leader loses its target.
  - Followers hold their slot while patrolling, investigating and engaging (the LAV-25 and M2 Bradley do not hold a slot while engaging: they keep strafing), and their turrets fire at their own targets. Evading, retreating to resupply, searching, flanking, leaving water, a line of fire blocked for too long and a leader more than 96 blocks away override the formation. Followers ignore noises, and the leader waits (up to 5 seconds per patrol goal) for a straggler.
  - **Halt on contact** (`formationHaltSeconds`, default 4, 0 = off): when the group makes contact with a target, every tank in it (leader included) stops in place and fires for that long (the LAV-25 and M2 Bradley keep rolling and firing), then the formation breaks and each tank fights on its own until the group has been out of contact for 10 seconds, when it forms up again.
  - Both tanks must list each other for a formation. Different tank types can share one; the leader's `formationSpacing` is used. Groups are rebuilt every second, so a dead, withdrawn or crippled tank, or a changed option, is picked up at once. Formations are not saved.
- **Hold fire:** a tank does not fire (cannon, machine guns, the weapon station) while a teammate boss stands in the line of fire. There is no damage immunity: a stray shell or an explosion can still hurt a teammate. A blocked line of fire makes the tank reposition after a couple of seconds, like a line blocked by terrain.
- **Smoke blocks sight for DYNAMIC bosses**, plane bosses included: a target standing in or behind a smoke screen is treated as hidden, so a DYNAMIC A-10, AC-130 or tank boss attacks its last known position instead. STATIC bosses are not affected by smoke. A tank sees through its own smoke and through the smoke of tanks it lists as teammates, so a salvo that covers a flank does not blind the tank that fired it.
- **Boss crates:** the boss spawn items are Superb Warfare supply crates (the same model as its container). Using one checks the spot, sets down a crate there and plays Superb Warfare's crate opening animation (the lid, the bang, the open sound); one second later the crate is gone and the boss is out. A plane's crate stands on the terrain at the clicked column and the plane leaves from the sky above it. If the boss can no longer be released (Peaceful was switched on meanwhile) the item is dropped again.
- **Ground spawning:** the boss crates place the boss on the clicked surface. They are refused in Peaceful, in dimensions with a ceiling (Nether), within 16 blocks of the world border, on lava or other hazards, on unloaded chunks, where the hull would not fit, and in water for the M1A2 and ZTZ-99A (the LAV-25 and M2 Bradley float and may be placed on water). Ground bosses skip the sky and altitude checks of the planes. The tank bosses are put forward of the click along the player's look direction (one hull length plus 6 blocks, then shorter if that spot does not fit, preferring a spot at least 3 blocks clear of the player) so they do not appear on top of the player. A resupply replacement is placed on a nearby passable surface.

## Test Entities

### Player Dummy

- Spawn with `superbenemies:player_dummy` or its spawn egg.
- Supports `STRAFE`, `ROAM` and `STATIC` movement.
- Can display health, avoid bosses, use cover and be made invulnerable.
- Hides at the nearest spot with a solid block overhead (glass, leaves and fences don't count, same as boss targeting) and faces the direction it runs.

### Dummy Soldier

- Spawn with `superbenemies:dummy_soldier` or its spawn egg.
- Finds, locks and fires Igla missiles at boss aircraft.
- Supports flare discipline, staggered shots, reloads, cover and resupply.
- Uses the same movement modes as the Player Dummy.

## Drone

A small flying enemy that uses Superb Warfare's drone model, with a mortar shell hung under it exactly as on a player-flown drone, and its vehicle label (name, health bar and distance, shown while you look at it).

- Spawn with `superbenemies:drone` or its spawn egg (**Superb Enemies** creative tab; the egg is drawn as the drone itself, with Superb Warfare's drone model and texture). Sneak + right-click with empty hands picks it up again.
- **Targets:** chooses by two target weights, `playerWeight` (default 5) and `tankWeight` (default 0), sliders from 0 to 5 where 0 means never: the target with the lowest distance divided by weight within `detectionRange` wins (an already noticed target is followed up to `chaseRange`). `tankWeight` covers manned armoured vehicles and the tank bosses (unless crippled). A held target is only swapped for one that scores at least 30% better, so the drone does not flip between two. With `playerWeight` at 0 the drone never chases, blows up next to or reports a player.
- **Chases and detonates:** flies at its target. It carries a mortar shell and blows up (Superb Warfare's own explosion with `explosionDamage` and `explosionRadius`; vehicles take `tankExplosionDamage` instead) once it is within `detonateDistance` of the target's hitbox. The shell is safe for `armSeconds` after the drone appears, so a drone spawned next to you does not go off at once. Set `destroyBlocks` for a blast that breaks blocks. A block in the way makes it path around; with no target nearby it drifts around slowly.
- **Nimble but fragile:** 6 health and no armor by default, but it flies at 14 blocks per second and turns and brakes sharply (`agility`). With `evasive` on it weaves from side to side on the way in (the weave fades out on the final approach) and dashes away when hit.
- **Swarms fan out:** with `fanOut` on, each drone flies up to its target, strafes round it in a random direction and then dives in from a random side (left, right, front, back or above), preferring a side no other drone on that target has taken, and drones push each other apart, so a swarm does not arrive and go off as one cluster. With it off every drone flies straight at the target.
- **Engine buzz:** a drone plays Superb Warfare's drone engine loop, louder and higher pitched the faster it flies (`engineVolume`, 0 = silent).
- **Dodges bullets:** with `dodgeProjectiles` on it spots bullets, shells and rockets heading for it and dashes across their line of flight, with a `dodgeChance` of noticing in time. It cannot dodge hitscan or very close shots, and it dodges at most once every 6 ticks, so sustained fire still gets it.
- **Alerts the other drones:** while a drone sees its target (in range and, with `requireLineOfSight`, a clear line; glass and leaves do not block it) every other drone is told where it is for `alertSeconds`. A drone that is told chases that target from any distance and through cover, whatever its `detectionRange` and line of sight say, so one drone spotting you brings the whole swarm. Each drone's own weights still apply (a drone with `tankWeight` 0 does not chase a reported tank), and only a drone that sees the target itself passes the report on. `alertRadius` limits the report to drones near the one that saw it (0 = every drone in the dimension). A report that is already out runs out by itself, even if the drone dies or detonates. The bosses are not told.
- **Bosses and the drone:** the tank bosses (LAV-25, M1A2, Bradley, ZTZ-99A) shoot at drones with the weight `tankDroneWeight` (default 4, 0 = ignore). The A-10 and AC-130 never target them. A drone shot down blows apart in smoke without detonating its shell.

## Spawning

- Boss crates (A-10, AC-130, LAV-25, M1A2, M2 Bradley and ZTZ-99A; item ids still `*_boss_spawn_egg`) are available in the **Superb Enemies** creative tab. The tank crates are ground spawns: use them on the surface you want the tank on.
- Test commands:
  - `/summon superbenemies:a10_warthog_boss ~ ~60 ~`
  - `/summon superbenemies:ac130_angel_of_death ~ ~80 ~`
  - `/summon superbenemies:lav25_boss ~ ~ ~`
  - `/summon superbenemies:m1a2_boss ~ ~ ~`
  - `/summon superbenemies:bradley_boss ~ ~ ~`
  - `/summon superbenemies:ztz99a_boss ~ ~ ~`
- Bosses do not spawn naturally and refuse Peaceful, Nether and unsafe locations.

## Configuration

All options are live unless stated otherwise. Config files are available in `config/` and in **Mods > Superb Enemies > Config**.

Config files: `superbenemies-common.toml` (A-10), `superbenemies-ac130-common.toml`, `superbenemies-lav25-common.toml`, `superbenemies-m1a2-common.toml`, `superbenemies-bradley-common.toml`, `superbenemies-ztz99a-common.toml`, `superbenemies-dummy-common.toml`, `superbenemies-soldier-common.toml`, `superbenemies-drone-common.toml`, `superbenemies-debug-common.toml` and, client side, `superbenemies-player-client.toml`.

### Targeting

All six bosses share the same targeting options, each set independently per boss.

- **Target weights** (`playerWeight`, `mobWeight`, `hostileMobWeight`, `planeWeight`, `tankWeight`): sliders from 0 to 5. 0 means the boss never attacks that kind of target.
  - `mobWeight` covers non-hostile mobs such as animals, villagers and golems. `hostileMobWeight` covers monsters.
  - `planeWeight` is experimental. It targets manned airplanes, manned helicopters, airships and the plane bosses.
  - `tankWeight` targets manned tanks, APCs and anti-air vehicles and the tank bosses (LAV-25, M1A2, M2 Bradley and ZTZ-99A). It defaults to 0 for the A-10 and AC-130 (opt-in) and 4 for the tank bosses. A player riding a vehicle counts once, with the higher of the two weights.
  - Player dummies with their boss-targeting option on count as players.
  - With `randomTargeting` on, the weight is the relative chance of being drawn. With it off, the boss picks the best distance-to-weight score, so a weight 5 target counts as 5 times closer than a weight 1 target.
- **Targeting method** (`targetingMethod`):
  - `STATIC`: the boss always knows where its target is. Smoke does not hide anything from it.
  - `DYNAMIC` (default): a target with a cover block anywhere above its head is hidden unless the boss has a clear direct ray to it. The boss switches to an exposed target if one exists. Otherwise it keeps attacking the hidden target's last known position until the target steps back under open sky or becomes directly visible.
  - Smoke grenades block the line of sight and hide targets standing in smoke from DYNAMIC bosses (planes and tanks).
  - These blocks don't count as cover: air, water, plants and other blocks without collision, glass, glass panes, leaves, fences, fence gates, carpets, iron bars, chains and scaffolding.
  - The see-through list is the block tag `superbenemies:boss_see_through`, which datapacks can extend for other mods' blocks.
- The A-10 only commits to mobs that are under open sky, whichever method is selected.
- The tank bosses see by a clear line from the turret within `sightRange` instead of sky exposure, and hear noises within `hearingRange` (see [Tank bosses](#tank-bosses)).

### Teammates

Every boss has a Teammates table with one on/off option per boss type: `a10Warthog`, `ac130`, `lav25`, `m1a2`, `bradley` and `ztz99a` (shown with readable names in the config screen). In the config files the section is `a10_teammates`, `ac130_teammates`, `lav25_teammates` and so on. By default only the boss's own type is on, so two bosses of the same type never fight each other and different types do.

- A boss never targets a boss whose type is switched on in its own table, and it does not retaliate against it either, and noises from teammates do not make it investigate.
- The relation counts per boss: if the M1A2 lists the ZTZ-99A but the ZTZ-99A does not list the M1A2, the M1A2 spares it while the ZTZ-99A still shoots at the M1A2. Tanks only form up when both list each other. In the in-game config screen a teammate toggle changes both sides at once (switching the AC-130 on in the A-10's table also switches the A-10 on in the AC-130's table, and resetting one resets the other); to get a one-sided relation, edit the config files directly.
- A teammate is not immune: shells and explosions of other sources still hurt it, and the tanks only hold fire while a teammate is in the line of fire.
- The tank bosses also have `formationEnabled` (default true) and `formationSpacing` (default 14, 6-40 blocks between the tanks of a formation) in this section; see [Tank bosses](#tank-bosses).

### A-10

<table>
<tr><th>Option</th><th>Default</th><th>Description</th></tr>
<tr><td><code>maxHealth</code></td><td>2000</td><td>Boss health.</td></tr>
<tr><td><code>cruiseHeightAboveTarget</code></td><td>40</td><td>Flight height.</td></tr>
<tr><td><code>globalAggro</code></td><td>true</td><td>Dimension-wide player hunting.</td></tr>
<tr><td><code>aggroRange</code></td><td>256</td><td>Player range.</td></tr>
<tr><td><code>targetingMethod</code></td><td>DYNAMIC</td><td>Static or cover-aware targeting.</td></tr>
<tr><td><code>sightLossGraceSeconds</code></td><td>5</td><td>DYNAMIC: seconds the boss keeps tracking a target's real position after losing sight of it, before it attacks the last known spot (0-30, 0 = off). The last-known strike starts after it.</td></tr>
<tr><td><code>playerWeight</code>, <code>mobWeight</code>, <code>hostileMobWeight</code>, <code>planeWeight</code>, <code>tankWeight</code></td><td>5, 0, 0, 0, 0</td><td>Target weights (0-5).</td></tr>
<tr><td><code>mobScanRange</code></td><td>128</td><td>Mob range.</td></tr>
<tr><td><code>randomTargeting</code></td><td>true</td><td>New target per attack.</td></tr>
<tr><td><code>orbitRadius</code></td><td>90</td><td>Orbit distance.</td></tr>
<tr><td><code>*DamageMultiplier</code></td><td>1.0</td><td>Weapon damage.</td></tr>
<tr><td><code>*Enabled</code></td><td>true</td><td>Weapon availability.</td></tr>
<tr><td><code>*Chance</code></td><td>varies</td><td>Attack and flare weights, including the Mk 84 nuke run.</td></tr>
<tr><td><code>limitedAmmo</code></td><td>false</td><td>Finite ammunition.</td></tr>
<tr><td><code>cannonRounds</code>, <code>rockets</code>, <code>bombs</code>, <code>nukes</code>, <code>missiles</code></td><td>600, 56, 9, 1, 8</td><td>Weapon loads.</td></tr>
<tr><td><code>nukeEnabled</code>, <code>nukeRunChance</code></td><td>true, 5</td><td>Single Mk 84 nuke attack availability and weight.</td></tr>
<tr><td><code>flares</code></td><td>varies</td><td>Flare load.</td></tr>
<tr><td><code>resupplySeconds</code></td><td>40</td><td>Resupply delay.</td></tr>
<tr><td><code>idleResupplySeconds</code>, <code>idleResupplyChance</code></td><td>120, 25</td><td>Idle boss with a used load may leave to rearm (seconds 0-3600, 0 = off; percent chance every 10 s).</td></tr>
<tr><td><code>blockDamage</code></td><td>true</td><td>Explosion block damage.</td></tr>
<tr><td><code>defensiveFlares</code></td><td>false</td><td>Missile evasion.</td></tr>
<tr><td><code>flareMissChance</code></td><td>10</td><td>Threat miss chance.</td></tr>
<tr><td><code>ignoreBlockCollisions</code></td><td>false</td><td>Fly through blocks.</td></tr>
<tr><td><code>collisionDamage</code></td><td>true</td><td>Take crash damage from terrain and buildings (off: it still collides but is not hurt; a crippled plane always crashes).</td></tr>
<tr><td><code>unstuckTeleport</code>, <code>unstuckSeconds</code></td><td>true, 15</td><td>Teleport a plane that stayed within 6 blocks of one spot for that many seconds (5-300) up into open air, keeping its heading.</td></tr>
<tr><td><code>lastKnownStrikeSeconds</code></td><td>20</td><td>Seconds spent attacking the last known position of a lost target (0-300, 0 = off).</td></tr>
<tr><td><code>bossBarRange</code></td><td>300</td><td>Boss bar range.</td></tr>
<tr><td><code>xpReward</code></td><td>500</td><td>Defeat experience.</td></tr>
<tr><td><code>a10Warthog</code>, <code>ac130</code>, <code>lav25</code>, <code>m1a2</code>, <code>bradley</code>, <code>ztz99a</code></td><td>only the own type true</td><td>Teammates: boss types this boss never targets (see <a href="#teammates">Teammates</a>).</td></tr>
</table>

### AC-130

<table>
<tr><th>Option</th><th>Default</th><th>Description</th></tr>
<tr><td><code>maxHealth</code></td><td>3000</td><td>Boss health.</td></tr>
<tr><td><code>cruiseHeightAboveTarget</code></td><td>90</td><td>Orbit height.</td></tr>
<tr><td><code>globalAggro</code>, <code>aggroRange</code></td><td>true, 256</td><td>Player targeting.</td></tr>
<tr><td><code>orbitRadius</code></td><td>180</td><td>Standoff distance.</td></tr>
<tr><td><code>targetingMethod</code></td><td>DYNAMIC</td><td>Static or cover-aware targeting.</td></tr>
<tr><td><code>sightLossGraceSeconds</code></td><td>5</td><td>DYNAMIC: seconds the boss keeps tracking a target's real position after losing sight of it, before it fires on the last known spot (0-30, 0 = off).</td></tr>
<tr><td><code>playerWeight</code>, <code>mobWeight</code>, <code>hostileMobWeight</code>, <code>planeWeight</code>, <code>tankWeight</code></td><td>5, 2, 4, 0, 0</td><td>Target weights (0-5).</td></tr>
<tr><td><code>randomTargeting</code>, <code>randomTargetSeconds</code></td><td>true, 20</td><td>Target switching.</td></tr>
<tr><td><code>mobScanRange</code></td><td>128</td><td>Mob range.</td></tr>
<tr><td><code>*DamageMultiplier</code></td><td>1.0</td><td>Gun damage.</td></tr>
<tr><td><code>*Enabled</code></td><td>true</td><td>Gun availability.</td></tr>
<tr><td><code>splitFireChance</code>, <code>focusFireChance</code></td><td>20, 20</td><td>Special fire chance.</td></tr>
<tr><td><code>longBurstTargetSwitchChance</code></td><td>10</td><td>25mm target switching.</td></tr>
<tr><td><code>*Simultaneous</code></td><td>true</td><td>Fire guns together.</td></tr>
<tr><td><code>limitedAmmo</code></td><td>false</td><td>Finite ammunition.</td></tr>
<tr><td><code>*Rounds</code></td><td>1500, 120, 30</td><td>Gun loads.</td></tr>
<tr><td><code>flares</code>, <code>resupplySeconds</code></td><td>varies</td><td>Flares and delay.</td></tr>
<tr><td><code>idleResupplySeconds</code>, <code>idleResupplyChance</code></td><td>120, 25</td><td>Idle boss with a used load may leave to rearm (seconds 0-3600, 0 = off; percent chance every 10 s).</td></tr>
<tr><td><code>defensiveFlares</code>, <code>flareMissChance</code></td><td>false, 10</td><td>Missile evasion.</td></tr>
<tr><td><code>tauntEnabled</code></td><td>true</td><td>Harmless flare passes.</td></tr>
<tr><td><code>blockDamage</code>, <code>ignoreBlockCollisions</code></td><td>true, false</td><td>Block interaction.</td></tr>
<tr><td><code>collisionDamage</code></td><td>true</td><td>Take crash damage from terrain and buildings (off: it still collides but is not hurt; a crippled plane always crashes).</td></tr>
<tr><td><code>unstuckTeleport</code>, <code>unstuckSeconds</code></td><td>true, 15</td><td>Teleport a plane that stayed within 6 blocks of one spot for that many seconds (5-300) up into open air, keeping its heading.</td></tr>
<tr><td><code>bossBarRange</code>, <code>xpReward</code></td><td>300, 800</td><td>Bar range and XP.</td></tr>
<tr><td><code>a10Warthog</code>, <code>ac130</code>, <code>lav25</code>, <code>m1a2</code>, <code>bradley</code>, <code>ztz99a</code></td><td>only the own type true</td><td>Teammates: boss types this boss never targets (see <a href="#teammates">Teammates</a>).</td></tr>
</table>

### LAV-25

The damage multipliers, `blockDamage` and `maxHealth` are applied when the boss is first created; everything else is live.

<table>
<tr><th>Option</th><th>Default</th><th>Description</th></tr>
<tr><td><code>maxHealth</code></td><td>1500</td><td>Boss health (100-1000000). Applied when the boss is first created.</td></tr>
<tr><td><code>aggroRange</code></td><td>192</td><td>Target range in blocks (32-1024). Unused with globalAggro.</td></tr>
<tr><td><code>globalAggro</code></td><td>false</td><td>Dimension-wide player hunting. Off by default: tanks are slow and never load chunks.</td></tr>
<tr><td><code>targetingMethod</code></td><td>DYNAMIC</td><td>STATIC or DYNAMIC (line of sight, scouting, hearing).</td></tr>
<tr><td><code>playerWeight</code>, <code>mobWeight</code>, <code>hostileMobWeight</code>, <code>planeWeight</code>, <code>tankWeight</code></td><td>5, 0, 2, 3, 4</td><td>Target weights (0-5 each).</td></tr>
<tr><td><code>mobScanRange</code></td><td>96</td><td>Mob range in blocks (32-256).</td></tr>
<tr><td><code>randomTargeting</code>, <code>randomTargetSeconds</code></td><td>true, 20</td><td>Target switching; average seconds per target (5-300).</td></tr>
<tr><td><code>engageRange</code></td><td>60</td><td>Distance held from the target in blocks (15-300).</td></tr>
<tr><td><code>sightRange</code></td><td>160</td><td>DYNAMIC: how far the boss sees a target in blocks (32-512).</td></tr>
<tr><td><code>sightConeDegrees</code>, <code>sightConeRangeMultiplier</code></td><td>40, 1.5</td><td>DYNAMIC: width of the cone along the barrel (0-180, 0 = off) and how much farther the boss sees inside it (1-4).</td></tr>
<tr><td><code>hearingRange</code></td><td>128</td><td>DYNAMIC: how far loud noises carry in blocks (16-512).</td></tr>
<tr><td><code>cannonHearingPercent</code>, <code>machineGunHearingPercent</code></td><td>50, 50</td><td>DYNAMIC: how far the cannon and machine gun fire of other vehicles is heard, in percent of the weapon's Superb Warfare sound radius (16 blocks per point); the shooter is pinpointed and hunted (0-200, 0 = deaf).</td></tr>
<tr><td><code>hearingScatterPercent</code></td><td>5</td><td>DYNAMIC: how far off a heard shooter is placed, in percent of the distance (0-30, 0 = exact).</td></tr>
<tr><td><code>smokeScoutRange</code></td><td>192</td><td>DYNAMIC: a tank with no target goes to scout a smoke cloud it sees within this many blocks (0-512, 0 = ignores smoke).</td></tr>
<tr><td><code>searchSeconds</code></td><td>45</td><td>DYNAMIC: seconds spent sweeping the last seen area (5-600).</td></tr>
<tr><td><code>breakDetectionSeconds</code></td><td>8</td><td>DYNAMIC: seconds a target must stay out of sight before the boss loses it for good; a lost target is only detected again when it comes within <code>closeDetectionRange</code> or shoots the boss (0-600, 0 = never). Counts from the end of <code>sightLossGraceSeconds</code>.</td></tr>
<tr><td><code>sightLossGraceSeconds</code></td><td>5</td><td>DYNAMIC: seconds the tank keeps tracking a target's real position after losing sight of it (turret following, repositioning, no shots through walls) before it shells the last known spot and searches (0-30, 0 = off).</td></tr>
<tr><td><code>decisionCommitSeconds</code></td><td>2</td><td>How long a tank sticks with a fighting move (advance, snipe, circle, back off, reach a stand-off spot...) or an investigation before it may switch to another one; urgent events (target too close, rear blocked, new target, evade, pull-back) switch at once (0-10, 0 = off).</td></tr>
<tr><td><code>closeDetectionRange</code></td><td>12</td><td>DYNAMIC: a lost target this close in blocks is detected again at once, seen or not (2-64).</td></tr>
<tr><td><code>leashRange</code></td><td>256</td><td>How far from its home point it wanders when idle, in blocks (32-1024).</td></tr>
<tr><td><code>cannon25mmDamageMultiplier</code>, <code>coaxMgDamageMultiplier</code>, <code>wireMissileDamageMultiplier</code></td><td>1.0, 1.0, 1.0</td><td>Weapon damage (0.0-100.0). Applied when the boss is first created.</td></tr>
<tr><td><code>blockDamage</code></td><td>true</td><td>Shell and wreck explosion block damage. Applied when the boss is first created.</td></tr>
<tr><td><code>cannon25mmEnabled</code>, <code>coaxMgEnabled</code>, <code>wireMissileEnabled</code></td><td>true, true, true</td><td>Weapon availability.</td></tr>
<tr><td><code>cannonShell</code></td><td>AUTO</td><td>AUTO, AP or HE.</td></tr>
<tr><td><code>missileCooldownSeconds</code></td><td>8</td><td>Minimum seconds between wire-guided missiles (3-600).</td></tr>
<tr><td><code>limitedAmmo</code></td><td>false</td><td>Finite ammunition.</td></tr>
<tr><td><code>cannon25mmRounds</code>, <code>coaxMgRounds</code>, <code>wireMissileRounds</code></td><td>300, 800, 4</td><td>Weapon loads (1-100000, missiles 1-1000).</td></tr>
<tr><td><code>smokeCharges</code></td><td>6</td><td>Smoke salvos per load with limitedAmmo (1-1000).</td></tr>
<tr><td><code>resupplySeconds</code></td><td>40</td><td>Resupply delay (0-3600).</td></tr>
<tr><td><code>idleResupplySeconds</code>, <code>idleResupplyChance</code></td><td>120, 25</td><td>Patrolling without a target for this long (0-3600, 0 = off) with a used load may retreat to rearm; percent chance every 10 s (0-100).</td></tr>
<tr><td><code>a10Warthog</code>, <code>ac130</code>, <code>lav25</code>, <code>m1a2</code>, <code>bradley</code>, <code>ztz99a</code></td><td>only the own type true</td><td>Teammates: boss types this boss never targets (see <a href="#teammates">Teammates</a>).</td></tr>
<tr><td><code>formationEnabled</code>, <code>formationSpacing</code></td><td>true, 14</td><td>Move in formation with teammate tanks; blocks between the tanks of a formation (6-40).</td></tr>
<tr><td><code>formationHaltSeconds</code></td><td>4</td><td>Seconds a formation stands and fires on first contact before breaking up (0-30, 0 = off).</td></tr>
<tr><td><code>smokeEnabled</code></td><td>true</td><td>Smoke launchers on or off. Off means no smoke at all.</td></tr>
<tr><td><code>smokeWhenHitChance</code></td><td>25</td><td>Percent chance to pop smoke when hit (0-100).</td></tr>
<tr><td><code>smokeAgainstMissiles</code>, <code>smokeAgainstAir</code></td><td>true, true</td><td>Pop smoke against guided missiles and against aircraft the guns cannot reach.</td></tr>
<tr><td><code>shootDownMissiles</code>, <code>shootDownFailPercent</code></td><td>true, 35</td><td>The machine guns shoot at an incoming guided missile together with the smoke; percent of the engaged missiles the guns fail to stop (0-100).</td></tr>
<tr><td><code>flankingEnabled</code>, <code>flankChance</code></td><td>true, 30</td><td>Flanking manoeuvres; percent chance per roll, every 6-10 seconds of fighting (0-100).</td></tr>
<tr><td><code>returnFireEnabled</code>, <code>returnFireSeconds</code></td><td>true, 4</td><td>Return fire at the estimated position of an unseen shooter; seconds spent suppressing it (0-30, 0 = off).</td></tr>
<tr><td><code>useCover</code></td><td>true</td><td>Scan nearby structures and terrain for hull-down and hide spots (off = random spots, as before).</td></tr>
<tr><td><code>pullBackDamagePercent</code></td><td>10</td><td>Percent of the maximum health lost within 3 seconds that sends the tank to cover (0 = never pulls back).</td></tr>
<tr><td><code>wallBreaching</code></td><td>true</td><td>Shoot a hole through a thin, breakable wall on the straight line to the goal when the route round it is a long detour or there is none, then drive through (needs <code>blockDamage</code>; only with no target in sight).</td></tr>
${ROW}
<tr><td><code>crossWater</code></td><td>true</td><td>Whether the amphibious hull may swim across water.</td></tr>
<tr><td><code>bossBarRange</code></td><td>300</td><td>Boss bar range in blocks (32-1024).</td></tr>
<tr><td><code>xpReward</code></td><td>600</td><td>Defeat experience (0-100000).</td></tr>
</table>

### M1A2

Same rules as the LAV-25. There is no `crossWater` option and no missile.

<table>
<tr><th>Option</th><th>Default</th><th>Description</th></tr>
<tr><td><code>maxHealth</code></td><td>2500</td><td>Boss health (100-1000000). Applied when the boss is first created.</td></tr>
<tr><td><code>aggroRange</code></td><td>256</td><td>Target range in blocks (32-1024). Unused with globalAggro.</td></tr>
<tr><td><code>globalAggro</code></td><td>false</td><td>Dimension-wide player hunting. Off by default: tanks are slow and never load chunks.</td></tr>
<tr><td><code>targetingMethod</code></td><td>DYNAMIC</td><td>STATIC or DYNAMIC (line of sight, scouting, hearing).</td></tr>
<tr><td><code>playerWeight</code>, <code>mobWeight</code>, <code>hostileMobWeight</code>, <code>planeWeight</code>, <code>tankWeight</code></td><td>5, 0, 2, 3, 4</td><td>Target weights (0-5 each).</td></tr>
<tr><td><code>mobScanRange</code></td><td>96</td><td>Mob range in blocks (32-256).</td></tr>
<tr><td><code>randomTargeting</code>, <code>randomTargetSeconds</code></td><td>true, 20</td><td>Target switching; average seconds per target (5-300).</td></tr>
<tr><td><code>engageRange</code></td><td>110</td><td>Distance held from the target in blocks (15-300).</td></tr>
<tr><td><code>sightRange</code></td><td>256</td><td>DYNAMIC: how far the boss sees a target in blocks (32-512).</td></tr>
<tr><td><code>sightConeDegrees</code>, <code>sightConeRangeMultiplier</code></td><td>40, 1.5</td><td>DYNAMIC: width of the cone along the barrel (0-180, 0 = off) and how much farther the boss sees inside it (1-4).</td></tr>
<tr><td><code>hearingRange</code></td><td>128</td><td>DYNAMIC: how far loud noises carry in blocks (16-512).</td></tr>
<tr><td><code>cannonHearingPercent</code>, <code>machineGunHearingPercent</code></td><td>50, 50</td><td>DYNAMIC: how far the cannon and machine gun fire of other vehicles is heard, in percent of the weapon's Superb Warfare sound radius (16 blocks per point); the shooter is pinpointed and hunted (0-200, 0 = deaf).</td></tr>
<tr><td><code>hearingScatterPercent</code></td><td>5</td><td>DYNAMIC: how far off a heard shooter is placed, in percent of the distance (0-30, 0 = exact).</td></tr>
<tr><td><code>smokeScoutRange</code></td><td>192</td><td>DYNAMIC: a tank with no target goes to scout a smoke cloud it sees within this many blocks (0-512, 0 = ignores smoke).</td></tr>
<tr><td><code>searchSeconds</code></td><td>45</td><td>DYNAMIC: seconds spent sweeping the last seen area (5-600).</td></tr>
<tr><td><code>breakDetectionSeconds</code></td><td>8</td><td>DYNAMIC: seconds a target must stay out of sight before the boss loses it for good; a lost target is only detected again when it comes within <code>closeDetectionRange</code> or shoots the boss (0-600, 0 = never). Counts from the end of <code>sightLossGraceSeconds</code>.</td></tr>
<tr><td><code>sightLossGraceSeconds</code></td><td>5</td><td>DYNAMIC: seconds the tank keeps tracking a target's real position after losing sight of it (turret following, repositioning, no shots through walls) before it shells the last known spot and searches (0-30, 0 = off).</td></tr>
<tr><td><code>decisionCommitSeconds</code></td><td>2</td><td>How long a tank sticks with a fighting move (advance, snipe, circle, back off, reach a stand-off spot...) or an investigation before it may switch to another one; urgent events (target too close, rear blocked, new target, evade, pull-back) switch at once (0-10, 0 = off).</td></tr>
<tr><td><code>closeDetectionRange</code></td><td>12</td><td>DYNAMIC: a lost target this close in blocks is detected again at once, seen or not (2-64).</td></tr>
<tr><td><code>leashRange</code></td><td>256</td><td>How far from its home point it wanders when idle, in blocks (32-1024).</td></tr>
<tr><td><code>cannon120mmDamageMultiplier</code>, <code>coaxMgDamageMultiplier</code>, <code>cal50DamageMultiplier</code></td><td>1.0, 1.0, 1.0</td><td>Weapon damage (0.0-100.0). Applied when the boss is first created.</td></tr>
<tr><td><code>blockDamage</code></td><td>true</td><td>Shell and wreck explosion block damage. Applied when the boss is first created.</td></tr>
<tr><td><code>cannon120mmEnabled</code>, <code>coaxMgEnabled</code>, <code>cal50Enabled</code></td><td>true, true, true</td><td>Weapon availability.</td></tr>
<tr><td><code>cannonShell</code></td><td>AUTO</td><td>AUTO, AP or HE.</td></tr>
<tr><td><code>limitedAmmo</code></td><td>false</td><td>Finite ammunition.</td></tr>
<tr><td><code>cannon120mmRounds</code>, <code>coaxMgRounds</code>, <code>cal50Rounds</code></td><td>30, 800, 500</td><td>Weapon loads (1-100000).</td></tr>
<tr><td><code>smokeCharges</code></td><td>6</td><td>Smoke salvos per load with limitedAmmo (1-1000).</td></tr>
<tr><td><code>resupplySeconds</code></td><td>40</td><td>Resupply delay (0-3600).</td></tr>
<tr><td><code>idleResupplySeconds</code>, <code>idleResupplyChance</code></td><td>120, 25</td><td>Patrolling without a target for this long (0-3600, 0 = off) with a used load may retreat to rearm; percent chance every 10 s (0-100).</td></tr>
<tr><td><code>a10Warthog</code>, <code>ac130</code>, <code>lav25</code>, <code>m1a2</code>, <code>bradley</code>, <code>ztz99a</code></td><td>only the own type true</td><td>Teammates: boss types this boss never targets (see <a href="#teammates">Teammates</a>).</td></tr>
<tr><td><code>formationEnabled</code>, <code>formationSpacing</code></td><td>true, 14</td><td>Move in formation with teammate tanks; blocks between the tanks of a formation (6-40).</td></tr>
<tr><td><code>formationHaltSeconds</code></td><td>4</td><td>Seconds a formation stands and fires on first contact before breaking up (0-30, 0 = off).</td></tr>
<tr><td><code>smokeEnabled</code></td><td>true</td><td>Smoke launchers on or off. Off means no smoke at all.</td></tr>
<tr><td><code>smokeWhenHitChance</code></td><td>25</td><td>Percent chance to pop smoke when hit (0-100).</td></tr>
<tr><td><code>smokeAgainstMissiles</code>, <code>smokeAgainstAir</code></td><td>true, true</td><td>Pop smoke against guided missiles and against aircraft the guns cannot reach.</td></tr>
<tr><td><code>shootDownMissiles</code>, <code>shootDownFailPercent</code></td><td>true, 35</td><td>The machine guns shoot at an incoming guided missile together with the smoke; percent of the engaged missiles the guns fail to stop (0-100).</td></tr>
<tr><td><code>flankingEnabled</code>, <code>flankChance</code></td><td>true, 30</td><td>Flanking manoeuvres; percent chance per roll, every 6-10 seconds of fighting (0-100).</td></tr>
<tr><td><code>returnFireEnabled</code>, <code>returnFireSeconds</code></td><td>true, 4</td><td>Return fire at the estimated position of an unseen shooter; seconds spent suppressing it (0-30, 0 = off).</td></tr>
<tr><td><code>useCover</code></td><td>true</td><td>Peek from cover, fire one main gun round and fall back into cover while the gun reloads (off = shoot and scoot from a firing spot).</td></tr>
<tr><td><code>pullBackDamagePercent</code></td><td>10</td><td>Percent of the maximum health lost within 3 seconds that sends the tank to cover (0 = never pulls back).</td></tr>
<tr><td><code>wallBreaching</code></td><td>true</td><td>Shoot a hole through a thin, breakable wall on the straight line to the goal when the route round it is a long detour or there is none, then drive through (needs <code>blockDamage</code>; only with no target in sight).</td></tr>
${ROW}
<tr><td><code>bossBarRange</code></td><td>300</td><td>Boss bar range in blocks (32-1024).</td></tr>
<tr><td><code>xpReward</code></td><td>900</td><td>Defeat experience (0-100000).</td></tr>
</table>

### M2 Bradley

Same rules and options as the LAV-25 (including `crossWater` and the missile); only the defaults below differ. The cannon engages out to 170 blocks and the missile to 200 (fixed, not options), and the gun elevates to 60 degrees.

<table>
<tr><th>Option</th><th>Default</th><th>Description</th></tr>
<tr><td><code>maxHealth</code></td><td>2200</td><td>Boss health (100-1000000). Applied when the boss is first created.</td></tr>
<tr><td><code>aggroRange</code></td><td>192</td><td>Target range in blocks (32-1024). Unused with globalAggro.</td></tr>
<tr><td><code>globalAggro</code></td><td>false</td><td>Dimension-wide player hunting. Off by default: tanks are slow and never load chunks.</td></tr>
<tr><td><code>targetingMethod</code></td><td>DYNAMIC</td><td>STATIC or DYNAMIC (line of sight, scouting, hearing).</td></tr>
<tr><td><code>playerWeight</code>, <code>mobWeight</code>, <code>hostileMobWeight</code>, <code>planeWeight</code>, <code>tankWeight</code></td><td>5, 0, 2, 3, 4</td><td>Target weights (0-5 each). Aircraft are only engaged inside the 60 degree elevation of the gun.</td></tr>
<tr><td><code>mobScanRange</code></td><td>96</td><td>Mob range in blocks (32-256).</td></tr>
<tr><td><code>randomTargeting</code>, <code>randomTargetSeconds</code></td><td>true, 20</td><td>Target switching; average seconds per target (5-300).</td></tr>
<tr><td><code>engageRange</code></td><td>70</td><td>Distance held from the target in blocks (15-300).</td></tr>
<tr><td><code>sightRange</code></td><td>160</td><td>DYNAMIC: how far the boss sees a target in blocks (32-512).</td></tr>
<tr><td><code>sightConeDegrees</code>, <code>sightConeRangeMultiplier</code></td><td>40, 1.5</td><td>DYNAMIC: width of the cone along the barrel (0-180, 0 = off) and how much farther the boss sees inside it (1-4).</td></tr>
<tr><td><code>hearingRange</code></td><td>128</td><td>DYNAMIC: how far loud noises carry in blocks (16-512).</td></tr>
<tr><td><code>cannonHearingPercent</code>, <code>machineGunHearingPercent</code></td><td>50, 50</td><td>DYNAMIC: how far the cannon and machine gun fire of other vehicles is heard, in percent of the weapon's Superb Warfare sound radius (16 blocks per point); the shooter is pinpointed and hunted (0-200, 0 = deaf).</td></tr>
<tr><td><code>hearingScatterPercent</code></td><td>5</td><td>DYNAMIC: how far off a heard shooter is placed, in percent of the distance (0-30, 0 = exact).</td></tr>
<tr><td><code>smokeScoutRange</code></td><td>192</td><td>DYNAMIC: a tank with no target goes to scout a smoke cloud it sees within this many blocks (0-512, 0 = ignores smoke).</td></tr>
<tr><td><code>searchSeconds</code></td><td>45</td><td>DYNAMIC: seconds spent sweeping the last seen area (5-600).</td></tr>
<tr><td><code>breakDetectionSeconds</code></td><td>8</td><td>DYNAMIC: seconds a target must stay out of sight before the boss loses it for good; a lost target is only detected again when it comes within <code>closeDetectionRange</code> or shoots the boss (0-600, 0 = never). Counts from the end of <code>sightLossGraceSeconds</code>.</td></tr>
<tr><td><code>sightLossGraceSeconds</code></td><td>5</td><td>DYNAMIC: seconds the tank keeps tracking a target's real position after losing sight of it (turret following, repositioning, no shots through walls) before it shells the last known spot and searches (0-30, 0 = off).</td></tr>
<tr><td><code>decisionCommitSeconds</code></td><td>2</td><td>How long a tank sticks with a fighting move (advance, snipe, circle, back off, reach a stand-off spot...) or an investigation before it may switch to another one; urgent events (target too close, rear blocked, new target, evade, pull-back) switch at once (0-10, 0 = off).</td></tr>
<tr><td><code>closeDetectionRange</code></td><td>12</td><td>DYNAMIC: a lost target this close in blocks is detected again at once, seen or not (2-64).</td></tr>
<tr><td><code>leashRange</code></td><td>256</td><td>How far from its home point it wanders when idle, in blocks (32-1024).</td></tr>
<tr><td><code>cannon25mmDamageMultiplier</code>, <code>coaxMgDamageMultiplier</code>, <code>wireMissileDamageMultiplier</code></td><td>1.0, 1.0, 1.0</td><td>Weapon damage (0.0-100.0). Applied when the boss is first created.</td></tr>
<tr><td><code>blockDamage</code></td><td>true</td><td>Shell and wreck explosion block damage. Applied when the boss is first created.</td></tr>
<tr><td><code>cannon25mmEnabled</code>, <code>coaxMgEnabled</code>, <code>wireMissileEnabled</code></td><td>true, true, true</td><td>Weapon availability.</td></tr>
<tr><td><code>cannonShell</code></td><td>AUTO</td><td>AUTO, AP or HE.</td></tr>
<tr><td><code>missileCooldownSeconds</code></td><td>6</td><td>Minimum seconds between wire-guided missiles (3-600).</td></tr>
<tr><td><code>limitedAmmo</code></td><td>false</td><td>Finite ammunition.</td></tr>
<tr><td><code>cannon25mmRounds</code>, <code>coaxMgRounds</code>, <code>wireMissileRounds</code></td><td>400, 800, 6</td><td>Weapon loads (1-100000, missiles 1-1000).</td></tr>
<tr><td><code>smokeCharges</code></td><td>6</td><td>Smoke salvos per load with limitedAmmo (1-1000).</td></tr>
<tr><td><code>resupplySeconds</code></td><td>40</td><td>Resupply delay (0-3600).</td></tr>
<tr><td><code>idleResupplySeconds</code>, <code>idleResupplyChance</code></td><td>120, 25</td><td>Patrolling without a target for this long (0-3600, 0 = off) with a used load may retreat to rearm; percent chance every 10 s (0-100).</td></tr>
<tr><td><code>a10Warthog</code>, <code>ac130</code>, <code>lav25</code>, <code>m1a2</code>, <code>bradley</code>, <code>ztz99a</code></td><td>only the own type true</td><td>Teammates: boss types this boss never targets (see <a href="#teammates">Teammates</a>).</td></tr>
<tr><td><code>formationEnabled</code>, <code>formationSpacing</code></td><td>true, 14</td><td>Move in formation with teammate tanks; blocks between the tanks of a formation (6-40).</td></tr>
<tr><td><code>formationHaltSeconds</code></td><td>4</td><td>Seconds a formation stands and fires on first contact before breaking up (0-30, 0 = off).</td></tr>
<tr><td><code>smokeEnabled</code></td><td>true</td><td>Smoke launchers on or off. Off means no smoke at all.</td></tr>
<tr><td><code>smokeWhenHitChance</code></td><td>25</td><td>Percent chance to pop smoke when hit (0-100).</td></tr>
<tr><td><code>smokeAgainstMissiles</code>, <code>smokeAgainstAir</code></td><td>true, true</td><td>Pop smoke against guided missiles and against aircraft the guns cannot reach.</td></tr>
<tr><td><code>shootDownMissiles</code>, <code>shootDownFailPercent</code></td><td>true, 35</td><td>The machine guns shoot at an incoming guided missile together with the smoke; percent of the engaged missiles the guns fail to stop (0-100).</td></tr>
<tr><td><code>flankingEnabled</code>, <code>flankChance</code></td><td>true, 30</td><td>Flanking manoeuvres; percent chance per roll, every 6-10 seconds of fighting (0-100).</td></tr>
<tr><td><code>returnFireEnabled</code>, <code>returnFireSeconds</code></td><td>true, 4</td><td>Return fire at the estimated position of an unseen shooter; seconds spent suppressing it (0-30, 0 = off).</td></tr>
<tr><td><code>useCover</code></td><td>true</td><td>Scan nearby structures and terrain for hull-down and hide spots (off = random spots, as before).</td></tr>
<tr><td><code>pullBackDamagePercent</code></td><td>10</td><td>Percent of the maximum health lost within 3 seconds that sends the tank to cover (0 = never pulls back).</td></tr>
<tr><td><code>wallBreaching</code></td><td>true</td><td>Shoot a hole through a thin, breakable wall on the straight line to the goal when the route round it is a long detour or there is none, then drive through (needs <code>blockDamage</code>; only with no target in sight).</td></tr>
${ROW}
<tr><td><code>crossWater</code></td><td>true</td><td>Whether the amphibious hull may swim across water.</td></tr>
<tr><td><code>bossBarRange</code></td><td>300</td><td>Boss bar range in blocks (32-1024).</td></tr>
<tr><td><code>xpReward</code></td><td>800</td><td>Defeat experience (0-100000).</td></tr>
</table>

### ZTZ-99A

Same rules, options and defaults as the M1A2 (the config file is `superbenemies-ztz99a-common.toml`). There is no `crossWater` option and no missile.

<table>
<tr><th>Option</th><th>Default</th><th>Description</th></tr>
<tr><td><code>maxHealth</code></td><td>2500</td><td>Boss health (100-1000000). Applied when the boss is first created.</td></tr>
<tr><td><code>aggroRange</code></td><td>256</td><td>Target range in blocks (32-1024). Unused with globalAggro.</td></tr>
<tr><td><code>globalAggro</code></td><td>false</td><td>Dimension-wide player hunting. Off by default: tanks are slow and never load chunks.</td></tr>
<tr><td><code>targetingMethod</code></td><td>DYNAMIC</td><td>STATIC or DYNAMIC (line of sight, scouting, hearing).</td></tr>
<tr><td><code>playerWeight</code>, <code>mobWeight</code>, <code>hostileMobWeight</code>, <code>planeWeight</code>, <code>tankWeight</code></td><td>5, 0, 2, 3, 4</td><td>Target weights (0-5 each).</td></tr>
<tr><td><code>mobScanRange</code></td><td>96</td><td>Mob range in blocks (32-256).</td></tr>
<tr><td><code>randomTargeting</code>, <code>randomTargetSeconds</code></td><td>true, 20</td><td>Target switching; average seconds per target (5-300).</td></tr>
<tr><td><code>engageRange</code></td><td>110</td><td>Distance held from the target in blocks (15-300).</td></tr>
<tr><td><code>sightRange</code></td><td>256</td><td>DYNAMIC: how far the boss sees a target in blocks (32-512).</td></tr>
<tr><td><code>sightConeDegrees</code>, <code>sightConeRangeMultiplier</code></td><td>40, 1.5</td><td>DYNAMIC: width of the cone along the barrel (0-180, 0 = off) and how much farther the boss sees inside it (1-4).</td></tr>
<tr><td><code>hearingRange</code></td><td>128</td><td>DYNAMIC: how far loud noises carry in blocks (16-512).</td></tr>
<tr><td><code>cannonHearingPercent</code>, <code>machineGunHearingPercent</code></td><td>50, 50</td><td>DYNAMIC: how far the cannon and machine gun fire of other vehicles is heard, in percent of the weapon's Superb Warfare sound radius (16 blocks per point); the shooter is pinpointed and hunted (0-200, 0 = deaf).</td></tr>
<tr><td><code>hearingScatterPercent</code></td><td>5</td><td>DYNAMIC: how far off a heard shooter is placed, in percent of the distance (0-30, 0 = exact).</td></tr>
<tr><td><code>smokeScoutRange</code></td><td>192</td><td>DYNAMIC: a tank with no target goes to scout a smoke cloud it sees within this many blocks (0-512, 0 = ignores smoke).</td></tr>
<tr><td><code>searchSeconds</code></td><td>45</td><td>DYNAMIC: seconds spent sweeping the last seen area (5-600).</td></tr>
<tr><td><code>breakDetectionSeconds</code></td><td>8</td><td>DYNAMIC: seconds a target must stay out of sight before the boss loses it for good; a lost target is only detected again when it comes within <code>closeDetectionRange</code> or shoots the boss (0-600, 0 = never). Counts from the end of <code>sightLossGraceSeconds</code>.</td></tr>
<tr><td><code>sightLossGraceSeconds</code></td><td>5</td><td>DYNAMIC: seconds the tank keeps tracking a target's real position after losing sight of it (turret following, repositioning, no shots through walls) before it shells the last known spot and searches (0-30, 0 = off).</td></tr>
<tr><td><code>decisionCommitSeconds</code></td><td>2</td><td>How long a tank sticks with a fighting move (advance, snipe, circle, back off, reach a stand-off spot...) or an investigation before it may switch to another one; urgent events (target too close, rear blocked, new target, evade, pull-back) switch at once (0-10, 0 = off).</td></tr>
<tr><td><code>closeDetectionRange</code></td><td>12</td><td>DYNAMIC: a lost target this close in blocks is detected again at once, seen or not (2-64).</td></tr>
<tr><td><code>leashRange</code></td><td>256</td><td>How far from its home point it wanders when idle, in blocks (32-1024).</td></tr>
<tr><td><code>cannon125mmDamageMultiplier</code>, <code>coaxMgDamageMultiplier</code>, <code>cal50DamageMultiplier</code></td><td>1.0, 1.0, 1.0</td><td>Weapon damage (0.0-100.0). Applied when the boss is first created.</td></tr>
<tr><td><code>blockDamage</code></td><td>true</td><td>Shell and wreck explosion block damage. Applied when the boss is first created.</td></tr>
<tr><td><code>cannon125mmEnabled</code>, <code>coaxMgEnabled</code>, <code>cal50Enabled</code></td><td>true, true, true</td><td>Weapon availability.</td></tr>
<tr><td><code>cannonShell</code></td><td>AUTO</td><td>AUTO, AP or HE.</td></tr>
<tr><td><code>limitedAmmo</code></td><td>false</td><td>Finite ammunition.</td></tr>
<tr><td><code>cannon125mmRounds</code>, <code>coaxMgRounds</code>, <code>cal50Rounds</code></td><td>30, 800, 500</td><td>Weapon loads (1-100000).</td></tr>
<tr><td><code>smokeCharges</code></td><td>6</td><td>Smoke salvos per load with limitedAmmo (1-1000).</td></tr>
<tr><td><code>resupplySeconds</code></td><td>40</td><td>Resupply delay (0-3600).</td></tr>
<tr><td><code>idleResupplySeconds</code>, <code>idleResupplyChance</code></td><td>120, 25</td><td>Patrolling without a target for this long (0-3600, 0 = off) with a used load may retreat to rearm; percent chance every 10 s (0-100).</td></tr>
<tr><td><code>a10Warthog</code>, <code>ac130</code>, <code>lav25</code>, <code>m1a2</code>, <code>bradley</code>, <code>ztz99a</code></td><td>only the own type true</td><td>Teammates: boss types this boss never targets (see <a href="#teammates">Teammates</a>).</td></tr>
<tr><td><code>formationEnabled</code>, <code>formationSpacing</code></td><td>true, 14</td><td>Move in formation with teammate tanks; blocks between the tanks of a formation (6-40).</td></tr>
<tr><td><code>formationHaltSeconds</code></td><td>4</td><td>Seconds a formation stands and fires on first contact before breaking up (0-30, 0 = off).</td></tr>
<tr><td><code>smokeEnabled</code></td><td>true</td><td>Smoke launchers on or off. Off means no smoke at all.</td></tr>
<tr><td><code>smokeWhenHitChance</code></td><td>25</td><td>Percent chance to pop smoke when hit (0-100).</td></tr>
<tr><td><code>smokeAgainstMissiles</code>, <code>smokeAgainstAir</code></td><td>true, true</td><td>Pop smoke against guided missiles and against aircraft the guns cannot reach.</td></tr>
<tr><td><code>shootDownMissiles</code>, <code>shootDownFailPercent</code></td><td>true, 35</td><td>The machine guns shoot at an incoming guided missile together with the smoke; percent of the engaged missiles the guns fail to stop (0-100).</td></tr>
<tr><td><code>flankingEnabled</code>, <code>flankChance</code></td><td>true, 30</td><td>Flanking manoeuvres; percent chance per roll, every 6-10 seconds of fighting (0-100).</td></tr>
<tr><td><code>returnFireEnabled</code>, <code>returnFireSeconds</code></td><td>true, 4</td><td>Return fire at the estimated position of an unseen shooter; seconds spent suppressing it (0-30, 0 = off).</td></tr>
<tr><td><code>useCover</code></td><td>true</td><td>Peek from cover, fire one main gun round and fall back into cover while the gun reloads (off = shoot and scoot from a firing spot).</td></tr>
<tr><td><code>pullBackDamagePercent</code></td><td>10</td><td>Percent of the maximum health lost within 3 seconds that sends the tank to cover (0 = never pulls back).</td></tr>
<tr><td><code>wallBreaching</code></td><td>true</td><td>Shoot a hole through a thin, breakable wall on the straight line to the goal when the route round it is a long detour or there is none, then drive through (needs <code>blockDamage</code>; only with no target in sight).</td></tr>
${ROW}
<tr><td><code>bossBarRange</code></td><td>300</td><td>Boss bar range in blocks (32-1024).</td></tr>
<tr><td><code>xpReward</code></td><td>900</td><td>Defeat experience (0-100000).</td></tr>
</table>

### Player Dummy

<table>
<tr><th>Option</th><th>Default</th><th>Description</th></tr>
<tr><td><code>maxHealth</code></td><td>20</td><td>Dummy health.</td></tr>
<tr><td><code>invulnerable</code></td><td>false</td><td>Ignore damage.</td></tr>
<tr><td><code>showHealthTag</code>, <code>showDeathMessage</code></td><td>true, true</td><td>Status display.</td></tr>
<tr><td><code>showCounter</code></td><td>false</td><td>Hotbar counter of loaded player dummies.</td></tr>
<tr><td><code>bossesTargetDummy</code></td><td>true</td><td>Boss targeting.</td></tr>
<tr><td><code>movement</code></td><td>STRAFE</td><td>STRAFE, ROAM or STATIC.</td></tr>
<tr><td><code>strafeRadius</code>, <code>roamDistance</code></td><td>6, 12</td><td>Movement distance.</td></tr>
<tr><td><code>roamPauseSeconds</code></td><td>2</td><td>Roam pause.</td></tr>
<tr><td><code>jumping</code>, <code>sprinting</code>, <code>crouching</code></td><td>true</td><td>Movement actions.</td></tr>
<tr><td><code>hideFromBosses</code></td><td>false</td><td>Seek cover.</td></tr>
</table>

### Dummy Soldier

<table>
<tr><th>Option</th><th>Default</th><th>Description</th></tr>
<tr><td><code>maxHealth</code>, <code>armor</code>, <code>armorToughness</code></td><td>20, 10, 2</td><td>Defensive stats.</td></tr>
<tr><td><code>invulnerable</code></td><td>false</td><td>Ignore damage.</td></tr>
<tr><td><code>showHealthTag</code>, <code>showDeathMessage</code></td><td>true, true</td><td>Status display.</td></tr>
<tr><td><code>showCounter</code></td><td>false</td><td>Hotbar counter of loaded dummy soldiers.</td></tr>
<tr><td><code>bossesTargetSoldier</code></td><td>true</td><td>Boss targeting.</td></tr>
<tr><td><code>unlimitedAmmo</code></td><td>false</td><td>Ignore missile reserves.</td></tr>
<tr><td><code>missiles</code>, <code>reloadTicks</code></td><td>4, 88</td><td>Load and reload.</td></tr>
<tr><td><code>resupplySeconds</code></td><td>60</td><td>Fresh load delay.</td></tr>
<tr><td><code>missileDamage</code>, <code>explosionDamage</code>, <code>explosionRadius</code></td><td>260, 90, 6</td><td>Missile damage.</td></tr>
<tr><td><code>movement</code></td><td>STATIC</td><td>STRAFE, ROAM or STATIC.</td></tr>
<tr><td><code>engageRange</code>, <code>minEngageRange</code></td><td>300, 24</td><td>Engagement range.</td></tr>
<tr><td><code>lockTimeTicks</code>, <code>aimTurnSpeed</code>, <code>reactionTicks</code></td><td>32, 12, 10</td><td>Aiming response.</td></tr>
<tr><td><code>holdFireOnFlares</code></td><td>true</td><td>Wait for flares.</td></tr>
<tr><td><code>staggerShots</code></td><td>true</td><td>Coordinate launches.</td></tr>
<tr><td><code>coverAfterHit</code>, <code>coverAfterMiss</code></td><td>true, false</td><td>Seek cover after firing.</td></tr>
<tr><td><code>coverSeconds</code>, <code>hideWhenOutOfAmmo</code></td><td>15, true</td><td>Cover and ammo behavior.</td></tr>
</table>

### Drone

<table>
<tr><th>Option</th><th>Default</th><th>Description</th></tr>
<tr><td><code>maxHealth</code>, <code>armor</code>, <code>armorToughness</code></td><td>6, 0, 0</td><td>Defensive stats.</td></tr>
<tr><td><code>invulnerable</code></td><td>false</td><td>Ignore damage (it still flashes and dashes).</td></tr>
<tr><td><code>showLabel</code>, <code>showDeathMessage</code></td><td>true, false</td><td>Superb Warfare vehicle label above the drone you look at; kill message in chat.</td></tr>
<tr><td><code>showCounter</code></td><td>false</td><td>Hotbar counter of loaded drones.</td></tr>
<tr><td><code>flySpeed</code></td><td>14.0</td><td>Top speed in blocks per second (1-40).</td></tr>
<tr><td><code>engineVolume</code></td><td>100</td><td>Drone engine buzz volume in percent (0-200, 0 = silent).</td></tr>
<tr><td><code>agility</code></td><td>1.0</td><td>Acceleration and turning multiplier (0.1-4).</td></tr>
<tr><td><code>fanOut</code></td><td>true</td><td>Strafe round the target and dive in from a random side (left, right, front, back, above) instead of flying straight at it.</td></tr>
<tr><td><code>evasive</code>, <code>jinkSeconds</code></td><td>true, 2</td><td>Weave on the way in and dash when hit; average seconds between weave changes.</td></tr>
<tr><td><code>dodgeProjectiles</code>, <code>dodgeChance</code></td><td>true, 70</td><td>Sidestep incoming shots; percent chance to notice one in time (0-100).</td></tr>
<tr><td><code>explosionDamage</code>, <code>explosionRadius</code></td><td>60, 5</td><td>The mortar shell's blast (Superb Warfare's own shell: 100, 8).</td></tr>
<tr><td><code>tankExplosionDamage</code></td><td>60</td><td>Damage of the same blast against vehicles (tanks, tank bosses), with the same falloff by distance. <code>explosionDamage</code> still applies to everything else.</td></tr>
<tr><td><code>detonateDistance</code>, <code>armSeconds</code></td><td>3.0, 2</td><td>How close to the target's hitbox it blows up; seconds the shell stays safe after spawning.</td></tr>
<tr><td><code>destroyBlocks</code></td><td>false</td><td>The blast breaks blocks (also needs Superb Warfare's explosion block damage on).</td></tr>
<tr><td><code>playerWeight</code>, <code>tankWeight</code></td><td>5, 0</td><td>What the drone attacks (0-5, 0 = never): players, and manned tanks plus the tank bosses.</td></tr>
<tr><td><code>tankDroneWeight</code></td><td>4</td><td>How much the tank bosses want to shoot drones (0-5, 0 = ignore).</td></tr>
<tr><td><code>detectionRange</code></td><td>64</td><td>Distance at which a new target is noticed (8-256).</td></tr>
<tr><td><code>chaseRange</code></td><td>96</td><td>Distance a noticed target is followed before the drone gives up (8-512, never less than <code>detectionRange</code>).</td></tr>
<tr><td><code>chaseCreative</code></td><td>true</td><td>Also chase (and blow up next to) creative players; spectators never.</td></tr>
<tr><td><code>requireLineOfSight</code></td><td>true</td><td>Only notice and report a target it can see.</td></tr>
<tr><td><code>alertDrones</code></td><td>true</td><td>Tell the other drones where the target is.</td></tr>
<tr><td><code>alertRadius</code>, <code>alertSeconds</code></td><td>0, 15</td><td>Drones within this many blocks of the drone that saw the target are told (0 = all); how long the report lasts after the drone last saw the target.</td></tr>
</table>

### Player

Client config (`superbenemies-player-client.toml`); it only changes your own screen. On the config screen it is the **Player** category.

<table>
<tr><th>Option</th><th>Default</th><th>Description</th></tr>
<tr><td><code>showSpottedIcon</code></td><td>true</td><td>Show a ring around the middle of the view, with an arc and an eye pivoting toward every tank boss that has you in sight as its target (more opaque the closer it is).</td></tr>
</table>

### Debug

Config file `superbenemies-debug-common.toml`; on the config screen it is the **Debug > AI Thinking** page. It replaces the old per-boss `debugLogging` options (their messages now go through it). The output goes to the server console and `logs/latest.log`, so on a dedicated server set it in the server's file.

<table>
<tr><th>Option</th><th>Default</th><th>Description</th></tr>
<tr><td><code>aiThinking</code></td><td>false</td><td>Print what the AI is thinking: state changes with their reason, decisions (attack picked, target chosen, holding fire and why), shots, routes and behaviour phases.</td></tr>
<tr><td><code>heartbeatSeconds</code></td><td>5</td><td>Every this many seconds each AI prints a status snapshot (state, target, distance, health, ammo, position). 0 = off.</td></tr>
<tr><td><code>showOnScreen</code></td><td>true</td><td>Also show the thinking lines in a small box on the screen (top left by default, like the subtitles) for players near the AI: the last 8 lines, each fading out after 8 seconds. Hidden with F1 and while the F3 debug screen is open. Heartbeat lines stay in the console.</td></tr>
<tr><td><code>screenRange</code></td><td>128</td><td>Players within this many blocks of an AI (same dimension) get its lines on screen.</td></tr>
<tr><td><code>screenX</code>, <code>screenY</code></td><td>0, 0</td><td>Sliders (0-100 %) that place the log box anywhere on the screen: 0 = left / top, 100 = right / bottom. The box always stays fully on screen. Client side: each player's own config.</td></tr>
<tr><td><code>screenScale</code></td><td>75</td><td>Text size of the log box in percent (25-300).</td></tr>
<tr><td><code>screenMaxWidth</code></td><td>340</td><td>Widest the log box may get, in screen pixels (100-1000); longer lines are cut with an ellipsis.</td></tr>
<tr><td><code>showVision</code></td><td>true</td><td>Draw each boss's sight in the world for players within <code>screenRange</code>: a line to what it aims at (green = seen, yellow = tracking during <code>sightLossGraceSeconds</code>, red with a cross = last known spot), a label naming the target and its category, and the tanks' sight cone. Also prints <code>vision</code> lines to the log. Hidden with F1.</td></tr>
<tr><td><code>thinkA10</code>, <code>thinkAc130</code>, <code>thinkLav25</code>, <code>thinkM1a2</code>, <code>thinkBradley</code>, <code>thinkZtz99a</code>, <code>thinkDrone</code>, <code>thinkDummySoldier</code>, <code>thinkAtSoldier</code>, <code>thinkPlayerDummy</code></td><td>true</td><td>Which AI types print, so one boss can be watched alone. Greyed out while <code>aiThinking</code> is off.</td></tr>
</table>

Lines look like `[AI][M1A2 #412|ENGAGE] tickEngage: backing off: target too close`: the AI type, its entity id, its current state (or running goal), the function and the thought. A function that runs every tick prints only when its thought changes, so the log stays readable with several bosses at once; one-off events (state changes, shots, routes found) always print.

## Dependencies

- Minecraft 1.20.1.
- Forge 47.2.0 or newer 47.x.
- Kotlin for Forge 4.11.0 or newer.
- Superb Warfare 0.8.x at runtime.

## Building

1. Put `superbwarfare*.jar` in `SuperbEnemies/libs/`.
2. Run `gradlew build` from `SuperbEnemies/`.
3. Use `gradlew runClient` for a test client.

JDK 17 is required. The built jar is placed in `SuperbEnemies/build/libs/`.

## License

MIT, see `LICENSE`.
