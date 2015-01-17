package io.nextop;

import com.google.common.base.Charsets;
import com.google.common.collect.AbstractIterator;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.apache.commons.codec.binary.Base64OutputStream;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.util.*;


// TODO
// wire value v2:
// - fixed size index (23-bit)
// - incremental headers
// -- lru maintainenace to keep within a fixed size
// -- store the compressed value in the lru map
// -- never compress int* of float*
// - use ByteBuffer in API instead of byte[]

// more efficient codec than text, that allows a lossless (*or nearly, for floats) conversion to text when needed
// like protobufs/bson but focused on easy conversion to text
// conversion of text is important when constructing HTTP requests, which are test-based
// TODO do compression of values and objects keysets by having a header in the front. up to the 127 that save the most bytes to compress
// TODO long term the compression table can be stateful, so transferring the same objects repeatedly will have a savings, like a dynamic protobuf
// TODO wire value even in a simple form will increase speed between cloud and client, because binary will be less data and faster to parse on client
// TODO can always call toString to get JSON out (if type is array or object)

// FIXME look at sizes if the lut is stateful, and the lut didn't need to be resent each time

// FIXME memory and compressed wire values should have same hashcode and equals


// FIXME everywhere replace byte[] with ByteBuffer
public abstract class WireValue {
    // just use int constants here
    public static enum Type {
        UTF8,
        BLOB,
        INT32,
        INT64,
        FLOAT32,
        FLOAT64,
        BOOLEAN,
        MAP,
        LIST

    }





    // FIXME parse
//    public static WireValue valueOf(byte[] bytes, int offset, int n) {
//
//        // expect compressed format here
//
//    }
//


    static int intv(byte[] bytes, int offset) {
        return ((0xFF & bytes[offset]) << 24)
                | ((0xFF & bytes[offset + 1]) << 16)
                | ((0xFF & bytes[offset + 2]) << 8)
                | (0xFF & bytes[offset + 3]);
    }
    static long longv(byte[] bytes, int offset) {
        return ((0xFFL & bytes[offset]) << 56)
                | ((0xFFL & bytes[offset + 1]) << 48)
                | ((0xFFL & bytes[offset + 2]) << 40)
                | ((0xFFL & bytes[offset + 3]) << 32)
                | ((0xFFL & bytes[offset + 4]) << 24)
                | ((0xFFL & bytes[offset + 5]) << 16)
                | ((0xFFL & bytes[offset + 6]) << 8)
                | (0xFFL & bytes[offset + 7]);
    }

    static WireValue valueOf(byte[] bytes) {
        int offset = 0;
        int h = 0xFF & bytes[offset];
        if ((h & H_COMPRESSED) == H_COMPRESSED) {
            int nb = h & ~H_COMPRESSED;
            int size = intv(bytes, offset + 1);
            // next 4 is size in bytes
            int[] offsets = new int[size + 1];
            offsets[0] = offset + 9;
            if (0 < size) {
                for (int i = 1; i <= size; ++i) {
                    offsets[i] = offsets[i - 1] + _byteSize(bytes, offsets[i - 1]);
                }
            }


            CompressionState cs = new CompressionState(bytes, offset, offsets, nb);
            return valueOf(bytes, offsets[size], cs);
        }
        return valueOf(bytes, offset, null);
    }


    static WireValue valueOf(byte[] bytes, int offset, CompressionState cs) {
        int h = 0xFF & bytes[offset];
        if ((h & H_COMPRESSED) == H_COMPRESSED) {
            int luti = h & ~H_COMPRESSED;
            for (int i = 1; i < cs.nb; ++i) {
                luti = (luti << 8) | (0xFF & bytes[offset + i]);
            }
            return valueOf(cs.header, cs.offsets[luti], cs);
        }

        switch (h) {
            case H_UTF8:
                return new CUtf8WireValue(bytes, offset, cs);
            case H_BLOB:
                return new CBlobWireValue(bytes, offset, cs);
            case H_INT32:
                return new CInt32WireValue(bytes, offset, cs);
            case H_INT64:
                return new CInt64WireValue(bytes, offset, cs);
            case H_FLOAT32:
                return new CFloat32WireValue(bytes, offset, cs);
            case H_FLOAT64:
                return new CFloat64WireValue(bytes, offset, cs);
            case H_TRUE_BOOLEAN:
                return new BooleanWireValue(true);
            case H_FALSE_BOOLEAN:
                return new BooleanWireValue(false);
            case H_MAP:
                return new CMapWireValue(bytes, offset, cs);
            case H_LIST:
                return new CListWireValue(bytes, offset, cs);
            case H_INT32_LIST:
//                return new CInt32ListWireValue(bytes, offset, cs);
                // FIXME see listh
                throw new IllegalArgumentException();
            case H_INT64_LIST:
//                return new CInt64ListWireValue(bytes, offset, cs);
                // FIXME see listh
                throw new IllegalArgumentException();
            case H_FLOAT32_LIST:
//                return new CFloat32ListWireValue(bytes, offset, cs);
                // FIXME see listh
                throw new IllegalArgumentException();
            case H_FLOAT64_LIST:
//                return new CFloat64ListWireValue(bytes, offset, cs);
                // FIXME see listh
                throw new IllegalArgumentException();
            default:
                throw new IllegalArgumentException("" + h);
        }
    }


    // based on byte[] and views into the byte[] (parsing does not expand into a bunch of objects in memory)
    private static abstract class CompressedWireValue extends WireValue {
        byte[] bytes;
        int offset;
        CompressionState cs;


        CompressedWireValue(Type type, byte[] bytes, int offset, CompressionState cs) {
            super(type);
            this.bytes = bytes;
            this.offset = offset;
            this.cs = cs;
        }


    }


