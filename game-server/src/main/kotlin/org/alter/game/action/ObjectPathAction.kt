package org.alter.game.action

import dev.openrune.cache.CacheManager.getObject
import gg.rsmod.util.AabbUtil
import gg.rsmod.util.DataConstants
import net.rsprot.protocol.game.outgoing.misc.player.SetMapFlag
import org.alter.game.model.Direction
import org.alter.game.model.MovementQueue
import org.alter.game.model.attr.INTERACTING_ITEM
import org.alter.game.model.attr.INTERACTING_OBJ_ATTR
import org.alter.game.model.attr.INTERACTING_OPT_ATTR
import org.alter.game.model.collision.ObjectType
import org.alter.game.model.entity.Entity
import org.alter.game.model.entity.GameObject
import org.alter.game.model.entity.Pawn
import org.alter.game.model.entity.Player
import org.alter.game.model.path.PathRequest
import org.alter.game.model.path.Route
import org.alter.game.model.queue.QueueTask
import org.alter.game.model.queue.TaskPriority
import org.alter.game.model.timer.FROZEN_TIMER
import org.alter.game.model.timer.STUN_TIMER
import org.alter.game.plugin.Plugin
import java.util.*

/**
 * This class is responsible for calculating distances and valid interaction
 * tiles for [GameObject] path-finding.
 *
 * @author Tom <rspsmods@gmail.com>
 */
object ObjectPathAction {
    fun walk(
        player: Player,
        obj: GameObject,
        lineOfSightRange: Int?,
        logic: Plugin.() -> Unit,
    ) {
        player.queue(TaskPriority.STANDARD) {
            terminateAction = {
                player.stopMovement()
                player.write(SetMapFlag(255, 255))
            }

            val route = walkTo(obj, lineOfSightRange)
            if (route.success) {
                if (lineOfSightRange == null || lineOfSightRange > 0) {
                    faceObj(player, obj)
                }
                player.executePlugin(logic)
            } else {
                player.faceTile(obj.tile)
                when {
                    player.timers.has(FROZEN_TIMER) -> player.writeMessage(Entity.MAGIC_STOPS_YOU_FROM_MOVING)
                    player.timers.has(STUN_TIMER) -> player.writeMessage(Entity.YOURE_STUNNED)
                    else -> player.writeMessage(Entity.YOU_CANT_REACH_THAT)
                }
                player.write(SetMapFlag(255, 255))
            }
        }
    }

    val itemOnObjectPlugin: Plugin.() -> Unit = {
        val player = ctx as Player

        val item = player.attr[INTERACTING_ITEM]!!.get()!!
        val obj = player.attr[INTERACTING_OBJ_ATTR]!!.get()!!
        val lineOfSightRange = player.world.plugins.getObjInteractionDistance(obj.id)

        walk(player, obj, lineOfSightRange) {
            if (!player.world.plugins.executeItemOnObject(player, obj.getTransform(player), item.id)) {
                player.writeMessage(Entity.NOTHING_INTERESTING_HAPPENS)
                if (player.world.devContext.debugObjects != "off") {
                    player.writeMessage(
                        "Unhandled item on object: [item=$item, id=${obj.id}, type=${obj.type}, rot=${obj.rot}, x=${obj.tile.x}, z=${obj.tile.z}]",
                    )
                }
            }
        }
    }

    val objectInteractPlugin: Plugin.() -> Unit = {
        val player = ctx as Player

        val obj = player.attr[INTERACTING_OBJ_ATTR]!!.get()!!
        val opt = player.attr[INTERACTING_OPT_ATTR]
        val lineOfSightRange = player.world.plugins.getObjInteractionDistance(obj.id)

        walk(player, obj, lineOfSightRange) {
            if (!player.world.plugins.executeObject(player, obj.getTransform(player), opt!!)) {
                player.writeMessage(Entity.NOTHING_INTERESTING_HAPPENS)
                if (player.world.devContext.debugObjects != "off") {
                    player.writeMessage(
                        "Unhandled object action: [opt=$opt, id=${obj.id}, type=${obj.type}, rot=${obj.rot}, x=${obj.tile.x}, z=${obj.tile.z}]",
                    )
                }
            }
        }
    }

