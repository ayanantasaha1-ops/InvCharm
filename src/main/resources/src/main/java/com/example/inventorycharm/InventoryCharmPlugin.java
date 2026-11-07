package com.example.inventorycharm;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class InventoryCharmPlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private NamespacedKey charmKey;
    private Connection dbConnection;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    @Override
    public void onEnable() {
        this.charmKey = new NamespacedKey(this, "is_charm");
        saveDefaultConfig();
        initDatabase();

        getServer().getPluginManager().registerEvents(this, this);
        if (getCommand("invcharm") != null) {
            getCommand("invcharm").setExecutor(this);
            getCommand("invcharm").setTabCompleter(this);
        }
        getLogger().info("InventoryCharm loaded successfully!");
    }

    @Override
    public void onDisable() {
        try {
            if (dbConnection != null && !dbConnection.isClosed()) {
                dbConnection.close();
            }
        } catch (SQLException e) {
            getLogger().severe("Failed to close database: " + e.getMessage());
        }
    }

    public ItemStack createCharmItem(int amount) {
        String matName = getConfig().getString("item.material", "ECHO_SHARD");
        Material mat = Material.matchMaterial(matName);
        if (mat == null) mat = Material.ECHO_SHARD;

        ItemStack charm = new ItemStack(mat, Math.max(1, amount));
        ItemMeta meta = charm.getItemMeta();

        if (meta != null) {
            String nameStr = getConfig().getString("item.name", "<bold><dark_purple>Inventory Charm</dark_purple></bold>");
            meta.displayName(miniMessage.deserialize(nameStr));

            List<String> loreStrings = getConfig().getStringList("item.lore");
            if (loreStrings.isEmpty()) {
                loreStrings = List.of(
                        "<gray>Keeps your inventory safe upon death.</gray>",
                        "<red>Consumed on use.</red>"
                );
            }
            List<Component> lore = new ArrayList<>();
            for (String s : loreStrings) {
                lore.add(miniMessage.deserialize(s));
            }
            meta.lore(lore);

            meta.getPersistentDataContainer().set(charmKey, PersistentDataType.BYTE, (byte) 1);
            charm.setItemMeta(meta);
        }
        return charm;
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        ItemStack[] contents = player.getInventory().getContents();
        int charmSlot = -1;

        for (int i = 0; i < contents.length; i++) {
            ItemStack item = contents[i];
            if (item != null && item.hasItemMeta()) {
                Byte val = item.getItemMeta().getPersistentDataContainer().get(charmKey, PersistentDataType.BYTE);
                if (val != null && val == 1) {
                    charmSlot = i;
                    break;
                }
            }
        }

        if (charmSlot != -1) {
            event.setKeepInventory(true);
            event.getDrops().clear();

            ItemStack charmStack = contents[charmSlot];
            if (charmStack.getAmount() > 1) {
                charmStack.setAmount(charmStack.getAmount() - 1);
            } else {
                contents[charmSlot] = null;
            }
            player.getInventory().setContents(contents);

            logCharmUse(player.getUniqueId(), player.getName());

            String msg = getConfig().getString("messages.saved", "<light_purple>⚡ Your Inventory Charm shattered, protecting your items!</light_purple>");
            player.sendMessage(miniMessage.deserialize(msg));
        }
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("inventorycharm.admin")) {
            sender.sendMessage(miniMessage.deserialize("<red>No permission.</red>"));
            return true;
        }

        if (args.length >= 2 && args[0].equalsIgnoreCase("give")) {
            Player target = Bukkit.getPlayer(args[1]);
            if (target == null) {
                sender.sendMessage(miniMessage.deserialize("<red>Player not found online.</red>"));
                return true;
            }

            int amount = 1;
            if (args.length >= 3) {
                try {
                    amount = Integer.parseInt(args[2]);
                    if (amount <= 0) amount = 1;
                } catch (NumberFormatException e) {
                    sender.sendMessage(miniMessage.deserialize("<red>Invalid amount.</red>"));
                    return true;
                }
            }

            target.getInventory().addItem(createCharmItem(amount));
            target.sendMessage(miniMessage.deserialize("<light_purple>You received " + amount + " Inventory Charm(s)!</light_purple>"));
            sender.sendMessage(miniMessage.deserialize("<green>Given " + amount + " Inventory Charm(s) to " + target.getName() + ".</green>"));
            return true;
        }

        sender.sendMessage(miniMessage.deserialize("<yellow>Usage: /invcharm give <player> [amount]</yellow>"));
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) return List.of("give");
        if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
            return List.of("1", "16", "64");
        }
        return List.of();
    }

    private void initDatabase() {
        try {
            if (!getDataFolder().exists()) getDataFolder().mkdirs();
            File dbFile = new File(getDataFolder(), "data.db");
            dbConnection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
            try (Statement stmt = dbConnection.createStatement()) {
                stmt.execute("CREATE TABLE IF NOT EXISTS charm_uses (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                        "player_uuid VARCHAR(36) NOT NULL, " +
                        "player_name VARCHAR(16) NOT NULL, " +
                        "timestamp DATETIME DEFAULT CURRENT_TIMESTAMP);");
            }
        } catch (SQLException e) {
            getLogger().severe("Database init error: " + e.getMessage());
        }
    }

    private void logCharmUse(UUID uuid, String name) {
        if (dbConnection == null) return;
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            try (PreparedStatement pstmt = dbConnection.prepareStatement("INSERT INTO charm_uses(player_uuid, player_name) VALUES(?, ?)")) {
                pstmt.setString(1, uuid.toString());
                pstmt.setString(2, name);
                pstmt.executeUpdate();
            } catch (SQLException e) {
                getLogger().warning("Failed to log charm use: " + e.getMessage());
            }
        });
    }
}
