package me.jellysquid.mods.phosphor.mixin.chunk.light;

import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMaps;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import me.jellysquid.mods.phosphor.common.chunk.light.IReadonly;
import me.jellysquid.mods.phosphor.common.chunk.light.LightInitializer;
import me.jellysquid.mods.phosphor.common.chunk.light.LightProviderUpdateTracker;
import me.jellysquid.mods.phosphor.common.chunk.light.LightStorageAccess;
import me.jellysquid.mods.phosphor.common.chunk.light.SharedLightStorageAccess;
import me.jellysquid.mods.phosphor.common.util.chunk.light.EmptyChunkNibbleArray;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.LightType;
import net.minecraft.world.SectionDistanceLevelPropagator;
import net.minecraft.world.chunk.ChunkNibbleArray;
import net.minecraft.world.chunk.ChunkProvider;
import net.minecraft.world.chunk.ChunkToNibbleArrayMap;
import net.minecraft.world.chunk.light.ChunkLightProvider;
import net.minecraft.world.chunk.light.LightStorage;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Slice;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.locks.StampedLock;

@SuppressWarnings("OverwriteModifiers")
@Mixin(LightStorage.class)
public abstract class MixinLightStorage<M extends ChunkToNibbleArrayMap<M>> extends SectionDistanceLevelPropagator implements SharedLightStorageAccess<M>, LightStorageAccess {
    protected MixinLightStorage() {
        super(0, 0, 0);
    }

    @Shadow
    @Final
    protected M lightArrays;

    @Mutable
    @Shadow
    @Final
    protected LongSet field_15802;

    @Mutable
    @Shadow
    @Final
    protected LongSet dirtySections;

    @Shadow
    protected abstract int getLevel(long id);

    @Mutable
    @Shadow
    @Final
    protected LongSet nonEmptySections;

    @Mutable
    @Shadow
    @Final
    protected LongSet field_15804;

    @Mutable
    @Shadow
    @Final
    protected LongSet field_15797;

    @Mutable
    @Shadow
    @Final
    private LongSet lightArraysToRemove;

    @Shadow
    protected abstract void onLightArrayCreated(long blockPos);

    @SuppressWarnings("unused")
    @Shadow
    protected volatile boolean hasLightUpdates;

    @Shadow
    protected volatile M uncachedLightArrays;

    @Shadow
    protected abstract ChunkNibbleArray createLightArray(long pos);

    @Shadow
    @Final
    protected Long2ObjectMap<ChunkNibbleArray> lightArraysToAdd;

    @Shadow
    protected abstract boolean hasLightUpdates();

    @Shadow
    protected abstract void onChunkRemoved(long l);

    @Shadow
    @Final
    private static Direction[] DIRECTIONS;

    @Shadow
    protected abstract void removeChunkData(ChunkLightProvider<?, ?> storage, long blockChunkPos);

    @Shadow
    protected abstract ChunkNibbleArray getLightArray(long sectionPos, boolean cached);

    @Shadow
    @Final
    private ChunkProvider chunkProvider;

    @Shadow
    @Final
    private LightType lightType;

    @Shadow
    @Final
    private LongSet field_25621;

    @Override
    @Invoker("getLightArray")
    public abstract ChunkNibbleArray callGetLightSection(final long sectionPos, final boolean cached);

    @Shadow
    protected int getInitialLevel(long id) {
        return 0;
    }

    private final StampedLock uncachedLightArraysLock = new StampedLock();

    /**
     * Replaces the two set of calls to unpack the XYZ coordinates from the input to just one, storing the result as local
     * variables.
     *
     * Additionally, this handles lookups for positions without an associated lightmap.
     *
     * @reason Use faster implementation
     * @author JellySquid
     */
    @Overwrite
    public int get(long blockPos) {
        int x = BlockPos.unpackLongX(blockPos);
        int y = BlockPos.unpackLongY(blockPos);
        int z = BlockPos.unpackLongZ(blockPos);

        long chunk = ChunkSectionPos.asLong(ChunkSectionPos.getSectionCoord(x), ChunkSectionPos.getSectionCoord(y), ChunkSectionPos.getSectionCoord(z));

        ChunkNibbleArray array = this.getLightArray(chunk, true);

        if (array == null) {
            return this.getLightWithoutLightmap(blockPos);
        }

        return array.get(ChunkSectionPos.getLocalCoord(x), ChunkSectionPos.getLocalCoord(y), ChunkSectionPos.getLocalCoord(z));
    }

