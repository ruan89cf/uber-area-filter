@echo off
setlocal
cd /d "%~dp0"

rem Usa o JDK que vem com o Android Studio. O Java padrao do Windows aqui e so um JRE (sem compilador).
if exist "C:\Program Files\Android\Android Studio\jbr\bin\javac.exe" set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"

echo ==========================================================
echo  Filtro Inteligente - compilar e instalar no celular (USB)
echo ==========================================================
echo.
echo Compilando... isso pode levar alguns minutos. Nao feche esta janela.
echo O resultado completo fica gravado em build_log.txt
echo.

call gradlew.bat installRelease > build_log.txt 2>&1
if not errorlevel 1 goto ok

echo A versao release nao instalou. Tentando a versao debug...
call gradlew.bat installDebug >> build_log.txt 2>&1
if not errorlevel 1 goto ok

echo.
echo FALHOU. O motivo esta no fim do arquivo build_log.txt
echo Nada foi desinstalado do celular.
goto fim

:ok
echo.
echo INSTALADO no celular. Abra o Filtro Inteligente para conferir.

:fim
echo.
pause
