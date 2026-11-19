# Sobe o codigo novo do servidor para a Lambda (filtro-api, sa-east-1).
# Uso:  powershell -File server\deploy.ps1
$ErrorActionPreference = 'Stop'
$S = Split-Path -Parent $MyInvocation.MyCommand.Path
$zip = Join-Path $env:TEMP 'filtro-lambda.zip'
if (Test-Path $zip) { Remove-Item $zip }
Compress-Archive -Path "$S\lambda\index.mjs", "$S\lambda\store.mjs", "$S\lambda\admin.html" -DestinationPath $zip
& aws lambda update-function-code --region sa-east-1 --function-name filtro-api --zip-file "fileb://$zip" --query 'LastModified' --output text
& aws lambda wait function-updated --region sa-east-1 --function-name filtro-api
Write-Output 'Servidor atualizado.'
