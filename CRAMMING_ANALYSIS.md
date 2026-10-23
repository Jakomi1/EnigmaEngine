# Cramming Entity Pushing - Vollstandiger Analysebericht

> Repo: C:\Users\jakob_vowie\Desktop\Coding Projekte\Java\EnigmaEngine (Canvas/Enigma/Folia-Fork, Regionizer-basiert, Multi-Threaded)
> Fokus: Entity Cramming (zusammengedrangte Entities erleiden Schaden) im gepatchten Code unter canvas-server/src/minecraft/java/

## (a) Vanilla-Referenzpfad (Schritt fur Schritt)

### 1) Wann wird gepusht?
- LivingEntity.aiStep(): Canvas patcht Aufruf von pushEntities nur wenn entityCollisionMode.allEntitiesCanBePushed() true (VANILLA).
- Code: canvas-server/src/minecraft/java/net/minecraft/world/entity/LivingEntity.java:3871, Patch: canvas-server/minecraft-patches/sources/net/minecraft/world/entity/LivingEntity.java.patch:199-201

### 2) Cramming-Logik in LivingEntity.pushEntities (3945-4003)
1. Canvas: falls collisionMode.noCollisions() return (3947-3951)
2. Paper: falls !isPushable() return; Team.CollisionRule.NEVER return (3953-3960)
3. maxCramming = getGameRules().get(GameRules.MAX_ENTITY_CRAMMING). Falls maxCramming<=0 und paperConfig().collisions.maxEntityCollisions<=0 return (3962-3966)
4. Entity-Liste: falls collisionMode.onlyPlayersPushable() -> level().canvas(..., EntitySelector.pushableBy(this)) sonst level().getPushableEntities(this, getBoundingBox()) (3968-3971)
5. Cramming-Schaden (3972-3988): falls ServerLevel und maxCramming>0 und pushableEntities.size()>maxCramming-1 und random.nextInt(4)==0: zahlt nicht-passagier Entities, falls count>maxCramming-1 -> hurtServer(cramming(), 6.0F)
6. Collision-Cap + push (3990-4002): numCollisions = max(0, numCollisions - maxEntityCollisions); fuer jedes Entity falls numCollisions>=maxEntityCollisions break; entity.numCollisions++; this.numCollisions++; doPush(entity)


### 3) Entity-Suche
- Level.getPushableEntities (2064-2066): return getEntities(pusher, boundingBox, EntitySelector.pushableBy(pusher)); Patch 115-117
- Level.getEntities gepatcht (1910-1931): wenn TickThread -> bb = TickThread.clipAABBToOwnedRegion(this,bb); falls null return List.of(); sonst ensureTickThread. Nutzt Moonrise EntityLookup.getEntities. Patch 74-98
- Level.getEntities(EntityTypeTest,...) gepatcht (1947-1957) analog. Patch 100-114
- EntityLookup.getEntities iteriert ueber Region/Chunk-Slices im Bereich (erweitert ±2 Chunks XZ), prueft Chunk FULL, ruft chunk.getEntities. EntityCollectionBySection durchsucht Y-Sections, prueft AABB-Intersection + Predicate.
- Level.canvas (2069-2095): holt Spieler aus moonrise() mit MapType je nach isLargePushRange() (SMALL->GENERAL_REALLY_REALLY_SMALL, LARGE->SPAWN_RANGE), filtert nach !=calling, AABB.intersects, predicate.test. Nutzt NearbyPlayers-Map, nicht EntityLookup.


### 4) Pushable-Filter & Collidable
- EntitySelector.pushableBy (45-74): Team-Regeln, NO_SPECTATORS.and(input -> input.isPushable() && input.canCollideWithBukkit(entity) && entity.canCollideWithBukkit(input) && Team-Collision + Paper enablePlayerCollisions)
- Entity.isPushable() -> isCollidable(false) (2668-2671). Entity.isCollidable() default false (2673-2676). LivingEntity.isPushable() -> isCollidable(fixClimbingBypassingCrammingRule) (4149-4151). LivingEntity.isCollidable(ignoreClimbing): falls canvas && canvasConfig.blocks.spawner.spawnedEntitiesHaveNoCollision -> false; sonst alive && !spectator && (ignoreClimbing||!onClimbable()) && collides (4154-4158). LivingEntity.canCollideWithBukkit -> isPushable() && collides != collidableExemptions.contains(uuid) (4162-4165). Entity.canCollideWithBukkit -> isPushable() (2679-2682).
- Entity.push(Entity): noPhysics/Vehicle Checks, Paper onlyPlayersCollide, CraftBukkit Event, berechnet Push, ruft push auf beide falls pushable (2497-2532). Entity.push(xa,ya,za,pushingEntity): Paper Events, deltaMovement.add, needsSync (2545-2561). LivingEntity.doPush -> entity.push(this) (4044-4046).


