# Building Tips

## General Guidelines

- It's best to create your base structure first without entering edit mode. Once the structure is complete, create a new building and use select mode to include all blocks.

## Underground and Underwater Structures

- When creating underground or underwater structures, always include air blocks between your solid blocks. This ensures they will replace water or terrain blocks when the building spawns.

## Using Select Mode

- To use select mode correctly, you sometimes need to enter spectator mode. In this case, pos1 and pos2 will no longer be based on the player's position but on what the player is looking at. So to select a precise point in the air, place a temporary block there, use pos1 or pos2, then remove that block.

## Managing Mobs

- When managing mobs between rooms and the base building, remember that even if base building mobs disappear when you edit a room, when the building spawns in the game world, both the room's mobs and the base building's mobs will spawn simultaneously.

## Room Collision

- If multiple rooms collide (their blocks overlap), only one of them will be able to spawn when the building generates in the game world.
