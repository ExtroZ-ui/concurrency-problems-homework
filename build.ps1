param(
    [ValidateSet('Build', 'Run', 'Verify', 'Capture')][string]$Action = 'Run',
    [Parameter(ValueFromRemainingArguments = $true)][string[]]$AppArgs = @()
)
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
$taskJdk = $null
if ($env:JAVA_HOME -and (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin\javac.exe'))) { $taskJdk = $env:JAVA_HOME }
if (-not $taskJdk) {
    $compiler = Get-Command javac.exe -ErrorAction SilentlyContinue
    if ($compiler) { $taskJdk = Split-Path (Split-Path $compiler.Source) }
}
if (-not $taskJdk) {
    $jdkFolder = Join-Path $env:USERPROFILE '.jdks'
    if (Test-Path -LiteralPath $jdkFolder) {
        $taskJdk = Get-ChildItem -LiteralPath $jdkFolder -Directory | Sort-Object LastWriteTime -Descending |
            Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'bin\javac.exe') } |
            Select-Object -First 1 -ExpandProperty FullName
    }
}
if (-not $taskJdk) { throw 'JDK 21+ required. Set JAVA_HOME or use IntelliJ to install a JDK.' }
$javac = Join-Path $taskJdk 'bin\javac.exe'
$java = Join-Path $taskJdk 'bin\java.exe'
New-Item -ItemType Directory -Force -Path 'build\classes', 'evidence' | Out-Null
$sources = @(Get-ChildItem -LiteralPath 'src\main\java' -Filter '*.java' -Recurse | Select-Object -ExpandProperty FullName)
& $javac --release 21 -encoding UTF-8 -d build/classes @sources
if ($LASTEXITCODE -ne 0) { throw 'Compilation failed' }
if ($Action -eq 'Build') { Write-Output 'Build completed'; exit 0 }
if ($Action -eq 'Capture') {
    $jstack = Join-Path $taskJdk 'bin\jstack.exe'
    if (-not (Test-Path -LiteralPath $jstack)) { throw 'jstack.exe missing in this JDK' }
    $taskProcess = $null
    try {
        # Only this child process is terminated below. A visible console is unnecessary.
        $taskProcess = Start-Process -FilePath $java -ArgumentList @('-cp', 'build/classes', 'ru.homework.concurrency.Main', 'deadlock') -WorkingDirectory $PSScriptRoot -WindowStyle Hidden -RedirectStandardOutput (Join-Path $PSScriptRoot 'evidence\deadlock-run.txt') -RedirectStandardError (Join-Path $PSScriptRoot 'evidence\deadlock-error.txt') -PassThru
        $taskReady = $false
        for ($attempt = 0; $attempt -lt 100; $attempt++) {
            if ($taskProcess.HasExited) { throw 'Deadlock process exited before capture' }
            $taskLog = Get-Content -LiteralPath 'evidence\deadlock-run.txt' -Raw -ErrorAction SilentlyContinue
            if ($taskLog -match 'READY_FOR_JSTACK') { $taskReady = $true; break }
            Start-Sleep -Milliseconds 100
        }
        if (-not $taskReady) { throw 'Timeout waiting for detected deadlock' }
        Write-Output ('Running jstack -l ' + $taskProcess.Id)
        $taskDump = @(& $jstack -l $taskProcess.Id 2>&1)
        if ($LASTEXITCODE -ne 0) { throw ('jstack failed: ' + ($taskDump -join [Environment]::NewLine)) }
        $taskDump | Set-Content -LiteralPath 'evidence\deadlock-jstack.txt' -Encoding UTF8
        if (($taskDump -join [Environment]::NewLine) -notmatch 'Found one Java-level deadlock') { throw 'Dump does not contain expected deadlock diagnosis' }
        Write-Output 'PASS: actual jstack dump saved to evidence/deadlock-jstack.txt'
    } finally {
        if ($taskProcess -and -not $taskProcess.HasExited) {
            Stop-Process -Id $taskProcess.Id -Force
            $taskProcess.WaitForExit()
            Write-Output ('Stopped only captured child PID=' + $taskProcess.Id)
        }
    }
} else {
    if ($Action -eq 'Verify') { $AppArgs = @('verify') }
    & $java -cp build/classes ru.homework.concurrency.Main @AppArgs
    if ($LASTEXITCODE -ne 0) { throw 'Scenario or verification failed' }
}