## (b) Canvas/Enigma-Abweichungen von Vanilla (mit Datei:Zeile)

### Region/Threading
- Level.getEntities(1910-1921): Clip AABB auf eigenen Region-Besitz wenn auf TickThread via TickThread.clipAABBToOwnedRegion; falls null -> List.of(); sonst ensureTickThread. (Patch 74-98)
- Level.getEntities(EntityTypeTest,...)(1947-1957): analoges Clipping. (Patch 100-114)
- TickThread.clipAABBToOwnedRegion (paper-server/src/main/java/ca/spottedleaf/moonrise/common/util/TickThread.java:425-443, Patch canvas-server/paper-patches/files/src/main/java/ca/spottedleaf/moonrise/common/util/TickThread.java.patch): holt Region vom TickThreadRunner/RegionShutdownThread, prueft world.regioniser == region.regioniser, gibt aber IMMER box unveraendert zurueck (Zeile 441).
- ServerEntityLookup.entitySectionChangeCallback: Region-Coupling Tracking bei Sectionswechsel ueber Regionsgrenzen (77-92).
- Entity.move: prueft/verhindert/begrenzt Bewegungen ausserhalb Region, wirft EntityMoveOutOfRegionException in bestimmten Faellen (1204-1218,1251-1290).

### Entity-Collision-Mode (Canvas)
- WorldConfig.Entities: entityCollisionMode mit VANILLA, ONLY_PUSHABLE_PLAYERS_LARGE, ONLY_PUSHABLE_PLAYERS_SMALL, NO_COLLISIONS; nurPlayersPushable/allEntitiesCanBePushed/noCollisions/isLargePushRange (343-424). Default VANILLA.
- LivingEntity.aiStep: pushEntities nur wenn allEntitiesCanBePushed() (3871, Patch 199-201).
- LivingEntity.pushEntities: falls noCollisions() return (3947-3951, Patch 216-221). Falls onlyPlayersPushable() nutzt canvas statt getPushableEntities (3968-3971, Patch 230-234).
- Level.canvas (2069-2095, Patch 119-146): nutzt moonrise() mit MapType SPAWN_RANGE (LARGE) oder GENERAL_REALLY_REALLY_SMALL (SMALL), filtert BoundingBox.intersection + Predicate. Run-Config canvas-worlds.yml:40-46 zeigt Default VANILLA.

### Spawner-Collidable
- LivingEntity.canvas (300), NBT save/load (866,983), Patch 7,96,104. BaseSpawner setzt mob.canvas = true (Patch). LivingEntity.isCollidable: falls canvas && blocks.spawner.spawnedEntitiesHaveNoCollision -> false (4155, Patch 242). WorldConfig.blocks.spawner.spawnedEntitiesHaveNoCollision default false (577,587), canvas-worlds.yml:160-161.

### Paper/Canvas Config
- WorldConfiguration.Collisions: onlyPlayersCollide false, allowVehicleCollisions false (gepatched), fixClimbingBypassingCrammingRule true (gepatched von false), maxEntityCollisions 4 (gepatched von 8), allowPlayerCrammingDamage false (496-503, Patch).
- LivingEntity.pushEntities nutzt numCollisions-Cap mit paperConfig().collisions.maxEntityCollisions (3990-3999).
- LivingEntity.isPushable() nutzt isCollidable(fixClimbingBypassingCrammingRule) (4149-4151).
- Entity.push(Entity): onlyPlayersCollide-Check (2500).
- Run-Configs zeigen teils alte Defaults (paper-world-defaults.yml:68-71), aber gepatchte Klassen haben geaenderte Defaults.


