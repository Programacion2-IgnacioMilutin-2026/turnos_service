package turnos.user.infrastructure.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.DefaultResourceLoader;
import turnos.TestRsaKeys;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.Signature;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class JwtSigningKeysTest {

    private final JwtSigningKeys keys = new JwtSigningKeys(
            new JwtProperties(TestRsaKeys.privateKeyLocation(), Duration.ofHours(24)), new DefaultResourceLoader());

    @Test
    void thePublicKeyIsTheOneThatMatchesThePrivateKey() {
        assertThat(keys.publicKey()).isEqualTo(TestRsaKeys.publicKey());
    }

    @Test
    void thePublicKeyVerifiesASignatureMadeWithThePrivateKey() throws GeneralSecurityException {
        byte[] data = "datos firmados".getBytes(StandardCharsets.UTF_8);
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(keys.privateKey());
        signer.update(data);
        byte[] signature = signer.sign();

        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(keys.publicKey());
        verifier.update(data);

        assertThat(verifier.verify(signature)).isTrue();
    }

    @Test
    void theApplicationDoesNotStartWithoutTheKeyProperty() {
        expectStartupFailure(contextRunner(), "Missing configuration property turnos.jwt.private-key-location");
    }

    @Test
    void theApplicationDoesNotStartWhenTheKeyFileDoesNotExist(@TempDir Path dir) {
        expectStartupFailure(contextRunner(dir.resolve("missing.pem")), "points to a file that does not exist");
    }

    @Test
    void theApplicationDoesNotStartWhenTheFileIsNotAKey(@TempDir Path dir) throws IOException {
        Path file = Files.writeString(dir.resolve("not-a-key.pem"), "-----BEGIN PRIVATE KEY-----\nnot a key\n-----END PRIVATE KEY-----\n");

        expectStartupFailure(contextRunner(file), "is not an RSA private key in PKCS#8 PEM format");
    }

    @Test
    void theApplicationDoesNotStartWithAKeySmallerThan2048Bits() {
        Path file = TestRsaKeys.writePrivateKey(TestRsaKeys.generate(1024));

        expectStartupFailure(contextRunner(file), "must be an RSA key of at least 2048 bits");
    }

    private ApplicationContextRunner contextRunner(Path keyFile) {
        return contextRunner().withPropertyValues("turnos.jwt.private-key-location=file:" + keyFile.toAbsolutePath());
    }

    private ApplicationContextRunner contextRunner() {
        return new ApplicationContextRunner()
                .withUserConfiguration(PropertiesConfiguration.class)
                .withBean(JwtSigningKeys.class);
    }

    private void expectStartupFailure(ApplicationContextRunner runner, String expectedMessage) {
        runner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause()
                    .hasMessageContaining(expectedMessage)
                    .message().doesNotContain("BEGIN", "MII");
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(JwtProperties.class)
    static class PropertiesConfiguration {
    }
}
