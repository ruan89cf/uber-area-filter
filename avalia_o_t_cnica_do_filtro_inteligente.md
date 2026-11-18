# Avaliação técnica do Filtro Inteligente

## Resumo executivo

O **Filtro Inteligente** (`com.ruan.uberareafilter`) é um assistente operacional para motoristas de aplicativo (Uber e 99) construído em Kotlin para Android, com foco em duas proteções vitais no trânsito: **evitar áreas de risco físico** e **impedir corridas financeiramente deficitárias** (baixo ganho líquido por hora/quilômetro ou deslocamentos que geram retorno vazio).

A arquitetura atual já possui fundações sólidas — leitura de tela via `AccessibilityService`, avaliação geográfica por polígonos e lista de vias (`AreaEvaluator`), cálculo de custos operacionais (`FuelCalculator`), coordenação entre aplicativos (`DualRideCoordinator`), sobreposição visual em tempo real (`OverlayManager`) e automação de toque via ADB/Shizuku (`StandaloneTapDaemon`, `ShizukuTapBridge`).

Contudo, a auditoria técnica confirma **vulnerabilidades críticas de lógica geográfica, latência de rede, persistência de estado em aparelhos Motorola e exposição de segredos de assinatura**, além de oportunidades importantes de evolução funcional para que o aplicativo opere estritamente a favor do motorista — e nunca a favor das métricas de retenção ou liquidez das plataformas.

**Principais conclusões:**
1. **Risco físico imediato (Prioridade P0):** A precedência atual entre nome de rua e coordenada GPS permite aprovar corridas em trechos perigosos de vias extensas (como ruas que nascem em bairros seguros e entram em comunidades), além de aceitar palpites secundários fracos do `Geocoder` e falhar na delimitação da Av. Brasil/Maré.
2. **Risco operacional e financeiro (Prioridade P1):** Consultas sequenciais de geocodificação sem tempo limite rígido fazem o app perder ofertas boas em redes 4G/5G instáveis; a trava entre Uber e 99 reside apenas em memória e pode ser apagada pelo gerenciamento de bateria da Motorola; e o cálculo de lucro atual ignora o custo de tempo e combustível do **retorno vazio** (*deadhead*) quando o destino sai da área segura.
3. **Segurança e privacidade (Prioridade P0/P1):** A chave de assinatura `app/release-key.jks` está versionada dentro do projeto, o modo diagnóstico vem ativado por padrão capturando dados pessoais de passageiros (nomes e endereços) e não há regras explícitas bloqueando o backup automático dessas capturas na nuvem.

---

## Escopo e método

Esta avaliação analisa o ecossistema completo do repositório `uber-area-filter`, cruzando:
* A estrutura de módulos, recursos, manifestos, testes e scripts de build observados no projeto;
* Os 15 pontos levantados no diagnóstico inicial de engenharia;
* As propostas técnicas derivadas da análise do código-fonte real (`AreaEvaluator.kt`, `StandaloneTapDaemon.kt`, `DualRideCoordinator.kt`, entre outros);
* Os princípios de engenharia defensiva aplicada à operação real de motoristas urbanos na Região Metropolitana do Rio de Janeiro.

**Critério de ponderação adotado:** Toda proposta técnica foi submetida a uma análise de custo-benefício real em campo. Soluções teoricamente sofisticadas, porém frágeis diante de dados incompletos no Brasil (como depender exclusivamente de interpolação numérica offline do OpenStreetMap em áreas periféricas), foram ajustadas para abordagens híbridas e deterministicamente seguras.

---

## Objetivos e critérios centrados no motorista

Em plataformas de mobilidade, os algoritmos de despacho são desenhados para maximizar a liquidez da rede, a taxa de aceitação e a cobertura geográfica da empresa — frequentemente empurrando corridas de baixa margem, com longos deslocamentos de busca ou destinos em zonas críticas, sob janelas de decisão curtas ($10\text{ s}$ a $15\text{ s}$).

Para que o **motorista seja o único beneficiário** do Filtro Inteligente, toda decisão automatizada deve obedecer a uma **hierarquia assimétrica de risco**:

