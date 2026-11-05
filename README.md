# GearHub API

API do GearHub em Java com Spring Boot e PostgreSQL. No Windows, use `mvnw.cmd` no lugar de `./mvnw`.

## Antes de começar

- JDK 21 ou mais recente, com a variável `JAVA_HOME` apontando para ele.
- Docker é opcional: só é preciso para rodar com o `docker-compose.yml`.

## Rodar sem Docker

Sobe a API com um Postgres real embutido, já com os dados de exemplo. Os dados são recriados a cada execução.

```bash
./mvnw spring-boot:test-run -Dspring-boot.run.mainClass=com.gearhub.LocalGearHubApplication
```

## Rodar com Docker

```bash
docker compose up -d
./mvnw spring-boot:run
```

## Endereços

| O quê | Endereço |
|---|---|
| Swagger | http://localhost:8080/swagger-ui.html |
| Contrato OpenAPI | http://localhost:8080/v3/api-docs |
| Saúde da API | http://localhost:8080/actuator/health |

## Testes

```bash
./mvnw verify
```

Rodam contra um Postgres real embutido, sem precisar de Docker.

## Banco de dados

- Migrations em `src/main/resources/db/migration`. Nunca edite uma que já foi aplicada: crie uma nova.
- Dados de exemplo em `src/main/resources/db/dev`, carregados só no perfil `dev`.

## Perfis

| Perfil | Uso |
|---|---|
| `dev` | Padrão. Inclui dados de exemplo e Swagger |
| `prod` | Sem dados de exemplo e sem Swagger. Ative com `SPRING_PROFILES_ACTIVE=prod` |
