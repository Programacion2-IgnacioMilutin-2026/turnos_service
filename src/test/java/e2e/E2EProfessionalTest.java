package e2e;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import turnos.FakeHttpServer;
import turnos.catalog.infrastructure.client.dto.ProfessionalResponse;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Sin la etiqueta e2e: corre en la suite normal, sin la cátedra. Prueba cómo se elige el profesional de los E2E
class E2EProfessionalTest {

    // Con el formato del snapshot del anexo: el 15 sirve; el 16 está deshabilitado, el 17 es de una categoría
    // deshabilitada y el 18 solo tiene un horario deshabilitado
    private static final String SNAPSHOT = """
            {"snapshotVersion":7,"generatedAt":"2026-09-10T13:44:59Z",
             "professionalCategories":[
               {"id":1,"name":"Clinica medica","description":"General","enabled":true},
               {"id":2,"name":"Pediatria","description":"Chicos","enabled":false}],
             "professionals":[
               {"id":15,"categoryId":1,"firstName":"Ana","lastName":"Gomez","enabled":true},
               {"id":16,"categoryId":1,"firstName":"Luis","lastName":"Diaz","enabled":false},
               {"id":17,"categoryId":2,"firstName":"Eva","lastName":"Paz","enabled":true},
               {"id":18,"categoryId":1,"firstName":"Juan","lastName":"Sosa","enabled":true}],
             "weeklySchedules":[
               {"id":21,"professionalId":15,"dayOfWeek":"MONDAY","startTime":"12:00:00","endTime":"15:00:00","slotDurationMinutes":30,"enabled":true},
               {"id":22,"professionalId":15,"dayOfWeek":"FRIDAY","startTime":"12:00:00","endTime":"13:00:00","slotDurationMinutes":20,"enabled":true},
               {"id":23,"professionalId":16,"dayOfWeek":"MONDAY","startTime":"09:00:00","endTime":"10:00:00","slotDurationMinutes":30,"enabled":true},
               {"id":24,"professionalId":17,"dayOfWeek":"MONDAY","startTime":"09:00:00","endTime":"10:00:00","slotDurationMinutes":30,"enabled":true},
               {"id":25,"professionalId":18,"dayOfWeek":"MONDAY","startTime":"09:00:00","endTime":"10:00:00","slotDurationMinutes":30,"enabled":false}]}
            """;

    private final FakeHttpServer catalog = FakeHttpServer.start();

    @AfterEach
    void stop() {
        catalog.close();
    }

    @Test
    void onlyAnEnabledProfessionalOfAnEnabledCategoryWithEnabledSchedulesIsKept() {
        List<E2EProfessional> professionals = E2EProfessional.fromSnapshot(SNAPSHOT);

        assertThat(professionals).extracting(E2EProfessional::id).containsExactly(15L);
        E2EProfessional ana = professionals.getFirst();
        assertThat(ana.categoryName()).isEqualTo("Clinica medica");
        assertThat(ana.schedules()).containsExactly(
                new E2EProfessional.Schedule(DayOfWeek.MONDAY, LocalTime.NOON, LocalTime.of(15, 0), 30),
                new E2EProfessional.Schedule(DayOfWeek.FRIDAY, LocalTime.NOON, LocalTime.of(13, 0), 20));
    }

    @Test
    void theSlotsOfADayFollowItsSchedules() {
        E2EProfessional ana = E2EProfessional.fromSnapshot(SNAPSHOT).getFirst();

        assertThat(ana.slotsOn(LocalDate.of(2026, 11, 9))).containsExactly("12:00", "12:30", "13:00", "13:30", "14:00", "14:30");
        assertThat(ana.slotsOn(LocalDate.of(2026, 11, 6))).containsExactly("12:00", "12:20", "12:40");
        assertThat(ana.slotsOn(LocalDate.of(2026, 11, 10))).isEmpty();
    }

    @Test
    void theSimulatedCatalogAnswersWhatTurnosReadsFromTheRealOne() throws Exception {
        E2EProfessional ana = E2EProfessional.fromSnapshot(SNAPSHOT).getFirst();
        ana.answerIn(catalog);

        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(catalog.baseUrl() + "/api/professionals/15")).build(),
                HttpResponse.BodyHandlers.ofString());
        ProfessionalResponse read = JsonMapper.builder().build().readValue(response.body(), ProfessionalResponse.class);

        assertThat(read.getId()).isEqualTo(15L);
        assertThat(read.isEnabled()).isTrue();
        assertThat(read.getCategory().getName()).isEqualTo("Clinica medica");
        assertThat(read.getWeeklySchedules()).hasSize(2);
        assertThat(read.getWeeklySchedules().getFirst().getDayOfWeek()).isEqualTo(DayOfWeek.MONDAY);
        assertThat(read.getWeeklySchedules().getFirst().getStartTime()).isEqualTo(LocalTime.NOON);
        assertThat(read.getWeeklySchedules().getFirst().getSlotDurationMinutes()).isEqualTo(30);
        assertThat(read.getWeeklySchedules().getFirst().isEnabled()).isTrue();
    }

    @Test
    void theFirstDayFromTomorrowWithEnoughFreeSlotsIsChosenWithoutAskingForDaysWithoutSchedule() throws Exception {
        List<E2EProfessional> professionals = E2EProfessional.fromSnapshot(SNAPSHOT);
        LocalDate tomorrow = LocalDate.now(ZoneId.of("America/Argentina/Mendoza")).plusDays(1);
        List<LocalDate> asked = new ArrayList<>();

        E2EProfessional.Choice choice = E2EProfessional.choose(professionals, catalog, 2, (id, date) -> {
            asked.add(date);
            // El primer día con horario está lleno: solo queda un turno
            return asked.size() == 1 ? List.of("12:00") : List.of("12:00", "12:30");
        });

        assertThat(asked).hasSize(2).allMatch(date -> !date.isBefore(tomorrow))
                .allMatch(date -> Set.of(DayOfWeek.MONDAY, DayOfWeek.FRIDAY).contains(date.getDayOfWeek()));
        assertThat(choice.date()).isEqualTo(asked.get(1));
        assertThat(choice.freeSlots()).containsExactly("12:00", "12:30");
        assertThat(choice.professional().id()).isEqualTo(15L);
        assertThat(catalog.receivedRequests()).isEmpty();
        assertThat(catalogAnswer()).contains("\"id\":15").contains("Clinica medica");
    }

    private String catalogAnswer() throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(catalog.baseUrl() + "/api/professionals/15")).build(),
                HttpResponse.BodyHandlers.ofString()).body();
    }

    @Test
    void withoutFreeSlotsTheSearchStopsAfterALimitedNumberOfQueries() {
        List<E2EProfessional> professionals = E2EProfessional.fromSnapshot(SNAPSHOT);
        List<LocalDate> asked = new ArrayList<>();

        assertThatThrownBy(() -> E2EProfessional.choose(professionals, catalog, 1, (id, date) -> {
            asked.add(date);
            return List.of();
        })).isInstanceOf(AssertionError.class);
        assertThat(asked).hasSizeLessThanOrEqualTo(20);
    }

    @Test
    void aSnapshotWithoutUsableProfessionalsFailsClearly() {
        assertThatThrownBy(() -> E2EProfessional.choose(List.of(), catalog, 1, (id, date) -> List.of()))
                .isInstanceOf(AssertionError.class).hasMessageContaining("enabled professionals");
    }
}
