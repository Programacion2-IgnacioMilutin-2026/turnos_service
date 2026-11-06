package e2e;

import com.jayway.jsonpath.JsonPath;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import turnos.FakeHttpServer;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

// El profesional de los E2E: uno habilitado, de una categoría habilitada y con horarios habilitados, elegido en cada
// corrida desde el snapshot de la cátedra. Uno fijo deja de servir cuando la cátedra lo deshabilita, como pasó con el
// 201 el 2026-11-05. El catálogo de los E2E es simulado: responde con este profesional y estos horarios
record E2EProfessional(long id, String firstName, String lastName, String categoryName, List<Schedule> schedules) {

    private static final ZoneId ZONE = ZoneId.of("America/Argentina/Mendoza");
    // Cuántos días hacia adelante se buscan turnos libres, y cuántas consultas de turnos libres se hacen como mucho
    private static final int DAYS_AHEAD = 14;
    private static final int MAX_FREE_SLOT_QUERIES = 20;
    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("HH:mm");

    record Schedule(DayOfWeek dayOfWeek, LocalTime startTime, LocalTime endTime, int slotDurationMinutes) {

        // Los turnos que el horario ofrece, como los informa turnos: HH:mm
        List<String> slots() {
            List<String> slots = new ArrayList<>();
            for (LocalTime slot = startTime; !slot.plusMinutes(slotDurationMinutes).isAfter(endTime)
                    && !slot.plusMinutes(slotDurationMinutes).isBefore(slot); slot = slot.plusMinutes(slotDurationMinutes)) {
                slots.add(slot.format(HOUR));
            }
            return slots;
        }
    }

    // Lo elegido para un recorrido: el profesional, el día y sus turnos libres
    record Choice(E2EProfessional professional, LocalDate date, List<String> freeSlots) {
    }

    // Lee el snapshot de la cátedra con el JWT técnico de la app. Es una consulta: no cambia nada. Nunca muestra la URL
    // ni el token
    static List<E2EProfessional> enabledInTheCatedra(String catedraBaseUrl, String technicalToken) {
        ResponseEntity<String> response = RestClient.create().get()
                .uri(catedraBaseUrl + "/api/synchronization/snapshot")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + technicalToken)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .onStatus(status -> true, (request, httpResponse) -> {
                    // el estado se revisa abajo, sin lanzar una excepción
                })
                .toEntity(String.class);
        assertThat(response.getStatusCode().value()).as("HTTP status of the Catedra snapshot").isEqualTo(200);
        return fromSnapshot(response.getBody());
    }

    static List<E2EProfessional> fromSnapshot(String snapshot) {
        List<Map<String, Object>> categories = JsonPath.read(snapshot, "$.professionalCategories[?(@.enabled == true)]");
        Map<Long, String> categoryNames = categories.stream()
                .collect(Collectors.toMap(category -> number(category.get("id")), category -> (String) category.get("name")));
        List<Map<String, Object>> schedules = JsonPath.read(snapshot, "$.weeklySchedules[?(@.enabled == true)]");
        List<Map<String, Object>> professionals = JsonPath.read(snapshot, "$.professionals[?(@.enabled == true)]");
        return professionals.stream()
                .filter(professional -> categoryNames.containsKey(number(professional.get("categoryId"))))
                .map(professional -> {
                    long id = number(professional.get("id"));
                    List<Schedule> own = schedules.stream()
                            .filter(schedule -> number(schedule.get("professionalId")) == id)
                            .map(schedule -> new Schedule(DayOfWeek.valueOf((String) schedule.get("dayOfWeek")),
                                    LocalTime.parse((String) schedule.get("startTime")),
                                    LocalTime.parse((String) schedule.get("endTime")),
                                    (int) number(schedule.get("slotDurationMinutes"))))
                            .toList();
                    return new E2EProfessional(id, (String) professional.get("firstName"), (String) professional.get("lastName"),
                            categoryNames.get(number(professional.get("categoryId"))), own);
                })
                .filter(professional -> !professional.schedules().isEmpty())
                .sorted(Comparator.comparingLong(E2EProfessional::id))
                .toList();
    }

    // Recorre los profesionales y los días desde mañana hasta encontrar uno con al menos los turnos libres pedidos.
    // Antes de cada consulta, el catálogo simulado pasa a responder con ese profesional
    static Choice choose(List<E2EProfessional> professionals, FakeHttpServer catalog, int freeSlotsNeeded,
                         BiFunction<Long, LocalDate, List<String>> freeSlots) {
        assertThat(professionals).as("enabled professionals with enabled schedules in the Catedra snapshot").isNotEmpty();
        LocalDate tomorrow = LocalDate.now(ZONE).plusDays(1);
        int queries = 0;
        for (int day = 0; day < DAYS_AHEAD; day++) {
            LocalDate date = tomorrow.plusDays(day);
            for (E2EProfessional professional : professionals) {
                if (professional.slotsOn(date).isEmpty()) {
                    continue;
                }
                if (queries++ >= MAX_FREE_SLOT_QUERIES) {
                    throw new AssertionError("No professional with " + freeSlotsNeeded + " free slots after "
                            + MAX_FREE_SLOT_QUERIES + " queries");
                }
                professional.answerIn(catalog);
                List<String> free = freeSlots.apply(professional.id(), date);
                if (free.size() >= freeSlotsNeeded) {
                    System.out.println("E2E professional " + professional.id() + " (" + professional.categoryName() + ") on "
                            + date + ": free slots " + free);
                    return new Choice(professional, date, free);
                }
            }
        }
        throw new AssertionError("No professional with " + freeSlotsNeeded + " free slots in the next " + DAYS_AHEAD + " days");
    }

    // Los turnos que ofrece ese día según sus horarios, sin mirar las ocupaciones
    List<String> slotsOn(LocalDate date) {
        return schedules.stream()
                .filter(schedule -> schedule.dayOfWeek() == date.getDayOfWeek())
                .flatMap(schedule -> schedule.slots().stream())
                .sorted()
                .toList();
    }

    void answerIn(FakeHttpServer catalog) {
        catalog.respondToAnyPath(FakeHttpServer.Response.of(200, catalogResponse()));
    }

    // Como lo devuelve el catálogo: el profesional con su categoría y sus horarios
    String catalogResponse() {
        String weeklySchedules = schedules.stream()
                .map(schedule -> """
                        {"dayOfWeek":"%s","startTime":"%s","endTime":"%s","slotDurationMinutes":%d,"enabled":true}"""
                        .formatted(schedule.dayOfWeek(), schedule.startTime(), schedule.endTime(), schedule.slotDurationMinutes()))
                .collect(Collectors.joining(","));
        return """
                {"id":%d,"firstName":"%s","lastName":"%s","enabled":true,"category":{"name":"%s","enabled":true},
                 "weeklySchedules":[%s]}
                """.formatted(id, escaped(firstName), escaped(lastName), escaped(categoryName), weeklySchedules);
    }

    private static String escaped(String value) {
        return Objects.requireNonNullElse(value, "").replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static long number(Object value) {
        return Optional.ofNullable(value).map(number -> ((Number) number).longValue()).orElse(-1L);
    }
}
