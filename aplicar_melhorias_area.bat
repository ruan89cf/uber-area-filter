@echo off
setlocal
cd /d "%~dp0"

set "DEST=app\src\main\java\com\ruan\uberareafilter"
set "TESTDEST=app\src\test\java\com\ruan\uberareafilter"

echo ==========================================================
echo  Filtro Inteligente - aplicar melhorias da demarcacao de area
echo ==========================================================
echo.

if not exist "%DEST%" (
  echo ERRO: pasta de codigo nao encontrada: %DEST%
  pause
  exit /b 1
)
if not exist "_melhorias_area\main" (
  echo ERRO: pasta _melhorias_area nao encontrada.
  pause
  exit /b 1
)

if exist "_backup_antes_melhorias_area\main" (
  echo 1/3 Backup original ja existe em _backup_antes_melhorias_area - mantido sem alterar.
) else (
  echo 1/3 Backup dos fontes atuais em _backup_antes_melhorias_area ...
  xcopy "%DEST%" "_backup_antes_melhorias_area\main\" /E /I /Y /Q >nul
  xcopy "%TESTDEST%" "_backup_antes_melhorias_area\test\" /E /I /Y /Q >nul
)

echo 2/3 Copiando fontes novos ...
xcopy "_melhorias_area\main" "%DEST%\" /E /I /Y
if errorlevel 1 goto falhou
xcopy "_melhorias_area\test" "%TESTDEST%\" /E /I /Y
if errorlevel 1 goto falhou

echo 3/3 Pronto.
echo.
echo Agora rode instalar_no_celular.bat para compilar e instalar.
echo Para desfazer: copie de volta o conteudo de _backup_antes_melhorias_area.
goto fim

:falhou
echo.
echo FALHOU ao copiar. Nada foi apagado; o backup esta em _backup_antes_melhorias_area.

:fim
echo.
pause
