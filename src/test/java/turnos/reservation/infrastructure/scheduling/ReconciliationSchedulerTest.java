package turnos.reservation.infrastructure.scheduling;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import turnos.reservation.application.service.ReservationService;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

class ReconciliationSchedulerTest {

    private static final String ENABLED = "turnos.reservation.reconciliation.enabled";
    private static final String INTERVAL = "turnos.reservation.reconciliation.interval";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Scheduling.class, ReconciliationScheduler.class);

    @ParameterizedTest
    @ValueSource(strings = {"1m", "PT1M", "60000"})
    void theFirstCycleRunsWhenTheApplicationStartsWithoutWaitingForTheInterval(String interval) {
        runner.withPropertyValues(ENABLED + "=true", INTERVAL + "=" + interval).run(context -> {
            assertThat(context).hasNotFailed();
            verify(context.getBean(ReservationService.class), timeout(2000)).reconcilePendingProcesses();
            verify(context.getBean(ReservationService.class), after(300).times(1)).reconcilePendingProcesses();
        });
    }

    @Test
    void theCycleRepeatsAtTheInterval() {
        runner.withPropertyValues(ENABLED + "=true", INTERVAL + "=50ms").run(context ->
                verify(context.getBean(ReservationService.class), timeout(5000).atLeast(3)).reconcilePendingProcesses());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "", "abc"})
    void theApplicationDoesNotStartWithAnInvalidInterval(String interval) {
        runner.withPropertyValues(ENABLED + "=true", INTERVAL + "=" + interval).run(context ->
                assertThat(context).hasFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"false", ""})
    void theReconciliationDoesNotExistIfItIsTurnedOffOrTheVariableIsEmpty(String enabled) {
        runner.withPropertyValues(ENABLED + "=" + enabled, INTERVAL + "=50ms").run(context -> {
            assertThat(context).doesNotHaveBean(ReconciliationScheduler.class);
            verify(context.getBean(ReservationService.class), after(200).never()).reconcilePendingProcesses();
        });
    }

    // La suite la apaga por propiedad de sistema; acá se quita para ver lo que pasa en producción si no se configura
    @Test
    void theReconciliationExistsIfNothingTurnsItOff() {
        runner.withSystemProperties(ENABLED).withPropertyValues(INTERVAL + "=1m").run(context ->
                assertThat(context).hasSingleBean(ReconciliationScheduler.class));
    }

    // Con la configuración del servicio: mientras un ciclo espera a la cátedra, el barrido de vencidos sigue corriendo
    @Test
    void aSlowReconciliationDoesNotDelayTheOverdueSweep() throws InterruptedException {
        CountDownLatch reconciling = new CountDownLatch(1);
        CountDownLatch catedraAnswers = new CountDownLatch(1);
        ReservationService service = mock(ReservationService.class);
        doAnswer(invocation -> {
            reconciling.countDown();
            catedraAnswers.await(10, TimeUnit.SECONDS);
            return null;
        }).when(service).reconcilePendingProcesses();
        try {
            new ApplicationContextRunner()
                    .withInitializer(new ConfigDataApplicationContextInitializer())
                    .withConfiguration(AutoConfigurations.of(TaskSchedulingAutoConfiguration.class))
                    .withUserConfiguration(SchedulingOnly.class, ReconciliationScheduler.class, OverdueProcessSweepScheduler.class)
                    .withBean(ReservationService.class, () -> service)
                    .withPropertyValues(ENABLED + "=true", INTERVAL + "=1h", "turnos.reservation.sweep.enabled=true",
                            "turnos.reservation.sweep.interval=50ms")
                    .run(context -> {
                        assertThat(reconciling.await(5, TimeUnit.SECONDS)).isTrue();
                        verify(service, timeout(5000).atLeast(3)).expireOverdueProcesses();
                    });
        } finally {
            catedraAnswers.countDown();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    static class SchedulingOnly {
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    static class Scheduling {
        @Bean
        ReservationService reservationService() {
            return mock(ReservationService.class);
        }
    }
}
