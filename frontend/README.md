# FixFlow — frontend

Workspace Angular diretamente em `frontend/`, com telas reais de cadastro, login e
área protegida da empresa. O access token fica em memória; refresh é cookie HttpOnly,
rotacionado pelo servidor. Não há armazenamento de tokens em localStorage/sessionStorage.

## Executar

1. Configure o banco e as chaves da API conforme [backend/README.md](../backend/fixflow/README.md).
2. Inicie a API em `localhost:8080`.
3. Na raiz do repositório:

```bash
cd frontend
npm ci
npm start
```

Abra `http://localhost:4200`. O proxy encaminha `/api/**` para `localhost:8080`, mantendo
as chamadas na mesma origem do navegador. O proxy está configurado em `angular.json`,
então funciona tanto com `npm start` quanto com `ng serve`. Reinicie o servidor
Angular ao alterar a configuração de proxy. O backend deve aceitar essa origem em
`APP_ORIGIN`. Para HTTP local, o script de chaves configura cookie Secure=false;
em produção, use HTTPS e mantenha Secure=true.

## Fluxo

- `/cadastro`: cria conta e empresa; confirma sucesso e encaminha ao login sem transportar senha.
- `/entrar`: autentica e encaminha à área protegida. Sem tenant explícito, a API seleciona
  o vínculo ativo mais antigo; seleção de múltiplas empresas é uma evolução posterior.
- `/inicio`: guard restaura a sessão por refresh ao recarregar a página. Um botão confirma
  o acesso ao tenant pela API existente. Funcionalidades de ordens de serviço ainda não existem.

Não há logout nem recuperação de senha nesta task (US-003).
Chamadas simultâneas no mesmo cliente compartilham o refresh para evitar reuso involuntário.
Reuso entre abas independentes pode revogar a família, exigindo novo login.

## Estrutura

```text
frontend/
  angular.json, package.json, package-lock.json, tsconfig*.json
  proxy.conf.json
  public/assets/
  src/
    app/
      core/auth/
        dto/
        data-access/
        guards/
        interceptors/
      layout/header/
      features/home/pages/home/
      features/auth/pages/login/
      features/auth/pages/register/
      features/workspace/pages/workspace/
    styles.css
```

## Verificar

```bash
npm test -- --watch=false
npm run build
```

Testes cobrem cadastro/duplicidade/limite de senha, login e erro genérico, guard de
sessão, renovação concorrente, retry autenticado e ausência de tokens no armazenamento.
O build é gerado em `dist/fixflow-front/browser`. A hospedagem deve encaminhar `/api`
à API e devolver `index.html` para as demais rotas da SPA. Node 24 e npm 11 foram usados.

### Smoke test em navegador real

O script opcional `scripts/auth-browser-smoke.mjs` usa o WebDriver BiDi do Firefox e
Node 24, sem bibliotecas adicionais. Execute somente contra banco descartável/de testes;
ele cria uma conta única de teste. API e Angular devem estar iniciados e usar a mesma origem.

```bash
firefox --headless --no-remote --profile /tmp/fixflow-browser-profile --remote-debugging-port 9222
node scripts/auth-browser-smoke.mjs
```

Crie o diretório de perfil antes de iniciar o Firefox. `FIXFLOW_E2E_ORIGIN` e
`FIXFLOW_BIDI_URL` permitem outras portas. `FIXFLOW_E2E_EMAIL_FILE` salva o e-mail
criado para a limpeza posterior; `FIXFLOW_E2E_ARTIFACTS` aponta para um diretório já
existente onde gravar screenshots. O teste confere cadastro, login, tenant autorizado,
renovação ao recarregar, ausência de tokens em armazenamento JS e layout móvel sem overflow.
