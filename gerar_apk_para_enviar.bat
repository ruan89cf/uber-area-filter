@echo off
setlocal EnableExtensions
cd /d "%~dp0"

rem Usa o JDK que vem com o Android Studio.
if exist "C:\Program Files\Android\Android Studio\jbr\bin\javac.exe" set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"

echo ==========================================================
echo  Filtro Inteligente - gerar o APK para enviar a motoristas
echo ==========================================================
echo.
echo Gerando o arquivo... isso pode levar alguns minutos.
echo O resultado completo fica em build_log.txt
echo.

call gradlew.bat assembleRelease > build_log.txt 2>&1
if errorlevel 1 goto falhou

set "APK=app\build\outputs\apk\release\app-release.apk"
if not exist "%APK%" goto sem_assinatura

if not exist "apk_para_enviar" mkdir "apk_para_enviar"
copy /y "%APK%" "apk_para_enviar\FiltroInteligente.apk" >nul
copy /y "COMO_INSTALAR.txt" "apk_para_enviar\COMO_INSTALAR.txt" >nul 2>&1

echo PRONTO. A pasta apk_para_enviar vai abrir com:
echo   FiltroInteligente.apk  - o app (mande pelo WhatsApp como DOCUMENTO, ou pelo Drive)
echo   COMO_INSTALAR.txt      - o texto para colar junto na conversa
echo.
echo Importante: sempre gere com este mesmo PC (mesma assinatura). Assim a
echo pessoa atualiza por cima, sem perder as zonas e configuracoes.
start "" explorer "apk_para_enviar"
goto fim

:sem_assinatura
echo.
echo O APK saiu SEM assinatura (falta keystore.properties ou release-key.jks).
echo Sem assinatura o celular nao instala. Confira esses dois arquivos na pasta do projeto.
goto fim

:falhou
echo.
echo FALHOU. O motivo esta no fim do arquivo build_log.txt

:fim
echo.
pause
