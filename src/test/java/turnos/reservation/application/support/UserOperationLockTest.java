package turnos.reservation.application.support;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserOperationLockTest {

    private final UserOperationLock lock = new UserOperationLock();

    @Test
    void theWorkRunsAndItsResultIsReturned() {
        assertThat(lock.exclusivelyFor(42L, () -> "done")).isEqualTo("done");
    }

    @Test
    void twoOperationsOfTheSameUserNeverOverlap() throws Exception {
        AtomicInteger running = new AtomicInteger();
        AtomicInteger maxRunning = new AtomicInteger();
        try (ExecutorService executor = Executors.newFixedThreadPool(4)) {
            for (int i = 0; i < 4; i++) {
                executor.submit(() -> lock.exclusivelyFor(42L, () -> {
                    maxRunning.accumulateAndGet(running.incrementAndGet(), Math::max);
                    sleep(50);
                    running.decrementAndGet();
                    return null;
                }));
            }
        }

        assertThat(maxRunning.get()).isEqualTo(1);
    }

    // Las dos operaciones esperan a que la otra empiece: solo terminan si corren a la vez
    @Test
    void operationsOfDifferentUsersDoNotWaitForEachOther() throws Exception {
        CountDownLatch bothStarted = new CountDownLatch(2);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Boolean> maria = executor.submit(() -> lock.exclusivelyFor(1L, () -> meetOther(bothStarted)));
            Future<Boolean> juan = executor.submit(() -> lock.exclusivelyFor(2L, () -> meetOther(bothStarted)));

            assertThat(maria.get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(juan.get(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void theLockIsDiscardedWhenNobodyUsesIt() {
        lock.exclusivelyFor(42L, () -> null);

        assertThatThrownBy(() -> lock.exclusivelyFor(7L, () -> {
            throw new IllegalStateException("the work failed");
        })).hasMessage("the work failed");

        assertThat(lock.usersInUse()).isZero();
    }

    private static boolean meetOther(CountDownLatch bothStarted) {
        bothStarted.countDown();
        try {
            return bothStarted.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
