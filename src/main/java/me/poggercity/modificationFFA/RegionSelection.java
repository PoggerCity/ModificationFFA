package me.poggercity.modificationFFA;

record RegionSelection(RegionPoint first, RegionPoint second) {

    boolean complete() {
        return first != null && second != null;
    }

    boolean sameWorld() {
        return complete() && first.worldId().equals(second.worldId());
    }
}
