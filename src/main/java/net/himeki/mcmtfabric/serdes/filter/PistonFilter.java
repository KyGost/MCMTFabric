package net.himeki.mcmtfabric.serdes.filter;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import net.himeki.mcmtfabric.serdes.ISerDesHookType;
import net.himeki.mcmtfabric.serdes.SerDesRegistry;
import net.himeki.mcmtfabric.serdes.pools.ChunkLockPool;
import net.himeki.mcmtfabric.serdes.pools.ISerDesPool;
import net.himeki.mcmtfabric.serdes.pools.ISerDesPool.ISerDesOptions;

import net.minecraft.block.entity.PistonBlockEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

public class PistonFilter implements ISerDesFilter {

    ISerDesPool clp;
    ISerDesOptions config;

    @Override
    public void init() {
        //TODO Figure out if piston specific chunklock can be used (or just move to using the 
        clp = SerDesRegistry.getOrCreatePool("LEGACY", ChunkLockPool::new);
        Map<String, String> cfg = new HashMap<>();
        cfg.put("range", "1");
        config = clp.compileOptions(cfg);
    }

    @Override
    public void serialise(Runnable task, Object obj, BlockPos bp, World w, ISerDesHookType hookType) {
        clp.serialise(task, obj, bp, w, config);
    }

    @Override
    public Set<Class<?>> getTargets() {
        Set<Class<?>> out = new HashSet<Class<?>>();
        out.add(PistonBlockEntity.class);
        return out;
    }

}