    private static class CUtf8WireValue extends CompressedWireValue{
        CUtf8WireValue(byte[] bytes, int offset, CompressionState cs) {
            super(Type.UTF8, bytes, offset, cs);
        }

        @Override
        public String asString() {
            int length = intv(bytes, offset + 1);
            return new String(bytes, offset + 5, length, Charsets.UTF_8);
        }
        @Override
        public int asInt() {
            throw new UnsupportedOperationException();
        }
        @Override
        public long asLong() {
            throw new UnsupportedOperationException();
        }
        @Override
        public float asFloat() {
            throw new UnsupportedOperationException();
        }
        @Override
        public double asDouble() {
            throw new UnsupportedOperationException();
        }
        @Override
        public boolean asBoolean() {
            throw new UnsupportedOperationException();
        }
        @Override
        public List<WireValue> asList() {
            throw new UnsupportedOperationException();
        }
        @Override
        public Map<WireValue, WireValue> asMap() {
            throw new UnsupportedOperationException();
        }
        @Override
        public ByteBuffer asBlob() {
            throw new UnsupportedOperationException();
        }
    }

    private static class CBlobWireValue extends CompressedWireValue{
        CBlobWireValue(byte[] bytes, int offset, CompressionState cs) {
            super(Type.BLOB, bytes, offset, cs);
        }

        @Override
        public String asString() {
            throw new UnsupportedOperationException();
        }
        @Override
        public int asInt() {
            throw new UnsupportedOperationException();
        }
        @Override
        public long asLong() {
            throw new UnsupportedOperationException();
        }
        @Override
        public float asFloat() {
            throw new UnsupportedOperationException();
        }
        @Override
        public double asDouble() {
            throw new UnsupportedOperationException();
        }
        @Override
        public boolean asBoolean() {
            throw new UnsupportedOperationException();
        }
        @Override
        public List<WireValue> asList() {
            throw new UnsupportedOperationException();
        }
        @Override
        public Map<WireValue, WireValue> asMap() {
            throw new UnsupportedOperationException();
        }
        @Override
        public ByteBuffer asBlob() {
            int length = intv(bytes, offset + 1);
            return ByteBuffer.wrap(bytes, offset + 5, length);
        }
    }


    private static class CMapWireValue extends CompressedWireValue{
        CMapWireValue(byte[] bytes, int offset, CompressionState cs) {
            super(Type.MAP, bytes, offset, cs);
        }

        @Override
        public String asString() {
            throw new UnsupportedOperationException();
        }
        @Override
        public int asInt() {
            throw new UnsupportedOperationException();
        }
        @Override
        public long asLong() {
            throw new UnsupportedOperationException();
        }
        @Override
        public float asFloat() {
            throw new UnsupportedOperationException();
        }
        @Override
        public double asDouble() {
            throw new UnsupportedOperationException();
        }
        @Override
        public boolean asBoolean() {
            throw new UnsupportedOperationException();
        }
        @Override
        public List<WireValue> asList() {
            throw new UnsupportedOperationException();
        }
        @Override
        public Map<WireValue, WireValue> asMap() {

            return new AbstractMap<WireValue, WireValue>() {
                int ds = offset + 5;
                List<WireValue> keys = valueOf(bytes, ds, cs).asList();
                List<WireValue> values = valueOf(bytes, ds + byteSize(bytes, ds, cs), cs).asList();
                int n = keys.size();

                @Override
                public Set<Entry<WireValue, WireValue>> entrySet() {
                    return new AbstractSet<Entry<WireValue, WireValue>>() {
                        @Override
                        public int size() {
                            return n;
                        }
                        @Override
                        public Iterator<Entry<WireValue, WireValue>> iterator() {
                            return new Iterator<Entry<WireValue, WireValue>>() {
                                int i = 0;

                                @Override
                                public boolean hasNext() {
                                    return i < n;
                                }

                                @Override
                                public Entry<WireValue, WireValue> next() {
                                    int j = i++;
                                    return new SimpleImmutableEntry<WireValue, WireValue>(keys.get(j), values.get(j));
                                }
                            };
                        }
                    };
                }
            };
        }
        @Override
        public ByteBuffer asBlob() {
            throw new UnsupportedOperationException();
        }
    }

    private static class CListWireValue extends CompressedWireValue {
        CListWireValue(byte[] bytes, int offset, CompressionState cs) {
            super(Type.LIST, bytes, offset, cs);
        }

        @Override
        public String asString() {
            throw new UnsupportedOperationException();
        }
        @Override
        public int asInt() {
            throw new UnsupportedOperationException();
        }
        @Override
        public long asLong() {
            throw new UnsupportedOperationException();
        }
        @Override
        public float asFloat() {
            throw new UnsupportedOperationException();
        }
        @Override
        public double asDouble() {
            throw new UnsupportedOperationException();
        }
        @Override
        public boolean asBoolean() {
            throw new UnsupportedOperationException();
        }
        @Override
        public List<WireValue> asList() {
            return new AbstractList<WireValue>() {
                int n = intv(bytes, offset + 1);
                int[] offsets = null;
                int offseti = 0;

                @Override
                public int size() {
                    return n;
                }

                @Override
                public WireValue get(int index) {
                    if (index < 0 || n <= index) {
                        throw new IndexOutOfBoundsException();
                    }
                    fillOffsets(index);
                    return valueOf(bytes, offsets[index], cs);
                }


                void fillOffsets(int j) {
                    if (offseti <= j) {
                        if (null == offsets) {
                            offsets = new int[n];
                            offsets[0] = offset + 9;
                            offseti = 1;
                        }

                        int i = offseti;
                        for (; i <= j; ++i) {
                            offsets[i] = offsets[i - 1] + byteSize(bytes, offsets[i - 1], cs);
                        }
                        offseti = i;
                    }
                }
            };
        }
        @Override
        public Map<WireValue, WireValue> asMap() {
            throw new UnsupportedOperationException();
        }
        @Override
        public ByteBuffer asBlob() {
            throw new UnsupportedOperationException();
        }
    }


