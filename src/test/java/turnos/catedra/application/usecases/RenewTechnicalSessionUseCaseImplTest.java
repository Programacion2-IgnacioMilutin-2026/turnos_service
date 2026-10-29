package turnos.catedra.application.usecases;

import turnos.MutableClock;
import org.junit.jupiter.api.Test;
import turnos.catedra.domain.model.IntegrationState;
import turnos.catedra.domain.model.ProvisioningStatus;
import turnos.catedra.domain.model.TechnicalLoginPolicy;
import turnos.catedra.domain.model.TechnicalLoginResult;
import turnos.catedra.domain.model.TechnicalSession;
import turnos.catedra.infrastructure.persistence.adapter.InMemoryTechnicalSessionRepositoryAdapter;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class RenewTechnicalSessionUseCaseImplTest {

    private static final Instant START = Instant.parse("2026-10-21T12:00:00Z");
    private static final Duration INTERVAL = Duration.ofSeconds(60);

    private final MutableClock clock = new MutableClock(START);
    private final InMemoryTechnicalSessionRepositoryAdapter repository = new InMemoryTechnicalSessionRepositoryAdapter();
    private final ScriptedLoginClient client = new ScriptedLoginClient();
    private final TechnicalLoginPolicy policy = new TechnicalLoginPolicy(
            5, Duration.ofMillis(1), 2, Duration.ofMillis(10), INTERVAL);
    private final RenewTechnicalSessionUseCaseImpl useCase =
            new RenewTechnicalSessionUseCaseImpl(client, repository, policy, clock);

    @Test
    void aDegradedSessionTriesAgainOnlyAfterTheInterval() {
        repository.save(TechnicalSession.degraded(START));
        client.then(ScriptedLoginClient.success("jwt-b", ProvisioningStatus.PROVISIONED));

        clock.advance(Duration.ofSeconds(3));
        assertThat(useCase.renewTechnicalSession(null)).isEmpty();
        clock.advance(Duration.ofSeconds(50));
        assertThat(useCase.renewTechnicalSession(null)).isEmpty();
        assertThat(client.calls()).isZero();

        clock.advance(Duration.ofSeconds(7));
        Optional<TechnicalSession> renewed = useCase.renewTechnicalSession(null);

        assertThat(client.calls()).isEqualTo(1);
        assertThat(renewed).map(TechnicalSession::token).contains("jwt-b");
        assertThat(repository.get().state()).isEqualTo(IntegrationState.ENABLED);
    }

    @Test
    void aConfigurationErrorNeverCallsTheCatedra() {
        repository.save(TechnicalSession.configurationError(START));

        clock.advance(Duration.ofDays(1));
        IntStream.range(0, 10).forEach(i -> assertThat(useCase.renewTechnicalSession(null)).isEmpty());

        assertThat(client.calls()).isZero();
    }

    @Test
    void whileTheInitialLoginRunsNoRenewalIsAttempted() {
        repository.save(TechnicalSession.connecting());

        assertThat(useCase.renewTechnicalSession(null)).isEmpty();
        assertThat(client.calls()).isZero();
    }

    @Test
    void aRejectedTokenIsRenewedAndTheOldOneIsNotRenewedTwice() {
        repository.save(enabled("jwt-a", START));
        client.then(ScriptedLoginClient.success("jwt-b", ProvisioningStatus.PROVISIONED));
        clock.advance(Duration.ofDays(30));

        Optional<TechnicalSession> first = useCase.renewTechnicalSession("jwt-a");
        Optional<TechnicalSession> second = useCase.renewTechnicalSession("jwt-a");

        assertThat(first).map(TechnicalSession::token).contains("jwt-b");
        assertThat(second).map(TechnicalSession::token).contains("jwt-b");
        assertThat(client.calls()).isEqualTo(1);
    }

    @Test
    void aFreshTokenRejectedBeforeTheIntervalIsNotRenewed() {
        repository.save(enabled("jwt-b", START));

        clock.advance(Duration.ofSeconds(10));

        assertThat(useCase.renewTechnicalSession("jwt-b")).isEmpty();
        assertThat(client.calls()).isZero();
        assertThat(repository.get().token()).isEqualTo("jwt-b");
    }

    @Test
    void twentySimultaneousRenewalsSendASingleLogin() throws Exception {
        int requests = 20;
        repository.save(enabled("jwt-a", START));
        clock.advance(Duration.ofDays(30));
        CountDownLatch allArrived = new CountDownLatch(requests);
        // El login no responde hasta que los 20 pedidos llegaron: así se superponen de verdad
        client.then(() -> {
            await(allArrived);
            sleep(Duration.ofMillis(100));
            return ScriptedLoginClient.success("jwt-b", ProvisioningStatus.PROVISIONED);
        });

        List<Optional<TechnicalSession>> results;
        try (ExecutorService executor = Executors.newFixedThreadPool(requests)) {
            List<Future<Optional<TechnicalSession>>> futures = IntStream.range(0, requests)
                    .mapToObj(i -> executor.submit(() -> {
                        allArrived.countDown();
                        return useCase.renewTechnicalSession("jwt-a");
                    }))
                    .toList();
            results = futures.stream().map(RenewTechnicalSessionUseCaseImplTest::get).toList();
        }

        assertThat(client.calls()).isEqualTo(1);
        assertThat(results).hasSize(requests)
                .allSatisfy(result -> assertThat(result).map(TechnicalSession::token).contains("jwt-b"));
    }

    @Test
    void aFailedRenewalLeavesTheSessionDegraded() {
        repository.save(enabled("jwt-a", START));
        client.then(new TechnicalLoginResult.Unavailable("HTTP 503"));
        clock.advance(Duration.ofDays(30));

        assertThat(useCase.renewTechnicalSession("jwt-a")).isEmpty();
        assertThat(repository.get()).isEqualTo(TechnicalSession.degraded(clock.instant()));
    }

    @Test
    void anUnexpectedErrorDuringARenewalDegradesAndReleasesTheLock() {
        repository.save(enabled("jwt-a", START));
        client.then(() -> {
            throw new IllegalStateException("unexpected");
        }).then(ScriptedLoginClient.success("jwt-b", ProvisioningStatus.PROVISIONED));
        clock.advance(Duration.ofDays(30));

        assertThat(useCase.renewTechnicalSession("jwt-a")).isEmpty();
        assertThat(repository.get().state()).isEqualTo(IntegrationState.DEGRADED);

        clock.advance(INTERVAL);
        assertThat(useCase.renewTechnicalSession(null)).map(TechnicalSession::token).contains("jwt-b");
        assertThat(client.calls()).isEqualTo(2);
    }

    @Test
    void aRenewalWithoutProvisioningLeavesTheSessionDegraded() {
        repository.save(enabled("jwt-a", START));
        client.then(ScriptedLoginClient.success("jwt-b", ProvisioningStatus.REVOKED));
        clock.advance(Duration.ofDays(30));

        assertThat(useCase.renewTechnicalSession("jwt-a")).isEmpty();
        assertThat(repository.get().state()).isEqualTo(IntegrationState.DEGRADED);
    }

    @Test
    void aRejectedRenewalIsAConfigurationErrorUntilRestart() {
        repository.save(enabled("jwt-a", START));
        client.then(new TechnicalLoginResult.ConfigurationError("HTTP 401"));
        clock.advance(Duration.ofDays(30));

        assertThat(useCase.renewTechnicalSession("jwt-a")).isEmpty();
        clock.advance(Duration.ofDays(1));
        assertThat(useCase.renewTechnicalSession(null)).isEmpty();

        assertThat(client.calls()).isEqualTo(1);
        assertThat(repository.get().state()).isEqualTo(IntegrationState.CONFIGURATION_ERROR);
    }

    private TechnicalSession enabled(String token, Instant attemptAt) {
        return TechnicalSession.enabled(token, ScriptedLoginClient.integration(ProvisioningStatus.PROVISIONED), attemptAt);
    }

    private static <T> T get(Future<T> future) {
        try {
            return future.get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
