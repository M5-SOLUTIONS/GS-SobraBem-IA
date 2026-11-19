# SobraBem — IA: orientação de conservação com RAG

O SobraBem conecta padarias, feiras e mercados que têm comida sobrando no fim do dia a ONGs e famílias,
que reservam e retiram com um código. Este repositório traz a API do SobraBem (Java + Spring Boot) com a
funcionalidade de **IA generativa com RAG**: ao publicar uma doação, o doador recebe uma orientação curta de
**como conservar o alimento e até quando consumir**, gerada pelo Gemini **somente** a partir de um corpus de
fontes oficiais e mostrando de qual arquivo a informação veio.

## Integrantes

| Nome | RM |
|---|---|
| Daniel Fonseca de Almeida | RM 563045 |
| Erick de Faria Gama | RM 561951 |
| Guilherme Batista Cintra | RM 562850 |
| Matheus Nascimento Corregio | RM 563765 |
| Pedro Fonseca de Almeida | RM 563466 |

---

## Avaliação da IA

O relatório com os casos testados, os resultados, a interpretação e as limitações está em
**[`avaliacao/RELATORIO.md`](avaliacao/RELATORIO.md)**. Como foi feita a avaliação: [seção 7](#7-avaliação).

---

## Sumário

1. [A funcionalidade e o público](#1-a-funcionalidade-e-o-público)
2. [Como funciona](#2-como-funciona)
3. [Por que RAG: justificativas técnicas](#3-por-que-rag-justificativas-técnicas)
4. [Modelos e tecnologias](#4-modelos-e-tecnologias)
5. [Dados: fontes e preparação](#5-dados-fontes-e-preparação)
6. [Como executar](#6-como-executar)
7. [Avaliação](#7-avaliação)
8. [Uso responsável](#8-uso-responsável)
9. [Limitações](#9-limitações)
10. [Estrutura do repositório](#10-estrutura-do-repositório)
11. [Declaração de uso de IA no desenvolvimento](#11-declaração-de-uso-de-ia-no-desenvolvimento)

---

## 1. A funcionalidade e o público

**Problema:** quem recebe uma doação de comida que sobrou nem sempre sabe como guardá-la nem por quanto
tempo ela continua segura. E o comerciante que doa não tem tempo de escrever essa orientação para cada
item.

**Funcionalidade:** no formulário de doação, o doador toca em **"Gerar orientação"**. A API busca no corpus
os trechos sobre aquele alimento e pede ao Gemini um resumo de até 3 frases. O texto preenche o campo
"orientações de conservação" da doação, e o doador revisa antes de publicar.

| Público | Benefício |
|---|---|
| **Famílias e ONGs** (público principal) | recebem, junto com a comida, como conservá-la e até quando consumir, com a fonte. Menos risco de consumir alimento mal conservado |
| **Doadores** (padarias, feiras, mercados) | não precisam escrever a orientação; só revisam o texto sugerido |

**Contribuição para a CitizenTech:** o SobraBem reduz o desperdício levando a sobra a quem precisa (ODS 2,
Fome zero). A IA garante que essa comida chegue **em condições seguras**, que é o que torna a doação
confiável para quem recebe.

**Exemplo real** (`POST /api/ia/orientacoes`, token de DOADOR):

```json
// pedido
{ "titulo": "Iogurte natural", "descricao": "potes de 170 g", "categoriaId": 3 }

// resposta
{
  "orientacao": "Na geladeira, o iogurte deve ser conservado por 7 a 14 dias. No congelador, o período de conservação é de 1 a 2 meses. Caso apresente mofo, o produto deve ser descartado inteiro.",
  "fontes": ["laticinios.md"],
  "aviso": null
}

// sem base no corpus (ex.: "Ração para cachorro")
{ "orientacao": null, "fontes": [], "aviso": "Sem orientação no acervo para este alimento." }
```

---

## 2. Como funciona

![Arquitetura da IA](docs/arquitetura.png)

**Na subida da API** (profile `gemini`, o padrão):

1. O `CorpusLoader` lê os 5 arquivos de `src/main/resources/corpus/` (um por categoria do banco).
2. Cada seção `##` vira um **trecho** (41 no total), prefixado com o título do arquivo, por exemplo
   `Laticínios - Iogurte e bebida láctea`. Cada trecho recebe os metadados `categoriaId` e `arquivo`.
3. O Spring AI envia os trechos ao modelo `gemini-embedding-001`, que devolve um vetor para cada um.
4. Os vetores ficam num `SimpleVectorStore`, um índice em memória. O log confirma:
   `Corpus indexado: 5 arquivos, 41 trechos`.

**A cada pedido** (`POST /api/ia/orientacoes`):

1. O `IaController` aceita só DOADOR e valida o corpo. O `IaService` busca a categoria (404 se não existir)
   e chama a IA com limite de 20 s.
2. O `OrientacaoServiceGemini` monta a consulta com título + descrição e faz a **busca vetorial**: no
   máximo 2 trechos (top-K 2), com similaridade mínima de 0,6 e **só da categoria da doação**.
3. **Nenhum trecho passou?** A API devolve o aviso **sem chamar o modelo de chat**.
4. **Achou trechos?** Uma única chamada ao `gemini-3.5-flash` (temperatura 0,2), com um prompt que manda
   usar **somente** os trechos, responder em até 3 frases e devolver `SEM_ORIENTACAO` se os trechos não
   falarem daquele alimento.
5. A resposta vira `{ orientacao, fontes, aviso }`. Erro, cota ou mais de 20 s viram **503
   `IA_INDISPONIVEL`**, e o resto da API continua funcionando.

**Modos de execução:** a IA fica atrás da interface `OrientacaoService`, com duas implementações.

| Modo | Como ativar | Implementação |
|---|---|---|
| gemini (padrão) | nada além da `GEMINI_API_KEY`: o `application.properties` tem `spring.profiles.default=gemini` | `CorpusLoader` + `OrientacaoServiceGemini` (RAG real) |
| fake | `SPRING_PROFILES_ACTIVE=fake` | `OrientacaoServiceFake`: resposta fixa, sem chave, para o app e a API rodarem sem o Gemini |

---

## 3. Por que RAG: justificativas técnicas

**Por que RAG, e não perguntar direto ao modelo:** conservação de alimento é assunto de saúde. Sozinho, um
LLM pode inventar prazos e temperaturas com toda a convicção. Com RAG, cada resposta se apoia em trechos
de fonte conhecida, a API mostra de qual arquivo veio e, sem base, avisa em vez de inventar.

| Alternativa | Decisão | Motivo |
|---|---|---|
| **RAG básico** | **escolhida** | ancora a resposta em fontes oficiais, permite citar a fonte e não exige dados rotulados |
| Machine Learning | não | não existe histórico real de doações para treinar um modelo |
| Visão computacional | não | exigiria um dataset de fotos de alimentos rotuladas por estado de conservação |
| Fine-tuning do modelo | não | caro, exige muitos exemplos e não garante fidelidade às fontes; trocar o corpus é mais simples que retreinar |

**Decisões de implementação:**

| Decisão | Por quê |
|---|---|
| Corpus escrito pela equipe a partir das fontes, em vez de indexar PDFs | elimina a extração e limpeza de texto de PDF, mantém o corpus pequeno e cada afirmação ligada a uma fonte (`[F1]`, `[F2]`...) |
| Um trecho por seção `##`, sem divisor por tokens | cada seção é a regra completa de um alimento; cortar no meio misturaria alimentos |
| Filtro por `categoriaId` na busca | a categoria vem da doação; filtrar evita devolver a regra de leite para um bolo |
| `SimpleVectorStore` em memória | são 41 trechos pequenos; reindexar na subida custa segundos e dispensa um banco vetorial |
| Não chamar o chat quando a busca não acha nada | economiza cota, deixa a recusa rápida e impede o modelo de responder sem base |
| Temperatura 0,2 | a tarefa é resumir trechos, não criar: respostas estáveis e fiéis |
| `thinking-level=LOW` no Gemini 3 | o nível padrão (alto) passava dos 20 s; resumir dois trechos prontos não precisa disso |
| 503 quando o Gemini falha, sem cair no fake | o fake é um texto de teste; mostrá-lo como orientação real seria informação errada |
| Top-K e similaridade mínima em propriedades | dá para calibrar com a avaliação sem recompilar |

---

## 4. Modelos e tecnologias

| Item | Escolha | Por quê |
|---|---|---|
| Modelo generativo | **Gemini 3.5 Flash** (`gemini-3.5-flash`) via API do Google AI Studio | rápido, barato, com cota gratuita; o grupo já tinha experiência com a API |
| Embeddings | **`gemini-embedding-001`** | mesmo provedor e mesma chave; o padrão antigo (`text-embedding-004`) foi desligado pelo Google em 14/01/2026 |
| Integração | **Spring AI 2.0.2** (`spring-ai-starter-model-google-genai` e `...-embedding`) | starter oficial; trocar de modelo ou provedor é configuração, não código |
| Vector store | `SimpleVectorStore` (`spring-ai-vector-store`) | em memória, sem infraestrutura extra |
| Aplicação | Java 21, Spring Boot 4.1, Oracle, Flyway | a API do SobraBem, onde a IA está integrada |

**Alternativa considerada:** modelo local com Ollama. Não há custo e nenhum dado sai da máquina, mas exige
mais hardware de quem roda o projeto. Com o Spring AI, a troca seria de configuração.

---

## 5. Dados: fontes e preparação

**Fontes:** `src/main/resources/corpus/FONTES.md` lista as 9 fontes com link e data de acesso: ANVISA
(RDC nº 216/2004), USDA FSIS, FoodSafety.gov, Nebraska Extension (baseada no FoodKeeper do USDA), Ohio State
University Extension, Tetra Pak e Itatiaia.

**Corpus:** 5 arquivos Markdown, um por categoria do banco, com 41 seções.

| categoriaId | Categoria | Arquivo | Seções | Exemplos |
|---|---|---|---|---|
| 1 | Pães e bolos | `paes-bolos.md` | 5 | pão francês, pão de forma, bolos, biscoitos caseiros |
| 2 | Frutas, legumes e verduras | `hortifruti.md` | 12 | maçã, banana, tomate, folhas, cenoura |
| 3 | Laticínios | `laticinios.md` | 7 | leite UHT, iogurte, queijos, requeijão |
| 4 | Refeições prontas | `refeicoes.md` | 9 | carnes cozidas, sopas, saladas com maionese |
| 5 | Mercearia | `mercearia.md` | 8 | arroz, feijão, farinha, enlatados |

**Preparação:**

1. Coleta das fontes, com link e data de acesso registrados em `FONTES.md`.
2. Escrita dos arquivos: um título `#` com a categoria, uma seção `##` por alimento (ou por regra geral da
   categoria), e cada afirmação terminando com o código da fonte:
```markdown
   ## Iogurte e bebida láctea
   - Na geladeira, de 7 a 14 dias [F6].
   - Iogurte com mofo deve ser descartado inteiro [F4].
```
3. Conversão de unidades americanas (°F, polegadas) para as brasileiras, registrada em `FONTES.md`.
4. Regra de segurança nos arquivos de produtos embalados: os prazos nunca passam a validade impressa na
   embalagem.
5. Revisão de cada arquivo por um segundo integrante.
6. Na subida da API, o `CorpusLoader` divide, gera os embeddings e indexa. `FONTES.md` não é indexado: ele
   serve para a revisão humana.

---

## 6. Como executar

**Pré-requisitos:** Java 21 e Maven 3.9+ (ou o `./mvnw` do projeto), Docker (para o RabbitMQ), acesso a
um schema Oracle e uma chave do **Google AI Studio** (https://aistudio.google.com).

**1. Variáveis de ambiente**

```bash
cp .env.example .env
```

Preencha no `.env`: `DB_URL`, `DB_USER`, `DB_PASSWORD`, `JWT_SECRET` (32 caracteres ou mais),
`ADMIN_EMAIL`, `ADMIN_SENHA` e **`GEMINI_API_KEY`**. O `ADDRESS_RABBIT` já vem preenchido para o RabbitMQ
do `docker compose` (`amqp://sobrabem:sobrabem@localhost:5672`). O `.env` está no `.gitignore`.

**2. RabbitMQ**

```bash
docker compose up -d
```

**3. API com a IA real**

O profile `gemini` é o padrão do projeto (`spring.profiles.default=gemini`), então basta a chave no
`.env`:

```bash
./mvnw spring-boot:run
```

Confira no log: `Corpus indexado: 5 arquivos, 41 trechos`.

**4. Testar**

No Swagger (http://localhost:8080/swagger-ui.html):

1. Cadastre um doador em `POST /api/auth/registro`:
```json
   {
     "nome": "Padaria Pão Quente", "email": "contato@paoquente.com.br", "senha": "Senha123",
     "telefone": "11987654321", "papel": "DOADOR",
     "endereco": "Avenida Paulista, 1000 - Bela Vista", "cidade": "São Paulo", "uf": "SP"
   }
```
2. Faça login em `POST /api/auth/login`, copie o `token` e cole em **Authorize**.
3. Chame `POST /api/ia/orientacoes` com `{ "titulo": "Iogurte natural", "descricao": "potes de 170 g", "categoriaId": 3 }`.

**Sem a chave:** ative outro profile, que tira o padrão `gemini` da frente. O profile **não** é lido do
`.env`: precisa ser uma variável de ambiente de verdade ou a configuração da IDE.

```bash
SPRING_PROFILES_ACTIVE=fake ./mvnw spring-boot:run
# no IntelliJ: Run > Edit Configurations > Active profiles = fake
```

A API funciona no modo fake e a orientação vem com `[FAKE]` e `fontes: ["orientacao-fake.md"]`. Não use
`IA_MODO=gemini` com outro profile: sem o profile `gemini`, o Spring AI fica desligado e a subida falha
(`No qualifying bean of type EmbeddingModel`).

**Problemas comuns na subida:**

| Mensagem | Causa e solução |
|---|---|
| `Could not resolve placeholder 'GEMINI_API_KEY'` | a variável não existe. Defina a chave no `.env` ou suba com `SPRING_PROFILES_ACTIVE=fake` |
| `Google GenAI project-id must be set` | `GEMINI_API_KEY` vazia: sem chave, o cliente tenta o modo Vertex AI. Preencha a chave |
| erro do Gemini durante a subida (ex.: `API key not valid`, `429`) | chave inválida, cota esgotada ou Gemini fora do ar: os embeddings do corpus são gerados na subida, e a falha impede a API de subir. Corrija a chave ou espere a cota renovar |
| `404 ... is no longer available to new users` | o modelo de chat não está liberado para a sua chave. Troque `spring.ai.google.genai.chat.model` em `application-gemini.properties` por um Flash atual |
| `JWT_SECRET precisa ter pelo menos 32 caracteres` | segredo ausente ou curto |
| `Connection refused` (RabbitMQ) | o container do RabbitMQ não está rodando |
| `ACCESS_REFUSED` (RabbitMQ) | `ADDRESS_RABBIT` vazio ou com usuário errado. Use o valor do `.env.example` |
---

## 7. Avaliação

**Relatório completo:** [`avaliacao/RELATORIO.md`](avaliacao/RELATORIO.md).

A avaliação usa **13 casos executados na API real**, com a IA ligada:

| Grupo | Casos | Passa quando |
|---|---|---|
| Alimentos cobertos pelo corpus | 7, das 5 categorias (pão francês, banana, morango, iogurte, leite de caixinha, marmita de frango, arroz) | a orientação vem com a fonte esperada e sem nada fora do corpus |
| Alimentos fora do corpus | 3 (ração para cachorro, ovos, peixe cru) | a API devolve o aviso, sem inventar orientação |
| Situações de falha | 3 (instrução escondida no título, categoria inexistente, título vazio) | resposta segura ou o erro esperado (404, 400) |

Metas: 6 de 7 cobertos, 3 de 3 fora do corpus, 3 de 3 falhas e tempo médio abaixo de 3 s. Os testes
unitários (`OrientacaoServiceGeminiTest` e `IaServiceTest`) complementam a avaliação, garantindo a lógica
do RAG com o Gemini simulado.

### Como repetir a avaliação

1. Suba a API com a IA real (seção 6) e confira no log `Corpus indexado: 5 arquivos, 41 trechos`.
2. Abra o Swagger em http://localhost:8080/swagger-ui.html, cadastre um DOADOR em
   `POST /api/auth/registro`, faça login e cole o `token` em **Authorize**.
3. Para cada caso da tabela do relatório, chame `POST /api/ia/orientacoes`, por exemplo:
```json
   { "titulo": "Iogurte de morango", "descricao": "potes de 170 g", "categoriaId": 3 }
```
4. Compare a resposta com a coluna "Esperado" do relatório. Nos alimentos cobertos, confira o texto contra
   a seção indicada no arquivo de `src/main/resources/corpus/`.

---

## 8. Uso responsável

- **Credenciais:** a `GEMINI_API_KEY` fica só no `.env` (fora do Git) ou nas variáveis do servidor. O
  `.env.example` traz os nomes, sem valores. O app nunca fala com o Gemini direto: só a API tem a chave.
- **Dados pessoais:** para o Gemini vão **só** o título e a descrição do alimento, o nome da categoria e
  os trechos do corpus. Nenhum dado de usuário (nome, e-mail, telefone, endereço) entra no prompt.
- **Termos do Gemini:** usamos a cota gratuita da API. Pelos
  [termos adicionais da Gemini API](https://ai.google.dev/terms) (vigentes desde 23/03/2026), no plano
  gratuito o Google usa o conteúdo enviado e as respostas para melhorar seus produtos, revisores humanos
  podem ler as entradas e saídas, e os termos pedem para não enviar informações sensíveis, confidenciais ou
  pessoais. Por isso o prompt contém só dados do alimento. No plano pago, o conteúdo não é usado para
  melhorar os produtos.
- **Humano no controle:** a orientação é uma **sugestão**. O doador revisa e pode editar antes de publicar,
  e a doação guarda o campo `geradaPorIa`.
- **Transparência:** o campo `fontes` mostra de qual arquivo do corpus veio a orientação, e o app exibe
  "Orientação gerada por IA com base em: … Na dúvida, não consuma."
- **Injeção de instrução:** o prompt avisa que o nome do alimento foi digitado por um usuário e deve ser
  tratado só como dado. Isso é testado no caso 11 da avaliação.

---

## 9. Limitações

- **Não avalia o estado real do alimento.** A IA orienta sobre conservação; não vê se o alimento está
  estragado. Na dúvida, não consuma.
- **Cobre só as 5 categorias** e os alimentos escritos no corpus. Categorias novas criadas pelo ADMIN
  recebem o aviso até alguém escrever o arquivo correspondente.
- **Parte das fontes é dos EUA** (USDA, FoodSafety.gov, extensões universitárias). Prazos e condições de
  armazenamento no Brasil (clima mais quente, por exemplo) podem ser diferentes; a fonte brasileira usada
  é a ANVISA.
- **O corpus reflete as fontes na data de acesso** (15/11/2026) e depende da fidelidade da equipe ao
  escrevê-lo; por isso a revisão por um segundo integrante.
- **Depende de serviço externo e de cota:** sem internet, com a cota esgotada ou com o Gemini fora do ar, a
  funcionalidade responde 503 (o resto do sistema continua).
- **O texto varia entre execuções**, mesmo com temperatura baixa.
- **Limiar de similaridade fixo:** um valor alto recusa alimentos que o corpus cobre; um valor baixo traz
  trechos pouco relacionados. Ele foi calibrado com a avaliação (ver o relatório).
- **Índice refeito a cada subida:** gera chamadas de embedding toda vez que a API reinicia. Se a chave
  for inválida ou a cota tiver acabado nesse momento, a API não sobe no profile `gemini`; nesse caso, ela
  continua disponível com `SPRING_PROFILES_ACTIVE=fake`. Depois de no ar, falhas do Gemini afetam só o
  endpoint de orientação (503).

---

## 10. Estrutura do repositório

```text
src/main/java/br/com/sobrabem/ai
├── OrientacaoService.java          interface usada pela API
├── OrientacaoServiceGemini.java    RAG: busca no índice + chamada ao Gemini
├── OrientacaoServiceFake.java      resposta fixa, sem chave (SPRING_PROFILES_ACTIVE=fake)
├── CorpusLoader.java               lê o corpus, gera embeddings e monta o índice
├── OrientacaoResultado.java
└── IaIndisponivelException.java    vira 503 IA_INDISPONIVEL
src/main/java/br/com/sobrabem/service/IaService.java      busca a categoria, limite de 20 s, falha → 503
src/main/java/br/com/sobrabem/controller/IaController.java POST /api/ia/orientacoes
src/main/resources/corpus/          os 5 arquivos do corpus + FONTES.md
src/main/resources/application-gemini.properties          modelos, top-K e similaridade (profile gemini, o padrão)
src/test/java/br/com/sobrabem/ai/OrientacaoServiceGeminiTest.java   testes unitários do RAG
avaliacao/RELATORIO.md             casos testados, resultados, interpretação e limitações
docs/arquitetura.png                diagrama da arquitetura da IA
```

O restante do código é a API completa do SobraBem (usuários, doações, reservas, mensageria), documentada
no Swagger em `/swagger-ui.html`.

---

## 11. Declaração de uso de IA no desenvolvimento

Conforme as regras da Global Solution, declaramos as ferramentas de IA generativa usadas no
desenvolvimento:

| Ferramenta | Uso |
|---|---|
| Claude (Anthropic) | apoio no planejamento da arquitetura e da avaliação, revisão da documentação, explicação de erros e revisão de código |

Todo o código e o conteúdo do corpus foram revisados e compreendidos pelos integrantes do grupo.