    private static class CInt32WireValue extends CompressedWireValue {
        CInt32WireValue(byte[] bytes, int offset, CompressionState cs) {
            super(Type.INT32, bytes, offset, cs);
        }

        @Override
        public String asString() {
            throw new UnsupportedOperationException();
        }
        @Override
        public int asInt() {
            return intv(bytes, offset + 1);
        }
        @Override
        public long asLong() {
            throw new UnsupportedOperationException();
        }
        @Override
        public float asFloat() {
            throw new UnsupportedOperationException();
        }
        @Override
        public double asDouble() {
            throw new UnsupportedOperationException();
        }
        @Override
        public boolean asBoolean() {
            throw new UnsupportedOperationException();
        }
        @Override
        public List<WireValue> asList() {
            throw new UnsupportedOperationException();
        }
        @Override
        public Map<WireValue, WireValue> asMap() {
            throw new UnsupportedOperationException();
        }
        @Override
        public ByteBuffer asBlob() {
            throw new UnsupportedOperationException();
        }
    }

    private static class CInt64WireValue extends CompressedWireValue {
        CInt64WireValue(byte[] bytes, int offset, CompressionState cs) {
            super(Type.INT64, bytes, offset, cs);
        }

        @Override
        public String asString() {
            throw new UnsupportedOperationException();
        }
        @Override
        public int asInt() {
            throw new UnsupportedOperationException();
        }
        @Override
        public long asLong() {
            return longv(bytes, offset + 1);
        }
        @Override
        public float asFloat() {
            throw new UnsupportedOperationException();
        }
        @Override
        public double asDouble() {
            throw new UnsupportedOperationException();
        }
        @Override
        public boolean asBoolean() {
            throw new UnsupportedOperationException();
        }
        @Override
        public List<WireValue> asList() {
            throw new UnsupportedOperationException();
        }
        @Override
        public Map<WireValue, WireValue> asMap() {
            throw new UnsupportedOperationException();
        }
        @Override
        public ByteBuffer asBlob() {
            throw new UnsupportedOperationException();
        }
    }

    private static class CFloat32WireValue extends CompressedWireValue {
        CFloat32WireValue(byte[] bytes, int offset, CompressionState cs) {
            super(Type.FLOAT32, bytes, offset, cs);
        }

        @Override
        public String asString() {
            throw new UnsupportedOperationException();
        }
        @Override
        public int asInt() {
            throw new UnsupportedOperationException();
        }
        @Override
        public long asLong() {
            throw new UnsupportedOperationException();
        }
        @Override
        public float asFloat() {
            return Float.intBitsToFloat(intv(bytes, offset + 1));
        }
        @Override
        public double asDouble() {
            throw new UnsupportedOperationException();
        }
        @Override
        public boolean asBoolean() {
            throw new UnsupportedOperationException();
        }
        @Override
        public List<WireValue> asList() {
            throw new UnsupportedOperationException();
        }
        @Override
        public Map<WireValue, WireValue> asMap() {
            throw new UnsupportedOperationException();
        }
        @Override
        public ByteBuffer asBlob() {
            throw new UnsupportedOperationException();
        }
    }

    private static class CFloat64WireValue extends CompressedWireValue {
        CFloat64WireValue(byte[] bytes, int offset, CompressionState cs) {
            super(Type.FLOAT64, bytes, offset, cs);
        }

        @Override
        public String asString() {
            throw new UnsupportedOperationException();
        }
        @Override
        public int asInt() {
            throw new UnsupportedOperationException();
        }
        @Override
        public long asLong() {
            throw new UnsupportedOperationException();
        }
        @Override
        public float asFloat() {
            throw new UnsupportedOperationException();
        }
        @Override
        public double asDouble() {
            return Double.longBitsToDouble(longv(bytes, offset + 1));
        }
        @Override
        public boolean asBoolean() {
            throw new UnsupportedOperationException();
        }
        @Override
        public List<WireValue> asList() {
            throw new UnsupportedOperationException();
        }
        @Override
        public Map<WireValue, WireValue> asMap() {
            throw new UnsupportedOperationException();
        }
        @Override
        public ByteBuffer asBlob() {
            throw new UnsupportedOperationException();
        }
    }




    // LUT sizes: 2^7, 2^15
    // index maps to byte[]
    private static class CompressionState {
        byte[] header;
        int offset;
        int[] offsets;
        int nb;

        CompressionState(byte[] header, int offset, int[] offsets, int nb) {
            this.header = header;
            this.offset = offset;
            this.offsets = offsets;
            this.nb = nb;
        }
    }






    public static WireValue valueOf(JsonElement e) {
        if (e.isJsonPrimitive()) {
            JsonPrimitive p = e.getAsJsonPrimitive();
            if (p.isBoolean()) {
                return of(p.getAsBoolean());
            } else if (p.isNumber()) {
                try {
                    long n = p.getAsLong();
                    if ((int) n == n) {
                        return of((int) n);
                    } else {
                        return of(n);
                    }
                } catch (NumberFormatException x) {
                    double d = p.getAsDouble();
                    if ((float) d == d) {
                        return of((float) d);
                    } else {
                        return of(d);
                    }
                }
            } else if (p.isString()) {
                return of(p.getAsString());
            } else {
                throw new IllegalArgumentException();
            }
        } else if (e.isJsonObject()) {
            JsonObject object = e.getAsJsonObject();
            Map<WireValue, WireValue> m = new HashMap<WireValue, WireValue>(4);
            for (Map.Entry<String, JsonElement> oe : object.entrySet()) {
                m.put(of(oe.getKey()), valueOf(oe.getValue()));
            }
            return of(m);
        } else if (e.isJsonArray()) {
            JsonArray array = e.getAsJsonArray();
            List<WireValue> list = new ArrayList<WireValue>(4);
            for (JsonElement ae : array) {
                list.add(valueOf(ae));
            }
            return of(list);
        } else {
            throw new IllegalArgumentException();
        }
    }


