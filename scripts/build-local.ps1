param(
    [string[]]$Tasks = @('assembleDebug', 'lintDebug'),
    [string]$JavaPath = '',
    [string]$SocketTempDirectory = 'C:\Windows\Temp'
)
$ErrorActionPreference = 'Stop'
$ulaProjectRoot = Split-Path $PSScriptRoot -Parent
if (-not $JavaPath) {
    $ulaBundledJava = Join-Path $ulaProjectRoot 'toolchain\jdk-17\jdk-17.0.20.1+1\bin\java.exe'
    if (Test-Path -LiteralPath $ulaBundledJava) { $JavaPath = $ulaBundledJava }
    else { $JavaPath = (Get-Command java.exe -ErrorAction Stop).Source }
}
$ulaBuildInfo = [System.Diagnostics.ProcessStartInfo]::new()
$ulaBuildInfo.FileName = $JavaPath
$ulaBuildInfo.WorkingDirectory = $ulaProjectRoot
$ulaBuildInfo.UseShellExecute = $false
# Only this build and its children use these settings; no global Java settings change.
$ulaExistingOptions = $ulaBuildInfo.Environment['JAVA_TOOL_OPTIONS']
$ulaBuildInfo.Environment['JAVA_TOOL_OPTIONS'] = "$ulaExistingOptions -Djava.nio.channels.spi.SelectorProvider=sun.nio.ch.WindowsSelectorProvider -Djdk.net.unixdomain.tmpdir=`"$SocketTempDirectory`""
@('-classpath', 'gradle\wrapper\gradle-wrapper.jar', 'org.gradle.wrapper.GradleWrapperMain',
  '--gradle-user-home', '.gradle-native-home', '--no-daemon') + $Tasks | ForEach-Object {
    $ulaBuildInfo.ArgumentList.Add($_)
}
$ulaBuildProcess = [System.Diagnostics.Process]::Start($ulaBuildInfo)
$ulaBuildProcess.WaitForExit()
exit $ulaBuildProcess.ExitCode
