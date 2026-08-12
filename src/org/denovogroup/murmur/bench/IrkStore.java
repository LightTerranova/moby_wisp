package org.denovogroup.murmur.bench;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.security.SecureRandom;

// hold IRK
public final class IrkStore {

    private static final String TAG = "IrkStore";
    private static final String PREFS = "moby_bench";
    private static final String KEY_IRK_HEX = "irk_hex";

    private static final int IRK_BYTES = 16;

    // cache the callback, not sure if this helps with performance significantly
    private static volatile byte[] cached;

    private IrkStore() { }

    // return IRK or null
    public static byte[] get(Context context) {
        if (cached != null) return cached;

        String hex = prefs(context).getString(KEY_IRK_HEX, null);
        if (hex == null) return null;

        cached = ResolvableServiceUuid.parseIrkHex(hex);
        return cached;
    }

    // store IRK
    public static void setHex(Context context, String hex) {
        byte[] parsed = ResolvableServiceUuid.parseIrkHex(hex); // check irk before store
        prefs(context).edit().putString(KEY_IRK_HEX, toHex(parsed)).apply();
        cached = parsed;
        Log.i(TAG, "IRK set");
    }

    // gen, store and share irk
    public static String generateAndStore(Context context) {
        byte[] irk = new byte[IRK_BYTES];
        new SecureRandom().nextBytes(irk);

        String hex = toHex(irk);
        prefs(context).edit().putString(KEY_IRK_HEX, hex).apply();
        cached = irk;

        Log.i(TAG, "Generated IRK: " + hex);
        return hex;
    }

    public static String getHex(Context context) {
        return prefs(context).getString(KEY_IRK_HEX, null);
    }

    public static void clear(Context context) {
        prefs(context).edit().remove(KEY_IRK_HEX).apply();
        cached = null;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}