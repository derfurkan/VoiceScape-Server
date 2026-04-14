package com.voicescape.server;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public final class UdpCrypto {
    private static final ThreadLocal<Cipher> CIPHER = ThreadLocal.withInitial(() ->
    {
        try {
            return Cipher.getInstance("AES/CTR/NoPadding");
        } catch (Exception e) {
            throw new RuntimeException("AES/CTR/NoPadding not available", e);
        }
    });
    // Reuse
    private static final ThreadLocal<byte[]> IV_BUF = ThreadLocal.withInitial(() -> new byte[16]);

    private UdpCrypto() {
    }

    public static byte[] encrypt(SecretKeySpec keySpec, int sequenceNumber, byte[] data) {
        return process(keySpec, sequenceNumber, data, Cipher.ENCRYPT_MODE);
    }

    public static byte[] decrypt(SecretKeySpec keySpec, int sequenceNumber, byte[] data) {
        return process(keySpec, sequenceNumber, data, Cipher.DECRYPT_MODE);
    }

    private static byte[] process(SecretKeySpec keySpec, int sequenceNumber, byte[] data, int mode) {
        try {

            byte[] iv = IV_BUF.get();
            iv[0] = (byte) (sequenceNumber >> 24);
            iv[1] = (byte) (sequenceNumber >> 16);
            iv[2] = (byte) (sequenceNumber >> 8);
            iv[3] = (byte) sequenceNumber;

            Cipher cipher = CIPHER.get();
            cipher.init(mode, keySpec, new IvParameterSpec(iv));
            return cipher.doFinal(data);
        } catch (Exception e) {
            throw new RuntimeException("UDP crypto failed", e);
        }
    }
}
