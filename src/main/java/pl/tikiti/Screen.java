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

public final class Screen {
    private static final int TILE = 128;
    private static final String YOUTUBE_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Safari/537.36";
    static final class Tile extends MapRenderer {
        volatile BufferedImage image = new BufferedImage(TILE, TILE, BufferedImage.TYPE_INT_RGB);
        Tile() { super(false); }
        @Override public void render(MapView map, MapCanvas canvas, Player player) { canvas.drawImage(0, 0, image); }
    }

    private final Plugin plugin; private final World world; private final int width, height;
    private final Tile[] tiles; private final List<ItemFrame> frames = new ArrayList<>();
    private final List<MapView> views = new ArrayList<>(); private final Location center;
    private final Object playbackLock = new Object();
    private volatile boolean running = true, paused; private volatile long generation;
    private volatile double positionSeconds; private volatile long startedAtNanos;
    private volatile Process yt, audioYt, videoFfmpeg, audioFfmpeg; private volatile AudioSink audio;
    private BukkitTask mapSender; private String url;

    public Screen(Plugin plugin, Block wall, BlockFace face, int width, int height) {
        this.plugin = plugin; this.world = wall.getWorld(); this.width = width; this.height = height;
        this.tiles = new Tile[width * height];
        int dx = -face.getOppositeFace().getModZ(), dz = face.getOppositeFace().getModX(), left = (width - 1) / 2;
        Block[][] air = new Block[width][height];
        for (int col = 0; col < width; col++) for (int y = 0; y < height; y++) {
            Block block = wall.getRelative(dx * (col - left), y, dz * (col - left));
            if (!block.getType().isSolid()) throw new IllegalStateException("Za mało litej ściany na ekran " + width + "x" + height + ".");
            Block front = block.getRelative(face);
            if (!front.isPassable()) throw new IllegalStateException("Przed ścianą coś stoi - zrób miejsce.");
            air[col][y] = front;
        }
        center = air[width / 2][height / 2].getLocation().add(0.5, 0.5, 0.5);
        for (int row = 0; row < height; row++) for (int col = 0; col < width; col++) {
            Tile tile = new Tile(); tiles[row * width + col] = tile;
            MapView view = Bukkit.createMap(world); new ArrayList<>(view.getRenderers()).forEach(view::removeRenderer); view.addRenderer(tile); views.add(view);
            ItemStack map = new ItemStack(Material.FILLED_MAP); MapMeta meta = (MapMeta) map.getItemMeta(); meta.setMapView(view); map.setItemMeta(meta);
            Block spot = air[col][height - 1 - row];
            frames.add(world.spawn(spot.getLocation(), ItemFrame.class, entity -> {
                entity.setFacingDirection(face, true); entity.setItem(map, false); entity.setVisible(false); entity.setFixed(true); entity.setItemDropChance(0f);
            }));
        }
        mapSender = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Player player : world.getPlayers()) {
                if (player.getLocation().distanceSquared(center) <= 64 * 64) for (MapView view : views) player.sendMap(view);
            }
        }, 20L, 2L);
    }

    public void play(String videoUrl) { synchronized (playbackLock) { url = videoUrl; paused = false; positionSeconds = 0; startedAtNanos = 0; startPlaybackLocked(); } }
    public boolean pause() { synchronized (playbackLock) {
        if (paused || !running) return false; positionSeconds = currentSecondsLocked(); paused = true; generation++; stopProcessesLocked(); return true;
    } }
    public boolean resume() { synchronized (playbackLock) {
        if (!paused || !running) return false; paused = false; startedAtNanos = 0; startPlaybackLocked(); return true;
    } }
    public void seek(double seconds) { synchronized (playbackLock) {
        positionSeconds = Math.max(0, seconds); generation++; stopProcessesLocked();
        if (!paused && running) { startedAtNanos = 0; startPlaybackLocked(); }
    } }
    public boolean isPaused() { return paused; }
    public double currentSeconds() { synchronized (playbackLock) { return currentSecondsLocked(); } }
    public String formattedTime() { long seconds = (long) Math.floor(currentSeconds()); return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60); }
    private double currentSecondsLocked() { return paused || !running || startedAtNanos == 0 ? positionSeconds : positionSeconds + (System.nanoTime() - startedAtNanos) / 1_000_000_000.0; }
    private void startPlaybackLocked() { long token = ++generation; double offset = positionSeconds; Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> runPlayback(token, offset)); }
    private boolean active(long token) { return running && !paused && generation == token; }

    private void runPlayback(long token, double offset) {
        try {
            int fps = plugin.getConfig().getInt("fps", 10), sourceHeight = plugin.getConfig().getInt("source-height", 360);
            int pixelWidth = width * TILE, pixelHeight = height * TILE; String ffmpeg = plugin.getConfig().getString("ffmpeg", "ffmpeg"), ytDlp = plugin.getConfig().getString("ytdlp", "yt-dlp");
            String filter = "fps=" + fps + ",scale=" + pixelWidth + ":" + pixelHeight + ":force_original_aspect_ratio=decrease,pad=" + pixelWidth + ":" + pixelHeight + ":(ow-iw)/2:(oh-ih)/2";
            String videoFormat = "bv*[height<=" + sourceHeight + "][ext=webm]/b[height<=" + sourceHeight + "]/bv*[height<=" + sourceHeight + "]/b";
            Process source = startYtPipe(ytDlp, videoFormat, offset);
            yt = source; logStream(source, "yt-dlp video: ");
            Process video = new ProcessBuilder(ffmpeg, "-hide_banner", "-loglevel", "error", "-re", "-i", "pipe:0", "-vf", filter, "-an", "-f", "rawvideo", "-pix_fmt", "rgb24", "-").start();
            synchronized (playbackLock) { if (!active(token)) { video.destroyForcibly(); return; } videoFfmpeg = video; }
            logStream(video, "ffmpeg video: ");
            pipe(source, video, token, "Tikiti-video-pipe");
            // The server's bundled PyInstaller yt-dlp crashes when two copies extract at once.
            // Keep video on the proven single-process pipeline; audio is enabled only after a
            // dedicated one-process muxing path is available.
            AudioSink sink = null;
            byte[] buffer = new byte[pixelWidth * pixelHeight * 3]; long framesRead = 0;
            try (DataInputStream input = new DataInputStream(new BufferedInputStream(video.getInputStream(), 1 << 20))) {
                while (active(token)) {
                    input.readFully(buffer);
                    if (framesRead++ == 0) {
                        synchronized (playbackLock) { if (active(token)) startedAtNanos = System.nanoTime(); }
                        plugin.getLogger().info("Pierwsza klatka odebrana - film leci.");
                        if (sink != null) sink.release();
                    }
                    updateImage(buffer, pixelWidth, pixelHeight);
                }
            } catch (EOFException ignored) { }
            finally {
                if (active(token)) {
                    try { video.waitFor(); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                    plugin.getLogger().info("Koniec strumienia wideo, odebrano klatek: " + framesRead + ", kod ffmpeg: " + video.exitValue());
                }
            }
        } catch (Exception exception) { if (active(token)) plugin.getLogger().warning("Błąd odtwarzania: " + exception.getMessage()); }
    }

    private AudioSink createAudioSink() {
        if (VoiceBridge.api == null) return null;
        try { AudioSink sink = new ScreenAudio(center, (float) plugin.getConfig().getDouble("audio-distance", 20.0)); synchronized (playbackLock) { audio = sink; } return sink; }
        catch (Exception exception) { plugin.getLogger().warning("Dźwięk Simple Voice Chat niedostępny: " + exception.getMessage()); return null; }
    }
    private Process startYtPipe(String ytDlp, String format, double offset) throws IOException {
        List<String> command = new ArrayList<>(List.of(ytDlp, "--no-playlist", "-q", "--no-warnings"));
        // yt-dlp cuts the source before it reaches ffmpeg, so seeking remains compatible with pipe mode.
        if (offset >= 0.5) command.addAll(List.of("--download-sections", "*" + String.format(Locale.ROOT, "%.3f", offset) + "-inf"));
        command.addAll(List.of("-f", format, "-o", "-", url));
        return new ProcessBuilder(command).start();
    }
    private void startAudio(long token, String ytDlp, String ffmpeg, double offset, AudioSink sink) throws IOException {
        Process source = startYtPipe(ytDlp, "ba/b", offset);
        audioYt = source; logStream(source, "yt-dlp audio: ");
        Process process = new ProcessBuilder(ffmpeg, "-hide_banner", "-loglevel", "error", "-re", "-i", "pipe:0", "-vn", "-ac", "1", "-ar", "48000", "-f", "s16le", "-").start();
        synchronized (playbackLock) { if (!active(token)) { process.destroyForcibly(); sink.stop(); return; } audioFfmpeg = process; }
        logStream(process, "ffmpeg audio: ");
        pipe(source, process, token, "Tikiti-audio-pipe");
        Thread reader = new Thread(() -> { try { sink.feed(process.getInputStream(), () -> active(token)); } catch (IOException exception) { if (active(token)) plugin.getLogger().warning("Błąd audio: " + exception.getMessage()); } }, "Tikiti-audio");
        reader.setDaemon(true); reader.start();
    }
    private void pipe(Process source, Process target, long token, String name) {
        Thread copier = new Thread(() -> {
            try (InputStream input = source.getInputStream(); OutputStream output = target.getOutputStream()) {
                byte[] bytes = new byte[65536]; int count;
                while (active(token) && (count = input.read(bytes)) != -1) output.write(bytes, 0, count);
            } catch (IOException ignored) { }
        }, name);
        copier.setDaemon(true); copier.start();
    }
    private void updateImage(byte[] rgb, int pixelWidth, int pixelHeight) {
        BufferedImage image = new BufferedImage(pixelWidth, pixelHeight, BufferedImage.TYPE_INT_RGB); int[] pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
        for (int pixel = 0, byteIndex = 0; pixel < pixels.length; pixel++, byteIndex += 3) pixels[pixel] = ((rgb[byteIndex] & 0xFF) << 16) | ((rgb[byteIndex + 1] & 0xFF) << 8) | (rgb[byteIndex + 2] & 0xFF);
        for (int row = 0; row < height; row++) for (int col = 0; col < width; col++) tiles[row * width + col].image = image.getSubimage(col * TILE, row * TILE, TILE, TILE);
    }
    private void logStream(Process process, String prefix) { Thread logger = new Thread(() -> {
        try (BufferedReader errors = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) { String line; while ((line = errors.readLine()) != null) plugin.getLogger().warning(prefix + line); } catch (IOException ignored) { }
    }, "Tikiti-log"); logger.setDaemon(true); logger.start(); }
    private void stopProcessesLocked() {
        if (yt != null) yt.destroyForcibly(); if (audioYt != null) audioYt.destroyForcibly(); if (videoFfmpeg != null) videoFfmpeg.destroyForcibly(); if (audioFfmpeg != null) audioFfmpeg.destroyForcibly();
        if (audio != null) { audio.stop(); audio = null; } yt = audioYt = videoFfmpeg = audioFfmpeg = null;
    }
    public void stop() { synchronized (playbackLock) { if (!running) return; positionSeconds = currentSecondsLocked(); running = false; generation++; stopProcessesLocked(); } if (mapSender != null) mapSender.cancel(); frames.forEach(ItemFrame::remove); frames.clear(); }
}