1. **Integridade física acima de qualquer ganho financeiro:** O custo de um *falso negativo* de risco (aceitar por engano uma corrida que entra em área conflagrada) é catastrófico (risco à vida, roubo de veículo e perda da ferramenta de trabalho). Já o custo de um *falso positivo* (recusar uma corrida segura por dúvida geográfica) é marginal (aguardar alguns segundos pela próxima chamada). **Regra de ouro: na dúvida geográfica, recusa.**
2. **Lucro líquido real acima de faturamento bruto:** Faturamento bruto exibido no card da Uber ou 99 mascara combustível, desgaste, tempo parado, deslocamento até o embarque e retorno vazio de zonas sem demanda. O critério de aceite deve ser o **$\text{R\$/h}$ líquido real** e o **$\text{R\$/km}$ efetivo**.
3. **Velocidade de decisão compatível com o trânsito:** Uma decisão perfeita que leva $12\text{ s}$ equivale a uma recusa involuntária, pois a oferta expira. O ciclo completo (leitura $\rightarrow$ cálculo $\rightarrow$ decisão $\rightarrow$ toque/exibição) deve ocorrer idealmente em menos de $2{,}5\text{ s}$ a $3{,}0\text{ s}$.
4. **Soberania e privacidade dos dados do motorista:** Nenhuma informação sensível de operação ou dados de terceiros capturados em tela deve vazar para backups automáticos ou repositórios públicos.

---

## Arquitetura e funcionalidades observadas

O mapeamento do repositório revela uma arquitetura modular bem organizada em Kotlin/Android, dividida nas seguintes camadas:

| Camada / Pacote | Arquivos Identificados | Responsabilidade Arquitetural |
| :--- | :--- | :--- |
| **Serviço de Acessibilidade & Captura** | `service/UberAccessibilityService.kt`<br>`driver/DriverCaptureModules.kt`<br>`res/xml/accessibility_service_config.xml` | Monitora eventos de janela/tela dos apps Uber e 99, extrai nós de interface e gerencia módulos de captura diagnóstica. |
| **Motor de Decisão & Regras** | `engine/AreaEvaluator.kt`<br>`engine/RideOfferParser.kt`<br>`engine/TextNormalizer.kt`<br>`engine/FuelCalculator.kt`<br>`engine/DualRideCoordinator.kt` | Normaliza textos, extrai valores/endereços da oferta, avalia polígonos e vias, calcula lucro/combustível e evita aceite simultâneo entre Uber e 99. |
| **Mapas & Dados Geográficos** | `map/OpenFreeMapStreetFetcher.kt`<br>`assets/map_picker.html`<br>`assets/favorites_map.html`<br>`ui/MapAreaActivity.kt`<br>`ui/FavoritesActivity.kt` | Permite desenhar polígonos de atuação/exclusão, marcar favoritos e consultar vias via OpenFreeMap/Overpass. |
| **Automação de Toque (ADB / Shizuku)** | `adb/StandaloneAdbBridge.kt`<br>`adb/StandaloneTapDaemon.kt`<br>`adb/AdbPairingReceiver.kt`<br>`shizuku/ShizukuTapBridge.kt`<br>`shizuku/ShizukuTapUserService.kt`<br>`aidl/.../IShizukuTapService.aidl` | Executa toques automatizados de aceite sem depender exclusivamente de gestos lentos ou bloqueados pela UI das plataformas. |
| **Interface & Overlay** | `overlay/OverlayManager.kt`<br>`overlay/RideCardModel.kt`<br>`ui/MainActivity.kt`<br>`ui/WhatsNewActivity.kt`<br>`SectionTabsLayout.kt` | Exibe o card flutuante sobre a oferta com métricas calculadas e oferece telas de configuração, mapa e novidades. |
| **Persistência & Modelos** | `storage/ConfigRepository.kt`<br>`model/FilterConfig.kt`<br>`model/RideModels.kt` | Armazena polígonos, parâmetros financeiros, listas de bloqueio e registros de histórico. |
| **Qualidade & Build** | `test/.../CoreFeaturesTest.kt`<br>`app/build.gradle.kts`<br>`instalar_no_celular.bat`<br>`app/release-key.jks` | Contém testes unitários do núcleo, configuração de compilação/assinatura e script de instalação local. |

