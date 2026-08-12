package org.denovogroup.murmur.bench;

import android.content.Context;
import android.util.Log;

import org.denovogroup.murmur.backend.Exchange;
import org.denovogroup.murmur.objects.CleartextFriends;
import org.denovogroup.murmur.objects.CleartextMessages;
import org.denovogroup.murmur.objects.MobyMessage;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/*
Generates traffic like Moby would if it were working.
Makes one friends frame, one timestamp and an N number of message frames.
There is one write per message.
 */
public final class MobyTrafficGenerator {

    private static final String TAG = "MobyTraffic";

    // Moby message cap
    public static final int MESSAGES_PER_EXCHANGE = 100;

    private static final char[] B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray(); // traffic gen char set

    // base 64 of 32 byte tag
    private static final int MOBY_TAG_CHARS = 44;

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

        // DEBUG
        @Override
        public String toString() {
            return "Profile{friends=" + friendCount + ", messages=" + messageCount + "/round, payloadChars=" + payloadChars + ", rounds=" + rounds + ", frames=" + frameCount() + "}";
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
    public static List<JSONObject> buildFrames(Context context, Profile profile) {
        Random rng = new Random(profile.seed);
        List<JSONObject> frames = new ArrayList<>(profile.frameCount());

        for (int round = 0; round < profile.rounds; round++) {

            // friends JSON like Moby sendFriends()
            ArrayList<String> friends = new ArrayList<>(profile.friendCount);
            for (int i = 0; i < profile.friendCount; i++) {
                friends.add(randomB64(rng, MOBY_TAG_CHARS));
            }
            frames.add(new CleartextFriends(friends).toJson());

            // control frame from sendMessages()
            MobyMessage agreement = new MobyMessage(-1L, -1L, "ExchangeAgreement", Integer.toString(profile.messageCount));
            frames.add(agreement.toJSON());

            // message frames with one frame per message from sendMessages() too
            long now = System.currentTimeMillis();
            for (int i = 0; i < profile.messageCount; i++) {
                MobyMessage message = new MobyMessage(now + i, now + i + 604_800_000L, randomB64(rng, MOBY_TAG_CHARS), randomB64(rng, profile.payloadChars));
                ArrayList<MobyMessage> single = new ArrayList<>(1);
                single.add(message);
                frames.add(new CleartextMessages(single).toJson(context));
            }
        }
        // DEBUG
        // TODO: Remove later
        Log.i(TAG, profile + " " + frames.size() + " frames, " + totalJsonBytes(frames) + " JSON bytes");
        return frames;
    }

    // getting the bytes for all frames
    public static long totalJsonBytes(List<JSONObject> frames) {
        long total = 0;
        for (JSONObject frame : frames) {
            try {
                total += frame.toString().getBytes("UTF-8").length;
            } catch (Exception ignored) { }
        }
        return total;
    }

    // writes frames and returns bytes written
    public static long send(OutputStream out, List<JSONObject> frames) throws IOException {
        long written = 0;
        for (JSONObject frame : frames) {
            written += frame.toString().getBytes("UTF-8").length;
        }
        out.flush();
        return written;
    }

    // reads a given number of frames and returns bytes read
    public static long receive(InputStream in, int expectedFrames) throws IOException {
        long read = 0;
        for (int i = 0; i < expectedFrames; i++) {
            JSONObject frame = Exchange.lengthValueRead(in);
            // DEBUG for dropping frames
            if (frame == null) {
                Log.w(TAG, "Stream ended after " + i + " of " + expectedFrames + " frames");
                break;
            }
            read += frame.toString().getBytes("UTF-8").length;
        }
        return read;
    }

    private static String randomB64(Random rng, int chars) {
        StringBuilder sb = new StringBuilder(chars);
        for (int i = 0; i < chars; i++) {
            sb.append(B64[rng.nextInt(B64.length)]);
        }
        return sb.toString();
    }
}