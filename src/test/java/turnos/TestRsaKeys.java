package turnos;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;

// Par RSA generado una vez por corrida de tests: no se versiona ninguna clave privada
public final class TestRsaKeys {

	private static final KeyPair KEY_PAIR = generate(2048);
	private static final Path PRIVATE_KEY_FILE = writePrivateKey(KEY_PAIR);

	private TestRsaKeys() {
	}

	public static String privateKeyLocation() {
		return "file:" + PRIVATE_KEY_FILE.toAbsolutePath();
	}

	public static RSAPublicKey publicKey() {
		return (RSAPublicKey) KEY_PAIR.getPublic();
	}

	public static KeyPair generate(int bits) {
		try {
			KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
			generator.initialize(bits);
			return generator.generateKeyPair();
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException(e);
		}
	}

	public static Path writePrivateKey(KeyPair keyPair) {
		try {
			Path file = Files.createTempFile("jwt-private", ".pem");
			file.toFile().deleteOnExit();
			Files.writeString(file, toPem(keyPair));
			return file;
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static String toPem(KeyPair keyPair) {
		String body = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(keyPair.getPrivate().getEncoded());
		return "-----BEGIN PRIVATE KEY-----\n" + body + "\n-----END PRIVATE KEY-----\n";
	}
}
