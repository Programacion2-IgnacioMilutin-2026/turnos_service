package turnos.reservation.infrastructure.scheduling;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import turnos.reservation.application.service.ReservationService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

class OverdueProcessSweepSchedulerTest {

    private static final String ENABLED = "turnos.reservation.sweep.enabled";
    private static final String INTERVAL = "turnos.reservation.sweep.interval";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Scheduling.class, OverdueProcessSweepScheduler.class);

    @ParameterizedTest
    @ValueSource(strings = {"1m", "PT1M", "60000"})
    void theFirstSweepRunsWhenTheApplicationStartsWithoutWaitingForTheInterval(String interval) {
        runner.withPropertyValues(ENABLED + "=true", INTERVAL + "=" + interval).run(context -> {
            assertThat(context).hasNotFailed();
            verify(context.getBean(ReservationService.class), timeout(2000)).expireOverdueProcesses();
            verify(context.getBean(ReservationService.class), after(300).times(1)).expireOverdueProcesses();
        });
    }

    @Test
    void theSweepRepeatsAtTheInterval() {
        runner.withPropertyValues(ENABLED + "=true", INTERVAL + "=50ms").run(context ->
                verify(context.getBean(ReservationService.class), timeout(5000).atLeast(3)).expireOverdueProcesses());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "", "abc"})
    void theApplicationDoesNotStartWithAnInvalidInterval(String interval) {
        runner.withPropertyValues(ENABLED + "=true", INTERVAL + "=" + interval).run(context ->
                assertThat(context).hasFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"false", ""})
    void theSweepDoesNotExistIfItIsTurnedOffOrTheVariableIsEmpty(String enabled) {
        runner.withPropertyValues(ENABLED + "=" + enabled, INTERVAL + "=50ms").run(context -> {
            assertThat(context).doesNotHaveBean(OverdueProcessSweepScheduler.class);
            verify(context.getBean(ReservationService.class), after(200).never()).expireOverdueProcesses();
        });
    }

    // La suite lo apaga por propiedad de sistema; acá se quita para ver lo que pasa en producción si no se configura
    @Test
    void theSweepExistsIfNothingTurnsItOff() {
        runner.withSystemProperties(ENABLED).withPropertyValues(INTERVAL + "=1m").run(context ->
                assertThat(context).hasSingleBean(OverdueProcessSweepScheduler.class));
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
