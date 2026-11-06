package gg.guab.blocks;

public record PrankBlock(
        String world,
        int x,
        int y,
        int z,
        PrankType type,
        Double chance,
        Integer restoreDelayTicks,
        Integer cooldownSeconds,
        String effect,
        Integer durationSeconds,
        Integer amplifier,
        String breakSound) {

    public String key() {
        return world + ";" + x + ";" + y + ";" + z;
    }

    public PrankBlock withType(PrankType newType) {
        return new PrankBlock(world, x, y, z, newType, chance, restoreDelayTicks, cooldownSeconds,
                effect, durationSeconds, amplifier, breakSound);
    }
}
