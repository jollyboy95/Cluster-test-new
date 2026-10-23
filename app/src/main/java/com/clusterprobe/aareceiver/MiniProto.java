package com.clusterprobe.aareceiver;

import java.io.ByteArrayOutputStream;

/**
 * A minimal, hand-written protobuf wire-format reader/writer - varint + length-delimited only
 * (that's all every message this project needs uses: no fixed32/fixed64 fields anywhere in the
 * vendored aasdk .proto files this class's callers encode/decode). Written against the standard,
 * public protobuf wire-format spec (tag = (field_number << 3) | wire_type; wire_type 0=varint,
 * 2=length-delimited; varint = 7 payload bits per byte, continuation bit in the high bit) - not
 * copied from anywhere, this is just the documented wire format.
 *
 * Used instead of the Gradle-generated protobuf-java classes for the control/AV-setup messages
 * (AuthCompleteIndication, ServiceDiscoveryRequest/Response, ChannelOpenRequest/Response,
 * AVChannelSetupRequest/Response) because those messages are small and this project's own stub-
 * compile harness (/tmp/stubcheck2, used to catch plain-Java errors before every push, since this
 * sandbox has no real Android SDK/protoc) can't practically stand in for a dozen+ generated
 * protobuf message classes - hand-rolling the wire format for just these fields keeps everything
 * genuinely compile-checkable before pushing, same principle already applied to
 * AaWirelessConstants's bootstrap-channel messages.
 */
public final class MiniProto {

    public static final class Writer {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        public Writer varint(int fieldNumber, long value) {
            tag(fieldNumber, 0);
            writeVarint(value);
            return this;
        }

        public Writer bool(int fieldNumber, boolean value) {
            return varint(fieldNumber, value ? 1 : 0);
        }

        public Writer bytesField(int fieldNumber, byte[] value) {
            tag(fieldNumber, 2);
            writeVarint(value.length);
            out.write(value, 0, value.length);
            return this;
        }

        public Writer stringField(int fieldNumber, String value) {
            return bytesField(fieldNumber, value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }

        public Writer message(int fieldNumber, Writer nested) {
            return bytesField(fieldNumber, nested.toByteArray());
        }

        private void tag(int fieldNumber, int wireType) {
            writeVarint(((long) fieldNumber << 3) | wireType);
        }

        private void writeVarint(long value) {
            while (true) {
                if ((value & ~0x7FL) == 0) {
                    out.write((int) value);
                    return;
                }
                out.write((int) ((value & 0x7F) | 0x80));
                value >>>= 7;
            }
        }

        public byte[] toByteArray() {
            return out.toByteArray();
        }
    }

    public static final class Field {
        public final int number;
        public final int wireType;
        public final long varintValue;
        public final byte[] bytesValue;
        Field(int number, int wireType, long varintValue, byte[] bytesValue) {
            this.number = number; this.wireType = wireType;
            this.varintValue = varintValue; this.bytesValue = bytesValue;
        }
    }

    /** Parses a message into its top-level fields (not recursive - call parseFields again on a
     *  Field's bytesValue to descend into a nested message). Skips any wire type this class
     *  doesn't need (fixed32/fixed64) safely rather than throwing, since an unknown/unused field
     *  showing up is normal protobuf forward-compatibility, not an error. */
    public static java.util.List<Field> parseFields(byte[] data) {
        java.util.List<Field> fields = new java.util.ArrayList<>();
        int[] pos = {0};
        while (pos[0] < data.length) {
            long tag = readVarint(data, pos);
            int fieldNumber = (int) (tag >>> 3);
            int wireType = (int) (tag & 0x7);
            switch (wireType) {
                case 0: // varint
                    fields.add(new Field(fieldNumber, wireType, readVarint(data, pos), null));
                    break;
                case 2: // length-delimited
                    int len = (int) readVarint(data, pos);
                    byte[] slice = new byte[len];
                    System.arraycopy(data, pos[0], slice, 0, len);
                    pos[0] += len;
                    fields.add(new Field(fieldNumber, wireType, 0, slice));
                    break;
                case 5: // fixed32 - skip
                    pos[0] += 4;
                    break;
                case 1: // fixed64 - skip
                    pos[0] += 8;
                    break;
                default:
                    // Unknown/unsupported wire type - stop rather than mis-parse the rest.
                    return fields;
            }
        }
        return fields;
    }

    public static String findString(java.util.List<Field> fields, int fieldNumber, String fallback) {
        for (Field f : fields) {
            if (f.number == fieldNumber && f.bytesValue != null) {
                return new String(f.bytesValue, java.nio.charset.StandardCharsets.UTF_8);
            }
        }
        return fallback;
    }

    public static long findVarint(java.util.List<Field> fields, int fieldNumber, long fallback) {
        for (Field f : fields) {
            if (f.number == fieldNumber && f.bytesValue == null) {
                return f.varintValue;
            }
        }
        return fallback;
    }

    private static long readVarint(byte[] data, int[] pos) {
        long result = 0;
        int shift = 0;
        while (true) {
            byte b = data[pos[0]++];
            result |= ((long) (b & 0x7F)) << shift;
            if ((b & 0x80) == 0) break;
            shift += 7;
        }
        return result;
    }

    private MiniProto() {}
}
