package pl.tikiti;

import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.audiochannel.AudioPlayer;
import de.maxhenkel.voicechat.api.audiochannel.LocationalAudioChannel;
import de.maxhenkel.voicechat.api.opus.OpusEncoder;
import org.bukkit.Location;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Positional movie sound sent through Simple Voice Chat. */
final class ScreenAudio implements AudioSink {
    private static final int SAMPLES = 960; // 20 ms at 48 kHz
    private final ArrayBlockingQueue<short[]> queue = new ArrayBlockingQueue<>(500);
    private final AudioPlayer player;
    private final OpusEncoder encoder;
    private volatile boolean gate, released, paused, stopped, ended;
    private volatile long releasedAt;

    ScreenAudio(Location location, float distance) {
        VoicechatServerApi api = VoiceBridge.api;
        if (api == null) throw new IllegalStateException("Simple Voice Chat nie jest gotowy");
        LocationalAudioChannel channel = api.createLocationalAudioChannel(UUID.randomUUID(),
                api.fromServerLevel(location.getWorld()),
                api.createPosition(location.getX(), location.getY(), location.getZ()));
        if (channel == null) throw new IllegalStateException("Nie udało się utworzyć kanału audio");
        channel.setDistance(distance);
        encoder = api.createEncoder();
        player = api.createAudioPlayer(channel, encoder, this::next);
        player.startPlaying();
    }

    private short[] next() {
        if (stopped) return null;
        if (!gate) {
            if (released && (queue.size() >= 10 || System.nanoTime() - releasedAt > 3_000_000_000L)) gate = true;
            else return new short[SAMPLES];
        }
        if (paused) return new short[SAMPLES];
        short[] frame = queue.poll();
        return frame != null ? frame : (ended ? null : new short[SAMPLES]);
    }

    @Override public void feed(InputStream pcm, BooleanSupplier alive) throws IOException {
        byte[] bytes = new byte[SAMPLES * 2];
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(pcm, 1 << 16))) {
            while (!stopped && alive.getAsBoolean()) {
                input.readFully(bytes);
                short[] frame = new short[SAMPLES];
                ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(frame);
                while (!stopped && alive.getAsBoolean() && !queue.offer(frame, 100, TimeUnit.MILLISECONDS)) { }
            }
        } catch (EOFException | InterruptedException ignored) {
        } finally { ended = true; }
    }

    @Override public void release() { if (!released) { releasedAt = System.nanoTime(); released = true; } }
    @Override public void setPaused(boolean value) { paused = value; }
    @Override public void stop() {
        stopped = true;
        try { player.stopPlaying(); } catch (Throwable ignored) { }
        try { encoder.close(); } catch (Throwable ignored) { }
        queue.clear();
    }
}
