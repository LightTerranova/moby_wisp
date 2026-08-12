package org.denovogroup.murmur.bench;

import android.os.ParcelUuid;

import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

// Resolvable UUID using BLE IRK design
// UUID becomes prand(24) and 104 bits of AES-128(IRK, zero padding || prand)
public final class ResolvableServiceUuid {

    public static final long ROTATION_PERIOD_MS = 15 * 60 * 1000L; // how long before forcing uuid to rotate
    private static final int PRAND_BYTES = 3;
    private static final int HASH_BYTES  = 13;
    private static final int IRK_BYTES   = 16;
    private static final SecureRandom RANDOM = new SecureRandom();

    private ResolvableServiceUuid() { }
    // new uuid given IRK
    public static ParcelUuid generate(byte[] irk) throws Exception {
        byte[] prand = new byte[PRAND_BYTES];
        RANDOM.nextBytes(prand);

        // top two bits of prand set to 01 to mark it as a resolvable private address
        // can remove this to give two more bits to prand
        prand[0] = (byte) ((prand[0] & 0x3F) | 0x40);

        byte[] hash = ah(irk, prand);

        byte[] uuidBytes = new byte[16];
        System.arraycopy(prand, 0, uuidBytes, 0, PRAND_BYTES);
        System.arraycopy(hash,  0, uuidBytes, PRAND_BYTES, HASH_BYTES);

        return new ParcelUuid(fromBytes(uuidBytes));
    }

    // test if a resolvable uuid was generated with irk
    public static boolean resolves(UUID candidate, byte[] irk) {
        if (candidate == null || irk == null || irk.length != IRK_BYTES) return false; // these cases should not happen

        try {
            byte[] uuidBytes = toBytes(candidate);

            byte[] prand = new byte[PRAND_BYTES];
            System.arraycopy(uuidBytes, 0, prand, 0, PRAND_BYTES);

            if ((prand[0] & 0xC0) != 0x40) return false; // reject if 01 for resolvable is not set

            byte[] expected = ah(irk, prand);

            int diff = 0;
            for (int i = 0; i < HASH_BYTES; i++) {
                diff |= expected[i] ^ uuidBytes[PRAND_BYTES + i];
            }
            return diff == 0;
        } catch (Exception e) {
            return false;
        }
    }

    // ah function from BLE spec with hash widened because we have space
    private static byte[] ah(byte[] irk, byte[] prand) throws Exception {
        byte[] block = new byte[16];
        System.arraycopy(prand, 0, block, 16 - PRAND_BYTES, PRAND_BYTES); // zero pad

        Cipher cipher = Cipher.getInstance("AES/ECB/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(irk, "AES"));
        byte[] encrypted = cipher.doFinal(block);

        byte[] hash = new byte[HASH_BYTES];
        System.arraycopy(encrypted, 0, hash, 0, HASH_BYTES);
        return hash;
    }

    private static UUID fromBytes(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    private static byte[] toBytes(UUID uuid) {
        return ByteBuffer.allocate(16).putLong(uuid.getMostSignificantBits()).putLong(uuid.getLeastSignificantBits()).array();
    }

    // 32 char hex str to IRK
    public static byte[] parseIrkHex(String hex) {
        byte[] out = new byte[IRK_BYTES];
        for (int i = 0; i < IRK_BYTES; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}