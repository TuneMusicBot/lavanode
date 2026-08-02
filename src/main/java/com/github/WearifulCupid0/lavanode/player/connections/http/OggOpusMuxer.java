package com.github.WearifulCupid0.lavanode.player.connections.http;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Minimal live Ogg/Opus muxer.
 *
 * It emits one complete Opus packet per Ogg page. Each HTTP listener owns its
 * own muxer, stream serial, sequence and granule position, allowing listeners
 * to connect at any point in the player timeline.
 */
public final class OggOpusMuxer {
    private static final byte[] CAPTURE_PATTERN = new byte[]{'O', 'g', 'g', 'S'};
    private static final byte[] OPUS_HEAD = "OpusHead".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] OPUS_TAGS = "OpusTags".getBytes(StandardCharsets.US_ASCII);
    private static final int[] CRC_LOOKUP = createCrcLookup();

    private static final int HEADER_TYPE_NORMAL = 0x00;
    private static final int HEADER_TYPE_BOS = 0x02;

    private static final int CHANNELS = 2;
    private static final int SAMPLE_RATE = 48_000;
    private static final int SAMPLES_PER_PACKET = 960;

    private final int streamSerial;
    private int pageSequence;
    private long granulePosition;

    public OggOpusMuxer() {
        int serial = ThreadLocalRandom.current().nextInt();
        this.streamSerial = serial == 0 ? 1 : serial;
    }

    public byte[] createIdentificationPage() {
        byte[] packet = new byte[19];
        System.arraycopy(OPUS_HEAD, 0, packet, 0, OPUS_HEAD.length);
        packet[8] = 1; // OpusHead version
        packet[9] = CHANNELS;
        writeLittleEndian16(packet, 10, 0); // Live stream: no pre-skip
        writeLittleEndian32(packet, 12, SAMPLE_RATE);
        writeLittleEndian16(packet, 16, 0); // Output gain
        packet[18] = 0; // Channel mapping family 0 (mono/stereo)

        return createPage(packet, HEADER_TYPE_BOS, 0L);
    }

    public byte[] createCommentPage() {
        byte[] vendor = "LavaNode Ogg/Opus Stream".getBytes(StandardCharsets.UTF_8);
        byte[] packet = new byte[8 + 4 + vendor.length + 4];

        System.arraycopy(OPUS_TAGS, 0, packet, 0, OPUS_TAGS.length);
        writeLittleEndian32(packet, 8, vendor.length);
        System.arraycopy(vendor, 0, packet, 12, vendor.length);
        writeLittleEndian32(packet, 12 + vendor.length, 0); // User comment count

        return createPage(packet, HEADER_TYPE_NORMAL, 0L);
    }

    public byte[] writeAudioPacket(byte[] opusPacket) {
        if (opusPacket == null || opusPacket.length == 0) {
            throw new IllegalArgumentException("Opus packet cannot be empty");
        }

        granulePosition += SAMPLES_PER_PACKET;
        return createPage(opusPacket, HEADER_TYPE_NORMAL, granulePosition);
    }

    private byte[] createPage(byte[] packet, int headerType, long granule) {
        int fullSegments = packet.length / 255;
        int remainder = packet.length % 255;
        int segmentCount = fullSegments + 1;

        if (segmentCount > 255) {
            throw new IllegalArgumentException("Packet is too large for a single Ogg page");
        }

        int headerSize = 27 + segmentCount;
        byte[] page = new byte[headerSize + packet.length];

        System.arraycopy(CAPTURE_PATTERN, 0, page, 0, CAPTURE_PATTERN.length);
        page[4] = 0; // Ogg stream structure version
        page[5] = (byte) headerType;
        writeLittleEndian64(page, 6, granule);
        writeLittleEndian32(page, 14, streamSerial);
        writeLittleEndian32(page, 18, pageSequence++);
        // Bytes 22-25 remain zero while calculating the CRC.
        page[26] = (byte) segmentCount;

        int segmentOffset = 27;
        for (int i = 0; i < fullSegments; i++) {
            page[segmentOffset + i] = (byte) 255;
        }
        page[segmentOffset + fullSegments] = (byte) remainder;

        System.arraycopy(packet, 0, page, headerSize, packet.length);

        int crc = calculateCrc(page);
        writeLittleEndian32(page, 22, crc);

        return page;
    }

    private static int calculateCrc(byte[] data) {
        int crc = 0;

        for (byte value : data) {
            int lookupIndex = ((crc >>> 24) ^ (value & 0xFF)) & 0xFF;
            crc = (crc << 8) ^ CRC_LOOKUP[lookupIndex];
        }

        return crc;
    }

    private static int[] createCrcLookup() {
        int[] lookup = new int[256];

        for (int i = 0; i < lookup.length; i++) {
            int value = i << 24;

            for (int bit = 0; bit < 8; bit++) {
                value = (value & 0x80000000) != 0
                        ? (value << 1) ^ 0x04C11DB7
                        : value << 1;
            }

            lookup[i] = value;
        }

        return lookup;
    }

    private static void writeLittleEndian16(byte[] target, int offset, int value) {
        target[offset] = (byte) value;
        target[offset + 1] = (byte) (value >>> 8);
    }

    private static void writeLittleEndian32(byte[] target, int offset, int value) {
        target[offset] = (byte) value;
        target[offset + 1] = (byte) (value >>> 8);
        target[offset + 2] = (byte) (value >>> 16);
        target[offset + 3] = (byte) (value >>> 24);
    }

    private static void writeLittleEndian64(byte[] target, int offset, long value) {
        for (int i = 0; i < Long.BYTES; i++) {
            target[offset + i] = (byte) (value >>> (i * 8));
        }
    }
}
