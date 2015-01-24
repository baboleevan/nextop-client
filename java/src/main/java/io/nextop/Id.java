package io.nextop;

import com.google.common.hash.Hashing;
import io.nextop.util.HexBytes;

import java.lang.IllegalArgumentException;import java.lang.Object;import java.lang.Override;import java.lang.String;import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.UUID;

/** 256-bit UUID.
 * @see #create */
public final class Id {
    private static final SecureRandom sr = new SecureRandom();

    /** generate a 256-bit UUID as a 128-bit UUID + 128 bits of randomness
     * @see java.util.UUID */
    public static Id create() {
        UUID uuid16 = UUID.randomUUID();
        byte[] rand16 = new byte[16];
        sr.nextBytes(rand16);

        return new Id(ByteBuffer.allocate(32
        ).putLong(uuid16.getMostSignificantBits()
        ).putLong(uuid16.getLeastSignificantBits()
        ).put(rand16
        ).array());
    }

    public static Id valueOf(String s) throws IllegalArgumentException {
        if (64 != s.length()) {
            throw new IllegalArgumentException();
        }
        byte[] bytes = HexBytes.valueOf(s);
        if (32 != bytes.length) {
            throw new IllegalArgumentException();
        }

        Id id = new Id(bytes);
        assert id.toString().equals(s);
        return id;
    }



    private final byte[] bytes;
    private final int offset;
    private final long hashCode;


    Id(byte[] bytes) {
        this(bytes, 0);
    }
    Id(byte[] bytes, int offset) {
        this.bytes = bytes;
        this.offset = offset;
        hashCode = Hashing.murmur3_128().hashBytes(bytes, offset, 32).asLong();
    }


    @Override
    public String toString() {
        return HexBytes.toString(bytes, offset, 32);
    }

    @Override
    public int hashCode() {
        return (int) hashCode;
    }

    public long longHashCode() {
        return hashCode;
    }


    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof Id)) {
            return false;
        }
        Id b = (Id) obj;
        return hashCode == b.hashCode && Arrays.equals(bytes, b.bytes);
    }
}
