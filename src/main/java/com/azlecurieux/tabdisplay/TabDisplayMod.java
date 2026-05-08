package com.azlecurieux.tabdisplay;

import com.google.gson.*;
import com.google.gson.reflect.TypeToken;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundTabListPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.io.*;
import java.lang.reflect.Type;
import java.nio.file.*;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Mod(TabDisplayMod.MOD_ID)
public class TabDisplayMod {

    public static final String MOD_ID = "tabdisplay";

    // ── Timezones / formatting ────────────────────────────────────────────────
    private static final ZoneId            PARIS    = ZoneId.of("Europe/Paris");
    private static final ZoneId            MONTREAL = ZoneId.of("America/Toronto");
    private static final DateTimeFormatter FMT      = DateTimeFormatter.ofPattern("HH:mm");

    // ── Persistence ───────────────────────────────────────────────────────────
    private static final Gson   GSON      = new GsonBuilder().setPrettyPrinting().create();
    private static final Type   DATA_TYPE = new TypeToken<Map<String, PlayerRecord>>() {}.getType();
    private static final String DATA_FILE = "tabdisplay-stats.json";

    private static final String[] RANK_STYLE = {"§6§l", "§7§l", "§e§l", "§7", "§7"};
    private static final String[] RANK_LABEL = {"#1", "#2", "#3", "#4", "#5"};

    /** Accumulate in-session time every minute (20 ticks/s × 60 s). */
    private static final int ACCUM_TICKS   = 1200;
    /** Full rebuild + send every 5 minutes. */
    private static final int DISPLAY_TICKS = 6000;

    // ── Runtime state ─────────────────────────────────────────────────────────
    private final Map<String, PlayerRecord> allTime      = new LinkedHashMap<>();
    private final Map<String, Long>         sessionStart = new HashMap<>();

    private Path    dataFile;
    private int     accumTick    = 0;
    private int     displayTick  = 0;
    /** True when an update was due but the server was empty; cleared on next join. */
    private boolean pendingUpdate = false;

    // ── Data model ────────────────────────────────────────────────────────────
    static class PlayerRecord {
        String name;
        long   seconds;

        PlayerRecord(String name, long seconds) {
            this.name    = name;
            this.seconds = seconds;
        }
    }

    // ── Bootstrap ─────────────────────────────────────────────────────────────
    public TabDisplayMod(IEventBus modEventBus) {
        NeoForge.EVENT_BUS.register(this);
    }

