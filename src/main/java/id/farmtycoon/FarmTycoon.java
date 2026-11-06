package id.farmtycoon;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.Farmland;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class FarmTycoon extends JavaPlugin implements Listener {

    private static final int Y = 64;          // tinggi permukaan pulau
    private static final int SPACING = 256;   // jarak antar pulau
    private static final int MAX_LEVEL = 6;   // level maksimal

    // Urutan petak lahan (tile 7x7). Tiap level membuka 1 petak baru.
    private static final int[][] TILES = {{0, 0}, {0, 1}, {0, -1}, {1, 0}, {1, 1}, {1, -1}};

    private static final Material[] FLOWERS = {
            Material.DANDELION, Material.POPPY, Material.CORNFLOWER,
            Material.AZURE_BLUET, Material.OXEYE_DAISY};

    private File dataFile;
    private YamlConfiguration data;
    private final Map<Material, Double> prices = new HashMap<>();
    private NamespacedKey npcKey;
    private final Set<String> farmWorlds = new HashSet<>(); // nama world pulau (huruf kecil)

    /** Generator dunia kosong (void) */
    public static class VoidGen extends ChunkGenerator {
        @Override
        public Location getFixedSpawnLocation(World w, Random r) {
            return new Location(w, 0.5, Y + 1, 0.5);
        }
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        npcKey = new NamespacedKey(this, "farmer_npc");
        loadPrices();
        dataFile = new File(getDataFolder(), "data.yml");
        data = YamlConfiguration.loadConfiguration(dataFile);
        for (String n : data.getStringList("worlds")) farmWorlds.add(n.toLowerCase());
        getServer().getPluginManager().registerEvents(this, this);

        // Kalau pemain jatuh ke void, balikin ke pulaunya
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (isFarmWorld(p.getWorld()) && hasIsland(p) && p.getLocation().getY() < Y - 20) {
                    p.teleport(home(p));
                }
            }
        }, 20L, 20L);
    }

    @Override
    public void onDisable() {
        if (data != null) saveData();
    }

    // ---------- Data ----------

    private void loadPrices() {
        prices.clear();
        ConfigurationSection sec = getConfig().getConfigurationSection("prices");
        if (sec == null) return;
        for (String key : sec.getKeys(false)) {
            Material m = Material.matchMaterial(key);
            if (m != null) prices.put(m, sec.getDouble(key));
        }
    }

    private void saveData() {
        try { data.save(dataFile); } catch (IOException e) { e.printStackTrace(); }
    }

    private String path(Player p, String key) { return "players." + p.getUniqueId() + "." + key; }
    private boolean hasIsland(Player p) { return data.contains(path(p, "world")); }
    private boolean isFarmWorld(World w) { return farmWorlds.contains(w.getName().toLowerCase()); }
    private int level(Player p) { return data.getInt(path(p, "level"), 1); }
    private double money(Player p) { return data.getDouble(path(p, "money"), 0); }
    private void setMoney(Player p, double v) { data.set(path(p, "money"), v); }

    // ---------- Pulau ----------

    /** Setiap pemain punya world sendiri, namanya sesuai nama pemain. */
    private World loadWorld(String name) {
        World w = Bukkit.getWorld(name);
        if (w == null) {
            w = new WorldCreator(name).generator(new VoidGen()).createWorld();
        }
        return w;
    }

    private Location home(Player p) {
        World w = loadWorld(data.getString(path(p, "world")));
        return new Location(w, 0.5, Y + 1, 0.5);
    }

    /** Nama world = nama pemain (karakter aneh diganti _, kalau bentrok ditambah angka). */
    private String pickWorldName(Player p) {
        String base = p.getName().replaceAll("[^A-Za-z0-9_]", "_");
        String name = base;
        int n = 2;
        while (farmWorlds.contains(name.toLowerCase())
                || Bukkit.getWorld(name) != null
                || new File(Bukkit.getWorldContainer(), name).exists()) {
            name = base + "_" + n++;
        }
        return name;
    }

    private void createIsland(Player p) {
        String name = pickWorldName(p);
        farmWorlds.add(name.toLowerCase());
        data.set("worlds", new ArrayList<>(farmWorlds));
        data.set(path(p, "world"), name);
        data.set(path(p, "level"), 1);
        data.set(path(p, "money"), 0.0);
        World w = loadWorld(name);
        buildIsland(w, 1, true);
        saveData();

        p.getInventory().addItem(
                new ItemStack(Material.WHEAT_SEEDS, 16),
                new ItemStack(Material.CARROT, 8),
                new ItemStack(Material.POTATO, 8),
                new ItemStack(Material.WOODEN_HOE),
                new ItemStack(Material.BONE_MEAL, 8));
    }

    /** Angka acak yang selalu sama untuk koordinat yang sama (supaya semua pulau identik). */
    private double noise(int x, int z, int salt) {
        long seed = (x * 73856093L) ^ (z * 19349663L) ^ (salt * 83492791L);
        Random r = new Random(seed);
        r.nextDouble();
        return r.nextDouble();
    }

    /** Radius pulau, cukup untuk menampung semua petak lahan yang sudah terbuka. */
    private double islandRadius(int level) {
        double m = 0;
        for (int i = 0; i < level && i < TILES.length; i++) {
            int x0 = 3 + 7 * TILES[i][0];
            int z0 = -3 + 7 * TILES[i][1];
            int fx = Math.max(Math.abs(x0), Math.abs(x0 + 6));
            int fz = Math.max(Math.abs(z0), Math.abs(z0 + 6));
            m = Math.max(m, Math.hypot(fx, fz));
        }
        return Math.max(11, m + 2);
    }

    /**
     * Bangun pulau melayang.
     * fresh = true  : pulau baru (bangun semuanya + dekorasi)
     * fresh = false : upgrade (hanya menambah tanah baru & petak lahan baru, tanaman aman)
     */
    private void buildIsland(World world, int level, boolean fresh) {
        int cx = 0, cz = 0;
        double R = islandRadius(level);
        int max = (int) Math.ceil(R) + 2;

        for (int dx = -max; dx <= max; dx++) {
            for (int dz = -max; dz <= max; dz++) {
                double dist = Math.hypot(dx, dz);
                double edge = R + (noise(dx, dz, 1) - 0.5) * 2.0;
                if (dist > edge) continue;

                // kerucut terbalik di bawah pulau
                int depth = (int) Math.min(14, Math.round((edge - dist) * 0.8) + 2);
                depth += (int) (noise(dx, dz, 2) * 3) - 1;
                depth = Math.max(depth, 2);

                for (int d = 0; d <= depth; d++) {
                    Block b = world.getBlockAt(cx + dx, Y - d, cz + dz);
                    if (!fresh && b.getType() != Material.AIR) continue;
                    Material m;
                    if (d == 0) m = Material.GRASS_BLOCK;
                    else if (d <= 2) m = Material.DIRT;
                    else {
                        double r = noise(dx * 7 + d, dz * 3 + d, 3);
                        m = r < 0.2 ? Material.ANDESITE : r < 0.3 ? Material.COBBLESTONE : Material.STONE;
                    }
                    b.setType(m, false);
                }
            }
        }

        if (fresh) decorate(world, cx, cz);
        buildTile(world, cx, cz, level - 1);
    }

    /** Satu petak lahan 7x7: tengahnya air, sisanya farmland basah. */
    private void buildTile(World world, int cx, int cz, int t) {
        if (t < 0 || t >= TILES.length) return;
        int x0 = cx + 3 + 7 * TILES[t][0];
        int z0 = cz - 3 + 7 * TILES[t][1];
        for (int i = 0; i < 7; i++) {
            for (int j = 0; j < 7; j++) {
                Block top = world.getBlockAt(x0 + i, Y, z0 + j);
                world.getBlockAt(x0 + i, Y - 1, z0 + j).setType(Material.DIRT, false);
                world.getBlockAt(x0 + i, Y + 1, z0 + j).setType(Material.AIR, false);
                if (i == 3 && j == 3) {
                    top.setType(Material.WATER, false);
                } else {
                    top.setType(Material.FARMLAND, false);
                    Farmland f = (Farmland) top.getBlockData();
                    f.setMoisture(f.getMaximumMoisture());
                    top.setBlockData(f, false);
                }
            }
        }
    }

    /** Pohon, kolam, api unggun, rumput & bunga. Sama persis untuk setiap pulau. */
    private void decorate(World world, int cx, int cz) {
        // Pohon besar
        int tx = cx - 6, tz = cz - 5;
        world.getBlockAt(tx, Y, tz).setType(Material.DIRT, false);
        for (int h = 1; h <= 6; h++) world.getBlockAt(tx, Y + h, tz).setType(Material.OAK_LOG, false);
        for (int ax = -3; ax <= 3; ax++) {
            for (int ay = -2; ay <= 2; ay++) {
                for (int az = -3; az <= 3; az++) {
                    if (ax * ax + ay * ay + az * az > 10) continue;
                    Block b = world.getBlockAt(tx + ax, Y + 6 + ay, tz + az);
                    if (b.getType() != Material.AIR) continue;
                    b.setType(Material.OAK_LEAVES, false);
                    Leaves lv = (Leaves) b.getBlockData();
                    lv.setPersistent(true);
                    b.setBlockData(lv, false);
                }
            }
        }

        // Kolam kecil
        int px = cx - 4, pz = cz + 6;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (dx * dx + dz * dz > 4) continue;
                world.getBlockAt(px + dx, Y, pz + dz).setType(Material.WATER, false);
                world.getBlockAt(px + dx, Y - 1, pz + dz).setType(Material.WATER, false);
            }
        }

        // Api unggun + jerami
        world.getBlockAt(cx - 3, Y + 1, cz + 3).setType(Material.CAMPFIRE, false);
        world.getBlockAt(cx - 4, Y + 1, cz + 3).setType(Material.HAY_BLOCK, false);
        world.getBlockAt(cx - 3, Y + 1, cz + 4).setType(Material.HAY_BLOCK, false);

        // NPC Farmer di bawah pohon
        spawnFarmer(world, cx, cz);

        // Rumput & bunga (tidak di tempat spawn dan area lahan)
        for (int dx = -13; dx <= 13; dx++) {
            for (int dz = -13; dz <= 13; dz++) {
                if (Math.abs(dx) <= 1 && Math.abs(dz) <= 1) continue;
                if (dx >= 3 && dx <= 16 && dz >= -10 && dz <= 10) continue;
                Block ground = world.getBlockAt(cx + dx, Y, cz + dz);
                if (ground.getType() != Material.GRASS_BLOCK) continue;
                Block above = world.getBlockAt(cx + dx, Y + 1, cz + dz);
                if (above.getType() != Material.AIR) continue;
                double r = noise(dx, dz, 4);
                if (r < 0.12) {
                    above.setType(Material.SHORT_GRASS, false);
                } else if (r < 0.16) {
                    int f = (int) (noise(dx, dz, 5) * FLOWERS.length);
                    above.setType(FLOWERS[Math.min(f, FLOWERS.length - 1)], false);
                }
            }
        }
    }

    /** NPC petani di bawah pohon (tidak bergerak, tidak bisa dibunuh). Klik kanan = jual panen. */
    private void spawnFarmer(World world, int cx, int cz) {
        world.getBlockAt(cx - 5, Y + 1, cz - 5).setType(Material.AIR, false);
        Location loc = new Location(world, cx - 5 + 0.5, Y + 1, cz - 5 + 0.5, -90f, 0f);
        String name = getConfig().getString("npc-name", "Farmer iaann");
        world.spawn(loc, Villager.class, v -> {
            v.setProfession(Villager.Profession.FARMER);
            v.setCustomName("§a" + name);
            v.setCustomNameVisible(true);
            v.setAI(false);
            v.setInvulnerable(true);
            v.setSilent(true);
            v.setPersistent(true);
            v.setRemoveWhenFarAway(false);
            v.getPersistentDataContainer().set(npcKey, PersistentDataType.BYTE, (byte) 1);
        });
    }

    // ---------- Event ----------

    @EventHandler
    public void onNpcClick(PlayerInteractEntityEvent e) {
        if (!(e.getRightClicked() instanceof Villager v)) return;
        if (!v.getPersistentDataContainer().has(npcKey, PersistentDataType.BYTE)) return;
        e.setCancelled(true); // jangan buka menu trading bawaan villager
        sell(e.getPlayer());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (!hasIsland(p)) createIsland(p);
        p.teleport(home(p));
    }

    // Bongkar world pemain yang sudah keluar supaya RAM hemat
    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        World w = e.getPlayer().getWorld();
        if (!isFarmWorld(w)) return;
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (w.getPlayers().isEmpty()) Bukkit.unloadWorld(w, true);
        }, 40L);
    }

    // Kalau mati, respawn di pulau sendiri
    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        if (hasIsland(p)) e.setRespawnLocation(home(p));
    }

    // ---------- Command ----------

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Hanya untuk pemain.");
            return true;
        }
        switch (cmd.getName().toLowerCase()) {
            case "farm" -> {
                if (!hasIsland(p)) createIsland(p);
                p.teleport(home(p));
                p.sendMessage("§aTeleport ke pulau farm kamu!");
            }
            case "balance" -> p.sendMessage("§eUang: §f$" + String.format("%.1f", money(p)));
            case "sell" -> sell(p);
            case "upgrade" -> upgrade(p);
        }
        return true;
    }

    private void sell(Player p) {
        double total = 0;
        ItemStack[] items = p.getInventory().getContents();
        for (int i = 0; i < items.length; i++) {
            ItemStack it = items[i];
            if (it == null) continue;
            Double price = prices.get(it.getType());
            if (price == null) continue;
            total += price * it.getAmount();
            p.getInventory().setItem(i, null);
        }
        if (total <= 0) {
            p.sendMessage("§cTidak ada hasil panen yang bisa dijual.");
            return;
        }
        setMoney(p, money(p) + total);
        saveData();
        p.sendMessage("§aTerjual! +$" + String.format("%.1f", total));
    }

    private void upgrade(Player p) {
        int lvl = level(p);
        if (lvl >= MAX_LEVEL) {
            p.sendMessage("§eLevel pulau sudah maksimal!");
            return;
        }
        double cost = getConfig().getDouble("upgrade-base-cost", 500) * lvl;
        if (money(p) < cost) {
            p.sendMessage("§cUang kurang. Upgrade ke level " + (lvl + 1) + " butuh $" + (int) cost);
            return;
        }
        setMoney(p, money(p) - cost);
        data.set(path(p, "level"), lvl + 1);
        buildIsland(loadWorld(data.getString(path(p, "world"))), lvl + 1, false);
        saveData();
        p.sendMessage("§aPulau naik ke level " + (lvl + 1) + "! Lahan baru terbuka.");
    }
}