---

## Achados confirmados no código

A análise técnica confirma os seguintes problemas estruturais e lógicos na base atual:

### 1. Precedência invertida: nome da rua atropela coordenada GPS (`AreaEvaluator.kt`)
* **Evidência:** Após o `Geocoder` converter o endereço com número em coordenadas e constatar que o ponto está fora do polígono seguro, o fluxo verifica `if (matchedStreet != null)` e retorna `ZoneCheckResult(ok = true, ...)`.
* **Impacto real:** Vias longas do Rio de Janeiro (ex.: Rua Uranos, Estrada Intendente Magalhães, Av. Pastor Martin Luther King Jr., Av. Itaóca) atravessam bairros seguros e adentram comunidades. Basta o nome da rua constar na lista permitida para o app autorizar uma corrida dentro da área de risco.

### 2. Aprovação baseada em candidatos secundários fracos do `Geocoder` (`AreaEvaluator.kt`)
* **Evidência:** A consulta `getFromLocationName(query, 5)` retorna até 5 hipóteses ordenadas por relevância. O loop atual aprova a oferta se *qualquer* um dos 5 resultados cair dentro da zona permitida.
* **Impacto real:** Mesmo quando o resultado principal (posição 1) aponta para um bairro distante ou de risco, um palpite residual (posição 4 ou 5) que caia dentro da área libera a corrida indevidamente.

### 3. Fallback de geocodificação sem restrição geográfica (`AreaEvaluator.kt`)
* **Evidência:** Quando a busca com caixa delimitadora inicial falha, o código executa `geocoder.getFromLocationName(query, 5)` sem limites geográficos, buscando em todo o território nacional.
* **Impacto real:** Endereços sem bairro explícito (ex.: "Rua São Pedro", "Rua Principal") podem resolver para municípios homônimos fora da Região Metropolitana do Rio de Janeiro.

### 4. Regra da Av. Brasil aplicada aos $58\text{ km}$ e falso positivo em "Marechal" (`AreaEvaluator.kt`)
* **Evidência:**
  1. A regra de paridade (lado ímpar aceita / lado par recusa, desenhada para o entorno do Complexo da Maré) não possui filtro de numeração/quilometragem, atingindo toda a extensão da Av. Brasil (inclusive trechos seguros em Campo Grande/Zona Oeste).
  2. A regex `\d{2,5}` ignora números prediais de 1 dígito (ex.: nº 8).
  3. A lista `mareSideIndicators` utiliza `contains("mare")`, fazendo com que endereços contendo **"Marechal"** (como *Marechal Hermes* ou *Praça Marechal Rondon*) sejam tratados como "Maré" e bloqueados por engano.

### 5. Varredura de termos bloqueados sobre texto bruto da tela (`RideOfferParser.kt` / `AreaEvaluator.kt`)
* **Evidência:** A checagem de palavras proibidas inspeciona blocos amplos de texto capturados da tela e aceita correspondência parcial (substring).
* **Impacto real:** Nomes de passageiros (ex.: *"Ana"*, *"Penha"*, *"Luz"*) ou palavras comuns dentro de outros termos disparam falsos bloqueios e fazem o motorista perder boas corridas.

### 6. Latência acumulada nas consultas de rede (`AreaEvaluator.kt`)
* **Evidência:** Chamadas sequenciais ao `Geocoder` sem timeout curto podem acumular até $\approx 14\text{ s}$ sob oscilação de sinal 4G/5G.
* **Impacto real:** Como Uber e 99 concedem entre $10\text{ s}$ e $15\text{ s}$ para aceite, a oferta desaparece antes de o filtro concluir a análise.

### 7. Sobrecarga de inicialização de processo no toque automático (`StandaloneTapDaemon.kt`)
* **Evidência:** Cada acionamento de toque via bridge ADB despacha uma nova invocação de comando/shell no Android.
* **Impacto real:** Adiciona centenas de milissegundos de atraso exatamente no momento crítico de disputar ou confirmar uma oferta.

