package me.example.miniskywars;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 一个小型 SkyWars 插件。为了让文件数量尽量少，所有逻辑都写在一个主类里。
 *
 * 设计约定：
 * 1. 地图源世界名就是 <地图名称>，游戏副本世界名是 <地图名称>_play。
 * 2. /swmap create 会创建一个虚空世界，并在 0,100,0 附近生成小平台给管理员开始搭建。
 * 3. 宝箱刷新点、出生点和死亡观察点存储在 config.yml。
 * 4. 为避免玩家把生存主世界物品带入/带出小游戏，默认会保存玩家进场前背包，离场时清空游戏物品后恢复原背包。
 *    如果你确实希望结束后让玩家背包保持空白，可在 config.yml 把 restore-player-inventory 改成 false。
 */
public final class MiniSkyWars extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private static final int MAX_PLAYERS = 8;
    private static final int AUTO_START_MIN_PLAYERS_EXCLUSIVE = 4; // “大于4人”才自动开始，所以至少5人。
    private static final String PLAY_SUFFIX = "_play";

    private final Random random = new Random();
    private final Map<String, Arena> arenas = new HashMap<>();
    private final Map<UUID, String> playerArena = new HashMap<>();
    private final Map<UUID, SavedPlayerState> savedStates = new HashMap<>();
    private final Set<String> restoringMaps = new HashSet<>();
    private final Set<UUID> pendingReconnectKills = new HashSet<>();
    private final Set<UUID> noDropForcedKills = new HashSet<>();

    @Override
    public void onEnable() {
        getConfig().addDefault("restore-player-inventory", true);
        getConfig().addDefault("pending-reconnect-kills", new ArrayList<String>());
        getConfig().options().copyDefaults(true);
        saveConfig();

        for (String raw : getConfig().getStringList("pending-reconnect-kills")) {
            try {
                pendingReconnectKills.add(UUID.fromString(raw));
            } catch (IllegalArgumentException ignored) {
            }
        }

        Objects.requireNonNull(getCommand("sw")).setExecutor(this);
        Objects.requireNonNull(getCommand("swmap")).setExecutor(this);
        Objects.requireNonNull(getCommand("swforce")).setExecutor(this);
        Objects.requireNonNull(getCommand("sw")).setTabCompleter(this);
        Objects.requireNonNull(getCommand("swmap")).setTabCompleter(this);
        Objects.requireNonNull(getCommand("swforce")).setTabCompleter(this);
        Bukkit.getPluginManager().registerEvents(this, this);
    }

    @Override
    public void onDisable() {
        savePendingReconnectKills();
        for (Arena arena : arenas.values()) {
            if (arena.countdownTask != null) {
                arena.countdownTask.cancel();
            }
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        try {
            String cmd = command.getName().toLowerCase(Locale.ROOT);
            if (cmd.equals("sw")) {
                return handleSw(sender, args);
            }
            if (cmd.equals("swmap")) {
                return handleSwMap(sender, args);
            }
            if (cmd.equals("swforce")) {
                return handleSwForce(sender, args);
            }
            return false;
        } catch (Exception ex) {
            sender.sendMessage(color("&c命令执行出错：" + ex.getMessage()));
            getLogger().warning("Command error: " + ex.getMessage());
            ex.printStackTrace();
            return true;
        }
    }

    private boolean handleSw(CommandSender sender, String[] args) throws IOException {
        if (args.length == 0) {
            sendSwHelp(sender);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "set" -> {
                requireAdmin(sender);
                Player player = requirePlayer(sender);
                if (args.length == 2 && args[1].equalsIgnoreCase("survival")) {
                    saveLocation("survivalSpawn", player.getLocation());
                    World world = player.getWorld();
                    trySetAnyGameRule(world, true, "keepInventory");
                    saveConfig();
                    sender.sendMessage(color("&a已设置生存主世界返回点。"));
                    return true;
                }
                sender.sendMessage(color("&e用法：/sw set survival"));
                return true;
            }
            case "add" -> {
                requireAdmin(sender);
                Player player = requirePlayer(sender);
                return handleSwAdd(player, args);
            }
            case "delete" -> {
                requireAdmin(sender);
                Player player = requirePlayer(sender);
                return handleSwDelete(player, args);
            }
            case "join" -> {
                Player player = requirePlayer(sender);
                if (!player.hasPermission("miniskywars.use")) {
                    sender.sendMessage(color("&c你没有权限。"));
                    return true;
                }
                if (args.length != 2) {
                    sender.sendMessage(color("&e用法：/sw join <地图名称>"));
                    return true;
                }
                joinArena(player, args[1]);
                return true;
            }
            case "quit" -> {
                Player player = requirePlayer(sender);
                playerQuitCommand(player);
                return true;
            }
            default -> {
                sendSwHelp(sender);
                return true;
            }
        }
    }

    private boolean handleSwAdd(Player player, String[] args) {
        String mapName = getEditableMapFromPlayer(player);
        if (mapName == null) {
            player.sendMessage(color("&c你必须在已创建的源地图世界中执行此命令，不能在 _play 世界里编辑。"));
            return true;
        }

        if (args.length >= 3 && args[1].equalsIgnoreCase("setspawn")) {
            int number = parseInt(args[2], -1);
            if (number < 1 || number > MAX_PLAYERS) {
                player.sendMessage(color("&c出生点编号必须是 1-8。"));
                return true;
            }
            saveLocation("maps." + mapName + ".spawns." + number, player.getLocation());
            saveConfig();
            player.sendMessage(color("&a已设置 &f" + mapName + " &a的玩家出生点 &f" + number + "&a。"));
            return true;
        }

        if (args.length >= 4 && args[1].equalsIgnoreCase("chest")) {
            int rarity = parseInt(args[2], -1);
            String chestName = args[3];
            if (rarity < 1 || rarity > 3) {
                player.sendMessage(color("&c宝箱稀有度只能是 1、2、3。"));
                return true;
            }
            if (!isSafeKey(chestName)) {
                player.sendMessage(color("&c宝箱名称只能包含字母、数字、中文、下划线和短横线。"));
                return true;
            }
            String path = "maps." + mapName + ".chests." + chestName;
            saveLocation(path + ".location", player.getLocation());
            getConfig().set(path + ".rarity", rarity);
            Block block = player.getLocation().getBlock();
            block.setType(Material.CHEST, false);
            saveConfig();
            player.sendMessage(color("&a已添加宝箱刷新点 &f" + chestName + " &a稀有度 &f" + rarity + "&a。"));
            return true;
        }

        if (args.length == 2 && args[1].equalsIgnoreCase("deathspawn")) {
            saveLocation("maps." + mapName + ".deathSpawn", player.getLocation());
            saveConfig();
            player.sendMessage(color("&a已设置死亡后旁观复活点。"));
            return true;
        }

        player.sendMessage(color("&e用法：/sw add setspawn <1-8> | /sw add chest <1/2/3> <name> | /sw add deathspawn"));
        return true;
    }

    private boolean handleSwDelete(Player player, String[] args) {
        String mapName = getEditableMapFromPlayer(player);
        if (mapName == null) {
            player.sendMessage(color("&c你必须在已创建的源地图世界中执行此命令。"));
            return true;
        }

        if (args.length == 3 && args[1].equalsIgnoreCase("setspawn")) {
            int number = parseInt(args[2], -1);
            if (number < 1 || number > MAX_PLAYERS) {
                player.sendMessage(color("&c出生点编号必须是 1-8。"));
                return true;
            }
            getConfig().set("maps." + mapName + ".spawns." + number, null);
            saveConfig();
            player.sendMessage(color("&a已删除出生点 &f" + number + "&a。"));
            return true;
        }

        if (args.length == 3 && args[1].equalsIgnoreCase("chest")) {
            String chestName = args[2];
            getConfig().set("maps." + mapName + ".chests." + chestName, null);
            saveConfig();
            player.sendMessage(color("&a已删除宝箱刷新点 &f" + chestName + "&a。"));
            return true;
        }

        player.sendMessage(color("&e用法：/sw delete setspawn <1-8> | /sw delete chest <name>"));
        return true;
    }

    private boolean handleSwMap(CommandSender sender, String[] args) throws IOException {
        requireAdmin(sender);
        if (args.length == 0) {
            sendSwMapHelp(sender);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "create" -> {
                Player player = requirePlayer(sender);
                if (args.length != 2) {
                    sender.sendMessage(color("&e用法：/swmap create <地图名称>"));
                    return true;
                }
                createMap(player, args[1]);
                return true;
            }
            case "save" -> {
                if (args.length != 2) {
                    sender.sendMessage(color("&e用法：/swmap save <地图名称>"));
                    return true;
                }
                saveMap(sender, args[1]);
                return true;
            }
            case "register" -> {
                if (args.length != 2) {
                    sender.sendMessage(color("&e用法：/swmap register <地图名称>"));
                    return true;
                }
                registerMap(sender, args[1], true);
                return true;
            }
            case "unregister" -> {
                if (args.length != 2) {
                    sender.sendMessage(color("&e用法：/swmap unregister <地图名称>"));
                    return true;
                }
                registerMap(sender, args[1], false);
                return true;
            }
            case "edit" -> {
                Player player = requirePlayer(sender);
                if (args.length != 2) {
                    sender.sendMessage(color("&e用法：/swmap edit <地图名称>"));
                    return true;
                }
                editMap(player, args[1]);
                return true;
            }
            case "quit" -> {
                Player player = requirePlayer(sender);
                teleportToSurvival(player);
                player.setGameMode(GameMode.SURVIVAL);
                sender.sendMessage(color("&a已返回生存主世界。"));
                return true;
            }
            default -> {
                sendSwMapHelp(sender);
                return true;
            }
        }
    }

    private boolean handleSwForce(CommandSender sender, String[] args) throws IOException {
        requireAdmin(sender);
        if (args.length == 0) {
            sender.sendMessage(color("&e用法：/swforce start [地图名称] 或 /swforce stop [地图名称]"));
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        String mapName = args.length >= 2 ? args[1] : inferMapForForceCommand(sender);
        if (mapName == null) {
            sender.sendMessage(color("&c无法判断目标地图。请站在目标地图/_play世界内，或使用 /swforce " + sub + " <地图名称>。"));
            return true;
        }

        if (sub.equals("start")) {
            Arena arena = arenas.get(mapName);
            if (arena == null || arena.players.isEmpty()) {
                sender.sendMessage(color("&c此地图当前没有等待中的玩家。"));
                return true;
            }
            if (arena.state == GameState.RUNNING || arena.state == GameState.ENDING) {
                sender.sendMessage(color("&c此地图已经在游戏中或正在结束。"));
                return true;
            }
            startCountdown(arena, true);
            sender.sendMessage(color("&a已强制开始 30 秒倒计时：&f" + mapName));
            return true;
        }

        if (sub.equals("stop")) {
            Arena arena = arenas.get(mapName);
            if (arena == null) {
                sender.sendMessage(color("&c此地图当前没有游戏会话。"));
                return true;
            }
            finishGame(arena, null, true);
            sender.sendMessage(color("&a已强行终止：&f" + mapName));
            return true;
        }

        sender.sendMessage(color("&e用法：/swforce start [地图名称] 或 /swforce stop [地图名称]"));
        return true;
    }

    private void createMap(Player player, String mapName) {
        if (!isSafeKey(mapName)) {
            player.sendMessage(color("&c地图名称只能包含字母、数字、中文、下划线和短横线。"));
            return;
        }
        if (getConfig().isConfigurationSection("maps." + mapName) || worldFolder(mapName).toFile().exists()) {
            player.sendMessage(color("&c这个地图已经存在。"));
            return;
        }

        World world = new WorldCreator(mapName)
                .generator(new VoidWorldGenerator())
                .createWorld();
        if (world == null) {
            player.sendMessage(color("&c创建世界失败。"));
            return;
        }
        world.setSpawnLocation(0, 101, 0);
        trySetAnyGameRule(world, false, "spawnMobs", "doMobSpawning");
        trySetAnyGameRule(world, false, "advanceTime", "doDaylightCycle");
        trySetAnyGameRule(world, true, "keepInventory");
        world.setTime(6000L);

        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                world.getBlockAt(x, 100, z).setType(Material.STONE, false);
            }
        }

        getConfig().set("maps." + mapName + ".registered", false);
        saveConfig();
        player.teleport(new Location(world, 0.5, 101.0, 0.5, 0, 0));
        player.sendMessage(color("&a已创建源地图世界 &f" + mapName + "&a。请开始搭建，然后设置出生点/宝箱/死亡点。"));
    }

    private void saveMap(CommandSender sender, String mapName) throws IOException {
        if (!mapExists(mapName)) {
            sender.sendMessage(color("&c地图不存在。"));
            return;
        }
        Arena arena = arenas.get(mapName);
        if (arena != null && (!arena.players.isEmpty() || arena.state != GameState.WAITING)) {
            sender.sendMessage(color("&c此地图有正在进行、倒计时或等待中的玩家，不能保存覆盖。"));
            return;
        }
        if (restoringMaps.contains(mapName)) {
            sender.sendMessage(color("&c此地图正在恢复中，不能保存覆盖。"));
            return;
        }
        World source = loadMapWorld(mapName);
        if (source == null) {
            sender.sendMessage(color("&c源地图世界加载失败。"));
            return;
        }
        source.save();
        resettingMessage(sender, mapName);
        resetPlayWorld(mapName);
        sender.sendMessage(color("&a已保存源地图，并复制/刷新 &f" + mapName + PLAY_SUFFIX + "&a。"));
    }

    private void resettingMessage(CommandSender sender, String mapName) {
        sender.sendMessage(color("&7正在复制地图并刷新宝箱：&f" + mapName + "&7。小地图通常很快，复制期间请不要重启服务器。"));
    }

    private void registerMap(CommandSender sender, String mapName, boolean registered) {
        if (!mapExists(mapName)) {
            sender.sendMessage(color("&c地图不存在。"));
            return;
        }
        if (registered) {
            if (getSpawnIds(mapName).size() < 2) {
                sender.sendMessage(color("&c至少需要设置 2 个玩家出生点。"));
                return;
            }
            if (!getConfig().isConfigurationSection("maps." + mapName + ".deathSpawn")) {
                sender.sendMessage(color("&c还没有设置死亡后复活/旁观点：/sw add deathspawn"));
                return;
            }
            if (!worldFolder(playWorldName(mapName)).toFile().exists()) {
                sender.sendMessage(color("&c还没有生成游戏副本，请先 /swmap save " + mapName));
                return;
            }
        }
        getConfig().set("maps." + mapName + ".registered", registered);
        saveConfig();
        sender.sendMessage(color(registered ? "&a地图已注册为可用：&f" + mapName : "&e地图已取消注册：&f" + mapName));
    }

    private void editMap(Player player, String mapName) {
        if (!mapExists(mapName)) {
            player.sendMessage(color("&c地图不存在。"));
            return;
        }
        World world = loadMapWorld(mapName);
        if (world == null) {
            player.sendMessage(color("&c地图世界加载失败。"));
            return;
        }
        Location target = loadLocationInWorld("maps." + mapName + ".deathSpawn", world.getName());
        if (target == null) {
            target = world.getSpawnLocation().add(0.5, 0, 0.5);
        }
        player.teleport(target);
        player.setGameMode(GameMode.CREATIVE);
        player.sendMessage(color("&a已进入源地图编辑：&f" + mapName));
    }

    private void joinArena(Player player, String mapName) throws IOException {
        if (!mapExists(mapName)) {
            player.sendMessage(color("&c地图不存在。"));
            return;
        }
        if (!getConfig().getBoolean("maps." + mapName + ".registered", false)) {
            player.sendMessage(color("&c此地图未注册或不可用。"));
            return;
        }
        if (restoringMaps.contains(mapName)) {
            player.sendMessage(color("&c此地图正在恢复中，请稍后再加入。"));
            return;
        }
        if (playerArena.containsKey(player.getUniqueId())) {
            player.sendMessage(color("&c你已经在一个 SkyWars 游戏中了。"));
            return;
        }

        Arena arena = arenas.computeIfAbsent(mapName, Arena::new);
        if (arena.state == GameState.RUNNING || arena.state == GameState.ENDING) {
            player.sendMessage(color("&c此地图游戏正在进行中，不能加入。"));
            return;
        }
        if (arena.players.size() >= MAX_PLAYERS) {
            player.sendMessage(color("&c该局游戏人数已满。"));
            return;
        }

        List<Integer> availableSpawns = new ArrayList<>(getSpawnIds(mapName));
        availableSpawns.removeAll(arena.assignedSpawns.values());
        if (availableSpawns.isEmpty()) {
            player.sendMessage(color("&c没有可用出生点，该局游戏人数已满。"));
            return;
        }
        Collections.shuffle(availableSpawns, random);
        int spawnId = availableSpawns.get(0);

        World playWorld = ensurePlayWorldReady(mapName);
        if (playWorld == null) {
            player.sendMessage(color("&c游戏副本世界加载失败，请管理员执行 /swmap save " + mapName));
            arenas.remove(mapName);
            return;
        }
        Location spawn = loadLocationInWorld("maps." + mapName + ".spawns." + spawnId, playWorld.getName());
        if (spawn == null) {
            player.sendMessage(color("&c出生点配置损坏。"));
            return;
        }

        savedStates.put(player.getUniqueId(), SavedPlayerState.capture(player));
        clearGameInventory(player);
        preparePlayerVitals(player);
        player.setGameMode(GameMode.ADVENTURE);
        trySetAnyGameRule(playWorld, true, "keepInventory");

        // 玩家脚下玻璃。开局时会移除该方块。
        Block footBlock = spawn.clone().subtract(0, 1, 0).getBlock();
        footBlock.setType(Material.GLASS, false);
        player.teleport(spawn);

        arena.players.add(player.getUniqueId());
        arena.alive.add(player.getUniqueId());
        arena.assignedSpawns.put(player.getUniqueId(), spawnId);
        playerArena.put(player.getUniqueId(), mapName);

        broadcast(arena, "&a" + player.getName() + " 加入了游戏 &7(&f" + arena.players.size() + "&7/&f" + MAX_PLAYERS + "&7)");
        if (arena.players.size() >= MAX_PLAYERS) {
            broadcast(arena, "&e该局人数已满。");
        }
        if (arena.players.size() > AUTO_START_MIN_PLAYERS_EXCLUSIVE && arena.state == GameState.WAITING) {
            startCountdown(arena, false);
        }
    }

    private World ensurePlayWorldReady(String mapName) throws IOException {
        String play = playWorldName(mapName);
        if (!worldFolder(play).toFile().exists()) {
            resetPlayWorld(mapName);
        }
        return loadMapWorld(play);
    }

    private void playerQuitCommand(Player player) throws IOException {
        String mapName = playerArena.get(player.getUniqueId());
        if (mapName == null) {
            teleportToSurvival(player);
            player.setGameMode(GameMode.SURVIVAL);
            clearGameInventory(player);
            player.sendMessage(color("&a已返回生存主世界。"));
            return;
        }
        Arena arena = arenas.get(mapName);
        if (arena == null) {
            cleanupPlayerFromArena(player.getUniqueId());
            restoreAndSendHome(player, null);
            player.sendMessage(color("&a已返回生存主世界。"));
            return;
        }

        boolean wasRunning = arena.state == GameState.RUNNING;
        removePlayerFromArena(arena, player.getUniqueId(), true);
        restoreAndSendHome(player, null);
        player.sendMessage(color("&a已退出 SkyWars 并返回生存主世界。"));
        if (wasRunning) {
            checkForWinner(arena);
        } else {
            maybeCancelCountdown(arena);
            if (arena.players.isEmpty()) {
                arenas.remove(arena.mapName);
            }
        }
    }

    private void startCountdown(Arena arena, boolean forced) {
        if (arena.state == GameState.RUNNING || arena.state == GameState.ENDING) {
            return;
        }
        if (arena.countdownTask != null) {
            arena.countdownTask.cancel();
        }
        arena.state = GameState.COUNTDOWN;
        arena.forcedCountdown = forced;

        arena.countdownTask = new BukkitRunnable() {
            int seconds = 30;

            @Override
            public void run() {
                if (arena.state != GameState.COUNTDOWN) {
                    cancel();
                    return;
                }
                if (!arena.forcedCountdown && arena.players.size() <= AUTO_START_MIN_PLAYERS_EXCLUSIVE) {
                    broadcast(arena, "&c人数不足，倒计时已取消。需要大于4人。" );
                    arena.state = GameState.WAITING;
                    arena.forcedCountdown = false;
                    arena.countdownTask = null;
                    cancel();
                    return;
                }
                if (seconds <= 0) {
                    startGame(arena);
                    arena.countdownTask = null;
                    cancel();
                    return;
                }
                if (seconds == 30 || seconds == 20 || seconds == 10 || seconds <= 5) {
                    broadcast(arena, "&e游戏将在 &f" + seconds + " &e秒后开始。" );
                    for (UUID uuid : arena.players) {
                        Player player = Bukkit.getPlayer(uuid);
                        if (player != null) {
                            player.sendTitle(color("&e" + seconds), color("&7SkyWars 即将开始"), 0, 20, 5);
                        }
                    }
                }
                seconds--;
            }
        }.runTaskTimer(this, 0L, 20L);
    }

    private void startGame(Arena arena) {
        if (arena.players.isEmpty()) {
            arena.state = GameState.WAITING;
            return;
        }
        arena.state = GameState.RUNNING;
        World world = Bukkit.getWorld(playWorldName(arena.mapName));
        if (world != null) {
            trySetAnyGameRule(world, false, "keepInventory");
        }

        for (UUID uuid : new HashSet<>(arena.players)) {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null) {
                removePlayerFromArena(arena, uuid, false);
                continue;
            }
            Integer spawnId = arena.assignedSpawns.get(uuid);
            if (spawnId != null) {
                Location spawn = loadLocationInWorld("maps." + arena.mapName + ".spawns." + spawnId, playWorldName(arena.mapName));
                if (spawn != null) {
                    Block glass = spawn.clone().subtract(0, 1, 0).getBlock();
                    if (glass.getType() == Material.GLASS) {
                        glass.setType(Material.AIR, false);
                    }
                }
            }
            player.setGameMode(GameMode.SURVIVAL);
            player.sendTitle(color("&a开始！"), color("&7击败其他玩家"), 5, 35, 10);
        }
        broadcast(arena, "&a游戏正式开始！死亡不掉落已关闭。" );
        checkForWinner(arena);
    }

    private void checkForWinner(Arena arena) {
        if (arena.state != GameState.RUNNING) {
            return;
        }
        arena.alive.removeIf(uuid -> Bukkit.getPlayer(uuid) == null && !arena.players.contains(uuid));
        if (arena.alive.size() <= 1) {
            UUID winner = arena.alive.stream().findFirst().orElse(null);
            Bukkit.getScheduler().runTaskLater(this, () -> finishGame(arena, winner, false), 30L);
        }
    }

    private void finishGame(Arena arena, UUID winner, boolean forced) {
        if (arena.state == GameState.ENDING) {
            return;
        }
        arena.state = GameState.ENDING;
        if (arena.countdownTask != null) {
            arena.countdownTask.cancel();
            arena.countdownTask = null;
        }

        String winnerName = winner == null ? "无人" : getOfflineName(winner);
        Set<UUID> all = new HashSet<>(arena.players);
        all.addAll(arena.alive);
        all.addAll(arena.eliminated);

        for (UUID uuid : all) {
            Player player = Bukkit.getPlayer(uuid);
            playerArena.remove(uuid);
            if (player != null) {
                restoreAndSendHome(player, forced ? "&c游戏已被管理员强制终止" : "&6获胜者：&f" + winnerName);
                player.sendTitle(
                        color(forced ? "&c游戏终止" : "&6游戏结束"),
                        color(forced ? "&7管理员已终止本局" : "&e" + winnerName + " 获胜"),
                        10, 60, 20
                );
            }
        }

        restoringMaps.add(arena.mapName);
        Bukkit.getScheduler().runTaskLater(this, () -> {
            try {
                resetPlayWorld(arena.mapName);
                getLogger().info("Map restored: " + arena.mapName);
            } catch (IOException ex) {
                getLogger().severe("Failed to restore map " + arena.mapName + ": " + ex.getMessage());
                ex.printStackTrace();
            } finally {
                arenas.remove(arena.mapName);
                restoringMaps.remove(arena.mapName);
            }
        }, 20L);
    }

    private void resetPlayWorld(String mapName) throws IOException {
        restoringMaps.add(mapName);
        String play = playWorldName(mapName);
        World playWorld = Bukkit.getWorld(play);
        if (playWorld != null) {
            for (Player player : new ArrayList<>(playWorld.getPlayers())) {
                restoreAndSendHome(player, "&c地图正在恢复，你已被传送回生存主世界");
            }
            Bukkit.unloadWorld(playWorld, false);
        }

        Path source = worldFolder(mapName);
        Path target = worldFolder(play);
        if (!Files.exists(source)) {
            throw new IOException("源地图文件夹不存在: " + source);
        }
        deleteDirectory(target);
        copyWorldFolder(source, target);

        World newPlay = loadMapWorld(play);
        if (newPlay != null) {
            trySetAnyGameRule(newPlay, true, "keepInventory");
            fillChests(mapName, newPlay);
            newPlay.save();
        }
        restoringMaps.remove(mapName);
    }

    private void fillChests(String mapName, World playWorld) {
        ConfigurationSection chests = getConfig().getConfigurationSection("maps." + mapName + ".chests");
        if (chests == null) {
            return;
        }
        for (String chestName : chests.getKeys(false)) {
            int rarity = getConfig().getInt("maps." + mapName + ".chests." + chestName + ".rarity", 1);
            Location loc = loadLocationInWorld("maps." + mapName + ".chests." + chestName + ".location", playWorld.getName());
            if (loc == null) {
                continue;
            }
            Block block = loc.getBlock();
            block.setType(Material.CHEST, false);
            if (block.getState() instanceof Chest chest) {
                chest.getBlockInventory().clear();
                List<ItemStack> loot = rollLoot(rarity);
                for (ItemStack item : loot) {
                    int slot;
                    int guard = 0;
                    do {
                        slot = random.nextInt(chest.getBlockInventory().getSize());
                        guard++;
                    } while (chest.getBlockInventory().getItem(slot) != null && guard < 100);
                    chest.getBlockInventory().setItem(slot, item);
                }
                chest.update(true, false);
            }
        }
    }

    private List<ItemStack> rollLoot(int rarity) {
        List<ItemStack> pool = new ArrayList<>();
        switch (rarity) {
            case 1 -> {
                add(pool, Material.WOODEN_SWORD, 1, 2);
                add(pool, Material.STONE_AXE, 1, 1);
                add(pool, Material.LEATHER_HELMET, 1, 1);
                add(pool, Material.LEATHER_BOOTS, 1, 1);
                add(pool, Material.BREAD, 3, 8);
                add(pool, Material.APPLE, 2, 5);
                add(pool, Material.ARROW, 4, 12);
                add(pool, Material.SNOWBALL, 4, 16);
                add(pool, Material.EGG, 4, 12);
                add(pool, Material.OAK_PLANKS, 16, 32);
                add(pool, Material.COBBLESTONE, 16, 32);
            }
            case 2 -> {
                add(pool, Material.STONE_SWORD, 1, 2);
                add(pool, Material.IRON_AXE, 1, 1);
                add(pool, Material.BOW, 1, 1);
                add(pool, Material.CHAINMAIL_CHESTPLATE, 1, 1);
                add(pool, Material.IRON_BOOTS, 1, 1);
                add(pool, Material.IRON_HELMET, 1, 1);
                add(pool, Material.COOKED_BEEF, 3, 8);
                add(pool, Material.GOLDEN_APPLE, 1, 2);
                add(pool, Material.ARROW, 8, 20);
                add(pool, Material.WATER_BUCKET, 1, 1);
                add(pool, Material.LAVA_BUCKET, 1, 1);
                add(pool, Material.ENDER_PEARL, 1, 2);
                add(pool, Material.COBWEB, 2, 5);
                add(pool, Material.STONE, 24, 48);
            }
            default -> {
                add(pool, Material.IRON_SWORD, 1, 1);
                add(pool, Material.DIAMOND_SWORD, 1, 1);
                add(pool, Material.DIAMOND_HELMET, 1, 1);
                add(pool, Material.DIAMOND_BOOTS, 1, 1);
                add(pool, Material.IRON_CHESTPLATE, 1, 1);
                add(pool, Material.IRON_LEGGINGS, 1, 1);
                add(pool, Material.BOW, 1, 1);
                add(pool, Material.CROSSBOW, 1, 1);
                add(pool, Material.GOLDEN_APPLE, 2, 4);
                add(pool, Material.ENDER_PEARL, 2, 4);
                add(pool, Material.ARROW, 16, 32);
                add(pool, Material.TNT, 2, 6);
                add(pool, Material.WATER_BUCKET, 1, 1);
                add(pool, Material.LAVA_BUCKET, 1, 1);
                add(pool, Material.OBSIDIAN, 4, 8);
            }
        }
        Collections.shuffle(pool, random);
        int min = switch (rarity) {
            case 1 -> 4;
            case 2 -> 5;
            default -> 6;
        };
        int maxExtra = switch (rarity) {
            case 1 -> 3;
            case 2 -> 4;
            default -> 5;
        };
        int count = Math.min(pool.size(), min + random.nextInt(maxExtra + 1));
        return new ArrayList<>(pool.subList(0, count));
    }

    private void add(List<ItemStack> pool, Material material, int min, int max) {
        int amount = min + random.nextInt(Math.max(1, max - min + 1));
        pool.add(new ItemStack(material, amount));
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        UUID uuid = player.getUniqueId();

        if (noDropForcedKills.remove(uuid)) {
            event.getDrops().clear();
            event.setDroppedExp(0);
            event.setKeepInventory(true);
            event.setKeepLevel(true);
            return;
        }

        String mapName = playerArena.get(uuid);
        if (mapName == null) {
            return;
        }
        Arena arena = arenas.get(mapName);
        if (arena == null) {
            return;
        }

        if (arena.state == GameState.RUNNING) {
            event.getDrops().clear();
            event.setDroppedExp(0);
            event.setKeepInventory(false);
            arena.alive.remove(uuid);
            arena.eliminated.add(uuid);
            broadcast(arena, "&c" + player.getName() + " 已被淘汰。剩余 &f" + arena.alive.size() + " &c人。" );
            checkForWinner(arena);
        } else {
            event.getDrops().clear();
            event.setDroppedExp(0);
            event.setKeepInventory(true);
            event.setKeepLevel(true);
        }
    }

    @EventHandler
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        String mapName = playerArena.get(player.getUniqueId());
        if (mapName == null) {
            return;
        }
        Arena arena = arenas.get(mapName);
        if (arena == null) {
            return;
        }
        if (arena.state == GameState.RUNNING && arena.eliminated.contains(player.getUniqueId())) {
            Location deathSpawn = loadLocationInWorld("maps." + mapName + ".deathSpawn", playWorldName(mapName));
            if (deathSpawn != null) {
                event.setRespawnLocation(deathSpawn);
                Bukkit.getScheduler().runTaskLater(this, () -> {
                    if (player.isOnline() && playerArena.containsKey(player.getUniqueId())) {
                        player.teleport(deathSpawn);
                        player.setGameMode(GameMode.SPECTATOR);
                        player.sendMessage(color("&7你已被淘汰，正在旁观。"));
                    }
                }, 2L);
            }
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        String mapName = playerArena.get(uuid);
        if (mapName == null) {
            return;
        }
        Arena arena = arenas.get(mapName);
        if (arena == null) {
            cleanupPlayerFromArena(uuid);
            return;
        }

        if (arena.state == GameState.RUNNING) {
            removePlayerFromArena(arena, uuid, false);
            pendingReconnectKills.add(uuid);
            savePendingReconnectKills();
            broadcast(arena, "&c" + player.getName() + " 离线，已判定失败。" );
            checkForWinner(arena);
        } else {
            removePlayerFromArena(arena, uuid, true);
            maybeCancelCountdown(arena);
            if (arena.players.isEmpty()) {
                arenas.remove(arena.mapName);
            }
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        if (pendingReconnectKills.remove(uuid)) {
            savePendingReconnectKills();
            noDropForcedKills.add(uuid);
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (!player.isOnline()) {
                    return;
                }
                restoreAndSendHome(player, "&c你上局中途离线，已判定失败。" );
                player.sendMessage(color("&c你上局 SkyWars 中途离线，已判定失败并执行重连击杀。"));
                try {
                    player.setHealth(0.0);
                } catch (IllegalArgumentException ignored) {
                }
            }, 5L);
            return;
        }

        // 等待/倒计时阶段离线的玩家不算中途逃跑；重进后直接送回主世界并恢复进场前背包。
        if (savedStates.containsKey(uuid)) {
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (player.isOnline() && savedStates.containsKey(uuid)) {
                    restoreAndSendHome(player, "&e你在游戏开始前离线，已自动退出 SkyWars。" );
                }
            }, 5L);
        }
    }

    private void removePlayerFromArena(Arena arena, UUID uuid, boolean voluntaryOrPreGame) {
        arena.players.remove(uuid);
        arena.alive.remove(uuid);
        arena.eliminated.remove(uuid);
        arena.assignedSpawns.remove(uuid);
        playerArena.remove(uuid);
    }

    private void cleanupPlayerFromArena(UUID uuid) {
        String mapName = playerArena.remove(uuid);
        if (mapName != null) {
            Arena arena = arenas.get(mapName);
            if (arena != null) {
                arena.players.remove(uuid);
                arena.alive.remove(uuid);
                arena.eliminated.remove(uuid);
                arena.assignedSpawns.remove(uuid);
            }
        }
    }

    private void maybeCancelCountdown(Arena arena) {
        if (arena.state == GameState.COUNTDOWN && !arena.forcedCountdown && arena.players.size() <= AUTO_START_MIN_PLAYERS_EXCLUSIVE) {
            if (arena.countdownTask != null) {
                arena.countdownTask.cancel();
                arena.countdownTask = null;
            }
            arena.state = GameState.WAITING;
            broadcast(arena, "&c人数不足，倒计时已取消。" );
        }
    }

    private void restoreAndSendHome(Player player, String message) {
        clearGameInventory(player);
        if (getConfig().getBoolean("restore-player-inventory", true)) {
            SavedPlayerState state = savedStates.remove(player.getUniqueId());
            if (state != null) {
                state.restoreInventoryOnly(player);
            }
        } else {
            savedStates.remove(player.getUniqueId());
        }
        teleportToSurvival(player);
        player.setGameMode(GameMode.SURVIVAL);
        preparePlayerVitals(player);
        if (message != null && !message.isBlank()) {
            player.sendMessage(color(message));
        }
    }

    private void clearGameInventory(Player player) {
        player.getInventory().clear();
        player.getInventory().setArmorContents(null);
        player.getInventory().setExtraContents(null);
        player.updateInventory();
    }

    @SuppressWarnings("deprecation")
    private void preparePlayerVitals(Player player) {
        player.setFoodLevel(20);
        player.setSaturation(5.0f);
        player.setFireTicks(0);
        try {
            player.setHealth(Math.min(20.0, player.getMaxHealth()));
        } catch (IllegalArgumentException ignored) {
        }
    }

    private void teleportToSurvival(Player player) {
        Location spawn = loadLocation("survivalSpawn");
        if (spawn == null) {
            World defaultWorld = Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0);
            if (defaultWorld == null) {
                return;
            }
            spawn = defaultWorld.getSpawnLocation().add(0.5, 0, 0.5);
        }
        if (spawn.getWorld() != null) {
            trySetAnyGameRule(spawn.getWorld(), true, "keepInventory");
        }
        player.teleport(spawn);
    }

    private void broadcast(Arena arena, String message) {
        String colored = color("&8[&bSkyWars&8] &r" + message);
        for (UUID uuid : arena.players) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                player.sendMessage(colored);
            }
        }
    }

    private String getOfflineName(UUID uuid) {
        Player online = Bukkit.getPlayer(uuid);
        if (online != null) {
            return online.getName();
        }
        String name = Bukkit.getOfflinePlayer(uuid).getName();
        return name == null ? uuid.toString().substring(0, 8) : name;
    }

    private void saveLocation(String path, Location loc) {
        getConfig().set(path + ".world", loc.getWorld() == null ? null : loc.getWorld().getName());
        getConfig().set(path + ".x", loc.getX());
        getConfig().set(path + ".y", loc.getY());
        getConfig().set(path + ".z", loc.getZ());
        getConfig().set(path + ".yaw", loc.getYaw());
        getConfig().set(path + ".pitch", loc.getPitch());
    }

    private Location loadLocation(String path) {
        String worldName = getConfig().getString(path + ".world");
        if (worldName == null) {
            return null;
        }
        World world = Bukkit.getWorld(worldName);
        if (world == null && worldFolder(worldName).toFile().exists()) {
            // 生存主世界不一定是本插件的虚空地图，因此这里用普通 WorldCreator 加载。
            world = new WorldCreator(worldName).createWorld();
        }
        if (world == null) {
            return null;
        }
        return new Location(
                world,
                getConfig().getDouble(path + ".x"),
                getConfig().getDouble(path + ".y"),
                getConfig().getDouble(path + ".z"),
                (float) getConfig().getDouble(path + ".yaw"),
                (float) getConfig().getDouble(path + ".pitch")
        );
    }

    private Location loadLocationInWorld(String path, String targetWorldName) {
        if (!getConfig().isConfigurationSection(path)) {
            return null;
        }
        World world = Bukkit.getWorld(targetWorldName);
        if (world == null) {
            world = loadMapWorld(targetWorldName);
        }
        if (world == null) {
            return null;
        }
        return new Location(
                world,
                getConfig().getDouble(path + ".x"),
                getConfig().getDouble(path + ".y"),
                getConfig().getDouble(path + ".z"),
                (float) getConfig().getDouble(path + ".yaw"),
                (float) getConfig().getDouble(path + ".pitch")
        );
    }

    private World loadMapWorld(String worldName) {
        World existing = Bukkit.getWorld(worldName);
        if (existing != null) {
            return existing;
        }
        if (!worldFolder(worldName).toFile().exists()) {
            return null;
        }
        return new WorldCreator(worldName)
                .generator(new VoidWorldGenerator())
                .createWorld();
    }

    private String getEditableMapFromPlayer(Player player) {
        String worldName = player.getWorld().getName();
        if (worldName.endsWith(PLAY_SUFFIX)) {
            return null;
        }
        return mapExists(worldName) ? worldName : null;
    }

    private String inferMapForForceCommand(CommandSender sender) {
        if (sender instanceof Player player) {
            String world = player.getWorld().getName();
            if (world.endsWith(PLAY_SUFFIX)) {
                String base = world.substring(0, world.length() - PLAY_SUFFIX.length());
                if (mapExists(base)) {
                    return base;
                }
            }
            if (mapExists(world)) {
                return world;
            }
        }
        if (arenas.size() == 1) {
            return arenas.keySet().iterator().next();
        }
        return null;
    }

    private List<Integer> getSpawnIds(String mapName) {
        ConfigurationSection section = getConfig().getConfigurationSection("maps." + mapName + ".spawns");
        if (section == null) {
            return new ArrayList<>();
        }
        return section.getKeys(false).stream()
                .map(key -> parseInt(key, -1))
                .filter(i -> i >= 1 && i <= MAX_PLAYERS)
                .sorted()
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private boolean mapExists(String mapName) {
        return getConfig().isConfigurationSection("maps." + mapName);
    }

    private String playWorldName(String mapName) {
        return mapName + PLAY_SUFFIX;
    }

    private Path worldFolder(String worldName) {
        return Bukkit.getWorldContainer().toPath().resolve(worldName);
    }

    private void deleteDirectory(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void copyWorldFolder(Path source, Path target) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Path relative = source.relativize(dir);
                Files.createDirectories(target.resolve(relative));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                String fileName = file.getFileName().toString();
                if (fileName.equalsIgnoreCase("uid.dat") || fileName.equalsIgnoreCase("session.lock")) {
                    return FileVisitResult.CONTINUE;
                }
                Path relative = source.relativize(file);
                Files.copy(file, target.resolve(relative), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void savePendingReconnectKills() {
        List<String> list = pendingReconnectKills.stream().map(UUID::toString).sorted().toList();
        getConfig().set("pending-reconnect-kills", list);
        saveConfig();
    }

    /**
     * 26.2 的 Bukkit/Paper 正在把部分 GameRule 常量改名；直接引用 GameRule.SPAWN_MOBS
     * 可能出现“编译能过、运行时 NoSuchFieldError”的情况。这里改用字符串 gamerule，
     * 并按新旧名称依次尝试，兼容 Spigot/Paper 26.2 以及过渡构建。
     */
    private void trySetAnyGameRule(World world, boolean value, String... candidateRules) {
        if (world == null || candidateRules == null) {
            return;
        }
        for (String rule : candidateRules) {
            if (trySetGameRuleValue(world, rule, Boolean.toString(value))) {
                return;
            }
        }
    }

    @SuppressWarnings("deprecation")
    private boolean trySetGameRuleValue(World world, String rule, String value) {
        if (world == null || rule == null || rule.isBlank()) {
            return false;
        }
    
        // 26.x 的 Spigot/Paper 正在迁移 GameRule API。
        // 不直接调用 World#setGameRuleValue，也不直接引用 GameRule.SPAWN_MOBS 等字段，
        // 否则可能出现“编译 API 有、运行时没有”或“运行时有、编译 API 没有”的问题。
    
        // 1) 运行时如果还保留字符串版 API，就优先用字符串版。
        try {
            java.lang.reflect.Method isGameRule = world.getClass().getMethod("isGameRule", String.class);
            Object valid = isGameRule.invoke(world, rule);
            if (valid instanceof Boolean && !((Boolean) valid)) {
                return false;
            }
        } catch (NoSuchMethodException ignored) {
            // 新 API 可能已经没有字符串校验方法，继续尝试 GameRule 对象版。
        } catch (Throwable ignored) {
            // 校验失败不直接终止，继续尝试设置。
        }
    
        try {
            java.lang.reflect.Method legacySetter = world.getClass().getMethod("setGameRuleValue", String.class, String.class);
            Object result = legacySetter.invoke(world, rule, value);
            return Boolean.TRUE.equals(result);
        } catch (NoSuchMethodException ignored) {
            // 编译/运行环境没有旧方法，继续尝试对象版。
        } catch (Throwable ignored) {
            // 旧方法存在但设置失败，继续尝试对象版。
        }
    
        // 2) 使用 GameRule.getByName(rule) + World#setGameRule(GameRule, Object)。
        try {
            Class<?> gameRuleClass = Class.forName("org.bukkit.GameRule");
            Object gameRule = null;
    
            try {
                java.lang.reflect.Method getByName = gameRuleClass.getMethod("getByName", String.class);
                gameRule = getByName.invoke(null, rule);
            } catch (Throwable ignored) {
                // 若将来 getByName 被移除，直接返回 false；本插件不会因此崩服。
            }
    
            if (gameRule == null) {
                return false;
            }
    
            Object typedValue = parseGameRuleValue(gameRuleClass, gameRule, value);
            java.lang.reflect.Method setter = world.getClass().getMethod("setGameRule", gameRuleClass, Object.class);
            Object result = setter.invoke(world, gameRule, typedValue);
            return Boolean.TRUE.equals(result);
        } catch (Throwable ignored) {
            return false;
        }
    }
    
    private Object parseGameRuleValue(Class<?> gameRuleClass, Object gameRule, String value) {
        try {
            java.lang.reflect.Method getType = gameRuleClass.getMethod("getType");
            Object typeObj = getType.invoke(gameRule);
            if (typeObj instanceof Class<?> type) {
                if (type == Boolean.class || type == Boolean.TYPE) {
                    return Boolean.parseBoolean(value);
                }
                if (type == Integer.class || type == Integer.TYPE) {
                    return Integer.parseInt(value);
                }
            }
        } catch (Throwable ignored) {
            // 目前本插件只设置 boolean gamerule；取不到类型时按 boolean 兜底。
        }
        if ("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)) {
            return Boolean.parseBoolean(value);
        }
        return value;
    }

    private Player requirePlayer(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            throw new IllegalArgumentException("此命令只能由玩家执行。" );
        }
        return player;
    }

    private void requireAdmin(CommandSender sender) {
        if (!sender.hasPermission("miniskywars.admin")) {
            throw new IllegalArgumentException("你没有管理员权限。" );
        }
    }

    private int parseInt(String raw, int fallback) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private boolean isSafeKey(String raw) {
        return raw != null && raw.matches("[\\p{L}\\p{N}_-]{1,32}");
    }

    private String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }

    private void sendSwHelp(CommandSender sender) {
        sender.sendMessage(color("&b/sw join <地图名称> &7加入游戏"));
        sender.sendMessage(color("&b/sw quit &7退出并回到生存主世界"));
        if (sender.hasPermission("miniskywars.admin")) {
            sender.sendMessage(color("&b/sw set survival &7设置生存主世界返回点"));
            sender.sendMessage(color("&b/sw add setspawn <1-8> &7设置出生点"));
            sender.sendMessage(color("&b/sw add chest <1/2/3> <name> &7添加宝箱点"));
            sender.sendMessage(color("&b/sw add deathspawn &7设置死亡旁观点"));
            sender.sendMessage(color("&b/sw delete setspawn <1-8> / chest <name> &7删除点位"));
        }
    }

    private void sendSwMapHelp(CommandSender sender) {
        sender.sendMessage(color("&b/swmap create <地图名称> &7创建源地图"));
        sender.sendMessage(color("&b/swmap save <地图名称> &7保存源地图并生成 _play 副本"));
        sender.sendMessage(color("&b/swmap register <地图名称> &7注册可用"));
        sender.sendMessage(color("&b/swmap unregister <地图名称> &7取消注册"));
        sender.sendMessage(color("&b/swmap edit <地图名称> &7进入源地图编辑"));
        sender.sendMessage(color("&b/swmap quit &7回到生存主世界"));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        String cmd = command.getName().toLowerCase(Locale.ROOT);
        if (cmd.equals("sw")) {
            if (args.length == 1) {
                return filter(args[0], sender.hasPermission("miniskywars.admin")
                        ? List.of("join", "quit", "set", "add", "delete")
                        : List.of("join", "quit"));
            }
            if (args.length == 2 && args[0].equalsIgnoreCase("join")) {
                return filter(args[1], registeredMapNames());
            }
            if (args.length == 2 && args[0].equalsIgnoreCase("set")) {
                return filter(args[1], List.of("survival"));
            }
            if (args.length == 2 && args[0].equalsIgnoreCase("add")) {
                return filter(args[1], List.of("setspawn", "chest", "deathspawn"));
            }
            if (args.length == 2 && args[0].equalsIgnoreCase("delete")) {
                return filter(args[1], List.of("setspawn", "chest"));
            }
            if (args.length == 3 && args[0].equalsIgnoreCase("add") && args[1].equalsIgnoreCase("chest")) {
                return filter(args[2], List.of("1", "2", "3"));
            }
        }
        if (cmd.equals("swmap")) {
            if (args.length == 1) {
                return filter(args[0], List.of("create", "save", "register", "unregister", "edit", "quit"));
            }
            if (args.length == 2 && !args[0].equalsIgnoreCase("create") && !args[0].equalsIgnoreCase("quit")) {
                return filter(args[1], mapNames());
            }
        }
        if (cmd.equals("swforce")) {
            if (args.length == 1) {
                return filter(args[0], List.of("start", "stop"));
            }
            if (args.length == 2) {
                return filter(args[1], new ArrayList<>(arenas.keySet()));
            }
        }
        return Collections.emptyList();
    }

    private List<String> mapNames() {
        ConfigurationSection maps = getConfig().getConfigurationSection("maps");
        if (maps == null) {
            return Collections.emptyList();
        }
        return new ArrayList<>(maps.getKeys(false));
    }

    private List<String> registeredMapNames() {
        return mapNames().stream()
                .filter(name -> getConfig().getBoolean("maps." + name + ".registered", false))
                .toList();
    }

    private List<String> filter(String prefix, List<String> values) {
        String lower = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        return values.stream()
                .filter(v -> v.toLowerCase(Locale.ROOT).startsWith(lower))
                .sorted()
                .toList();
    }

    private enum GameState {
        WAITING,
        COUNTDOWN,
        RUNNING,
        ENDING
    }

    private static final class Arena {
        final String mapName;
        GameState state = GameState.WAITING;
        boolean forcedCountdown = false;
        BukkitTask countdownTask;
        final Set<UUID> players = new HashSet<>();
        final Set<UUID> alive = new HashSet<>();
        final Set<UUID> eliminated = new HashSet<>();
        final Map<UUID, Integer> assignedSpawns = new HashMap<>();

        Arena(String mapName) {
            this.mapName = mapName;
        }
    }

    private record SavedPlayerState(ItemStack[] contents, ItemStack[] armor, ItemStack[] extra) {
        static SavedPlayerState capture(Player player) {
            return new SavedPlayerState(
                    cloneItems(player.getInventory().getContents()),
                    cloneItems(player.getInventory().getArmorContents()),
                    cloneItems(player.getInventory().getExtraContents())
            );
        }

        void restoreInventoryOnly(Player player) {
            player.getInventory().setContents(cloneItems(contents));
            player.getInventory().setArmorContents(cloneItems(armor));
            player.getInventory().setExtraContents(cloneItems(extra));
            player.updateInventory();
        }

        private static ItemStack[] cloneItems(ItemStack[] items) {
            if (items == null) {
                return null;
            }
            return Arrays.stream(items)
                    .map(item -> item == null ? null : item.clone())
                    .toArray(ItemStack[]::new);
        }
    }

    /**
     * 空的 ChunkGenerator：新建地图时用作虚空世界；复制已有地图时，不影响已生成区块，只避免外部继续生成普通地形。
     */
    public static final class VoidWorldGenerator extends ChunkGenerator {
        @Override
        public Location getFixedSpawnLocation(World world, Random random) {
            return new Location(world, 0.5, 101.0, 0.5);
        }

        @Override
        public ChunkData generateChunkData(World world, Random random, int chunkX, int chunkZ, BiomeGrid biome) {
            return createChunkData(world);
        }
    }
}