    /**
     * An extremely important optimization is made here in regards to adding items to the pending notification set. The
     * original implementation attempts to add the coordinate of every chunk which contains a neighboring block position
     * even though a huge number of loop iterations will simply map to block positions within the same updating chunk.
     * <p>
     * Our implementation here avoids this by pre-calculating the min/max chunk coordinates so we can iterate over only
     * the relevant chunk positions once. This reduces what would always be 27 iterations to just 1-8 iterations.
     *
     * @reason Use faster implementation
     * @author JellySquid
     */
    @Overwrite
    public void set(long blockPos, int value) {
        int x = BlockPos.unpackLongX(blockPos);
        int y = BlockPos.unpackLongY(blockPos);
        int z = BlockPos.unpackLongZ(blockPos);

        long chunkPos = ChunkSectionPos.asLong(x >> 4, y >> 4, z >> 4);

        final ChunkNibbleArray lightmap = this.getOrAddLightmap(chunkPos);
        final int oldVal = lightmap.get(x & 15, y & 15, z & 15);

        this.beforeLightChange(blockPos, oldVal, value, lightmap);
        this.changeLightmapComplexity(chunkPos, this.getLightmapComplexityChange(blockPos, oldVal, value, lightmap));

        if (this.field_15802.add(chunkPos)) {
            this.lightArrays.replaceWithCopy(chunkPos);
        }

        ChunkNibbleArray nibble = this.getLightArray(chunkPos, true);
        nibble.set(x & 15, y & 15, z & 15, value);

        for (int z2 = (z - 1) >> 4; z2 <= (z + 1) >> 4; ++z2) {
            for (int x2 = (x - 1) >> 4; x2 <= (x + 1) >> 4; ++x2) {
                for (int y2 = (y - 1) >> 4; y2 <= (y + 1) >> 4; ++y2) {
                    this.dirtySections.add(ChunkSectionPos.asLong(x2, y2, z2));
                }
            }
        }
    }

    /**
     * @author PhiPro
     * @reason Move large parts of the logic to other methods
     */
    @Overwrite
    public void setLevel(long id, int level) {
        int oldLevel = this.getLevel(id);

        if (oldLevel != 0 && level == 0) {
            this.nonEmptySections.add(id);
            this.field_15804.remove(id);
        }

        if (oldLevel == 0 && level != 0) {
            this.nonEmptySections.remove(id);
            this.field_15797.remove(id);
        }

        if (oldLevel >= 2 && level < 2) {
            this.nonOptimizableSections.add(id);

            if (this.enabledChunks.contains(ChunkSectionPos.withZeroZ(id)) && !this.vanillaLightmapsToRemove.remove(id) && this.getLightArray(id, true) == null) {
                this.lightArrays.put(id, this.createTrivialVanillaLightmap(id));
                this.field_15802.add(id);
                this.lightArrays.clearCache();
            }
        }

        if (oldLevel < 2 && level >= 2) {
            this.nonOptimizableSections.remove(id);

            if (this.enabledChunks.contains(id)) {
                final ChunkNibbleArray lightmap = this.getLightArray(id, true);

                if (lightmap != null && ((IReadonly) lightmap).isReadonly()) {
                    this.vanillaLightmapsToRemove.add(id);
                }
            }
        }
    }

    /**
     * @reason Drastically improve efficiency by making removals O(n) instead of O(16*16*16)
     * @author JellySquid
     */
    @Inject(method = "removeChunkData", at = @At("HEAD"), cancellable = true)
    protected void preRemoveSection(ChunkLightProvider<?, ?> provider, long pos, CallbackInfo ci) {
        if (provider instanceof LightProviderUpdateTracker) {
            ((LightProviderUpdateTracker) provider).cancelUpdatesForChunk(pos);

            ci.cancel();
        }
    }