### 8. Perda da trava anti-conflito Uber $\leftrightarrow$ 99 em memória (`DualRideCoordinator.kt`)
* **Evidência:** O estado de corrida ativa que impede aceitar chamadas no aplicativo concorrente fica apenas em memória RAM.
* **Impacto real:** Aparelhos Motorola aplicam gerenciamento agressivo de energia em segundo plano quando o motorista alterna para Waze ou Google Maps. Se o processo for reciclado pelo Android, a trava zera e o app pode aceitar uma segunda corrida simultânea no outro aplicativo.

### 9. Cálculo de lucro ignora o retorno vazio (`FuelCalculator.kt`)
* **Evidência:** O cálculo atual considera apenas a distância e o tempo da corrida ofertada, sem estimar o deslocamento de retorno quando o destino fica fora da área de atuação do motorista.
* **Impacto real:** Corridas que levam para pontas isoladas ou fora da zona segura parecem lucrativas no card, mas geram prejuízo líquido real ao somar combustível e tempo perdido voltando vazio.

### 10. Modo diagnóstico ativado por padrão e exposição de dados em backup (`DriverCaptureModules.kt` & `AndroidManifest.xml`)
* **Evidência:** O modo diagnóstico/Raio-X grava continuamente conteúdos de tela (incluindo nomes e endereços de passageiros) durante todo o turno, consumindo bateria e armazenamento. Adicionalmente, não há configuração explícita (`dataExtractionRules` / `fullBackupContent`) impedindo que capturas e logs sejam enviados ao backup automático do Google.

### 11. Chave de assinatura versionada no repositório (`app/release-key.jks` & `app/build.gradle.kts`)
* **Evidência:** O arquivo `app/release-key.jks` encontra-se diretamente na raiz do módulo `app/` junto às configurações de build.
* **Impacto real:** Qualquer vazamento ou compartilhamento do código-fonte compromete a identidade criptográfica do aplicativo, permitindo que terceiros assinem APKs maliciosos como se fossem atualizações oficiais.

### 12. Artefatos temporários e necessidades de ajuste visual (`app/build.gradle.kts` & UI)
* **Evidência:** Permanência da pasta espelho temporária (`_fontes_espelho`) e respectiva tarefa Gradle no build, além de oportunidades de refinamento visual nos cards, abas, tipografia e contraste para leitura rápida ao volante.

---

## Funcionalidades ausentes ou incompletas

Além da correção dos 15 pontos já mapeados, a investigação arquitetural mostra 6 lacunas importantes para blindar ainda mais o motorista frente às dinâmicas das plataformas:

1. **Perfis dinâmicos por horário e turno (Dia vs. Madrugada):** No Rio de Janeiro, o mapa de risco e a fluidez do trânsito mudam drasticamente após as $20\text{h}00$ ou $22\text{h}00$. Falta um alternador automático de perfil (ex.: polígono mais restritivo e meta mínima de $\text{R\$/h}$ maior no turno noturno/madrugada).
2. **Penalização explícita do deslocamento até o embarque (*Pickup Ratio*):** Ofertas frequentemente mascaram $4\text{ km}$ a $6\text{ km}$ de deslocamento vazio apenas para buscar o passageiro. É necessário computar separadamente a distância/tempo até o embarque no cálculo de $\text{R\$/km}$ e $\text{R\$/h}$ reais.
3. **Detecção e penalização de corridas com paradas intermediárias:** Corridas com `+1 parada` reduzem drasticamente o ganho por hora devido ao tempo de espera não remunerado. O `RideOfferParser.kt` deve identificar explicitamente esse indicador para aplicar penalidade de tempo ou recusa automática configurável.
4. **Validação cruzada de bairro/município no retorno do `Geocoder`:** Mesmo dentro da caixa delimitadora do Grande Rio, o endereço retornado pelo `Geocoder` deve ter seu `subLocality` (bairro) e `locality` (município) confrontados com listas de bairros seguros/proibidos, criando uma segunda camada de proteção contra ruas sem numeração exata.
5. **Exportação e importação local de zonas (`.json`):** Como os backups em nuvem devem excluir dados sensíveis, o motorista precisa de um botão simples para exportar/importar seus polígonos e configurações em arquivo local, facilitando troca de aparelho ou backup manual seguro.
6. **Botão de pausa rápida e destravamento manual no Overlay:** Em blitzes, túneis ou manobras complexas, o motorista precisa pausar o auto-aceite com um único toque no card flutuante — e também poder liberar manualmente a trava Uber/99 caso cancele uma corrida fora do fluxo esperado.

