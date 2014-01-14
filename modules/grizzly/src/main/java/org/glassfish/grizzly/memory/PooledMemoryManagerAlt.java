/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2013-2014 Oracle and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * https://glassfish.dev.java.net/public/CDDL+GPL_1_1.html
 * or packager/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at packager/legal/LICENSE.txt.
 *
 * GPL Classpath Exception:
 * Oracle designates this particular file as subject to the "Classpath"
 * exception as provided by Oracle in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */
package org.glassfish.grizzly.memory;


import org.glassfish.grizzly.Buffer;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;

import org.glassfish.grizzly.monitoring.DefaultMonitoringConfig;
import org.glassfish.grizzly.monitoring.MonitoringConfig;
import org.glassfish.grizzly.monitoring.MonitoringUtils;

/**
 * A {@link MemoryManager} implementation based on a series of shared memory pools.
 * Each pool contains multiple buffers of the fixed length specific for this pool.
 *
 * There are several tuning options for this {@link MemoryManager} implementation.
 * <ul>
 *     <li>The base size of the buffer for the 1st pool, every next pool n will have buffer size equal to bufferSize(n-1) * 2^growthFactor.</li>
 *     <li>The number of pools, responsible for allocation of buffers of a pool-specific size.</li>
 *     <li>The buffer size growth factor, that defines 2^x multiplier, used to calculate buffer size for next allocated pool.</li>
 *     <li>The number of pool slices that every pool will stripe allocation requests across.</li>
 *     <li>The percentage of the heap that this manager will use when populating the pools.</li>
 * </ul>
 *
 * If no explicit configuration is provided, the following defaults will be used:
 * <ul>
 *     <li>Base buffer size: 4 KiB ({@link #DEFAULT_BASE_BUFFER_SIZE}).</li>
 *     <li>Number of pools: 3 ({@link #DEFAULT_NUMBER_OF_POOLS}).</li>
 *     <li>Growth factor: 2 ({@link #DEFAULT_GROWTH_FACTOR}), which means the first buffer pool will contains buffer of size 4 KiB, the seconds one buffer of size 16KiB, the third one buffer of size 64KiB.</li>
 *     <li>Number of pool slices: Based on the return value of <code>Runtime.getRuntime().availableProcessors()</code>.</li>
 *     <li>Percentage of heap: 10% ({@link #DEFAULT_HEAP_USAGE_PERCENTAGE}).</li>
 * </ul>
 *
 * The main advantage of this manager over {@link org.glassfish.grizzly.memory.HeapMemoryManager} or
 * {@link org.glassfish.grizzly.memory.ByteBufferManager} is that this implementation doesn't use ThreadLocal pools
 * and as such, doesn't suffer from the memory fragmentation/reallocation cycle that can impact the ThreadLocal versions.
 *
 * @since 3.0
 */
public class PooledMemoryManagerAlt implements MemoryManager<Buffer>, WrapperAware {

    public static final int DEFAULT_BASE_BUFFER_SIZE = 4 * 1024;
    public static final int DEFAULT_NUMBER_OF_POOLS = 3;
    public static final int DEFAULT_GROWTH_FACTOR = 2;
    
    public static final float DEFAULT_HEAP_USAGE_PERCENTAGE = 0.1f;

    private static final boolean IS_SKIP_BUF_WAIT_LOOP =
            Boolean.getBoolean(PooledMemoryManagerAlt.class.getName() + ".skip-buf-wait-loop");
    
    /**
     * Basic monitoring support.  Concrete implementations of this class need
     * only to implement the {@link #createJmxManagementObject()}  method
     * to plug into the Grizzly 2.0 JMX framework.
     */
    protected final DefaultMonitoringConfig<MemoryProbe> monitoringConfig =
            new DefaultMonitoringConfig<MemoryProbe>(MemoryProbe.class) {

                @Override
                public Object createManagementObject() {
                    return createJmxManagementObject();
                }

            };

    // number of pools with different buffer sizes
    private final Pool[] pools;

    // the max buffer size pooled by this memory manager
    private final int maxPooledBufferSize;


    // ------------------------------------------------------------ Constructors


