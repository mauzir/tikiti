package pl.tikiti;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.*;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class Screen {
    private static final int T = 128;

    static class Tile extends MapRenderer {
        volatile BufferedImage img = new BufferedImage(T, T, BufferedImage.TYPE_INT_RGB);
        Tile() { super(false); }
        @Override public void render(MapView map, MapCanvas canvas, Player player) {
            canvas.drawImage(0, 0, img);
        }
    }

    private final Plugin plugin;
    private final World world;
    private final int w, h;
    private final Tile[] tiles;
    private final List<ItemFrame> frames = new ArrayList<>();
    private final List<MapView> views = new ArrayList<>();
    private final Location center;
    private volatile boolean running = true;
    private volatile Process yt, ff;
    private BukkitTask sender;

    public Screen(Plugin plugin, Block wall, BlockFace face, int w, int h) {
        this.plugin = plugin; this.world = wall.getWorld(); this.w = w; this.h = h;
        this.tiles = new Tile[w * h];

        // "w prawo" z perspektywy patrzącego na ścianę
        int dx = -face.getOppositeFace().getModZ();
        int dz = face.getOppositeFace().getModX();
        int left = (w - 1) / 2;

        // sprawdź miejsce
        Block[][] air = new Block[w][h];
        for (int c = 0; c < w; c++) for (int y = 0; y < h; y++) {
            Block b = wall.getRelative(dx * (c - left), y, dz * (c - left));
            if (!b.getType().isSolid()) throw new IllegalStateException("Za mało litej ściany na ekran " + w + "x" + h + ".");
            Block f = b.getRelative(face);
            if (!f.isPassable()) throw new IllegalStateException("Przed ścianą coś stoi - zrób miejsce.");
            air[c][y] = f;
        }
        center = air[w / 2][h / 2].getLocation().add(0.5, 0.5, 0.5);

        for (int row = 0; row < h; row++) for (int c = 0; c < w; c++) {
            Tile t = new Tile();
            tiles[row * w + c] = t;
            MapView v = Bukkit.createMap(world);
            new ArrayList<>(v.getRenderers()).forEach(v::removeRenderer);
            v.addRenderer(t);
            views.add(v);
            ItemStack map = new ItemStack(Material.FILLED_MAP);
            MapMeta mm = (MapMeta) map.getItemMeta();
            mm.setMapView(v);
            map.setItemMeta(mm);
            Block spot = air[c][h - 1 - row];
            ItemFrame fr = world.spawn(spot.getLocation(), ItemFrame.class, e -> {
                e.setFacingDirection(face, true);
                e.setItem(map, false);
                e.setVisible(false);
                e.setFixed(true);
                e.setItemDropChance(0f);
            });
            frames.add(fr);
        }

        sender = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Player p : world.getPlayers()) {
                if (p.getLocation().distanceSquared(center) > 64 * 64) continue;
                for (MapView v : views) p.sendMap(v);
            }
        }, 20L, 2L);
    }

    private void logStream(Process p, String prefix) {
        Thread t = new Thread(() -> {
            try (BufferedReader er = new BufferedReader(
                    new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8))) {
                String l;
                while ((l = er.readLine()) != null) plugin.getLogger().warning(prefix + l);
            } catch (IOException ignored) { }
        });
        t.setDaemon(true);
        t.start();
    }

    public void play(String url) {
        int fps = plugin.getConfig().getInt("fps", 10);
        int srcH = plugin.getConfig().getInt("source-height", 360);
        String ytBin = plugin.getConfig().getString("ytdlp", "yt-dlp");
        String ffBin = plugin.getConfig().getString("ffmpeg", "ffmpeg");
        boolean pipe = plugin.getConfig().getBoolean("pipe-mode", true);
        int W = w * T, H = h * T;

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                String vf = "fps=" + fps + ",scale=" + W + ":" + H
                        + ":force_original_aspect_ratio=decrease,pad=" + W + ":" + H + ":(ow-iw)/2:(oh-ih)/2";
                List<String> cmd = new ArrayList<>(List.of(ffBin, "-hide_banner", "-loglevel", "error"));
                Process ytp = null;

                if (pipe) {
                    String fmt = "bv*[height<=" + srcH + "][ext=webm]/b[height<=" + srcH + "]/bv*[height<=" + srcH + "]/b";
                    ProcessBuilder ypb = new ProcessBuilder(ytBin, "--no-playlist", "-q", "--no-warnings",
                            "-f", fmt, "-o", "-", url);
                    ypb.environment().put("TMPDIR", ((TikitiPlugin) plugin).tmpDir().getAbsolutePath());
                    ytp = ypb.start();
                    yt = ytp;
                    logStream(ytp, "yt-dlp: ");
                    cmd.addAll(List.of("-re", "-i", "pipe:0"));
                    plugin.getLogger().info("Tryb rury: yt-dlp -> ffmpeg, startuje ffmpeg...");
                } else {
                    yt = new ProcessBuilder(ytBin, "--no-playlist", "--no-warnings", "-g",
                            "-f", "bv*[height<=" + srcH + "]/b[height<=" + srcH + "]/b", url)
                            .redirectErrorStream(true).start();
                    String stream = null;
                    List<String> out = new ArrayList<>();
                    try (BufferedReader br = new BufferedReader(
                            new InputStreamReader(yt.getInputStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = br.readLine()) != null) {
                            out.add(line);
                            if (stream == null && line.startsWith("http")) stream = line;
                        }
                    }
                    if (stream == null) {
                        plugin.getLogger().warning("yt-dlp nie zwrocil linku. Odpowiedz yt-dlp:");
                        for (String l : out) plugin.getLogger().warning("  " + l);
                        return;
                    }
                    cmd.add("-nostdin");
                    cmd.addAll(List.of("-re", "-i", stream));
                    plugin.getLogger().info("yt-dlp OK, startuje ffmpeg...");
                }
                cmd.addAll(List.of("-vf", vf, "-f", "rawvideo", "-pix_fmt", "rgb24", "-an", "-"));

                final Process ffp = new ProcessBuilder(cmd).start();
                ff = ffp;
                logStream(ffp, "ffmpeg: ");

                if (ytp != null) {
                    final Process src = ytp;
                    Thread copier = new Thread(() -> {
                        try (InputStream i = src.getInputStream(); OutputStream o = ffp.getOutputStream()) {
                            byte[] b = new byte[65536];
                            int n;
                            while (running && (n = i.read(b)) != -1) o.write(b, 0, n);
                        } catch (IOException ignored) { }
                    });
                    copier.setDaemon(true);
                    copier.start();
                }

                byte[] buf = new byte[W * H * 3];
                long frames = 0;
                try (DataInputStream in = new DataInputStream(new BufferedInputStream(ffp.getInputStream(), 1 << 20))) {
                    while (running) {
                        in.readFully(buf);
                        if (frames++ == 0) plugin.getLogger().info("Pierwsza klatka odebrana - film leci.");
                        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
                        int[] px = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
                        for (int i = 0, j = 0; i < px.length; i++, j += 3)
                            px[i] = ((buf[j] & 0xFF) << 16) | ((buf[j + 1] & 0xFF) << 8) | (buf[j + 2] & 0xFF);
                        for (int row = 0; row < h; row++) for (int c = 0; c < w; c++)
                            tiles[row * w + c].img = img.getSubimage(c * T, row * T, T, T);
                    }
                } finally {
                    try { ffp.waitFor(3, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
                    if (running && !ffp.isAlive())
                        plugin.getLogger().info("ffmpeg kod wyjscia: " + ffp.exitValue()
                                + " (137=zabity/limit pamieci, 126/127=nie da sie uruchomic, 132/139=awaria)");
                    if (running) plugin.getLogger().info("Koniec strumienia ffmpeg, odebrano klatek: " + frames);
                }
            } catch (EOFException ignored) {
                // koniec strumienia - opisany wyzej
            } catch (Exception e) {
                if (running) plugin.getLogger().warning("Blad odtwarzania: " + e);
            }
        });
    }

    public void stop() {
        running = false;
        if (yt != null) yt.destroy();
        if (ff != null) ff.destroyForcibly();
        if (sender != null) sender.cancel();
        frames.forEach(ItemFrame::remove);
        frames.clear();
    }
}
