package pt.isep.sidis.flightops.common.crypto;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;

/**
 * Encryption at rest of sensitive columns (P1 p.16). Deterministic authenticated encryption, SIV construction:
 * <ol>
 *   <li>IV = first 16 bytes of HMAC-SHA256(macKey, plaintext) - the "synthetic IV";</li>
 *   <li>ciphertext = AES-256-CBC(encKey, IV, plaintext);</li>
 *   <li>stored as {@code enc:v1:base64(IV || ciphertext)}.</li>
 * </ol>
 * Deterministic, so the database can still find rows by equality (registration, airport code); the price is that it
 * shows which rows share a value, not what the value is. Decryption recomputes the HMAC, so tampering is detected.
 *
 * <p>Keys are derived from one 256-bit master key (env DATA_ENCRYPTION_KEY). {@code v1} is the key version, so a new
 * key can be introduced as v2 (rotation; re-encrypting old values is not implemented). Values without the
 * {@code enc:} prefix are old, unencrypted data and are returned as they are (see {@link EncryptionMigration}).
 */
@Component
public class FieldEncryption {

    public static final String PREFIX = "enc:v1:";

    /** For the JPA converter, which Hibernate creates outside Spring. */
    private static volatile FieldEncryption instance;

    private final SecretKeySpec encKey;
    private final SecretKeySpec macKey;

    public FieldEncryption(@Value("${flightops.data-encryption.key}") String base64MasterKey) {
        byte[] master = Base64.getDecoder().decode(base64MasterKey);
        if (master.length != 32) {
            throw new IllegalArgumentException("flightops.data-encryption.key must be 32 bytes (base64), got " + master.length);
        }
        this.encKey = new SecretKeySpec(hmac(master, "aisafe-enc-v1"), "AES");
        this.macKey = new SecretKeySpec(hmac(master, "aisafe-mac-v1"), "HmacSHA256");
        instance = this;
    }

    static FieldEncryption instance() {
        FieldEncryption current = instance;
        if (current == null) {
            throw new IllegalStateException("FieldEncryption not initialised yet");
        }
        return current;
    }

    public String encrypt(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        try {
            byte[] data = plaintext.getBytes(StandardCharsets.UTF_8);
            byte[] iv = Arrays.copyOf(mac(data), 16);
            Cipher aes = Cipher.getInstance("AES/CBC/PKCS5Padding");
            aes.init(Cipher.ENCRYPT_MODE, encKey, new IvParameterSpec(iv));
            byte[] ciphertext = aes.doFinal(data);
            byte[] out = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ciphertext, 0, out, iv.length, ciphertext.length);
            return PREFIX + Base64.getEncoder().encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Encryption failed", e);
        }
    }

    public String decrypt(String stored) {
        if (stored == null || !stored.startsWith(PREFIX)) {
            return stored;   // old row written before encryption was enabled
        }
        try {
            byte[] in = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            byte[] iv = Arrays.copyOf(in, 16);
            Cipher aes = Cipher.getInstance("AES/CBC/PKCS5Padding");
            aes.init(Cipher.DECRYPT_MODE, encKey, new IvParameterSpec(iv));
            byte[] plain = aes.doFinal(in, 16, in.length - 16);
            if (!MessageDigest.isEqual(iv, Arrays.copyOf(mac(plain), 16))) {
                throw new IllegalStateException("Stored value was modified (integrity check failed)");
            }
            return new String(plain, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Stored value could not be decrypted (wrong key or modified data)", e);
        }
    }

    public boolean isEncrypted(String stored) {
        return stored != null && stored.startsWith(PREFIX);
    }

    private byte[] mac(byte[] data) throws GeneralSecurityException {
        Mac hmac = Mac.getInstance("HmacSHA256");
        hmac.init(macKey);
        return hmac.doFinal(data);
    }

    private static byte[] hmac(byte[] key, String label) {
        try {
            Mac hmac = Mac.getInstance("HmacSHA256");
            hmac.init(new SecretKeySpec(key, "HmacSHA256"));
            return hmac.doFinal(label.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
