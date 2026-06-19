package net.buildtheearth.buildteamtools.modules.generator.components.rail;

record ColumnKey(int x, int z) {

    static ColumnKey from(PositionKey key) {
        return new ColumnKey(key.x(), key.z());
    }

    static ColumnKey of(int x, int z) {
        return new ColumnKey(x, z);
    }
}