    private suspend fun QueueTask.walkTo(
        obj: GameObject,
        lineOfSightRange: Int?,
    ): Route {
        val pawn = ctx as Pawn

        val def = obj.getDef()
        val tile = obj.tile
        val type = obj.type
        val rot = obj.rot
        var width = def.sizeX
        var length = def.sizeY
        val clipMask = def.clipMask

        val wall = type == ObjectType.LENGTHWISE_WALL.value || type == ObjectType.DIAGONAL_WALL.value
        val diagonal = type == ObjectType.DIAGONAL_WALL.value || type == ObjectType.DIAGONAL_INTERACTABLE.value
        val wallDeco = type == ObjectType.INTERACTABLE_WALL_DECORATION.value || type == ObjectType.INTERACTABLE_WALL.value
        val blockDirections = EnumSet.noneOf(Direction::class.java)

        if (wallDeco) {
            width = 0
            length = 0
        } else if (!wall && (rot == 1 || rot == 3)) {
            width = def.sizeY
            length = def.sizeX
        }

        /*
         * Objects have a clip mask in their [ObjectDef] which can be used
         * to specify any directions that the object can't be 'interacted'
         * from.
         */
        val blockBits = 4
        val clipFlag = (DataConstants.BIT_MASK[blockBits] and (clipMask shl rot)) or (clipMask shr (blockBits - rot))

        if ((0x1 and clipFlag) != 0) {
            blockDirections.add(Direction.NORTH)
        }

        if ((0x2 and clipFlag) != 0) {
            blockDirections.add(Direction.EAST)
        }

        if ((0x4 and clipFlag) != 0) {
            blockDirections.add(Direction.SOUTH)
        }

        if ((clipFlag and 0x8) != 0) {
            blockDirections.add(Direction.WEST)
        }

        /*
         * Wall objects can't be interacted from certain directions due to
         * how they are visually placed in a tile.
         */
        val blockedWallDirections =
            when (rot) {
                0 -> EnumSet.of(Direction.EAST)
                1 -> EnumSet.of(Direction.SOUTH)
                2 -> EnumSet.of(Direction.WEST)
                3 -> EnumSet.of(Direction.NORTH)
                else -> throw IllegalStateException("Invalid object rotation: $rot")
            }

        /*
         * Diagonal walls have an extra direction set as 'blocked', this is to
         * avoid the player interacting with the door and having its opened
         * door object be spawned on top of them, which leads to them being
         * stuck.
         */
        if (wall && diagonal) {
            when (rot) {
                0 -> blockedWallDirections.add(Direction.NORTH)
                1 -> blockedWallDirections.add(Direction.EAST)
                2 -> blockedWallDirections.add(Direction.SOUTH)
                3 -> blockedWallDirections.add(Direction.WEST)
            }
        }

        if (wall) {
            /*
             * Check if the pawn is within interaction distance of the wall.
             */
            if (pawn.tile.isWithinRadius(tile, 1)) {
                val dir = Direction.between(tile, pawn.tile)
                if (dir !in blockedWallDirections && (
                        diagonal ||
                            !AabbUtil.areDiagonal(
                                pawn.tile.x,
                                pawn.tile.z,
                                pawn.getSize(),
                                pawn.getSize(),
                                tile.x,
                                tile.z,
                                width,
                                length,
                            )
                    )
                ) {
                    return Route(ArrayDeque(), success = true, tail = pawn.tile)
                }
            }

            blockDirections.addAll(blockedWallDirections)
        }

        val builder =
            PathRequest.Builder()
                .setPoints(pawn.tile, tile)
                .setSourceSize(pawn.getSize(), pawn.getSize())
                .setProjectilePath(lineOfSightRange != null)
                .setTargetSize(width, length)
                .clipPathNodes(node = true, link = true)
                .clipDirections(*blockDirections.toTypedArray())

        if (lineOfSightRange != null) {
            builder.setTouchRadius(lineOfSightRange)
        }

        /*
         * If the object is not a 'diagonal' object, you shouldn't be able to
         * interact with them from diagonal tiles.
         */
        if (!diagonal) {
            builder.clipDiagonalTiles()
        }

        /*
         * If the object is not a wall object, or if we have a line of sight range
         * set for the object, then we shouldn't clip the tiles that overlap the
         * object; otherwise we do clip them.
         */
        if (!wall && (lineOfSightRange == null || lineOfSightRange > 0)) {
            builder.clipOverlapTiles()
        }

        val route = pawn.createPathFindingStrategy().calculateRoute(builder.build())

        if (pawn.timers.has(FROZEN_TIMER) && !pawn.tile.sameAs(route.tail)) {
            return Route(ArrayDeque(), success = false, tail = pawn.tile)
        }

        pawn.walkPath(route.path, MovementQueue.StepType.NORMAL, detectCollision = true)

        val last = pawn.movementQueue.peekLast()
        while (last != null &&
            !pawn.tile.sameAs(
                last,
            ) && !pawn.timers.has(FROZEN_TIMER) && !pawn.timers.has(STUN_TIMER) && pawn.lock.canMove()
        ) {
            wait(1)
        }

        if (pawn.timers.has(STUN_TIMER)) {
            pawn.stopMovement()
            return Route(ArrayDeque(), success = false, tail = pawn.tile)
        }

        if (pawn.timers.has(FROZEN_TIMER) && !pawn.tile.sameAs(route.tail)) {
            return Route(ArrayDeque(), success = false, tail = pawn.tile)
        }

        if (wall && !route.success && pawn.tile.isWithinRadius(tile, 1) && Direction.between(tile, pawn.tile) !in blockedWallDirections) {
            return Route(route.path, success = true, tail = route.tail)
        }

        return route
    }

    private fun faceObj(
        pawn: Pawn,
        obj: GameObject,
    ) {
        val def = getObject(obj.id)
        val rot = obj.rot
        val type = obj.type

        when (type) {
            ObjectType.LENGTHWISE_WALL.value -> {
                if (!pawn.tile.sameAs(obj.tile)) {
                    pawn.faceTile(obj.tile)
                }
            }
            ObjectType.INTERACTABLE_WALL_DECORATION.value, ObjectType.INTERACTABLE_WALL.value -> {
                val dir =
                    when (rot) {
                        0 -> Direction.WEST
                        1 -> Direction.NORTH
                        2 -> Direction.EAST
                        3 -> Direction.SOUTH
                        else -> throw IllegalStateException("Invalid object rotation: $obj")
                    }
                pawn.faceTile(pawn.tile.step(dir))
            }
            else -> {
                var width = def.sizeX
                var length = def.sizeY
                if (rot == 1 || rot == 3) {
                    width = def.sizeY
                    length = def.sizeX
                }
                pawn.faceTile(obj.tile.transform(width shr 1, length shr 1), width, length)
            }
        }
    }
}