    // ── Server ready: load our data, then merge vanilla play_time stats ────────
    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        dataFile = server.getWorldPath(LevelResource.ROOT).resolve(DATA_FILE);
        loadData();
        importVanillaStats(server);
        saveData();
    }

    // ── Player joins ──────────────────────────────────────────────────────────
    @SubscribeEvent
    public void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        String uuid = player.getUUID().toString();
        String name = player.getName().getString();

        sessionStart.put(uuid, System.currentTimeMillis());
        allTime.merge(uuid, new PlayerRecord(name, 0L), (ex, ignored) -> {
            ex.name = name;
            return ex;
        });

        MinecraftServer server = player.getServer();
        if (server == null) return;

        // If an update was pending (server was empty), flush it now
        if (pendingUpdate) {
            saveData();
            pendingUpdate = false;
        }

        // Rebuild and push to all players — online indicator changed
        List<ServerPlayer> all = List.copyOf(server.getPlayerList().getPlayers());
        sendTabToAll(all);
    }

    // ── Player leaves ─────────────────────────────────────────────────────────
    @SubscribeEvent
    public void onPlayerLeave(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        String uuid  = player.getUUID().toString();
        Long   start = sessionStart.remove(uuid);
        if (start != null) {
            addTime(uuid, player.getName().getString(), elapsedSeconds(start));
        }
        saveData();

        MinecraftServer server = player.getServer();
        if (server == null) return;

        // Rebuild for remaining players — online indicator changed
        List<ServerPlayer> remaining = List.copyOf(server.getPlayerList().getPlayers())
                .stream()
                .filter(p -> !p.getUUID().toString().equals(uuid))
                .collect(Collectors.toList());

        if (!remaining.isEmpty()) sendTabToAll(remaining);
    }

    // ── Tick handler ──────────────────────────────────────────────────────────
    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();

        // Accumulate play-time for every online player every minute
        if (++accumTick >= ACCUM_TICKS) {
            accumTick = 0;
            long now = System.currentTimeMillis();
            for (ServerPlayer p : List.copyOf(server.getPlayerList().getPlayers())) {
                String uuid  = p.getUUID().toString();
                Long   start = sessionStart.get(uuid);
                if (start == null) continue;
                addTime(uuid, p.getName().getString(), elapsedSeconds(start));
                sessionStart.put(uuid, now);
            }
        }

        // Full rebuild every 5 minutes
        if (++displayTick < DISPLAY_TICKS) return;
        displayTick = 0;

        List<ServerPlayer> players = List.copyOf(server.getPlayerList().getPlayers());
        if (players.isEmpty()) {
            pendingUpdate = true;
            return;
        }

        pendingUpdate = false;
        saveData();
        sendTabToAll(players);
    }

    // ── Build and push header+footer to a list of players ────────────────────
    private void sendTabToAll(List<ServerPlayer> players) {
        if (players.isEmpty()) return;

        Set<String> online = new HashSet<>();
        for (ServerPlayer p : players) online.add(p.getUUID().toString());

        List<Map.Entry<String, PlayerRecord>> top5 = buildTop5();

        ClientboundTabListPacket pkt = new ClientboundTabListPacket(
                buildHeader(), buildFooter(top5, online));

        for (ServerPlayer p : players) {
            if (p.connection != null) p.connection.send(pkt);
        }
    }

    // ── Build sorted top-5 list ───────────────────────────────────────────────
    private List<Map.Entry<String, PlayerRecord>> buildTop5() {
        return allTime.entrySet().stream()
                .filter(e -> e.getValue().seconds > 0)
                .sorted((a, b) -> Long.compare(b.getValue().seconds, a.getValue().seconds))
                .limit(5)
                .collect(Collectors.toList());
    }

    // ── Header: server name + France/Canada time ──────────────────────────────
    private Component buildHeader() {
        ZonedDateTime now     = ZonedDateTime.now();
        String        paris   = now.withZoneSameInstant(PARIS).format(FMT);
        String        montreal = now.withZoneSameInstant(MONTREAL).format(FMT);
        return Component.literal(
                "\n"
                + "§6§l        All of Create: Aeronautics        \n"
                + "\n"
                + "§e§lFrance  §7(Paris)§r    §f§l" + paris
                + "          "
                + "§b§lCanada  §7(Montreal)§r  §f§l" + montreal
                + "\n"
        );
    }

    // ── Footer: top-5 leaderboard ─────────────────────────────────────────────
    private Component buildFooter(List<Map.Entry<String, PlayerRecord>> top5, Set<String> online) {
        if (top5.isEmpty()) return Component.empty();

        StringBuilder sb = new StringBuilder();
        sb.append("\n");
        sb.append("§8§m                                          §r\n");
        sb.append("§6§l           Meilleurs joueurs              \n");
        sb.append("§8§m                                          §r\n");
        sb.append("\n");

        for (int i = 0; i < top5.size(); i++) {
            Map.Entry<String, PlayerRecord> entry = top5.get(i);
            PlayerRecord rec      = entry.getValue();
            boolean      isOnline = online.contains(entry.getKey());

            long h = rec.seconds / 3600;
            long m = (rec.seconds % 3600) / 60;

            sb.append("  ")
              .append(RANK_STYLE[i]).append(RANK_LABEL[i])
              .append("  ")
              .append(isOnline ? "§a● " : "§8○ ")
              .append("§f").append(rec.name)
              .append("  §8").append(h).append("h ").append(String.format("%02d", m)).append("m")
              .append("\n");
        }

        sb.append("\n");
        sb.append("§7§o§a●§7§o = connecté   §8○§7§o = hors ligne");

        return Component.literal(sb.toString());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────
    private void addTime(String uuid, String name, long seconds) {
        allTime.merge(uuid, new PlayerRecord(name, seconds), (ex, added) -> {
            ex.name     = name;
            ex.seconds += added.seconds;
            return ex;
        });
    }

    private static long elapsedSeconds(long startMillis) {
        return Math.max(0L, (System.currentTimeMillis() - startMillis) / 1000L);
    }

    // ── JSON persistence ──────────────────────────────────────────────────────
    private void loadData() {
        if (dataFile == null || !Files.exists(dataFile)) return;
        try (Reader r = Files.newBufferedReader(dataFile)) {
            Map<String, PlayerRecord> saved = GSON.fromJson(r, DATA_TYPE);
            if (saved != null) allTime.putAll(saved);
        } catch (IOException ignored) {}
    }

    private void saveData() {
        if (dataFile == null) return;
        try (Writer w = Files.newBufferedWriter(dataFile)) {
            GSON.toJson(allTime, w);
        } catch (IOException ignored) {}
    }

    // ── Retroactive import from vanilla minecraft:play_time stats ─────────────
    private void importVanillaStats(MinecraftServer server) {
        Path statsDir = server.getWorldPath(LevelResource.ROOT).resolve("stats");
        if (!Files.exists(statsDir)) return;

        Map<String, String> nameCache = loadUsercache(server);

        try (var stream = Files.list(statsDir)) {
            stream.filter(p -> p.toString().endsWith(".json"))
                  .forEach(statFile -> {
                      String fname = statFile.getFileName().toString();
                      String uuid  = fname.substring(0, fname.length() - 5);

                      try (Reader r = Files.newBufferedReader(statFile)) {
                          JsonObject root   = JsonParser.parseReader(r).getAsJsonObject();
                          JsonObject stats  = root.getAsJsonObject("stats");
                          if (stats == null) return;
                          JsonObject custom = stats.getAsJsonObject("minecraft:custom");
                          if (custom == null || !custom.has("minecraft:play_time")) return;

                          long vanillaSec = custom.get("minecraft:play_time").getAsLong() / 20;
                          if (vanillaSec <= 0) return;

                          String name = nameCache.getOrDefault(uuid,
                                  uuid.length() >= 8 ? uuid.substring(0, 8) + "…" : uuid);

                          // Keep whichever source shows more time
                          allTime.merge(uuid, new PlayerRecord(name, vanillaSec), (ex, v) -> {
                              if (v.seconds > ex.seconds) ex.seconds = v.seconds;
                              ex.name = nameCache.getOrDefault(uuid, ex.name);
                              return ex;
                          });
                      } catch (Exception ignored) {}
                  });
        } catch (IOException ignored) {}
    }

    // Parse usercache.json for UUID → name resolution
    private Map<String, String> loadUsercache(MinecraftServer server) {
        Map<String, String> result = new HashMap<>();
        try {
            Path cache = server.getServerDirectory().resolve("usercache.json");
            if (!Files.exists(cache)) return result;
            try (Reader r = Files.newBufferedReader(cache)) {
                JsonArray arr = JsonParser.parseReader(r).getAsJsonArray();
                for (JsonElement el : arr) {
                    JsonObject o = el.getAsJsonObject();
                    result.put(o.get("uuid").getAsString(), o.get("name").getAsString());
                }
            }
        } catch (Exception ignored) {}
        return result;
    }
}
