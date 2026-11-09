# Integrações externas: Steam, IGDB e IsThereAnyDeal

Guia para ligar os dados de jogos (preço, histórico de preço, jogadores ativos e ficha do jogo) na GearHub API e criar a aba nova no site.

> Levantado em 09/11/2026. Os itens marcados com ⚠️ não puderam ser conferidos na documentação oficial e devem ser testados com uma chamada real antes de virar código.

---

## 1. Visão geral

| Dado | Fonte | Chave | Observação |
|---|---|---|---|
| Preço atual, desconto, descrição, imagens | Loja da Steam (`appdetails`) | Não | Endpoint não documentado oficialmente, mas usado há anos |
| Jogadores online agora | Steam Web API (`GetNumberOfCurrentPlayers`) | Não | Só o número do momento, sem histórico |
| Avaliações (positivas/negativas) | Loja da Steam (`appreviews`) | Não | |
| Histórico de preço (inclusive passado) | IsThereAnyDeal | Sim, grátis | Única fonte livre com histórico completo |
| Ficha do jogo (gênero, capa, nota, lançamento, estúdio) | IGDB | Sim, grátis (via Twitch) | 4 req/s |
| Lista completa de jogos da Steam | Steam Web API (`IStoreService/GetAppList`) | Sim, grátis | Opcional, só se quiser buscar entre todos os jogos |

### Por que o SteamDB não entra