    public static WireValue of(Object value) {
        if (value instanceof WireValue) {
            return (WireValue) value;
        }
        if (value instanceof CharSequence) {
            return of(value.toString());
        }
        if (value instanceof Number) {
            if (value instanceof Integer) {
                return of(((Integer) value).intValue());
            }
            if (value instanceof Long) {
                return of(((Long) value).longValue());
            }
            if (value instanceof Float) {
                return of(((Float) value).floatValue());
            }
            if (value instanceof Double) {
                return of(((Double) value).doubleValue());
            }
            // default int32
            return of(((Number) value).intValue());
        }
        if (value instanceof Boolean) {
            return of(((Boolean) value).booleanValue());
        }
        if (value instanceof byte[]) {
            return of((byte[]) value);
        }
        // FIXME bytebuffer
        if (value instanceof Map) {
            return of((Map<WireValue, WireValue>) value);
        }
        if (value instanceof Collection) {
            if (value instanceof List) {
                return of((List<WireValue>) value);
            }
            return of(new ArrayList<WireValue>(((Collection) value)));
        }
        throw new IllegalArgumentException();
    }

    static WireValue of(byte[] value) {
        return new BlobWireValue(value);
    }
    // FIXME of(ByteBuffer)

    static WireValue of(String value) {
        return new Utf8WireValue(value);
    }

    static WireValue of(int value) {
        return new NumberWireValue(value);
    }

    static WireValue of(long value) {
        return new NumberWireValue(value);
    }

    static WireValue of(float value) {
        return new NumberWireValue(value);
    }

    static WireValue of(double value) {
        return new NumberWireValue(value);
    }

    static WireValue of(boolean value) {
        return new BooleanWireValue(value);
    }

    static WireValue of(Map<WireValue, WireValue> m) {
        return new MapWireValue(m);
    }

    static WireValue of(List<WireValue> list) {
        return new ListWireValue(list);
    }








    final Type type;
    boolean hashCodeSet = false;
    int hashCode;


    WireValue(Type type) {
        this.type = type;
    }


    // FIXME as* functions that represent the wirevalue as something



    public final Type getType() {
        return type;
    }


    @Override
    public final int hashCode() {
        if (!hashCodeSet) {
            hashCode = _hashCode(this);
            hashCodeSet = true;
        }
        return hashCode;
    }
    static int _hashCode(WireValue value) {
        switch (value.type) {
            case UTF8:
                return value.asString().hashCode();
            case BLOB:
                return value.asBlob().hashCode();
            case INT32:
                return Integer.hashCode(value.asInt());
            case INT64:
                return Long.hashCode(value.asLong());
            case FLOAT32:
                return Float.hashCode(value.asFloat());
            case FLOAT64:
                return Double.hashCode(value.asDouble());
            case BOOLEAN:
                return Boolean.hashCode(value.asBoolean());
            case MAP: {
                Map<WireValue, WireValue> map = value.asMap();
                List<WireValue> keys = stableKeys(map);
                List<WireValue> values = stableValues(map, keys);
                int c = 0;
                for (WireValue v : keys) {
                    c = 31 * c + _hashCode(v);
                }
                for (WireValue v : values) {
                    c = 31 * c + _hashCode(v);
                }
                return c;
             }
            case LIST: {
                int c = 0;
                for (WireValue v : value.asList()) {
                    c = 31 * c + _hashCode(v);
                }
                return c;
            }
            default:
                throw new IllegalArgumentException();
        }
    }

    @Override
    public final boolean equals(Object obj) {
        if (!(obj instanceof WireValue)) {
            return false;
        }
        WireValue b = (WireValue) obj;
        return _equals(this, b);
    }
    static boolean _equals(WireValue a, WireValue b) {
        if (!a.type.equals(b.type) || a.hashCode() != b.hashCode()) {
            return false;
        }
        switch (a.type) {
            case UTF8:
                return a.asString().equals(b.asString());
            case BLOB:
                return a.asBlob().equals(b.asBlob());
            case INT32:
                return a.asInt() == b.asInt();
            case INT64:
                return a.asLong() == b.asLong();
            case FLOAT32:
                return a.asFloat() == b.asFloat();
            case FLOAT64:
                return a.asDouble() == b.asDouble();
            case BOOLEAN:
                return a.asBoolean() == b.asBoolean();
            case MAP:
                return a.asMap().equals(b.asMap());
            case LIST:
                return a.asList().equals(b.asList());
            default:
                throw new IllegalArgumentException();
        }
    }


    public String toString() {
        // blob to base64
        // object, array to json (string values underneath get quoted)
        // others to standard string forms
        // FIXME

        StringBuilder sb = new StringBuilder();

        toString(this, sb, false, 0);
        return sb.toString();
    }

    public String toJsonString() {
        StringBuilder sb = new StringBuilder();

        toString(this, sb, true, 0);
        return sb.toString();
    }

