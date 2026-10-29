package turnos;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.function.Consumer;

// Arma tokens de prueba con cualquier contenido: válidos, vencidos, mal firmados o alterados
public final class TestTokens {

	private static final Base64.Encoder BASE64_URL = Base64.getUrlEncoder().withoutPadding();

	private TestTokens() {
	}

	// Firmado con la clave de test, para un usuario y con una hora de vigencia
	public static String forUser(String subject, String authorities) {
		Instant now = Instant.now();
		return signed(claims -> claims.subject(subject).claim("auth", authorities)
				.issuedAt(now).expiresAt(now.plusSeconds(3600)));
	}

	public static String signed(Consumer<JwtClaimsSet.Builder> claims) {
		return signedWith(TestRsaKeys.keyPair(), claims);
	}

	public static String signedWith(KeyPair keyPair, Consumer<JwtClaimsSet.Builder> claims) {
		NimbusJwtEncoder encoder = NimbusJwtEncoder
				.withKeyPair((RSAPublicKey) keyPair.getPublic(), (RSAPrivateKey) keyPair.getPrivate())
				.build();
		JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).build();
		return encoder.encode(JwtEncoderParameters.from(header, build(claims))).getTokenValue();
	}

	// HS256 con un secreto cualquiera: el algoritmo que no corresponde
	public static String signedWithSecret(Consumer<JwtClaimsSet.Builder> claims) {
		byte[] secret = "un-secreto-de-prueba-de-al-menos-32-bytes".getBytes(StandardCharsets.UTF_8);
		NimbusJwtEncoder encoder = NimbusJwtEncoder.withSecretKey(new SecretKeySpec(secret, "HmacSHA256")).build();
		JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
		return encoder.encode(JwtEncoderParameters.from(header, build(claims))).getTokenValue();
	}

	// Sin firma ("alg": "none"), con el payload JSON que se indique
	public static String unsigned(String payloadJson) {
		return encode("{\"alg\":\"none\"}") + "." + encode(payloadJson) + ".";
	}

	// Conserva el encabezado y la firma originales, pero cambia el payload
	public static String withPayload(String token, String payloadJson) {
		String[] parts = token.split("\\.");
		return parts[0] + "." + encode(payloadJson) + "." + parts[2];
	}

	private static JwtClaimsSet build(Consumer<JwtClaimsSet.Builder> claims) {
		JwtClaimsSet.Builder builder = JwtClaimsSet.builder();
		claims.accept(builder);
		return builder.build();
	}

	private static String encode(String json) {
		return BASE64_URL.encodeToString(json.getBytes(StandardCharsets.UTF_8));
	}
}
