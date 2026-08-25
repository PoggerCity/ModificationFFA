package me.poggercity.modificationFFA;

import org.bukkit.World;

record RegionBounds(
        String worldId,
        String worldName,
        int minX,
        int minY,
        int minZ,
        int maxX,
        int maxY,
        int maxZ
) {

    static RegionBounds from(RegionSelection selection) {
        RegionPoint first = selection.first();
        RegionPoint second = selection.second();
        return new RegionBounds(
                first.worldId().toString(),
                first.worldName(),
                Math.min(first.x(), second.x()),
                Math.min(first.y(), second.y()),
                Math.min(first.z(), second.z()),
                Math.max(first.x(), second.x()),
                Math.max(first.y(), second.y()),
                Math.max(first.z(), second.z())
        );
    }

    boolean valid() {
        return worldId != null && worldName != null
                && minX <= maxX && minY <= maxY && minZ <= maxZ;
    }

    boolean contains(World world, int x, int y, int z) {
        return matches(world)
                && x >= minX && x <= maxX
                && y >= minY && y <= maxY
                && z >= minZ && z <= maxZ;
    }

    boolean encloses(RegionBounds other) {
        boolean sameWorld = worldId.equals(other.worldId)
                || worldName.equalsIgnoreCase(other.worldName);
        return sameWorld
                && other.minX >= minX && other.maxX <= maxX
                && other.minY >= minY && other.maxY <= maxY
                && other.minZ >= minZ && other.maxZ <= maxZ;
    }

    private boolean matches(World world) {
        return world.getUID().toString().equals(worldId)
                || world.getName().equalsIgnoreCase(worldName);
    }
}