O [FAQ do SteamDB](https://steamdb.info/faq/) diz que não existe API pública e que "We also do not allow scraping/crawling on SteamDB". Quem acessa de forma automática pode ser banido. Eles orientam a pegar os dados direto da Steam, que é o que este guia faz.

### O que não existe em lugar nenhum

- **Histórico de jogadores:** a Steam só informa o número de agora. A API precisa salvar esse número de hora em hora; o gráfico começa no dia em que a coleta for ligada.
- **Vendas:** a Steam não divulga. Só há estimativas (SteamSpy), muito imprecisas. A aba deve mostrar preço, descontos e jogadores, que são dados reais.

### Regra de ouro

**Toda chamada externa sai da API Java, nunca do navegador.** O Client Secret e as chaves não podem aparecer no front, e o IGDB recusa chamadas vindas do navegador (CORS). O Nuxt só conversa com `/api/v1/...`.

---

## 2. Pegando as credenciais

### 2.1 IGDB (via Twitch)

1. Crie uma conta em [twitch.tv](https://www.twitch.tv) e **ative a verificação em duas etapas** (sem isso a Twitch não deixa registrar app).
2. Acesse [dev.twitch.tv/console/apps](https://dev.twitch.tv/console/apps) → **Register Your Application**:
   - **Name:** `GearHub` (precisa ser único na Twitch; se der conflito, use `GearHub-API`)
   - **OAuth Redirect URLs:** `http://localhost`
   - **Category:** Application Integration
   - **Client Type:** Confidential
3. Abra o app criado → copie o **Client ID** → clique em **New Secret** e copie o **Client Secret** (ele só aparece uma vez).

### 2.2 IsThereAnyDeal

1. Crie uma conta em [isthereanydeal.com](https://isthereanydeal.com).
2. Registre um app na área de desenvolvedor (link no rodapé ou em [docs.isthereanydeal.com](https://docs.isthereanydeal.com/)) e copie a **API Key**.

### 2.3 Steam Web API (opcional)

1. Entre com sua conta Steam em [steamcommunity.com/dev/apikey](https://steamcommunity.com/dev/apikey).
2. Domínio: `localhost` → copie a chave.
3. Só é necessária para a lista completa de jogos. Preço e jogadores funcionam sem ela.

---

## 3. Onde colocar as chaves

Mesmo padrão do Discord e do Resend: as chaves ficam no `.env` (fora do Git) e o `application.yml` lê de lá.

### 3.1 `.env` (e o mesmo bloco, sem valores, no `.env.example`)

```env
# IGDB (Twitch)
IGDB_CLIENT_ID=
IGDB_CLIENT_SECRET=

# IsThereAnyDeal
ITAD_API_KEY=

# Steam Web API (opcional)
STEAM_API_KEY=
```

### 3.2 `src/main/resources/application.yml`, dentro de `gearhub:`

```yaml
  igdb:
    client-id: ${IGDB_CLIENT_ID:}
    client-secret: ${IGDB_CLIENT_SECRET:}
    token-uri: https://id.twitch.tv/oauth2/token
    api-uri: https://api.igdb.com/v4
  itad:
    api-key: ${ITAD_API_KEY:}
    api-uri: https://api.isthereanydeal.com
  steam:
    api-key: ${STEAM_API_KEY:}
    store-uri: https://store.steampowered.com
    api-uri: https://api.steampowered.com
    country: br
    language: portuguese
```

### 3.3 Classe de propriedades (modelo, igual a `DiscordProperties`)

```java
@ConfigurationProperties("gearhub.igdb")
public record IgdbProperties(String clientId, String clientSecret, String tokenUri, String apiUri) {

    public boolean configured() {
        return clientId != null && !clientId.isBlank() && clientSecret != null && !clientSecret.isBlank();
    }
}
```

Crie `ItadProperties` e `SteamProperties` do mesmo jeito. Quando a chave estiver vazia, `configured()` devolve `false` e a parte correspondente fica desligada sem derrubar a API (mesmo comportamento do login com Discord).

---

## 4. Testando cada API antes de programar

Rode estes comandos no Git Bash. Se todos responderem, as credenciais estão certas.

### 4.1 Steam: preço atual (sem chave)

```bash
curl "https://store.steampowered.com/api/appdetails?appids=730&cc=br&l=portuguese"
```

Resposta (resumida). Os valores vêm **em centavos**:

```json
{ "730": { "success": true, "data": {
  "name": "Counter-Strike 2",
  "is_free": true,
  "header_image": "https://...",
  "price_overview": { "currency": "BRL", "initial": 9999, "final": 4999, "discount_percent": 50 }
}}}
```

- Jogo gratuito não tem `price_overview`.
- Mande **um appid por chamada** (pedir vários juntos com `price_overview` costuma falhar).
- Limite não oficial: cerca de 200 chamadas a cada 5 minutos. Passou disso, a Steam devolve 429 por um tempo.

### 4.2 Steam: jogadores online agora (sem chave)

```bash
curl "https://api.steampowered.com/ISteamUserStats/GetNumberOfCurrentPlayers/v1/?appid=730"
```

```json
{ "response": { "player_count": 812345, "result": 1 } }
```

### 4.3 Steam: avaliações (sem chave)

```bash
curl "https://store.steampowered.com/appreviews/730?json=1&language=all&purchase_type=all&num_per_page=0"
```

Use `query_summary.total_positive`, `total_negative` e `review_score_desc`.

### 4.4 IGDB: token e primeira consulta

Token (vale cerca de 60 dias; `expires_in` vem em segundos):

```bash
curl -X POST "https://id.twitch.tv/oauth2/token?client_id=SEU_ID&client_secret=SEU_SECRET&grant_type=client_credentials"
```

```json
{ "access_token": "abc123...", "expires_in": 5184000, "token_type": "bearer" }
```

Consulta. É sempre **POST**, e o corpo usa a linguagem Apicalypse:

```bash
curl -X POST "https://api.igdb.com/v4/games" \
  -H "Client-ID: SEU_ID" \
  -H "Authorization: Bearer SEU_TOKEN" \
  -d 'search "elden ring"; fields name,slug,cover.image_id,genres.name,first_release_date,total_rating,involved_companies.company.name; limit 5;'
```

Capa: monte a URL com o `image_id`:

```
https://images.igdb.com/igdb/image/upload/t_cover_big/IMAGE_ID.jpg
```

Ligação IGDB ↔ Steam (pegar o appid da Steam de um jogo do IGDB):

```bash
curl -X POST "https://api.igdb.com/v4/external_games" \
  -H "Client-ID: SEU_ID" -H "Authorization: Bearer SEU_TOKEN" \
  -d 'fields uid,game,name,external_game_source; where game = 119133; limit 20;'
```

⚠️ O IGDB trocou o campo que diz de qual loja é o id: era `category` (Steam = 1) e passou a ser `external_game_source`. Rode a consulta acima, veja qual campo vem preenchido e qual número representa a Steam, e só então fixe o filtro no código (por exemplo `where external_game_source = 1 & uid = "730";`).

Regras do IGDB:
- **4 requisições por segundo** e no máximo 4 simultâneas. Acima disso volta 429.
- Não há cabeçalho que informe o limite: controle do nosso lado.
- Guarde a ficha do jogo no banco e só atualize de tempos em tempos (por exemplo, uma vez por semana).

### 4.5 IsThereAnyDeal: achar o jogo e pegar o histórico

⚠️ Confira os nomes exatos na especificação OpenAPI em [docs.isthereanydeal.com](https://docs.isthereanydeal.com/) antes de programar. A chave vai como parâmetro `key` ou no cabeçalho `ITAD-API-Key`.

1. Converter o appid da Steam no id da ITAD (procure na spec o endpoint de *lookup* por `appid`/`shop`).
2. Histórico de preço do jogo (procure o endpoint `games/history`), com `country=BR`.
3. Menor preço histórico (procure o endpoint de *historical low* ou *overview*).

Teste de autenticação (troque pelo endpoint de lookup que estiver na spec):

```bash
curl -H "ITAD-API-Key: SUA_CHAVE" "https://api.isthereanydeal.com/games/lookup/v1?appid=730"
```

Alguns endpoints da ITAD estão marcados como *unstable* na documentação. Funcionam, mas podem mudar: confira o status de cada um que for usar.

---

## 5. Como montar na API Java

### 5.1 Pacote e classes

Crie o pacote `com.gearhub.market` (ou o nome da aba):

| Classe | Papel |
|---|---|
| `IgdbProperties`, `ItadProperties`, `SteamProperties` | Lêem o `application.yml` |
| `IgdbClient` | Pede e guarda o token (renova quando faltar menos de 1 dia), faz as consultas e respeita 4 req/s |
| `SteamClient` | `appdetails`, `GetNumberOfCurrentPlayers`, `appreviews` |
| `ItadClient` | Lookup por appid, histórico e menor preço |
| `MarketService` | Junta tudo e lê/grava no banco |
| `MarketCollector` | Tarefa agendada que salva preço e jogadores |
| `MarketController` | Endpoints públicos para o site |

Use `RestClient` (o mesmo de `DiscordAuthService`). Para o token do IGDB, guarde `token` e `expiraEm` em memória dentro do `IgdbClient`, com `synchronized` na renovação.

### 5.2 Tabelas (próxima migração livre, hoje `V23__market.sql`)

```sql
-- Jogos acompanhados e a ficha que veio do IGDB
CREATE TABLE market_games (
    steam_appid     integer PRIMARY KEY,
    igdb_id         bigint UNIQUE,
    itad_id         text UNIQUE,
    name            text NOT NULL,
    cover_url       text,
    genres          text[] NOT NULL DEFAULT '{}',
    released_at     date,
    rating          numeric(5, 2),
    is_free         boolean NOT NULL DEFAULT false,
    tracked_since   timestamptz NOT NULL DEFAULT now(),
    details_updated_at timestamptz
);

-- Um registro por coleta: preço em centavos
CREATE TABLE market_price_snapshots (
    steam_appid      integer NOT NULL REFERENCES market_games ON DELETE CASCADE,
    taken_at         timestamptz NOT NULL,
    currency         text NOT NULL,
    initial_cents    integer NOT NULL,
    final_cents      integer NOT NULL,
    discount_percent integer NOT NULL,
    source           text NOT NULL, -- 'steam' (coleta nossa) ou 'itad' (histórico importado)
    PRIMARY KEY (steam_appid, taken_at, source)
);

-- Jogadores online, uma linha por hora
CREATE TABLE market_player_snapshots (
    steam_appid  integer NOT NULL REFERENCES market_games ON DELETE CASCADE,
    taken_at     timestamptz NOT NULL,
    players      integer NOT NULL,
    PRIMARY KEY (steam_appid, taken_at)
);
```

### 5.3 Coleta agendada

- Ligue o agendamento com `@EnableScheduling` numa classe `@Configuration`.
- `@Scheduled(cron = "0 0 * * * *")`: a cada hora, para cada jogo acompanhado, salve jogadores e preço.
- Espace as chamadas (por exemplo 1 por segundo) para não estourar o limite da Steam.
- Preço quase nunca muda de hora em hora: grave uma linha nova **só quando mudar**, e o gráfico fica em degraus.
- Uma vez por dia: atualize a ficha do IGDB dos jogos com `details_updated_at` antigo.
- Ao adicionar um jogo novo: importe o histórico de preço da ITAD uma vez (`source = 'itad'`).
- Não deixe uma falha externa derrubar a coleta inteira: `try/catch` por jogo, com log.

### 5.4 Quais jogos acompanhar

Salvar mais de 100 mil jogos de hora em hora é inviável. Comece com:
1. Os jogos que já existem no catálogo do GearHub (tabela de jogos do perfil).
2. Os mais jogados da Steam (top 100).
3. Qualquer jogo que alguém abrir na aba: passa a ser acompanhado dali em diante.

### 5.5 Endpoints para o site

| Método | Rota | Retorna |
|---|---|---|
| GET | `/api/v1/market/games?sort=players\|discount\|name&page=` | Lista com nome, capa, preço atual, desconto, jogadores agora |
| GET | `/api/v1/market/games/{appid}` | Ficha completa + preço atual + menor preço histórico + avaliações |
| GET | `/api/v1/market/games/{appid}/history?range=24h\|7d\|30d\|all` | Séries de preço e de jogadores para os gráficos |
| GET | `/api/v1/market/search?q=` | Busca (no banco e, se não achar, no IGDB) |

Libere as rotas `GET /api/v1/market/**` como `permitAll()` no `SecurityConfig`. Lembre que lá tudo começa fechado.

### 5.6 Testes

Siga os testes de integração que já existem (`ApiBaseIntegrationTest`). **Não chame as APIs reais nos testes:** simule as respostas, por exemplo com o `MockRestServiceServer` do Spring, ou deixe os clientes atrás de uma interface e use uma versão falsa no teste.

---

## 6. Como montar no site (Nuxt)

1. **Página da lista:** `app/pages/mercado/index.vue`, com cards de jogo (capa, preço, desconto, jogadores agora) e filtros por mais jogados, maiores descontos e nome.
2. **Página do jogo:** `app/pages/mercado/[appid].vue`, com:
   - cabeçalho: capa, nome, gêneros, nota, lançamento;
   - preço atual, desconto e menor preço histórico;
   - gráfico de preço (linha em degraus);
   - gráfico de jogadores (24h / 7d / 30d / tudo);
   - avaliações.
3. **Navbar:** item novo em `AppNavbar.vue` e `MobileMenu.vue`.
4. **Gráficos:** o projeto ainda não tem biblioteca de gráfico de linha (o `RadarChart.vue` é próprio). Opções: `chart.js` + `vue-chartjs` (simples) ou `echarts` + `vue-echarts` (mais recursos, como zoom e intervalo de datas).
5. **Valores:** o preço vem em centavos. Formate com `(cents / 100).toLocaleString('pt-BR', { style: 'currency', currency: 'BRL' })`.
6. As chamadas usam `useFetch('/api/v1/market/...')`, igual às outras páginas. Nenhuma chave no front.

---

## 7. Checklist

- [ ] Conta Twitch com 2FA e app registrado → `IGDB_CLIENT_ID` e `IGDB_CLIENT_SECRET` no `.env`
- [ ] Conta e app na IsThereAnyDeal → `ITAD_API_KEY` no `.env`
- [ ] (Opcional) Chave da Steam → `STEAM_API_KEY` no `.env`
- [ ] Mesmas variáveis, sem valor, no `.env.example`
- [ ] Todos os `curl` da seção 4 respondendo
- [ ] Confirmado o campo/número da Steam em `external_games` do IGDB ⚠️
- [ ] Confirmados os endpoints de lookup, histórico e menor preço da ITAD ⚠️
- [ ] `application.yml` + classes `*Properties`
- [ ] Migração `V23__market.sql`
- [ ] Clientes `SteamClient`, `IgdbClient`, `ItadClient`
- [ ] Coleta agendada + importação do histórico da ITAD
- [ ] `MarketController` + rotas liberadas no `SecurityConfig`
- [ ] Testes com respostas simuladas
- [ ] Páginas `mercado/index.vue` e `mercado/[appid].vue` + item na navbar
- [ ] Biblioteca de gráficos instalada

---

## Fontes

- [SteamDB: FAQ (sem API pública, proibido scraping)](https://steamdb.info/faq/)
- [IGDB: documentação da API](https://api-docs.igdb.com/#getting-started)
- [Twitch: console de desenvolvedor](https://dev.twitch.tv/console/apps)
- [IsThereAnyDeal: documentação e OpenAPI](https://docs.isthereanydeal.com/)
- [Steam Web API: chave](https://steamcommunity.com/dev/apikey)
- [igdb-atlas (fluxo de token e limite de 4 req/s)](https://docs.rs/crate/igdb-atlas/latest/source/README.md)
- [Fórum da Twitch: limite do IGDB](https://discuss.dev.twitch.com/t/response-headers-oauth-access-tokens-getting-rate-limited/46963)