    /**
     * @author PhiPro
     * @reason Re-implement completely
     */
    @Overwrite
    public void updateLightArrays(ChunkLightProvider<M, ?> chunkLightProvider, boolean doSkylight, boolean skipEdgeLightPropagation) {
        if (!this.hasLightUpdates()) {
            return;
        }

        this.initializeChunks();
        this.removeChunks(chunkLightProvider);
        this.removeTrivialLightmaps(chunkLightProvider);
        this.removeVanillaLightmaps(chunkLightProvider);
        this.addQueuedLightmaps(chunkLightProvider);

        final LongIterator it;

        if (!skipEdgeLightPropagation) {
            it = this.lightArraysToAdd.keySet().iterator();
        } else {
            it = this.field_25621.iterator();
        }
        
        while (it.hasNext()) {
            method_29967(chunkLightProvider, it.nextLong());
        }

        this.field_25621.clear();
        this.lightArraysToAdd.clear();

        // Vanilla would normally iterate back over the map of light arrays to remove those we worked on, but
        // that is unneeded now because we removed them earlier.

        this.hasLightUpdates = false;
    }

    /**
     * @reason Avoid integer boxing, reduce map lookups and iteration as much as possible
     * @author JellySquid
     */
    @Overwrite
    private void method_29967(ChunkLightProvider<M, ?> chunkLightProvider, long pos) {
        if (this.hasLight(pos)) {
            int x = ChunkSectionPos.getWorldCoord(ChunkSectionPos.getX(pos));
            int y = ChunkSectionPos.getWorldCoord(ChunkSectionPos.getY(pos));
            int z = ChunkSectionPos.getWorldCoord(ChunkSectionPos.getZ(pos));

            for (Direction dir : DIRECTIONS) {
                long adjPos = ChunkSectionPos.offset(pos, dir);

                // Avoid updating initializing chunks unnecessarily
                if (this.lightArraysToAdd.containsKey(adjPos)) {
                    continue;
                }

                // If there is no light data for this section yet, skip it
                if (!this.hasLight(adjPos)) {
                    continue;
                }

                for (int u1 = 0; u1 < 16; ++u1) {
                    for (int u2 = 0; u2 < 16; ++u2) {
                        long a;
                        long b;

                        switch (dir) {
                            case DOWN:
                                a = BlockPos.asLong(x + u2, y, z + u1);
                                b = BlockPos.asLong(x + u2, y - 1, z + u1);
                                break;
                            case UP:
                                a = BlockPos.asLong(x + u2, y + 15, z + u1);
                                b = BlockPos.asLong(x + u2, y + 16, z + u1);
                                break;
                            case NORTH:
                                a = BlockPos.asLong(x + u1, y + u2, z);
                                b = BlockPos.asLong(x + u1, y + u2, z - 1);
                                break;
                            case SOUTH:
                                a = BlockPos.asLong(x + u1, y + u2, z + 15);
                                b = BlockPos.asLong(x + u1, y + u2, z + 16);
                                break;
                            case WEST:
                                a = BlockPos.asLong(x, y + u1, z + u2);
                                b = BlockPos.asLong(x - 1, y + u1, z + u2);
                                break;
                            case EAST:
                                a = BlockPos.asLong(x + 15, y + u1, z + u2);
                                b = BlockPos.asLong(x + 16, y + u1, z + u2);
                                break;
                            default:
                                continue;
                        }

                        ((LightInitializer) chunkLightProvider).spreadLightInto(a, b);
                    }
                }
            }
        }
    }

    /**
     * @reason
     * @author JellySquid
     */
    @Overwrite
    public void notifyChunkProvider() {
        if (!this.field_15802.isEmpty()) {
            // This could result in changes being flushed to various arrays, so write lock.
            long stamp = this.uncachedLightArraysLock.writeLock();

            try {
                // This only performs a shallow copy compared to before
                M map = this.lightArrays.copy();
                map.disableCache();

                this.uncachedLightArrays = map;
            } finally {
                this.uncachedLightArraysLock.unlockWrite(stamp);
            }

            this.field_15802.clear();
        }

        if (!this.dirtySections.isEmpty()) {
            LongIterator it = this.dirtySections.iterator();

            while(it.hasNext()) {
                long pos = it.nextLong();

                this.chunkProvider.onLightUpdate(this.lightType, ChunkSectionPos.from(pos));
            }

            this.dirtySections.clear();
        }
    }

