package com.gawharasmp.coins;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Color;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.FireworkEffect;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.potion.PotionType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Independent Gawhara Coins economy and a purchase-only shop which mirrors the
 * configured EconomyShopGUI category/page layout. EconomyShopGUI/Vault remains
 * responsible for every sale, so sell transactions are always paid in dollars.
 */
public final class GawharaCoins extends JavaPlugin implements Listener, CommandExecutor {
    private static final Pattern SLOT_RANGE = Pattern.compile("^\\s*(\\d+)\\s*(?:-\\s*(\\d+)\\s*)?$");

    private File dataFile;
    private YamlConfiguration data;
    private BukkitTask autosaveTask;
    private final Map<String, ShopCategory> shopCategories = new LinkedHashMap<>();
    private int mainMenuRows = 5;
    private boolean mainMenuNavEnabled = true;
    private ItemStack mainMenuFiller;
    private ConfigurationSection mainMenuNavConfig;
    private ConfigurationSection globalShopNavConfig;

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
        loadShopCatalogue();
        getServer().getPluginManager().registerEvents(this, this);
        if (getCommand("coins") != null) getCommand("coins").setExecutor(this);
        long seconds = Math.max(15L, getConfig().getLong("settings.autosave-seconds", 60L));
        autosaveTask = Bukkit.getScheduler().runTaskTimer(this, this::saveData, seconds * 20L, seconds * 20L);
        getLogger().info("GawharaCoins enabled. GC is independent from Vault; GC shop is purchase-only and sales remain in Vault dollars.");
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
                loadShopCatalogue();
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

