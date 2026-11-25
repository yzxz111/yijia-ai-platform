package com.yuzhihuihe.apiplatform.platform.security;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public final class SecretEncryptionService {

    private static final byte FORMAT_VERSION = 1;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public SecretEncryptionService(String base64MasterKey) {
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(base64MasterKey == null ? "" : base64MasterKey);
        } catch (IllegalArgumentException invalidBase64) {
            throw new IllegalArgumentException("invalid_secret_master_key", invalidBase64);
        }
        if (decoded.length != 32) {
            throw new IllegalArgumentException("invalid_secret_master_key");
        }
        this.key = new SecretKeySpec(decoded, "AES");
    }

    public byte[] encrypt(byte[] plaintext) {
        if (plaintext == null) {
            throw new IllegalArgumentException("invalid_secret_plaintext");
        }
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            byte[] ciphertext = cipher.doFinal(plaintext);
            byte[] encoded = new byte[1 + nonce.length + ciphertext.length];
            encoded[0] = FORMAT_VERSION;
            System.arraycopy(nonce, 0, encoded, 1, nonce.length);
            System.arraycopy(ciphertext, 0, encoded, 1 + nonce.length, ciphertext.length);
            return encoded;
        } catch (Exception error) {
            throw new IllegalStateException("secret_encryption_failed", error);
        }
    }

    public byte[] decrypt(byte[] encoded) {
        if (encoded == null || encoded.length <= 1 + NONCE_BYTES + 16
                || encoded[0] != FORMAT_VERSION) {
            throw new IllegalArgumentException("secret_decryption_failed");
        }
        try {
            byte[] nonce = Arrays.copyOfRange(encoded, 1, 1 + NONCE_BYTES);
            byte[] ciphertext = Arrays.copyOfRange(encoded, 1 + NONCE_BYTES, encoded.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            return cipher.doFinal(ciphertext);
        } catch (Exception error) {
            throw new IllegalArgumentException("secret_decryption_failed", error);
        }
    }
}
