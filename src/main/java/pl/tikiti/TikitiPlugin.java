package pl.tikiti;

import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.RayTraceResult;

import java.util.*;
import java.util.regex.Pattern;

public class TikitiPlugin extends JavaPlugin implements CommandExecutor, TabCompleter {
    private static final Pattern YT = Pattern.compile(
            "^https://(www\\.|m\\.)?(youtube\\.com/watch\\?v=|youtu\\.be/)[\\w-]+.*$");
    private final Map<UUID, Screen> screens = new HashMap<>();

    public java.io.File tmpDir() {
        java.io.File d = new java.io.File(getDataFolder(), "tmp");
        d.mkdirs();
        return d;
    }

    private static void deleteRec(java.io.File f) {
        java.io.File[] c = f.listFiles();
        if (c != null) for (java.io.File x : c) deleteRec(x);
        f.delete();
    }

    public void cleanTmp() {
        java.io.File[] fs = tmpDir().listFiles();
        if (fs != null) for (java.io.File f : fs) deleteRec(f);
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        cleanTmp();
        for (String key : new String[]{"ytdlp", "ffmpeg"}) {
            java.io.File f = new java.io.File(getConfig().getString(key, key));
            if (f.isFile()) {
                boolean ok = f.setExecutable(true, false);
                getLogger().info(key + ": " + f.getPath() + " wykonywalny=" + f.canExecute() + " (set=" + ok + ")");
            } else {
                getLogger().info(key + ": nie znaleziono pliku " + f.getPath() + " (uzywam z systemu)");
            }
        }
        org.bukkit.Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                Process pr = new ProcessBuilder(getConfig().getString("ffmpeg", "ffmpeg"), "-version")
                        .redirectErrorStream(true).start();
                String first;
                try (java.io.BufferedReader br = new java.io.BufferedReader(
                        new java.io.InputStreamReader(pr.getInputStream()))) {
                    first = br.readLine();
                }
                int code = pr.waitFor();
                getLogger().info("Test ffmpeg -version: kod=" + code + " " + first);
            } catch (Exception e) {
                getLogger().warning("Test ffmpeg nieudany: " + e);
            }
        });
        getCommand("tikiti").setExecutor(this);
        getCommand("tikiti").setTabCompleter(this);
        try {
            VoiceBridge.register(this);
            getLogger().info("Simple Voice Chat wykryty: dźwięk ekranów będzie pozycyjny.");
        } catch (IllegalStateException e) {
            getLogger().info("Simple Voice Chat niedostępny: ekran będzie bez dźwięku.");
        }
    }

    @Override
    public void onDisable() {
        screens.values().forEach(Screen::stop);
        screens.clear();
        cleanTmp();
    }

    @Override
    public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
        if (!(s instanceof Player p)) { s.sendMessage("Tylko dla graczy."); return true; }
        if (!p.hasPermission("tikiti.use")) { p.sendMessage("§cBrak uprawnień."); return true; }
        if (a.length == 0) { usage(p); return true; }

        if (a[0].equalsIgnoreCase("stop")) {
            Screen old = screens.remove(p.getUniqueId());
            p.sendMessage(old != null ? "§aTelewizor wyłączony." : "§cNie masz włączonego telewizora.");
            if (old != null) old.stop();
            org.bukkit.Bukkit.getScheduler().runTaskLater(this, () -> { if (screens.isEmpty()) cleanTmp(); }, 100L);
            return true;
        }
        Screen current = screens.get(p.getUniqueId());
        if (a[0].equalsIgnoreCase("pause")) {
            if (current == null) p.sendMessage("§cNie masz włączonego telewizora.");
            else if (!current.pause()) p.sendMessage("§eFilm już jest wstrzymany.");
            else p.sendMessage("§aFilm wstrzymany na §f" + current.formattedTime() + "§a.");
            return true;
        }
        if (a[0].equalsIgnoreCase("resume")) {
            if (current == null) p.sendMessage("§cNie masz włączonego telewizora.");
            else if (!current.resume()) p.sendMessage("§eFilm już jest odtwarzany.");
            else p.sendMessage("§aWznowiono od §f" + current.formattedTime() + "§a.");
            return true;
        }
        if (a[0].equalsIgnoreCase("time")) {
            if (current == null) p.sendMessage("§cNie masz włączonego telewizora.");
            else p.sendMessage("§eCzas filmu: §f" + current.formattedTime() + (current.isPaused() ? " §7(pauza)" : ""));
            return true;
        }
        if (a[0].equalsIgnoreCase("seek")) {
            if (current == null) { p.sendMessage("§cNie masz włączonego telewizora."); return true; }
            if (a.length != 2) { p.sendMessage("§eUżycie: /tikiti seek <mm:ss|sekundy|+/-sekundy>"); return true; }
            try {
                current.seek(parseTime(a[1], current.currentSeconds()));
                p.sendMessage("§aPrzewinięto do §f" + current.formattedTime() + "§a.");
            } catch (IllegalArgumentException e) { p.sendMessage("§cNiepoprawny czas. Przykłady: 1:30, 90, +30, -10."); }
            return true;
        }
        if (!YT.matcher(a[0]).matches()) { p.sendMessage("§cPodaj poprawny link do YouTube."); return true; }

        int w = 5, h = 3;
        try {
            if (a.length > 1) w = Integer.parseInt(a[1]);
            if (a.length > 2) h = Integer.parseInt(a[2]);
        } catch (NumberFormatException e) { p.sendMessage("§cRozmiar musi być liczbą."); return true; }
        w = Math.max(1, Math.min(w, getConfig().getInt("max-width", 10)));
        h = Math.max(1, Math.min(h, getConfig().getInt("max-height", 6)));

        RayTraceResult r = p.rayTraceBlocks(8);
        if (r == null || r.getHitBlock() == null || r.getHitBlockFace() == null) {
            p.sendMessage("§cPatrz na ścianę (do 8 bloków) - to będzie dolny środek ekranu."); return true;
        }
        Block wall = r.getHitBlock();
        BlockFace face = r.getHitBlockFace();
        if (face == BlockFace.UP || face == BlockFace.DOWN) {
            p.sendMessage("§cPatrz na pionową ścianę, nie podłogę/sufit."); return true;
        }

        Screen old = screens.remove(p.getUniqueId());
        if (old != null) old.stop();

        try {
            Screen sc = new Screen(this, wall, face, w, h);
            screens.put(p.getUniqueId(), sc);
            sc.play(a[0]);
            p.sendMessage("§aStawiam telewizor " + w + "x" + h + ", ładowanie filmu (kilka sekund)...");
        } catch (IllegalStateException e) {
            p.sendMessage("§c" + e.getMessage());
        }
        return true;
    }

    private static double parseTime(String value, double current) {
        boolean relative = value.startsWith("+") || value.startsWith("-");
        String raw = relative ? value.substring(1) : value;
        if (raw.isEmpty()) throw new IllegalArgumentException();
        String[] parts = raw.split(":", -1);
        if (parts.length > 2) throw new IllegalArgumentException();
        double seconds = Double.parseDouble(parts[parts.length - 1]);
        if (parts.length == 2) seconds += Integer.parseInt(parts[0]) * 60.0;
        if (!Double.isFinite(seconds) || seconds < 0) throw new IllegalArgumentException();
        return Math.max(0, relative ? (value.charAt(0) == '-' ? current - seconds : current + seconds) : seconds);
    }

    private static void usage(Player player) {
        player.sendMessage("§e/tikiti <link> [szer] [wys] §7- uruchamia ekran");
        player.sendMessage("§e/tikiti pause|resume|stop|time");
        player.sendMessage("§e/tikiti seek <mm:ss|sekundy|+/-sekundy>");
    }

    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return prefix(args[0], List.of("pause", "resume", "seek", "time", "stop"));
        if (args.length == 2 && args[0].equalsIgnoreCase("seek")) return prefix(args[1], List.of("0:30", "1:30", "+30", "-10", "90"));
        return Collections.emptyList();
    }

    private static List<String> prefix(String typed, List<String> values) {
        return values.stream().filter(value -> value.regionMatches(true, 0, typed, 0, typed.length())).toList();
    }
}
