# Filtro de Área Uber (Android / Kotlin)

Aplicativo Android pessoal que utiliza `AccessibilityService` para ler ofertas de corrida no app **Uber Driver** (`com.ubercab.driver`), verificar se o destino/trajeto está dentro da área configurada e:
- **Dentro da área:** exibe um banner verde na tela e deixa você decidir se aceita ou não.
- **Fora da área:** recusa automaticamente a corrida (quando a opção de auto-recusa está ativada) e registra no log.

## Como abrir e compilar no Android Studio

1. Abra o **Android Studio** -> **File > Open** e selecione a pasta `uber-area-filter`.
2. Aguarde o Gradle sincronizar as dependências.
3. Conecte seu celular Android (com **Depuração USB** ativada) ou gere o APK em **Build > Build Bundle(s) / APK(s) > Build APK(s)** e instale no aparelho.

## Como configurar no celular (passo a passo)

1. Abra o app **Filtro de Área Uber**.
2. Toque em **Acessibilidade** -> encontre **Filtro de Área Uber** -> ative.
   - *Nota (Android 13+):* se o sistema bloquear por "Configuração restrita", vá em **Configurações > Apps > Filtro de Área Uber > menu (⋮) > Permitir configurações restritas**, depois volte e ative na Acessibilidade.
3. Toque em **Sobrepor Tela** e permita exibir sobre outros apps (para o banner verde/vermelho).
4. Configure sua área de duas formas (pode usar uma ou as duas juntas):
   - **Lista de Bairros / Regiões permitidas:** digite os bairros/cidades que você aceita (ex: `Pinheiros, Vila Madalena, Paulista`).
   - **Raio Geográfico (opcional):** ative a chave, digite um endereço/bairro central, toque em **Buscar coordenadas do centro** e defina o raio em km.
5. **Teste primeiro sem auto-recusa:** deixe **Recusar automaticamente** desligado e ative o **Modo diagnóstico** para confirmar no log que os textos e o botão de recusa da sua versão do Uber Driver estão sendo lidos corretamente. Depois ative a auto-recusa.

## Aviso importante

- Funciona apenas em **Android** (o iOS não permite que um app leia a tela ou clique em outro app).
- O uso de automação de cliques no app da Uber pode violar os Termos de Uso da plataforma; use com moderação e sempre teste primeiro apenas com o aviso visual.
