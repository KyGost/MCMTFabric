package net.himeki.mcmtfabric.serdes;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import net.himeki.mcmtfabric.MCMT;
import net.himeki.mcmtfabric.config.BlockEntityLists;
import net.himeki.mcmtfabric.config.GeneralConfig;
import net.himeki.mcmtfabric.serdes.filter.ISerDesFilter;
import net.himeki.mcmtfabric.serdes.filter.LegacyFilter;
import net.himeki.mcmtfabric.serdes.filter.PistonFilter;
import net.himeki.mcmtfabric.serdes.filter.VanillaFilter;
import net.himeki.mcmtfabric.serdes.pools.ChunkLockPool;
import net.himeki.mcmtfabric.serdes.pools.ISerDesPool;
import net.himeki.mcmtfabric.serdes.pools.ISerDesPool.ISerDesOptions;

import net.minecraft.block.entity.PistonBlockEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * Fully modular filtering
 *
 * @author jediminer543
 */
public class SerDesRegistry {

    private static final Map<Class<?>, ISerDesFilter> EMPTYMAP = new ConcurrentHashMap<Class<?>, ISerDesFilter>();
    private static final Set<Class<?>> EMPTYSET = ConcurrentHashMap.newKeySet();

    static Map<ISerDesHookType, Map<Class<?>, ISerDesFilter>> optimisedLookup;
    static Map<ISerDesHookType, Set<Class<?>>> whitelist;
    static Set<Class<?>> unknown;

    static ArrayList<ISerDesFilter> filters;

    static Set<ISerDesHookType> hookTypes;

    static {
        filters = new ArrayList<ISerDesFilter>();
        optimisedLookup = new ConcurrentHashMap<ISerDesHookType, Map<Class<?>, ISerDesFilter>>();
        whitelist = new ConcurrentHashMap<ISerDesHookType, Set<Class<?>>>();
        unknown = ConcurrentHashMap.newKeySet();
        hookTypes = new HashSet<ISerDesHookType>();
        //TODO do an event loop so that this is a thing
        for (ISerDesHookType isdh : SerDesHookTypes.values()) {
            hookTypes.add(isdh);
        }
    }

    private static final ISerDesFilter DEFAULT_FILTER = new DefaultFilter();

    public static void init() {
        initPools();
        initFilters();
        initLookup();
    }

    public static void initFilters() {
        filters.clear();
        //TODO make this an event
        filters.add(new VanillaFilter());
        filters.add(new PistonFilter());
        filters.add(new LegacyFilter());
        filters.add(DEFAULT_FILTER);
        for (ISerDesFilter sdf : filters) {
            sdf.init();
        }
    }

    public static void initLookup() {
        optimisedLookup.clear();
        for (ISerDesFilter f : filters) {
            Set<Class<?>> rawTgt = f.getTargets();
            Set<Class<?>> rawWl = f.getWhitelist();
            if (rawTgt == null) rawTgt = ConcurrentHashMap.newKeySet();
            if (rawWl == null) rawWl = ConcurrentHashMap.newKeySet();
            Map<ISerDesHookType, Set<Class<?>>> whitelist = group(rawWl);
            for (ISerDesHookType sh : hookTypes) {
                for (Class<?> i : rawTgt) {
                    if (sh.isTargetable(i)) {
                        optimisedLookup.computeIfAbsent(sh,
                                k -> new ConcurrentHashMap<Class<?>, ISerDesFilter>()).put(i, f);
                        whitelist.computeIfAbsent(sh,
                                k -> ConcurrentHashMap.newKeySet()).remove(i);
                    }
                }
                whitelist.computeIfAbsent(sh,
                        k -> ConcurrentHashMap.newKeySet()).addAll(rawWl);
            }
        }
    }

    public static Map<ISerDesHookType, Set<Class<?>>> group(Set<Class<?>> set) {
        Map<ISerDesHookType, Set<Class<?>>> out = new ConcurrentHashMap<ISerDesHookType, Set<Class<?>>>();
        for (Class<?> i : set) {
            for (ISerDesHookType sh : hookTypes) {
                if (sh.isTargetable(i)) {
                    out.computeIfAbsent(sh, k -> ConcurrentHashMap.newKeySet()).add(i);
                }
            }
        }
        return out;
    }

    public static ISerDesFilter getFilter(ISerDesHookType isdh, Class<?> clazz) {
        if (whitelist.getOrDefault(isdh, EMPTYSET).contains(clazz)) {
            return null;
        }
        return optimisedLookup.getOrDefault(isdh, EMPTYMAP).getOrDefault(clazz, DEFAULT_FILTER);
    }

    static Map<String, ISerDesPool> registry = new ConcurrentHashMap<String, ISerDesPool>();

    public static ISerDesPool getPool(String name) {
        return registry.get(name);
    }

    public static ISerDesPool getOrCreatePool(String name, Supplier<ISerDesPool> source) {
        return registry.computeIfAbsent(name, (i) -> source.get());
    }

    public static void initPools() {
        registry.clear();
    }

    public static class DefaultFilter implements ISerDesFilter {
        private static GeneralConfig config;

        //TODO make not shit
        public static boolean filterTE(Object tte) {
            boolean isLocking = false;
            if (BlockEntityLists.teBlackList.contains(tte.getClass())) {
                isLocking = true;
            }
            // Apparently a string starts with check is faster than Class.getPackage; who knew (I didn't)
            if (!isLocking && MCMT.config.chunkLockModded && !tte.getClass().getName().startsWith("net.minecraft.tileentity.")) {
                isLocking = true;
            }
            if (isLocking && BlockEntityLists.teWhiteList.contains(tte.getClass())) {
                isLocking = false;
            }
            if (tte instanceof PistonBlockEntity) {
                isLocking = true;
            }
            return isLocking;
        }

        ISerDesPool clp;
        ISerDesOptions serDesConfig;

        @Override
        public void init() {
            clp = SerDesRegistry.getOrCreatePool("LEGACY", ChunkLockPool::new);
            Map<String, String> cfg = new HashMap<>();
            cfg.put("range", "1");
            serDesConfig = clp.compileOptions(cfg);
        }

        @Override
        public void serialise(Runnable task, Object obj, BlockPos bp, World w, ISerDesHookType hookType) {
            if (!unknown.contains(obj.getClass())) {
                ClassMode mode = ClassMode.UNKNOWN;
                for (ISerDesFilter isdf : filters) {
                    ClassMode cm = isdf.getModeOnline(obj.getClass());
                    if (cm.compareTo(mode) < 0) {
                        mode = cm;
                    }
                    if (mode == ClassMode.BLACKLIST) {
                        optimisedLookup.computeIfAbsent(hookType,
                                        i -> new ConcurrentHashMap<Class<?>, ISerDesFilter>())
                                .put(obj.getClass(), isdf);
                        isdf.serialise(task, obj, bp, w, hookType);
                        return;
                    }
                }
                if (mode == ClassMode.WHITELIST) {
                    whitelist.computeIfAbsent(hookType,
                                    k -> ConcurrentHashMap.newKeySet())
                            .add(obj.getClass());
                    task.run(); // Whitelist = run on thread
                    return;
                }
                unknown.add(obj.getClass());
            }
            // TODO legacy behaviour please fix
            if (hookType.equals(SerDesHookTypes.TETick) && filterTE(obj)) {
                clp.serialise(task, obj, bp, w, serDesConfig);
            } else {
                task.run();
            }
        }


    }
}
