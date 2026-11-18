@echo off
setlocal EnableExtensions
cd /d "%~dp0"

rem Usa o JDK que vem com o Android Studio. O Java padrao do Windows aqui e so um JRE (sem compilador).
if exist "C:\Program Files\Android\Android Studio\jbr\bin\javac.exe" set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"

set "PKG=com.ruan.uberareafilter"

echo ==========================================================
echo  Filtro Inteligente - compilar e instalar no celular (USB)
echo ==========================================================
echo.
echo Antes: celular ligado no cabo USB, com "Depuracao USB" ligada
echo nas Opcoes do desenvolvedor. Se o celular perguntar "Permitir
echo depuracao USB?", marque "Sempre permitir" e toque em Permitir.
echo.
echo 1/3 Compilando e instalando... isso pode levar alguns minutos.
echo     Nao feche esta janela. O resultado fica em build_log.txt
echo.

call gradlew.bat installRelease > build_log.txt 2>&1
if not errorlevel 1 goto instalado

echo A versao release nao instalou. Tentando a versao debug...
call gradlew.bat installDebug >> build_log.txt 2>&1
if not errorlevel 1 goto instalado

echo.
echo FALHOU. O motivo esta no fim do arquivo build_log.txt
echo Nada foi desinstalado do celular.
goto fim

:instalado
echo 2/3 INSTALADO no celular.
echo.

call :achar_adb
if not defined ADB goto sem_adb

rem Ja que o cabo esta ligado, libera de uma vez o que no celular levaria varios toques:
rem  - WRITE_SECURE_SETTINGS: o app liga a propria Acessibilidade e religa o motor depois de reiniciar
rem  - ACCESS_RESTRICTED_SETTINGS: tira o aviso "Configuracao restrita" (Android 13+)
rem  - deviceidle: bateria sem restricao (o Android nao fecha o filtro no meio do turno)
rem  - POST_NOTIFICATIONS: notificacoes (codigo de pareamento e alertas)
echo 3/3 Liberando permissoes pelo cabo (economiza varios passos no celular)...
echo ---- permissoes via adb ---->> build_log.txt
"%ADB%" -d shell pm grant %PKG% android.permission.WRITE_SECURE_SETTINGS >> build_log.txt 2>&1
"%ADB%" -d shell appops set %PKG% ACCESS_RESTRICTED_SETTINGS allow >> build_log.txt 2>&1
"%ADB%" -d shell cmd deviceidle whitelist +%PKG% >> build_log.txt 2>&1
"%ADB%" -d shell pm grant %PKG% android.permission.POST_NOTIFICATIONS >> build_log.txt 2>&1
"%ADB%" -d shell am start -n %PKG%/.ui.MainActivity >nul 2>&1
echo.
echo PRONTO. No celular, o Filtro abriu sozinho.
echo Toque em "Assistente de configuracao": o leitor de corridas liga
echo com o botao "Ligar automaticamente". Para a Uber, siga o passo
echo "Toque automatico na Uber" (pareamento de 3 minutos, uma vez so).
goto fim

:sem_adb
echo 3/3 (adb nao encontrado: tudo bem, siga o "Assistente de configuracao" no celular)

:fim
echo.
pause
exit /b 0

rem ------------------------------------------------------------------
rem Acha o adb.exe: pasta do SDK do local.properties, pasta padrao do Android Studio ou PATH
rem ------------------------------------------------------------------
:achar_adb
set "ADB="
set "SDKDIR="
if exist "local.properties" (
  for /f "usebackq tokens=1,* delims==" %%a in ("local.properties") do (
    if /i "%%a"=="sdk.dir" set "SDKDIR=%%b"
  )
)
if not defined SDKDIR goto adb_padrao
rem local.properties guarda C\:\\Users\\... : desfaz o escape
set "SDKDIR=%SDKDIR:\\=\%"
set "SDKDIR=%SDKDIR:\:=:%"
if exist "%SDKDIR%\platform-tools\adb.exe" set "ADB=%SDKDIR%\platform-tools\adb.exe"
:adb_padrao
if not defined ADB if exist "%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe" set "ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
if not defined ADB (
  where adb >nul 2>&1
  if not errorlevel 1 set "ADB=adb"
)
exit /b 0
