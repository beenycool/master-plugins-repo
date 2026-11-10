package gg.guab.blocks;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class GuabBlocksPlugin extends JavaPlugin {

    private BlocksConfig config;
    private BlockStore blocks;
    private PrankManager pranks;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.config = BlocksConfig.load(this);
        this.blocks = new BlockStore(this);
        this.pranks = new PrankManager(this);

        getServer().getPluginManager().registerEvents(new TriggerListener(this), this);

        PluginCommand command = getCommand("guabblocks");
        if (command != null) {
            GuabBlocksCommand executor = new GuabBlocksCommand(this);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }
        getLogger().info("GuabBlocks ready (" + blocks.size() + " blocks).");
    }

    public boolean reload() {
        reloadConfig();
        this.config = BlocksConfig.load(this);
        if (blocks != null) {
            return blocks.reload();
        }
        return true;
    }

    @Override
    public void onDisable() {
        if (pranks != null) {
            pranks.shutdown();
        }
        if (blocks != null) {
            blocks.save();
        }
    }

    public BlocksConfig config() {
        return config;
    }

    public BlockStore blocks() {
        return blocks;
    }

    public PrankManager pranks() {
        return pranks;
    }
}
