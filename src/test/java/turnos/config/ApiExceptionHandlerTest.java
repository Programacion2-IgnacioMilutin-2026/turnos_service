package turnos.config;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import turnos.TestSecurityConfiguration;
import turnos.TestTokens;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

@WebMvcTest(ProbeController.class)
@Import({TestSecurityConfiguration.class, ProbeController.class})
class ApiExceptionHandlerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void anUnknownRouteAnswersNotFoundWithoutRevealingHowRoutesAreResolved() throws Exception {
        MockHttpServletResponse response = mockMvc.perform(get("/api/no-existe")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.forUser("42", "ROLE_USER")))
                .andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(response.getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        Map<String, Object> problem = JsonPath.read(response.getContentAsString(), "$");
        assertThat(problem).containsEntry("title", "Not Found").containsEntry("status", 404)
                .containsEntry("detail", "Resource not found").containsEntry("instance", "/api/no-existe");
        assertThat(response.getContentAsString()).doesNotContainIgnoringCase("static");
    }
}