    /**
     * Creates a new <code>PooledMemoryManager</code> using the following defaults:
     * <ul>
     *     <li>4 KiB base buffer size.</li>
     *     <li>3 pools.</li>
     *     <li>2 growth factor, which means 1st pool will contain buffers of size 4KiB, the 2nd - 16KiB, the 3rd - 64KiB.</li>
     *     <li>Number of pool slices based on <code>Runtime.getRuntime().availableProcessors()</code></li>
     *     <li>The initial allocation will use 10% of the heap.</li>
     * </ul>
     */
    public PooledMemoryManagerAlt() {
        this(DEFAULT_BASE_BUFFER_SIZE,
                DEFAULT_NUMBER_OF_POOLS,
                DEFAULT_GROWTH_FACTOR,
                Runtime.getRuntime().availableProcessors(),
                DEFAULT_HEAP_USAGE_PERCENTAGE);
    }


    /**
     * Creates a new <code>PooledMemoryManager</code> using the specified parameters for configuration.
     *
     * @param baseBufferSize the base size of the buffer for the 1st pool, every next pool n will have buffer size equal to bufferSize(n-1) * 2^growthFactor.
     * @param numberOfPools the number of pools, responsible for allocation of buffers of a pool-specific size.
     * @param growthFactor the buffer size growth factor, that defines 2^x multiplier, used to calculate buffer size for next allocated pool.
     * @param numberOfPoolSlices the number of pool slices that every pool will stripe allocation requests across
     * @param percentOfHeap percentage of the heap that will be used when populating the pools.
     */
    public PooledMemoryManagerAlt(
            final int baseBufferSize,
            final int numberOfPools,
            final int growthFactor,
            final int numberOfPoolSlices,
            final float percentOfHeap) {
        if (baseBufferSize <= 0) {
            throw new IllegalArgumentException("baseBufferSize must be greater than zero");
        }
        if (numberOfPools <= 0) {
            throw new IllegalArgumentException("numberOfPools must be greater than zero");
        }
        if (growthFactor == 0 && numberOfPools > 1) {
            throw new IllegalArgumentException("if numberOfPools is greater than 0 - growthFactor must be greater than zero");
        }
        if (growthFactor < 0) {
            throw new IllegalArgumentException("growthFactor must be greater or equal to zero");
        }
        if (numberOfPoolSlices <= 0) {
            throw new IllegalArgumentException("numberOfPoolSlices must be greater than zero");
        }

        if (!isPowerOfTwo(baseBufferSize) || !isPowerOfTwo(growthFactor)) {
            throw new IllegalArgumentException("minBufferSize and growthFactor must be a power of two");
        }

        if (percentOfHeap <= 0.0f || percentOfHeap >= 1.0f) {
            throw new IllegalArgumentException("percentOfHeap must be greater than zero and less than 1.");
        }
        
        final long heapSize = Runtime.getRuntime().maxMemory();
        final long memoryPerSubPool = (long) (heapSize * percentOfHeap / numberOfPools);

        pools = new Pool[numberOfPools];
        for (int i = 0, bufferSize = baseBufferSize; i < numberOfPools; i++, bufferSize <<= growthFactor) {
            pools[i] = new Pool(bufferSize, memoryPerSubPool,
                    numberOfPoolSlices, monitoringConfig);
        }
        maxPooledBufferSize = pools[numberOfPools - 1].bufferSize;
    }

    
    // ---------------------------------------------- Methods from MemoryManager


    /**
     * For this implementation, this method simply calls through to
     * {@link #allocateAtLeast(int)};
     */
    @Override
    public Buffer allocate(final int size) {
        if (size < 0) {
            throw new IllegalArgumentException("Requested allocation size must be greater than or equal to zero.");
        }
        return allocateAtLeast(size).limit(size);
    }

    /**
     * Allocates a buffer of at least the size requested.
     * <p/>
     * Keep in mind that the capacity of the buffer may be greater than the
     * allocation request.  The limit however, will be set to the specified
     * size.  The memory beyond the limit, is available for use.
     *
     * @param size the min {@link Buffer} size to be allocated.
     * @return a buffer with a limit of the specified <tt>size</tt>.
     */
    @Override
    public Buffer allocateAtLeast(int size) {
        if (size < 0) {
            throw new IllegalArgumentException("Requested allocation size must be greater than or equal to zero.");
        }
        
        if (size == 0) {
            return Buffers.EMPTY_BUFFER;
        }
        
        return size <= maxPooledBufferSize ?
                getPoolFor(size).allocate() :
                allocateToCompositeBuffer(newCompositeBuffer(), size);
    }

