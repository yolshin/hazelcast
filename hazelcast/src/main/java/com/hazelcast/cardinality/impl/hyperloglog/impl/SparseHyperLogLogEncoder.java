/*
 * Copyright (c) 2008-2017, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.cardinality.impl.hyperloglog.impl;

import com.hazelcast.cardinality.impl.CardinalityEstimatorDataSerializerHook;
import com.hazelcast.nio.Bits;
import com.hazelcast.nio.ObjectDataInput;
import com.hazelcast.nio.ObjectDataOutput;

import java.io.IOException;
import java.util.Arrays;

/**
 * 1. http://static.googleusercontent.com/media/research.google.com/en//pubs/archive/40671.pdf
 */
@SuppressWarnings("checkstyle:magicnumber")
public class SparseHyperLogLogEncoder implements HyperLogLogEncoder  {

    private static final long P_PRIME_FENCE_MASK = 0x8000000000L;
    private static final int DEFAULT_TEMP_CAPACITY = 200;

    private int p;
    private int pMask;
    private int pPrime;
    private int pPrimeMask;
    private long pDiffMask;
    private long pDiffEncodedMask;

    private VariableLengthDiffArray register;

    private int[] temp;
    private int mPrime;
    private int tempIdx;

    public SparseHyperLogLogEncoder() {
    }

    public SparseHyperLogLogEncoder(final int p, final int pPrime) {
        init(p, pPrime, new VariableLengthDiffArray());
    }

    public void init(int p, int pPrime, VariableLengthDiffArray register) {
        this.p = p;
        this.pPrime = pPrime;
        this.pPrimeMask = (1 << pPrime) - 1;
        this.mPrime = 1 << pPrime;
        this.temp = new int[DEFAULT_TEMP_CAPACITY];

        this.pMask = ((1 << p) - 1);
        this.pDiffMask = pPrimeMask ^ pMask;
        this.pDiffEncodedMask = (1L << (pPrime - p)) - 1;
        this.register = register;
    }

    @Override
    public boolean add(long hash) {
        int encoded = encodeHash(hash);
        temp[tempIdx++] = encoded;
        boolean isTempAtCapacity = tempIdx == DEFAULT_TEMP_CAPACITY;
        if (isTempAtCapacity) {
            mergeAndResetTmp();
        }

        return true;
    }

    @Override
    public long estimate() {
        mergeAndResetTmp();
        return linearCounting(mPrime, mPrime - register.total);
    }

    @Override
    public int getFactoryId() {
        return CardinalityEstimatorDataSerializerHook.F_ID;
    }

    @Override
    public int getId() {
        return CardinalityEstimatorDataSerializerHook.HLL_SPARSE_ENC;
    }

    @Override
    public void writeData(ObjectDataOutput out) throws IOException {
        mergeAndResetTmp();
        out.writeInt(p);
        out.writeInt(pPrime);
        out.writeInt(register.total);
        out.writeInt(register.mark);
        out.writeInt(register.prev);
        out.writeByteArray(register.elements);
    }

    @Override
    public void readData(ObjectDataInput in) throws IOException {
        int p = in.readInt();
        int pPrime = in.readInt();
        int total = in.readInt();
        int mark = in.readInt();
        int prev = in.readInt();
        byte[] bytes = in.readByteArray();
        init(p, pPrime, new VariableLengthDiffArray(bytes, total, mark, prev));
    }

    @Override
    public HyperLogLogEncoding getEncodingType() {
        return HyperLogLogEncoding.SPARSE;
    }

    @Override
    public int getMemoryFootprint() {
        return register.mark + (DEFAULT_TEMP_CAPACITY * Bits.INT_SIZE_IN_BYTES);
    }

    public HyperLogLogEncoder asDense() {
        byte[] dense = new byte[1 << this.p];
        for (int hash : register.explode()) {
            int index = decodeHashPIndex(hash);
            dense[index] = (byte) Math.max(dense[index], decodeHashRunOfZeros(hash));
        }

        return new DenseHyperLogLogEncoder(p, dense);
    }

    private int encodeHash(long hash) {
        int index = (int) (hash & pPrimeMask) << (32 - pPrime);
        if ((hash & pDiffMask) == 0) {
            return index | Long.numberOfTrailingZeros((hash >>> pPrime) | P_PRIME_FENCE_MASK) << 1 | 0x1;
        }

        return ((index >>> (32 - pPrime)) & pPrimeMask) << 1;
    }