    void toString(WireValue value, StringBuilder sb, boolean q, int qe) {
        switch (value.getType()) {
            case UTF8:
                if (q) {
                    sb.append(q(qe)).append(value.asString()).append(q(qe));
                } else {
                    sb.append(value.asString());
                }
                break;
            case BLOB:
                if (q) {
                    sb.append(q(qe)).append(base64(value.asBlob())).append(q(qe));
                } else {
                    sb.append(base64(value.asBlob()));
                }
                break;
            case INT32:
                sb.append(value.asInt());
                break;
            case INT64:
                sb.append(value.asLong());
                break;
            case FLOAT32:
                sb.append(value.asFloat());
                break;
            case FLOAT64:
                sb.append(value.asDouble());
                break;
            case BOOLEAN:
                sb.append(value.asBoolean());
                break;
            case MAP: {
                sb.append("{");
                int c = 0;
                for (Map.Entry<WireValue, WireValue> e : value.asMap().entrySet()) {
                    if (1 < ++c) {
                        sb.append(",");
                    }
                    sb.append(q(qe));
                    toString(e.getKey(), sb, false, qe + 1);
                    sb.append(q(qe)).append(":");
                    toString(e.getValue(), sb, true, qe);
                }
                sb.append("}");
                break;
            }
            case LIST:
                sb.append("[");
                int c = 0;
                for (WireValue v : value.asList()) {
                    if (1 < ++c) {
                        sb.append(",");
                    }
                    toString(v, sb, true, qe);
                }
                sb.append("]");
                break;
            default:
                throw new IllegalArgumentException();
        }
    }
    static String q(int e) {
        StringBuilder sb = new StringBuilder(e + 1);
        for (int i = 0; i < e; ++i) {
            sb.append("\\");
        }
        sb.append("\"");
        return sb.toString();
    }

    static String base64(ByteBuffer bytes) {
        byte[] b64;
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(bytes.remaining() * 4 / 3);
            Base64OutputStream b64os = new Base64OutputStream(baos, true, 0, null);

            WritableByteChannel channel = Channels.newChannel(b64os);
            channel.write(bytes);
            channel.close();

            b64 = baos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return new String(b64, Charsets.UTF_8);
    }




    static final int H_COMPRESSED = 0x80;
    static final int H_UTF8 = 1;
    static final int H_BLOB = 2;
    static final int H_INT32 = 3;
    static final int H_INT64 = 4;
    static final int H_FLOAT32 = 5;
    static final int H_FLOAT64 = 6;
    static final int H_TRUE_BOOLEAN = 7;
    static final int H_FALSE_BOOLEAN = 8;
    static final int H_MAP = 9;
    static final int H_LIST = 10;
    static final int H_INT32_LIST = 11;
    static final int H_INT64_LIST = 12;
    static final int H_FLOAT32_LIST = 13;
    static final int H_FLOAT64_LIST = 14;


    static byte[] header(int nb, int v) {
        switch (nb) {
            case 1:
                return new byte[]{(byte) (v & 0xFF)};
            case 2:
                return new byte[]{(byte) ((v >>> 8) & 0xFF), (byte) (v & 0xFF)};
            case 3:
                return new byte[]{(byte) ((v >>> 16) & 0xFF), (byte) ((v >>> 8) & 0xFF), (byte) (v & 0xFF)};
            default:
                throw new IllegalArgumentException();
        }
    }

    static int listh(List<WireValue> list) {
        return H_LIST;
        // FIXME implement later
//        int n = list.size();
//        if (0 == n) {
//            return H_LIST;
//        }
//        Type t = list.get(0).getType();
//        for (int i = 0; i < n; ++i) {
//            if (!t.equals(list.get(1).getType())) {
//                return H_LIST;
//            }
//        }
//        switch (t) {
//            case INT32:
//                return H_INT32;
//            case INT64:
//                return H_INT64_LIST;
//            case FLOAT32:
//                return H_FLOAT32_LIST;
//            case FLOAT64:
//                return H_FLOAT64_LIST;
//            default:
//                return H_LIST;
//        }
    }




    // toByte should always compress
    static int byteSize(byte[] bytes, int offset, CompressionState cs) {
        int h = 0xFF & bytes[offset];
        if ((h & H_COMPRESSED) == H_COMPRESSED) {
            return cs.nb;
        }
        return _byteSize(bytes, offset);
    }

    static int _byteSize(byte[] bytes, int offset) {
        int h = 0xFF & bytes[offset];
//        System.out.printf("_byteSize %s\n", h);
        switch (h) {
            case H_UTF8:
                return 5 + intv(bytes, offset + 1);
            case H_BLOB:
                return 5 + intv(bytes, offset + 1);
            case H_INT32:
                return 5;
            case H_INT64:
                return 9;
            case H_FLOAT32:
                return 5;
            case H_FLOAT64:
                return 9;
            case H_TRUE_BOOLEAN:
                return 1;
            case H_FALSE_BOOLEAN:
                return 1;
            case H_MAP:
                return 5 + intv(bytes, offset + 1);
            case H_LIST:
                return 9 + intv(bytes, offset + 5);
            case H_INT32_LIST:
                // FIXME see listh
                throw new IllegalArgumentException();
            case H_INT64_LIST:
                // FIXME see listh
                throw new IllegalArgumentException();
            case H_FLOAT32_LIST:
                // FIXME see listh
                throw new IllegalArgumentException();
            case H_FLOAT64_LIST:
                // FIXME see listh
                throw new IllegalArgumentException();
            default:
                throw new IllegalArgumentException("" + h);
        }
    }