    @Override
    public M getStorage() {
        return this.uncachedLightArrays;
    }

    @Override
    public StampedLock getStorageLock() {
        return this.uncachedLightArraysLock;
    }

    @Override
    public int getLightWithoutLightmap(final long blockPos) {
        return 0;
    }

    @Unique
    protected void beforeChunkEnabled(final long chunkPos) {
    }

    @Unique
    protected void afterChunkDisabled(final long chunkPos) {
    }

    @Unique
    protected final LongSet enabledChunks = new LongOpenHashSet();
    @Unique
    protected final Long2IntMap lightmapComplexities = setDefaultReturnValue(new Long2IntOpenHashMap(), -1);

    @Unique
    private final LongSet markedEnabledChunks = new LongOpenHashSet();
    @Unique
    private final LongSet markedDisabledChunks = new LongOpenHashSet();
    @Unique
    private final LongSet trivialLightmaps = new LongOpenHashSet();
    @Unique
    private final LongSet vanillaLightmapsToRemove = new LongOpenHashSet();

    // This is put here since the relevant methods to overwrite are located in LightStorage
    @Unique
    protected LongSet nonOptimizableSections = new LongOpenHashSet();

    @Unique
    private static Long2IntMap setDefaultReturnValue(final Long2IntMap map, final int rv) {
        map.defaultReturnValue(rv);
        return map;
    }

    @Unique
    protected ChunkNibbleArray getOrAddLightmap(final long sectionPos) {
        ChunkNibbleArray lightmap = this.getLightArray(sectionPos, true);

        if (lightmap == null) {
            lightmap = this.createLightArray(sectionPos);
        } else {
            if (((IReadonly) lightmap).isReadonly()) {
                lightmap = lightmap.copy();
                this.vanillaLightmapsToRemove.remove(sectionPos);
            } else {
                return lightmap;
            }
        }

        this.lightArrays.put(sectionPos, lightmap);
        this.lightArrays.clearCache();
        this.field_15802.add(sectionPos);

        this.onLightArrayCreated(sectionPos);
        this.setLightmapComplexity(sectionPos, 0);

        return lightmap;
    }

    @Unique
    protected void setLightmapComplexity(final long sectionPos, final int complexity) {
        int oldComplexity = this.lightmapComplexities.put(sectionPos, complexity);

        if (oldComplexity == 0) {
            this.trivialLightmaps.remove(sectionPos);
        }

        if (complexity == 0) {
            this.trivialLightmaps.add(sectionPos);
            this.markForLightUpdates();
        }
    }

    @Unique
    private void markForLightUpdates() {
        // Avoid volatile writes
        if (!this.hasLightUpdates) {
            this.hasLightUpdates = true;
        }
    }

    @Unique
    protected void changeLightmapComplexity(final long sectionPos, final int amount) {
        int complexity = this.lightmapComplexities.get(sectionPos);

        if (complexity == 0) {
            this.trivialLightmaps.remove(sectionPos);
        }

        complexity += amount;
        this.lightmapComplexities.put(sectionPos, complexity);

        if (complexity == 0) {
            this.trivialLightmaps.add(sectionPos);
            this.markForLightUpdates();
        }
    }

    @Unique
    protected ChunkNibbleArray getLightmap(final long sectionPos) {
        final ChunkNibbleArray lightmap = this.getLightArray(sectionPos, true);
        return lightmap == null || ((IReadonly) lightmap).isReadonly() ? null : lightmap;
    }

    @Unique
    protected boolean hasLightmap(final long sectionPos) {
        return this.getLightmap(sectionPos) != null;
    }

    /**
     * Set up lightmaps and adjust complexities as needed for the given light change.
     * Actions are only required for other affected positions, not for the given <code>blockPos</code> directly.
     */
    @Unique
    protected void beforeLightChange(final long blockPos, final int oldVal, final int newVal, final ChunkNibbleArray lightmap) {
    }

    @Unique
    protected int getLightmapComplexityChange(final long blockPos, final int oldVal, final int newVal, final ChunkNibbleArray lightmap) {
        return 0;
    }