## (c) Rangliste plausibelster Ursachen fuer 'Cramming verhaelt sich nicht normal'

| Rang | Ursache | Schwere | Begruendung | Zu pruefende Config/Code |
|------|---------|---------|------------|--------------------------|
| 1 | Entity-Collision-Mode nicht VANILLA | ***** | pushEntities() nur wenn allEntitiesCanBePushed(). ONLY_* nutzt nur Spieler via NearbyPlayers; NO_COLLISIONS deaktiviert komplett. | canvas-worlds.yml entities.entity-collision-mode; WorldConfig.Entities.EntityCollisionMode |
| 2 | Nur-Spieler-Push nutzt NearbyPlayers statt EntityLookup | ***** | canvas holt aus moonrise() mit begrenztem MapType (GENERAL_REALLY_REALLY_SMALL/SPAWN_RANGE). Ausserhalb Radius nicht gefunden. | Level.java:2069-2095; NearbyPlayers MapTypes; Modus ONLY_PUSHABLE_PLAYERS_* |
| 3 | maxEntityCollisions auf 4 reduziert | ****☆ | Paper-Cap bricht frueher ab -> weniger Pushes pro Tick (Default 8->4 gepatcht). | WorldConfiguration.Collisions.maxEntityCollisions; LivingEntity.pushEntities:3990-3999 |
| 4 | Region-basiertes AABB-Clipping in Level.getEntities | ****☆ | Clip auf TickThread via clipAABBToOwnedRegion; clipAABBToOwnedRegion gibt aktuell unveraendertes box zurueck, aber Codepfad geaendert; bei null Clip -> leere Liste. In Folia strikt region-begrenzt. | Level.java:1910-1957, TickThread.clipAABBToOwnedRegion:425-443 |
| 5 | Spawner-Entities ohne Collision | ****☆ | Falls spawnedEntitiesHaveNoCollision true -> gespawnte Mobs nicht pushable/collidable, nehmen nicht am Cramming teil. | LivingEntity.isCollidable:4155; WorldConfig.blocks.spawner.spawnedEntitiesHaveNoCollision |
| 6 | fixClimbingBypassingCrammingRule true | ***☆☆ | isPushable() nutzt isCollidable(true), ignoriert onClimbable eher nicht als Grund zum Ausschluss -> kann Cramming haeufiger ausloesen (meist Korrektur). | WorldConfiguration.Collisions.fixClimbingBypassingCrammingRule |
| 7 | Entity.push onlyPlayersCollide | ***☆☆ | Falls true, nur Spieler koennen untereinander pushen. | Entity.java:2500, WorldConfiguration.Collisions.onlyPlayersCollide |

## Relevante Dateien (Kurzreferenz)
- Kern: canvas-server/src/minecraft/java/net/minecraft/world/entity/LivingEntity.java (3871,3945-4003,4044-4046,4147-4166), Patch canvas-server/minecraft-patches/sources/net/minecraft/world/entity/LivingEntity.java.patch
- Filter/Push: canvas-server/src/minecraft/java/net/minecraft/world/entity/EntitySelector.java (45-74), canvas-server/src/minecraft/java/net/minecraft/world/entity/Entity.java (2497-2562,2668-2682)
- Suche/Region: canvas-server/src/minecraft/java/net/minecraft/world/level/Level.java (1910-1931,1947-1957,2064-2095), Patch canvas-server/minecraft-patches/sources/net/minecraft/world/level/Level.java.patch; EntityGetter.java; moonrise EntityLookup/ChunkEntitySlices; paper-server/src/main/java/ca/spottedleaf/moonrise/common/util/TickThread.java (425-443), Patch canvas-server/paper-patches/files/src/main/java/ca/spottedleaf/moonrise/common/util/TickThread.java.patch
- Config: canvas-server/src/main/java/io/canvasmc/canvas/WorldConfig.java (343-424,577,587); paper-server/src/main/java/io/papermc/paper/configuration/WorldConfiguration.java (496-503), Patch canvas-server/paper-patches/files/src/main/java/io/papermc/paper/configuration/WorldConfiguration.java.patch; run/config/canvas-worlds.yml, run/config/paper-world-defaults.yml

