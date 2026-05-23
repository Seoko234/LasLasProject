package com.example.disasterplugin;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.UUID;

public class DisasterPlugin extends JavaPlugin implements Listener {

    private static final int FINE_DIAMONDS = 5;
    private static final int SLOWNESS_AMPLIFIER = 1;

    private BukkitRunnable countdownTask;
    private BukkitRunnable homeParticleTask;
    private int remainingSeconds;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.getCommand("game").setExecutor(new GameCommand(this));
        this.getCommand("벌금").setExecutor(new FineCommand(this));
        getServer().getPluginManager().registerEvents(this, this);
        for (Player player : Bukkit.getOnlinePlayers()) {
            updateFinePenalty(player);
        }
        startHomeParticles();
        getLogger().info("FLHP enabled!");
    }

    @Override
    public void onDisable() {
        if (countdownTask != null) {
            countdownTask.cancel();
        }
        if (homeParticleTask != null) {
            homeParticleTask.cancel();
        }
        getLogger().info("FLHP disabled!");
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        if (event.getBlockPlaced().getType() != Material.LODESTONE) {
            return;
        }

        Player player = event.getPlayer();
        Location location = event.getBlockPlaced().getLocation();
        saveHome(player.getUniqueId(), location);
        player.sendMessage(ChatColor.AQUA + "자석석 집이 등록되었습니다. 이 위에서 나가면 벌금이 붙지 않습니다.");
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        Location home = getHome(player.getUniqueId());
        if (home == null) {
            return;
        }

        if (!isStandingOnHome(player, home)) {
            addFine(player.getUniqueId(), FINE_DIAMONDS);
            getLogger().info(player.getName() + " left away from their lodestone home. Added "
                    + FINE_DIAMONDS + " diamond fine.");
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        int fine = getFine(event.getPlayer().getUniqueId());
        if (fine > 0) {
            event.getPlayer().sendMessage(ChatColor.RED + "미납 벌금이 다이아몬드 " + fine
                    + "개 있습니다. /벌금 지불 로 납부하세요.");
        }
        updateFinePenalty(event.getPlayer());
    }

    @EventHandler
    public void onPlayerCommandPreprocess(PlayerCommandPreprocessEvent event) {
        String[] parts = event.getMessage().substring(1).split("\\s+");
        if (parts.length == 0 || !parts[0].equalsIgnoreCase("벌금")) {
            return;
        }

        event.setCancelled(true);
        String[] args = new String[Math.max(parts.length - 1, 0)];
        System.arraycopy(parts, 1, args, 0, args.length);
        handleFineCommand(event.getPlayer(), args);
    }

    private void saveHome(UUID playerId, Location location) {
        String path = "homes." + playerId;
        getConfig().set(path + ".world", location.getWorld().getName());
        getConfig().set(path + ".x", location.getBlockX());
        getConfig().set(path + ".y", location.getBlockY());
        getConfig().set(path + ".z", location.getBlockZ());
        saveConfig();
    }

    private Location getHome(UUID playerId) {
        ConfigurationSection section = getConfig().getConfigurationSection("homes." + playerId);
        if (section == null) {
            return null;
        }

        World world = Bukkit.getWorld(section.getString("world", ""));
        if (world == null) {
            return null;
        }

        return new Location(world, section.getInt("x"), section.getInt("y"), section.getInt("z"));
    }

    private void startHomeParticles() {
        if (homeParticleTask != null) {
            homeParticleTask.cancel();
        }

        homeParticleTask = new BukkitRunnable() {
            @Override
            public void run() {
                ConfigurationSection homes = getConfig().getConfigurationSection("homes");
                if (homes == null) {
                    return;
                }

                Particle.DustOptions redDust = new Particle.DustOptions(Color.RED, 1.4F);
                for (String playerId : homes.getKeys(false)) {
                    Location home = getHome(UUID.fromString(playerId));
                    if (home == null || home.getBlock().getType() != Material.LODESTONE) {
                        continue;
                    }

                    Location particleLocation = home.clone().add(0.5, 1.15, 0.5);
                    home.getWorld().spawnParticle(Particle.DUST, particleLocation, 8, 0.12, 0.04, 0.12, 0, redDust);
                }
            }
        };
        homeParticleTask.runTaskTimer(this, 0L, 20L);
    }

    private boolean isStandingOnHome(Player player, Location home) {
        Location belowPlayer = player.getLocation().clone().subtract(0, 1, 0);
        return belowPlayer.getWorld().equals(home.getWorld())
                && belowPlayer.getBlockX() == home.getBlockX()
                && belowPlayer.getBlockY() == home.getBlockY()
                && belowPlayer.getBlockZ() == home.getBlockZ()
                && belowPlayer.getBlock().getType() == Material.LODESTONE;
    }

    private void addFine(UUID playerId, int diamonds) {
        String path = "fines." + playerId;
        getConfig().set(path, getConfig().getInt(path, 0) + diamonds);
        saveConfig();
    }

    private void applyFinePenalty(Player player) {
        player.addPotionEffect(new PotionEffect(
                PotionEffectType.SLOWNESS,
                PotionEffect.INFINITE_DURATION,
                SLOWNESS_AMPLIFIER,
                false,
                false,
                true
        ));
    }

    private void clearFinePenalty(Player player) {
        player.removePotionEffect(PotionEffectType.SLOWNESS);
    }

    private void updateFinePenalty(Player player) {
        if (getFine(player.getUniqueId()) > 0) {
            applyFinePenalty(player);
        } else {
            clearFinePenalty(player);
        }
    }

    public int getFine(UUID playerId) {
        return getConfig().getInt("fines." + playerId, 0);
    }

    public boolean payFine(Player player) {
        int fine = getFine(player.getUniqueId());
        if (fine <= 0) {
            player.sendMessage(ChatColor.GREEN + "미납 벌금이 없습니다.");
            updateFinePenalty(player);
            return true;
        }

        if (!player.getInventory().containsAtLeast(new ItemStack(Material.DIAMOND), fine)) {
            player.sendMessage(ChatColor.RED + "다이아몬드가 부족합니다. 필요: " + fine + "개");
            return true;
        }

        player.getInventory().removeItem(new ItemStack(Material.DIAMOND, fine));
        getConfig().set("fines." + player.getUniqueId(), null);
        saveConfig();
        updateFinePenalty(player);
        player.sendMessage(ChatColor.GREEN + "벌금으로 다이아몬드 " + fine + "개를 납부했습니다.");
        return true;
    }

    public boolean handleFineCommand(Player player, String[] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("지불")) {
            return payFine(player);
        }

        if (args.length == 0 || args[0].equalsIgnoreCase("확인")) {
            int fine = getFine(player.getUniqueId());
            if (fine > 0) {
                player.sendMessage(ChatColor.RED + "현재 벌금: 다이아몬드 " + fine + "개");
                player.sendMessage(ChatColor.YELLOW + "/벌금 지불 로 납부하세요.");
            } else {
                player.sendMessage(ChatColor.GREEN + "현재 벌금이 없습니다.");
            }
            updateFinePenalty(player);
            return true;
        }

        player.sendMessage(ChatColor.YELLOW + "사용법: /벌금 확인 또는 /벌금 지불");
        return true;
    }

    /**
     * Starts the countdown with the given seconds.
     */
    public void startCountdown(int seconds) {
        // Cancel any existing task
        if (countdownTask != null) {
            countdownTask.cancel();
        }
        this.remainingSeconds = seconds;
        getLogger().info("Countdown started for " + seconds + " seconds.");

        countdownTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (remainingSeconds <= 0) {
                    // Time's up! Trigger disaster.
                    triggerDisaster();
                    this.cancel(); // Stop the countdown
                } else {
                    remainingSeconds--;
                    // Optional: broadcast remaining time each second (comment out if not needed)
                    // Bukkit.broadcastMessage(ChatColor.YELLOW + "Disaster in " + remainingSeconds + " seconds");
                }
            }
        };
        // Run task every 20 ticks (1 second)
        countdownTask.runTaskTimer(this, 0L, 20L);
    }

    /**
     * Placeholder for disaster logic.
     * Replace with actual disaster effects (e.g., lightning, explosions, etc.).
     */
    public void triggerDisaster() {
        Bukkit.broadcastMessage(ChatColor.RED + "Disaster has occurred!");
        // Example: strike lightning at random online players' locations
        // for (Player p : Bukkit.getOnlinePlayers()) {
        //     p.getWorld().strikeLightning(p.getLocation());
        // }
    }
}

class FineCommand implements CommandExecutor {
    private final DisasterPlugin plugin;

    public FineCommand(DisasterPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "플레이어만 사용할 수 있습니다.");
            return true;
        }

        return plugin.handleFineCommand(player, args);
    }
}

class GameCommand implements CommandExecutor {
    private final DisasterPlugin plugin;

    public GameCommand(DisasterPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("disasterplugin.game")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to use this command.");
            return true;
        }

        if (args.length == 0 || !args[0].equalsIgnoreCase("start")) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /game start <seconds>");
            return true;
        }

        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "Please specify the number of seconds.");
            return true;
        }

        try {
            int seconds = Integer.parseInt(args[1]);
            if (seconds <= 0) {
                sender.sendMessage(ChatColor.RED + "Seconds must be greater than 0.");
                return true;
            }
            plugin.startCountdown(seconds);
            sender.sendMessage(ChatColor.GREEN + "Countdown started for " + seconds + " seconds.");
        } catch (NumberFormatException e) {
            sender.sendMessage(ChatColor.RED + "Invalid number: " + args[1]);
        }
        return true;
    }
}
