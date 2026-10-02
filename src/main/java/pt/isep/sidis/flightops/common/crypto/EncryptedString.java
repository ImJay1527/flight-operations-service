package pt.isep.sidis.flightops.common.crypto;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * JPA converter: the field is encrypted when written to the database and decrypted when read
 * ({@link FieldEncryption}). Use with {@code @Convert(converter = EncryptedString.class)}.
 * Query parameters compared with such a field are encrypted the same way, so equality queries keep working.
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
