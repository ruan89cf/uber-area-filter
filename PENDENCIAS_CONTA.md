# Conta, servidor e assinatura: o que já está pronto e o que falta

## Pronto
- **Servidor (AWS, São Paulo):** `https://d07z412uu9.execute-api.sa-east-1.amazonaws.com`
  - Lambda `filtro-api`, banco DynamoDB `filtro-inteligente`, API `filtro-api`.
  - Login (Google ou código de acesso), teste de 7 dias (só conta nova **e** celular novo), licença assinada (vale 6 h, só no celular da conta), cupons (dias ou vitalício), bloqueio de conta/celular, um celular por conta, atualização obrigatória/opcional, trânsito (a chave da AWS fica só no servidor), trava de APK modificado.
  - Atualizar o servidor depois de mudar o código: `powershell -File server\deploy.ps1`
- **Site de administração:** `https://d07z412uu9.execute-api.sa-east-1.amazonaws.com/admin`
  - Senha: arquivo `server/secrets/admin_token.txt` (fora do git; guarde num lugar seguro).
  - Usuários (+30 dias, vitalício, bloquear, liberar troca de celular), celulares, cupons, códigos de acesso, atualização obrigatória.
- **App 3.0:** sem licença válida não abre nem filtra. Tela "Minha conta" (ícone de pessoa no topo): plano, dias de teste, cupom, assinar, sair. Código ofuscado (R8).

## Falta (com você)

### 1. Nome do app e do pacote
Escolher o nome. O pacote (`com.ruan.uberareafilter`) deve ser trocado **antes** de publicar na Play Store (depois não muda). Me diga o nome que eu troco no app.

### 2. Login com Google (Google Cloud Console)
1. console.cloud.google.com → criar projeto → **Tela de consentimento OAuth** (Externo, nome do app, seu e-mail).
2. **Credenciais → Criar → ID do cliente OAuth → Aplicativo da Web** → copie o ID (`...apps.googleusercontent.com`).
3. **Credenciais → Criar → ID do cliente OAuth → Android**: pacote do app + SHA-1:
   `B1:A1:42:1C:94:11:C6:A5:4D:BC:5F:4A:A7:30:A8:31:F0:C9:40:94`
   (depois de publicar, adicione também o SHA-1 da "chave de assinatura do app" que o Play Console mostra).
4. Me mande o **ID da Web** (não é segredo). Eu coloco em `google.properties` (app) e em `GOOGLE_CLIENT_IDS` (servidor).

### 3. Play Console (publicação e assinatura)
1. Conta de desenvolvedor (US$ 25, uma vez) e verificação de identidade.
2. Criar o app, enviar o pacote (`gradlew bundleRelease`) para teste interno.
3. Declarar o uso de **Acessibilidade** (formulário + vídeo mostrando o app filtrando corridas).
4. **Produtos → Assinaturas:** `plano_mensal` (R$ 24,90) e `plano_anual`.
5. **Play App Signing:** o Google reassina o app. Me passe o **SHA-256** do "certificado da chave de assinatura do app" para eu trocar a trava de APK modificado no servidor (`APK_CERT_SHA256`). Sem isso, a versão da Play Store seria tratada como modificada.
6. **Conta de serviço** para o servidor conferir as compras: Google Cloud → IAM → Contas de serviço → criar → chave JSON; no Play Console → Usuários e permissões → convidar o e-mail dessa conta com acesso a "Ver dados financeiros / gerenciar pedidos e assinaturas". Me mande o JSON (eu guardo só no servidor).

### 4. Segurança da AWS (importante)
Apague a **chave de acesso root** (`AKIAX3W5...`) no console: IAM → Credenciais de segurança → Chaves de acesso → Excluir. Ela está no histórico da conversa. Para mexer no servidor depois, crie um usuário IAM só para isso.

## Fica para a próxima entrega
- Mover a **decisão das corridas** (zonas, regras, Negocia) para o servidor. Hoje ela roda no celular, protegida pela licença.
- Avisos de renovação/cancelamento do Google em tempo real (notificações do Play para o servidor).