    private int decodeHashPPrimeIndex(int hash) {
        if (!hasRunOfZerosEncoded(hash)) {
            return ((hash >> 1) & pPrimeMask) & mPrime - 1;
        }

        return (hash >> (32 - pPrime) & pPrimeMask) & mPrime - 1;
    }

    private int decodeHashPIndex(long hash) {
        if (!hasRunOfZerosEncoded(hash)) {
            return (int) ((hash >>> 1)) & pMask;
        }

        return (int) (hash >>> (32 - pPrime)) & pMask;
    }

    private byte decodeHashRunOfZeros(long hash) {
        if (!hasRunOfZerosEncoded(hash)) {
            // |-25bits-||-1bit-
            // (p - p') || 0
            int pDiff = (int) ((hash >>> 1) & pDiffEncodedMask);
            return (byte) (Integer.numberOfTrailingZeros(pDiff) + 1);
        }

        // |-25bits-||-6bits-||-1bit-|
        // (p - p') || p(w') || 1
        int pW = (int) (hash & ((1 << (32 - pPrime)) - 1)) >>> 1;
        return (byte) (pW + (pPrime - p) + 1);
    }

    private boolean hasRunOfZerosEncoded(long hash) {
        // is format (p - p') || p(w') || 1
        return ((hash & 0x1) == 1);
    }

    private long linearCounting(final int total, final int empty) {
        return (long) (total * Math.log(total / (double) empty));
    }

    private void mergeAndResetTmp() {
        if (tempIdx == 0) {
            return;
        }

        // merge existing register with temp
        int[] old = register.explode();
        int[] all = Arrays.copyOf(old, old.length + tempIdx);

        System.arraycopy(temp, 0, all, old.length, tempIdx);
        Arrays.sort(all);

        // clear register, re-inserting will be in different order, due to new values
        register.clear();

        int previousHash = all[0];
        for (int i = 1; i < all.length; i++) {
            int hash = all[i];
            boolean conflictingIndex = decodeHashPPrimeIndex(hash) == decodeHashPPrimeIndex(previousHash);

            if (!conflictingIndex) {
                register.add(previousHash);
            }

            previousHash = hash;
        }

        register.add(previousHash);
        Arrays.fill(temp, 0);
        tempIdx = 0;
    }

    /**
     * Variable length difference encoding for sorted integer lists.
     *
     * Single byte, (7 bits) used to store the value if less or equal to 127,
     * or more bytes for larger numbers, having the MSB bit set to 1 to signify
     * the next_flag. Also, numbers are stored as a diff from the previous one
     * to make the Variable Length algo more efficient. Therefore, the input must
     * be sorted first.
     */
    private static class VariableLengthDiffArray {
        //aka 32
        private static final int INITIAL_CAPACITY = 1 << 5;

        private byte[] elements = new byte[INITIAL_CAPACITY];

        private int prev;
        private int total;
        private int mark;

        VariableLengthDiffArray() {
        }

        VariableLengthDiffArray(final byte[] elements, final int total, final int mark, final int prev) {
            this.elements = elements;
            this.total = total;
            this.mark = mark;
            this.prev = prev;
        }

        void add(int value) {
            append(value - prev);
            prev = value;
        }

        void clear() {
            Arrays.fill(elements, (byte) 0);
            mark = 0;
            total = 0;
            prev = 0;
        }

        int[] explode() {
            int[] exploded = new int[total];
            int counter = 0;

            int last = 0;
            for (int i = 0; i < mark; i++) {
                int noOfBytes = 0;
                byte element;

                do {
                    element = elements[i++];
                    exploded[counter] |= (element & 0x7F) << (7 * noOfBytes++);
                } while (needsMoreBytes(element));

                exploded[counter] += last;
                last = exploded[counter];

                // fix positions
                i--;
                counter++;
            }

            return exploded;
        }

        private void append(int diff) {
            while (diff > 0x7F) {
                ensureCapacity();
                elements[mark++] = (byte) ((diff & 0x7F) | 0x80);
                diff >>>= 7;
            }

            ensureCapacity();
            elements[mark++] = (byte) (diff & 0x7F);
            total++;
        }

        private void ensureCapacity() {
            if (elements.length == mark) {
                int newCapacity = elements.length << 1;
                elements = Arrays.copyOf(elements, newCapacity);
            }
        }

        private boolean needsMoreBytes(byte val) {
            return (val & 0x80) != 0;
        }
    }
}