---

## Propostas técnicas priorizadas

Abaixo estão as propostas técnicas refinadas e ponderadas, organizadas por impacto direto na segurança e no bolso do motorista:

| ID | Proposta Técnica | Solução Recomendada (Ponderada) | Benefício Direto ao Motorista | Complexidade |
| :--- | :--- | :--- | :--- | :--- |
| **P1** | **Hierarquia Estrita GPS $>$ Nome da Rua** | Inverter a lógica em `AreaEvaluator.kt`: se o `Geocoder` localizar a coordenada do número e ela cair fora do polígono permitido (ou dentro de zona vermelha), **recusar imediatamente**. O nome da rua só decide se não houver coordenada confiável **e** a via não cruzar fronteira de risco. | Elimina o maior vetor de risco físico (ruas longas que entram em comunidades). | Média |
| **P2** | **Regra Assimétrica nos Candidatos do `Geocoder`** | Para **aceitar**, exigir que estritamente o **candidato #1** (maior relevância) esteja dentro da área permitida. Para **recusar**, manter a regra de precaução: se **qualquer** candidato válido no Grande Rio cair em área proibida, recusar. | Evita falsos aceites gerados por palpites geográficos fracos. | Baixa |
| **P3** | **Cerca Geográfica da Região Metropolitana do RJ** | Aplicar `Bounding Box` calibrado para o Grande Rio: Latitude $[-23{,}10,\; -22{,}50]$ e Longitude $[-43{,}85,\; -42{,}85]$, validando `adminArea` (RJ) e removendo o fallback irrestrito nacional. Acrescentar sufixo de estado/cidade apenas quando ausente na string original. | Impede confusão com ruas homônimas em outros estados ou cidades distantes. | Baixa |
| **P4** | **Correção Av. Brasil e Bug "Marechal"** | Atualizar regex numérica para `\d{1,5}`, normalizar acentos via `TextNormalizer.kt` e exigir fronteira de palavra `\b(mare|maré)\b`. Restringir a regra de paridade exclusivamente ao intervalo numérico da Maré validado pelo motorista. | Acaba com bloqueios errados em Marechal Hermes e reconhece números de 1 dígito. | Baixa |
| **P5** | **Filtro de Bloqueio Restrito a Endereços** | Executar a lista de termos proibidos exclusivamente sobre `pickupAddress` e `dropoffAddress` com correspondência de palavra inteira (`\b...\b`), isolando totalmente o nome do passageiro. | Evita perder corridas boas por causa do nome do passageiro. | Baixa |
| **P6** | **Orçamento de Latência ($2{,}5\text{ s}$) + Paralelismo + Cache Local** | Executar geocodificação de embarque e destino **em paralelo** (`async`) encapsulada em `withTimeoutOrNull(2500L)`. Persistir localmente as geometrias das vias ao salvar polígonos em `MapAreaActivity.kt` para contingência offline. *(Nota: não depender de interpolação numérica OSM offline, pois a cobertura de numeração no RJ é incompleta; em modo offline sem coordenada, aprovar apenas vias 100% internas ao polígono).* | Garante resposta antes de a oferta expirar, mesmo no 4G instável. | Média |
| **P7** | **Toque de Baixa Latência via Daemon Persistente / Shizuku** | Manter canal persistente aberto (stream de shell/socket Unix local em `StandaloneTapDaemon.kt` ou chamada direta via `IShizukuTapService.aidl`), eliminando a criação de processo a cada toque e adicionando trava contra duplo clique. | Reduz latência do toque de $\approx 300\text{ ms}$ para $< 15\text{ ms}$. | Média |
| **P8** | **Persistência Atômica da Trava Anti-Conflito** | Gravar o estado de corrida ativa do `DualRideCoordinator.kt` em disco de forma atômica (com timestamp e expiração máxima de segurança, ex.: $60\text{ min}$) e solicitar isenção de otimização de bateria (`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`). | Impede aceitar duas corridas ao mesmo tempo se a Motorola fechar o app em segundo plano. | Baixa |
| **P9** | **Cálculo de Retorno Vazio (*Deadhead*) no $\text{R\$/h}$ Líquido** | Quando o destino estiver fora da área de atuação, calcular a distância estimada até a borda mais próxima da zona segura (aplicando fator de tortuosidade viária $\approx 1{,}3$), descontar combustível/desgaste e somar o tempo de retorno no cálculo de $\text{R\$/h}$ líquido exibido no card. | Revela o lucro real da corrida e protege o motorista de viagens "armadilha". | Média |
| **P10** | **Painel de Resumo do Turno e Economia Gerada** | Consolidar o histórico em um resumo claro na tela inicial: lucro líquido acumulado, $\text{R\$/h}$ real por faixa horária/região, corridas perigosas evitadas e corridas deficitárias filtradas. | Dá visibilidade financeira real para o motorista ajustar suas metas. | Média |
| **P11** | **Blindagem de Privacidade, Backup e Assinatura** | Desligar modo diagnóstico por padrão (com auto-desligamento após $30\text{ min}$), configurar `dataExtractionRules` e `fullBackupContent` excluindo prints/logs do Google Backup, e remover `release-key.jks` e senhas do controle de versão. | Protege dados dos passageiros, economiza bateria e blinda a assinatura do APK. | Baixa |
| **P12** | **Limpeza do Projeto e Polimento Visual** | Remover `_fontes_espelho` e a tarefa temporária do Gradle; aplicar ajustes de interface conforme definição do motorista, priorizando legibilidade rápida ao volante e diferenciação visual (verde/vermelho) das vias no mapa. | Código limpo, compilação rápida e leitura segura durante a direção. | Baixa |

