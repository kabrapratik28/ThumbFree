package io.github.kabrapratik28.thumbfree.engine;

import io.github.kabrapratik28.thumbfree.engine.EngineResult;
import io.github.kabrapratik28.thumbfree.engine.StreamUpdate;

// Main process to :engine. The main process sends a WAV path and a sample range: the transcribe path's PCM never crosses
// Binder. A transcribe's token names the run for abort (0: none); an abort with a token stops only that run
// (NativeEngine.nativeAbort).
//
// Live preview: the stream behind the bubble's preview panel. streamBegin opens it for a take (its token) once :engine
// has that take's model, streamFeed sends it all of the take's audio (PCM16 little-endian, 16 kHz mono, whole
// 512-sample windows) for :engine's Silero gate and returns the stream's text, streamEnd closes it and returns once it
// is freed (the take's final transcribe waits for that), streamRelease frees any stream (the setting turned off). Calls
// naming another take's token do nothing. The stream gives way to the offline path: a begin, or a feed's stream work,
// while a load, transcribe or unload waits or runs returns StreamUpdate.BUSY at once.
interface IEngine {
    int load(String modelPath, int threads);
    EngineResult transcribe(String wavPath, long fromSample, long toSample, String language, boolean allowRetry,
            boolean speechCheck, long token);
    oneway void abort(long token);
    void unload();
    String info();
    int streamBegin(long token, String modelFile);
    StreamUpdate streamFeed(long token, in byte[] pcm16);
    int streamEnd(long token);
    void streamRelease();
}
