param([string[]]$Tasks = @('bundleRelease', 'assembleRelease', 'lintRelease'))
$ErrorActionPreference = 'Stop'
$ulaReleaseProject = Split-Path $PSScriptRoot -Parent
$ulaReleaseKey = Join-Path $ulaReleaseProject '.signing\ula-upload.p12'
$ulaReleasePasswordFile = Join-Path $ulaReleaseProject '.signing\upload-password.dpapi'
if (-not (Test-Path -LiteralPath $ulaReleaseKey) -or -not (Test-Path -LiteralPath $ulaReleasePasswordFile)) {
    throw 'The approved local upload key is missing. Do not create a replacement identity automatically.'
}
$ulaReleaseSecret = ConvertTo-SecureString -String ([System.IO.File]::ReadAllText($ulaReleasePasswordFile))
$ulaReleaseCredential = [System.Management.Automation.PSCredential]::new('ula-upload', $ulaReleaseSecret)
$ulaReleaseProcessInfo = [System.Diagnostics.ProcessStartInfo]::new()
$ulaReleaseProcessInfo.FileName = Join-Path $ulaReleaseProject 'toolchain\jdk-17\jdk-17.0.20.1+1\bin\java.exe'
$ulaReleaseProcessInfo.WorkingDirectory = $ulaReleaseProject
$ulaReleaseProcessInfo.UseShellExecute = $false
$ulaReleaseProcessInfo.Environment['ULA_UPLOAD_STORE_FILE'] = $ulaReleaseKey
$ulaReleaseProcessInfo.Environment['ULA_UPLOAD_STORE_PASSWORD'] = $ulaReleaseCredential.GetNetworkCredential().Password
$ulaReleaseProcessInfo.Environment['ULA_UPLOAD_KEY_ALIAS'] = 'ula-upload'
$ulaReleaseProcessInfo.Environment['ULA_UPLOAD_KEY_PASSWORD'] = $ulaReleaseCredential.GetNetworkCredential().Password
$ulaReleaseProcessInfo.Environment['JAVA_TOOL_OPTIONS'] = '-Djava.nio.channels.spi.SelectorProvider=sun.nio.ch.WindowsSelectorProvider -Djdk.net.unixdomain.tmpdir="C:\Windows\Temp"'
foreach ($ulaReleaseArgument in (@('-classpath', 'gradle\wrapper\gradle-wrapper.jar',
    'org.gradle.wrapper.GradleWrapperMain', '--gradle-user-home', '.gradle-native-home', '--no-daemon',
    '--no-parallel', '--max-workers=1',
    "-PulaNativeBuildDir=$(Join-Path ([System.IO.Path]::GetTempPath()) 'SunSwift-ULA-native')") + $Tasks)) {
    $ulaReleaseProcessInfo.ArgumentList.Add($ulaReleaseArgument)
}
try {
    $ulaReleaseProcess = [System.Diagnostics.Process]::Start($ulaReleaseProcessInfo)
    $ulaReleaseProcess.WaitForExit()
    exit $ulaReleaseProcess.ExitCode
} finally { $ulaReleaseSecret.Dispose() }
