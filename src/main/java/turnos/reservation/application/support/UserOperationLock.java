package turnos.reservation.application.support;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

// Paquete application/support: excepción a la skill autorizada por el usuario.
// Un candado por usuario que comparten las operaciones sobre sus procesos (bloquear y confirmar): las de un mismo
// usuario se atienden de a una, y las de usuarios distintos no se esperan. Vale para una sola instancia del servicio
@Component
public class UserOperationLock {

    // Cada candado se descarta cuando nadie lo usa, para que el mapa no crezca con cada usuario
    private final Map<Long, UserLock> userLocks = new ConcurrentHashMap<>();

    public <T> T exclusivelyFor(Long userId, Supplier<T> work) {
        UserLock userLock = userLocks.compute(userId, (id, current) -> (current == null ? new UserLock() : current).take());
        userLock.lock().lock();
        try {
            return work.get();
        } finally {
            userLock.lock().unlock();
            userLocks.computeIfPresent(userId, (id, current) -> current.release());
        }
    }

    // Cuántos usuarios tienen un candado en uso; sirve para comprobar que se descartan
    int usersInUse() {
        return userLocks.size();
    }

    // Cuántos pedidos usan el candado; solo se modifica dentro de compute, que el mapa ejecuta de a uno por usuario
    private static final class UserLock {
        private final ReentrantLock lock = new ReentrantLock();
        private int users;

        ReentrantLock lock() {
            return lock;
        }

        UserLock take() {
            users++;
            return this;
        }

        UserLock release() {
            users--;
            return users == 0 ? null : this;
        }
    }
}