    /**
     * Load the exact category slots, icons, fill items, page sizes, product slots,
     * and navigation-bar configuration used by this server's EconomyShopGUI setup.
     */
    private void loadShopCatalogue() {
        shopCategories.clear();
        File pluginsFolder = getDataFolder().getParentFile();
        File economyFolder = new File(pluginsFolder, "EconomyShopGUI");
        File shopsFolder = new File(economyFolder, "shops");
        File sectionsFolder = new File(economyFolder, "sections");
        File mainConfigFile = new File(economyFolder, "config.yml");
        YamlConfiguration esgConfig = YamlConfiguration.loadConfiguration(mainConfigFile);

        mainMenuRows = clamp(esgConfig.getInt("main-menu.gui-rows", 5), 2, 6);
        mainMenuFiller = buildSimpleItem(esgConfig.getConfigurationSection("main-menu.fill-item"), null, Material.BLACK_STAINED_GLASS_PANE, 0, 1);
        if (!esgConfig.getBoolean("main-menu.fill-item.enable", true)) mainMenuFiller = null;
        mainMenuNavConfig = esgConfig.getConfigurationSection("main-menu-nav-bar");
        mainMenuNavEnabled = esgConfig.getBoolean("main-menu-nav-bar.enabled", true);
        globalShopNavConfig = esgConfig.getConfigurationSection("shops-nav-bar");

        if (!getConfig().getBoolean("shop.import-economyshopgui", true)) {
            loadConfiguredProducts();
            return;
        }

        File[] files = shopsFolder.listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".yml"));
        if (files == null || files.length == 0) {
            getLogger().warning("EconomyShopGUI shop files were not found; using the manual products in GawharaCoins/config.yml instead.");
            loadConfiguredProducts();
            return;
        }

        Arrays.sort(files, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
        double multiplier = getConfig().getDouble("shop.price-multiplier", 0.10D);
        if (!Double.isFinite(multiplier) || multiplier <= 0D) multiplier = 0.10D;
        int imported = 0;
        int skippedMaterials = 0;

        for (File shopFile : files) {
            String categoryId = shopFile.getName().substring(0, shopFile.getName().length() - 4);
            File sectionFile = new File(sectionsFolder, shopFile.getName());
            YamlConfiguration sectionConfig = YamlConfiguration.loadConfiguration(sectionFile);
            if (!sectionConfig.getBoolean("enable", true) || sectionConfig.getBoolean("hidden", false)) continue;

            ConfigurationSection sectionItem = sectionConfig.getConfigurationSection("item");
            if (sectionItem == null) continue;
            int categorySlot = sectionConfig.getInt("slot", -1);
            if (categorySlot < 0 || categorySlot >= mainMenuRows * 9) {
                getLogger().warning("Skipping category " + categoryId + " because its section slot is outside the configured main menu.");
                continue;
            }

            String displayName = sectionItem.getString("displayname", sectionItem.getString("name", prettyLabel(categoryId)));
            String categoryTitle = sectionConfig.getString("title", "");
            if (categoryTitle == null || categoryTitle.isBlank()) {
                categoryTitle = sectionItem.getString("name", displayName);
                if (categoryTitle == null || categoryTitle.isBlank()) categoryTitle = displayName;
            }
            ItemStack categoryIcon = buildConfiguredItem(sectionItem, null, true, Material.CHEST);
            ConfigurationSection sectionFill = sectionConfig.getConfigurationSection("fill-item");

            String navMode = sectionConfig.getString("nav-bar.mode", "INHERIT");
            boolean sectionNavEnabled;
            ConfigurationSection sectionNavConfig;
            if ("DISABLED".equalsIgnoreCase(navMode)) {
                sectionNavEnabled = false;
                sectionNavConfig = null;
            } else if ("SELF".equalsIgnoreCase(navMode)) {
                sectionNavConfig = sectionConfig.getConfigurationSection("nav-bar");
                sectionNavEnabled = sectionNavConfig != null && sectionNavConfig.getBoolean("enabled", true);
            } else {
                sectionNavConfig = globalShopNavConfig;
                sectionNavEnabled = esgConfig.getBoolean("shops-nav-bar.enabled", true);
            }

            ShopCategory category = new ShopCategory(categoryId, categoryTitle, categorySlot, categoryIcon);
            YamlConfiguration shopConfig = YamlConfiguration.loadConfiguration(shopFile);
            ConfigurationSection pages = shopConfig.getConfigurationSection("pages");
            if (pages == null) continue;

            List<String> pageKeys = new ArrayList<>(pages.getKeys(false));
            pageKeys.sort(Comparator.naturalOrder());
            for (String pageKey : pageKeys) {
                ConfigurationSection pageConfig = pages.getConfigurationSection(pageKey);
                if (pageConfig == null) continue;
                int pageRows = clamp(pageConfig.getInt("gui-rows", 6), 2, 6);
                String pageTitle = pageConfig.getString("title", "");
                if (pageTitle == null || pageTitle.isBlank()) pageTitle = categoryTitle;
                int contentSize = pageRows * 9 - (sectionNavEnabled ? 9 : 0);
                ItemStack pageFiller = buildSimpleItem(sectionFill, null, Material.AIR, 0, 1);
                if (pageFiller == null || pageFiller.getType().isAir()) pageFiller = null;
                ShopPage page = new ShopPage(pageKey, pageTitle, pageRows, pageFiller, sectionNavEnabled, sectionNavConfig);
                ConfigurationSection items = pageConfig.getConfigurationSection("items");
                if (items != null) {
                    for (String itemKey : items.getKeys(false)) {
                        ConfigurationSection item = items.getConfigurationSection(itemKey);
                        if (item == null || item.getBoolean("hidden", false)) continue;
                        double sourcePrice = item.getDouble("gc-buy-source", item.getDouble("buy", -1D));
                        if (!Double.isFinite(sourcePrice) || sourcePrice <= 0D) continue;

                        String materialName = item.getString("material", "PAPER");
                        Material material = materialName.equalsIgnoreCase("FIREWORK") ? Material.FIREWORK_ROCKET : Material.matchMaterial(materialName);
                        if (material == null || material.isAir()) { skippedMaterials++; continue; }

                        double rawPrice = sourcePrice * multiplier;
                        if (!Double.isFinite(rawPrice) || rawPrice > Long.MAX_VALUE) continue;
                        long gcPrice = Math.max(1L, (long) Math.ceil(rawPrice));
                        int amount = clamp(item.getInt("stack-size", 1), 1, material.getMaxStackSize());
                        String actualName = item.getString("name", "");
                        String shownName = item.getString("displayname", actualName.isBlank() ? prettyMaterialName(material) : actualName);
                        List<String> actualLore = new ArrayList<>(item.getStringList("lore"));
                        List<String> displayLore = new ArrayList<>(item.getStringList("displaylore"));
                        if (displayLore.isEmpty()) displayLore = new ArrayList<>(actualLore);
                        ShopProduct product = new ShopProduct(categoryId + "/" + pageKey + "/" + itemKey,
                                material, actualName, shownName, amount, gcPrice, item.getDouble("sell", -1D),
                                actualLore, displayLore,
                                new ArrayList<>(item.getStringList("potiontypes")),
                                new ArrayList<>(item.getStringList("enchantments")),
                                item.getInt("duration", 1),
                                new ArrayList<>(item.getStringList("colors")),
                                new ArrayList<>(item.getStringList("fade-colors")),
                                item.getBoolean("flicker", false), item.getBoolean("trail", false),
                                item.getString("shape", "BURST"), item.getString("skullowner", ""),
                                item.getString("armorcolor", ""), item.getBoolean("enchantment-glint", false));

                        List<Integer> slots = parseSlots(item.get("slot"));
                        if (slots.isEmpty()) {
                            int next = page.nextFreeSlot(contentSize);
                            if (next >= 0) page.productSlots.put(next, product);
                        } else {
                            for (int slot : slots) {
                                if (slot < 0 || slot >= contentSize) continue;
                                if (!page.productSlots.containsKey(slot)) page.productSlots.put(slot, product);
                            }
                        }
                        imported++;
                    }
                }
                category.pages.add(page);
            }

            if (!category.pages.isEmpty()) shopCategories.put(categoryId, category);
        }

        if (imported == 0 || shopCategories.isEmpty()) {
            getLogger().warning("No GC shop products could be imported; using the manual products in GawharaCoins/config.yml instead.");
            loadConfiguredProducts();
        } else {
            getLogger().info("Imported " + imported + " GC shop products using EconomyShopGUI's category positions, item slots, page sizes, and navigation layout in " + shopCategories.size() + " categories.");
            if (skippedMaterials > 0) getLogger().warning("Skipped " + skippedMaterials + " products with unsupported item materials.");
        }
    }

    private List<Integer> parseSlots(Object raw) {
        List<Integer> slots = new ArrayList<>();
        if (raw == null) return slots;
        String input = String.valueOf(raw).trim();
        if (input.isEmpty()) return slots;
        for (String token : input.split(",")) {
            Matcher matcher = SLOT_RANGE.matcher(token);
            if (!matcher.matches()) continue;
            int first;
            int last;
            try {
                first = Integer.parseInt(matcher.group(1));
                last = matcher.group(2) == null ? first : Integer.parseInt(matcher.group(2));
            } catch (NumberFormatException ignored) { continue; }
            if (last < first) { int swap = first; first = last; last = swap; }
            for (int slot = first; slot <= last && slot - first < 54; slot++) slots.add(slot);
        }
        return slots;
    }

    private void loadConfiguredProducts() {
        ConfigurationSection products = getConfig().getConfigurationSection("shop.items");
        if (products == null) return;
        ShopCategory category = new ShopCategory("gawharacoins", "&bGawhara Coins", 11,
                makeItem(Material.GOLD_INGOT, "&bGawhara Coins", List.of("&7Browse GC products")));
        ShopPage page = new ShopPage("page1", "&bGawhara Coins", 6, null, true, globalShopNavConfig);
        for (String id : products.getKeys(false)) {
            ConfigurationSection product = products.getConfigurationSection(id);
            if (product == null) continue;
            Material material = Material.matchMaterial(product.getString("material", "PAPER"));
            if (material == null || material.isAir()) continue;
            long price = product.getLong("price", 1L);
            if (price <= 0) continue;
            int amount = clamp(product.getInt("amount", 1), 1, material.getMaxStackSize());
            String name = product.getString("name", id);
            ShopProduct shopProduct = new ShopProduct(id, material, name, name, amount, price,
                    product.getDouble("sell-price", -1D), new ArrayList<>(product.getStringList("lore")),
                    new ArrayList<>(product.getStringList("lore")), List.of(), List.of(), 1,
                    List.of(), List.of(), false, false, "BURST", "", "", false);
            int next = page.nextFreeSlot(45);
            if (next >= 0) page.productSlots.put(next, shopProduct);
        }
        if (!page.productSlots.isEmpty()) {
            category.pages.add(page);
            shopCategories.put(category.id, category);
        }
    }

    private void openShop(Player player) {
        if (!getConfig().getBoolean("shop.enabled", true)) {
            player.sendMessage(color("&cThe GC shop is disabled."));
            return;
        }
        if (shopCategories.isEmpty()) loadShopCatalogue();
        if (shopCategories.isEmpty()) { send(player, "shop-empty"); return; }

        int rows = mainMenuRows + (mainMenuNavEnabled ? 1 : 0);
        rows = clamp(rows, 1, 6);
        String title = color(getConfig().getString("shop.title", "&8Shop"));
        ShopHolder holder = new ShopHolder(ShopView.CATEGORIES);
        Inventory inventory = Bukkit.createInventory(holder, rows * 9, title);
        holder.inventory = inventory;
        fillTop(inventory, mainMenuRows * 9, mainMenuFiller);

        for (ShopCategory category : shopCategories.values()) {
            if (category.slot >= 0 && category.slot < mainMenuRows * 9) {
                inventory.setItem(category.slot, category.icon.clone());
                holder.categorySlots.put(category.slot, category.id);
            }
        }
        if (mainMenuNavEnabled) renderNavBar(player, inventory, holder, mainMenuNavConfig, 0, 1, true);
        player.openInventory(inventory);
        send(player, "shop-open");
    }

    private void openCategory(Player player, String categoryId, int pageIndex) {
        ShopCategory category = shopCategories.get(categoryId);
        if (category == null || category.pages.isEmpty()) { openShop(player); return; }
        int safePage = clamp(pageIndex, 0, category.pages.size() - 1);
        ShopPage page = category.pages.get(safePage);
        String title = color(page.title);
        ShopHolder holder = new ShopHolder(ShopView.PRODUCTS);
        holder.categoryId = categoryId;
        holder.page = safePage;
        holder.contentSize = page.rows * 9 - (page.navEnabled ? 9 : 0);
        Inventory inventory = Bukkit.createInventory(holder, page.rows * 9, title);
        holder.inventory = inventory;
        fillTop(inventory, holder.contentSize, page.filler);

        for (Map.Entry<Integer, ShopProduct> entry : page.productSlots.entrySet()) {
            int slot = entry.getKey();
            if (slot < 0 || slot >= holder.contentSize) continue;
            ShopProduct product = entry.getValue();
            inventory.setItem(slot, createProductDisplay(product));
            holder.products.put(slot, product);
        }
        if (page.navEnabled) renderNavBar(player, inventory, holder, page.navConfig, safePage, category.pages.size(), false);
        player.openInventory(inventory);
    }

    private void renderNavBar(Player player, Inventory inventory, ShopHolder holder,
                              ConfigurationSection navConfig, int currentPage, int pageCount, boolean mainMenu) {
        if (navConfig == null) return;
        ConfigurationSection navItems = navConfig.getConfigurationSection("items");
        if (navItems == null) return;
        ConfigurationSection navFill = navConfig.getConfigurationSection("fill-item");
        ItemStack fill = buildSimpleItem(navFill, player, Material.AIR, currentPage, pageCount);
        if (fill != null && !fill.getType().isAir()) {
            for (int slot = inventory.getSize() - 9; slot < inventory.getSize(); slot++) inventory.setItem(slot, fill.clone());
        }

        for (String itemKey : navItems.getKeys(false)) {
            ConfigurationSection itemConfig = navItems.getConfigurationSection(itemKey);
            if (itemConfig == null) continue;
            int navSlot;
            try { navSlot = itemConfig.getInt("slot", Integer.parseInt(itemKey)); }
            catch (NumberFormatException ex) { continue; }
            int slot = inventory.getSize() - 9 + navSlot - 1;
            if (navSlot < 1 || navSlot > 9 || slot < 0 || slot >= inventory.getSize()) continue;

            ConfigurationSection renderConfig = itemConfig;
            ConfigurationSection paginated = itemConfig.getConfigurationSection("paginated");
            if (paginated != null && paginated.getBoolean("enabled", false) && pageCount <= 1) {
                ConfigurationSection orElse = paginated.getConfigurationSection("orElse");
                if (orElse != null) renderConfig = orElse;
            }
            ItemStack navItem = buildSimpleItem(renderConfig, player, Material.AIR, currentPage, pageCount);
            if (navItem != null && !navItem.getType().isAir()) inventory.setItem(slot, navItem);
            else inventory.setItem(slot, null);

            String action = itemConfig.getString("action", "").toUpperCase(Locale.ROOT);
            if (!action.isBlank()) holder.navActions.put(slot, action);
        }
    }

    private void fillTop(Inventory inventory, int contentSize, ItemStack filler) {
        if (filler == null || filler.getType().isAir()) return;
        for (int slot = 0; slot < Math.min(contentSize, inventory.getSize()); slot++) inventory.setItem(slot, filler.clone());
    }

    private ItemStack createProductDisplay(ShopProduct product) {
        ItemStack display = createProductStack(product, true);
        ItemMeta meta = display.getItemMeta();
        if (meta != null) {
            List<String> lore = meta.hasLore() && meta.getLore() != null ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
            lore.add(color("&8&m--------------------"));
            lore.add(color("&aBuy: &e" + product.price + " GC"));
            if (product.sellPrice >= 0D) lore.add(color("&6Sell: &f$" + formatMoney(product.sellPrice)));
            else lore.add(color("&7Sell: &cNot available"));
            lore.add(color("&8"));
            lore.add(color("&eLeft-click to buy with GC"));
            meta.setLore(lore);
            display.setItemMeta(meta);
        }
        return display;
    }

    private ItemStack createProductStack(ShopProduct product, boolean shopDisplay) {
        ItemStack stack = new ItemStack(product.material, product.amount);
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return stack;
        String shownName = shopDisplay ? product.displayName : product.name;
        if (shownName != null && !shownName.isBlank()) meta.setDisplayName(color(shownName));
        List<String> loreSource = shopDisplay ? product.displayLore : product.itemLore;
        if (loreSource != null && !loreSource.isEmpty()) {
            List<String> lore = new ArrayList<>();
            for (String line : loreSource) lore.add(color(line));
            meta.setLore(lore);
        }
        applyPotionData(meta, product.potionTypes, product.id);
        applyEnchantments(meta, product.enchantments, product.id);
        applyFireworkData(meta, product);
        applyArmorColor(meta, product.armorColor, product.id);
        if (product.skullOwner != null && !product.skullOwner.isBlank() && meta instanceof SkullMeta skullMeta) {
            OfflinePlayer owner = Bukkit.getOfflinePlayer(product.skullOwner);
            skullMeta.setOwningPlayer(owner);
        }
        if (product.enchantmentGlint && product.enchantments.isEmpty()) {
            Enchantment glint = Registry.ENCHANTMENT.get(NamespacedKey.minecraft("unbreaking"));
            if (glint != null) meta.addEnchant(glint, 1, true);
        }
        stack.setItemMeta(meta);
        return stack;
    }

    private ItemStack buildSimpleItem(ConfigurationSection config, Player player, Material fallback, int currentPage, int pageCount) {
        if (config == null) return fallback == null ? null : makeItem(fallback, "&r", List.of());
        String materialName = config.getString("material", fallback == null ? "AIR" : fallback.name());
        Material material = Material.matchMaterial(materialName);
        if (material == null) material = fallback == null ? Material.AIR : fallback;
        if (material.isAir()) return new ItemStack(Material.AIR);
        int amount = clamp(config.getInt("stack-size", 1), 1, material.getMaxStackSize());
        ItemStack item = new ItemStack(material, amount);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        String name = config.getString("displayname", config.getString("name", ""));
        if (name != null && !name.isBlank()) meta.setDisplayName(color(applyPlaceholders(name, player, currentPage, pageCount)));
        List<String> loreSource = config.getStringList("lore");
        if (!loreSource.isEmpty()) {
            List<String> lore = new ArrayList<>();
            for (String line : loreSource) lore.add(color(applyPlaceholders(line, player, currentPage, pageCount)));
            meta.setLore(lore);
        }
        if (meta instanceof SkullMeta skullMeta && player != null) {
            String ownerName = config.getString("skullowner", "");
            if (ownerName.contains("%player_name%")) skullMeta.setOwningPlayer(player);
            else if (!ownerName.isBlank()) skullMeta.setOwningPlayer(Bukkit.getOfflinePlayer(ownerName));
        }
        applyEnchantments(meta, config.getStringList("enchantments"), "navigation item");
        String armorColor = config.getString("armorcolor", "");
        applyArmorColor(meta, armorColor, "navigation item");
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack buildConfiguredItem(ConfigurationSection config, Player player, boolean shopDisplay, Material fallback) {
        if (config == null) return makeItem(fallback, "&r", List.of());
        String materialName = config.getString("material", fallback.name());
        Material material = Material.matchMaterial(materialName);
        if (material == null || material.isAir()) material = fallback;
        int amount = clamp(config.getInt("stack-size", 1), 1, material.getMaxStackSize());
        ItemStack item = new ItemStack(material, amount);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        String name = shopDisplay ? config.getString("displayname", config.getString("name", "")) : config.getString("name", "");
        if (name != null && !name.isBlank()) meta.setDisplayName(color(name));
        List<String> loreSource = shopDisplay ? config.getStringList("displaylore") : config.getStringList("lore");
        if (loreSource.isEmpty() && shopDisplay) loreSource = config.getStringList("lore");
        if (!loreSource.isEmpty()) {
            List<String> lore = new ArrayList<>();
            for (String line : loreSource) lore.add(color(line));
            meta.setLore(lore);
        }
        applyEnchantments(meta, config.getStringList("enchantments"), "section icon");
        applyArmorColor(meta, config.getString("armorcolor", ""), "section icon");
        if (meta instanceof SkullMeta skullMeta) {
            String owner = config.getString("skullowner", "");
            if (!owner.isBlank()) skullMeta.setOwningPlayer(Bukkit.getOfflinePlayer(owner));
        }
        item.setItemMeta(meta);
        return item;
    }

    private void applyPotionData(ItemMeta meta, List<String> potionTypes, String itemId) {
        if (!(meta instanceof PotionMeta potionMeta) || potionTypes == null || potionTypes.isEmpty()) return;
        String potionName = potionTypes.get(0).trim().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        try { potionMeta.setBasePotionType(PotionType.valueOf(potionName)); }
        catch (IllegalArgumentException ignored) { getLogger().warning("Unknown potion type '" + potionName + "' in GC shop item " + itemId); }
    }

    private void applyEnchantments(ItemMeta meta, List<String> rawEnchantments, String itemId) {
        if (rawEnchantments == null) return;
        for (String raw : rawEnchantments) {
            String[] parts = raw.split(":", 2);
            String enchantmentName = normalizeEnchantmentName(parts[0]);
            int level = 1;
            if (parts.length > 1) {
                try { level = Math.max(1, Integer.parseInt(parts[1])); }
                catch (NumberFormatException ignored) { level = 1; }
            }
            Enchantment enchantment = Registry.ENCHANTMENT.get(NamespacedKey.minecraft(enchantmentName.toLowerCase(Locale.ROOT)));
            if (enchantment == null) {
                getLogger().warning("Unknown enchantment '" + parts[0] + "' in GC shop item " + itemId);
                continue;
            }
            if (meta instanceof EnchantmentStorageMeta bookMeta) bookMeta.addStoredEnchant(enchantment, level, true);
            else meta.addEnchant(enchantment, level, true);
        }
    }

    private void applyFireworkData(ItemMeta meta, ShopProduct product) {
        if (!(meta instanceof FireworkMeta fireworkMeta)) return;
        try {
            FireworkEffect.Type type = FireworkEffect.Type.valueOf(product.fireworkShape.toUpperCase(Locale.ROOT));
            FireworkEffect.Builder builder = FireworkEffect.builder().with(type)
                    .flicker(product.fireworkFlicker).trail(product.fireworkTrail);
            for (String shade : product.fireworkColors) {
                Color color = namedColor(shade);
                if (color != null) builder.withColor(color);
            }
            for (String shade : product.fireworkFadeColors) {
                Color color = namedColor(shade);
                if (color != null) builder.withFade(color);
            }
            fireworkMeta.addEffect(builder.build());
            fireworkMeta.setPower(clamp(product.fireworkPower, 1, 3));
        } catch (IllegalArgumentException ignored) {
            getLogger().warning("Unknown firework shape '" + product.fireworkShape + "' in GC shop item " + product.id);
        }
    }

    private void applyArmorColor(ItemMeta meta, String rawColor, String itemId) {
        if (!(meta instanceof LeatherArmorMeta leatherMeta) || rawColor == null || rawColor.isBlank()) return;
        try {
            String value = rawColor.startsWith("#") ? rawColor.substring(1) : rawColor;
            leatherMeta.setColor(Color.fromRGB(Integer.parseInt(value, 16)));
        } catch (NumberFormatException ex) {
            getLogger().warning("Invalid leather armor color '" + rawColor + "' in " + itemId);
        }
    }

    private String normalizeEnchantmentName(String raw) {
        String value = raw.trim().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        return switch (value) {
            case "WATER_WORKER" -> "AQUA_AFFINITY";
            case "DAMAGE_ARTHROPODS" -> "BANE_OF_ARTHROPODS";
            case "PROTECTION_EXPLOSIONS" -> "BLAST_PROTECTION";
            case "DIG_SPEED" -> "EFFICIENCY";
            case "PROTECTION_FALL" -> "FEATHER_FALLING";
            case "PROTECTION_FIRE" -> "FIRE_PROTECTION";
            case "ARROW_FIRE" -> "FLAME";
            case "LOOT_BONUS_BLOCKS" -> "FORTUNE";
            case "ARROW_INFINITE" -> "INFINITY";
            case "LOOT_BONUS_MOBS" -> "LOOTING";
            case "OXYGEN" -> "RESPIRATION";
            case "DAMAGE_ALL" -> "SHARPNESS";
            case "DAMAGE_UNDEAD" -> "SMITE";
            case "ARROW_DAMAGE" -> "POWER";
            case "ARROW_KNOCKBACK" -> "PUNCH";
            case "PROTECTION_ENVIRONMENTAL" -> "PROTECTION";
            default -> value;
        };
    }

    private Color namedColor(String name) {
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "black" -> Color.BLACK;
            case "blue" -> Color.BLUE;
            case "cyan" -> Color.AQUA;
            case "gray", "grey" -> Color.GRAY;
            case "green" -> Color.GREEN;
            case "lime" -> Color.LIME;
            case "magenta" -> Color.FUCHSIA;
            case "orange" -> Color.ORANGE;
            case "purple" -> Color.PURPLE;
            case "red" -> Color.RED;
            case "white" -> Color.WHITE;
            case "yellow" -> Color.YELLOW;
            default -> null;
        };
    }

    private String prettyMaterialName(Material material) { return prettyLabel(material.name()); }

    private String prettyLabel(String raw) {
        String[] parts = raw.toLowerCase(Locale.ROOT).replace(':', ' ').replace('_', ' ').split("\\s+");
        StringBuilder result = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) continue;
            if (result.length() > 0) result.append(' ');
            result.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return result.toString();
    }

    private String applyPlaceholders(String raw, Player player, int currentPage, int pageCount) {
        String value = raw == null ? "" : raw;
        value = value.replace("%translations-money%", "GC")
                .replace("%translations-previous-page%", "Previous page")
                .replace("%translations-current-page%", "Page " + (currentPage + 1) + "/" + Math.max(1, pageCount))
                .replace("%translations-next-page%", "Next page")
                .replace("%translations-back%", "Back")
                .replace("%translations-cancel%", "Close");
        if (player != null) {
            value = value.replace("%player_name%", player.getName())
                    .replace("%player_displayname%", player.getDisplayName())
                    .replace("%player_balance%", String.valueOf(getBalance(player.getUniqueId())))
                    .replace("%player_level%", String.valueOf(player.getLevel()));
        }
        return value;
    }

    private String formatMoney(double amount) {
        if (Math.abs(amount - Math.rint(amount)) < 0.000001D) return String.format(Locale.US, "%,d", (long) Math.rint(amount));
        return String.format(Locale.US, "%,.2f", amount).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    private void purchaseProduct(Player player, ShopProduct product) {
        if (getBalance(player.getUniqueId()) < product.price) { send(player, "insufficient"); return; }
        ItemStack purchaseStack = createProductStack(product, false);
        Inventory simulation = Bukkit.createInventory(null, 36);
        simulation.setContents(player.getInventory().getStorageContents());
        if (!simulation.addItem(purchaseStack.clone()).isEmpty()) { send(player, "shop-full"); return; }
        if (!withdrawCoins(player.getUniqueId(), product.price, "GC Shop purchase: " + product.id)) { send(player, "insufficient"); return; }
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(purchaseStack);
        if (!leftover.isEmpty()) {
            depositCoins(player.getUniqueId(), product.price, "Refund: inventory changed during purchase");
            send(player, "shop-full");
            return;
        }
        send(player, "bought", "%item%", product.displayName, "%price%", String.valueOf(product.price));
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

    private int clamp(int value, int minimum, int maximum) { return Math.max(minimum, Math.min(maximum, value)); }

    @EventHandler
    public void onShopClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof ShopHolder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || event.getClickedInventory() != event.getView().getTopInventory()) return;
        int slot = event.getRawSlot();
        if (event.getClick() != ClickType.LEFT) return;

        String navAction = holder.navActions.get(slot);
        if (navAction != null) {
            switch (navAction) {
                case "PAGE_BACK" -> {
                    if (holder.view == ShopView.PRODUCTS && holder.page > 0) openCategory(player, holder.categoryId, holder.page - 1);
                }
                case "PAGE_NEXT" -> {
                    if (holder.view == ShopView.PRODUCTS) openCategory(player, holder.categoryId, holder.page + 1);
                }
                case "BACK" -> {
                    if (holder.view == ShopView.PRODUCTS) openShop(player);
                    else player.closeInventory();
                }
                case "CLOSE" -> player.closeInventory();
                default -> { }
            }
            return;
        }

        if (holder.view == ShopView.CATEGORIES) {
            String categoryId = holder.categorySlots.get(slot);
            if (categoryId != null) openCategory(player, categoryId, 0);
            return;
        }

        ShopProduct product = holder.products.get(slot);
        if (product != null) purchaseProduct(player, product);
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
    private record ShopProduct(String id, Material material, String name, String displayName, int amount,
                               long price, double sellPrice, List<String> itemLore, List<String> displayLore,
                               List<String> potionTypes, List<String> enchantments, int fireworkPower,
                               List<String> fireworkColors, List<String> fireworkFadeColors,
                               boolean fireworkFlicker, boolean fireworkTrail, String fireworkShape,
                               String skullOwner, String armorColor, boolean enchantmentGlint) {}

    private static final class ShopCategory {
        private final String id;
        private final String title;
        private final int slot;
        private final ItemStack icon;
        private final List<ShopPage> pages = new ArrayList<>();
        private ShopCategory(String id, String title, int slot, ItemStack icon) {
            this.id = id; this.title = title; this.slot = slot; this.icon = icon;
        }
    }

    private static final class ShopPage {
        private final String id;
        private final String title;
        private final int rows;
        private final ItemStack filler;
        private final boolean navEnabled;
        private final ConfigurationSection navConfig;
        private final Map<Integer, ShopProduct> productSlots = new TreeMap<>();
        private ShopPage(String id, String title, int rows, ItemStack filler, boolean navEnabled, ConfigurationSection navConfig) {
            this.id = id; this.title = title; this.rows = rows; this.filler = filler;
            this.navEnabled = navEnabled; this.navConfig = navConfig;
        }
        private int nextFreeSlot(int contentSize) {
            for (int slot = 0; slot < contentSize; slot++) if (!productSlots.containsKey(slot)) return slot;
            return -1;
        }
    }

    private enum ShopView { CATEGORIES, PRODUCTS }

    private static final class ShopHolder implements InventoryHolder {
        private final ShopView view;
        private Inventory inventory;
        private String categoryId;
        private int page;
        private int contentSize;
        private final Map<Integer, String> categorySlots = new HashMap<>();
        private final Map<Integer, ShopProduct> products = new HashMap<>();
        private final Map<Integer, String> navActions = new HashMap<>();
        private ShopHolder(ShopView view) { this.view = view; }
        @Override public Inventory getInventory() { return inventory; }
    }
}
