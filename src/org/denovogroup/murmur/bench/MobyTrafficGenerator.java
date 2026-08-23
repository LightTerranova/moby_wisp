package org.denovogroup.murmur.bench;

import android.content.Context;
import android.util.Log;

import org.denovogroup.murmur.objects.CleartextFriends;
import org.denovogroup.murmur.objects.CleartextMessages;
import org.denovogroup.murmur.objects.MobyMessage;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Random;

/*
Generates traffic like Moby would if it were working.
Makes one friends frame, one timestamp and an N number of message frames.
There is one write per message.
 */
public final class MobyTrafficGenerator {

    private static final String TAG = "MobyTraffic";

    // Moby message cap
    public static final int MESSAGES_PER_EXCHANGE = 50;

    private static final char[] B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray(); // traffic gen char set

    // base 64 of 32 byte tag
    private static final int MOBY_TAG_CHARS = 44;

    private static final int MAX_FRAME_BYTES = 10 * 1024 * 1024;

    private MobyTrafficGenerator() { }

    public static final class Profile {
        public final int friendCount;
        public final int messageCount;
        public final int payloadChars;
        public final int rounds;
        public final long seed;

        public Profile(int friendCount, int messageCount, int payloadChars, int rounds, long seed) {
            this.friendCount = friendCount;
            this.messageCount = Math.min(messageCount, MESSAGES_PER_EXCHANGE);
            this.payloadChars = payloadChars;
            this.rounds = rounds;
            this.seed = seed;
        }

        // total frames of n + 2
        public int frameCount() {
            return rounds * (2 + messageCount);
        }

        // goodput is message bytes only
        public long payloadWireBytes() {
            return (long) rounds * messageCount * payloadChars;
        }

        // DEBUG
        @Override
        public String toString() {
            return "Profile{friends=" + friendCount + ", messages=" + messageCount + "/round, payloadChars=" + payloadChars + ", rounds=" + rounds + ", frames=" + frameCount() + ", payload=" + payloadWireBytes() + "B}";
        }
    }

    // typical Moby user profile
    public static Profile blackoutSingleExchange() {
        return new Profile(20, MESSAGES_PER_EXCHANGE, 400, 1, 0xB0BAFE77L);
    }

    // twelve rounds of message exchanges
    public static Profile volumeMatched() {
        return new Profile(20, MESSAGES_PER_EXCHANGE, 400, 12, 0xB0BAFE77L);
    }

    // building the JSON frames
    public static long send(Context context, OutputStream out, Profile profile)
            throws IOException, JSONException {

        Random rng = new Random(profile.seed);
        long written = 0;
        int frameIndex = 0;

        for (int round = 0; round < profile.rounds; round++) {

            // friends JSON like Moby sendFriends()
            ArrayList<String> friends = new ArrayList<>(profile.friendCount);
            for (int i = 0; i < profile.friendCount; i++) {
                friends.add(randomB64(rng, MOBY_TAG_CHARS));
            }
            written += writeFrame(out, new CleartextFriends(friends).toJson());
            frameIndex++;

            // control frame from sendMessages()
            MobyMessage agreement = new MobyMessage(-1L, -1L, "ExchangeAgreement", Integer.toString(profile.messageCount));
            written += writeFrame(out, agreement.toJSON());
            frameIndex++;

            // message frames with one frame per message from sendMessages() too
            long now = System.currentTimeMillis();
            for (int i = 0; i < profile.messageCount; i++) {
                MobyMessage message = new MobyMessage(now + i, now + i + 604_800_000L, randomB64(rng, MOBY_TAG_CHARS), randomB64(rng, profile.payloadChars));
                ArrayList<MobyMessage> single = new ArrayList<>(1);
                single.add(message);
                written += writeFrame(out, new CleartextMessages(single).toJson(context));

                if (++frameIndex % 200 == 0) {
                    Log.i(TAG, "sent frame " + frameIndex + "/" + profile.frameCount());
                }
            }
        }
        out.flush();
        Log.i(TAG, "sent complete:" + written + " B in " + frameIndex + " frames");
        return written;
    }

    // reads a given number of frames and returns bytes read
    public static long receive(InputStream in, int expectedFrames) throws IOException {
        long read = 0;
        byte[] header = new byte[4];

        for (int i = 0; i < expectedFrames; i++) {
            if (!readFully(in, header, 4)) {
                throw new IOException("Stream ended after " + i + " of " + expectedFrames);
            }

            int length = ((header[0] & 0xFF) << 24) | ((header[1] & 0xFF) << 16)
                    | ((header[2] & 0xFF) << 8)  |  (header[3] & 0xFF);

            if (length < 0 || length > MAX_FRAME_BYTES) {
                throw new IOException("Bad framed length " + length + " at frame " + i);
            }

            byte[] body = new byte[length];
            if (!readFully(in, body, length)) {
                throw new IOException("Stream ended in body of frame " + i + " of " + expectedFrames);
            }
            read += length;

            if ((i + 1) % 200 == 0) {
                Log.i(TAG, "read frame " + (i + 1) + "/" + expectedFrames);
            }
        }
        Log.i(TAG, "receive complete: " + read + " B in " + expectedFrames + " frames");
        return read;
    }

    // one write per frame
    private static long writeFrame(OutputStream out, JSONObject frame) throws IOException {
        byte[] body = frame.toString().getBytes("UTF-8");

        byte[] framed = new byte[4 + body.length];
        framed[0] = (byte) ((body.length >>> 24) & 0xFF);
        framed[1] = (byte) ((body.length >>> 16) & 0xFF);
        framed[2] = (byte) ((body.length >>> 8) & 0xFF);
        framed[3] = (byte) (body.length & 0xFF);
        System.arraycopy(body, 0, framed, 4, body.length);

        out.write(framed);
        return body.length;
    }

    // reads len bytes
    private static boolean readFully(InputStream in, byte[] buf, int len) throws IOException {
        int total = 0;
        while (total < len) {
            int n = in.read(buf, total, len - total);
            if (n == -1) return false;
            total += n;
        }
        return true;
    }

    static String randomB64(Random rng, int chars) {
        StringBuilder sb = new StringBuilder(chars);
        for (int i = 0; i < chars; i++) {
            sb.append(B64[rng.nextInt(B64.length)]);
        }
        return sb.toString();
    }
}