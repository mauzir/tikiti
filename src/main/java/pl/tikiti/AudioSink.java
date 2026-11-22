package pl.tikiti;

import java.io.IOException;
import java.io.InputStream;
import java.util.function.BooleanSupplier;

/** Receives 48 kHz mono signed-16-bit PCM from the movie audio stream. */
interface AudioSink {
    void feed(InputStream pcm, BooleanSupplier alive) throws IOException;
    void release();
    void setPaused(boolean paused);
    void stop();
}
