package net.himeki.mcmtfabric.parallelised;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import org.apache.commons.lang3.ArrayUtils;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class ChunkLock {

    static Map<Long, Lock> chunkLockCache = new ConcurrentHashMap<>();

    static {
        //TODO Add cleanup thread
    }

    public static void cleanup() {
        chunkLockCache = new ConcurrentHashMap<>();
    }

    public static long[] lock(BlockPos bp, int radius) {
        long cp = new ChunkPos(bp).toLong();
        long[] targets = new long[(1 + radius * 2) * (1 + radius * 2)];
        int pos = 0;
        for (int i = -radius; i <= radius; i++) {
            for (int j = -radius; j <= radius; j++) {
                long curr = cp + ChunkPos.toLong(i, j); // Can error at the boundaries but eh
                targets[pos++] = curr;
            }
        }
        Arrays.sort(targets);
        for (long l : targets) {
            chunkLockCache.computeIfAbsent(l, x -> new ReentrantLock()).lock();
        }
        return targets;
    }

    public static void unlock(long[] locks) {
        ArrayUtils.reverse(locks);
        for (long l : locks) {
            chunkLockCache.get(l).unlock();
        }
    }

}