package me.poggercity.modificationFFA;

import org.bukkit.block.Block;

import java.util.UUID;

record RegionPoint(UUID worldId, String worldName, int x, int y, int z) {

    static RegionPoint from(Block block) {
        return new RegionPoint(
                block.getWorld().getUID(),
                block.getWorld().getName(),
                block.getX(),
                block.getY(),
                block.getZ()
        );
    }
}
