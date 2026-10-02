package pt.isep.sidis.flightops.common.crypto;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Encrypts the field on write and decrypts it on read ({@link FieldEncryption}). Query parameters compared with the
 * field are encrypted the same way, so equality queries keep working.
 */
@Converter
public class EncryptedString implements AttributeConverter<String, String> {

    @Override
    public String convertToDatabaseColumn(String plaintext) {
        return FieldEncryption.instance().encrypt(plaintext);
    }

    @Override
    public String convertToEntityAttribute(String stored) {
        return FieldEncryption.instance().decrypt(stored);
    }
}