    /**
     * Set up lightmaps and adjust complexities as needed for the given lightmap change.
     * Actions are only required for other affected sections, not for the given <code>sectionPos</code> directly.
     */
    @Unique
    protected void beforeLightmapChange(final long sectionPos, final ChunkNibbleArray oldLightmap, final ChunkNibbleArray newLightmap) {
    }

    @Unique
    protected int getInitialLightmapComplexity(final long sectionPos, final ChunkNibbleArray lightmap) {
        return 0;
    }

    /**
     * Determines whether light updates should be propagated into the given section.
     * @author PhiPro
     * @reason Method completely changed. Allow child mixins to properly extend this.
     */
    @Overwrite
    public boolean hasLight(final long sectionPos) {
        return this.enabledChunks.contains(ChunkSectionPos.withZeroZ(sectionPos));
    }

    @Shadow
    protected abstract void setLightEnabled(long columnPos, boolean enabled);

    @Override
    @Invoker("setLightEnabled")
    public abstract void invokeSetColumnEnabled(final long chunkPos, final boolean enabled);

    @Override
    public void setLightUpdatesEnabled(final long chunkPos, final boolean enabled) {
        if (enabled) {
            if (this.markedDisabledChunks.remove(chunkPos) || this.enabledChunks.contains(chunkPos)) {
                return;
            }

            this.markedEnabledChunks.add(chunkPos);
            this.markForLightUpdates();
        } else {
            if (this.markedEnabledChunks.remove(chunkPos) || !this.enabledChunks.contains(chunkPos)) {
                for (int i = -1; i < 17; ++i) {
                    final long sectionPos = ChunkSectionPos.asLong(ChunkSectionPos.getX(chunkPos), i, ChunkSectionPos.getZ(chunkPos));

                    if (this.lightArrays.removeChunk(sectionPos) != null) {
                        this.field_15802.add(sectionPos);
                    }
                }

                this.setLightEnabled(chunkPos, false);
            } else {
                this.markedDisabledChunks.add(chunkPos);
                this.markForLightUpdates();
            }
        }
    }

    @Unique
    private void initializeChunks() {
        this.lightArrays.clearCache();

        for (final LongIterator it = this.markedEnabledChunks.iterator(); it.hasNext(); ) {
            final long chunkPos = it.nextLong();

            this.beforeChunkEnabled(chunkPos);

            // First need to register all lightmaps via onLoadSection() as this data is needed for calculating the initial complexity

            for (int i = -1; i < 17; ++i) {
                final long sectionPos = ChunkSectionPos.asLong(ChunkSectionPos.getX(chunkPos), i, ChunkSectionPos.getZ(chunkPos));

                if (this.hasLightmap(sectionPos)) {
                    this.onLightArrayCreated(sectionPos);
                }
            }

            // Now the initial complexities can be computed

            for (int i = -1; i < 17; ++i) {
                final long sectionPos = ChunkSectionPos.asLong(ChunkSectionPos.getX(chunkPos), i, ChunkSectionPos.getZ(chunkPos));

                if (this.hasLightmap(sectionPos)) {
                    this.setLightmapComplexity(sectionPos, this.getInitialLightmapComplexity(sectionPos, this.getLightArray(sectionPos, true)));
                }
            }

            // Add lightmaps for vanilla compatibility and try to recover stripped data from vanilla saves

            for (int i = -1; i < 17; ++i) {
                final long sectionPos = ChunkSectionPos.asLong(ChunkSectionPos.getX(chunkPos), i, ChunkSectionPos.getZ(chunkPos));

                if (this.nonOptimizableSections.contains(sectionPos) && this.getLightArray(sectionPos, true) == null) {
                    this.lightArrays.put(sectionPos, this.createInitialVanillaLightmap(sectionPos));
                    this.field_15802.add(sectionPos);
                }
            }

            this.enabledChunks.add(chunkPos);
        }

        this.lightArrays.clearCache();

        this.markedEnabledChunks.clear();
    }

    @Unique
    protected ChunkNibbleArray createInitialVanillaLightmap(final long sectionPos) {
        return this.createTrivialVanillaLightmap(sectionPos);
    }

    @Unique
    protected ChunkNibbleArray createTrivialVanillaLightmap(final long sectionPos) {
        return new EmptyChunkNibbleArray();
    }