    /**
     * Reallocates an existing buffer to at least the specified size.
     *
     * @param oldBuffer old {@link Buffer} to be reallocated.
     * @param newSize   new {@link Buffer} required size.
     *
     * @return potentially a new buffer of at least the specified size.
     */
    @Override
    public Buffer reallocate(final Buffer oldBuffer, final int newSize) {
        if (newSize == 0) {
            oldBuffer.tryDispose();
            return Buffers.EMPTY_BUFFER;
        }
        
        final int curBufSize = oldBuffer.capacity();
        
        if (oldBuffer instanceof PoolBuffer) {
            if (curBufSize >= newSize) {
                final PoolBuffer oldPoolBuffer = (PoolBuffer) oldBuffer;
                
                final Pool newPool = getPoolFor(newSize);
                if (newPool != oldPoolBuffer.owner.owner) {
                    final int pos = Math.min(oldPoolBuffer.position(), newSize);
                    final int lim = Math.min(oldPoolBuffer.limit(), newSize);
                    
                    final Buffer newPoolBuffer = newPool.allocate();
                    Buffers.setPositionLimit(oldPoolBuffer, 0, newSize);
                    newPoolBuffer.put(oldPoolBuffer);
                    Buffers.setPositionLimit(newPoolBuffer, pos, lim);
                    
                    oldPoolBuffer.tryDispose();
                    
                    return newPoolBuffer;
                }
                
                return oldPoolBuffer.limit(newSize);
            } else {
                final int pos = oldBuffer.position();
                final int lim = oldBuffer.limit();
                Buffers.setPositionLimit(oldBuffer, 0, curBufSize);
                
                if (newSize <= maxPooledBufferSize) {
                    
                    final Pool newPool = getPoolFor(newSize);
                    
                    final Buffer newPoolBuffer = newPool.allocate();
                    newPoolBuffer.put(oldBuffer);
                    Buffers.setPositionLimit(newPoolBuffer, pos, lim);
                    
                    oldBuffer.tryDispose();
                    
                    return newPoolBuffer;
                } else {
                    final CompositeBuffer cb = newCompositeBuffer();
                    cb.append(oldBuffer);
                    allocateToCompositeBuffer(cb, newSize - curBufSize);
                    Buffers.setPositionLimit(cb, pos, newSize);
                    return cb;
                }
            }
        } else {
            assert oldBuffer instanceof CompositeBuffer;
            final CompositeBuffer oldCompositeBuffer = (CompositeBuffer) oldBuffer;
            if (curBufSize > newSize) {
                final int oldPos = oldCompositeBuffer.position();
                Buffers.setPositionLimit(oldBuffer, newSize, newSize);
                oldCompositeBuffer.trim();
                oldCompositeBuffer.position(Math.min(oldPos, newSize));
                
                return oldCompositeBuffer;
            } else {
                return allocateToCompositeBuffer(oldCompositeBuffer,
                        newSize - curBufSize);
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void release(final Buffer buffer) {
        buffer.tryDispose();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean willAllocateDirect(final int size) {
        return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public MonitoringConfig<MemoryProbe> getMonitoringConfig() {
        return monitoringConfig;
    }


    // ----------------------------------------------- Methods from WrapperAware


    @Override
    public Buffer wrap(final byte[] data) {
        return wrap(ByteBuffer.wrap(data));
    }

    @Override
    public Buffer wrap(byte[] data, int offset, int length) {
        return wrap(ByteBuffer.wrap(data, offset, length));
    }

    @Override
    public Buffer wrap(final String s) {
        return wrap(s.getBytes(Charset.defaultCharset()));
    }

    @Override
    public Buffer wrap(final String s, final Charset charset) {
        return wrap(s.getBytes(charset));
    }

    @Override
    public Buffer wrap(final ByteBuffer byteBuffer) {
        return new ByteBufferWrapper(byteBuffer);
    }


    // ---------------------------------------------------------- Public Methods


    public Pool[] getPools() {
        return Arrays.copyOf(pools, pools.length);
    }


    // ------------------------------------------------------- Protected Methods


    protected Object createJmxManagementObject() {
        
        return MonitoringUtils.loadJmxObject(
                "org.glassfish.grizzly.memory.jmx.PooledMemoryManager", this,
                PooledMemoryManager.class);
    }


    // --------------------------------------------------------- Private Methods


    private Pool getPoolFor(final int size) {
        for (int i = 0; i < pools.length; i++) {
            final Pool pool = pools[i];
            if (pool.bufferSize >= size) {
                return pool;
            }
        }

        throw new IllegalStateException(
                "There is no pool big enough to allocate " + size + " bytes");
    }

    private CompositeBuffer allocateToCompositeBuffer(
            final CompositeBuffer cb, int size) {

        assert size >= 0;

        final boolean oldAppendable = cb.isAppendable();
        cb.setAppendable(true);

        if (size >= maxPooledBufferSize) {
            final Pool maxBufferSizePool = pools[pools.length - 1];

            do {
                cb.append(maxBufferSizePool.allocate());
                size -= maxPooledBufferSize;
            } while (size >= maxPooledBufferSize);
        }

        for (int i = 0; i < pools.length; i++) {
            final Pool pool = pools[i];
            if (pool.bufferSize >= size) {
                cb.append(pool.allocate());
                break;
            }
        }

        cb.setAppendable(oldAppendable);

        return cb;
    }

    private CompositeBuffer newCompositeBuffer() {
        final CompositeBuffer cb = CompositeBuffer.newBuffer(this);
        cb.allowInternalBuffersDispose(true);
        cb.allowBufferDispose(true);
        return cb;
    }

    private static boolean isPowerOfTwo(final int valueToCheck) {
        return ((valueToCheck & (valueToCheck - 1)) == 0);
    }

    /*
     * Propagates right-most one bit to the right.  Each shift right
     * will set all of the bits between the original and new position to one.
     *
     * Ex.  If the value is 16, i.e.:
     *     0x0000 0000 0000 0000 0000 0000 0001 0000
     * the result of this call will be:
     *     0x0000 0000 0000 0000 0000 0000 0001 1111
     * or 31.
     *
     * In our case, we're using the result of this method as a
     * mask.
     *
     * Part of this algorithm came from HD Figure 15-5.
     */
    private static int fillHighestOneBitRight(int value) {
        value |= (value >> 1);
        value |= (value >> 2);
        value |= (value >> 4);
        value |= (value >> 8);
        value |= (value >> 16);
        return value;
    }

    public static class Pool {
        private final PoolSlice[] slices;
        private final int bufferSize;

        public Pool(final int bufferSize, final long memoryPerSubPool,
                final int numberOfPoolSlices,
                final DefaultMonitoringConfig<MemoryProbe> monitoringConfig) {
            this.bufferSize = bufferSize;
            slices = new PoolSlice[numberOfPoolSlices];
            final long memoryPerSlice = memoryPerSubPool / numberOfPoolSlices;
            
            for (int i = 0; i < numberOfPoolSlices; i++) {
                slices[i] = new PoolSlice(this, memoryPerSlice, bufferSize,
                        monitoringConfig);
            }
        }

        public int elementsCount() {
            int sum = 0;
            for (int i = 0; i < slices.length; i++) {
                sum += slices[i].elementsCount();
            }
            
            return sum;
        }
        
        public long size() {
            return (long) elementsCount() * (long) bufferSize;
        }
        
        public int getBufferSize() {
            return bufferSize;
        }
        
        public PoolSlice[] getSlices() {
            return Arrays.copyOf(slices, slices.length);
        }
        
        public Buffer allocate() {
            final PoolSlice slice = getSlice();
            PoolBuffer b = slice.poll();
            if (b == null) {
                b = slice.allocate();
            }
            b.allowBufferDispose(true);
            b.free = false;
            return b;
        }

        @Override
        public String toString() {
            final StringBuilder sb = new StringBuilder(
                    "Pool[" + Integer.toHexString(hashCode()) + "] {" +
                    "buffer size=" + bufferSize +
                    ", slices count=" + slices.length);
            
            for (int i = 0; i < slices.length; i++) {
                if (i == 0) {
                    sb.append("\n");
                }
                
                sb.append("\t[").append(i).append("] ")
                        .append(slices[i].toString()).append('\n');
            }
            
            sb.append('}');
            return sb.toString();
        }
        
        @SuppressWarnings("unchecked")
        private PoolSlice getSlice() {
            return slices[ThreadLocalRandom.current().nextInt(slices.length)];
        }
    }

    /*
     *   This array backed by this pool can only support
     *   2^30-1 elements instead of the usual 2^32-1.
     *   This is because we use bit 30 to store information about the read
     *   and write pointer 'wrapping' status.  Without these bits, it's
     *   difficult to tell if the array is full or empty when both read and
     *   write pointers are equal.
     *   The pool is considered full when read and write pointers
     *   refer to the same index and the aforementioned bits are equal.
     *   The same logic is applied to determine if the pool is empty, except
     *   the bits are not equal.
     */
    public static class PoolSlice {

        // Array index stride.
        private static final int STRIDE = 16;

        // Apply this mask to obtain the first 30 bits of an integer
        // less the bits for wrap and offset.
        private static final int MASK = 0x3FFFFFFF;

        // Apply this mask to get/set the wrap status bit.
        private static final int WRAP_BIT_MASK = 0x40000000;

        // Using an AtomicReferenceArray to ensure proper visibility of items
        // within the pool which will be shared across threads.
        private final PaddedAtomicReferenceArray<PoolBuffer> pool1, pool2;

        // Maintain two different pointers for reading/writing to reduce
        // contention.
        private final PaddedAtomicInteger pollIdx = new PaddedAtomicInteger(0);
        private final PaddedAtomicInteger offerIdx = new PaddedAtomicInteger(WRAP_BIT_MASK);

        // The Pool this slice belongs to
        private final Pool owner;
        
        // The max size of the pool.
        private final int maxPoolSize;

        // individual buffer size.
        private final int bufferSize;

        // MemoryProbe configuration.
        private final DefaultMonitoringConfig<MemoryProbe> monitoringConfig;


        // -------------------------------------------------------- Constructors


        PoolSlice(final Pool owner,
                   final long totalPoolSize,
                   final int bufferSize,
                   final DefaultMonitoringConfig<MemoryProbe> monitoringConfig) {

            this.owner = owner;
            this.bufferSize = bufferSize;
            this.monitoringConfig = monitoringConfig;
            int initialSize = (int) (totalPoolSize / ((long) bufferSize));

            // Round up to the nearest multiple of 16 (STRIDE).  This is
            // done as elements will be accessed at (offset + index + STRIDE).
            // Offset is calculated each time we overflow the array.
            // This access scheme should help us avoid false sharing.
            maxPoolSize = ((initialSize + (STRIDE - 1)) & ~(STRIDE - 1));

            // poolSize must be less than or equal to 2^30 - 1.
            if (maxPoolSize >= WRAP_BIT_MASK) {
                throw new IllegalStateException(
                        "Cannot manage a pool larger than 2^30-1");
            }

            pool1 = new PaddedAtomicReferenceArray<PoolBuffer>(maxPoolSize);
            for (int i = 0; i < maxPoolSize; i++) {
                PoolBuffer b = allocate();
                b.allowBufferDispose(true);
                b.free = true;
                pool1.lazySet(i, b);
            }
            pool2 = new PaddedAtomicReferenceArray<PoolBuffer>(maxPoolSize);
        }


        // ------------------------------------------------------ Public Methods


        public final PoolBuffer poll() {
            int pollIdx;
            for (;;) {
                pollIdx = this.pollIdx.get();
                final int offerIdx = this.offerIdx.get();
                
                // weak isEmpty check, might return false positives
                if (isEmpty(pollIdx, offerIdx)) {
                    return null;
                }
                
                final int nextPollIdx = nextIndex(pollIdx);
                if (this.pollIdx.compareAndSet(pollIdx, nextPollIdx)) {
                    break;
                }
            }
            
            PoolBuffer pb;
            if (!IS_SKIP_BUF_WAIT_LOOP) {
                final int unmaskedPollIdx = unmask(pollIdx);
                final AtomicReferenceArray<PoolBuffer> pool = pool(pollIdx);
                for (;;) {
                    // unmask the current read value to the actual array index.
                    pb = pool.getAndSet(unmaskedPollIdx, null);
                    if (pb != null) {
                        break;
                    }
                    // give offer at this index time to complete...
                    Thread.yield();
                }
            } else {
                pb = pool(pollIdx).getAndSet(unmask(pollIdx), null);
                if (pb == null) {
                    return null;
                }
            }
            
            ProbeNotifier.notifyBufferAllocatedFromPool(monitoringConfig,
                                                        bufferSize);
            return pb;
        }

        public final boolean offer(final PoolBuffer b) {
            if (b.owner != this) {
                return false;
            }
            
            int offerIdx;
            for (;;) {
                offerIdx = this.offerIdx.get();
                final int pollIdx = this.pollIdx.get();
                
                // weak isFull check, might return false positives
                if (isFull(pollIdx, offerIdx)) {
                    return false;
                }
                final int nextOfferIndex = nextIndex(offerIdx);
                if (this.offerIdx.compareAndSet(offerIdx, nextOfferIndex)) {
                    break;
                }
            }
            
            if (!IS_SKIP_BUF_WAIT_LOOP) {
                final int unmaskedOfferIdx = unmask(offerIdx);
                final AtomicReferenceArray<PoolBuffer> pool = pool(offerIdx);
                for (;;) {
                    // unmask the current write value to the actual array index.
                    if (pool.compareAndSet(unmaskedOfferIdx, null, b)) {
                        break;
                    }
                    // give poll at this index time to complete...
                    Thread.yield();
                }
            } else {
                if (!pool(offerIdx).compareAndSet(unmask(offerIdx), null, b)) {
                    return false;
                }
            }
            
            ProbeNotifier.notifyBufferReleasedToPool(monitoringConfig,
                                                     bufferSize);
            
            return true;
        }

        public final int elementsCount() {
            return elementsCount(pollIdx.get(), offerIdx.get());
        }

        /*
         * There are two cases to consider.
         *  1) When both indexes are on the same array.
         *  2) When index are on different arrays (i.e., the wrap bit is set
         *     on the index value).
         *
         *  When both indexes are on the same array, then to calculate
         *  the number of elements, we have to 'de-virtualize' the indexes
         *  and simply subtract the result.
         *
         *  When the indexes are on different arrays, the result of subtracting
         *  the 'de-virtualized' indexes will be negative.  We then have to
         *  add the result of and-ing the maxPoolSize with a mask consisting
         *  of the first 31 bits being all ones.
         */
        private int elementsCount(final int ridx, final int widx) {
            return unstride(unmask(widx)) - unstride(unmask(ridx)) +
                    (maxPoolSize & fillHighestOneBitRight(
                            (ridx ^ widx) & WRAP_BIT_MASK));
        }

        public final long size() {
            return (long) elementsCount() * (long) bufferSize;
        }
        
        public void clear() {
            //noinspection StatementWithEmptyBody
            while (poll() != null) ;
        }

        public PoolBuffer allocate() {
            final PoolBuffer buffer = new PoolBuffer(
                    ByteBuffer.allocate(bufferSize),
                    this);
            ProbeNotifier.notifyBufferAllocated(monitoringConfig, bufferSize);
            return buffer;
        }


        // ----------------------------------------------------- Private Methods


        private static boolean isFull(final int pollIdx, final int offerIdx) {
            return (pollIdx ^ offerIdx) == WRAP_BIT_MASK;
        }

        private static boolean isEmpty(final int pollIdx, final int offerIdx) {
            return pollIdx == offerIdx;
        }

        private AtomicReferenceArray<PoolBuffer> pool(final int idx) {
            return (idx & WRAP_BIT_MASK) == 0 ? pool1 : pool2;
        }

        private int nextIndex(final int currentIdx) {
            final int arrayIndex = unmask(currentIdx);
            if (arrayIndex + STRIDE < maxPoolSize) {
                // add stride and return
                return currentIdx + STRIDE;
            } else {
                final int offset = arrayIndex - maxPoolSize + STRIDE + 1;
                
                return offset == STRIDE ?
                    // we reached the end on the current array,
                    // set lower 30 bits to zero and flip the wrap bit.
                    WRAP_BIT_MASK ^ (currentIdx & WRAP_BIT_MASK) :
                    // otherwise we stay on the same array, just flip the index
                    // considering the current offset
                    offset | (currentIdx & WRAP_BIT_MASK);
            }
        }

        /*
         * Return lower 30 bits, i.e., the actual array index.
         */
        private static int unmask(final int val) {
            return val & MASK;
        }

        /*
         * Return only the wrapping bit.
         */
        private static int getWrappingBit(final int val) {
            return val & WRAP_BIT_MASK;
        }

        /*
         * Calculate the index value without stride and offset.
         */
        private int unstride(final int idx) {
            return idx / STRIDE + (idx % STRIDE) * (maxPoolSize / STRIDE);
        }
        
        @Override
        public String toString() {
            return toString(pollIdx.get(), offerIdx.get());
        }

        private String toString(final int ridx, final int widx) {
            return "BufferSlice[" + Integer.toHexString(hashCode()) + "] {" +
                                "buffer size=" + bufferSize +
                                ", elements in pool=" + elementsCount(ridx, widx) +
                                ", poll index=" + unmask(ridx) +
                                ", poll wrap bit=" + (fillHighestOneBitRight(
                    getWrappingBit(ridx)) & 1) +
                                ", offer index=" + unmask(widx) +
                                ", offer wrap bit=" + (fillHighestOneBitRight(
                    getWrappingBit(widx)) & 1) +
                                ", maxPoolSize=" + maxPoolSize +
                                '}';
        }

        /*
         * We pad the default AtomicInteger implementation as the offer/poll
         * pointers will be highly contended.  The padding ensures that
         * each AtomicInteger is within it's own cacheline thus reducing
         * false sharing.
         */
        @SuppressWarnings("UnusedDeclaration")
        static final class PaddedAtomicInteger extends AtomicInteger {
            private long p0, p1, p2, p3, p4, p5, p6, p7 = 7l;


            PaddedAtomicInteger(int initialValue) {
                super(initialValue);
            }

        } // END PaddedAtomicInteger

        /*
         * Padded in order to avoid false sharing when the arrays used by AtomicReferenceArray
         * are laid out end-to-end (pointer in array one is at end of the array and
         * pointer two in array two is at the beginning - both elements could be loaded
         * onto the same cacheline).
         */
        @SuppressWarnings("UnusedDeclaration")
        static final class PaddedAtomicReferenceArray<E>
                extends AtomicReferenceArray<E> {
            private long p0, p1, p2, p3, p4, p5, p6, p7 = 7l;

            PaddedAtomicReferenceArray(int length) {
                super(length);
            }

        } // END PaddedAtomicReferenceArray

    } // END BufferPool


    public static class PoolBuffer extends ByteBufferWrapper {

        // The pool slice to which this Buffer instance will be returned.
        private final PoolSlice owner;

        // When this Buffer instance resides in the pool, this flag will
        // be true.
        boolean free;

        // represents the number of 'child' buffers that have been created using
        // this as the foundation.  This source buffer can't be returned
        // to the pool unless this value is zero.
        protected final AtomicInteger shareCount;

        // represents the original buffer from the pool.  This value will be
        // non-null in any 'child' buffers created from the original.
        protected final PoolBuffer source;

        // Used for the special case of the split() method.  This maintains
        // the original wrapper from the pool which must ultimately be returned.
        private ByteBuffer origVisible;


        // ------------------------------------------------------------ Constructors


        /**
         * Creates a new PoolBuffer instance wrapping the specified
         * {@link java.nio.ByteBuffer}.
         *
         * @param underlyingByteBuffer the {@link java.nio.ByteBuffer} instance to wrap.
         * @param owner the {@link org.glassfish.grizzly.memory.PooledMemoryManager.BufferPool} that owns
         *              this <tt>PoolBuffer</tt> instance.
         */
        PoolBuffer(final ByteBuffer underlyingByteBuffer,
                   final PoolSlice owner) {
            this(underlyingByteBuffer, owner, null, new AtomicInteger());
        }

        /**
         * Creates a new PoolBuffer instance wrapping the specified
         * {@link java.nio.ByteBuffer}.
         *
         * @param underlyingByteBuffer the {@link java.nio.ByteBuffer} instance to wrap.
         * @param owner                the {@link org.glassfish.grizzly.memory.PooledMemoryManager.BufferPool} that owns
         *                             this <tt>PoolBuffer</tt> instance.
         *                             May be <tt>null</tt>.
         * @param source               the <tt>PoolBuffer</tt> that is the
         *                             'parent' of this new buffer instance.  May be <tt>null</tt>.
         * @param shareCount          shared reference to an {@link java.util.concurrent.atomic.AtomicInteger} that enables
         *                             shared buffer book-keeping.
         *
         * @throws IllegalArgumentException if <tt>underlyingByteBuffer</tt> or <tt>shareCount</tt>
         *                                  are <tt>null</tt>.
         */
        private PoolBuffer(final ByteBuffer underlyingByteBuffer,
                           final PoolSlice owner,
                           final PoolBuffer source,
                           final AtomicInteger shareCount) {
            super(underlyingByteBuffer);
            if (underlyingByteBuffer == null) {
                throw new IllegalArgumentException("underlyingByteBuffer cannot be null.");
            }
            if (shareCount == null) {
                throw new IllegalArgumentException("shareCount cannot be null");
            }

            this.owner = owner;
            this.shareCount = shareCount;
            this.source = source;
        }


        // ------------------------------------------ Methods from ByteBufferWrapper


        /**
         * Overrides the default behavior to only consider a buffer disposed when
         * it is no longer shared.  When invoked and this buffer isn't shared,
         * the buffer will be cleared and returned back to the pool.
         */
        @Override
        public void dispose() {
            if (free) {
                return;
            }
            free = true;
            // if shared count is greater than 0, decrement and take no further
            // action
            if (shareCount.get() != 0) {
                shareCount.decrementAndGet();
            } else {
                // if source is available and has been disposed, we can now
                // safely return source back to the queue
                if (source != null && source.free) {
                    // this block will be executed if this buffer was split.
                    if (source.origVisible != null) {
                        source.visible = source.origVisible;
                    }
                    source.visible.clear();
                    if (!source.owner.offer(source)) {
                        // queue couldn't accept the buffer, allow GC to reclaim it
                        source.visible = null;
                    }
                } else {
                    // this block executes in the simple case where the original
                    // buffer is allocated and returned to the pool without sharing
                    // the data across multiple buffer instances
                    if (owner != null) {
                        // this block will be executed if this buffer was split.
                        if (origVisible != null) {
                            visible = origVisible;
                        }
                        visible.clear();
                        if (!owner.offer(this)) {
                            // queue couldn't accept the buffer, allow GC to reclaim it
                            visible = null;
                        }
                    }
                }
            }
        }


        /**
         * Override the default implementation to check the <tt>free</tt> status
         * of this buffer (i.e., once released, operations on the buffer will no
         * longer succeed).
         */
        @Override
        protected final void checkDispose() {
            if (free) {
                throw new IllegalStateException(
                        "PoolBuffer has already been disposed",
                        disposeStackTrace);
            }
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public Buffer split(final int splitPosition) {
            checkDispose();
            final int oldPosition = position();
            final int oldLimit = limit();
            // we have to save the original ByteBuffer before we split
            // in order to restore the result prior to returning the result
            // to the pool.
            origVisible = visible;
            Buffers.setPositionLimit(visible, 0, splitPosition);
            ByteBuffer slice1 = visible.slice();
            Buffers.setPositionLimit(visible, splitPosition, visible.capacity());
            ByteBuffer slice2 = visible.slice();

            if (oldPosition < splitPosition) {
                slice1.position(oldPosition);
            } else {
                slice1.position(slice1.capacity());
                slice2.position(oldPosition - splitPosition);
            }

            if (oldLimit < splitPosition) {
                slice1.limit(oldLimit);
                slice2.limit(0);
            } else {
                slice2.limit(oldLimit - splitPosition);
            }


            this.visible = slice1;

            return wrap(slice2);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public ByteBufferWrapper asReadOnlyBuffer() {
            checkDispose();
            return wrap(visible.asReadOnlyBuffer());
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public ByteBufferWrapper duplicate() {
            checkDispose();
            return wrap(visible.duplicate());
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public ByteBufferWrapper slice() {
            checkDispose();
            return wrap(visible.slice());
        }


        // ----------------------------------------------------- Private Methods


        private ByteBufferWrapper wrap(final ByteBuffer buffer) {
            final PoolBuffer b =
                    new PoolBuffer(buffer,
                            null, // don't keep track of the owner for child buffers
                            ((source == null) ? this : source), // pass the 'parent' buffer along
                            shareCount); // pass the shareCount
            b.allowBufferDispose(true);
            b.shareCount.incrementAndGet();

            return b;
        }

    } // END PoolBuffer

    public static void main(String[] args) {
        PooledMemoryManagerAlt alt = new PooledMemoryManagerAlt();
        System.out.println(alt.getPools()[0].elementsCount());
    }

}
