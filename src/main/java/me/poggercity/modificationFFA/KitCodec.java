package me.poggercity.modificationFFA;

import org.bukkit.inventory.ItemStack;

final class KitCodec {

    private KitCodec() {
    }

    static byte[] serialize(ItemStack[] contents) {
        return ItemStack.serializeItemsAsBytes(contents);
    }

    static ItemStack[] deserialize(byte[] data) {
        return ItemStack.deserializeItemsFromBytes(data);
    }
}