---

## Proteções de segurança, privacidade e confiabilidade

Para garantir operação robusta em produção no dispositivo pessoal do motorista, devem ser aplicadas quatro salvaguardas técnicas obrigatórias:

1. **Gestão segura de chaves de assinatura (`release-key.jks`):**
   * Remover `app/release-key.jks` do histórico/pasta rastreada e incluir `*.jks`, `*.keystore` e `keystore.properties` no `.gitignore`.
   * Carregar `storeFile`, `storePassword`, `keyAlias` e `keyPassword` em `app/build.gradle.kts` via arquivo local não versionado (`keystore.properties`) ou variáveis de ambiente.
   * *Nota de segurança:* Caso o repositório já tenha sido enviado para algum servidor remoto público com o `.jks` incluso, recomenda-se gerar uma nova chave de assinatura para distribuição futura.
2. **Minimização e retenção de dados pessoais (LGPD / Privacidade):**
   * `diagnosticMode` inicia como `false` por padrão.
   * Quando ativado manualmente para suporte, exibir aviso visual no overlay e desativar automaticamente após $30\text{ minutos}$.
   * Aplicar retenção automática (ex.: exclusão de logs diagnósticos após $24\text{ horas}$) e botão de limpeza imediata.
3. **Bloqueio de exfiltração via Backup do Android:**
   * Configurar `android:fullBackupContent="@xml/backup_rules"` (Android $\le 11$) e `android:dataExtractionRules="@xml/data_extraction_rules"` (Android $12+$) no `AndroidManifest.xml`, excluindo diretórios de capturas de tela, logs de acessibilidade e tokens de pareamento ADB.
4. **Tolerância a falhas e prevenção de travamento permanente (*Fail-Safe*):**
   * Toda trava persistida em disco (`DualRideCoordinator`) deve possuir um **TTL (Time-To-Live)** automático (ex.: $60\text{ minutos}$) e um botão físico/visual de **Destravar Agora** no overlay, garantindo que uma falha inesperada nunca impeça o motorista de trabalhar.

