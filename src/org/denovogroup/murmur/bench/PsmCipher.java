package org.denovogroup.murmur.bench;

import java.nio.ByteBuffer;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

// Encrypts the advertised L2CAP PSM with AES CTR
// Counter block uses rotating UUID for nonce so PSM encryption rotates
// The encryption key is derived from the pre shared AES-128 key
public final class PsmCipher {

    // One AES seed block
    private static final byte[] KEY_LABEL = {
            1, 2, 3, 4, 5, 6, 7, 8, 9, 1, 2, 3, 4, 5, 6, 7
    };

    private PsmCipher() { }

    // encrypt and decrypt
    private static byte[] crypt(byte[] input, UUID uuid, byte[] irk) throws Exception {
        byte[] counterBlock = ByteBuffer.allocate(16).putLong(uuid.getMostSignificantBits()).putLong(uuid.getLeastSignificantBits()).array();

        Cipher cipher = Cipher.getInstance("AES/CTR/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(deriveKey(irk), "AES"), new IvParameterSpec(counterBlock));
        return cipher.doFinal(input);
    }

    public static byte[] encrypt(int psm, UUID uuid, byte[] irk) throws Exception {
        byte[] plain = {(byte) ((psm >>> 8) & 0xFF), (byte) (psm & 0xFF)};
        return crypt(plain, uuid, irk);
    }

    // call after uuid resolves
    public static int decrypt(byte[] ciphertext, UUID uuid, byte[] irk) throws Exception {
        byte[] plain = crypt(ciphertext, uuid, irk);
        return ((plain[0] & 0xFF) << 8) | (plain[1] & 0xFF);
    }

    // subkey is AES-ECB with IRK and seed block
    private static byte[] deriveKey(byte[] irk) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/ECB/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(irk, "AES"));
        return cipher.doFinal(KEY_LABEL);
    }
}