# Imagen del servicio de turnos, en dos etapas: la primera compila el jar y la segunda solo lo ejecuta.
# Los tests no corren acá: necesitan Docker (Testcontainers). Se corren aparte con ./mvnw test.

# Etapa 1: compila con el Maven Wrapper del repositorio, que descarga su propio Maven
FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY src/ src/
# El caché de Maven se conserva entre builds: no se vuelven a descargar las dependencias
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q -DskipTests package && cp target/*.jar app.jar

# Etapa 2: solo el JRE y el jar, sin el código fuente ni Maven, con un usuario sin privilegios
FROM eclipse-temurin:25-jre
WORKDIR /app
RUN groupadd --system turnos && useradd --system --gid turnos --no-create-home turnos
COPY --from=build /workspace/app.jar app.jar
USER turnos
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