    public void toBytes(ByteBuffer bb) {

        Lb lb = new Lb();
        lb.init(this);
        if (lb.opt()) {
            // write the lut header
            bb.put((byte) (H_COMPRESSED | lb.lutNb));
            bb.putInt(lb.lut.size());
            bb.putInt(0);
            int i = bb.position();

            for (Lb.S s : lb.lut) {
                _toBytes(s.value, lb, bb);
            }

            int bytes = bb.position() - i;
            bb.putInt(i - 4, bytes);
            System.out.printf("header %d bytes\n", bytes);
        }

        int i = bb.position();
        toBytes(this, lb, bb);
        int bytes = bb.position() - i;
        System.out.printf("body %d bytes\n", bytes);

    }
    private void toBytes(WireValue value, Lb lb, ByteBuffer bb) {
        int luti = lb.luti(value);
        if (0 <= luti) {
            byte[] header = header(lb.lutNb, luti);
            header[0] |= H_COMPRESSED;
            bb.put(header);
        } else {
            _toBytes(value, lb, bb);
        }
    }
    private void _toBytes(WireValue value, Lb lb, ByteBuffer bb) {
        switch (value.getType()) {
            case MAP: {
                bb.put((byte) H_MAP);
                bb.putInt(0);

                int i = bb.position();

                Map<WireValue, WireValue> m = value.asMap();
                List<WireValue> keys = stableKeys(m);
                toBytes(of(keys), lb, bb);
                List<WireValue> values = stableValues(m, keys);
                toBytes(of(values), lb, bb);

                int bytes = bb.position() - i;
                bb.putInt(i - 4, bytes);

                break;
            }
            case LIST: {
                List<WireValue> list = value.asList();
                int listh = listh(list);
                if (H_LIST == listh) {
                    bb.put((byte) H_LIST);
                    bb.putInt(list.size());
                    bb.putInt(0);

                    int i = bb.position();

                    for (WireValue v : value.asList()) {
                        toBytes(v, lb, bb);
                    }

                    int bytes = bb.position() - i;
                    bb.putInt(i - 4, bytes);
                } else {
                    // primitive homogeneous list
                    bb.put((byte) listh);
                    bb.putInt(list.size());
                    bb.putInt(0);

                    int i = bb.position();

                    switch (listh) {
                        case H_INT32_LIST:
                            for (WireValue v : value.asList()) {
                                bb.putInt(v.asInt());
                            }
                            break;
                        case H_INT64_LIST:
                            for (WireValue v : value.asList()) {
                                bb.putLong(v.asLong());
                            }
                            break;
                        case H_FLOAT32_LIST:
                            for (WireValue v : value.asList()) {
                                bb.putFloat(v.asFloat());
                            }
                            break;
                        case H_FLOAT64_LIST:
                            for (WireValue v : value.asList()) {
                                bb.putDouble(v.asDouble());
                            }
                            break;
                    }

                    int bytes = bb.position() - i;
                    bb.putInt(i - 4, bytes);

                }
                break;
            }
            case BLOB: {
                ByteBuffer b = value.asBlob();

                bb.put((byte) H_BLOB);
                bb.putInt(b.remaining());
                bb.put(b);

                break;
            }
            case UTF8: {
                byte[] b = value.asString().getBytes(Charsets.UTF_8);

                bb.put((byte) H_UTF8);
                bb.putInt(b.length);
                bb.put(b);

                break;
            }
            case INT32:
                bb.put((byte) H_INT32);
                bb.putInt(value.asInt());
                break;
            case INT64:
                bb.put((byte) H_INT64);
                bb.putLong(value.asLong());
                break;
            case FLOAT32:
                bb.put((byte) H_FLOAT32);
                bb.putFloat(value.asFloat());
                break;
            case FLOAT64:
                bb.put((byte) H_FLOAT64);
                bb.putDouble(value.asDouble());
                break;
            case BOOLEAN:
                bb.put((byte) (value.asBoolean() ? H_TRUE_BOOLEAN : H_FALSE_BOOLEAN));
                break;
            default:
                throw new IllegalArgumentException();
        }
    }


    // lut builder
    static class Lb {
        static class S {
            WireValue value;

            int count;

            // FIXME record this in expandOne/expand
            int maxd = -1;
            int maxi = -1;
//            boolean r = false;

            int luti = -1;
        }


        Map<WireValue, S> stats = new HashMap<WireValue, S>(4);
//        List<S> rs = new ArrayList<S>(4);

        List<S> lut = new ArrayList<S>(4);
        int lutNb = 0;


        int luti(WireValue value) {
            S s = stats.get(value);
            assert null != s;
            return s.luti;
        }


        void init(WireValue value) {
            expand(value, 0, 0);
        }

        int expand(WireValue value, int d, int i) {
            switch (value.getType()) {
                case MAP:
                    expandOne(value, d, i);
                    i += 1;
                    // the rest
                    Map<WireValue, WireValue> m = value.asMap();
                    List<WireValue> keys = stableKeys(m);
                    i = expand(of(keys), d + 1, i);
                    List<WireValue> values = stableValues(m, keys);
                    i = expand(of(values), d + 1, i);
                    break;
                case LIST:
                    expandOne(value, d, i);
                    i += 1;
                    // the rest
                    for (WireValue v : value.asList()) {
                        i = expand(v, d + 1, i);
                    }
                    break;
                default:
                    expandOne(value, d, i);
                    i += 1;
                    break;
            }
            return i;
        }
        void expandOne(WireValue value, int d, int i) {
            S s = stats.get(value);
            if (null == s) {
                s = new S();
                s.value = value;
                stats.put(value, s);
            }
            s.count += 1;
            s.maxd = Math.max(s.maxd, d);
            s.maxi = Math.max(s.maxi, i);
//            if (rmask && !s.r) {
//                s.r = true;
//                rs.add(s);
//            }
        }

