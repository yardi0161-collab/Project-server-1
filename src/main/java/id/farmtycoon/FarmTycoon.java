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
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class FarmTycoon extends JavaPlugin implements Listener {

    private static final int Y = 64;          // tinggi permukaan pulau
    private static final int MAX_LEVEL = 5;   // level pulau maksimal

    // Urutan petak lahan (tile 7x7). Tiap level membuka 1 petak baru.
    private static final int[][] TILES = {{0, 0}, {0, 1}, {0, -1}, {1, 0}, {1, 1}, {1, -1}, {2, 0}, {2, 1}, {2, -1}};

    // Ukuran pulau per level: setengah sisi (H). Sisi pulau = 2H + 1 blok (termasuk border).
    private static final int[] SIZES = {11, 13, 16, 19, 25};
    // Jumlah petak lahan yang terbuka di tiap level
    private static final int[] TILE_COUNT = {1, 2, 3, 5, 9};
    // Biaya upgrade menuju level 1..5 (level 1 gratis)
    private static final double[] DEFAULT_COSTS = {0, 100000, 500000, 1000000, 2000000};

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
    private final Map<UUID, Scoreboard> boards = new HashMap<>();
    private final Map<UUID, Long> lastUpgradeClick = new HashMap<>();

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
        for (Player p : Bukkit.getOnlinePlayers()) {
            bar.addPlayer(p);
            createBoard(p);
        }

        // Tiap detik: cek perubahan harga + update bar
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            long now = System.currentTimeMillis();
            if (now >= nextChange) {
                rollPrices(true);
                nextChange = now + intervalMillis();
                saveMarket();
            }
            updateBar(now);
            updateBoards();
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
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
        }
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

    // ---------- Scoreboard ----------

    private void createBoard(Player p) {
        Scoreboard sb = Bukkit.getScoreboardManager().getNewScoreboard();
        Objective obj = sb.registerNewObjective("farm", Criteria.DUMMY,
                LegacyComponentSerializer.legacySection().deserialize("§6§lFARM TYCOON"));
        obj.setDisplaySlot(DisplaySlot.SIDEBAR);
        boards.put(p.getUniqueId(), sb);
        p.setScoreboard(sb);
        updateBoard(p);
    }

    private void updateBoards() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!boards.containsKey(p.getUniqueId())) createBoard(p);
            else updateBoard(p);
        }
    }

    private void updateBoard(Player p) {
        Scoreboard sb = boards.get(p.getUniqueId());
        if (sb == null) return;
        Objective obj = sb.getObjective("farm");
        if (obj == null) return;
        for (String entry : new ArrayList<>(sb.getEntries())) sb.resetScores(entry);

        String lvl = hasIsland(p) ? level(p) + "/" + MAX_LEVEL : "-";
        String[] lines = {
                "§1",
                "§fPemain: §a" + p.getName(),
                "§fUang: §e$" + fmt(money(p)),
                "§fLevel pulau: §b" + lvl
        };
        int score = lines.length;
        for (String l : lines) obj.getScore(l).setScore(score--);
    }

    // ---------- Data ----------

    private void saveData() {
        try { data.save(dataFile); } catch (IOException e) { e.printStackTrace(); }
    }

    private String path(Player p, String key) { return "players." + p.getUniqueId() + "." + key; }
    private boolean hasIsland(Player p) { return data.contains(path(p, "world")); }
    private boolean isFarmWorld(World w) { return farmWorlds.contains(w.getName().toLowerCase()); }
    private int level(Player p) { return Math.max(1, Math.min(MAX_LEVEL, data.getInt(path(p, "level"), 1))); }
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
        if (!data.contains(path(p, "money"))) data.set(path(p, "money"), 0.0);
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

    /**
     * Bangun pulau berbentuk kotak dengan border (dinding batu bata + pagar) di sisinya.
     * fresh = true  : pulau baru (bangun semuanya + dekorasi)
     * fresh = false : upgrade. Pulau lama tidak dirusak: border lama dibuka jadi tanah biasa,
     *                 lalu tanah baru + border baru dibangun lebih luar.
     */
    private void buildIsland(World world, int level, boolean fresh) {
        int lv = Math.max(1, Math.min(MAX_LEVEL, level));
        int H = SIZES[lv - 1];
        int oldH = (!fresh && lv > 1) ? SIZES[lv - 2] : -1;

        for (int dx = -H; dx <= H; dx++) {
            for (int dz = -H; dz <= H; dz++) {
                int cheb = Math.max(Math.abs(dx), Math.abs(dz));
                boolean border = cheb == H;
                boolean oldBorder = cheb == oldH;

                // bagian bawah pulau meruncing seperti piramida terbalik
                int depth = 3 + (int) ((H - cheb) * 0.6) + (int) (noise(dx, dz, 2) * 2);
                depth = Math.min(depth, 14);

                for (int d = 0; d <= depth; d++) {
                    Block b = world.getBlockAt(dx, Y - d, dz);
                    if (!fresh && !oldBorder && b.getType() != Material.AIR) continue;
                    Material m;
                    if (border) m = Material.STONE_BRICKS;
                    else if (d == 0) m = Material.GRASS_BLOCK;
                    else if (d <= 2) m = Material.DIRT;
                    else {
                        double r = noise(dx * 7 + d, dz * 3 + d, 3);
                        m = r < 0.2 ? Material.ANDESITE : r < 0.3 ? Material.COBBLESTONE : Material.STONE;
                    }
                    b.setType(m, false);
                }
                if (oldBorder) world.getBlockAt(dx, Y + 1, dz).setType(Material.AIR, true); // buang pagar lama
            }
        }

        // Pagar di atas border
        for (int i = -H; i <= H; i++) {
            world.getBlockAt(i, Y + 1, -H).setType(Material.OAK_FENCE, true);
            world.getBlockAt(i, Y + 1, H).setType(Material.OAK_FENCE, true);
            world.getBlockAt(-H, Y + 1, i).setType(Material.OAK_FENCE, true);
            world.getBlockAt(H, Y + 1, i).setType(Material.OAK_FENCE, true);
        }

        if (fresh) decorate(world, 0, 0);
        int from = fresh ? 0 : TILE_COUNT[lv - 2];
        for (int t = from; t < TILE_COUNT[lv - 1]; t++) buildTile(world, 0, 0, t);
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

    private boolean isNpc(Entity e) {
        return e instanceof Villager v && v.getPersistentDataContainer().has(npcKey, PersistentDataType.BYTE);
    }

    @EventHandler
    public void onNpcClick(PlayerInteractEntityEvent e) {
        if (!isNpc(e.getRightClicked())) return;
        e.setCancelled(true); // jangan buka menu trading bawaan villager
        sell(e.getPlayer());
    }

    // NPC kebal dari semua damage, termasuk pukulan pemain mode creative
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onNpcDamage(EntityDamageEvent e) {
        if (isNpc(e.getEntity())) e.setCancelled(true);
    }

    // Kalau tetap mati (misal lewat /kill), NPC muncul lagi di tempatnya
    @EventHandler
    public void onNpcDeath(EntityDeathEvent e) {
        if (!isNpc(e.getEntity())) return;
        e.getDrops().clear();
        e.setDroppedExp(0);
        World w = e.getEntity().getWorld();
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (Bukkit.getWorld(w.getName()) != null) spawnFarmer(w, 0, 0);
        }, 5L);
    }

    // Awal masuk server: ke lobby (world datar), pulau dibuat lewat /farm
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (bar != null) bar.addPlayer(p);
        createBoard(p);
        p.teleport(lobbySpawn());
        p.sendMessage("§aSelamat datang! Ketik §e/farm §auntuk masuk ke pulau farm kamu.");
    }

    // Bongkar world pemain yang sudah keluar supaya RAM hemat
    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        if (bar != null) bar.removePlayer(e.getPlayer());
        boards.remove(e.getPlayer().getUniqueId());
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
            case "upgrade" -> openUpgradeMenu(p);
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

    // ---------- Upgrade Pulau (menu) ----------

    private static class UpgradeMenu implements InventoryHolder {
        private Inventory inv;

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    private static Component txt(String legacy) {
        return LegacyComponentSerializer.legacySection().deserialize(legacy)
                .decoration(TextDecoration.ITALIC, false);
    }

    private ItemStack item(Material m, String name, String... lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(txt(name));
        List<Component> l = new ArrayList<>();
        for (String line : lore) l.add(txt(line));
        meta.lore(l);
        it.setItemMeta(meta);
        return it;
    }

    private double upgradeCost(int target) {
        return getConfig().getDouble("upgrade.costs." + target, DEFAULT_COSTS[target - 1]);
    }

    /** Format uang gaya Indonesia: 1.000.000 */
    private static String rp(double v) {
        return String.format(Locale.US, "%,.0f", v).replace(',', '.');
    }

    private static String side(int lvl) {
        int n = SIZES[lvl - 1] * 2 + 1;
        return n + " x " + n + " blok";
    }

    // Posisi item di menu (chest 6 baris). Upgrade 1..5 = kolom 2..6.
    private static final int SLOT_LEVEL = 2, SLOT_ISLAND = 4, SLOT_MONEY = 6, SLOT_CLOSE = 49;

    private static int colSlot(int n, int row) {
        return row * 9 + 1 + n;
    }

    private static boolean isItemSlot(int i) {
        if (i == SLOT_LEVEL || i == SLOT_ISLAND || i == SLOT_MONEY || i == SLOT_CLOSE) return true;
        int row = i / 9, col = i % 9;
        return row >= 1 && row <= 4 && col >= 2 && col <= 6;
    }

    /** Level pemain = level XP Minecraft (minimal 1). */
    private int playerLevel(Player p) {
        return Math.max(1, p.getLevel());
    }

    private void openUpgradeMenu(Player p) {
        UpgradeMenu holder = new UpgradeMenu();
        Inventory inv = Bukkit.createInventory(holder, 54, txt("§6§lUPGRADE PULAU"));
        holder.inv = inv;
        fillMenu(inv, p);
        p.openInventory(inv);
    }

    private void fillMenu(Inventory inv, Player p) {
        ItemStack pane = item(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 54; i++) inv.setItem(i, pane);

        int islandLvl = hasIsland(p) ? level(p) : 0;
        int pLvl = playerLevel(p);
        double money = money(p);

        // Informasi pemain
        inv.setItem(SLOT_LEVEL, item(Material.EXPERIENCE_BOTTLE, "§b§lLEVEL KAMU",
                "§7Level saat ini: §f" + pLvl,
                "§8(level XP Minecraft)"));
        inv.setItem(SLOT_ISLAND, item(Material.GRASS_BLOCK, "§6§lPULAU KAMU",
                "§7Level pulau: §b" + (islandLvl == 0 ? "-" : islandLvl + "/" + MAX_LEVEL),
                "§7Ukuran: §f" + (islandLvl == 0 ? "Belum punya pulau" : side(islandLvl))));
        inv.setItem(SLOT_MONEY, item(Material.GOLD_INGOT, "§e§lUANG KAMU",
                "§7Saldo: §e$" + rp(money)));

        // Panel tiap upgrade
        for (int n = 1; n <= MAX_LEVEL; n++) {
            boolean owned = n <= islandLvl;
            boolean next = n == islandLvl + 1;
            double cost = n == 1 ? 0 : upgradeCost(n);
            boolean lvlOk = owned || pLvl >= n;
            boolean moneyOk = owned || money >= cost;

            String color = owned ? "§a" : next ? "§e" : "§7";
            Material head = owned ? Material.LIME_CONCRETE : next ? Material.YELLOW_CONCRETE : Material.GRAY_CONCRETE;
            inv.setItem(colSlot(n, 1), item(head, color + "§lUPGRADE " + n,
                    "§7Ukuran pulau: §f" + side(n),
                    "",
                    owned ? "§a✔ Sudah dimiliki" : next ? "§e➤ Upgrade berikutnya" : "§8✖ Terkunci"));

            inv.setItem(colSlot(n, 2), item(Material.EXPERIENCE_BOTTLE,
                    (lvlOk ? "§a" : "§c") + "Level dibutuhkan: " + n,
                    "§7Level kamu: §f" + pLvl,
                    lvlOk ? "§a✔ Terpenuhi" : "§c✖ Belum terpenuhi"));

            inv.setItem(colSlot(n, 3), item(Material.GOLD_INGOT,
                    (moneyOk ? "§a" : "§c") + "Harga: " + (n == 1 ? "FREE" : "$" + rp(cost)),
                    "§7Uang kamu: §e$" + rp(money),
                    moneyOk ? "§a✔ Cukup" : "§c✖ Kurang"));

            ItemStack btn;
            if (owned) {
                btn = item(Material.LIME_STAINED_GLASS_PANE, "§a§l✔ DIMILIKI",
                        "§7Upgrade ini sudah kamu miliki");
            } else if (next && lvlOk && moneyOk) {
                btn = item(Material.EMERALD_BLOCK, "§a§l▶ UPGRADE",
                        "§7Ukuran baru: §f" + side(n),
                        "§7Biaya: " + (n == 1 ? "§aFREE" : "§e$" + rp(cost)),
                        "",
                        "§eKlik untuk upgrade!");
            } else if (next) {
                btn = item(Material.REDSTONE_BLOCK, "§c§lTERKUNCI",
                        lvlOk ? "§a✔ Level terpenuhi" : "§c✖ Level kamu kurang",
                        moneyOk ? "§a✔ Uang cukup" : "§c✖ Uang kamu kurang",
                        "",
                        "§7Klik untuk mencoba");
            } else {
                btn = item(Material.IRON_BARS, "§8§lTERKUNCI",
                        "§7Beli UPGRADE " + (n - 1) + " terlebih dahulu");
            }
            inv.setItem(colSlot(n, 4), btn);
        }

        inv.setItem(SLOT_CLOSE, item(Material.BARRIER, "§c§lCLOSE", "§7Klik untuk menutup menu"));
    }

    @EventHandler
    public void onMenuClick(InventoryClickEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (!(top.getHolder() instanceof UpgradeMenu)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getClickedInventory() == null || e.getClickedInventory() != top) return;

        int slot = e.getRawSlot();
        if (slot == SLOT_CLOSE) {
            p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1f);
            p.closeInventory();
            return;
        }
        for (int n = 1; n <= MAX_LEVEL; n++) {
            if (slot == colSlot(n, 4)) {
                doUpgrade(p, top, n);
                return;
            }
        }
    }

    @EventHandler
    public void onMenuDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof UpgradeMenu) e.setCancelled(true);
    }

    private void doUpgrade(Player p, Inventory inv, int n) {
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1f); // suara klik tombol

        long now = System.currentTimeMillis();
        Long last = lastUpgradeClick.get(p.getUniqueId());
        if (last != null && now - last < 1000L) return; // cegah klik ganda
        lastUpgradeClick.put(p.getUniqueId(), now);

        int islandLvl = hasIsland(p) ? level(p) : 0;
        if (n <= islandLvl) {
            p.sendMessage("§eKamu sudah memiliki UPGRADE " + n + ".");
            return;
        }
        if (n > islandLvl + 1) {
            p.sendMessage("§cBeli UPGRADE " + (n - 1) + " terlebih dahulu.");
            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
            return;
        }

        double cost = n == 1 ? 0 : upgradeCost(n);
        if (playerLevel(p) < n || money(p) < cost) {
            p.sendMessage("§c§lUANG/LEVEL ANDA KURANG UNTUK MELAKUKAN UPGRADE PULAU");
            p.sendActionBar(txt("§c§lUANG/LEVEL ANDA KURANG UNTUK MELAKUKAN UPGRADE PULAU"));
            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f); // suara gagal
            return;
        }

        try {
            if (n == 1) {
                createIsland(p); // UPGRADE 1 (gratis) = membuat pulau awal
            } else {
                buildIsland(loadWorld(data.getString(path(p, "world"))), n, false);
            }
        } catch (Exception ex) {
            ex.printStackTrace();
            p.sendMessage("§cTerjadi kesalahan saat upgrade. Uang tidak dipotong.");
            return;
        }

        // Uang baru dipotong setelah upgrade berhasil
        if (n > 1) {
            setMoney(p, money(p) - cost);
            data.set(path(p, "level"), n);
            saveData();
        }

        p.sendMessage("§a§lUPGRADE PULAU BERHASIL");
        p.sendMessage("§7Ukuran pulau kamu sekarang §f" + side(n) + "§7. Ketik §e/farm §7untuk menuju pulau.");
        p.sendActionBar(txt("§a§lUPGRADE PULAU BERHASIL"));
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f); // suara berhasil
        Bukkit.getScheduler().runTaskLater(this, () ->
                p.playSound(p.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 1f, 1.2f), 12L); // suara pulau membesar

        fillMenu(inv, p);
        playUpgradeAnimation(p, inv);

        // Kalau upgrade dari lobby, bongkar world pulau lagi supaya RAM hemat
        World w = Bukkit.getWorld(data.getString(path(p, "world"), ""));
        if (w != null && !w.getName().equals(p.getWorld().getName())) {
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (w.getPlayers().isEmpty()) Bukkit.unloadWorld(w, true);
            }, 100L);
        }
    }

    /** Animasi: bingkai menu berkedip hijau/kuning beberapa kali. */
    private void playUpgradeAnimation(Player p, Inventory inv) {
        new BukkitRunnable() {
            int step = 0;

            @Override
            public void run() {
                if (!p.isOnline() || p.getOpenInventory().getTopInventory() != inv) {
                    cancel();
                    return;
                }
                Material m = step % 2 == 0 ? Material.LIME_STAINED_GLASS_PANE : Material.YELLOW_STAINED_GLASS_PANE;
                ItemStack pane = item(m, "§a§l✔ UPGRADE BERHASIL");
                for (int i = 0; i < 54; i++) {
                    if (!isItemSlot(i)) inv.setItem(i, pane);
                }
                p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 1f + step * 0.1f);
                step++;
                if (step >= 8) {
                    cancel();
                    fillMenu(inv, p);
                }
            }
        }.runTaskTimer(this, 0L, 3L);
    }
}
