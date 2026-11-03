# FixFlow — frontend Angular

Aplicação Angular com a home institucional do FixFlow, baseada na identidade visual
existente. O painel usa **dados fictícios** e não faz chamadas à API. Login e cadastro
ainda não fazem parte desta interface.

## Executar

Na raiz do repositório:

```bash
cd frontend
```

Em seguida:

```bash
npm ci
npm start
```

Abra `http://localhost:4200`. Validado com Node 24 e npm 11.

## Verificar e gerar produção

```bash
npm test -- --watch=false
npm run build
```

A saída publicável fica em `dist/fixflow-front/browser`. Configure a hospedagem para
retornar `index.html` nas rotas da aplicação. A home é carregada sob demanda e rotas
desconhecidas redirecionam para ela.

## Organização

```text
frontend/
├── angular.json
├── package.json
├── package-lock.json
├── tsconfig*.json
├── public/assets/              # Logos e arquivos públicos
├── src/
│   ├── main.ts
│   ├── index.html
│   ├── styles.css              # Tema e estilos responsivos
│   └── app/
│       ├── app.*               # Shell, configuração e rotas
│       ├── layout/
│       │   ├── header/
│       │   └── footer/
│       └── features/home/
│           ├── pages/home/
│           ├── components/about-dialog/
│           ├── data/
│           └── models/
└── docs/reference/static-home/ # Referência da versão anterior
```

Estado e eventos usam bindings Angular e signals. Não há `onclick` inline nem
manipulação global do DOM. O modal nativo cuida de foco, Escape e bloqueio do fundo.
Os testes cobrem rotas, etapas, menu móvel e ações de abertura/fechamento do modal.

A versão estática original está preservada em `docs/reference/static-home/`, fora de
`src/` e `public/`. Ela não participa do build nem é uma segunda aplicação mantida.
