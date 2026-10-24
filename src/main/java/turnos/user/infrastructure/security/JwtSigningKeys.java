package turnos.user.infrastructure.security;

import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.security.converter.RsaKeyConverters;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;

// Paquete infrastructure/security: excepción a la skill autorizada por el usuario.
// Carga el par de claves una sola vez: la privada firma los tokens y la pública los valida.
// Si la clave falta o no sirve, la aplicación no arranca. Los mensajes nunca incluyen la clave
@Component
public class JwtSigningKeys {

    private static final String KEY_PROPERTY = "turnos.jwt.private-key-location";
    private static final int MIN_KEY_SIZE = 2048;

    private final RSAPrivateKey privateKey;
    private final RSAPublicKey publicKey;

    public JwtSigningKeys(JwtProperties jwtProperties, ResourceLoader resourceLoader) {
        this.privateKey = loadPrivateKey(jwtProperties.privateKeyLocation(), resourceLoader);
        this.publicKey = publicKeyOf(privateKey);
    }

    public RSAPrivateKey privateKey() {
        return privateKey;
    }

    public RSAPublicKey publicKey() {
        return publicKey;
    }

    private static RSAPrivateKey loadPrivateKey(String location, ResourceLoader resourceLoader) {
        if (location == null || location.isBlank()) {
            throw new IllegalStateException("Missing configuration property " + KEY_PROPERTY);
        }
        Resource resource = resourceLoader.getResource(location);
        if (!resource.exists()) {
            throw new IllegalStateException(KEY_PROPERTY + " points to a file that does not exist: " + location);
        }
        RSAPrivateKey key;
        try (InputStream inputStream = resource.getInputStream()) {
            key = RsaKeyConverters.pkcs8().convert(inputStream);
        } catch (Exception e) {
            throw new IllegalStateException(KEY_PROPERTY + " is not an RSA private key in PKCS#8 PEM format: " + location);
        }
        if (key == null || key.getModulus().bitLength() < MIN_KEY_SIZE) {
            throw new IllegalStateException(KEY_PROPERTY + " must be an RSA key of at least " + MIN_KEY_SIZE + " bits: " + location);
        }
        return key;
    }

    // Una clave privada PKCS#8 trae el módulo y el exponente público, así que no hace falta configurar la pública
    private static RSAPublicKey publicKeyOf(RSAPrivateKey privateKey) {
        if (!(privateKey instanceof RSAPrivateCrtKey crtKey)) {
            throw new IllegalStateException(KEY_PROPERTY + " does not contain the public exponent of the key");
        }
        try {
            return (RSAPublicKey) KeyFactory.getInstance("RSA")
                    .generatePublic(new RSAPublicKeySpec(crtKey.getModulus(), crtKey.getPublicExponent()));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot derive the public key from " + KEY_PROPERTY);
        }
    }
}
