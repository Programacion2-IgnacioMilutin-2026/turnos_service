package turnos.availability.application.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import turnos.availability.domain.model.FreeSlotsResult;
import turnos.availability.domain.ports.in.GetFreeSlotsUseCase;

import java.time.LocalDate;

@Service
@RequiredArgsConstructor
public class AvailabilityService {

    private final GetFreeSlotsUseCase getFreeSlotsUseCase;

    public FreeSlotsResult getFreeSlots(Long professionalId, LocalDate date, String userToken) {
        return getFreeSlotsUseCase.getFreeSlots(professionalId, date, userToken);
    }
}
