package id.farmtycoon;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.Farmland;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class FarmTycoon extends JavaPlugin implements Listener {

    private static final int Y = 64;          // tinggi permukaan pulau
    private static final int MAX_LEVEL = 6;   // level maksimal

    // Urutan petak lahan (tile 7x7). Tiap level membuka 1 petak baru.
    private static final int[][] TILES = {{0, 0}, {0, 1}, {0, -1}, {1, 0}, {1, 1}, {1, -1}};

    private static final Material[] FLOWERS = {
            Material.DANDELION, Material.POPPY, Material.CORNFLOWER,
            Material.AZURE_BLUET, Material.OXEYE_DAISY};

    private World lobby;
    private File dataFile;
    private YamlConfiguration data;
    private NamespacedKey npcKey;
    private final Set<String> farmWorlds = new HashSet<>(); // nama world pulau (huruf kecil)

    // Harga
    private static class Crop {
        double min, max, up, down, cur, last;
    }

    private static final String[] DEFAULT_CROPS = {
            "WHEAT:3:1.5:5:0.2:0.15", "CARROT:2:1:3.2:0.12:0.1", "POTATO:2:1:3.2:0.12:0.08",
            "BEETROOT:3:1.5:5:0.2:0.15", "MELON_SLICE:1:0.5:2:0.08:0.06", "PUMPKIN:5:2.5:8:0.3:0.25",
            "SWEET_BERRIES:2:1:3:0.1:0.08", "SUGAR_CANE:2:1:3:0.1:0.08",
            "TORCHFLOWER:8:4:12:0.5:0.4", "PITCHER_PLANT:10:5:15:0.6:0.5"};

    private final Map<Material, Crop> crops = new LinkedHashMap<>();
    private final Map<Material, Double> seedPrices = new LinkedHashMap<>();
    private final Random rng = new Random();
    private long nextChange;
    private BossBar bar;

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
        dataFile = new File(getDataFolder(), "data.yml");
        data = YamlConfiguration.loadConfiguration(dataFile);
        for (String n : data.getStringList("worlds")) farmWorlds.add(n.toLowerCase());
        loadPrices();
        loadMarket();

        lobby = loadLobby();
        getServer().getPluginManager().registerEvents(this, this);

        // Bar waktu perubahan harga di atas layar
        bar = Bukkit.createBossBar("", BarColor.YELLOW, BarStyle.SEGMENTED_10);
        for (Player p : Bukkit.getOnlinePlayers()) bar.addPlayer(p);

        // Tiap detik: cek perubahan harga + update bar
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            long now = System.currentTimeMillis();
            if (now >= nextChange) {
                rollPrices(true);
                nextChange = now + intervalMillis();
                saveMarket();
            }
            updateBar(now);
        }, 20L, 20L);

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
        if (bar != null) bar.removeAll();
        if (data != null) saveMarket();
    }

    // ---------- Lobby ----------

    private World loadLobby() {
        World w = Bukkit.getWorld("lobby");
        if (w == null) {
            w = new WorldCreator("lobby").type(WorldType.FLAT).generateStructures(false).createWorld();
        }
        if (w != null) {
            w.setDifficulty(Difficulty.PEACEFUL);
            w.setSpawnLocation(0, w.getHighestBlockYAt(0, 0) + 1, 0);
        }
        return w;
    }

    private Location lobbySpawn() {
        return new Location(lobby, 0.5, lobby.getHighestBlockYAt(0, 0) + 1, 0.5);
    }

    // ---------- Harga ----------

    private long intervalMillis() {
        return Math.max(1, getConfig().getLong("price-change.interval-minutes", 15)) * 60_000L;
    }

    private static double round2(double v) {
        return Math.round(v * 100) / 100.0;
    }

    private static String fmt(double v) {
        return String.format(Locale.US, "%.2f", v);
    }

    private void readSeeds() {
        seedPrices.clear();
        ConfigurationSection sec = getConfig().getConfigurationSection("seeds");
        if (sec != null) {
            for (String key : sec.getKeys(false)) {
                Material m = Material.matchMaterial(key);
                if (m != null) seedPrices.put(m, sec.getDouble(key));
            }
        }
        if (seedPrices.isEmpty()) {
            String[] d = {"WHEAT_SEEDS:2", "CARROT:4", "POTATO:4", "BEETROOT_SEEDS:3", "MELON_SEEDS:8",
                    "PUMPKIN_SEEDS:10", "SWEET_BERRIES:5", "SUGAR_CANE:5", "TORCHFLOWER_SEEDS:12", "PITCHER_POD:15"};
            for (String x : d) {
                String[] a = x.split(":");
                Material m = Material.matchMaterial(a[0]);
                if (m != null) seedPrices.put(m, Double.parseDouble(a[1]));
            }
        }
    }

    private void addCrop(Material m, double base, double min, double max, double up, double down) {
        Crop c = new Crop();
        c.min = min;
        c.max = max;
        // Cegah curang: harga jual tidak boleh melebihi harga beli benih yang sama
        Double sp = seedPrices.get(m);
        if (sp != null) c.max = Math.min(c.max, sp - 0.01);
        if (c.max < c.min) c.min = c.max;
        c.up = Math.max(0.01, up);
        c.down = Math.max(0.01, down);
        c.cur = round2(Math.max(c.min, Math.min(c.max, base)));
        c.last = c.cur;
        crops.put(m, c);
    }

    private void loadPrices() {
        readSeeds();
        crops.clear();
        ConfigurationSection sec = getConfig().getConfigurationSection("prices");
        if (sec != null) {
            for (String key : sec.getKeys(false)) {
                Material m = Material.matchMaterial(key);
                if (m == null) continue;
                ConfigurationSection c = sec.getConfigurationSection(key);
                double base = c != null ? c.getDouble("sell", 1) : sec.getDouble(key, 1);
                addCrop(m, base,
                        c != null ? c.getDouble("min", base * 0.5) : base * 0.5,
                        c != null ? c.getDouble("max", base * 1.6) : base * 1.6,
                        c != null ? c.getDouble("up", base * 0.08) : base * 0.08,
                        c != null ? c.getDouble("down", base * 0.08) : base * 0.08);
            }
        }
        if (crops.isEmpty()) {
            for (String d : DEFAULT_CROPS) {
                String[] a = d.split(":");
                Material m = Material.matchMaterial(a[0]);
                if (m == null) continue;
                addCrop(m, Double.parseDouble(a[1]), Double.parseDouble(a[2]), Double.parseDouble(a[3]),
                        Double.parseDouble(a[4]), Double.parseDouble(a[5]));
            }
        }
    }

    /** Ambil harga terakhir & waktu perubahan dari data.yml supaya restart tidak mereset harga. */
    private void loadMarket() {
        for (Map.Entry<Material, Crop> en : crops.entrySet()) {
            Crop c = en.getValue();
            String k = "market.prices." + en.getKey().name();
            if (data.contains(k + ".cur")) {
                c.cur = round2(Math.max(c.min, Math.min(c.max, data.getDouble(k + ".cur"))));
                c.last = round2(Math.max(c.min, Math.min(c.max, data.getDouble(k + ".last", c.cur))));
            }
        }
        long now = System.currentTimeMillis();
        nextChange = data.getLong("market.next-change", 0);
        if (nextChange == 0 || nextChange > now + intervalMillis()) nextChange = now + intervalMillis();
    }

    private void saveMarket() {
        for (Map.Entry<Material, Crop> en : crops.entrySet()) {
            String k = "market.prices." + en.getKey().name();
            data.set(k + ".cur", en.getValue().cur);
            data.set(k + ".last", en.getValue().last);
        }
        data.set("market.next-change", nextChange);
        saveData();
    }

    /** Harga naik atau turun dalam dolar (bukan kali lipat). */
    private void rollPrices(boolean announce) {
        for (Crop c : crops.values()) {
            c.last = c.cur;
            boolean up = rng.nextBoolean();
            double step = up ? c.up : c.down;
            double amt = round2(0.01 + rng.nextDouble() * Math.max(0, step - 0.01));
            double nv = c.cur + (up ? amt : -amt);
            if (nv > c.max || nv < c.min) nv = c.cur + (up ? -amt : amt); // mentok batas -> balik arah
            c.cur = round2(Math.max(c.min, Math.min(c.max, nv)));
        }
        if (announce) {
            Bukkit.broadcastMessage("§6[Pasar] §eHarga tanaman berubah! Ketik §f/price §euntuk melihat.");
        }
    }

    private void updateBar(long now) {
        long interval = intervalMillis();
        long remain = Math.max(0, nextChange - now);
        long sec = remain / 1000L;
        bar.setTitle(String.format("§6Harga tanaman berubah dalam §f%d:%02d", sec / 60, sec % 60));
        bar.setProgress(Math.max(0.0, Math.min(1.0, (double) remain / interval)));
        bar.setColor(sec < 60 ? BarColor.RED : BarColor.YELLOW);
    }

    private double sellPrice(Material m) {
        return crops.get(m).cur;
    }

    private String pretty(Material m) {
        return m.name().toLowerCase().replace('_', ' ');
    }

    // ---------- Data ----------

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

    /** Pohon, kolam, api unggun, NPC, rumput & bunga. Sama persis untuk setiap pulau. */
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

    // Awal masuk server: ke lobby (world datar), pulau dibuat lewat /farm
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (bar != null) bar.addPlayer(p);
        p.teleport(lobbySpawn());
        p.sendMessage("§aSelamat datang! Ketik §e/farm §auntuk masuk ke pulau farm kamu.");
    }

    // Bongkar world pemain yang sudah keluar supaya RAM hemat
    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        if (bar != null) bar.removePlayer(e.getPlayer());
        World w = e.getPlayer().getWorld();
        if (!isFarmWorld(w)) return;
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (w.getPlayers().isEmpty()) Bukkit.unloadWorld(w, true);
        }, 40L);
    }

    // Kalau mati di pulau, respawn di pulau; selain itu di lobby
    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        if (isFarmWorld(p.getWorld()) && hasIsland(p)) e.setRespawnLocation(home(p));
        else e.setRespawnLocation(lobbySpawn());
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
                if (!hasIsland(p)) {
                    p.sendMessage("§eMembuat pulau kamu, tunggu sebentar...");
                    createIsland(p);
                }
                p.teleport(home(p));
                p.sendMessage("§aTeleport ke pulau farm kamu!");
            }
            case "lobby" -> {
                p.teleport(lobbySpawn());
                p.sendMessage("§aKembali ke lobby.");
            }
            case "balance" -> p.sendMessage("§eUang: §f$" + String.format("%.1f", money(p)));
            case "sell" -> sell(p);
            case "upgrade" -> upgrade(p);
            case "price" -> showPrices(p);
            case "buy" -> buy(p, args);
        }
        return true;
    }

    private void showPrices(Player p) {
        p.sendMessage("§6=== Harga Jual Tanaman ===");
        for (Map.Entry<Material, Crop> en : crops.entrySet()) {
            Crop c = en.getValue();
            double d = c.cur - c.last;
            String t = d > 0.001 ? "§a▲ +$" + fmt(d) : d < -0.001 ? "§c▼ -$" + fmt(-d) : "§7- $0.00";
            p.sendMessage("§f" + pretty(en.getKey()) + ": §e$" + fmt(c.cur) + " " + t);
        }
        long sec = Math.max(0, (nextChange - System.currentTimeMillis()) / 1000L);
        p.sendMessage("§7Harga berubah lagi dalam " + (sec / 60) + ":" + String.format("%02d", sec % 60));
    }

    private void buy(Player p, String[] args) {
        if (args.length == 0) {
            p.sendMessage("§6=== Toko Benih ===");
            for (Map.Entry<Material, Double> en : seedPrices.entrySet()) {
                p.sendMessage("§f" + pretty(en.getKey()) + ": §e$" + String.format("%.1f", en.getValue()));
            }
            p.sendMessage("§7Beli dengan: /buy <nama> [jumlah]");
            return;
        }
        String q = args[0].toLowerCase();
        Material found = null;
        for (Material m : seedPrices.keySet()) {
            if (m.name().toLowerCase().contains(q)) { found = m; break; }
        }
        if (found == null) {
            p.sendMessage("§cBenih tidak ditemukan. Ketik /buy untuk melihat daftar.");
            return;
        }
        int amount = 1;
        if (args.length > 1) {
            try { amount = Integer.parseInt(args[1]); }
            catch (NumberFormatException ex) { p.sendMessage("§cJumlah harus berupa angka."); return; }
        }
        amount = Math.max(1, Math.min(amount, 64));
        double cost = seedPrices.get(found) * amount;
        if (money(p) < cost) {
            p.sendMessage("§cUang kurang. Butuh $" + String.format("%.1f", cost));
            return;
        }
        setMoney(p, money(p) - cost);
        Map<Integer, ItemStack> left = p.getInventory().addItem(new ItemStack(found, amount));
        for (ItemStack it : left.values()) p.getWorld().dropItemNaturally(p.getLocation(), it);
        saveData();
        p.sendMessage("§aBeli " + amount + " " + pretty(found) + " seharga $" + String.format("%.1f", cost));
    }

    private void sell(Player p) {
        double total = 0;
        ItemStack[] items = p.getInventory().getContents();
        for (int i = 0; i < items.length; i++) {
            ItemStack it = items[i];
            if (it == null) continue;
            if (!crops.containsKey(it.getType())) continue;
            total += sellPrice(it.getType()) * it.getAmount();
            p.getInventory().setItem(i, null);
        }
        if (total <= 0) {
            p.sendMessage("§cTidak ada hasil panen yang bisa dijual.");
            return;
        }
        setMoney(p, money(p) + total);
        saveData();
        p.sendMessage("§aTerjual! +$" + fmt(total));
    }

    private void upgrade(Player p) {
        if (!hasIsland(p)) {
            p.sendMessage("§cKamu belum punya pulau. Ketik /farm dulu.");
            return;
        }
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