        boolean opt() {

            int nb = estimateLutNb();
            if (nb <= 0) {
                return false;
            }

            // order r values by maxd
            // if include each, subtract down
            List<S> rs = new ArrayList<S>(stats.values());
            Collections.sort(rs, new Comparator<S>() {
                @Override
                public int compare(S a, S b) {
                    if (a == b) {
                        return 0;
                    }
                    int d = Integer.compare(a.maxd, b.maxd);
                    if (0 != d) {
                        return d;
                    }
                    d = Integer.compare(a.maxi, b.maxi);
                    assert 0 != d;
                    return d;
                }
            });
            for (S s : rs) {
                if (include(s, nb)) {
                    s.luti = lut.size();
                    lut.add(s);
                    collapse(s.value);
                }
            }

            lutNb = nb(lut.size());

            // lut is ordered implicity by maxd

            return true;
        }

        void collapse(WireValue value) {
            switch (value.getType()) {
                case MAP:
                    collapseOne(value);
                    // the rest
                    Map<WireValue, WireValue> m = value.asMap();
                    List<WireValue> keys = stableKeys(m);
                    collapse(of(keys));
                    List<WireValue> values = stableValues(m, keys);
                    collapse(of(values));
                    break;
                case LIST:
                    collapseOne(value);
                    // the rest
                    for (WireValue v : value.asList()) {
                        collapse(v);
                    }
                    break;
                default:
                    collapseOne(value);
                    break;
            }
        }
        void collapseOne(WireValue value) {
            S s = stats.get(value);
            assert null != s;
            s.count -= 1;
        }





        int[] sizes = new int[]{0, 0x10, 0x0100, 0x010000, 0x01000000};
        int nb(int c) {
            int nb = 0;
            while (sizes[nb] < c) {
                ++nb;
            }
            return nb;
        }






        int estimateLutNb() {
            int nb = nb(count(1));
            if (nb == sizes.length) {
                // too many unique values; punt
                return -1;
            }
            int pnb;
            do {
                pnb = nb;
                nb = nb(count(nb));
            } while (nb < pnb);

            return pnb;
        }
        int count(int nb) {
            int c = 0;
            for (S s : stats.values()) {
                if (include(s, nb)) {
                    c += 1;
                }
            }
            return c;
        }


        boolean include(S s, int nb) {
            int b = quickLowerSize(s.value.getType());
            return nb * s.count + b < s.count * b;
        }

        int quickLowerSize(Type type) {
            switch (type) {
                case MAP:
                    // TODO this is a lower estimate
                    // length + ...
                    return 12;
                case LIST:
                    // TODO this is a lower estimate
                    return 8;
                case BLOB:
                    // TODO this is a lower estimate
                    return 8;
                case UTF8:
                    // TODO this is a lower estimate
                    return 8;
                case INT32:
                    return 4;
                case INT64:
                    return 8;
                case FLOAT32:
                    return 4;
                case FLOAT64:
                    return 8;
                case BOOLEAN:
                    return 1;
                default:
                    throw new IllegalArgumentException();
            }
        }
    }

    static List<WireValue> stableKeys(Map<WireValue, WireValue> m) {
        List<WireValue> keys = new ArrayList<WireValue>(m.keySet());
        Collections.sort(keys, stableComparator);
        return keys;
    }

    static List<WireValue> stableValues(Map<WireValue, WireValue> m, List<WireValue> keys) {
        List<WireValue> values = new ArrayList<WireValue>(keys.size());
        for (WireValue key : keys) {
            values.add(m.get(key));
        }
        return values;
    }


    static final Comparator<WireValue> stableComparator = new StableComparator();

    // stable ordering for all values
    static final class StableComparator implements Comparator<WireValue> {
        @Override
        public int compare(WireValue a, WireValue b) {
            Type ta = a.getType();
            Type tb = b.getType();

            int d = ta.ordinal() - tb.ordinal();
            if (0 != d) {
                return d;
            }
            assert ta.equals(tb);

            switch (ta) {
                case MAP: {
                    Map<WireValue, WireValue> amap = a.asMap();
                    Map<WireValue, WireValue> bmap = b.asMap();
                    d = amap.size() - bmap.size();
                    if (0 != d) {
                        return d;
                    }
                    List<WireValue> akeys = stableKeys(amap);
                    List<WireValue> bkeys = stableKeys(bmap);
                    d = compare(of(akeys), of(bkeys));
                    if (0 != d) {
                        return d;
                    }
                    return compare(of(stableValues(amap, akeys)), of(stableValues(bmap, bkeys)));
                }
                case LIST: {
                    List<WireValue> alist = a.asList();
                    List<WireValue> blist = b.asList();
                    int n = alist.size();
                    int m = blist.size();
                    d = n - m;
                    if (0 != d) {
                        return d;
                    }
                    for (int i = 0; i < n; ++i) {
                        d = compare(alist.get(i), blist.get(i));
                        if (0 != d) {
                            return d;
                        }
                    }
                    return 0;
                }
                case BLOB: {
                    ByteBuffer abytes = a.asBlob();
                    ByteBuffer bbytes = b.asBlob();
                    int n = abytes.remaining();
                    int m = bbytes.remaining();
                    d = n - m;
                    if (0 != d) {
                        return d;
                    }
                    for (int i = 0; i < n; ++i) {
                        d = (0xFF & abytes.get(i)) - (0xFF & bbytes.get(i));
                        if (0 != d) {
                            return d;
                        }
                    }
                    return 0;
                }
                case UTF8:
                    return a.asString().compareTo(b.asString());
                case INT32:
                    return Integer.compare(a.asInt(), b.asInt());
                case INT64:
                    return Long.compare(a.asLong(), b.asLong());
                case FLOAT32:
                    return Float.compare(a.asFloat(), b.asFloat());
                case FLOAT64:
                    return Double.compare(a.asDouble(), b.asDouble());
                default:
                    throw new IllegalArgumentException();
            }
        }
    }





    // logical view, regardless of wire format

