# FixFlow
SaaS B2B multi-tenant para gestão de assistências técnicas


## Estrutura

- `backend/fixflow/`: API Spring Boot.
- `frontend/`: workspace Angular, com código em `src/` e arquivos públicos em `public/`.

Para executar a interface:

```bash
cd frontend
npm ci
npm start
```

Instruções completas em [frontend/README.md](frontend/README.md).

## Anexos com S3 local

Na raiz do projeto, suba o LocalStack e seu gateway:

```bash
docker compose up -d localstack-gateway
```

O bucket privado `fixflow-attachments` fica disponível em `http://localhost:4566`.
Para o backend no host, execute em `backend/fixflow`:

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=localstack
```

O ambiente também aceita `podman-compose`. Detalhes de configuração, snapshots
locais e testes em [desenvolvimento.md](backend/fixflow/docs/desenvolvimento.md).
