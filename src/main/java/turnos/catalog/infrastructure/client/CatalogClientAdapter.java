package turnos.catalog.infrastructure.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import turnos.catalog.domain.model.ProfessionalResult;
import turnos.catalog.domain.ports.out.CatalogClient;
import turnos.catalog.infrastructure.client.dto.ProfessionalResponse;
import turnos.catalog.infrastructure.client.mapper.ProfessionalMapper;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.time.Duration;

// Paquete infrastructure/client: excepción a la skill autorizada por el usuario.
// Habla con catálogo por HTTP: reenvía el token del usuario (nunca lo registra), reintenta si catálogo no está
// disponible y traduce cada respuesta a un resultado del dominio
@Slf4j
@Component
public class CatalogClientAdapter implements CatalogClient {

    private static final String BASE_URL_PROPERTY = "turnos.catalog.base-url";
    private static final String PROFESSIONAL_PATH = "/api/professionals/{professionalId}";

    private final CatalogProperties catalogProperties;
    private final boolean configured;
    private final ProfessionalMapper professionalMapper;
    private final RestClient restClient;

    public CatalogClientAdapter(CatalogProperties catalogProperties, ProfessionalMapper professionalMapper) {
        this.catalogProperties = catalogProperties;
        this.professionalMapper = professionalMapper;
        this.configured = isConfigured(catalogProperties.baseUrl());
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(catalogProperties.connectTimeout()).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(catalogProperties.readTimeout());
        this.restClient = RestClient.builder().requestFactory(requestFactory).build();
    }

    // Solo se reintenta cuando catálogo no está disponible; cualquier otra respuesta se devuelve al primer intento
    @Override
    public ProfessionalResult fetchProfessional(Long professionalId, String userToken) {
        if (!configured) {
            return new ProfessionalResult.Unavailable(BASE_URL_PROPERTY + " is missing or invalid");
        }
        int maxAttempts = catalogProperties.maxAttempts();
        ProfessionalResult result = new ProfessionalResult.Unavailable("Catalog request was not attempted");
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            if (!waitBefore(attempt)) {
                break;
            }
            result = requestOnce(professionalId, userToken);
            if (!(result instanceof ProfessionalResult.Unavailable unavailable)) {
                return result;
            }
            log.warn("Catalog request for professional {} attempt {}/{} failed: {}",
                    professionalId, attempt, maxAttempts, unavailable.reason());
        }
        return result;
    }

    private ProfessionalResult requestOnce(Long professionalId, String userToken) {
        try {
            return restClient.get()
                    .uri(catalogProperties.baseUrl() + PROFESSIONAL_PATH, professionalId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken)
                    .accept(MediaType.APPLICATION_JSON)
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        return switch (status) {
                            case 200 -> found(response.bodyTo(ProfessionalResponse.class));
                            case 401, 403 -> new ProfessionalResult.Rejected(status);
                            case 404 -> new ProfessionalResult.NotFound();
                            case 503 -> new ProfessionalResult.Unavailable("Catalog answered HTTP 503");
                            default -> new ProfessionalResult.Failed("Catalog answered HTTP " + status);
                        };
                    });
        } catch (ResourceAccessException e) {
            return new ProfessionalResult.Unavailable("Catalog is not reachable or did not answer in time");
        } catch (RestClientException e) {
            return new ProfessionalResult.Failed("Catalog answered an unreadable response");
        } catch (RuntimeException e) {
            // Un error no previsto es un fallo propio, no un catálogo caído: no se reintenta
            log.error("Unexpected error requesting professional {} from the catalog: {}", professionalId, e.getClass().getSimpleName());
            return new ProfessionalResult.Failed("Unexpected error: " + e.getClass().getSimpleName());
        }
    }

    private ProfessionalResult found(ProfessionalResponse response) {
        if (response == null || response.getId() == null) {
            return new ProfessionalResult.Failed("Catalog answered an empty professional");
        }
        return new ProfessionalResult.Found(professionalMapper.toDomain(response));
    }

    // El primer intento no espera; desde el segundo, la espera crece con el multiplicador
    private boolean waitBefore(int attempt) {
        if (attempt <= 1) {
            return true;
        }
        long millis = (long) (catalogProperties.initialDelay().toMillis()
                * Math.pow(catalogProperties.multiplier(), attempt - 2.0));
        try {
            Thread.sleep(Duration.ofMillis(millis));
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    // Se avisa una sola vez, al arrancar: sin una URL válida no se envía ningún request
    private static boolean isConfigured(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            log.warn("Catalog client disabled: missing configuration property {}", BASE_URL_PROPERTY);
            return false;
        }
        if (!isHttpUrl(baseUrl)) {
            log.warn("Catalog client disabled: {} is not a valid http or https URL{}", BASE_URL_PROPERTY,
                    baseUrl.startsWith("\"") || baseUrl.startsWith("'") ? " (remove the quotes from the .env value)" : "");
            return false;
        }
        return true;
    }

    private static boolean isHttpUrl(String value) {
        try {
            URI uri = new URI(value);
            boolean httpScheme = "http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme());
            return httpScheme && uri.getHost() != null;
        } catch (URISyntaxException e) {
            return false;
        }
    }
}