---

## Experiência do motorista e operação em campo

Um aplicativo usado no painel de um carro ou guidão em movimento não pode exigir leitura detalhada. As melhorias de interface devem seguir princípios de **ergonomia veicular**:

* **Leitura em menos de $1{,}5\text{ segundo}$ (`OverlayManager.kt`):**
  * Destaque visual imediato por cor de borda/fundo: **Verde** (Aprovada), **Vermelho** (Área de Risco / Bloqueada) e **Âmbar** (Fora da Meta Financeira / Retorno Vazio Alto).
  * Hierarquia tipográfica clara: **$\text{R\$/h}$ líquido real** e **Status da Região** em fonte grande e alto contraste; valores brutos, distância de busca e desconto de volta em linha secundária compacta.
* **Clareza cartográfica (`MapAreaActivity.kt` / `map_picker.html`):**
  * Ao desenhar ou editar polígonos, destacar visualmente em **verde** as vias integralmente contidas na zona segura e sinalizar em **amarelo/vermelho** as vias de fronteira que cruzam para áreas externas ou de risco.
* **Painel principal limpo (`MainActivity.kt`):**
  * Manter logs técnicos recolhidos em painel expansível (acordeão), deixando em primeiro plano apenas os controles essenciais do turno: botão Ligar/Pausar Filtro, Resumo Financeiro do Dia, Zonas Ativas e Meta de $\text{R\$/h}$.

---

## Plano de implementação por fases

Recomenda-se executar as melhorias em **4 fases incrementais**, permitindo testar cada bloco com segurança antes de avançar:

