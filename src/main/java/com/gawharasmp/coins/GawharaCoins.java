package com.gawharasmp.coins;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashMap;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Public reflection-friendly API for companion plugins:
 * getBalance(UUID), hasCoins(UUID,long), depositCoins(UUID,long,String),
 * withdrawCoins(UUID,long,String), awardCoinsOnce(UUID,long,String,String).
 */
public final class GawharaCoins extends JavaPlugin implements Listener, CommandExecutor {
    private File dataFile;
    private YamlConfiguration data;
    private BukkitTask autosaveTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        if (!getDataFolder().exists() && !getDataFolder().mkdirs()) {
            getLogger().severe("Could not create data folder.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        dataFile = new File(getDataFolder(), "balances.yml");
        data = YamlConfiguration.loadConfiguration(dataFile);
        getServer().getPluginManager().registerEvents(this, this);
        if (getCommand("coins") != null) getCommand("coins").setExecutor(this);
        long seconds = Math.max(15L, getConfig().getLong("settings.autosave-seconds", 60L));
        autosaveTask = Bukkit.getScheduler().runTaskTimer(this, this::saveData, seconds * 20L, seconds * 20L);
        getLogger().info("GawharaCoins enabled. GC is independent from Vault.");
    }

    @Override
    public void onDisable() {
        if (autosaveTask != null) autosaveTask.cancel();
        saveData();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        synchronized (this) {
            data.set("players." + event.getPlayer().getUniqueId() + ".name", event.getPlayer().getName());
            saveData();
        }
    }

    public synchronized long getBalance(UUID playerId) {
        if (playerId == null) return 0L;
        return Math.max(0L, data.getLong("players." + playerId + ".balance", 0L));
    }

    public synchronized boolean hasCoins(UUID playerId, long amount) {
        return amount >= 0L && getBalance(playerId) >= amount;
    }

    public synchronized boolean depositCoins(UUID playerId, long amount, String reason) {
        if (playerId == null || amount <= 0L) return false;
        long current = getBalance(playerId);
        if (Long.MAX_VALUE - current < amount) return false;
        data.set("players." + playerId + ".balance", current + amount);
        appendTransaction(playerId, amount, reason == null ? "Deposit" : reason, null);
        return saveData();
    }

    public synchronized boolean withdrawCoins(UUID playerId, long amount, String reason) {
        if (playerId == null || amount <= 0L || getBalance(playerId) < amount) return false;
        data.set("players." + playerId + ".balance", getBalance(playerId) - amount);
        appendTransaction(playerId, -amount, reason == null ? "Purchase" : reason, null);
        return saveData();
    }

    /** Duplicate transaction keys are treated as already paid and never add coins twice. */
    public synchronized boolean awardCoinsOnce(UUID playerId, long amount, String reason, String transactionKey) {
        if (playerId == null || amount <= 0L || transactionKey == null || transactionKey.isBlank()) return false;
        String hash = sha256(transactionKey);
        String txPath = "processed." + hash;
        if (data.contains(txPath)) return true;
        long current = getBalance(playerId);
        if (Long.MAX_VALUE - current < amount) return false;
        data.set("players." + playerId + ".balance", current + amount);
        data.set(txPath, true);
        appendTransaction(playerId, amount, reason == null ? "Reward" : reason, hash);
        return saveData();
    }

    public synchronized boolean transferCoins(UUID from, UUID to, long amount, String reason) {
        if (from == null || to == null || from.equals(to) || amount <= 0L || getBalance(from) < amount) return false;
        long target = getBalance(to);
        if (Long.MAX_VALUE - target < amount) return false;
        data.set("players." + from + ".balance", getBalance(from) - amount);
        data.set("players." + to + ".balance", target + amount);
        appendTransaction(from, -amount, "Transfer to " + to, null);
        appendTransaction(to, amount, "Transfer from " + from, null);
        return saveData();
    }

    private void appendTransaction(UUID playerId, long delta, String reason, String keyHash) {
        int next = data.getInt("history.next-id", 0) + 1;
        data.set("history.next-id", next);
        String path = "history.entries." + next;
        data.set(path + ".player", playerId.toString());
        data.set(path + ".delta", delta);
        data.set(path + ".reason", reason);
        data.set(path + ".time", System.currentTimeMillis());
        if (keyHash != null) data.set(path + ".key", keyHash);
        int keep = Math.max(100, getConfig().getInt("storage.history-limit", 5000));
        int removeBefore = next - keep;
        if (removeBefore > 0) data.set("history.entries." + removeBefore, null);
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    private synchronized boolean saveData() {
        if (data == null || dataFile == null) return false;
        try {
            data.save(dataFile);
            return true;
        } catch (IOException ex) {
            getLogger().log(Level.SEVERE, "Could not save balances.yml", ex);
            return false;
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("balance")) {
            if (args.length >= 2 && sender.hasPermission("gawharacoins.admin")) {
                OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
                send(sender, "other-balance", "%player%", safeName(target), "%balance%", String.valueOf(getBalance(target.getUniqueId())));
                return true;
            }
            if (!(sender instanceof Player player)) { send(sender, "player-only"); return true; }
            send(player, "balance", "%balance%", String.valueOf(getBalance(player.getUniqueId())));
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "shop" -> {
                if (!(sender instanceof Player player)) { send(sender, "player-only"); return true; }
                openShop(player);
            }
            case "pay" -> {
                if (!(sender instanceof Player player)) { send(sender, "player-only"); return true; }
                if (args.length < 3) { sender.sendMessage(color("&eUsage: /coins pay <player> <amount>")); return true; }
                Player target = Bukkit.getPlayerExact(args[1]);
                long amount = parseAmount(args[2]);
                if (target == null) { send(sender, "player-not-found"); return true; }
                if (amount <= 0) { send(sender, "invalid-amount"); return true; }
                if (target.getUniqueId().equals(player.getUniqueId())) { send(sender, "cannot-pay-self"); return true; }
                if (!transferCoins(player.getUniqueId(), target.getUniqueId(), amount, "Player payment")) { send(sender, "insufficient"); return true; }
                send(player, "paid", "%amount%", String.valueOf(amount), "%player%", target.getName());
                send(target, "received", "%amount%", String.valueOf(amount), "%player%", player.getName());
            }
            case "top" -> showTop(sender);
            case "give", "take", "set" -> {
                if (!sender.hasPermission("gawharacoins.admin")) { send(sender, "no-permission"); return true; }
                if (args.length < 3) { sender.sendMessage(color("&eUsage: /coins " + sub + " <player> <amount>")); return true; }
                OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
                long amount = parseAmount(args[2]);
                if (amount < 0 || (!sub.equals("set") && amount == 0) || (!target.hasPlayedBefore() && !target.isOnline())) {
                    send(sender, "invalid-amount"); return true;
                }
                boolean ok;
                if (sub.equals("give")) ok = depositCoins(target.getUniqueId(), amount, "Admin grant by " + sender.getName());
                else if (sub.equals("take")) ok = withdrawCoins(target.getUniqueId(), amount, "Admin removal by " + sender.getName());
                else {
                    synchronized (this) {
                        long previous = getBalance(target.getUniqueId());
                        data.set("players." + target.getUniqueId() + ".balance", amount);
                        data.set("players." + target.getUniqueId() + ".name", safeName(target));
                        appendTransaction(target.getUniqueId(), amount - previous, "Admin balance set by " + sender.getName(), null);
                        ok = saveData();
                    }
                }
                send(sender, ok ? (sub.equals("give") ? "given" : sub.equals("take") ? "taken" : "set") : "transaction-failed",
                        "%amount%", String.valueOf(amount), "%player%", safeName(target));
            }
            case "reload" -> {
                if (!sender.hasPermission("gawharacoins.admin")) { send(sender, "no-permission"); return true; }
                reloadConfig();
                send(sender, "reloaded");
            }
            default -> sender.sendMessage(color("&b/coins &7| &b/coins pay <player> <amount> &7| &b/coins top &7| &b/coins shop"));
        }
        return true;
    }

    private long parseAmount(String value) {
        try { return Long.parseLong(value); } catch (NumberFormatException ex) { return -1L; }
    }

    private String safeName(OfflinePlayer player) {
        String name = player.getName();
        return name == null ? player.getUniqueId().toString().substring(0, 8) : name;
    }

    private void showTop(CommandSender sender) {
        ConfigurationSection section = data.getConfigurationSection("players");
        List<BalanceEntry> balances = new ArrayList<>();
        if (section != null) {
            for (String rawId : section.getKeys(false)) {
                try {
                    UUID id = UUID.fromString(rawId);
                    String name = section.getString(rawId + ".name", Bukkit.getOfflinePlayer(id).getName());
                    long balance = section.getLong(rawId + ".balance", 0L);
                    if (name != null) balances.add(new BalanceEntry(name, balance));
                } catch (IllegalArgumentException ignored) { }
            }
        }
        balances.sort(Comparator.comparingLong(BalanceEntry::balance).reversed());
        sender.sendMessage(color(getConfig().getString("settings.prefix", "") + getConfig().getString("messages.top-header", "&bTop GC")));
        for (int i = 0; i < Math.min(10, balances.size()); i++) {
            BalanceEntry entry = balances.get(i);
            String line = getConfig().getString("messages.top-line", "&e%rank%. &f%player% &8» &b%balance% GC")
                    .replace("%rank%", String.valueOf(i + 1)).replace("%player%", entry.name()).replace("%balance%", String.valueOf(entry.balance()));
            sender.sendMessage(color(line));
        }
    }

    private void openShop(Player player) {
        if (!getConfig().getBoolean("shop.enabled", true)) { player.sendMessage(color("&cThe GC shop is disabled.")); return; }
        int rows = Math.max(3, Math.min(6, getConfig().getInt("shop.rows", 6)));
        String title = color(getConfig().getString("shop.title", "&8✦ &bGawhara &fCoins Shop"));
        ShopHolder holder = new ShopHolder();
        Inventory inventory = Bukkit.createInventory(holder, rows * 9, title);
        holder.inventory = inventory;
        Material fillerType = Material.matchMaterial(getConfig().getString("shop.filler", "BLUE_STAINED_GLASS_PANE"));
        if (fillerType == null || fillerType.isAir()) fillerType = Material.BLUE_STAINED_GLASS_PANE;
        ItemStack filler = makeItem(fillerType, "&8 ", List.of());
        for (int i = 0; i < inventory.getSize(); i++) inventory.setItem(i, filler);

        ConfigurationSection products = getConfig().getConfigurationSection("shop.items");
        if (products != null) {
            for (String id : products.getKeys(false)) {
                ConfigurationSection product = products.getConfigurationSection(id);
                if (product == null) continue;
                Material material = Material.matchMaterial(product.getString("material", "PAPER"));
                if (material == null || material.isAir()) continue;
                int slot = product.getInt("slot", -1);
                if (slot < 0 || slot >= inventory.getSize()) continue;
                int amount = Math.max(1, Math.min(material.getMaxStackSize(), product.getInt("amount", 1)));
                long price = product.getLong("price", 1L);
                if (price <= 0) continue;
                ItemStack display = makeItem(material, product.getString("name", id), product.getStringList("lore"));
                ItemMeta meta = display.getItemMeta();
                if (meta != null) {
                    List<String> lore = meta.hasLore() && meta.getLore() != null ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
                    lore.add(color("&8"));
                    lore.add(color("&7Price: &e" + price + " GC"));
                    lore.add(color("&aClick to purchase"));
                    meta.setLore(lore);
                    display.setItemMeta(meta);
                }
                inventory.setItem(slot, display);
                holder.products.put(slot, new ShopProduct(id, material, product.getString("name", id), amount, price));
            }
        }
        player.openInventory(inventory);
        send(player, "shop-open");
    }

    private ItemStack makeItem(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(color(name));
            List<String> lines = new ArrayList<>();
            for (String line : lore) lines.add(color(line));
            meta.setLore(lines);
            item.setItemMeta(meta);
        }
        return item;
    }

    @EventHandler
    public void onShopClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof ShopHolder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || event.getClickedInventory() != event.getView().getTopInventory()) return;
        ShopProduct product = holder.products.get(event.getRawSlot());
        if (product == null) return;
        if (getBalance(player.getUniqueId()) < product.price) { send(player, "insufficient"); return; }
        Inventory simulation = Bukkit.createInventory(null, 36);
        simulation.setContents(player.getInventory().getStorageContents());
        if (!simulation.addItem(new ItemStack(product.material, product.amount)).isEmpty()) { send(player, "shop-full"); return; }
        if (!withdrawCoins(player.getUniqueId(), product.price, "GC Shop purchase: " + product.id)) { send(player, "insufficient"); return; }
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(new ItemStack(product.material, product.amount));
        if (!leftover.isEmpty()) {
            depositCoins(player.getUniqueId(), product.price, "Refund: inventory changed during purchase");
            send(player, "shop-full");
            return;
        }
        send(player, "bought", "%item%", product.name, "%price%", String.valueOf(product.price));
    }

    @EventHandler
    public void onShopDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof ShopHolder) event.setCancelled(true);
    }

    private void send(CommandSender sender, String key, String... replacements) {
        String text = getConfig().getString("messages." + key, "");
        for (int i = 0; i + 1 < replacements.length; i += 2) text = text.replace(replacements[i], replacements[i + 1]);
        sender.sendMessage(color(getConfig().getString("settings.prefix", "") + text));
    }

    private String color(String value) { return ChatColor.translateAlternateColorCodes('&', value == null ? "" : value); }

    private record BalanceEntry(String name, long balance) {}
    private record ShopProduct(String id, Material material, String name, int amount, long price) {}

    private static final class ShopHolder implements InventoryHolder {
        private Inventory inventory;
        private final Map<Integer, ShopProduct> products = new HashMap<>();
        @Override public Inventory getInventory() { return inventory; }
    }
}
