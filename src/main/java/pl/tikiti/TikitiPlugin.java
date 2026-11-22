package pl.tikiti;

import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.RayTraceResult;

import java.util.*;
import java.util.regex.Pattern;

public class TikitiPlugin extends JavaPlugin implements CommandExecutor {
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
        if (a.length == 0) { p.sendMessage("§eUżycie: /tikiti <link|stop> [szer] [wys]"); return true; }

        if (a[0].equalsIgnoreCase("stop")) {
            Screen old = screens.remove(p.getUniqueId());
            p.sendMessage(old != null ? "§aTelewizor wyłączony." : "§cNie masz włączonego telewizora.");
            if (old != null) old.stop();
            org.bukkit.Bukkit.getScheduler().runTaskLater(this, () -> { if (screens.isEmpty()) cleanTmp(); }, 100L);
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
}
