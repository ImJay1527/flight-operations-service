package pt.isep.sidis.flightops.common.crypto;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FieldEncryptionTest {

    private static final String KEY = "9BvH3kXoOq3m1o9mJmPFjB6m4cO1kz3Hqk2iY7G1M8o=";
    private final FieldEncryption encryption = new FieldEncryption(KEY);

    @Test
    void roundTrip() {
        String stored = encryption.encrypt("CS-TPA");
        assertThat(stored).startsWith("enc:v1:").doesNotContain("CS-TPA");
        assertThat(encryption.decrypt(stored)).isEqualTo("CS-TPA");
    }

    @Test
    void deterministicSoEqualityQueriesStillWork() {
        assertThat(encryption.encrypt("LIS")).isEqualTo(encryption.encrypt("LIS"));
        assertThat(encryption.encrypt("LIS")).isNotEqualTo(encryption.encrypt("OPO"));
    }

    @Test
    void modifiedCiphertextIsDetected() {
        byte[] raw = Base64.getDecoder().decode(encryption.encrypt("route-opo-lis").substring("enc:v1:".length()));
        raw[raw.length - 1] ^= 0x01;   // flip one bit of the stored value
        String tampered = "enc:v1:" + Base64.getEncoder().encodeToString(raw);
        assertThatThrownBy(() -> encryption.decrypt(tampered)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void wrongKeyCannotRead() {
        String stored = encryption.encrypt("CS-TPA");
        FieldEncryption other = new FieldEncryption(Base64.getEncoder().encodeToString(new byte[32]));
        assertThatThrownBy(() -> other.decrypt(stored)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void oldUnencryptedValuesAreReturnedAsTheyAre() {
        assertThat(encryption.decrypt("CS-TPA")).isEqualTo("CS-TPA");
        assertThat(encryption.decrypt(null)).isNull();
    }

    @Test
    void keyMustBe256Bits() {
        assertThatThrownBy(() -> new FieldEncryption(Base64.getEncoder().encodeToString(new byte[16])))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
