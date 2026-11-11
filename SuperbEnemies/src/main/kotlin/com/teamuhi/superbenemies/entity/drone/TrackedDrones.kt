package com.teamuhi.superbenemies.entity.drone

import com.teamuhi.superbenemies.entity.boss.common.ai.EntityTracker

/** Every drone; read by a drone that spots a target so it can tell the other drones without scanning the level. */
val TrackedDrones = EntityTracker { it is DroneEntity }
