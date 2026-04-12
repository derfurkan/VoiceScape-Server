package com.voicescape.server;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-128-CTR encryption for UDP audio packets.
 * Uses ThreadLocal Cipher to avoid Cipher.getInstance() on every packet.
 * The 4-byte sequence number is the nonce — safe because keys are unique
 * per session and sequence numbers never repeat within a session.
 */
public final class UdpCrypto
{
    private UdpCrypto() {}

    private static final ThreadLocal<Cipher> CIPHER = ThreadLocal.withInitial(() ->
    {
        try
        {
            return Cipher.getInstance("AES/CTR/NoPadding");
        }
        catch (Exception e)
        {
            throw new RuntimeException("AES/CTR/NoPadding not available", e);
        }
    });

    // Reusable IV buffer per thread — avoids allocating 16 bytes per packet
    private static final ThreadLocal<byte[]> IV_BUF = ThreadLocal.withInitial(() -> new byte[16]);

    public static byte[] encrypt(byte[] key, int sequenceNumber, byte[] data)
    {
        return process(key, sequenceNumber, data, Cipher.ENCRYPT_MODE);
    }

    public static byte[] decrypt(byte[] key, int sequenceNumber, byte[] data)
    {
        return process(key, sequenceNumber, data, Cipher.DECRYPT_MODE);
    }

    private static byte[] process(byte[] key, int sequenceNumber, byte[] data, int mode)
    {
        try
        {
            SecretKeySpec keySpec = new SecretKeySpec(key, 0, 16, "AES");

            byte[] iv = IV_BUF.get();
            iv[0] = (byte) (sequenceNumber >> 24);
            iv[1] = (byte) (sequenceNumber >> 16);
            iv[2] = (byte) (sequenceNumber >> 8);
            iv[3] = (byte) sequenceNumber;
            // bytes 4-15 stay zero

            Cipher cipher = CIPHER.get();
            cipher.init(mode, keySpec, new IvParameterSpec(iv));
            return cipher.doFinal(data);
        }
        catch (Exception e)
        {
            throw new RuntimeException("UDP crypto failed", e);
        }
    }
}
