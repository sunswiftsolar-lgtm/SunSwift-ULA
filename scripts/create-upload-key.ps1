# Creates the user-approved ULA upload identity. Never prints or commits its password.
$ErrorActionPreference = 'Stop'
$ulaKeyProject = Split-Path $PSScriptRoot -Parent
$ulaKeyFolder = Join-Path $ulaKeyProject '.signing'
$ulaUploadKey = Join-Path $ulaKeyFolder 'ula-upload.p12'
$ulaProtectedPassword = Join-Path $ulaKeyFolder 'upload-password.dpapi'
$ulaPublicCertificate = Join-Path $ulaKeyFolder 'upload-certificate.pem'
$ulaKeytool = Join-Path $ulaKeyProject 'toolchain\jdk-17\jdk-17.0.20.1+1\bin\keytool.exe'
if ((Test-Path -LiteralPath $ulaUploadKey) -or (Test-Path -LiteralPath $ulaProtectedPassword)) {
    throw 'A signing identity already exists. Refusing to replace it.'
}
if (-not (Test-Path -LiteralPath $ulaKeytool)) { throw 'The bundled JDK 17 key tool is missing.' }
[System.IO.Directory]::CreateDirectory($ulaKeyFolder) | Out-Null
$ulaPassword = [Convert]::ToBase64String([System.Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
$ulaSecurePassword = ConvertTo-SecureString -String $ulaPassword -AsPlainText -Force
# DPAPI encryption is bound to the Windows account that runs this script.
[System.IO.File]::WriteAllText($ulaProtectedPassword, (ConvertFrom-SecureString -SecureString $ulaSecurePassword))
function Invoke-UlaKeytool([string[]]$Arguments) {
    $ulaKeyProcessInfo = [System.Diagnostics.ProcessStartInfo]::new()
    $ulaKeyProcessInfo.FileName = $ulaKeytool
    $ulaKeyProcessInfo.WorkingDirectory = $ulaKeyProject
    $ulaKeyProcessInfo.UseShellExecute = $false
    $ulaKeyProcessInfo.Environment['ULA_NEW_KEY_PASSWORD'] = $ulaPassword
    foreach ($ulaArgument in $Arguments) { $ulaKeyProcessInfo.ArgumentList.Add($ulaArgument) }
    $ulaKeyProcess = [System.Diagnostics.Process]::Start($ulaKeyProcessInfo)
    $ulaKeyProcess.WaitForExit()
    if ($ulaKeyProcess.ExitCode -ne 0) { throw "Key tool failed with exit code $($ulaKeyProcess.ExitCode). Preserve the existing files for recovery." }
}
try {
    Invoke-UlaKeytool @('-genkeypair', '-keystore', $ulaUploadKey, '-storetype', 'PKCS12',
        '-alias', 'ula-upload', '-keyalg', 'RSA', '-keysize', '3072', '-validity', '10000',
        '-dname', 'CN=SunSwift ULA, OU=Internal Applications, O=SunSwift, C=IN',
        '-storepass:env', 'ULA_NEW_KEY_PASSWORD', '-keypass:env', 'ULA_NEW_KEY_PASSWORD', '-noprompt')
    Invoke-UlaKeytool @('-exportcert', '-rfc', '-keystore', $ulaUploadKey, '-alias', 'ula-upload',
        '-storepass:env', 'ULA_NEW_KEY_PASSWORD', '-file', $ulaPublicCertificate)
    Invoke-UlaKeytool @('-list', '-v', '-keystore', $ulaUploadKey, '-alias', 'ula-upload',
        '-storepass:env', 'ULA_NEW_KEY_PASSWORD')
    Write-Output 'Upload key created locally. Password encrypted for this Windows account; .signing is excluded from Git.'
} finally { $ulaPassword = $null; $ulaSecurePassword.Dispose() }