### Fase 1 — Blindagem de Segurança Física e Credenciais (Urgente)
* **Escopo:** Itens **1, 2, 3, 4, 5 e 13** (Precedência GPS $>$ Rua, candidato #1 para aceite / qualquer candidato para recusa, Bounding Box do Grande Rio, correção Av. Brasil/Maré, filtro de bloqueio apenas em endereços e remoção do `.jks`/senhas do repositório).
* **Critério de aceite:** Testes automatizados em `CoreFeaturesTest.kt` validando ruas que cruzam divisas de comunidades, endereços em "Marechal Hermes", homônimos fora do RJ e passageiros com nomes iguais a bairros bloqueados.

### Fase 2 — Velocidade, Estabilidade Motorola e Privacidade
* **Escopo:** Itens **6, 7, 8, 11 e 12** (Timeout paralelo de $2{,}5\text{ s}$ + cache local de vias, otimização do daemon de toque ADB/Shizuku, persistência atômica da trava anti-conflito + isenção de bateria, modo diagnóstico desligado por padrão e exclusão de prints/logs do backup).
* **Critério de aceite:** Tempo médio de decisão $< 2{,}5\text{ s}$ sob rede 4G simulada com latência; manutenção da trava Uber $\leftrightarrow$ 99 após encerramento forçado do processo em segundo plano.

### Fase 3 — Inteligência Financeira e Resumo do Turno
* **Escopo:** Itens **9 e 10** (Cálculo de retorno vazio/deadhead no $\text{R\$/h}$ líquido e painel de métricas acumuladas do turno).
* **Critério de aceite:** Validação em campo comparando o $\text{R\$/h}$ líquido exibido no card com o deslocamento real de retorno ao final de corridas periféricas.

### Fase 4 — Faxina de Build e Refinamento Visual
* **Escopo:** Itens **14 e 15** (Remoção de `_fontes_espelho` e da tarefa temporária no Gradle; ajustes finais de cores, fontes, cards, abas e mapa conforme preferência do motorista).
* **Critério de aceite:** Build de release limpo e aprovação visual direta do motorista em uso diurno e noturno.

---

## Decisões que exigem validação do motorista

Antes de codificar as regras definitivas das fases correspondentes, é necessário confirmar **3 definições operacionais** diretamente com você:

1. **Trecho exato da Av. Brasil para a regra de paridade (Item 4):**
   * Entre quais números prediais da Av. Brasil a regra *"lado ímpar aceita / lado par recusa"* deve valer?
   * *Referência geográfica aproximada para apoio:* o corredor entre Caju/Benfica e Penha/Trevo das Margaridas (onde se concentra o Complexo da Maré e adjacências críticas) situa-se aproximadamente entre os números **$1.000$ e $14.000$** (enquanto Realengo, Bangu e Campo Grande ficam acima de $25.000$). Confirme também se a regra vale para **embarque, destino ou ambos**.
2. **Parâmetros do cálculo de retorno vazio (Item 9):**
   * Quando uma corrida terminar fora da sua área segura, você prefere que o app calcule a volta até a **borda mais próxima da sua região** ou até um **ponto base fixo** (ex.: sua casa/bairro principal)?
   * Qual velocidade média urbana (ex.: $25\text{ km/h}$ ou $30\text{ km/h}$) devemos usar como padrão para estimar o tempo de retorno quando não houver rota online?
3. **Preferências de layout e visual (Item 15):**
   * Quais elementos visuais incomodam hoje e devem ser alterados (paleta de cores/tema escuro, tamanho das letras, formato dos cards da tela inicial, disposição das abas ou tamanho/posição do card flutuante sobre a Uber/99)?

---

## Limites da avaliação

Para manter total rigor técnico e transparência:
1. **Formato do pacote inspecionado:** O arquivo anexado nesta sessão (`uber-area-filter-4451374a97ef067cd7858ed83d6a2c959f7d2239`) foi recebido como um fluxo binário compactado (ZIP bruto), a partir do qual foram extraídos integralmente a árvore de diretórios, nomes de classes, recursos e artefatos de build/assinatura, cruzados com os trechos auditados diretamente nos arquivos-fonte (`AreaEvaluator.kt`, `StandaloneTapDaemon.kt`, `DualRideCoordinator.kt`, `app/build.gradle.kts`).
2. **Verificação recomendada antes da aplicação:** Antes de aplicar alterações em arquivos cujas linhas internas não foram transcritas integralmente em texto puro nesta sessão (como `RideOfferParser.kt`, `FuelCalculator.kt`, `DriverCaptureModules.kt` e `ShizukuTapUserService.kt`), cada arquivo deve ser lido diretamente no workspace durante a execução de cada fase para garantir compatibilidade exata de assinaturas e variáveis.
3. **Dependência externa das plataformas:** Uber e 99 atualizam periodicamente a estrutura visual de seus cards de oferta. Por isso, toda evolução no parser (`RideOfferParser.kt`) deve ser acompanhada de testes unitários em `CoreFeaturesTest.kt` com exemplos reais de telas capturadas.

---

## Fontes e evidências

* **Árvore completa e manifesto do projeto:** `uber-area-filter-4451374a97ef067cd7858ed83d6a2c959f7d2239` (estrutura completa de `app/src/main/`, `app/src/test/`, `app/build.gradle.kts`, `AndroidManifest.xml` e `app/release-key.jks`).
* **Módulos de avaliação geográfica e texto:** `app/src/main/java/com/ruan/uberareafilter/engine/AreaEvaluator.kt`, `TextNormalizer.kt`, `RideOfferParser.kt` e `map/OpenFreeMapStreetFetcher.kt`.
* **Módulos de coordenação, cálculo e automação de toque:** `engine/DualRideCoordinator.kt`, `engine/FuelCalculator.kt`, `adb/StandaloneTapDaemon.kt`, `adb/StandaloneAdbBridge.kt`, `shizuku/ShizukuTapBridge.kt` e `aidl/com/ruan/uberareafilter/shizuku/IShizukuTapService.aidl`.
* **Módulos de captura, interface e armazenamento:** `service/UberAccessibilityService.kt`, `driver/DriverCaptureModules.kt`, `overlay/OverlayManager.kt`, `overlay/RideCardModel.kt`, `storage/ConfigRepository.kt`, `ui/MainActivity.kt` e `ui/MapAreaActivity.kt`.
* **Especificação de requisitos e diagnóstico operacional:** Lista consolidada de 15 melhorias propostas por `@Ruan` e auditoria técnica cruzada da base de código.