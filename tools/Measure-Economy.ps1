param(
    [Parameter(Mandatory=$true)][ValidatePattern('^[a-zA-Z0-9_-]{1,20}$')][string]$Label,
    [ValidateRange(1,5)][int]$Repetitions = 2,
    [ValidateSet('empty','idle4','active4','idle16','active16','history4')][string[]]$Scenarios = @('empty','idle4','active4','idle16','active16','history4'),
    [switch]$NoDetails,
    [ValidateRange(0,6000)][int]$SaveInterval = 0
)
$ErrorActionPreference = 'Stop'
$workspace = Split-Path -Parent $PSScriptRoot
foreach ($round in 1..$Repetitions) {
    foreach ($scenario in $Scenarios) {
        $runName = 'p_' + $Label + '_' + $scenario + '_' + $round
        $directory = Join-Path $workspace ('build/task-e-gametest-' + $runName)
        if (Test-Path -LiteralPath $directory) { throw "Refusing to reuse an existing world: $directory" }
        $log = Join-Path $workspace ('build/task-p-' + $Label + '-' + $scenario + '-' + $round + '.log')
        Write-Output "Starting $runName"
        # PowerShell 5.1 treats native stderr (including ordinary JVM notices) as ErrorRecord.
        $ErrorActionPreference = 'Continue'
        $details = if ($NoDetails) { 'false' } else { 'true' }
        & (Join-Path $workspace 'gradlew.bat') runGameTestServer "-PveGameTestRun=$runName" -PveGameTestMode=perf "-PvePerfScenario=$scenario" "-PvePerfLabel=$Label" "-PvePerfDetails=$details" "-PvePerfSaveInterval=$SaveInterval" --console=plain *> $log
        $code = $LASTEXITCODE
        $ErrorActionPreference = 'Stop'
        if ($code -ne 0) { throw "GameTest failed with exit $code; inspect $log" }
        $result = Get-Content -LiteralPath (Join-Path $directory 'performance.json') -Raw | ConvertFrom-Json
        if ($result.serverTick.count -ne 1300 -or (!$NoDetails -and $result.sample.timings.'context.tick'.count -ne 1300)) { throw 'Unaligned or incomplete sample' }
        Write-Output ($runName + ': ' + ($result.serverTick | ConvertTo-Json -Compress) + '; TPS=' + $result.observedTps)
    }
}