    @Unique
    private void removeChunks(final ChunkLightProvider<?, ?> lightProvider) {
        for (final LongIterator it = this.markedDisabledChunks.iterator(); it.hasNext(); ) {
            final long chunkPos = it.nextLong();

            // First need to remove all pending light updates before changing any light value

            for (int i = -1; i < 17; ++i) {
                final long sectionPos = ChunkSectionPos.asLong(ChunkSectionPos.getX(chunkPos), i, ChunkSectionPos.getZ(chunkPos));

                if (this.hasLight(sectionPos)) {
                    this.removeChunkData(lightProvider, sectionPos);
                }
            }

            // Now the chunk can be disabled

            this.enabledChunks.remove(chunkPos);

            // Now lightmaps can be removed

            int sections = 0;

            for (int i = -1; i < 17; ++i) {
                final long sectionPos = ChunkSectionPos.asLong(ChunkSectionPos.getX(chunkPos), i, ChunkSectionPos.getZ(chunkPos));

                this.lightArraysToAdd.remove(sectionPos);

                if (this.removeLightmap(sectionPos)) {
                    sections |= 1 << (i + 1);
                }
            }

            // Calling onUnloadSection() after removing all the lightmaps is slightly more efficient

            this.lightArrays.clearCache();

            for (int i = -1; i < 17; ++i) {
                if ((sections & (1 << (i + 1))) != 0) {
                    this.onChunkRemoved(ChunkSectionPos.asLong(ChunkSectionPos.getX(chunkPos), i, ChunkSectionPos.getZ(chunkPos)));
                }
            }

            this.setLightEnabled(chunkPos, false);
            this.afterChunkDisabled(chunkPos);
        }

        this.markedDisabledChunks.clear();
    }

    /**
     * Removes the lightmap associated to the provided <code>sectionPos</code>, but does not call {@link #onChunkRemoved(long)} or {@link ChunkToNibbleArrayMap#clearCache()}
     * @return Whether a lightmap was removed
     */
    @Unique
    protected boolean removeLightmap(final long sectionPos) {
        if (this.lightArrays.removeChunk(sectionPos) == null) {
            return false;
        }

        this.field_15802.add(sectionPos);

        if (this.lightmapComplexities.remove(sectionPos) == -1) {
            this.vanillaLightmapsToRemove.remove(sectionPos);
            return false;
        } else {
            this.trivialLightmaps.remove(sectionPos);
            return true;
        }
    }

    @Unique
    private void removeTrivialLightmaps(final ChunkLightProvider<?, ?> lightProvider) {
        for (final LongIterator it = this.trivialLightmaps.iterator(); it.hasNext(); ) {
            final long sectionPos = it.nextLong();

            this.lightArrays.removeChunk(sectionPos);
            this.lightmapComplexities.remove(sectionPos);
            this.field_15802.add(sectionPos);
        }

        this.lightArrays.clearCache();

        // Calling onUnloadSection() after removing all the lightmaps is slightly more efficient

        for (final LongIterator it = this.trivialLightmaps.iterator(); it.hasNext(); ) {
            this.onChunkRemoved(it.nextLong());
        }

        // Add trivial lightmaps for vanilla compatibility

        for (final LongIterator it = this.trivialLightmaps.iterator(); it.hasNext(); ) {
            final long sectionPos = it.nextLong();

            if (this.nonOptimizableSections.contains(sectionPos)) {
                this.lightArrays.put(sectionPos, this.createTrivialVanillaLightmap(sectionPos));
            }
        }

        this.lightArrays.clearCache();

        // Remove pending light updates for sections that no longer support light propagations

        for (final LongIterator it = this.trivialLightmaps.iterator(); it.hasNext(); ) {
            final long sectionPos = it.nextLong();

            if (!this.hasLight(sectionPos)) {
                this.removeChunkData(lightProvider, sectionPos);
            }
        }

        this.trivialLightmaps.clear();
    }

