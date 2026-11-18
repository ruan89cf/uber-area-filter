# Filtro Inteligente (99 Motorista e Uber Driver)

App Android em Kotlin que lê as ofertas de corrida da 99 e da Uber pela Acessibilidade e aplica as suas regras: área demarcada no mapa, ganho por hora, distância, passageiro e combustível. Ofertas dentro dos critérios podem ser aceitas sozinhas; as de fora, recusadas.

## Instalar e configurar

Veja **[GUIA_INSTALACAO.md](GUIA_INSTALACAO.md)**, escrito para quem nunca mexeu em modo desenvolvedor. No app, o **Assistente de configuração** mostra o estado real de cada recurso e abre a tela certa para cada passo.

## Scripts (Windows)

| Script | O que faz |
|---|---|
| `instalar_no_celular.bat` | Compila, instala pelo cabo USB e já libera pelo cabo as permissões que dispensam passos no celular |
| `gerar_apk_para_enviar.bat` | Gera `apk_para_enviar\FiltroInteligente.apk` e o texto `COMO_INSTALAR.txt` para mandar a outros motoristas |
| `aplicar_melhorias_area.bat` | Copia os fontes novos de `_melhorias_area` para `app\src` |

A assinatura do APK fica em `keystore.properties` e `release-key.jks` (fora do git). Gere sempre com a mesma assinatura, para que as atualizações instalem por cima.

## Avisos

- Só funciona no Android: o iPhone não permite que um app leia a tela de outro.
- Automatizar toques na Uber ou na 99 pode contrariar os termos de uso das plataformas. Teste primeiro só com o aviso visual (aceite e recusa automáticos desligados).
- Decisões técnicas: `DECISOES_MELHORIAS_AREA.md` (demarcação de área) e `DESIGN_VISUAL.md` (visual).