    public abstract String asString();
    public abstract int asInt();
    public abstract long asLong();
    public abstract float asFloat();
    public abstract double asDouble();
    public abstract boolean asBoolean();
    public abstract List<WireValue> asList();
    public abstract Map<WireValue, WireValue> asMap();
    public abstract ByteBuffer asBlob();








    private static class BlobWireValue extends WireValue {
        final byte[] value;

        BlobWireValue(byte[] value) {
            super(Type.BLOB);
            this.value = value;
        }

        public String asString() {
            return base64(ByteBuffer.wrap(value));
        }

        public int asInt() {
            throw new UnsupportedOperationException();
        }

        public long asLong() {
            throw new UnsupportedOperationException();
        }
        public float asFloat() {
            throw new UnsupportedOperationException();
        }
        public double asDouble() {
            throw new UnsupportedOperationException();
        }
        public boolean asBoolean() {
            throw new UnsupportedOperationException();
        }
        public List<WireValue> asList() {
            throw new UnsupportedOperationException();
        }
        public Map<WireValue, WireValue> asMap() {
            throw new UnsupportedOperationException();
        }
        public ByteBuffer asBlob() {
            return ByteBuffer.wrap(value);
        }


    }

    private static class Utf8WireValue extends WireValue {
        final String value;

        Utf8WireValue(String value) {
            super(Type.UTF8);
            this.value = value;
        }

        public String asString() {
            return value;
        }

        public int asInt() {
            return Integer.parseInt(value);
        }

        public long asLong() {
            return Long.parseLong(value);
        }
        public float asFloat() {
            return Float.parseFloat(value);
        }
        public double asDouble() {
            return Double.parseDouble(value);
        }
        public boolean asBoolean() {
            return Boolean.parseBoolean(value);
        }
        public List<WireValue> asList() {
            throw new UnsupportedOperationException();
        }
        public Map<WireValue, WireValue> asMap() {
            throw new UnsupportedOperationException();
        }
        public ByteBuffer asBlob() {
            return ByteBuffer.wrap(value.getBytes(Charsets.UTF_8));
        }
    }

    private static class NumberWireValue extends WireValue {
        static Type type(Number n) {
            if (n instanceof Integer) {
                return Type.INT32;
            }
            if (n instanceof Long) {
                return Type.INT64;
            }
            if (n instanceof Float) {
                return Type.FLOAT32;
            }
            if (n instanceof Double) {
                return Type.FLOAT64;
            }
            throw new IllegalArgumentException();
        }

        final Number value;

        NumberWireValue(Number value) {
            super(type(value));
            this.value = value;
        }

        public String asString() {
            return value.toString();
        }

        public int asInt() {
            return value.intValue();
        }

        public long asLong() {
            return value.longValue();
        }
        public float asFloat() {
            return value.floatValue();
        }
        public double asDouble() {
            return value.doubleValue();
        }
        public boolean asBoolean() {
            // c style
            return 0 != value.floatValue();
        }
        public List<WireValue> asList() {
            throw new UnsupportedOperationException();
        }
        public Map<WireValue, WireValue> asMap() {
            throw new UnsupportedOperationException();
        }
        public ByteBuffer asBlob() {
            // TODO
            throw new UnsupportedOperationException();
        }
    }

    private static class BooleanWireValue extends WireValue {

        final boolean value;

        BooleanWireValue(boolean value) {
            super(Type.BOOLEAN);
            this.value = value;
        }

        public String asString() {
            return String.valueOf(value);
        }

        public int asInt() {
            return value ? 1 : 0;
        }

        public long asLong() {
            return value ? 1L : 0L;
        }
        public float asFloat() {
            return value ? 1.f : 0.f;
        }
        public double asDouble() {
            return value ? 1.0 : 0.0;
        }
        public boolean asBoolean() {
            return value;
        }
        public List<WireValue> asList() {
            throw new UnsupportedOperationException();
        }
        public Map<WireValue, WireValue> asMap() {
            throw new UnsupportedOperationException();
        }
        public ByteBuffer asBlob() {
            // TODO
            throw new UnsupportedOperationException();
        }
    }

    private static class ListWireValue extends WireValue {
        final List<WireValue> value;

        ListWireValue(List<WireValue> value) {
            super(Type.LIST);
            this.value = value;
        }

        public String asString() {
            return value.toString();
        }

        public int asInt() {
            throw new UnsupportedOperationException();
        }

        public long asLong() {
            throw new UnsupportedOperationException();
        }
        public float asFloat() {
            throw new UnsupportedOperationException();
        }
        public double asDouble() {
            throw new UnsupportedOperationException();
        }
        public boolean asBoolean() {
            throw new UnsupportedOperationException();
        }
        public List<WireValue> asList() {
            return value;
        }
        public Map<WireValue, WireValue> asMap() {
            throw new UnsupportedOperationException();
        }
        public ByteBuffer asBlob() {
            // TODO
            throw new UnsupportedOperationException();
        }
    }

    private static class MapWireValue extends WireValue {
        final Map<WireValue, WireValue> value;

        MapWireValue(Map<WireValue, WireValue> value) {
            super(Type.MAP);
            this.value = value;
        }

        public String asString() {
            return value.toString();
        }

        public int asInt() {
            throw new UnsupportedOperationException();
        }

        public long asLong() {
            throw new UnsupportedOperationException();
        }
        public float asFloat() {
            throw new UnsupportedOperationException();
        }
        public double asDouble() {
            throw new UnsupportedOperationException();
        }
        public boolean asBoolean() {
            throw new UnsupportedOperationException();
        }
        public List<WireValue> asList() {
            throw new UnsupportedOperationException();
        }
        public Map<WireValue, WireValue> asMap() {
            return value;
        }
        public ByteBuffer asBlob() {
            // TODO
            throw new UnsupportedOperationException();
        }
    }

}
