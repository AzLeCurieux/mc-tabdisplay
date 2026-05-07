package com.azlecurieux.tabdisplay;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
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

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Mod(TabDisplayMod.MOD_ID)
public class TabDisplayMod {

    public static final String MOD_ID = "tabdisplay";

    // ── Time ──────────────────────────────────────────────────────────────────
    private static final ZoneId            PARIS    = ZoneId.of("Europe/Paris");
    private static final ZoneId            MONTREAL = ZoneId.of("America/Toronto");
    private static final DateTimeFormatter FMT      = DateTimeFormatter.ofPattern("HH:mm");

    // ── Playtime ──────────────────────────────────────────────────────────────
    private static final Gson   GSON      = new GsonBuilder().setPrettyPrinting().create();
    private static final Type   DATA_TYPE = new TypeToken<Map<String, PlayerRecord>>() {}.getType();
    private static final String DATA_FILE = "tabdisplay-stats.json";

    /** uuid (String) → cumulative play time across all sessions */
    private final Map<String, PlayerRecord> allTime      = new LinkedHashMap<>();
    /** uuid (String) → millis at login (current session only) */
    private final Map<String, Long>         sessionStart = new HashMap<>();

    private Path dataFile;
    private int  ticks = 0;

    // ── Simple data container ─────────────────────────────────────────────────
    static class PlayerRecord {
        String name;
        long   seconds;

        PlayerRecord(String name, long seconds) {
            this.name    = name;
            this.seconds = seconds;
        }
    }

    // ── Constructor ───────────────────────────────────────────────────────────
    public TabDisplayMod(IEventBus modEventBus) {
        NeoForge.EVENT_BUS.register(this);
    }

    // ── Server started → load persisted data ──────────────────────────────────
    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        dataFile = event.getServer()
                .getWorldPath(LevelResource.ROOT)
                .resolve(DATA_FILE);
        loadData();
    }

    // ── Player joins ──────────────────────────────────────────────────────────
    @SubscribeEvent
    public void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        String uuid = player.getUUID().toString();
        String name = player.getName().getString();

        sessionStart.put(uuid, System.currentTimeMillis());

        // Register new player or refresh their display name
        allTime.merge(uuid, new PlayerRecord(name, 0L), (existing, ignored) -> {
            existing.name = name;
            return existing;
        });
    }

    // ── Player leaves → commit session time ───────────────────────────────────
    @SubscribeEvent
    public void onPlayerLeave(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        String uuid  = player.getUUID().toString();
        Long   start = sessionStart.remove(uuid);
        if (start == null) return;

        addTime(uuid, player.getName().getString(), elapsedSeconds(start));
        saveData();
    }

    // ── Every minute: accumulate time + refresh tab ────────────────────────────
    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        if (++ticks < 1200) return; // 20 ticks/s × 60s = 1200
        ticks = 0;

        MinecraftServer server = event.getServer();
        long            now    = System.currentTimeMillis();

        // Commit the past minute for every online player, reset their reference point
        for (ServerPlayer p : List.copyOf(server.getPlayerList().getPlayers())) {
            String uuid  = p.getUUID().toString();
            Long   start = sessionStart.get(uuid);
            if (start == null) continue;
            addTime(uuid, p.getName().getString(), elapsedSeconds(start));
            sessionStart.put(uuid, now); // start of next minute
        }

        saveData();
        sendTabList(server);
    }

    // ── Build + send the tab list packet ─────────────────────────────────────
    private void sendTabList(MinecraftServer server) {
        Component header = buildHeader();
        Component footer = buildFooter(server);
        ClientboundTabListPacket packet = new ClientboundTabListPacket(header, footer);

        for (ServerPlayer p : List.copyOf(server.getPlayerList().getPlayers())) {
            if (p.connection != null) p.connection.send(packet);
        }
    }

    // Header: France / Canada time
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

    // Footer: top-5 leaderboard
    private Component buildFooter(MinecraftServer server) {
        // Collect online UUIDs for the presence indicator
        Set<String> online = new HashSet<>();
        for (ServerPlayer p : server.getPlayerList().getPlayers())
            online.add(p.getUUID().toString());

        // Sort by seconds desc, take top 5 — keep uuid alongside the record
        List<Map.Entry<String, PlayerRecord>> top5 = allTime.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue().seconds, a.getValue().seconds))
                .limit(5)
                .collect(Collectors.toList());

        if (top5.isEmpty()) return Component.empty();

        // Gold / silver / bronze / gray / gray
        String[] rankStyle = {"§6§l", "§7§l", "§e§l", "§7", "§7"};
        String[] rankLabel = {"#1", "#2", "#3", "#4", "#5"};

        StringBuilder sb = new StringBuilder();
        sb.append("\n");
        sb.append("§8§m                                          §r\n");
        sb.append("§6§l           Meilleurs joueurs              \n");
        sb.append("§8§m                                          §r\n");
        sb.append("\n");

        for (int i = 0; i < top5.size(); i++) {
            Map.Entry<String, PlayerRecord> entry = top5.get(i);
            PlayerRecord rec       = entry.getValue();
            boolean      isOnline  = online.contains(entry.getKey());

            long h = rec.seconds / 3600;
            long m = (rec.seconds % 3600) / 60;

            // Green dot = online, dark dot = offline
            String presence = isOnline ? "§a● " : "§8○ ";

            sb.append("  ")
              .append(rankStyle[i]).append(rankLabel[i])
              .append("  ")
              .append(presence)
              .append("§f").append(rec.name)
              .append("  §8").append(h).append("h ").append(String.format("%02d", m)).append("m")
              .append("\n");
        }

        sb.append("\n");
        sb.append("§7§o§a●§7§o = connecte  §8○§7§o = hors ligne");

        return Component.literal(sb.toString());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Add elapsed seconds to a player's cumulative record (thread: server tick). */
    private void addTime(String uuid, String name, long seconds) {
        allTime.merge(uuid, new PlayerRecord(name, seconds), (existing, added) -> {
            existing.name    = name; // refresh name
            existing.seconds += added.seconds;
            return existing;
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
        } catch (IOException ignored) {
            // Non-fatal: fresh start, data rebuilds over time
        }
    }

    private void saveData() {
        if (dataFile == null) return;
        try (Writer w = Files.newBufferedWriter(dataFile)) {
            GSON.toJson(allTime, w);
        } catch (IOException ignored) {
            // Non-fatal: will retry in 60s
        }
    }
}
