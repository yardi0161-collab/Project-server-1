package id.farmtycoon;

import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class FarmTycoon extends JavaPlugin implements Listener {

    private static final int Y = 64;          // tinggi pulau
    private static final int SPACING = 256;   // jarak antar pulau

    private World world;
    private File dataFile;
    private YamlConfiguration data;
    private final Map<Material, Double> prices = new HashMap<>();

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
        loadPrices();
        dataFile = new File(getDataFolder(), "data.yml");
        data = YamlConfiguration.loadConfiguration(dataFile);
        world = new WorldCreator("farmworld").generator(new VoidGen()).createWorld();
        getServer().getPluginManager().registerEvents(this, this);

        // Kalau pemain jatuh ke void, balikin ke pulaunya
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (Player p : world.getPlayers()) {
                if (p.getLocation().getY() < Y - 10) p.teleport(home(p));
            }
        }, 20L, 20L);
    }

    @Override
    public void onDisable() {
        saveData();
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
    private boolean hasIsland(Player p) { return data.contains(path(p, "island")); }
    private int level(Player p) { return data.getInt(path(p, "level"), 1); }
    private double money(Player p) { return data.getDouble(path(p, "money"), 0); }
    private void setMoney(Player p, double v) { data.set(path(p, "money"), v); }

    // ---------- Pulau ----------

    private Location center(int idx) {
        return new Location(world, (idx % 100) * SPACING + 0.5, Y + 1, (idx / 100) * SPACING + 0.5);
    }

    private Location home(Player p) {
        return center(data.getInt(path(p, "island")));
    }

    private void createIsland(Player p) {
        int idx = data.getInt("next-island", 0);
        data.set("next-island", idx + 1);
        data.set(path(p, "island"), idx);
        data.set(path(p, "level"), 1);
        data.set(path(p, "money"), 0.0);
        buildIsland(idx, 1);
        saveData();

        p.getInventory().addItem(
                new ItemStack(Material.WHEAT_SEEDS, 16),
                new ItemStack(Material.CARROT, 8),
                new ItemStack(Material.POTATO, 8),
                new ItemStack(Material.WOODEN_HOE),
                new ItemStack(Material.BONE_MEAL, 8));
    }

    /** Bangun platform farmland + air. Ukuran bertambah sesuai level. */
    private void buildIsland(int idx, int level) {
        Location c = center(idx);
        int cx = c.getBlockX(), cz = c.getBlockZ();
        int half = 3 + level; // level 1 = 9x9
        for (int dx = -half; dx <= half; dx++) {
            for (int dz = -half; dz <= half; dz++) {
                world.getBlockAt(cx + dx, Y - 1, cz + dz).setType(Material.DIRT);
                Material top;
                if (Math.abs(dx) <= 1 && Math.abs(dz) <= 1) top = Material.GRASS_BLOCK; // tempat spawn
                else if (Math.floorMod(dx, 9) == 4 && Math.floorMod(dz, 9) == 4) top = Material.WATER;
                else top = Material.FARMLAND;
                world.getBlockAt(cx + dx, Y, cz + dz).setType(top);
            }
        }
    }

    // ---------- Event ----------

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (!hasIsland(p)) createIsland(p);
        p.teleport(home(p));
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
        double cost = getConfig().getDouble("upgrade-base-cost", 500) * lvl;
        if (money(p) < cost) {
            p.sendMessage("§cUang kurang. Upgrade ke level " + (lvl + 1) + " butuh $" + (int) cost);
            return;
        }
        setMoney(p, money(p) - cost);
        data.set(path(p, "level"), lvl + 1);
        buildIsland(data.getInt(path(p, "island")), lvl + 1);
        saveData();
        p.sendMessage("§aPulau naik ke level " + (lvl + 1) + "!");
    }
}
