package cheeezer.notenoughspectators;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Replacement backing store for {@code PacketSniffer.PLAY_PACKETS}.
 *
 * <p>The original mod appended a {@code ByteBuf.copy()} of <em>every</em> client-bound PLAY
 * packet to a static {@link ArrayList} that was never pruned, and cleared it with a bare
 * {@code clear()} that never released the Netty buffers. Because chunk LOAD packets
 * ({@link ChunkDataS2CPacket}) and the matching UNLOAD packets ({@link UnloadChunkS2CPacket})
 * were both appended to the same append-only log, the log grew without bound for the whole
 * session and the pooled/direct ByteBufs were leaked. This is the memory leak.</p>
 *
 * <p>This cache fixes that by:</p>
 * <ul>
 *   <li>keeping the play log in stored order (so replay to a new spectator stays correct),
 *       but indexing chunk entries by chunk position;</li>
 *   <li><b>superseding</b> an old chunk buffer (and {@code release()}-ing it) when a newer
 *       {@link ChunkDataS2CPacket} arrives for the same position;</li>
 *   <li><b>removing + releasing</b> a stored chunk when its {@link UnloadChunkS2CPacket}
 *       arrives, and not storing the unload itself (it is still forwarded live by the caller);</li>
 *   <li><b>auto-evicting</b> any chunk that has lived in the cache longer than
 *       {@link #CHUNK_TTL_MS} (3 minutes) — the behaviour requested;</li>
 *   <li>always {@code release()}-ing buffers on removal / clear so native memory is freed.</li>
 * </ul>
 *
 * <p>All public methods are synchronized: the sniffer writes from the client network thread,
 * the sweep runs on the client tick thread, and replay snapshots are taken on the spectator
 * server threads.</p>
 */
public final class PlayPacketCache {

    /** How long a chunk packet may live in the replay cache before it is auto-evicted. */
    public static final long CHUNK_TTL_MS = 3L * 60L * 1000L; // 3 minutes

    /** Sentinel chunk key meaning "this entry is not a chunk and never ages out". */
    private static final long NOT_A_CHUNK = Long.MIN_VALUE;

    private static final class Entry {
        final ByteBuf buf;     // owned by the cache; released on removal
        final long timeMs;     // when it was stored
        final long chunkKey;   // ChunkPos.toLong(), or NOT_A_CHUNK
        Entry(ByteBuf buf, long timeMs, long chunkKey) {
            this.buf = buf;
            this.timeMs = timeMs;
            this.chunkKey = chunkKey;
        }
        boolean isChunk() { return chunkKey != NOT_A_CHUNK; }
    }

    private static final Object LOCK = new Object();
    private static final List<Entry> ORDER = new ArrayList<>();          // replay order
    private static final Map<Long, Entry> CHUNKS = new HashMap<>();      // chunkKey -> entry
    private static long lastSweepMs = 0L;

    // Fallback unique key generator for chunk packets whose position could not be parsed,
    // so they still age out by TTL instead of living forever. Always negative & unique.
    private static long unparsableCounter = -2L;

    private PlayPacketCache() {}

    /**
     * Store a captured PLAY packet. Ownership of {@code buf} transfers to the cache, so the
     * caller must pass an independent buffer (e.g. {@code originalBuf.copy()}).
     *
     * @param packet the already-decoded packet (used only to classify; may be null)
     * @param buf    a fresh, cache-owned buffer holding the encoded bytes
     */
    public static void store(Packet<?> packet, ByteBuf buf) {
        synchronized (LOCK) {
            // An unload cancels the stored chunk and is not itself kept in the replay cache.
            if (packet instanceof ClientboundForgetLevelChunkPacket unload) {
                removeChunkLocked(unload.pos().pack());
                buf.release(); // we are not storing it
                return;
            }

            long now = System.currentTimeMillis();

            if (packet instanceof ClientboundLevelChunkWithLightPacket) {
                long key = chunkKeyOf(buf);
                // A re-sent chunk for the same position replaces the old one (release old).
                removeChunkLocked(key);
                Entry e = new Entry(buf, now, key);
                ORDER.add(e);
                CHUNKS.put(key, e);
                return;
            }

            // Non-chunk state packet: keep as before (these are needed for late joiners and
            // are not the source of the unbounded growth).
            ORDER.add(new Entry(buf, now, NOT_A_CHUNK));
        }
    }

    /** Snapshot of the current play log, in order, as fresh buffers the caller owns. */
    public static ArrayList<ByteBuf> snapshotForReplay() {
        synchronized (LOCK) {
            ArrayList<ByteBuf> out = new ArrayList<>(ORDER.size());
            for (Entry e : ORDER) {
                out.add(e.buf.copy()); // caller writes/releases its own copy
            }
            return out;
        }
    }

    /** Append a raw buffer that is not associated with a decoded packet (legacy helper). */
    public static void storeRaw(ByteBuf buf) {
        synchronized (LOCK) {
            ORDER.add(new Entry(buf, System.currentTimeMillis(), NOT_A_CHUNK));
        }
    }

    /**
     * Evict every chunk entry older than {@code ttlMs}. Cheap to call every client tick:
     * the actual scan is throttled to run at most once per second.
     */
    public static void sweepOldChunks(long ttlMs) {
        synchronized (LOCK) {
            long now = System.currentTimeMillis();
            if (now - lastSweepMs < 1000L) return;
            lastSweepMs = now;

            Iterator<Entry> it = ORDER.iterator();
            while (it.hasNext()) {
                Entry e = it.next();
                if (e.isChunk() && now - e.timeMs >= ttlMs) {
                    it.remove();
                    CHUNKS.remove(e.chunkKey);
                    e.buf.release();
                }
            }
        }
    }

    /** Release every buffer and empty the cache (use this instead of a bare {@code clear()}). */
    public static void clearAll() {
        synchronized (LOCK) {
            for (Entry e : ORDER) {
                e.buf.release();
            }
            ORDER.clear();
            CHUNKS.clear();
        }
    }

    /** Current number of cached play packets (handy for a debug HUD / logging). */
    public static int size() {
        synchronized (LOCK) {
            return ORDER.size();
        }
    }

    // --- internals -------------------------------------------------------------------------

    private static void removeChunkLocked(long key) {
        Entry old = CHUNKS.remove(key);
        if (old != null) {
            ORDER.remove(old);
            old.buf.release();
        }
    }

    /**
     * Derive the chunk key from the encoded buffer instead of relying on Yarn accessor names.
     * A {@link ChunkDataS2CPacket} body begins with {@code int chunkX, int chunkZ} (stable wire
     * format since 1.18). The buffer here is [VarInt packetId][body...]; we duplicate it so the
     * real reader index is untouched, skip the id VarInt, then read the two ints.
     */
    private static long chunkKeyOf(ByteBuf buf) {
        try {
            ByteBuf b = buf.duplicate(); // independent reader index, no refcount change
            readVarInt(b);               // skip packet id
            int x = b.readInt();
            int z = b.readInt();
            return ChunkPos.pack(x, z);
        } catch (Exception ignored) {
            // Could not parse a position; give it a unique negative key so it still ages out
            // by TTL rather than living forever.
            return unparsableCounter--;
        }
    }

    private static int readVarInt(ByteBuf b) {
        int value = 0;
        int pos = 0;
        byte cur;
        do {
            cur = b.readByte();
            value |= (cur & 0x7F) << pos;
            pos += 7;
            if (pos >= 35) throw new RuntimeException("VarInt too big");
        } while ((cur & 0x80) != 0);
        return value;
    }
}