    @Unique
    private void removeVanillaLightmaps(final ChunkLightProvider<?, ?> lightProvider) {
        for (final LongIterator it = this.vanillaLightmapsToRemove.iterator(); it.hasNext(); ) {
            final long sectionPos = it.nextLong();

            this.lightArrays.removeChunk(sectionPos);
            this.field_15802.add(sectionPos);
        }

        this.lightArrays.clearCache();

        // Remove pending light updates for sections that no longer support light propagations

        for (final LongIterator it = this.vanillaLightmapsToRemove.iterator(); it.hasNext(); ) {
            final long sectionPos = it.nextLong();

            if (!this.hasLight(sectionPos)) {
                this.removeChunkData(lightProvider, sectionPos);
            }
        }

        this.vanillaLightmapsToRemove.clear();
    }

    @Unique
    private void addQueuedLightmaps(final ChunkLightProvider<?, ?> lightProvider) {
        for (final ObjectIterator<Long2ObjectMap.Entry<ChunkNibbleArray>> it = Long2ObjectMaps.fastIterator(this.lightArraysToAdd); it.hasNext(); ) {
            final Long2ObjectMap.Entry<ChunkNibbleArray> entry = it.next();

            final long sectionPos = entry.getLongKey();
            final ChunkNibbleArray lightmap = entry.getValue();

            final ChunkNibbleArray oldLightmap = this.getLightmap(sectionPos);

            if (lightmap != oldLightmap) {
                this.removeChunkData(lightProvider, sectionPos);

                this.beforeLightmapChange(sectionPos, oldLightmap, lightmap);

                this.lightArrays.put(sectionPos, lightmap);
                this.lightArrays.clearCache();
                this.field_15802.add(sectionPos);

                if (oldLightmap == null) {
                    this.onLightArrayCreated(sectionPos);
                }

                this.vanillaLightmapsToRemove.remove(sectionPos);
                this.setLightmapComplexity(sectionPos, this.getInitialLightmapComplexity(sectionPos, lightmap));
            }
        }
    }

    /**
     * @author PhiPro
     * @reason Add lightmaps for disabled chunks directly to the world
     */
    @Overwrite
    public void setLightArray(final long sectionPos, final ChunkNibbleArray array, final boolean bl) {
        final boolean chunkEnabled = this.enabledChunks.contains(ChunkSectionPos.withZeroZ(sectionPos));

        if (array != null) {
            if (chunkEnabled) {
                this.lightArraysToAdd.put(sectionPos, array);
                this.markForLightUpdates();
            } else {
                this.lightArrays.put(sectionPos, array);
                this.field_15802.add(sectionPos);
            }

            if (!bl) {
                this.field_25621.add(sectionPos);
            }
        } else {
            if (chunkEnabled) {
                this.lightArraysToAdd.remove(sectionPos);
            } else {
                this.lightArrays.removeChunk(sectionPos);
                this.field_15802.add(sectionPos);
            }
        }
    }

    // Queued lightmaps are only added to the world via updateLightmaps()
    @Redirect(
        method = "createLightArray(J)Lnet/minecraft/world/chunk/ChunkNibbleArray;",
        slice = @Slice(
            from = @At(
                value = "FIELD",
                target = "Lnet/minecraft/world/chunk/light/LightStorage;lightArraysToAdd:Lit/unimi/dsi/fastutil/longs/Long2ObjectMap;",
                opcode = Opcodes.GETFIELD
            )
        ),
        at = @At(
            value = "INVOKE",
            target = "Lit/unimi/dsi/fastutil/longs/Long2ObjectMap;get(J)Ljava/lang/Object;",
            ordinal = 0,
            remap = false
        )
    )
    private Object cancelLightmapLookupFromQueue(final Long2ObjectMap<ChunkNibbleArray> lightmapArray, final long pos) {
        return null;
    }

    @Redirect(
        method = "getLevel(J)I",
        slice = @Slice(
            from = @At(
                value = "FIELD",
                target = "Lnet/minecraft/world/chunk/light/LightStorage;lightArrays:Lnet/minecraft/world/chunk/ChunkToNibbleArrayMap;",
                opcode = Opcodes.GETFIELD
            )
        ),
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/chunk/ChunkToNibbleArrayMap;containsKey(J)Z",
            ordinal = 0
        )
    )
    private boolean isNonOptimizable(final ChunkToNibbleArrayMap<?> lightmapArray, final long sectionPos) {
        return this.nonOptimizableSections.contains(sectionPos);
    }
}
